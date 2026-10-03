import { execFileSync } from 'node:child_process';
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { crc32, deflateRawSync, inflateRawSync } from 'node:zlib';
import { projectionColumns } from '../../../../src/core/export/pages';
import type {
  ExportCommitResult,
  ExportConnection,
  ExportContext,
  ExportDeflateResult,
  ExportOpenRequest,
  ExportOpenResult,
  ExportPage,
  ExportPageRequest,
  ExportSink,
  ExportSource,
  ProjectionName,
} from '../../../../src/core/export/types';

export type Cell = number | string | null;
export type Row = Readonly<Record<string, Cell>>;
export type Identity = Omit<ExportConnection, 'token'>;

export interface CanonicalRide {
  readonly rideId: string;
  readonly context: ExportContext;
  readonly open: Omit<ExportOpenResult, 'session'>;
  readonly identities?: Readonly<Record<string, Identity>>;
  readonly rows: Partial<Record<ProjectionName, readonly Row[]>>;
}

export const FIXTURES = 'tests/fixtures/export/zip';
export const ENTRY_NAMES = ['telemetry.csv', 'gps.csv', 'health.csv', 'events.csv', 'datapackage.json'] as const;

const settle = () => new Promise<void>(resolve => setTimeout(resolve, 0));

export function canonicalPage<P extends ProjectionName>(
  ride: CanonicalRide,
  projection: P,
  start: number,
  size: number,
  seen?: Set<string>,
): ExportPage<P> {
  const all = ride.rows[projection] ?? [];
  const rows = all.slice(start, start + size);
  const columns: Record<string, Float64Array | (string | null)[]> = {};
  const expected = projectionColumns(projection, ride.context.platform, 'zip');
  for (const row of rows)
    for (const name of Object.keys(row))
      if (!expected.some(column => column.name === name))
        throw new Error(`Fixture ${projection} row names ${name}, which ${ride.context.platform} does not deliver`);
  if (rows.length > 0)
    for (const column of expected) {
      if (column.type === 'number')
        columns[column.name] = Float64Array.from(rows, row => {
          const value = row[column.name] ?? null;
          if (typeof value === 'string') throw new Error(`Fixture ${projection}.${column.name} must be numeric`);
          return value === null ? NaN : value;
        });
      else
        columns[column.name] = rows.map(row => {
          const value = row[column.name] ?? null;
          if (typeof value === 'number') throw new Error(`Fixture ${projection}.${column.name} must be text`);
          return value;
        });
    }
  const connections: ExportConnection[] = [];
  if (projection === 'telemetry' && seen)
    for (const row of rows) {
      const token = row.connection;
      if (typeof token !== 'string' || seen.has(token)) continue;
      seen.add(token);
      const identity = ride.identities?.[token];
      if (!identity) throw new Error(`Fixture connection ${token} has no identity`);
      connections.push({ token, ...identity });
    }
  const end = start + rows.length;
  return {
    rows: rows.length,
    last: rows.length ? [end] : null,
    done: end >= all.length,
    ...(projection === 'telemetry' ? { connections } : {}),
    columns,
  } as ExportPage<P>;
}

export interface SourceHooks {
  beforePage?(request: ExportPageRequest, count: number): void | Promise<void>;
}

export class MemorySource implements ExportSource {
  readonly requests: ExportPageRequest[] = [];
  readonly opened: ExportOpenRequest[] = [];
  readonly closed: string[] = [];
  maxInFlight = 0;
  private inFlight = 0;
  private readonly seen = new Map<ProjectionName, Set<string>>();

  constructor(
    readonly ride: CanonicalRide,
    readonly pageSize = 4096,
    private readonly hooks: SourceHooks = {},
  ) {}

  async open(request: ExportOpenRequest): Promise<ExportOpenResult> {
    this.opened.push(request);
    await settle();
    return { session: 'canonical-session', ...this.ride.open };
  }

