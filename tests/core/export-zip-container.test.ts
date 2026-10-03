import { describe, expect, it } from 'vitest';
import {
  ExportError,
  type ExportCommitResult,
  type ExportDeflateResult,
  type ExportErrorCode,
  type ExportSink,
} from '../../src/core/export/types';
import { ZipWriter, zipTime, type ZipTime } from '../../src/core/export/zip';

const LIMIT = 0xffffffff;
const TIME: ZipTime = { time: 0x4b6f, date: 0x5c6e };
const BLOCK = new Uint8Array(65536);

interface Recorded {
  readonly offset: number;
  readonly bytes: Uint8Array;
}

class SparseSink implements ExportSink {
  size = 0;
  readonly writes: Recorded[] = [];
  readonly patches: Recorded[] = [];
  readonly calls: string[] = [];
  private input: number | null = null;
  private start = 0;

  constructor(private readonly outputs: number[]) {}

  async write(bytes: Uint8Array): Promise<void> {
    if (this.input !== null) {
      this.input += bytes.length;
      return;
    }
    this.calls.push('write');
    this.writes.push({ offset: this.size, bytes: bytes.slice() });
    this.size += bytes.length;
  }

  async writeAt(offset: number, bytes: Uint8Array): Promise<void> {
    this.calls.push('writeAt');
    if (offset + bytes.length > (this.input === null ? this.size : this.start)) throw new Error('writeAt past the end');
    this.patches.push({ offset, bytes: bytes.slice() });
  }

  async beginDeflate(): Promise<void> {
    this.calls.push('beginDeflate');
    this.input = 0;
    this.start = this.size;
  }

  async endDeflate(): Promise<ExportDeflateResult> {
    this.calls.push('endDeflate');
    const inputBytes = this.input!;
    this.input = null;
    const outputBytes = this.outputs.shift()!;
    this.size += outputBytes;
    return { crc32: 0xc0ffee00 + this.calls.length, inputBytes, outputBytes };
  }

  commit(): Promise<ExportCommitResult> {
    throw new Error('unused');
  }

  abort(): Promise<void> {
    throw new Error('unused');
  }

  at(offset: number): Uint8Array {
    const patch = [...this.patches].reverse().find(item => item.offset === offset);
    const written = this.writes.find(item => item.offset === offset);
    return (patch ?? written)!.bytes;
  }

  get tail(): Recorded {
    return this.writes[this.writes.length - 1]!;
  }
}

const u16 = (bytes: Uint8Array, at: number) => bytes[at]! | (bytes[at + 1]! << 8);
const u32 = (bytes: Uint8Array, at: number) => (u16(bytes, at) + u16(bytes, at + 2) * 0x10000) >>> 0;
const u64 = (bytes: Uint8Array, at: number) => u32(bytes, at) + u32(bytes, at + 4) * 0x100000000;

async function feed(zip: ZipWriter, bytes: number): Promise<void> {
  for (let left = bytes; left > 0; left -= BLOCK.length)
    await zip.write(BLOCK.subarray(0, Math.min(left, BLOCK.length)));
}

async function archive(entries: { name: string; input: number }[], outputs: number[]) {
  const sink = new SparseSink(outputs);
  const zip = new ZipWriter(sink, TIME);
  const offsets: number[] = [];
  for (const entry of entries) {
    offsets.push(zip.offset);
    await zip.begin(entry.name);
    await feed(zip, entry.input);
    await zip.end();
  }
  const directory = zip.offset;
  await zip.finish();
  return { sink, offsets, directory, end: zip.offset };
}

interface Central {
  readonly crc: number;
  readonly compressed: number;
  readonly size: number;
  readonly offset: number;
  readonly versions: [number, number];
  readonly extra: number[];
  readonly extraLength: number;
}

function central(tail: Uint8Array, count: number): { entries: Central[]; rest: Uint8Array } {
  const entries: Central[] = [];
  let at = 0;
  for (let i = 0; i < count; i++) {
    expect(u32(tail, at)).toBe(0x02014b50);
    expect(u16(tail, at + 8)).toBe(0x0800);
    expect(u16(tail, at + 10)).toBe(8);
    expect([u16(tail, at + 12), u16(tail, at + 14)]).toEqual([TIME.time, TIME.date]);
    const nameLength = u16(tail, at + 28);
    const extraLength = u16(tail, at + 30);
    const extra: number[] = [];
    if (extraLength) {
      expect(u16(tail, at + 46 + nameLength)).toBe(0x0001);
      expect(u16(tail, at + 48 + nameLength)).toBe(extraLength - 4);
      for (let v = 0; v < (extraLength - 4) / 8; v++) extra.push(u64(tail, at + 50 + nameLength + 8 * v));
    }
    entries.push({
      crc: u32(tail, at + 16),
      compressed: u32(tail, at + 20),
      size: u32(tail, at + 24),
      offset: u32(tail, at + 42),
      versions: [u16(tail, at + 4), u16(tail, at + 6)],
      extra,
      extraLength,
    });
    at += 46 + nameLength + extraLength;
  }
  return { entries, rest: tail.subarray(at) };
}

