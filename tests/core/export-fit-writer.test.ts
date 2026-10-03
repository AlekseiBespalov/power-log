import { describe, expect, it } from 'vitest';
import { FIT_MESSAGES } from '../../src/core/export/catalog';
import { FitWriter, fitCrc, fitFloat32, fitInteger, fitString } from '../../src/core/export/fit/writer';
import { createSlicer } from '../../src/core/export/pages';
import { ExportError, type FitBaseType } from '../../src/core/export/types';
import { FIT_START, MemorySink, readFit, runFit } from '../fixtures/export/fit/ride';

const u16 = (n: number) => [n & 0xff, n >>> 8];
const u32 = (n: number) => [n & 0xff, (n >>> 8) & 0xff, (n >>> 16) & 0xff, n >>> 24];
const NAN11 = () => new Float64Array(11).fill(NaN);

async function written(build: (writer: FitWriter) => void): Promise<Uint8Array> {
  const sink = new MemorySink();
  const writer = new FitWriter(sink, createSlicer());
  await writer.begin();
  build(writer);
  await writer.finish();
  return sink.bytes;
}

function record(timestamp: number, power = NaN, developer?: number[]): [Float64Array, Float64Array | undefined] {
  const values = NAN11();
  values[0] = timestamp;
  values[7] = power;
  return [values, developer ? Float64Array.from(developer) : undefined];
}

describe('FIT file framing', () => {
  it('writes the 14-byte header with protocol 2.0, profile 21.217, the data size and both CRC-16s', async () => {
    const bytes = await written(writer => writer.message('fileId', [4, 255, 1, FIT_START]));
    const view = new DataView(bytes.buffer);
    expect([...bytes.subarray(0, 4)]).toEqual([14, 0x20, 0xe1, 0x52]);
    expect(view.getUint32(4, true)).toBe(bytes.length - 16);
    expect(new TextDecoder().decode(bytes.subarray(8, 12))).toBe('.FIT');
    expect(view.getUint16(12, true)).toBe(fitCrc(bytes.subarray(0, 12)));
    expect(view.getUint16(bytes.length - 2, true)).toBe(fitCrc(bytes.subarray(0, bytes.length - 2)));
    expect(fitCrc(bytes)).toBe(0);
  });

  it('computes CRC-16/ARC', () => {
    expect(fitCrc(new TextEncoder().encode('123456789'))).toBe(0xbb3d);
  });

  it('streams large files through several sink writes and still checks out', async () => {
    const sink = new MemorySink();
    const writer = new FitWriter(sink, createSlicer());
    await writer.begin();
    for (let i = 0; i < 20_000; i++) {
      const [values] = record(FIT_START + i, i % 300);
      writer.message('record', values);
      if (writer.pending >= 65536) await writer.drain();
    }
    await writer.finish();
    expect(sink.writes).toBeGreaterThan(3);
    expect(fitCrc(sink.bytes)).toBe(0);
    expect(readFit(sink.bytes)).toHaveLength(20_000);
  });

  it('pads strings with zeros and keeps one byte for the terminator', () => {
    expect([...fitString('abc', 5)]).toEqual([97, 98, 99, 0, 0]);
    expect([...fitString('abcdef', 4)]).toEqual([97, 98, 99, 0]);
  });
});

