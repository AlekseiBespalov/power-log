import type { ByteOutput } from './tables/text';
import { ExportError, type ExportDeflateResult, type ExportSink } from './types';

const ZIP64_LIMIT = 0xffffffff;
const LOCAL_SIGNATURE = 0x04034b50;
const CENTRAL_SIGNATURE = 0x02014b50;
const END_SIGNATURE = 0x06054b50;
const ZIP64_END_SIGNATURE = 0x06064b50;
const ZIP64_LOCATOR_SIGNATURE = 0x07064b50;
const ZIP64_TAG = 0x0001;
const VERSION = 45;
const UTF8_NAMES = 0x0800;
const DEFLATED = 8;
const LOCAL_BYTES = 30;
const LOCAL_EXTRA_BYTES = 20;
const CENTRAL_BYTES = 46;
const END_BYTES = 22;
const ZIP64_END_BYTES = 56;
const ZIP64_LOCATOR_BYTES = 20;
const UTC_TIME = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.\d{3})?(?:Z|\+00:00)$/;
const encoder = new TextEncoder();

export interface ZipTime {
  readonly time: number;
  readonly date: number;
}

interface Entry {
  readonly name: Uint8Array;
  readonly offset: number;
  crc: number;
  size: number;
  compressed: number;
}

class Bytes {
  readonly bytes: Uint8Array;
  private readonly view: DataView;
  private at = 0;

  constructor(length: number) {
    this.bytes = new Uint8Array(length);
    this.view = new DataView(this.bytes.buffer);
  }

  u16(value: number): this {
    this.view.setUint16(this.at, value, true);
    this.at += 2;
    return this;
  }

  u32(value: number): this {
    this.view.setUint32(this.at, value, true);
    this.at += 4;
    return this;
  }

  u64(value: number): this {
    this.view.setUint32(this.at, value % 0x100000000, true);
    this.view.setUint32(this.at + 4, Math.floor(value / 0x100000000), true);
    this.at += 8;
    return this;
  }

  append(bytes: Uint8Array): this {
    this.bytes.set(bytes, this.at);
    this.at += bytes.length;
    return this;
  }
}

export function zipTime(exportedAt: string): ZipTime {
  const match = typeof exportedAt === 'string' ? UTC_TIME.exec(exportedAt) : null;
  const notUtc = () => new ExportError('unsupported', 'The export time is not a UTC timestamp.');
  if (!match) throw notUtc();
  const [year, month, day, hour, minute, second] = match.slice(1, 7).map(Number) as [
    number,
    number,
    number,
    number,
    number,
    number,
  ];
  const calendar = new Date(0);
  calendar.setUTCFullYear(year, month - 1, day);
  const valid =
    calendar.getUTCFullYear() === year && calendar.getUTCMonth() === month - 1 && calendar.getUTCDate() === day;
  if (!valid || hour > 23 || minute > 59 || second > 59) throw notUtc();
  if (year < 1980) return { time: 0, date: (1 << 5) | 1 };
  if (year > 2107) return { time: (23 << 11) | (59 << 5) | 29, date: (127 << 9) | (12 << 5) | 31 };
  return { time: (hour << 11) | (minute << 5) | (second >> 1), date: ((year - 1980) << 9) | (month << 5) | day };
}

const sinkFailure = (problem: string) => new ExportError('sink', `The export file could not be written: ${problem}.`);

function checkDeflate(result: ExportDeflateResult, input: number): void {
  if (typeof result !== 'object' || result === null) throw sinkFailure('the compressor reported no result');
  if (result.inputBytes !== input) throw sinkFailure('the compressor did not receive every byte');
  if (!Number.isSafeInteger(result.outputBytes) || result.outputBytes < 0)
    throw sinkFailure('the compressor reported an invalid size');
  if (!Number.isSafeInteger(result.crc32) || result.crc32 < 0 || result.crc32 > 0xffffffff)
    throw sinkFailure('the compressor reported an invalid checksum');
}

export class ZipWriter implements ByteOutput {
  private readonly entries: Entry[] = [];
  private entry: Entry | null = null;
  private pending: Promise<void> | null = null;
  private position = 0;
  private input = 0;

  constructor(
    private readonly sink: ExportSink,
    private readonly time: ZipTime,
  ) {}

  get offset(): number {
    return this.position;
  }

  async begin(name: string): Promise<void> {
    if (this.entry) throw new Error('A ZIP entry is already open');
    const entry: Entry = { name: encoder.encode(name), offset: this.position, crc: 0, size: 0, compressed: 0 };
    const header = this.local(entry);
    await this.settle();
    await this.sink.write(header);
    this.position += header.length;
    await this.sink.beginDeflate();
    this.entry = entry;
    this.input = 0;
  }

