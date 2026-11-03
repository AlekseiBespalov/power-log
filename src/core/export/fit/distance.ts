import {
  AnalyticsDistanceReducer,
  analyticsDivide,
  analyticsQuantize,
  analyticsStoredInterval,
  analyticsTimeUs,
  type AnalyticsInterval,
} from '../../../../shared/js';
import { fitOriginal } from './core';
import type { Timeline } from '../timeline';
import { ExportError, type ExportDistanceProfile } from '../types';
import { fitInteger } from './writer';

export type DistanceKind = ExportDistanceProfile['kind'];

export interface DistanceInterval {
  readonly startUs: string;
  readonly endUs: string;
  readonly meters: number;
  readonly segment: number;
  readonly startSpeed: number;
  readonly endSpeed: number;
}

export interface DistanceRange {
  readonly meters: number;
  readonly covered: number;
  readonly unresolved: boolean;
  readonly partial: boolean;
}

const malformed = (problem: string) =>
  new ExportError('page', `The ride data could not be read for export: a distance interval ${problem}.`);

const sameSegment = (a: number, b: number) => a === b || (Number.isNaN(a) && Number.isNaN(b));

function intervalTime(value: string): bigint {
  if (!/^(0|[1-9]\d*)$/.test(value)) throw malformed('has an invalid microsecond endpoint');
  const time = BigInt(value);
  if (time > 2678400000000n) throw malformed('has an unsupported microsecond endpoint');
  return time;
}

export class DistancePoints {
  // Four values per point: elapsed seconds, cumulative meters, activity interval and controller speed (NaN for GPS).
  values = new Float64Array(0);
  count = 0;
  private readonly total = new AnalyticsDistanceReducer('0', '2678400000000');
  private previousEnd: bigint | null = null;
  private previousSegment = NaN;
  private started = false;
  private activity = 0;

  private readonly intervals: { id: number; open: bigint; close: bigint }[];

  constructor(
    timeline: Timeline,
    private readonly kind: DistanceKind = 'gps',
  ) {
    this.intervals = timeline.intervals.map((interval, i) => ({
      id: interval.id,
      open: BigInt(timeline.analytics?.startsUs[i] ?? analyticsTimeUs(interval.open)),
      close: BigInt(timeline.analytics?.endsUs[i] ?? analyticsTimeUs(interval.close)),
    }));
  }

  clear(): void {
    this.count = 0;
  }

  add(interval: DistanceInterval): number {
    const { meters, segment, startSpeed, endSpeed } = interval;
    const start = intervalTime(interval.startUs);
    const end = intervalTime(interval.endUs);
    if (!Number.isFinite(meters) || start < 0n || end <= start || meters < 0) throw malformed('is not a valid span');
    if (this.started && start < this.previousEnd!) throw malformed('overlaps the previous one');
    const activity = this.containing(start, end);
    const controller = this.kind === 'controller';
    const first = this.count;
    if (!this.started || !sameSegment(segment, this.previousSegment) || start !== this.previousEnd)
      this.push(start, this.total.result('0').distanceMeters, activity, controller ? startSpeed : NaN);
    const item = coreInterval(interval, this.kind, activity);
    if (controller && end - start > 1000000n) {
      const { distanceQ, coveredUs } = this.total.result('0');
      for (let at = start + 1000000n; at < end; at += 1000000n) {
        const reached = new AnalyticsDistanceReducer('0', at.toString(), distanceQ, coveredUs);
        reached.append(item);
        const share = Number(at - start) / Number(end - start);
        this.push(at, reached.result('0').distanceMeters, activity, startSpeed + (endSpeed - startSpeed) * share);
      }
    }
    this.total.append(item);
    this.push(end, this.total.result('0').distanceMeters, activity, controller ? endSpeed : NaN);
    this.started = true;
    this.previousEnd = end;
    this.previousSegment = segment;
    return this.count - first;
  }

