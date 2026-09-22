import { describe, expect, it } from 'vitest';
import { decimateChart, findChartSample, MAX_CHART_BUCKETS, type ChartPoint } from '../../src/core/chart';

const watts = (sample: { watts: number }): number => sample.watts;
function readings(times: readonly number[], values?: readonly number[]) {
  return times.map((elapsedSeconds, index) => ({ elapsedSeconds, watts: values?.[index] ?? 0 }));
}
function segments(points: readonly ChartPoint[]): ChartPoint[][] {
  const result: ChartPoint[][] = [];
  for (const point of points) {
    if (point.startsSegment) result.push([]);
    result[result.length - 1]!.push(point);
  }
  return result;
}

describe('telemetry chart decimation', () => {
  it('does not invent gaps when irregular adjacent samples are continuous but retained points are far apart', () => {
    const input = readings([0, 0.125, 0.25, 1.75, 2, 4.5, 4.75, 7.25, 7.5, 10]);
    const points = decimateChart(input, watts, 1);
    expect(points.map(point => point.sampleIndex)).toEqual([0, 9]);
    expect(points.map(point => point.startsSegment)).toEqual([true, false]);
    expect(segments(points)).toHaveLength(1);
  });

  it('retains exactly one real gap at a bucket edge without discarding the first post-gap segment', () => {
    const input = readings([0, 0.5, 1, 1.5, 2, 8, 8.5, 9, 9.5, 10]);
    const points = decimateChart(input, watts, 2);
    expect(segments(points).map(run => run.map(point => point.sampleIndex))).toEqual([[0, 4], [5, 9]]);
    expect(points.map(point => point.startsSegment)).toEqual([true, false, true, false]);
    expect(points[1]!.elapsedSeconds).toBe(2);
    expect(points[2]!.elapsedSeconds).toBe(8);
  });

  it('splits a bucket at a real gap and retains the exact observation on either side', () => {
    const input = readings([0, 0.5, 1, 1.5, 8, 8.5, 9, 9.5], [8, 1, 12, 5, 7, 2, 15, 6]);
    const points = decimateChart(input, watts, 1);
    const runs = segments(points);
    expect(runs).toHaveLength(2);
    expect(runs[0]!.map(point => point.sampleIndex)).toEqual([0, 1, 2, 3]);
    expect(runs[1]!.map(point => point.sampleIndex)).toEqual([4, 5, 6, 7]);
    expect(points.filter(point => point.startsSegment).map(point => point.sampleIndex)).toEqual([0, 4]);
  });

  it('preserves extrema in their actual time order together with both endpoints', () => {
    const input = readings([0, 0.5, 1, 1.5, 2, 2.5], [7, 2, 950, -10, 3, 9]);
    const points = decimateChart(input, watts, 1);
    expect(points.map(point => point.sampleIndex)).toEqual([0, 2, 3, 5]);
    expect(points.map(point => point.value)).toEqual([7, 950, -10, 9]);
    expect(points.map(point => point.startsSegment)).toEqual([true, false, false, false]);
  });

  it('retains flat zero as measured data while reducing a long continuous run to endpoints', () => {
    const input = Array.from({ length: 200 }, (_, index) => ({ elapsedSeconds: index / 4, watts: 0 }));
    const points = decimateChart(input, watts, 1);
    expect(points).toEqual([
      { sampleIndex: 0, elapsedSeconds: 0, value: 0, startsSegment: true },
      { sampleIndex: 199, elapsedSeconds: 49.75, value: 0, startsSegment: false },
    ]);
  });

  it('keeps singleton segments and the last observation without connecting across real missing data', () => {
    const input = readings([0, 6, 6.5, 12.5], [10, 20, 25, 30]);
    const points = decimateChart(input, watts, 1);
    expect(segments(points).map(run => run.map(point => point.sampleIndex))).toEqual([[0], [1, 2], [3]]);
    expect(decimateChart(readings([4], [0]), watts, 64)).toEqual([{ sampleIndex: 0, elapsedSeconds: 4, value: 0, startsSegment: true }]);
    expect(decimateChart([], watts, 64)).toEqual([]);
  });

  it('uses the six-second display threshold without changing capture freshness', () => {
    expect(segments(decimateChart(readings([0, 5.999, 10]), watts, 1))).toHaveLength(1);
    expect(segments(decimateChart(readings([0, 6, 10]), watts, 1))).toHaveLength(2);
  });

  it('retains every real break even if several gaps occupy one pixel bucket', () => {
    const input = readings([0, 0.5, 6.5, 7, 13, 13.5, 19.5, 20]);
    const runs = segments(decimateChart(input, watts, 1));
    expect(runs.map(run => run.map(point => point.sampleIndex))).toEqual([[0, 1], [2, 3], [4, 5], [6, 7]]);
  });

  it('treats invalid values or elapsed-time rollback as breaks, not synthetic zeros or connecting lines', () => {
    const invalidValues = decimateChart(readings([0, 0.5, 1, 1.5, 2], [0, 2, Number.NaN, 3, 0]), watts, 1);
    expect(segments(invalidValues).map(run => run.map(point => point.sampleIndex))).toEqual([[0, 1], [3, 4]]);
    const invalidTime = decimateChart(readings([0, 0.5, Number.NaN, 1.5, 2]), watts, 1);
    expect(segments(invalidTime)).toHaveLength(2);
    expect(segments(decimateChart(readings([0, 1, 0.5, 1.5]), watts, 1))).toHaveLength(2);
    expect(decimateChart(readings([Number.NaN, Number.POSITIVE_INFINITY]), watts, 1)).toEqual([]);
  });

  it('bounds retained output for more than 200,000 readings while preserving spikes and gap endpoints', () => {
    const count = 250_001; const gapIndex = 125_000; const bucketCount = 256;
    const input = Array.from({ length: count }, (_, index) => ({
      elapsedSeconds: index / 8 + (index >= gapIndex ? 6 : 0),
      watts: index === 100_001 ? 900 : index === 220_001 ? 4000 : 0,
    }));
    let reads = 0;
    const points = decimateChart(input, sample => { reads += 1; return sample.watts; }, bucketCount);
    const retained = points.map(point => point.sampleIndex);
    expect(reads).toBe(count);
    expect(points.length).toBeLessThanOrEqual(4 * (bucketCount + 1));
    expect(retained).toEqual([...new Set(retained)].sort((a, b) => a - b));
    for (const index of [0, count - 1, 100_001, 220_001, gapIndex - 1, gapIndex]) expect(retained).toContain(index);
    expect(points.filter(point => point.startsSegment).map(point => point.sampleIndex)).toEqual([0, gapIndex]);
    expect(decimateChart(input, watts, 1_000_000).length).toBeLessThanOrEqual(4 * (MAX_CHART_BUCKETS + 1));
  });

  it('rejects unusable options instead of producing invalid geometry', () => {
    for (const buckets of [0, -1, Number.NaN, Number.POSITIVE_INFINITY]) expect(() => decimateChart([], watts, buckets)).toThrow('bucket');
    for (const gap of [0, -1, Number.NaN]) expect(() => decimateChart([], watts, 1, gap)).toThrow('gap');
  });
});

