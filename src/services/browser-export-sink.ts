import { crc32 } from '../core/export/crc32';
import {
  ExportError,
  type ExportCommitResult,
  type ExportDeflateResult,
  type ExportFileKind,
  type ExportSink,
  type OpenExportSink,
} from '../core/export/types';
import { downloadFile } from './files.web';

export const BROWSER_EXPORT_MEMORY_LIMIT = 128 * 1024 * 1024;
const DIRECTORY = 'exports';
const KEEP_MS = 24 * 60 * 60 * 1000;
// Also matches the `.crswap` file Chromium keeps beside a staging file while its writer is open.
const STAGING = /^\.powerlog-export-([0-9a-f-]{36})\.part/;
const COMMITTED = /^[0-9a-f-]{36}$/;
const DEFLATE_SLICE = 256 * 1024;
const SLICE_MS = 40;
const BLOCK = 1024 * 1024;
const MIME: Record<ExportFileKind, string> = {
  fit: 'application/octet-stream',
  zip: 'application/zip',
  csv: 'text/csv;charset=utf-8',
};

type MovableFileHandle = FileSystemFileHandle & {
  move(parent: FileSystemDirectoryHandle, name: string): Promise<void>;
};
interface ExportFile {
  readonly size: number;
  append(bytes: Uint8Array): Promise<void>;
  patch(offset: number, bytes: Uint8Array): Promise<void>;
  publish(name: string): Promise<void>;
  data(): Promise<Blob>;
  discard(): Promise<void>;
}

const lockName = (uuid: string) => `power-log-export:${uuid}`;
const owned = (bytes: Uint8Array) => bytes as Uint8Array<ArrayBuffer>;
const cancelled = () => new ExportError('cancelled', 'The export was cancelled.');

function sinkError(error: unknown): ExportError {
  if (error instanceof ExportError) return error;
  if (error instanceof Error && error.name === 'QuotaExceededError')
    return new ExportError(
      'sink',
      "There isn't enough browser storage for this export. Free some space and try again.",
    );
  return new ExportError(
    'sink',
    `Power Log couldn't write the export file.${error instanceof Error ? ` ${error.message}` : ''}`,
  );
}

// Continuous work is measured from the first slice after the event loop last ran a timer task.
let busySince: number | null = null;
async function relax(): Promise<void> {
  const now = performance.now();
  if (busySince === null) {
    busySince = now;
    setTimeout(() => {
      busySince = null;
    }, 0);
  } else if (now - busySince >= SLICE_MS) await new Promise<void>(resolve => setTimeout(resolve, 0));
}

class DeflateStage {
  crc = 0;
  input = 0;
  output = 0;
  private failure: unknown;
  private readonly writer: WritableStreamDefaultWriter<BufferSource>;
  private readonly reader: ReadableStreamDefaultReader<Uint8Array<ArrayBuffer>>;
  private readonly drained: Promise<void>;
  constructor(
    readonly start: number,
    emit: (bytes: Uint8Array) => Promise<void>,
  ) {
    const stream = new CompressionStream('deflate-raw');
    this.writer = stream.writable.getWriter();
    this.reader = stream.readable.getReader();
    this.drained = this.drain(emit);
    this.drained.catch(() => {});
  }
  private async drain(emit: (bytes: Uint8Array) => Promise<void>): Promise<void> {
    try {
      while (true) {
        const { done, value } = await this.reader.read();
        if (done) return;
        await emit(value);
        this.output += value.length;
      }
    } catch (error) {
      this.failure ??= error;
      await this.reader.cancel(error).catch(() => {});
      throw error;
    }
  }
  async write(bytes: Uint8Array): Promise<void> {
    for (let offset = 0; offset < bytes.length; offset += DEFLATE_SLICE) {
      await relax();
      const slice = bytes.subarray(offset, offset + DEFLATE_SLICE);
      this.crc = crc32(slice, this.crc);
      this.input += slice.length;
      try {
        await this.writer.write(owned(slice));
      } catch (error) {
        throw this.failure ?? error;
      }
    }
  }
  async finish(): Promise<ExportDeflateResult> {
    try {
      await this.writer.close();
      await this.drained;
    } catch (error) {
      throw this.failure ?? error;
    }
    return { crc32: this.crc, inputBytes: this.input, outputBytes: this.output };
  }
  cancel(reason: unknown): void {
    this.failure ??= reason;
    void this.reader.cancel(reason).catch(() => {});
  }
}

