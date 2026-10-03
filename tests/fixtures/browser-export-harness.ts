import type { ExportPage, ExportPageRequest, ExportSource, ProjectionName } from '../../src/core/export/types';
import { BROWSER_BATCH, browserRideStore, type BrowserRide, type RideRow } from '../../src/services/browser-ride-store';
import { cleanBrowserExports, openBrowserExportSink } from '../../src/services/browser-export-sink';
import { browserRow } from '../support/browser-ride';
export { crc32 } from '../../src/core/export/crc32';
export { ExportError } from '../../src/core/export/types';
export { ensureBrowserDistance } from '../../src/services/browser-distance-store';
export { browserTransaction, idbRequest } from '../../src/services/browser-ride-store';
export { BrowserExportSource, browserExportSource } from '../../src/services/browser-export-source';
export { BROWSER_EXPORT_MEMORY_LIMIT } from '../../src/services/browser-export-sink';
export { browserRideStore as store, browserRow, cleanBrowserExports, openBrowserExportSink };

const OWNER = 'export-owner';
const START = Date.UTC(2026, 0, 1);
export const at = (seconds: number) => new Date(START + seconds * 1000).toISOString();

export type SeedRow = Partial<Omit<RideRow, 'recordingId' | 'sequence'>> & { elapsedSeconds: number };
export type SeedStep =
  { rows: SeedRow[] } | { action: 'pause' | 'resume' | 'lap' | 'save'; elapsed: number; timer?: number };

export async function seedRide(steps: SeedStep[]): Promise<BrowserRide> {
  let record = await browserRideStore.begin(OWNER, true, at(0));
  let sequence = 0;
  for (const step of steps) {
    if ('action' in step) {
      record = await browserRideStore.transition(
        record.id,
        OWNER,
        step.action,
        step.elapsed,
        step.timer ?? record.timerSeconds,
        at(step.elapsed),
      );
      continue;
    }
    for (let start = 0; start < step.rows.length; start += BROWSER_BATCH) {
      const rows = step.rows.slice(start, start + BROWSER_BATCH).map(values => ({
        ...browserRow(values.elapsedSeconds, sequence, at(values.elapsedSeconds)),
        ...values,
        recordingId: record.id,
        sequence: sequence++,
      }));
      const last = rows[rows.length - 1]!;
      record = await browserRideStore.append(
        record.id,
        OWNER,
        rows,
        last.elapsedSeconds,
        record.timerSeconds,
        last.timestamp,
      );
    }
  }
  return record;
}

export async function storedRows(id: string): Promise<RideRow[]> {
  const rows: RideRow[] = [];
  for (let after: [number, number] | undefined; ;) {
    const page = await browserRideStore.page(id, 0, Infinity, after);
    rows.push(...page);
    if (!page.length) return rows;
    after = [page[page.length - 1]!.elapsedSeconds, page[page.length - 1]!.sequence];
  }
}

export function plain(page: ExportPage) {
  const columns: Record<string, (number | string | null)[]> = {};
  const buffers: Record<string, [number, number, number]> = {};
  for (const [name, values] of Object.entries(page.columns)) {
    if (values instanceof Float64Array) {
      columns[name] = Array.from(values);
      buffers[name] = [values.byteOffset, values.byteLength, values.buffer.byteLength];
    } else columns[name] = values as (string | null)[];
  }
  return { rows: page.rows, last: page.last, done: page.done, connections: page.connections, columns, buffers };
}

export async function pages(
  source: ExportSource,
  session: string,
  projection: ProjectionName,
): Promise<ReturnType<typeof plain>[]> {
  const result: ReturnType<typeof plain>[] = [];
  let after: ExportPageRequest['after'] = null;
  for (;;) {
    const page: ExportPage = await source.page({ session, projection, after });
    result.push(plain(page));
    if (page.done) return result;
    after = page.last;
  }
}

export async function failure(work: () => Promise<unknown>): Promise<{ code?: string; message: string } | null> {
  try {
    await work();
    return null;
  } catch (error) {
    return { code: (error as { code?: string }).code, message: (error as Error).message };
  }
}

const getDirectory = StorageManager.prototype.getDirectory;
export async function exportsDirectory(): Promise<FileSystemDirectoryHandle> {
  return (await getDirectory.call(navigator.storage)).getDirectoryHandle('exports', { create: true });
}
export function withoutOpfs(): void {
  Object.defineProperty(navigator.storage, 'getDirectory', { configurable: true, value: undefined });
}

