import { analyticsTimeUs } from '../../shared/js';
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
      controllerSequence: new Float64Array(all.length).fill(NaN),
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
  startUs: Number.isFinite(start) && start >= 0 ? analyticsTimeUs(start) : '',
  endUs: Number.isFinite(end) && end >= 0 ? analyticsTimeUs(end) : '',
  meters,
  segment: 0,
  startSpeed: NaN,
  endSpeed: NaN,
  ...values,
});

function points(ride: Timeline, intervals: DistanceInterval[], kind: DistanceKind = 'gps'): number[][] {
  const builder = new DistancePoints(ride, kind);
  for (const interval of intervals) builder.add(interval);
  return Array.from({ length: builder.count }, (_, i) => [...builder.values.subarray(4 * i, 4 * i + 4)]);
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
      [1, 0, 1, NaN],
      [2, 5, 1, NaN],
      [3, 9, 1, NaN],
      [4, 9, 1, NaN],
      [5, 12, 1, NaN],
      [5, 12, 1, NaN],
      [6, 14, 1, NaN],
      [7, 15, 1, NaN],
    ]);
  });

  it('compares missing segments as equal', () => {
    const builder = new DistancePoints(ride);
    expect(builder.add(span(1, 2, 1, { segment: NaN }))).toBe(2);
    expect(builder.add(span(2, 3, 1, { segment: NaN }))).toBe(1);
  });

  it('gives an endpoint at a pause or at E to the interval that closes there', () => {
    expect(points(ride, [span(7, 8, 2), span(10, 11, 3), span(19, 20, 1)])).toEqual([
      [7, 0, 1, NaN],
      [8, 2, 1, NaN],
      [10, 2, 2, NaN],
      [11, 5, 2, NaN],
      [19, 5, 2, NaN],
      [20, 6, 2, NaN],
    ]);
  });

  it('gives no activity to an interval outside every activity interval', () => {
    expect(points(ride, [span(7.5, 9, 2), span(9, 11, 2)])).toEqual([
      [7.5, 0, 0, NaN],
      [9, 2, 0, NaN],
      [11, 4, 0, NaN],
    ]);
  });

  it('adds each whole second inside a controller interval with its clipped distance and straight-line speed', () => {
    expect(
      points(
        ride,
        [
          span(1, 2, 3, { startSpeed: 2, endSpeed: 4 }),
          span(2, 6, 24, { startSpeed: 4, endSpeed: 8 }),
          span(6, 7.5, 12, { startSpeed: 8, endSpeed: 8 }),
          span(10.5, 11.5, 6, { startSpeed: 6, endSpeed: 6, segment: 1 }),
        ],
        'controller',
      ),
    ).toEqual([
      [1, 0, 1, 2],
      [2, 3, 1, 4],
      [3, 7.5, 1, 5],
      [4, 13, 1, 6],
      [5, 19.5, 1, 7],
      [6, 27, 1, 8],
      [7, 35, 1, 8],
      [7.5, 39, 1, 8],
      [10.5, 39, 2, 6],
      [11.5, 45, 2, 6],
    ]);
  });

  it('grows its buffer for as many points as a page needs', () => {
    const intervals = Array.from({ length: 299 }, (_, i) =>
      span(2 * i, 2 * i + 1.5, 3, { startSpeed: 2, endSpeed: 2 }),
    );
    const all = points(timeline(600, []), intervals, 'controller');
    expect(all).toHaveLength(3 * 299);
    expect(all.slice(-3)).toEqual([
      [596, 894, 1, 2],
      [597, 896, 1, 2],
      [597.5, 897, 1, 2],
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

  it('averages the distance over its covered time even when coverage is partial', () => {
    const sweep = ranges('controller', ride, [span(2, 4, 12, { startSpeed: 4, endSpeed: 8 })]);
    expect(sweep.total().partial).toBe(true);
    expect(sweep.averageSpeed()).toBe(6);
    expect(ranges('gps', ride, []).averageSpeed()).toBeNaN();
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
    expect(sweep.covers('0')).toBe(false);
    sweep.add(span(1, 4, 1));
    expect(sweep.covers('4000000')).toBe(true);
    expect(sweep.covers('4500000')).toBe(false);
    sweep.finish();
    expect(sweep.covers('12000000')).toBe(true);
  });
});
