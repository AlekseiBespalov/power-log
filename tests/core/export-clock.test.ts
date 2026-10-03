import { describe, expect, it } from 'vitest';
import {
  FIT_EPOCH_SECONDS,
  FitAnchor,
  fitClock,
  lowerMedian,
  nearest,
  parseStartedAt,
  roundHalfAway,
  type ExportStart,
} from '../../src/core/export/clock';
import type { ExportSlicer } from '../../src/core/export/pages';
import { TimelineBuilder } from '../../src/core/export/timeline';
import { ExportError, type ExportPage } from '../../src/core/export/types';

const idle: ExportSlicer = { tick: () => Promise.resolve() };
const unix = (iso: string) => Date.parse(iso) / 1000;
const fit = (iso: string) => unix(iso) - FIT_EPOCH_SECONDS;

function gateCode(startedAt: unknown) {
  try {
    parseStartedAt(startedAt as string);
  } catch (error) {
    return error instanceof ExportError ? error.code : 'other';
  }
  return undefined;
}

describe('FIT rounding', () => {
  it('rounds ties to the later second', () => {
    const inputs = [0, 0.25, 0.5, 1.5, 2.5, 3.5, 2.4999999999999996, 0.49999999999999994, 1e9 + 0.5, 7];
    expect(inputs.map(nearest)).toEqual([0, 0, 1, 2, 3, 4, 2, 0, 1e9 + 1, 7]);
  });

  it('rounds measurements half away from zero', () => {
    const inputs = [-0.5, -1.5, -2.5, -2.4999999999999996, -0.49999999999999994, 0.5, 2.5, -7];
    expect(inputs.map(roundHalfAway)).toEqual([-1, -2, -3, -2, 0, 1, 3, -7]);
    expect(Object.is(roundHalfAway(-0.25), 0)).toBe(true);
  });
});

describe('ride start', () => {
  it.each([
    ['2026-03-04T05:06:07.890Z', '2026-03-04T05:06:07Z', 890],
    ['2026-03-04T05:06:07.890+00:00', '2026-03-04T05:06:07Z', 890],
    ['2026-03-04T05:06:07.000Z', '2026-03-04T05:06:07Z', 0],
    ['2026-03-04T05:06:07Z', '2026-03-04T05:06:07Z', 0],
    ['2026-03-04T05:06:07+00:00', '2026-03-04T05:06:07Z', 0],
    ['2024-02-29T23:59:59.999Z', '2024-02-29T23:59:59Z', 999],
    ['2000-01-01T00:00:00.001Z', '2000-01-01T00:00:00Z', 1],
  ])('reads %s', (startedAt, seconds, startMs) => {
    expect(parseStartedAt(startedAt)).toEqual({ startSec: unix(seconds), startMs });
  });

  it('keeps four-digit years below 100', () => {
    expect(parseStartedAt('0099-12-31T23:59:59Z').startSec).toBe(
      Date.UTC(2099, 11, 31, 23, 59, 59) / 1000 - 2000 * 31556952,
    );
  });

  it.each([
    '2026-03-04T05:06:07.89Z',
    '2026-03-04T05:06:07.8901Z',
    '2026-03-04T05:06:07.890+01:00',
    '2026-03-04T05:06:07.890-00:00',
    '2026-03-04T05:06:07.890+0000',
    '2026-03-04T05:06:07.890',
    '2026-03-04T05:06:07.890z',
    '2026-03-04t05:06:07.890Z',
    '2026-03-04 05:06:07.890Z',
    ' 2026-03-04T05:06:07.890Z',
    '2026-03-04T05:06:07.890Z ',
    '2026-03-04T05:06:07.890Z\n',
    '2026-03-04T05:06Z',
    '2026-3-04T05:06:07Z',
    '+02026-03-04T05:06:07Z',
    '2025-02-29T00:00:00Z',
    '2100-02-29T00:00:00Z',
    '2026-04-31T00:00:00Z',
    '2026-13-01T00:00:00Z',
    '2026-00-10T00:00:00Z',
    '2026-01-00T00:00:00Z',
    '2026-01-01T24:00:00Z',
    '2026-01-01T23:60:00Z',
    '2026-01-01T23:59:60Z',
    '1767225600',
    '',
    1767225600,
    null,
  ])('rejects %j', startedAt => {
    expect(gateCode(startedAt)).toBe('gate');
  });
});