function local(sink: SparseSink, offset: number, name: string) {
  const header = sink.at(offset);
  expect(header.length).toBe(30 + name.length + 20);
  expect(u32(header, 0)).toBe(0x04034b50);
  expect([u16(header, 4), u16(header, 6), u16(header, 8)]).toEqual([45, 0x0800, 8]);
  expect([u16(header, 10), u16(header, 12)]).toEqual([TIME.time, TIME.date]);
  expect([u32(header, 18), u32(header, 22)]).toEqual([LIMIT, LIMIT]);
  expect([u16(header, 26), u16(header, 28)]).toEqual([name.length, 20]);
  expect(new TextDecoder().decode(header.subarray(30, 30 + name.length))).toBe(name);
  const extra = 30 + name.length;
  expect([u16(header, extra), u16(header, extra + 2)]).toEqual([0x0001, 16]);
  return { crc: u32(header, 14), size: u64(header, extra + 4), compressed: u64(header, extra + 12) };
}

function end(rest: Uint8Array) {
  const at = rest.length - 22;
  expect(u32(rest, at)).toBe(0x06054b50);
  return {
    disks: [u16(rest, at + 4), u16(rest, at + 6)],
    counts: [u16(rest, at + 8), u16(rest, at + 10)],
    size: u32(rest, at + 12),
    offset: u32(rest, at + 16),
    comment: u16(rest, at + 20),
    before: rest.subarray(0, at),
  };
}