describe('FIT definitions', () => {
  it('omits absent fields from the definition instead of writing invalid sentinels', async () => {
    const bytes = await written(writer => {
      const [values] = record(FIT_START, 100);
      writer.message('record', values);
    });
    const [message] = readFit(bytes);
    expect(message!.definition).toEqual([
      [253, 4, 0x86],
      [7, 2, 0x84],
    ]);
    expect([...message!.bytes]).toEqual([0, ...u32(FIT_START), ...u16(100)]);
  });

  it('keeps 16 local slots and evicts the least recently used definition', async () => {
    const masks = [...Array.from({ length: 16 }, (_, i) => i + 1), 1, 17, 1, 2];
    const bytes = await written(writer =>
      masks.forEach((mask, index) => {
        const developer = Array.from({ length: 10 }, (_, j) => (j >= 1 && j <= 5 && mask & (1 << (j - 1)) ? j : NaN));
        const [values, dev] = record(FIT_START + index, 100, developer);
        writer.message('record', values, dev);
      }),
    );
    const messages = readFit(bytes);
    expect(messages.map(message => message.local)).toEqual([...Array.from({ length: 16 }, (_, i) => i), 0, 1, 0, 2]);
    expect(messages.map(message => message.redefined)).toEqual([
      ...Array.from({ length: 16 }, () => true),
      false,
      true,
      false,
      true,
    ]);
  });

  it('marks developer definitions and lists developer fields with data index 0', async () => {
    const bytes = await written(writer => {
      const [values, developer] = record(FIT_START, 100, [-5, 48.5, NaN, NaN, NaN, NaN, NaN, NaN, NaN, 12.25]);
      writer.message('record', values, developer);
    });
    expect(bytes[14]).toBe(0x60);
    const [message] = readFit(bytes);
    expect(message!.developerDefinition).toEqual([
      [0, 2, 0],
      [1, 4, 0],
      [9, 4, 0],
    ]);
    expect([...message!.developer]).toEqual([
      [0, -5],
      [1, 48.5],
      [9, 12.25],
    ]);
  });
});

describe('FIT widths and rounding', () => {
  const cases: [FitBaseType, number, number][] = [
    ['uint8', 254, 254],
    ['uint8', 254.49, 254],
    ['uint8', 254.5, NaN],
    ['uint8', -0.49, 0],
    ['uint8', -0.5, NaN],
    ['enum', 255, NaN],
    ['uint16', 65534.4, 65534],
    ['uint16', 65534.5, NaN],
    ['uint32', 4294967294, 4294967294],
    ['uint32', 4294967294.5, NaN],
    ['sint16', -32768.4, -32768],
    ['sint16', -32768.5, NaN],
    ['sint16', 32766.4, 32766],
    ['sint16', 32766.5, NaN],
    ['sint32', -2147483648, -2147483648],
    ['sint32', 2147483646.4, 2147483646],
    ['sint32', 2147483646.5, NaN],
    ['uint16', 2.5, 3],
    ['sint16', -2.5, -3],
    ['sint16', -0.4, 0],
    ['uint8', Infinity, NaN],
    ['uint8', NaN, NaN],
  ];
  it.each(cases)('%s %d → %d', (type, value, expected) => {
    expect(fitInteger(value, type)).toBe(expected);
  });

  it('accepts float32 values only when they stay finite as float32', () => {
    expect(fitFloat32(3.4e38)).toBe(3.4e38);
    expect(fitFloat32(3.41e38)).toBeNaN();
    expect(fitFloat32(-Infinity)).toBeNaN();
  });

  it('fails the export when a required field is invalid', async () => {
    const write = (values: number[]) =>
      written(writer => writer.message('lap', values)).catch((error: unknown) => error as ExportError);
    const lap = [FIT_START + 10, 0, 9, 1, FIT_START, 10_000, 10_000, NaN, 7, 2];
    await expect(write(lap)).resolves.toBeInstanceOf(Uint8Array);
    const early = await write([0x0fffffff, ...lap.slice(1)]);
    expect(early).toBeInstanceOf(ExportError);
    expect((early as ExportError).code).toBe('limit');
    const index = await write([lap[0]!, 4096, ...lap.slice(2)]);
    expect((index as ExportError).message).toContain('4096 laps');
    expect(await write([lap[0]!, 4095, ...lap.slice(2)])).toBeInstanceOf(Uint8Array);
    expect(await write([...lap.slice(0, 5), -1, ...lap.slice(6)])).toBeInstanceOf(ExportError);
  });

  it('accepts the smallest absolute FIT time', async () => {
    await expect(written(writer => writer.message('fileId', [4, 255, 1, 0x10000000]))).resolves.toBeInstanceOf(
      Uint8Array,
    );
  });

  it('lists catalog field orders for every message kind', () => {
    expect(FIT_MESSAGES.record.fields.map(field => field.number)).toEqual([253, 0, 1, 78, 73, 5, 3, 7, 4, 82, 119]);
  });
});

