import { inflateRawSync } from 'node:zlib';
import { describe, expect, it } from 'vitest';
import type { ExportSlicer } from '../../src/core/export/pages';
import {
  CELL_COUNT,
  CELL_ID,
  CELL_INTEGER,
  CELL_NUMBER,
  CELL_TEXT,
  TextChunks,
  csvBoolean,
  csvHeader,
  csvInteger,
  csvNumber,
  csvText,
  writeRows,
  type ByteOutput,
  type CellValues,
  type TableLayout,
} from '../../src/core/export/tables/text';
import { ExportError, type ExportErrorCode } from '../../src/core/export/types';
import { ZipWriter } from '../../src/core/export/zip';
import { MemorySink } from '../fixtures/export/zip/harness';

function code(run: () => unknown): ExportErrorCode | undefined {
  try {
    run();
  } catch (error) {
    if (error instanceof ExportError) return error.code;
    throw error;
  }
  return undefined;
}

class Collector implements ByteOutput {
  readonly chunks: Uint8Array[] = [];
  async write(bytes: Uint8Array): Promise<void> {
    this.chunks.push(bytes.slice());
  }
  get bytes(): Buffer {
    return Buffer.concat(this.chunks);
  }
}

const idle: ExportSlicer = { tick: () => Promise.resolve() };

describe('CSV cells', () => {
  it.each([
    ['plain', 'plain'],
    ['', ''],
    ['a,b', '"a,b"'],
    ['say "hi"', '"say ""hi"""'],
    ['"', '""""'],
    ['line\nbreak', '"line\nbreak"'],
    ['carriage\rreturn', '"carriage\rreturn"'],
    ['both\r\nends', '"both\r\nends"'],
    ['tab\tsemicolon; space ', 'tab\tsemicolon; space '],
    ["apostrophe's", "apostrophe's"],
    ['Ünïcødé 🚲', 'Ünïcødé 🚲'],
  ])('writes %j as %j', (value, expected) => {
    expect(csvText(value)).toBe(expected);
  });

  it('writes missing text as an empty cell', () => {
    expect(csvText(null)).toBe('');
    expect(csvText(undefined)).toBe('');
  });

  it.each([
    [0, '0'],
    [-0, '0'],
    [1.5, '1.5'],
    [-2.25, '-2.25'],
    [182, '182'],
    [1e21, '1e+21'],
    [1e-7, '1e-7'],
    [0.1 + 0.2, '0.30000000000000004'],
    [123456789012345680000, '123456789012345680000'],
    [5e-324, '5e-324'],
    [NaN, ''],
  ])('writes the number %d as %j', (value, expected) => {
    expect(csvNumber(value, 'a reading', 'value')).toBe(expected);
  });

  it('fails a value that is not a finite number', () => {
    expect(code(() => csvNumber(Infinity, 'a reading', 'value'))).toBe('page');
    expect(code(() => csvNumber(-Infinity, 'a reading', 'value'))).toBe('page');
  });

  it('writes integers and fails values that are not exact integers', () => {
    expect(csvInteger(3, 'a reading', 'faultCode')).toBe('3');
    expect(csvInteger(-0, 'a reading', 'faultCode')).toBe('0');
    expect(csvInteger(-7, 'a reading', 'faultCode')).toBe('-7');
    expect(csvInteger(NaN, 'a reading', 'faultCode')).toBe('');
    expect(code(() => csvInteger(1.5, 'a reading', 'faultCode'))).toBe('page');
    expect(code(() => csvInteger(2 ** 53, 'a reading', 'faultCode'))).toBe('page');
    expect(code(() => csvInteger(Infinity, 'a reading', 'faultCode'))).toBe('page');
  });

  it('writes booleans as true and false', () => {
    expect([csvBoolean(true), csvBoolean(false)]).toEqual(['true', 'false']);
  });

  it('quotes header names that need it', () => {
    expect(
      csvHeader({
        name: 't',
        fields: [
          { name: 'a', type: 'string' },
          { name: 'b,c', type: 'number' },
        ],
      }),
    ).toBe('a,"b,c"');
  });
});