describe('ZIP64 thresholds', () => {
  it('adds the uncompressed size to the central extra exactly from 0xFFFFFFFF', async () => {
    const { sink, offsets } = await archive(
      [
        { name: 'a', input: LIMIT },
        { name: 'b', input: LIMIT - 1 },
      ],
      [7, 9],
    );
    const { entries, rest } = central(sink.tail.bytes, 2);
    expect(entries[0]).toMatchObject({ size: LIMIT, compressed: 7, offset: 0, extra: [LIMIT], extraLength: 12 });
    expect(entries[1]).toMatchObject({ size: LIMIT - 1, compressed: 9, offset: 58, extra: [], extraLength: 0 });
    expect(entries.map(entry => entry.versions)).toEqual([
      [45, 45],
      [45, 45],
    ]);
    expect(offsets).toEqual([0, 58]);
    expect(local(sink, 0, 'a')).toEqual({ crc: entries[0]!.crc, size: LIMIT, compressed: 7 });
    expect(local(sink, 58, 'b')).toEqual({ crc: entries[1]!.crc, size: LIMIT - 1, compressed: 9 });
    expect(end(rest)).toMatchObject({ before: new Uint8Array(0), offset: 118, size: 106, counts: [2, 2] });
  }, 30_000);

  it('adds the compressed size exactly from 0xFFFFFFFF', async () => {
    const below = await archive([{ name: 'c', input: 3 }], [LIMIT - 1]);
    expect(central(below.sink.tail.bytes, 1).entries[0]).toMatchObject({ compressed: LIMIT - 1, extra: [] });
    expect(local(below.sink, 0, 'c')).toMatchObject({ size: 3, compressed: LIMIT - 1 });
    const at = await archive([{ name: 'c', input: 3 }], [LIMIT]);
    expect(central(at.sink.tail.bytes, 1).entries[0]).toMatchObject({ compressed: LIMIT, size: 3, extra: [LIMIT] });
    expect(local(at.sink, 0, 'c')).toMatchObject({ size: 3, compressed: LIMIT });
  });

  it('adds the local header offset exactly from 0xFFFFFFFF', async () => {
    const below = await archive(
      [
        { name: 'd', input: 1 },
        { name: 'e', input: 1 },
      ],
      [LIMIT - 1 - 51, 5],
    );
    expect(below.offsets[1]).toBe(LIMIT - 1);
    expect(central(below.sink.tail.bytes, 2).entries[1]).toMatchObject({ offset: LIMIT - 1, extra: [] });
    const at = await archive(
      [
        { name: 'd', input: 1 },
        { name: 'e', input: 1 },
      ],
      [LIMIT - 51, 5],
    );
    expect(at.offsets[1]).toBe(LIMIT);
    const entries = central(at.sink.tail.bytes, 2).entries;
    expect(entries[0]).toMatchObject({ offset: 0, compressed: LIMIT - 51, extra: [] });
    expect(entries[1]).toMatchObject({ offset: LIMIT, size: 1, compressed: 5, extra: [LIMIT] });
    expect(local(at.sink, LIMIT, 'e')).toMatchObject({ size: 1, compressed: 5 });
  });

  it('orders a full ZIP64 extra as size, compressed size, offset', async () => {
    const { sink } = await archive(
      [
        { name: 'f', input: 2 },
        { name: 'g', input: LIMIT + 3 },
      ],
      [LIMIT + 10, LIMIT + 5],
    );
    const { entries, rest } = central(sink.tail.bytes, 2);
    expect(entries[0]).toMatchObject({ compressed: LIMIT, size: 2, offset: 0, extra: [LIMIT + 10] });
    expect(entries[1]).toMatchObject({
      compressed: LIMIT,
      size: LIMIT,
      offset: LIMIT,
      extra: [LIMIT + 3, LIMIT + 5, 51 + LIMIT + 10],
      extraLength: 28,
    });
    expect(local(sink, 51 + LIMIT + 10, 'g')).toMatchObject({ size: LIMIT + 3, compressed: LIMIT + 5 });
    const directory = 2 * LIMIT + 117;
    const last = end(rest);
    expect(last).toMatchObject({ offset: LIMIT, size: 134, counts: [2, 2] });
    expect([u64(last.before, 40), u64(last.before, 48), u64(last.before, 64)]).toEqual([
      134,
      directory,
      directory + 134,
    ]);
  }, 30_000);

  it('writes the ZIP64 end record and locator only from a central directory offset of 0xFFFFFFFF', async () => {
    const below = await archive([{ name: 'h', input: 4 }], [LIMIT - 1 - 51]);
    expect(below.directory).toBe(LIMIT - 1);
    const plain = end(central(below.sink.tail.bytes, 1).rest);
    expect(plain).toMatchObject({ before: new Uint8Array(0), offset: LIMIT - 1, size: 47, counts: [1, 1] });
    expect(plain).toMatchObject({ disks: [0, 0], comment: 0 });

    const at = await archive([{ name: 'h', input: 4 }], [LIMIT - 51]);
    expect(at.directory).toBe(LIMIT);
    const { entries, rest } = central(at.sink.tail.bytes, 1);
    expect(entries[0]).toMatchObject({ offset: 0, compressed: LIMIT - 51, extra: [] });
    const last = end(rest);
    expect(last).toMatchObject({ offset: LIMIT, size: 47, counts: [1, 1], disks: [0, 0], comment: 0 });
    const records = last.before;
    expect(records.length).toBe(56 + 20);
    expect(u32(records, 0)).toBe(0x06064b50);
    expect(u64(records, 4)).toBe(44);
    expect([u16(records, 12), u16(records, 14), u32(records, 16), u32(records, 20)]).toEqual([45, 45, 0, 0]);
    expect([u64(records, 24), u64(records, 32), u64(records, 40), u64(records, 48)]).toEqual([1, 1, 47, LIMIT]);
    expect(u32(records, 56)).toBe(0x07064b50);
    expect([u32(records, 60), u64(records, 64), u32(records, 72)]).toEqual([0, LIMIT + 47, 1]);
    expect(at.end).toBe(LIMIT + 47 + 56 + 20 + 22);
  });
});

