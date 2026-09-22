import { TELEMETRY_DISPLAY_HOLD_SECONDS } from './telemetry-display';

export const MAX_CHART_BUCKETS = 2048;

export interface ChartPoint {
  sampleIndex: number;
  elapsedSeconds: number;
  value: number;
  startsSegment: boolean;
}

export type ChartSelection<T> = {
  requestedSeconds: number;
  sample: T;
  sampleIndex: number;
  unavailable?: never;
} | {
  requestedSeconds: number;
  sample: null;
  sampleIndex: null;
  unavailable: 'empty' | 'outside' | 'gap' | 'invalid';
};

/** First source observation at/after a time, in validated, increasing capture order. */
function lowerBound<T extends { elapsedSeconds: number }>(samples: readonly T[], seconds: number): number {
  let low = 0; let high = samples.length;
  while (low < high) {
    const middle = low + Math.floor((high - low) / 2);
    const time = samples[middle]!.elapsedSeconds;
    if (!Number.isFinite(time)) return -1;
    if (time < seconds) low = middle + 1;
    else high = middle;
  }
  return low;
}

/**
 * Inspect original, validated samples in O(log n), independently of decimation.
 * Snap to the nearest observation within a continuous span (ties go earlier).
 * An actual gap or time outside the recording never borrows a neighbouring value.
 */
export function findChartSample<T extends { elapsedSeconds: number }>(
  samples: readonly T[], seconds: number, maxGapSeconds = TELEMETRY_DISPLAY_HOLD_SECONDS,
): ChartSelection<T> {
  const missing = (unavailable: 'empty' | 'outside' | 'gap' | 'invalid'): ChartSelection<T> =>
    ({ requestedSeconds: seconds, sample: null, sampleIndex: null, unavailable });
  if (!Number.isFinite(seconds) || !Number.isFinite(maxGapSeconds) || maxGapSeconds <= 0) return missing('invalid');
  if (!samples.length) return missing('empty');
  const first = samples[0]!.elapsedSeconds; const last = samples[samples.length - 1]!.elapsedSeconds;
  if (!Number.isFinite(first) || !Number.isFinite(last) || last < first) return missing('invalid');
  if (seconds < first || seconds > last) return missing('outside');
  const after = lowerBound(samples, seconds);
  if (after < 0 || after >= samples.length) return missing('invalid');
  const next = samples[after]!;
  const found = (sampleIndex: number): ChartSelection<T> => ({ requestedSeconds: seconds, sample: samples[sampleIndex]!, sampleIndex });
  if (next.elapsedSeconds === seconds) return found(after);
  const previous = samples[after - 1];
  if (!previous || !Number.isFinite(previous.elapsedSeconds) || next.elapsedSeconds <= previous.elapsedSeconds) return missing('invalid');
  if (next.elapsedSeconds - previous.elapsedSeconds >= maxGapSeconds) return missing('gap');
  return found(seconds - previous.elapsedSeconds <= next.elapsedSeconds - seconds ? after - 1 : after);
}

/**
 * Retain first/min/max/last per time bucket and contiguous run. Breaks are decided
 * from adjacent input samples before decimation, never from the distance between
 * retained points. For ordered telemetry, memory/output is O(buckets + real breaks),
 * with one input pass. Real breaks remain explicit even when they share a bucket.
 */
export function decimateChart<T extends { elapsedSeconds: number }>(
  samples: readonly T[],
  valueOf: (sample: T) => number,
  requestedBuckets: number,
  maxGapSeconds = TELEMETRY_DISPLAY_HOLD_SECONDS,
): ChartPoint[] {
  if (!Number.isFinite(requestedBuckets) || requestedBuckets < 1) throw new Error('Chart bucket count must be positive');
  if (!Number.isFinite(maxGapSeconds) || maxGapSeconds <= 0) throw new Error('Chart gap threshold must be positive');
  const bucketCount = Math.min(MAX_CHART_BUCKETS, Math.floor(requestedBuckets));
  if (samples.length === 0) return [];

  let start = 0; let end = samples.length - 1;
  while (start <= end && !Number.isFinite(samples[start]!.elapsedSeconds)) start += 1;
  while (end >= start && !Number.isFinite(samples[end]!.elapsedSeconds)) end -= 1;
  if (start > end) return [];
  const firstTime = samples[start]!.elapsedSeconds;
  const duration = Math.max(0, samples[end]!.elapsedSeconds - firstTime);
  const points: ChartPoint[] = [];
  let previousTime: number | undefined;
  let bucket = -1;
  let first: ChartPoint | undefined;
  let last: ChartPoint | undefined;
  let low: ChartPoint | undefined;
  let high: ChartPoint | undefined;

  const flush = (): void => {
    if (!first || !last || !low || !high) return;
    const candidates = [first, low, high, last].sort((a, b) => a.sampleIndex - b.sampleIndex);
    let previousIndex = -1;
    for (const point of candidates) {
      if (point.sampleIndex === previousIndex) continue;
      points.push(point);
      previousIndex = point.sampleIndex;
    }
    first = last = low = high = undefined;
  };

  for (let index = start; index <= end; index += 1) {
    const sample = samples[index]!;
    const time = sample.elapsedSeconds;
    const value = valueOf(sample);
    if (!Number.isFinite(time) || !Number.isFinite(value)) {
      flush(); previousTime = undefined; bucket = -1;
      continue;
    }
    const startsSegment = previousTime === undefined || time <= previousTime || time - previousTime >= maxGapSeconds;
    const nextBucket = duration > 0 ? Math.max(0, Math.min(bucketCount - 1, Math.floor((time - firstTime) / duration * bucketCount))) : 0;
    if (startsSegment || nextBucket !== bucket) flush();
    const point: ChartPoint = { sampleIndex: index, elapsedSeconds: time, value, startsSegment };
    if (!first) first = low = high = point;
    else {
      if (value < low!.value) low = point;
      if (value > high!.value) high = point;
    }
    last = point;
    previousTime = time;
    bucket = nextBucket;
  }
  flush();
  return points;
}