  private push(time: bigint, meters: number, activity: number, speed: number): void {
    const at = 4 * this.count;
    if (at === this.values.length) {
      const grown = new Float64Array(Math.max(1024, 2 * this.values.length));
      grown.set(this.values);
      this.values = grown;
    }
    this.values[at] = Number(time) / 1e6;
    this.values[at + 1] = meters;
    this.values[at + 2] = activity;
    this.values[at + 3] = speed;
    this.count++;
  }

  private containing(start: bigint, end: bigint): number {
    const intervals = this.intervals;
    while (this.activity < intervals.length && intervals[this.activity]!.close < end) this.activity++;
    const candidate = intervals[this.activity];
    return candidate && candidate.open <= start && end <= candidate.close ? candidate.id : 0;
  }
}

function coreInterval(interval: DistanceInterval, kind: DistanceKind, activeInterval = 0): AnalyticsInterval {
  const { startUs, endUs } = interval;
  const quantize = (metric: string, value: number) =>
    Number.isFinite(value) ? (analyticsQuantize(metric, 'real64', String(value)) ?? null) : null;
  return analyticsStoredInterval(
    startUs,
    endUs,
    quantize('distanceMeters', interval.meters)!,
    Number.isFinite(interval.segment) ? interval.segment : 0,
    activeInterval,
    fitOriginal({ elapsed: Number(startUs) / 1e6, timeUs: startUs, kind: 'location' }),
    fitOriginal({ elapsed: Number(endUs) / 1e6, timeUs: endUs, kind: 'location' }),
    kind === 'controller' ? quantize('controllerSpeedMps', interval.startSpeed) : null,
    kind === 'controller' ? quantize('controllerSpeedMps', interval.endSpeed) : null,
    false,
  );
}

export class DistanceRanges {
  private readonly laps: AnalyticsDistanceReducer[];
  private readonly session: AnalyticsDistanceReducer;
  private next = 0;
  private reached: bigint | null = null;
  private complete = false;

  constructor(
    readonly kind: DistanceKind,
    private readonly timeline: Timeline,
  ) {
    this.session = new AnalyticsDistanceReducer('0', timeline.endUs);
    this.laps = timeline.lapTimes.map(lap => new AnalyticsDistanceReducer(lap.startUs, lap.endUs));
  }

  add(interval: DistanceInterval): void {
    const item = coreInterval(interval, this.kind);
    this.session.append(item);
    const laps = this.timeline.lapTimes;
    const start = intervalTime(interval.startUs);
    const end = intervalTime(interval.endUs);
    while (this.next < laps.length && BigInt(laps[this.next]!.endUs) <= start) this.next++;
    for (let j = this.next; j < laps.length && BigInt(laps[j]!.startUs) < end; j++) this.laps[j]!.append(item);
    this.reached = end;
  }

  finish(): void {
    this.complete = true;
  }
  covers(timeUs: string): boolean {
    return this.complete || (this.reached !== null && this.reached >= BigInt(timeUs));
  }

  lap(index: number): DistanceRange {
    const lap = this.timeline.lapTimes[index]!;
    return this.range(this.laps[index]!, lap.timerUs);
  }

  total(): DistanceRange {
    return this.range(this.session, this.timeline.timerUs);
  }

  averageSpeed(): number {
    const result = this.session.result(this.timeline.timerUs);
    return result.coveredUs === '0' ? NaN : analyticsDivide(result.distanceQ, result.coveredUs);
  }

  private range(reducer: AnalyticsDistanceReducer, timerUs: string): DistanceRange {
    const result = reducer.result(timerUs);
    return {
      meters: result.distanceMeters,
      covered: result.coveredSeconds,
      unresolved: result.unresolvedBoundary,
      partial: result.partial,
    };
  }
}

export function distanceEncodable(meters: number): boolean {
  return !Number.isNaN(fitInteger(meters * 100, 'uint32'));
}