class OpfsFile implements ExportFile {
  size = 0;
  private tail: Promise<void> = Promise.resolve();
  private discarded = false;
  constructor(
    private readonly directory: FileSystemDirectoryHandle,
    private readonly handle: MovableFileHandle,
    private readonly writable: FileSystemWritableFileStream,
    private readonly uuid: string,
    private readonly release: () => void,
  ) {}
  private serial(work: () => Promise<void>): Promise<void> {
    if (this.discarded) return Promise.reject(cancelled());
    const next = this.tail.then(work);
    this.tail = next.catch(() => {});
    return next;
  }
  append(bytes: Uint8Array): Promise<void> {
    const position = this.size;
    this.size += bytes.length;
    return this.serial(() => this.writable.write({ type: 'write', position, data: owned(bytes) }));
  }
  patch(offset: number, bytes: Uint8Array): Promise<void> {
    return this.serial(() => this.writable.write({ type: 'write', position: offset, data: owned(bytes) }));
  }
  async publish(name: string): Promise<void> {
    await this.serial(() => this.writable.close());
    const folder = await this.directory.getDirectoryHandle(this.uuid, { create: true });
    await this.handle.move(folder, name);
    this.release();
  }
  data(): Promise<Blob> {
    return this.handle.getFile();
  }
  async discard(): Promise<void> {
    this.discarded = true;
    await this.tail;
    await this.writable.abort().catch(() => {});
    await this.directory.removeEntry(this.handle.name).catch(() => {});
    await this.directory.removeEntry(this.uuid, { recursive: true }).catch(() => {});
    this.release();
  }
}

class MemoryFile implements ExportFile {
  size = 0;
  private blocks: Uint8Array<ArrayBuffer>[] = [];
  private copy(offset: number, bytes: Uint8Array): void {
    for (let done = 0; done < bytes.length;) {
      const at = offset + done;
      const index = Math.floor(at / BLOCK);
      while (this.blocks.length <= index) this.blocks.push(new Uint8Array(BLOCK));
      const count = Math.min(BLOCK - (at % BLOCK), bytes.length - done);
      this.blocks[index]!.set(bytes.subarray(done, done + count), at % BLOCK);
      done += count;
    }
  }
  async append(bytes: Uint8Array): Promise<void> {
    if (this.size + bytes.length > BROWSER_EXPORT_MEMORY_LIMIT)
      throw new ExportError(
        'limit',
        'This export is larger than 128 MiB, the most this browser can save without file storage. Your ride remains saved.',
      );
    this.copy(this.size, bytes);
    this.size += bytes.length;
  }
  async patch(offset: number, bytes: Uint8Array): Promise<void> {
    this.copy(offset, bytes);
  }
  async publish(): Promise<void> {}
  async data(): Promise<Blob> {
    const parts = this.blocks.map((block, index) => block.subarray(0, Math.min(BLOCK, this.size - index * BLOCK)));
    this.blocks = [];
    return new Blob(parts);
  }
  async discard(): Promise<void> {
    this.blocks = [];
  }
}

class BrowserExportSink implements ExportSink {
  private phase: 'open' | 'committed' | 'aborted' | 'failed' = 'open';
  private stage: DeflateStage | null = null;
  private busy: Promise<unknown> | null = null;
  private aborting?: Promise<void>;
  constructor(
    private readonly file: ExportFile,
    private readonly kind: ExportFileKind,
  ) {}

  write(bytes: Uint8Array): Promise<void> {
    return this.run(() => (this.stage ? this.stage.write(bytes) : this.file.append(bytes)));
  }

  writeAt(offset: number, bytes: Uint8Array): Promise<void> {
    return this.run(async () => {
      const end = this.stage ? this.stage.start : this.file.size;
      if (!Number.isSafeInteger(offset) || offset < 0 || offset + bytes.length > end)
        throw new ExportError('sink', 'The export tried to change bytes it has not written.');
      await this.file.patch(offset, bytes);
    });
  }

  beginDeflate(): Promise<void> {
    return this.run(async () => {
      if (this.stage) throw new ExportError('sink', 'A compressed export entry is already open.');
      this.stage = new DeflateStage(this.file.size, bytes => this.file.append(bytes));
    });
  }

  endDeflate(): Promise<ExportDeflateResult> {
    return this.run(async () => {
      if (!this.stage) throw new ExportError('sink', 'No compressed export entry is open.');
      const result = await this.stage.finish();
      this.stage = null;
      return result;
    });
  }

  commit(name: string): Promise<ExportCommitResult> {
    return this.run(async () => {
      if (this.stage) throw new ExportError('sink', 'Finish the compressed export entry before saving the file.');
      if (!/^[^\\/\p{Cc}]{1,255}$/u.test(name) || name === '.' || name === '..')
        throw new ExportError('sink', 'The export file name is not valid.');
      await this.file.publish(name);
      this.phase = 'committed';
      const uri = URL.createObjectURL(new Blob([await this.file.data()], { type: MIME[this.kind] }));
      downloadFile(uri, name);
      return { uri };
    });
  }

