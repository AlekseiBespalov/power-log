import { describe, expect, it } from 'vitest';
import { ZipWriter, zipTime } from '../../modules/power-log/src/exports/zip';
import { MemorySink } from '../fixtures/export/zip/harness';
import { expectRideGoldenArchive, microsOf, utcUs, withoutStartingBuildCells } from '../support/golden-rules';

async function archive(entries: Record<string, string>): Promise<Uint8Array> {
  const sink = new MemorySink();
  const zip = new ZipWriter(sink, zipTime('2026-03-01T09:30:00.000Z'));
  for (const [name, text] of Object.entries(entries)) {
    await zip.begin(`PowerLog-original/${name}`);
    await zip.write(new TextEncoder().encode(text));
    await zip.end();
  }
  await zip.finish();
  return sink.file;
}

const descriptor = (timerSeconds: number, elapsedSeconds = 60) =>
  `${JSON.stringify(
    {
      created: '2026-03-01T09:30:00.000Z',
      'powerLog:ride': {
        startedAt: '2026-03-01T09:00:00.000Z',
        endedAt: '2026-03-01T09:01:00.000Z',
        elapsedSeconds,
        timerSeconds,
        ownerTiming: null,
        health: { provider: null, state: 'notRequested', workoutUUID: null, export: null },
      },
    },
    null,
    2,
  )}\n`;
const events =
  'timestamp,elapsedSeconds,timerSeconds,action,interrupted,source\nA,0,,start,false,phone\nB,60,,stop,false,phone\n';
const health = (lines: string[]) =>
  [
    'timestamp,startTimestamp,elapsedSeconds,activeInterval,source,metric,value,unit,kind,sampleCount,provider,parentId,memberIndex,dataOrigin,originalEventId,originalEndTimestamp,logicalKey,targetEventId,originRevision,valueBits,evidence',
    ...lines,
  ].join('\n') + '\n';
const sample = (timestamp: string, metric: string, start = '', key = '') =>
  `${timestamp},${start},1,1,phone,${metric},70,count/min,sample,,healthConnect,,,app,,,${key},,,,`;

