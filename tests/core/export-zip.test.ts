import { describe, expect, it } from 'vitest';
import { exportZip } from '../../src/core/export/zip-export';
import { ride as android } from '../fixtures/export/zip/gps-android/ride';
import { ride as iphone } from '../fixtures/export/zip/gps-iphone/ride';
import {
  ENTRY_NAMES,
  MemorySink,
  MemorySource,
  entryText,
  expectedEntries,
  readZip,
  verifyWithPython,
  type CanonicalRide,
} from '../fixtures/export/zip/harness';
import { ride as health } from '../fixtures/export/zip/health-descriptor/ride';
import { ride as telemetry } from '../fixtures/export/zip/telemetry-lifecycle/ride';

const FAMILIES: [string, CanonicalRide, { time: number; date: number }][] = [
  ['telemetry-lifecycle', telemetry, { time: (9 << 11) | (27 << 5) | 15, date: (46 << 9) | (3 << 5) | 14 }],
  ['gps-iphone', iphone, { time: (23 << 11) | (59 << 5) | 29, date: (46 << 9) | (6 << 5) | 21 }],
  ['gps-android', android, { time: 12 << 11, date: (46 << 9) | (7 << 5) | 4 }],
  ['health-descriptor', health, { time: (8 << 11) | (15 << 5) | 21, date: (46 << 9) | (5 << 5) | 1 }],
];

async function exported(ride: CanonicalRide, pageSize = 4096) {
  const source = new MemorySource(ride, pageSize);
  const sink = new MemorySink();
  const result = await exportZip(source, async () => sink, { rideId: ride.rideId, context: ride.context });
  return { source, sink, result };
}

const encoded = (text: string) => Buffer.from(text, 'utf8');

describe.each(FAMILIES)('the %s family', (family, ride, dos) => {
  const expected = expectedEntries(family);

  it('extracts the hand-derived files byte for byte', async () => {
    const { sink, source, result } = await exported(ride);
    const entries = readZip(sink.file);
    expect(entries.map(entry => entry.name)).toEqual(ENTRY_NAMES.map(name => `PowerLog-original/${name}`));
    for (const entry of entries) {
      const name = entry.name.slice('PowerLog-original/'.length) as (typeof ENTRY_NAMES)[number];
      expect(entryText(entry)).toBe(expected[name]);
      expect(Buffer.from(entry.data).equals(encoded(expected[name]))).toBe(true);
    }
    expect(result).toEqual({ uri: `memory://power-log-original-${ride.rideId}.zip` });
    expect(sink.committed).toBe(`power-log-original-${ride.rideId}.zip`);
    expect(sink.aborted).toBe(false);
    expect(sink.violations).toEqual([]);
    expect(source.opened).toEqual([{ rideId: ride.rideId, kind: 'zip', context: ride.context }]);
    expect(source.closed).toEqual(['canonical-session']);
  });

  it('frames every entry in the ZIP64 local form with UTF-8 names and the export time', async () => {
    const { sink } = await exported(ride);
    for (const entry of readZip(sink.file)) {
      expect(entry).toMatchObject({ flags: 0x0800, method: 8, versionNeeded: 45, ...dos });
      expect(entry.centralExtra).toEqual(new Uint8Array(0));
      expect(Array.from(entry.localExtra.subarray(0, 4))).toEqual([0x01, 0x00, 0x10, 0x00]);
      expect(entry.localSizes).toEqual([entry.size, entry.compressedSize]);
    }
  });

  it('passes the independent Python extractor', async () => {
    const { sink } = await exported(ride);
    const verified = verifyWithPython(sink.file);
    expect(verified.zip64End).toBe(false);
    expect(verified.entries.map(entry => entry.name)).toEqual(ENTRY_NAMES.map(name => `PowerLog-original/${name}`));
    for (const entry of verified.entries) {
      const name = entry.name.slice('PowerLog-original/'.length) as (typeof ENTRY_NAMES)[number];
      expect(entry.text).toBe(expected[name]);
      expect({ time: entry.dosTime, date: entry.dosDate }).toEqual(dos);
    }
  });

  it('gives the same extracted bytes at page sizes 1, 17 and 4096', async () => {
    const texts = async (pageSize: number) => readZip((await exported(ride, pageSize)).sink.file).map(entryText);
    const reference = await texts(4096);
    expect(await texts(1)).toEqual(reference);
    expect(await texts(17)).toEqual(reference);
  });
});

it('writes identical archives for identical input and context', async () => {
  const first = await exported(health);
  const second = await exported(health);
  expect(Buffer.from(first.sink.file).equals(Buffer.from(second.sink.file))).toBe(true);
});