describe('chunked UTF-8 text', () => {
  const lines = ['ascii', 'é ü', '🚲 bike', 'mixed ✓ 𝄞 end', 'lone \ud800 surrogate', '', 'last'];
  const expected = Buffer.from(lines.map(line => `${line}\n`).join(''), 'utf8');

  it('encodes lines as UTF-8 without a byte order mark, ending each with LF', async () => {
    const output = new Collector();
    const out = new TextChunks(output);
    for (const line of lines) out.line(line);
    await out.end();
    expect(output.bytes.equals(expected)).toBe(true);
    expect(output.bytes.subarray(0, 3).equals(Buffer.from([0xef, 0xbb, 0xbf]))).toBe(false);
    expect(output.bytes.includes(Buffer.from([0xef, 0xbf, 0xbd]))).toBe(true);
  });

  it.each([4, 5, 6, 7, 64])('fills %i-byte chunks without splitting a character', async size => {
    const output = new Collector();
    const out = new TextChunks(output, size);
    for (const line of lines) {
      out.line(line);
      await out.drain();
    }
    await out.end();
    expect(output.bytes.equals(expected)).toBe(true);
    for (const chunk of output.chunks) {
      expect(chunk.length).toBeGreaterThan(0);
      expect(chunk.length).toBeLessThanOrEqual(size);
      expect(() => new TextDecoder('utf-8', { fatal: true }).decode(chunk)).not.toThrow();
    }
    expect(output.chunks.slice(0, -1).every(chunk => chunk.length > size - 4)).toBe(true);
  });

  it('holds lines until enough text is pending', async () => {
    const output = new Collector();
    const out = new TextChunks(output, 1024);
    out.line('short');
    expect(out.due).toBe(false);
    await out.drain();
    expect(output.chunks).toEqual([]);
    out.line('x'.repeat(16 * 1024));
    expect(out.due).toBe(true);
    await out.drain();
    expect(out.due).toBe(false);
    expect(output.chunks.length).toBe(16);
    await out.end();
    expect(output.bytes.toString('utf8')).toBe(`short\n${'x'.repeat(16 * 1024)}\n`);
  });

  it('reuses a chunk buffer only after the sink settled its write', async () => {
    const sink = new MemorySink({ beforeWrite: () => new Promise(resolve => setTimeout(resolve, 1)) });
    const zip = new ZipWriter(sink, { time: 0, date: 33 });
    const out = new TextChunks(zip, 64);
    await zip.begin('PowerLog-original/text.csv');
    let text = '';
    for (let i = 0; i < 400; i++) {
      const line = `${i},${'é'.repeat(i % 7)},🚲`;
      text += `${line}\n`;
      out.line(line);
      if (i % 3 === 0) await out.drain();
    }
    await out.end();
    await zip.end();
    await zip.finish();
    expect(sink.violations).toEqual([]);
    expect(sink.maxInFlight).toBe(1);
    expect(sink.writes).toBeGreaterThan(100);
    const header = 30 + 'PowerLog-original/text.csv'.length + 20;
    const central = sink.file.length - 22 - (46 + 'PowerLog-original/text.csv'.length);
    expect(inflateRawSync(sink.file.subarray(header, central)).toString('utf8')).toBe(text);
  });

  it('rejects chunks too small for one character', () => {
    expect(() => new TextChunks(new Collector(), 3)).toThrow(RangeError);
  });
});

describe('table rows', () => {
  const layout: TableLayout = {
    table: {
      name: 'demo',
      fields: [
        { name: 'number', type: 'number' },
        { name: 'integer', type: 'integer' },
        { name: 'text', type: 'string' },
        { name: 'id', type: 'integer' },
        { name: 'count', type: 'integer' },
        { name: 'absent', type: 'number' },
      ],
    },
    subject: 'a demo row',
    kinds: [CELL_NUMBER, CELL_INTEGER, CELL_TEXT, CELL_ID, CELL_COUNT, CELL_NUMBER],
    reads: ['number', 'integer', 'text', null, null, 'absent'],
  };

  async function rows(columns: CellValues[], count: number, slicer: ExportSlicer = idle): Promise<string> {
    const output = new Collector();
    const out = new TextChunks(output);
    await writeRows(out, slicer, layout, columns, count);
    await out.end();
    return output.bytes.toString('utf8');
  }

  it('formats every cell kind and leaves missing columns empty', async () => {
    const text = await rows(
      [
        Float64Array.from([1.25, -0, NaN]),
        Float64Array.from([4, NaN, -3]),
        ['a,b', null, 'plain'],
        Int32Array.from([2, 0, 7]),
        Int32Array.from([1, 1, 3]),
        undefined,
      ],
      3,
    );
    expect(text).toBe('1.25,4,"a,b",2,1,\n0,,,,1,\n,-3,plain,7,3,\n');
  });

  it.each([
    [[Float64Array.from([Infinity]), Float64Array.from([1])], 'a finite number'],
    [[Float64Array.from([1]), Float64Array.from([2.5])], 'an integer'],
    [[Float64Array.from([1]), Float64Array.from([2 ** 53])], 'an integer'],
  ])('fails a row whose value breaks its field type', async (numbers, problem) => {
    const columns: CellValues[] = [...numbers, [null], Int32Array.from([0]), Int32Array.from([1]), undefined];
    const failure = await rows(columns, 1).catch((error: unknown) => error);
    expect(failure).toBeInstanceOf(ExportError);
    expect(failure).toMatchObject({ code: 'page' });
    expect((failure as ExportError).message).toContain(problem);
    expect((failure as ExportError).message).toContain('a demo row');
  });

  it('calls the slicer after every batch of 64 rows', async () => {
    let ticks = 0;
    const slicer: ExportSlicer = { tick: () => Promise.resolve(void ticks++) };
    const count = 200;
    const column = Float64Array.from({ length: count }, (_, i) => i);
    await rows(
      [column, column, Array(count).fill(null), new Int32Array(count), new Int32Array(count), undefined],
      count,
      slicer,
    );
    expect(ticks).toBe(3);
  });
});