describe('the golden comparison rules', () => {
  it('reads printed seconds exactly to the microsecond', () => {
    expect(microsOf('0.000511')).toBe(511);
    expect(microsOf('12')).toBe(12_000_000);
    expect(microsOf('1.2345678')).toBe(1_234_567);
    expect(microsOf('-0.5')).toBe(-500_000);
    expect(microsOf('1e-7')).toBeNull();
  });

  it('reads UTC timestamps in the 2000s with a fraction truncated to microseconds', () => {
    expect(utcUs('2026-01-01T00:00:00Z')).toBe(Date.UTC(2026, 0, 1) * 1000);
    expect(utcUs('2026-01-01T00:00:00.123456789+00:00')).toBe(Date.UTC(2026, 0, 1) * 1000 + 123_456);
    expect(utcUs('2026-02-30T00:00:00Z')).toBeNull();
    expect(utcUs('1999-12-31T23:59:59Z')).toBeNull();
  });

  it('blanks the cells only the starting build wrote and keeps the others as written', () => {
    const csv = 'metric,logicalKey,evidence,value\nheartRate,k,"{""a"":1,""b"":2}",70\n';
    expect(withoutStartingBuildCells(csv)).toBe('metric,logicalKey,evidence,value\nheartRate,,,70\n');
  });

  it('compares printed seconds within a microsecond and the timer within one per active-interval boundary', async () => {
    const telemetry = (elapsed: string) => `timestamp,elapsedSeconds,humanPowerW\nA,${elapsed},100\n`;
    const golden = await archive({
      'telemetry.csv': telemetry('1.0000005'),
      'events.csv': events,
      'datapackage.json': descriptor(59.999999),
    });
    const close = await archive({
      'telemetry.csv': telemetry('1.000001'),
      'events.csv': events,
      'datapackage.json': descriptor(60.000001),
    });
    expectRideGoldenArchive(close, golden, 'ios', []);
    const far = await archive({
      'telemetry.csv': telemetry('1.000002'),
      'events.csv': events,
      'datapackage.json': descriptor(60.000001),
    });
    expect(() => expectRideGoldenArchive(far, golden, 'ios', [])).toThrow();
    const timer = await archive({
      'telemetry.csv': telemetry('1.0000005'),
      'events.csv': events,
      'datapackage.json': descriptor(60.000002),
    });
    expect(() => expectRideGoldenArchive(timer, golden, 'ios', [])).toThrow();
  });

  it("compares the descriptor as text once it holds the golden's tolerated values", async () => {
    const golden = await archive({ 'events.csv': events, 'datapackage.json': descriptor(59.999999) });
    const replayed = (text: string) => archive({ 'events.csv': events, 'datapackage.json': text });
    expectRideGoldenArchive(await replayed(descriptor(60.000001)), golden, 'ios', []);
    const { created, ...rest } = JSON.parse(descriptor(60.000001)) as Record<string, unknown>;
    for (const text of [
      `${JSON.stringify({ ...rest, created }, null, 2)}\n`,
      descriptor(60.000001).replace('"created": ', '"created":  '),
      descriptor(60.000001).slice(0, -1),
    ]) {
      const bytes = await replayed(text);
      expect(() => expectRideGoldenArchive(bytes, golden, 'ios', [])).toThrow();
    }
  });

  it('compares a table as text, and cell by cell only in the rows that differ', async () => {
    const table = (rows: string) => `timestamp,elapsedSeconds,humanPowerW\n${rows}`;
    const replayed = (rows: string) =>
      archive({ 'telemetry.csv': table(rows), 'events.csv': events, 'datapackage.json': descriptor(60) });
    const golden = await replayed('A,1.0000005,100\nB,2,110\n');
    expectRideGoldenArchive(await replayed('A,1.000001,100\nB,2,110\n'), golden, 'ios', []);
    for (const rows of [
      'A,1.000001,100\nB,2,110',
      '"A",1.0000005,100\nB,2,110\n',
      'A,1.0000005,100.0\nB,2,110\n',
      'A,1.000001,101\nB,2,110\n',
    ]) {
      const bytes = await replayed(rows);
      expect(() => expectRideGoldenArchive(bytes, golden, 'ios', [])).toThrow();
    }
  });

  it('drops exactly the Health lines the loader dropped and blanks the starting build cells of the golden', async () => {
    const inside = sample('2026-03-01T09:00:30Z', 'heartRate');
    const outside = sample('2026-03-01T09:05:00Z', 'heartRate');
    const golden = await archive({
      'health.csv': health([sample('2026-03-01T09:00:30Z', 'heartRate', '', 'key'), outside]),
      'events.csv': events,
      'datapackage.json': descriptor(60),
    });
    const replayed = await archive({
      'health.csv': health([inside]),
      'events.csv': events,
      'datapackage.json': descriptor(60),
    });
    expectRideGoldenArchive(replayed, golden, 'android', [3]);
    expect(() => expectRideGoldenArchive(replayed, golden, 'android', [])).toThrow();
    expect(() => expectRideGoldenArchive(replayed, golden, 'android', [2])).toThrow();
    expect(() => expectRideGoldenArchive(replayed, golden, 'android', [3, 4])).toThrow(
      'health.csv has no line 4 to drop',
    );
  });

  it('compares iPhone Health timestamps as instants', async () => {
    const golden = await archive({
      'health.csv': health([sample('2026-03-01T09:00:30.0000009Z', 'heartRate')]),
      'events.csv': events,
      'datapackage.json': descriptor(60),
    });
    const replayed = await archive({
      'health.csv': health([sample('2026-03-01T09:00:30Z', 'heartRate')]),
      'events.csv': events,
      'datapackage.json': descriptor(60),
    });
    expectRideGoldenArchive(replayed, golden, 'ios', []);
    expect(() => expectRideGoldenArchive(replayed, golden, 'android', [])).toThrow();
  });
});