describe('ZIP entries', () => {
  it('patches the CRC and both sizes into the local header after the deflate stage', async () => {
    const sink = new SparseSink([12]);
    const zip = new ZipWriter(sink, TIME);
    await zip.begin('PowerLog-original/x.csv');
    await zip.write(new Uint8Array([1, 2, 3]));
    await zip.write(new Uint8Array([4, 5]));
    await zip.end();
    expect(sink.calls).toEqual(['write', 'beginDeflate', 'endDeflate', 'writeAt']);
    const header = sink.writes[0]!.bytes;
    expect([u32(header, 14), u64(header, 57), u64(header, 65)]).toEqual([0, 0, 0]);
    expect(local(sink, 0, 'PowerLog-original/x.csv')).toEqual({ crc: 0xc0ffee00 + 3, size: 5, compressed: 12 });
    expect(zip.offset).toBe(73 + 12);
  });

  it('keeps one write outstanding and resolves a write before it settles', async () => {
    const pending: (() => void)[] = [];
    let outstanding = 0;
    let most = 0;
    const sink: ExportSink = {
      write: () => {
        outstanding++;
        most = Math.max(most, outstanding);
        return new Promise<void>(resolve =>
          pending.push(() => {
            outstanding--;
            resolve();
          }),
        );
      },
      writeAt: async () => {},
      beginDeflate: async () => {},
      endDeflate: async () => ({ crc32: 1, inputBytes: 2, outputBytes: 2 }),
      commit: async () => ({ uri: '' }),
      abort: async () => {},
    };
    const release = async () => {
      while (pending.length === 0) await new Promise(resolve => setTimeout(resolve, 0));
      pending.shift()!();
    };
    const zip = new ZipWriter(sink, TIME);
    const begun = zip.begin('n');
    await release();
    await begun;
    await zip.write(new Uint8Array([1]));
    expect(outstanding).toBe(1);
    let second = false;
    const next = zip.write(new Uint8Array([2])).then(() => (second = true));
    await new Promise(resolve => setTimeout(resolve, 5));
    expect(second).toBe(false);
    expect(outstanding).toBe(1);
    await release();
    await next;
    expect(second).toBe(true);
    const ended = zip.end();
    await release();
    await ended;
    expect(most).toBe(1);
    expect(outstanding).toBe(0);
  });

  it.each([
    [{ crc32: 1, inputBytes: 2, outputBytes: 4 }, 'sink'],
    [{ crc32: 1, inputBytes: 3, outputBytes: -1 }, 'sink'],
    [{ crc32: 1, inputBytes: 3, outputBytes: 2.5 }, 'sink'],
    [{ crc32: -1, inputBytes: 3, outputBytes: 4 }, 'sink'],
    [{ crc32: 2 ** 32, inputBytes: 3, outputBytes: 4 }, 'sink'],
    [{ crc32: 0.5, inputBytes: 3, outputBytes: 4 }, 'sink'],
  ] as [ExportDeflateResult, ExportErrorCode][])('rejects the deflate result %j', async (result, code) => {
    const sink: ExportSink = {
      write: async () => {},
      writeAt: async () => {},
      beginDeflate: async () => {},
      endDeflate: async () => result,
      commit: async () => ({ uri: '' }),
      abort: async () => {},
    };
    const zip = new ZipWriter(sink, TIME);
    await zip.begin('n');
    await zip.write(new Uint8Array([1, 2, 3]));
    await expect(zip.end()).rejects.toMatchObject({ name: 'ExportError', code });
  });
});

describe('ZIP time', () => {
  it.each([
    ['2026-03-14T09:27:31.250Z', (9 << 11) | (27 << 5) | 15, (46 << 9) | (3 << 5) | 14],
    ['2026-03-14T09:27:30+00:00', (9 << 11) | (27 << 5) | 15, (46 << 9) | (3 << 5) | 14],
    ['1980-01-01T00:00:00Z', 0, (1 << 5) | 1],
    ['2107-12-31T23:59:59.999Z', (23 << 11) | (59 << 5) | 29, (127 << 9) | (12 << 5) | 31],
    ['2024-02-29T12:00:01Z', 12 << 11, (44 << 9) | (2 << 5) | 29],
  ])('encodes %s in UTC', (exportedAt, time, date) => {
    expect(zipTime(exportedAt)).toEqual({ time, date });
  });

  it('clamps times a DOS date cannot hold', () => {
    expect(zipTime('1979-12-31T23:59:59.000Z')).toEqual({ time: 0, date: (1 << 5) | 1 });
    expect(zipTime('2108-01-01T00:00:00.000Z')).toEqual({
      time: (23 << 11) | (59 << 5) | 29,
      date: (127 << 9) | (12 << 5) | 31,
    });
  });

  it.each([
    '2026-02-30T00:00:00Z',
    '2025-02-29T00:00:00Z',
    '2026-03-14T24:00:00Z',
    '2026-03-14T09:60:00Z',
    '2026-03-14T09:27:60Z',
    '2026-03-14T09:27:31.25Z',
    '2026-03-14T09:27:31+01:00',
    '2026-03-14 09:27:31Z',
    '2026-03-14T09:27:31',
    '',
  ])('rejects %j', exportedAt => {
    expect(() => zipTime(exportedAt)).toThrow(ExportError);
    try {
      zipTime(exportedAt);
    } catch (error) {
      expect((error as ExportError).code).toBe('unsupported');
    }
  });
});