  abort(): Promise<void> {
    return (this.aborting ??= this.discard());
  }

  private async discard(): Promise<void> {
    if (this.phase === 'committed') return;
    this.phase = 'aborted';
    this.stage?.cancel(cancelled());
    await this.busy?.catch(() => {});
    // A commit already in flight publishes before the abort could take effect.
    if (this.phase !== 'aborted') return;
    this.stage = null;
    await this.file.discard();
  }

  private async run<T>(work: () => Promise<T>): Promise<T> {
    if (this.phase === 'aborted') throw cancelled();
    if (this.phase === 'committed') throw new ExportError('sink', 'This export file is already saved.');
    if (this.phase === 'failed') throw new ExportError('sink', 'This export stopped after an earlier write failed.');
    if (this.busy) throw new ExportError('sink', 'Export writes must not overlap.');
    const running = work();
    this.busy = running;
    try {
      return await running;
    } catch (error) {
      if (this.phase === 'open') this.phase = 'failed';
      throw sinkError(error);
    } finally {
      this.busy = null;
    }
  }
}

function hold(uuid: string): Promise<() => void> {
  return new Promise((resolve, reject) => {
    navigator.locks.request(lockName(uuid), () => new Promise<void>(release => resolve(release))).catch(reject);
  });
}

async function unlessHeld(uuid: string, work: () => Promise<void>): Promise<void> {
  await navigator.locks.request(lockName(uuid), { ifAvailable: true }, async lock => {
    if (lock) await work();
  });
}

async function expired(folder: FileSystemDirectoryHandle, now: number): Promise<boolean> {
  for await (const entry of folder.values()) {
    if (entry.kind !== 'file' || now - (await entry.getFile()).lastModified <= KEEP_MS) return false;
  }
  return true;
}

async function clean(directory: FileSystemDirectoryHandle, now: number): Promise<void> {
  const entries: (FileSystemDirectoryHandle | FileSystemFileHandle)[] = [];
  try {
    for await (const entry of directory.values()) entries.push(entry);
  } catch {
    return;
  }
  for (const entry of entries) {
    const staging = STAGING.exec(entry.name)?.[1];
    const uuid = staging ?? (COMMITTED.test(entry.name) ? entry.name : undefined);
    if (!uuid) continue;
    try {
      // Every check runs under the export's lock, which a commit holds until its file is in place.
      await unlessHeld(uuid, async () => {
        if (staging && entry.kind === 'file') await directory.removeEntry(entry.name);
        else if (!staging && entry.kind === 'directory' && (await expired(entry, now)))
          await directory.removeEntry(entry.name, { recursive: true });
      });
    } catch {
      // An entry that another tab still writes or already removed is left for a later cleanup.
    }
  }
}

async function exportDirectory(): Promise<FileSystemDirectoryHandle | null> {
  if (
    typeof navigator === 'undefined' ||
    typeof navigator.storage?.getDirectory !== 'function' ||
    !navigator.locks ||
    typeof FileSystemFileHandle === 'undefined' ||
    typeof FileSystemFileHandle.prototype.createWritable !== 'function' ||
    typeof (FileSystemFileHandle.prototype as Partial<MovableFileHandle>).move !== 'function'
  )
    return null;
  try {
    return await (await navigator.storage.getDirectory()).getDirectoryHandle(DIRECTORY, { create: true });
  } catch {
    return null;
  }
}

async function stagingFile(directory: FileSystemDirectoryHandle): Promise<OpfsFile> {
  await clean(directory, Date.now());
  const uuid = crypto.randomUUID();
  const release = await hold(uuid);
  const name = `.powerlog-export-${uuid}.part`;
  try {
    const handle = (await directory.getFileHandle(name, { create: true })) as MovableFileHandle;
    return new OpfsFile(directory, handle, await handle.createWritable(), uuid, release);
  } catch (error) {
    await directory.removeEntry(name).catch(() => {});
    release();
    throw error;
  }
}

export async function cleanBrowserExports(now = Date.now()): Promise<void> {
  const directory = await exportDirectory();
  if (directory) await clean(directory, now);
}

export const openBrowserExportSink: OpenExportSink = async kind => {
  if (kind !== 'fit' && kind !== 'zip' && kind !== 'csv')
    throw new ExportError('unsupported', 'Choose a FIT, ride-data or CSV export.');
  const directory = await exportDirectory();
  try {
    return new BrowserExportSink(directory ? await stagingFile(directory) : new MemoryFile(), kind);
  } catch (error) {
    throw sinkError(error);
  }
};
