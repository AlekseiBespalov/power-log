import { describe, expect, it } from 'vitest';
import {
  DistancePoints,
  DistanceRanges,
  distanceEncodable,
  type DistanceInterval,
  type DistanceKind,
} from '../../src/core/export/fit/distance';
import { TimelineBuilder, type Timeline } from '../../src/core/export/timeline';
import { ExportError, type ExportColumns } from '../../src/core/export/types';

function timeline(end: number, events: [number, string][]): Timeline {
  const builder = new TimelineBuilder('ios', end);
  const all: [number, string][] = [[0, 'start'], ...events, [end, 'stop']];
  builder.add({
    rows: all.length,
    columns: {
      elapsedSeconds: Float64Array.from(all.map(([t]) => t)),
      action: all.map(([, action]) => action),
      producer: all.map(() => 'phone'),
      producerSequence: Float64Array.from(all.map((_, i) => i + 1)),
      clockEpoch: all.map(() => null),
      interrupted: new Float64Array(all.length),
      cycSequence: new Float64Array(all.length).fill(NaN),
    } as ExportColumns<'lifecycle'>,
  });
  return builder.finish();
}

const span = (
  start: number,
  end: number,
  meters: number,
  values: Partial<DistanceInterval> = {},
): DistanceInterval => ({
  start,
  end,
  meters,
  segment: 0,
  startSpeed: NaN,
  endSpeed: NaN,
  ...values,
});

function points(ride: Timeline, intervals: DistanceInterval[]): number[][] {
  const builder = new DistancePoints(ride.intervals);
  const out = new Float64Array(6);
  const result: number[][] = [];
  for (const interval of intervals) {
    const count = builder.add(interval, out, 0);
    for (let i = 0; i < count; i++) result.push([out[3 * i]!, out[3 * i + 1]!, out[3 * i + 2]!]);
  }
  return result;
}

function ranges(kind: DistanceKind, ride: Timeline, intervals: DistanceInterval[]): DistanceRanges {
  const sweep = new DistanceRanges(kind, ride);
  for (const interval of intervals) sweep.add(interval);
  sweep.finish();
  return sweep;
}

describe('distance points', () => {
  const ride = timeline(20, [
    [8, 'pause'],
    [10, 'resume'],
  ]);

  it('emits a start point before the first interval, after a gap and at a segment change, then each end', () => {
    expect(
      points(ride, [
        span(1, 2, 5),
        span(2, 3, 4),
        span(4, 5, 3),
        span(5, 6, 2, { segment: 1 }),
        span(6, 7, 1, { segment: 1 }),
      ]),
    ).toEqual([
      [1, 0, 1],
      [2, 5, 1],
      [3, 9, 1],
      [4, 9, 1],
      [5, 12, 1],
      [5, 12, 1],
      [6, 14, 1],
      [7, 15, 1],
    ]);
  });

  it('compares missing segments as equal', () => {
    const builder = new DistancePoints(ride.intervals);
    const out = new Float64Array(6);
    expect(builder.add(span(1, 2, 1, { segment: NaN }), out, 0)).toBe(2);
    expect(builder.add(span(2, 3, 1, { segment: NaN }), out, 0)).toBe(1);
  });

  it('gives an endpoint at a pause or at E to the interval that closes there', () => {
    expect(points(ride, [span(7, 8, 2), span(10, 11, 3), span(19, 20, 1)])).toEqual([
      [7, 0, 1],
      [8, 2, 1],
      [10, 2, 2],
      [11, 5, 2],
      [19, 5, 2],
      [20, 6, 2],
    ]);
  });

  it('gives no activity to an interval outside every activity interval', () => {
    expect(points(ride, [span(7.5, 9, 2), span(9, 11, 2)])).toEqual([
      [7.5, 0, 0],
      [9, 2, 0],
      [11, 4, 0],
    ]);
  });

  it.each([
    ['an empty span', span(3, 3, 0)],
    ['a reversed span', span(3, 2, 0)],
    ['negative meters', span(1, 2, -0.1)],
    ['a missing end', span(1, NaN, 1)],
    ['a negative start', span(-1, 2, 1)],
  ])('rejects %s', (_, interval) => {
    expect(() => points(ride, [interval])).toThrow(ExportError);
  });

  it('rejects an interval overlapping the previous one', () => {
    let error: unknown;
    try {
      points(ride, [span(1, 3, 1), span(2.5, 4, 1)]);
    } catch (caught) {
      error = caught;
    }
    expect((error as ExportError).code).toBe('page');
  });

  it('encodes distances that fit uint32 centimeters after rounding', () => {
    expect(distanceEncodable(-0.004)).toBe(true);
    expect(distanceEncodable(-0.005)).toBe(false);
    expect(distanceEncodable(42949672.94)).toBe(true);
    expect(distanceEncodable(42949672.95)).toBe(false);
    expect(distanceEncodable(NaN)).toBe(false);
  });
});