describe('Android GPS clock anchor', () => {
  const start = parseStartedAt('2026-01-01T00:00:00.250Z');
  const at = (offsetMs: number) => new Date(start.startSec * 1000 + offsetMs).toISOString();
  const cluster: [number, string][] = [
    [10, at(11_250)],
    [20, at(21_100)],
    [30, at(31_300)],
  ];
  const anchorOf = (rows: [number, string | null][], platform: 'ios' | 'android' | 'web' = 'android') => {
    const anchor = new FitAnchor(platform, start);
    for (const [elapsed, timestamp] of rows) anchor.add(elapsed, timestamp);
    return anchor.value(idle);
  };

  it('takes the lower median of GPS minus elapsed time in milliseconds', async () => {
    expect(await anchorOf(cluster)).toBe(1250);
    expect(await anchorOf([...cluster, [40, at(41_200)]])).toBe(1200);
    expect(await anchorOf([cluster[0]!])).toBe(1250);
    expect(await anchorOf([cluster[0]!, cluster[1]!])).toBe(1100);
  });

  it('ignores outliers on either side', async () => {
    const hourAhead: [number, string] = [50, at(3_650_000)];
    const dayBehind: [number, string] = [60, at(-86_340_000)];
    expect(await anchorOf([...cluster, hourAhead, dayBehind])).toBe(1250);
    expect(await anchorOf([...cluster, hourAhead, dayBehind, [70, at(-86_330_000)]])).toBe(1100);
  });

  it('rounds elapsed milliseconds with ties to the later millisecond', async () => {
    expect(await anchorOf([[1.0625, at(2_000)]])).toBe(2000 - 1063);
  });

  it.each([
    ['an unknown elapsed time', NaN],
    ['a negative elapsed time', -0.001],
    ['an infinite elapsed time', Infinity],
  ])('excludes a fix with %s', async (_, elapsed) => {
    expect(await anchorOf([...cluster, [elapsed, at(-100_000)]])).toBe(1250);
  });

  it.each([
    ['without a timestamp', null],
    ['with a two-digit fraction', '2026-01-01T00:00:01.25Z'],
    ['with an offset zone', '2026-01-01T01:00:01.250+01:00'],
    ['with an impossible date', '2025-12-32T00:00:01.250Z'],
  ])('excludes a fix %s', async (_, timestamp) => {
    expect(await anchorOf([...cluster, [1, timestamp]])).toBe(1250);
  });

  it('uses only fixes inside activity intervals', async () => {
    const builder = new TimelineBuilder('android', 200);
    builder.add({
      rows: 4,
      columns: {
        elapsedSeconds: Float64Array.from([0, 50, 100, 200]),
        action: ['start', 'pause', 'resume', 'stop'],
        producer: ['phone', 'phone', 'phone', 'phone'],
        interrupted: Float64Array.from([0, 0, 0, 0]),
      },
    });
    const timeline = builder.finish();
    const rows: [elapsed: number, active: number, timestamp: string][] = [
      [10, 1, at(11_250)],
      [20, 1, at(21_100)],
      [30, 1, at(31_300)],
      [60, 0, at(55_000)],
      [70, 1, at(65_000)],
      [120, 1, at(121_200)],
      [250, 1, at(245_000)],
    ];
    const page: Pick<ExportPage<'gpsDiscovery'>, 'rows' | 'columns'> = {
      rows: rows.length,
      columns: {
        elapsedSeconds: Float64Array.from(rows.map(row => row[0])),
        active: Float64Array.from(rows.map(row => row[1])),
        timestamp: rows.map(row => row[2]),
        producer: rows.map(() => 'phone'),
        horizontalAccuracyM: Float64Array.from(rows.map(() => 5)),
      },
    };
    const { interval } = timeline.cursor('gpsDiscovery').map(page);
    expect(Array.from(interval)).toEqual([1, 1, 1, 0, 0, 2, 0]);
    const anchor = new FitAnchor('android', start);
    anchor.addPage(page, interval);
    expect(anchor.samples).toBe(4);
    expect(await anchor.value(idle)).toBe(1200);
  });

  it('falls back to the phone anchor without fixes and on iPhone and web', async () => {
    expect(await anchorOf([])).toBe(250);
    expect(await anchorOf(cluster, 'ios')).toBe(250);
    expect(await anchorOf(cluster, 'web')).toBe(250);
  });
});