export interface Entry {
  path: string;
  kind: 'file' | 'directory';
  size?: number;
  lastModified?: number;
}
export async function listing(directory?: FileSystemDirectoryHandle, prefix = ''): Promise<Entry[]> {
  const entries: Entry[] = [];
  for await (const entry of (directory ?? (await exportsDirectory())).values()) {
    const path = prefix + entry.name;
    if (entry.kind === 'file') {
      const file = await entry.getFile();
      entries.push({ path, kind: 'file', size: file.size, lastModified: file.lastModified });
    } else entries.push({ path, kind: 'directory' }, ...(await listing(entry, `${path}/`)));
  }
  return entries.sort((a, b) => a.path.localeCompare(b.path));
}

export async function readExport(path: string): Promise<string> {
  const parts = path.split('/');
  let directory = await exportsDirectory();
  for (const part of parts.slice(0, -1)) directory = await directory.getDirectoryHandle(part);
  const file = await (await directory.getFileHandle(parts[parts.length - 1]!)).getFile();
  return base64(new Uint8Array(await file.arrayBuffer()));
}

export async function leaveFile(name: string, size: number): Promise<void> {
  const handle = await (await exportsDirectory()).getFileHandle(name, { create: true });
  const writable = await handle.createWritable();
  await writable.write(new Uint8Array(size));
  await writable.close();
}

export function base64(bytes: Uint8Array): string {
  let binary = '';
  for (let i = 0; i < bytes.length; i += 0x8000) binary += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
  return btoa(binary);
}

export function fillRandom(bytes: Uint8Array<ArrayBuffer>): Uint8Array<ArrayBuffer> {
  for (let i = 0; i < bytes.length; i += 65_536) crypto.getRandomValues(bytes.subarray(i, i + 65_536));
  return bytes;
}
export const randomBytes = (length: number) => fillRandom(new Uint8Array(length));

interface Gate {
  open(): void;
  readonly opened: Promise<void>;
}
function gate(): Gate {
  let open!: () => void;
  const opened = new Promise<void>(resolve => (open = resolve));
  return { open, opened };
}

export async function cleanDuringCommit(hold: 'afterFolderCheck' | 'everyAttempt') {
  const before = new Set((await listing()).map(entry => entry.path));
  const sink = await openBrowserExportSink('zip', { exportedAt: at(0), platform: 'web' });
  await sink.write(new TextEncoder().encode('committed during cleanup'));
  const staged = (await listing()).find(entry => !before.has(entry.path) && entry.path.endsWith('.part'))!;
  const uuid = staged.path.slice('.powerlog-export-'.length, -'.part'.length);
  const files = FileSystemFileHandle.prototype as unknown as {
    move(parent: FileSystemDirectoryHandle, name: string): Promise<void>;
  };
  const directories = FileSystemDirectoryHandle.prototype as unknown as { values(): unknown };
  const locks = LockManager.prototype as unknown as { request(name: string, ...rest: unknown[]): Promise<unknown> };
  const { move } = files;
  const { values } = directories;
  const { request } = locks;
  const moving = gate();
  const moved = gate();
  const attempted = gate();
  const granted = gate();
  let checked = false;
  files.move = async function (this: FileSystemFileHandle, parent: FileSystemDirectoryHandle, name: string) {
    if (parent.name === uuid) {
      moving.open();
      await moved.opened;
    }
    return move.call(this, parent, name);
  };
  directories.values = function (this: FileSystemDirectoryHandle) {
    if (this.name === uuid) checked = true;
    return values.call(this);
  };
  locks.request = function (this: LockManager, name: string, ...rest: unknown[]) {
    const options = rest.length > 1 ? (rest[0] as LockOptions) : undefined;
    if (name.includes(uuid) && options?.ifAvailable && (hold === 'everyAttempt' || checked)) {
      attempted.open();
      return granted.opened.then(() => request.call(this, name, ...rest));
    }
    return request.call(this, name, ...rest);
  };
  try {
    const committing = sink.commit('committed.zip');
    await moving.opened;
    const cleaning = cleanBrowserExports();
    await Promise.race([attempted.opened, cleaning]);
    moved.open();
    await committing;
    granted.open();
    await cleaning;
  } finally {
    files.move = move;
    directories.values = values;
    locks.request = request;
    moved.open();
    granted.open();
  }
  return {
    uuid,
    entries: await listing(),
    content: await readExport(`${uuid}/committed.zip`).catch(() => null),
  };
}

export async function longestStall<T>(work: () => Promise<T>): Promise<{ result: T; stallMs: number }> {
  let last = performance.now(),
    stallMs = 0,
    running = true;
  const tick = () => {
    const now = performance.now();
    stallMs = Math.max(stallMs, now - last);
    last = now;
    if (running) setTimeout(tick, 0);
  };
  setTimeout(tick, 0);
  await new Promise(resolve => setTimeout(resolve, 20));
  try {
    const result = await work();
    // The ticker's pending timer runs before this one, so it records the stall that ended with `work`.
    await new Promise(resolve => setTimeout(resolve, 0));
    return { result, stallMs };
  } finally {
    running = false;
  }
}