  // Resolves once the previous write has settled and this one is in flight, so a caller can prepare the next buffer
  // while the sink still owns this one; at most one write is ever outstanding.
  async write(bytes: Uint8Array): Promise<void> {
    if (!this.entry) throw new Error('No ZIP entry is open');
    if (bytes.length === 0) return;
    await this.settle();
    this.input += bytes.length;
    const write = this.sink.write(bytes);
    write.catch(() => undefined);
    this.pending = write;
  }

  async end(): Promise<void> {
    const entry = this.entry;
    if (!entry) throw new Error('No ZIP entry is open');
    await this.settle();
    const result = await this.sink.endDeflate();
    checkDeflate(result, this.input);
    entry.crc = result.crc32;
    entry.size = this.input;
    entry.compressed = result.outputBytes;
    this.entry = null;
    this.position += result.outputBytes;
    await this.sink.writeAt(entry.offset, this.local(entry));
    this.entries.push(entry);
  }

  async finish(): Promise<void> {
    if (this.entry) throw new Error('A ZIP entry is still open');
    await this.settle();
    const tail = this.directory();
    await this.sink.write(tail);
    this.position += tail.length;
  }

  async settle(): Promise<void> {
    const pending = this.pending;
    this.pending = null;
    if (pending) await pending;
  }

  private local(entry: Entry): Uint8Array {
    const { time, date } = this.time;
    return new Bytes(LOCAL_BYTES + entry.name.length + LOCAL_EXTRA_BYTES)
      .u32(LOCAL_SIGNATURE)
      .u16(VERSION)
      .u16(UTF8_NAMES)
      .u16(DEFLATED)
      .u16(time)
      .u16(date)
      .u32(entry.crc)
      .u32(ZIP64_LIMIT)
      .u32(ZIP64_LIMIT)
      .u16(entry.name.length)
      .u16(LOCAL_EXTRA_BYTES)
      .append(entry.name)
      .u16(ZIP64_TAG)
      .u16(16)
      .u64(entry.size)
      .u64(entry.compressed).bytes;
  }

  private directory(): Uint8Array {
    const start = this.position;
    const extras = this.entries.map(entry =>
      [entry.size, entry.compressed, entry.offset].filter(value => value >= ZIP64_LIMIT),
    );
    const extraBytes = extras.map(extra => (extra.length ? 4 + 8 * extra.length : 0));
    let centralBytes = 0;
    this.entries.forEach((entry, index) => (centralBytes += CENTRAL_BYTES + entry.name.length + extraBytes[index]!));
    const zip64 = start >= ZIP64_LIMIT;
    const out = new Bytes(centralBytes + (zip64 ? ZIP64_END_BYTES + ZIP64_LOCATOR_BYTES : 0) + END_BYTES);
    const { time, date } = this.time;
    const clamp = (value: number) => Math.min(value, ZIP64_LIMIT);
    this.entries.forEach((entry, index) => {
      const extra = extras[index]!;
      out
        .u32(CENTRAL_SIGNATURE)
        .u16(VERSION)
        .u16(VERSION)
        .u16(UTF8_NAMES)
        .u16(DEFLATED)
        .u16(time)
        .u16(date)
        .u32(entry.crc)
        .u32(clamp(entry.compressed))
        .u32(clamp(entry.size))
        .u16(entry.name.length)
        .u16(extraBytes[index]!)
        .u16(0)
        .u16(0)
        .u16(0)
        .u32(0)
        .u32(clamp(entry.offset))
        .append(entry.name);
      if (extra.length) out.u16(ZIP64_TAG).u16(8 * extra.length);
      for (const value of extra) out.u64(value);
    });
    const count = this.entries.length;
    if (zip64)
      out
        .u32(ZIP64_END_SIGNATURE)
        .u64(ZIP64_END_BYTES - 12)
        .u16(VERSION)
        .u16(VERSION)
        .u32(0)
        .u32(0)
        .u64(count)
        .u64(count)
        .u64(centralBytes)
        .u64(start)
        .u32(ZIP64_LOCATOR_SIGNATURE)
        .u32(0)
        .u64(start + centralBytes)
        .u32(1);
    return out.u32(END_SIGNATURE).u16(0).u16(0).u16(count).u16(count).u32(centralBytes).u32(clamp(start)).u16(0).bytes;
  }
}