describe('distance ranges', () => {
  const ride = timeline(12, [
    [3, 'lap'],
    [7, 'lap'],
  ]);

  it('clips an accelerating controller interval linearly and a constant one proportionally', () => {
    const sweep = ranges('controller', ride, [
      span(2, 4, 12, { startSpeed: 4, endSpeed: 8 }),
      span(6, 8, 10, { startSpeed: 5, endSpeed: 5 }),
    ]);
    expect(sweep.lap(0)).toEqual({ meters: 5, covered: 1, unresolved: false, partial: true });
    expect(sweep.lap(1)).toEqual({ meters: 12, covered: 2, unresolved: false, partial: true });
    expect(sweep.lap(2)).toEqual({ meters: 5, covered: 1, unresolved: false, partial: true });
    expect(sweep.total()).toEqual({ meters: 22, covered: 4, unresolved: false, partial: true });
  });

  it('clips a GPS interval in proportion to time', () => {
    const sweep = ranges('gps', ride, [span(2, 4, 10, { startSpeed: 4, endSpeed: 8 })]);
    expect(sweep.lap(0).meters).toBe(5);
    expect(sweep.lap(1).meters).toBe(5);
  });

  it('excludes an indivisible Health interval cut by a boundary and marks that range unresolved', () => {
    const sweep = ranges('health', ride, [span(2, 4, 10), span(4, 6, 8)]);
    expect(sweep.lap(0)).toEqual({ meters: 0, covered: 0, unresolved: true, partial: true });
    expect(sweep.lap(1)).toEqual({ meters: 8, covered: 2, unresolved: true, partial: true });
    expect(sweep.lap(2)).toEqual({ meters: 0, covered: 0, unresolved: false, partial: true });
    expect(sweep.total()).toEqual({ meters: 18, covered: 4, unresolved: false, partial: true });
  });

  it('keeps an observed zero as covered distance', () => {
    const sweep = ranges('gps', ride, [span(0, 3, 0)]);
    expect(sweep.lap(0)).toEqual({ meters: 0, covered: 3, unresolved: false, partial: false });
  });

  it('marks a range partial only when more than 0.001 s of active time is uncovered', () => {
    const covered = (end: number) => ranges('gps', timeline(10, []), [span(0, end, end)]).total().partial;
    expect(covered(9.999)).toBe(false);
    expect(covered(9.9989)).toBe(true);
    const paused = timeline(10, [
      [4, 'pause'],
      [6, 'resume'],
    ]);
    expect(ranges('gps', paused, [span(0, 4, 4), span(6, 10, 4)]).total()).toEqual({
      meters: 8,
      covered: 8,
      unresolved: false,
      partial: false,
    });
  });

  it('covers a time once an interval reaching it has been added', () => {
    const sweep = new DistanceRanges('gps', ride);
    expect(sweep.covers(0)).toBe(false);
    sweep.add(span(1, 4, 1));
    expect(sweep.covers(4)).toBe(true);
    expect(sweep.covers(4.5)).toBe(false);
    sweep.finish();
    expect(sweep.covers(12)).toBe(true);
  });
});