describe('yieldable median selection', () => {
  const sorted = (values: number[]) => Float64Array.from(values).sort();
  const reference = (values: number[]) => sorted(values)[Math.floor((values.length - 1) / 2)]!;
  const random = (count: number, seed: number) => {
    let state = seed;
    return Array.from({ length: count }, () => {
      state = (Math.imul(state, 1664525) + 1013904223) >>> 0;
      return (state % 2001) - 1000;
    });
  };

  it.each([
    ['ascending', Array.from({ length: 1001 }, (_, i) => i)],
    ['descending', Array.from({ length: 1000 }, (_, i) => 1000 - i)],
    ['equal', Array.from({ length: 999 }, () => 437)],
    ['two values', Array.from({ length: 1000 }, (_, i) => (i % 2 ? 401 : 399))],
    ['organ pipe', Array.from({ length: 1000 }, (_, i) => Math.min(i, 999 - i))],
    ['random', random(5000, 7)],
    ['random even', random(4096, 11)],
  ])('selects the lower median of %s values', async (_, values) => {
    expect(await lowerMedian(Float64Array.from(values), values.length, idle)).toBe(reference(values));
  });

  it('selects every rank pattern of small inputs', async () => {
    const permutations = (items: number[]): number[][] =>
      items.length <= 1
        ? [items]
        : items.flatMap((item, index) =>
            permutations([...items.slice(0, index), ...items.slice(index + 1)]).map(rest => [item, ...rest]),
          );
    for (const base of [[5], [5, 1], [5, 1, 3], [5, 1, 3, 3], [2, 9, 4, 4, 7, 1]])
      for (const values of permutations(base))
        expect(await lowerMedian(Float64Array.from(values), values.length, idle)).toBe(reference(values));
  });

  it('gives the slicer turns while selecting among 259,200 values', async () => {
    const values = random(259_200, 3);
    let ticks = 0;
    const counting: ExportSlicer = {
      tick: () => {
        ticks++;
        return Promise.resolve();
      },
    };
    expect(await lowerMedian(Float64Array.from(values), values.length, counting)).toBe(reference(values));
    expect(ticks).toBeGreaterThanOrEqual(Math.floor(259_200 / 4096));
  });

  it('waits while the slicer holds its turn', async () => {
    let release!: () => void;
    let holding = false;
    const holder: ExportSlicer = {
      tick: () => {
        if (holding) return Promise.resolve();
        holding = true;
        return new Promise(resolve => (release = resolve));
      },
    };
    const values = random(10_000, 5);
    let done = false;
    const result = lowerMedian(Float64Array.from(values), values.length, holder).then(value => {
      done = true;
      return value;
    });
    for (let i = 0; i < 20; i++) await Promise.resolve();
    expect(done).toBe(false);
    release();
    expect(await result).toBe(reference(values));
  });

  it('selects inside the first count values only', async () => {
    const values = Float64Array.from([9, 1, 5, -100, -100, -100]);
    expect(await lowerMedian(values, 3, idle)).toBe(5);
    await expect(lowerMedian(values, 0, idle)).rejects.toThrow(RangeError);
    await expect(lowerMedian(values, 7, idle)).rejects.toThrow(RangeError);
  });
});

describe('FIT time', () => {
  const clock = (startedAt: string, anchor?: number) => {
    const start: ExportStart = parseStartedAt(startedAt);
    return fitClock(start, anchor ?? start.startMs);
  };

  it('rounds once from the phone anchor across a day boundary', () => {
    const value = clock('2026-01-01T23:59:59.750Z');
    expect([value.base, value.f]).toEqual([unix('2026-01-01T23:59:59Z'), 0.75]);
    expect(value.time(0)).toBe(fit('2026-01-02T00:00:00Z'));
    expect(value.time(0.25)).toBe(fit('2026-01-02T00:00:00Z'));
    expect(value.time(0.7)).toBe(fit('2026-01-02T00:00:00Z'));
    expect(value.time(0.75)).toBe(fit('2026-01-02T00:00:01Z'));
    expect(value.time(86_400)).toBe(fit('2026-01-03T00:00:00Z'));
    expect(value.second(0)).toBe(1);
    expect(value.timestamp(value.second(0.75))).toBe(value.time(0.75));
  });

  it('carries a negative Android anchor into the previous second and day', () => {
    const late = clock('2026-01-02T00:00:00.000Z', -250);
    expect([late.base, late.f]).toEqual([unix('2026-01-01T23:59:59Z'), 0.75]);
    expect(late.time(0)).toBe(fit('2026-01-02T00:00:00Z'));
    expect(late.time(0.7)).toBe(fit('2026-01-02T00:00:00Z'));
    expect(late.time(0.75)).toBe(fit('2026-01-02T00:00:01Z'));
    const earlier = clock('2026-01-02T00:00:00.000Z', -1250);
    expect([earlier.base, earlier.f]).toEqual([unix('2026-01-01T23:59:58Z'), 0.75]);
    expect(earlier.time(0)).toBe(fit('2026-01-01T23:59:59Z'));
    const whole = clock('2026-01-02T00:00:00.000Z', -1000);
    expect([whole.base, whole.f]).toEqual([unix('2026-01-01T23:59:59Z'), 0]);
  });

  it('carries an Android anchor of a second or more forward', () => {
    const value = clock('2026-01-01T23:59:59.900Z', 1250);
    expect([value.base, value.f]).toEqual([unix('2026-01-02T00:00:00Z'), 0.25]);
    expect(value.time(0.25)).toBe(fit('2026-01-02T00:00:01Z'));
    expect(value.time(0.2)).toBe(fit('2026-01-02T00:00:00Z'));
  });

  it('requires whole milliseconds', () => {
    expect(() => clock('2026-01-01T00:00:00Z', 0.5)).toThrow(RangeError);
  });
});