describe('FIT byte trace', () => {
  it('writes every message of a one-second ride byte for byte', async () => {
    const { bytes } = await runFit({ end: 1, telemetry: [{ t: 0, humanPowerW: 100, cadenceRpm: 80 }] });
    const t0 = FIT_START;
    const name = (text: string, size: number) => [
      ...new TextEncoder().encode(text),
      ...Array(size - text.length).fill(0),
    ];
    const developer: [number, number, string, string][] = [
      [0, 0x83, 'battery_power', 'W'],
      [1, 0x88, 'battery_voltage', 'V'],
      [2, 0x88, 'battery_current', 'A'],
      [3, 0x88, 'motor_current', 'A'],
      [4, 0x88, 'motor_speed', 'rpm'],
      [5, 0x83, 'motor_temperature', 'C'],
      [6, 0x83, 'controller_temperature', 'C'],
      [7, 0x88, 'pedal_torque', 'Nm'],
      [8, 0x88, 'consumed_energy', 'Wh'],
      [9, 0x88, 'consumed_charge', 'Ah'],
    ];
    const expected = [
      [0x40, 0, 0, 0, 0, 4, 0, 1, 0x00, 1, 2, 0x84, 2, 2, 0x84, 4, 4, 0x86],
      [0x00, 4, 255, 0, 1, 0, ...u32(t0)],
      [0x41, 0, 0, 207, 0, 2, 1, 16, 0x0d, 3, 1, 0x02],
      [0x01, 0x9b, 0x71, 0x6e, 0x18, 0x08, 0xfe, 0x4c, 0x23, 0xb6, 0x4f, 0xbe, 0x64, 0xd5, 0x17, 0xcb, 0x48, 0],
      [0x42, 0, 0, 206, 0, 5, 0, 1, 2, 1, 1, 2, 2, 1, 2, 3, 23, 7, 8, 4, 7],
      ...developer.map(([number, type, field, units]) => [
        0x02,
        0,
        number,
        type,
        ...name(field, 23),
        ...name(units, 4),
      ]),
      [0x43, 0, 0, 21, 0, 4, 253, 4, 0x86, 0, 1, 0, 1, 1, 0, 3, 4, 0x86],
      [0x03, ...u32(t0), 0, 0, 0, 0, 0, 0],
      [0x44, 0, 0, 20, 0, 3, 253, 4, 0x86, 7, 2, 0x84, 4, 1, 0x02],
      [0x04, ...u32(t0), 100, 0, 80],
      [0x03, ...u32(t0 + 1), 0, 4, 0, 0, 0, 0],
      [
        0x45, 0, 0, 19, 0, 9, 253, 4, 0x86, 254, 2, 0x84, 0, 1, 0, 1, 1, 0, 2, 4, 0x86, 7, 4, 0x86, 8, 4, 0x86, 24, 1,
        0, 25, 1, 0,
      ],
      [0x05, ...u32(t0 + 1), 0, 0, 9, 1, ...u32(t0), ...u32(1000), ...u32(1000), 7, 2],
      [
        0x46, 0, 0, 18, 0, 13, 253, 4, 0x86, 254, 2, 0x84, 0, 1, 0, 1, 1, 0, 2, 4, 0x86, 5, 1, 0, 6, 1, 0, 7, 4, 0x86,
        8, 4, 0x86, 19, 1, 0x02, 21, 2, 0x84, 25, 2, 0x84, 26, 2, 0x84,
      ],
      [0x06, ...u32(t0 + 1), 0, 0, 8, 1, ...u32(t0), 2, 28, ...u32(1000), ...u32(1000), 80, 100, 0, 0, 0, 1, 0],
      [0x47, 0, 0, 34, 0, 6, 253, 4, 0x86, 0, 4, 0x86, 1, 2, 0x84, 2, 1, 0, 3, 1, 0, 4, 1, 0],
      [0x07, ...u32(t0 + 1), ...u32(1000), 1, 0, 0, 26, 1],
    ];
    const body = expected.flat();
    const header = [14, 0x20, 0xe1, 0x52, ...u32(body.length), 0x2e, 0x46, 0x49, 0x54];
    const headerCrc = fitCrc(Uint8Array.from(header));
    const file = [...header, ...u16(headerCrc), ...body];
    const crc = fitCrc(Uint8Array.from(file));
    expect([...bytes]).toEqual([...file, ...u16(crc)]);
    let at = 14;
    for (const message of expected) {
      expect([...bytes.subarray(at, at + message.length)]).toEqual(message);
      at += message.length;
    }
  });
});