  async page<P extends ProjectionName>(request: ExportPageRequest<P>): Promise<ExportPage<P>> {
    this.requests.push(request);
    this.inFlight++;
    this.maxInFlight = Math.max(this.maxInFlight, this.inFlight);
    try {
      await settle();
      await this.hooks.beforePage?.(request, this.requests.length);
      if (request.session !== 'canonical-session') throw new Error('Unknown session');
      if (request.after === null) this.seen.set(request.projection, new Set());
      const start = request.after === null ? 0 : request.after[0]!;
      return canonicalPage(this.ride, request.projection, start, this.pageSize, this.seen.get(request.projection));
    } finally {
      this.inFlight--;
    }
  }

  async close(session: string): Promise<void> {
    this.closed.push(session);
  }
}

export interface SinkHooks {
  beforeWrite?(index: number): void | Promise<void>;
  commit?(name: string): void | Promise<void>;
}

export class MemorySink implements ExportSink {
  readonly calls: string[] = [];
  readonly violations: string[] = [];
  committed: string | null = null;
  aborted = false;
  maxInFlight = 0;
  writes = 0;
  private bytes = new Uint8Array(1 << 16);
  private length = 0;
  private inFlight = 0;
  private stage: { start: number; input: Uint8Array[] } | null = null;

  constructor(private readonly hooks: SinkHooks = {}) {}

  get file(): Uint8Array {
    return this.bytes.slice(0, this.length);
  }

  get busy(): boolean {
    return this.inFlight > 0;
  }

  write(bytes: Uint8Array): Promise<void> {
    return this.run('write', async () => {
      const copy = bytes.slice();
      const index = this.writes++;
      await this.hooks.beforeWrite?.(index);
      await settle();
      if (!Buffer.from(copy).equals(Buffer.from(bytes)))
        this.violations.push(`write ${index} changed before it settled`);
      if (this.stage) this.stage.input.push(copy);
      else this.append(copy);
    });
  }

  writeAt(offset: number, bytes: Uint8Array): Promise<void> {
    return this.run('writeAt', async () => {
      const end = this.stage ? this.stage.start : this.length;
      if (!Number.isSafeInteger(offset) || offset < 0 || offset + bytes.length > end)
        throw new Error('writeAt outside the written bytes');
      this.bytes.set(bytes, offset);
    });
  }

  beginDeflate(): Promise<void> {
    return this.run('beginDeflate', async () => {
      if (this.stage) throw new Error('A deflate stage is already open');
      this.stage = { start: this.length, input: [] };
    });
  }

  endDeflate(): Promise<ExportDeflateResult> {
    return this.run('endDeflate', async () => {
      const stage = this.stage;
      if (!stage) throw new Error('No deflate stage is open');
      this.stage = null;
      const input = Buffer.concat(stage.input);
      const output = deflateRawSync(input, { level: 5 });
      this.append(output);
      return { crc32: crc32(input), inputBytes: input.length, outputBytes: output.length };
    });
  }

  commit(name: string): Promise<ExportCommitResult> {
    return this.run('commit', async () => {
      if (this.stage) throw new Error('Commit inside a deflate stage');
      await this.hooks.commit?.(name);
      this.committed = name;
      return { uri: `memory://${name}` };
    });
  }

  async abort(): Promise<void> {
    this.calls.push('abort');
    if (this.inFlight > 0) this.violations.push('abort while a call was in flight');
    if (this.committed !== null) this.violations.push('abort after commit');
    this.aborted = true;
  }

  private async run<T>(name: string, work: () => Promise<T>): Promise<T> {
    this.calls.push(name);
    if (this.aborted || this.committed !== null) this.violations.push(`${name} after the sink closed`);
    this.inFlight++;
    this.maxInFlight = Math.max(this.maxInFlight, this.inFlight);
    try {
      return await work();
    } finally {
      this.inFlight--;
    }
  }

  private append(bytes: Uint8Array): void {
    if (this.length + bytes.length > this.bytes.length) {
      const grown = new Uint8Array(Math.max(this.bytes.length * 2, this.length + bytes.length));
      grown.set(this.bytes.subarray(0, this.length));
      this.bytes = grown;
    }
    this.bytes.set(bytes, this.length);
    this.length += bytes.length;
  }
}

export interface ZipEntry {
  readonly name: string;
  readonly flags: number;
  readonly method: number;
  readonly versionNeeded: number;
  readonly time: number;
  readonly date: number;
  readonly crc: number;
  readonly size: number;
  readonly compressedSize: number;
  readonly offset: number;
  readonly centralExtra: Uint8Array;
  readonly localExtra: Uint8Array;
  readonly localSizes: readonly [number, number];
  readonly data: Uint8Array;
}

