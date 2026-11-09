import { crc32 } from '../../modules/power-log/src/exports/crc32';
import { BROWSER_EXPORT_MEMORY_LIMIT, cleanExports, openSink } from '../../modules/power-log/src/exports/sink.web';
import type { FileKind } from '../../modules/power-log/src/exports/types';

export { BROWSER_EXPORT_MEMORY_LIMIT, crc32 };
export type FileKindName = FileKind;

const START = Date.UTC(2026, 0, 1);
const at = (seconds: number) => new Date(START + seconds * 1000).toISOString();

export const openBrowserExportSink = (kind: FileKind, _context?: unknown) => openSink(kind);
export const cleanBrowserExports = (now?: number) => cleanExports(now);

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