describe('source-sample chart inspection', () => {
  it('finds the nearest original observation even when decimation removed it', () => {
    const input = readings([0, 0.1, 0.35, 1.5, 2], [100, 100.1234, 100.5, 101, 102]);
    expect(decimateChart(input, watts, 1).map(point => point.sampleIndex)).not.toContain(1);
    const selected = findChartSample(input, 0.12);
    expect(selected.sampleIndex).toBe(1);
    expect(selected.sample).toBe(input[1]);
    expect(selected.sample?.watts).toBe(100.1234);
    expect(findChartSample(input, 1.4).sample).toBe(input[3]);
  });

  it('never lends readings to a real gap, including immediately beside its endpoints', () => {
    const input = readings([0, 0.125, 0.25, 6.25, 6.375], [0, 1, 2, 3, 4]);
    for (const time of [0.250001, 1, 3.25, 6.249999]) {
      expect(findChartSample(input, time)).toMatchObject({ sample: null, sampleIndex: null, unavailable: 'gap' });
    }
    expect(findChartSample(input, 0.25).sample).toBe(input[2]);
    expect(findChartSample(input, 6.25).sample).toBe(input[3]);
  });

  it('uses the same exclusive six-second continuity boundary as drawing, with deterministic earlier ties', () => {
    const continuous = readings([0, 5.5], [0, 800]);
    expect(findChartSample(continuous, 2.75).sample).toBe(continuous[0]);
    expect(findChartSample(continuous, 2.750001).sample).toBe(continuous[1]);
    expect(findChartSample(readings([0, 6]), 3).unavailable).toBe('gap');
  });

  it('preserves measured zero and does not extrapolate empty, singleton or short recordings', () => {
    const input = readings([4], [0]);
    expect(findChartSample(input, 4).sample?.watts).toBe(0);
    for (const time of [0, 3.999999, 4.000001, 10]) expect(findChartSample(input, time).unavailable).toBe('outside');
    expect(findChartSample([], 0).unavailable).toBe('empty');
    for (const time of [Number.NaN, Number.POSITIVE_INFINITY]) expect(findChartSample(input, time).unavailable).toBe('invalid');
    expect(findChartSample(readings([Number.NaN]), 0).unavailable).toBe('invalid');
    expect(findChartSample(input, 4, 0).unavailable).toBe('invalid');
  });

  it('uses elapsed capture time rather than a corrected wall clock and keeps all selected channel values together', () => {
    const input = [
      { elapsedSeconds: 50, timestamp: '2026-01-01T12:00:00.000Z', humanPowerW: 219, cadenceRpm: 87.1256, motorInputPowerW: 453.9876 },
      { elapsedSeconds: 50.125, timestamp: '2026-01-01T11:59:59.000Z', humanPowerW: 225, cadenceRpm: 88.1234, motorInputPowerW: 456.1234 },
    ];
    expect(findChartSample(input, 50.1).sample).toBe(input[1]);
  });

  it('does logarithmic lookup for a ride containing more than 200,000 observations', () => {
    let reads = 0;
    const count = 250_001;
    const input = Array.from({ length: count }, (_, index) => ({
      get elapsedSeconds() { reads += 1; return index / 8; },
      watts: index,
    }));
    const result = findChartSample(input, 212_345 / 8 + 0.01);
    expect(result.sample).toBe(input[212_345]);
    expect(reads).toBeLessThan(40);
  });
});