const u16 = (bytes: Uint8Array, at: number) => bytes[at]! | (bytes[at + 1]! << 8);
const u32 = (bytes: Uint8Array, at: number) => (u16(bytes, at) + u16(bytes, at + 2) * 0x10000) >>> 0;
const u64 = (bytes: Uint8Array, at: number) => u32(bytes, at) + u32(bytes, at + 4) * 0x100000000;

export function readZip(file: Uint8Array): ZipEntry[] {
  const end = file.length - 22;
  if (u32(file, end) !== 0x06054b50) throw new Error('No end of central directory record');
  const count = u16(file, end + 10);
  let at = u32(file, end + 16);
  const entries: ZipEntry[] = [];
  for (let i = 0; i < count; i++) {
    if (u32(file, at) !== 0x02014b50) throw new Error('Bad central header');
    const nameLength = u16(file, at + 28);
    const extraLength = u16(file, at + 30);
    const name = new TextDecoder('utf-8', { fatal: true }).decode(file.subarray(at + 46, at + 46 + nameLength));
    const offset = u32(file, at + 42);
    const compressedSize = u32(file, at + 20);
    const local = offset;
    if (u32(file, local) !== 0x04034b50) throw new Error('Bad local header');
    const localName = u16(file, local + 26);
    const localExtraLength = u16(file, local + 28);
    const localExtra = file.slice(local + 30 + localName, local + 30 + localName + localExtraLength);
    const dataStart = local + 30 + localName + localExtraLength;
    const data = inflateRawSync(file.subarray(dataStart, dataStart + compressedSize));
    entries.push({
      name,
      flags: u16(file, at + 8),
      method: u16(file, at + 10),
      versionNeeded: u16(file, at + 6),
      time: u16(file, at + 12),
      date: u16(file, at + 14),
      crc: u32(file, at + 16),
      size: u32(file, at + 24),
      compressedSize,
      offset,
      centralExtra: file.slice(at + 46 + nameLength, at + 46 + nameLength + extraLength),
      localExtra,
      localSizes: [u64(localExtra, 4), u64(localExtra, 12)],
      data: new Uint8Array(data),
    });
    if (crc32(data) !== u32(file, at + 16)) throw new Error(`${name} fails its CRC-32`);
    at += 46 + nameLength + extraLength + u16(file, at + 32);
  }
  return entries;
}

export const entryText = (entry: ZipEntry) => new TextDecoder('utf-8', { fatal: true }).decode(entry.data);

export function expectedEntries(family: string): Record<(typeof ENTRY_NAMES)[number], string> {
  const read = (name: string) => readFileSync(join(FIXTURES, family, name), 'utf8');
  const table = (name: string) => (JSON.parse(read(`${name}.json`)) as string[]).map(record => `${record}\n`).join('');
  return {
    'telemetry.csv': table('telemetry.csv'),
    'gps.csv': table('gps.csv'),
    'health.csv': table('health.csv'),
    'events.csv': table('events.csv'),
    'datapackage.json': read('datapackage.json'),
  };
}

export interface VerifiedEntry {
  readonly name: string;
  readonly flags: number;
  readonly method: number;
  readonly versionNeeded: number;
  readonly versionMadeBy: number;
  readonly dosTime: number;
  readonly dosDate: number;
  readonly dateTime: number[];
  readonly crc: number;
  readonly size: number;
  readonly compressedSize: number;
  readonly offset: number;
  readonly records?: number;
  readonly text: string;
}

export function verifyWithPython(file: Uint8Array): { entries: VerifiedEntry[]; zip64End: boolean } {
  const directory = mkdtempSync(join(tmpdir(), 'power-log-zip-'));
  try {
    const path = join(directory, 'export.zip');
    writeFileSync(path, file);
    const output = execFileSync('python3', ['tests/export/verify_zip.py', path], {
      encoding: 'utf8',
      stdio: ['ignore', 'pipe', 'pipe'],
    });
    return JSON.parse(output) as { entries: VerifiedEntry[]; zip64End: boolean };
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
}
