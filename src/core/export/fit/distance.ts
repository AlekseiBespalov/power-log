import { clipControllerDistance } from '../../distance';
import type { ActivityInterval, Timeline } from '../timeline';
import { ExportError, type ExportDistanceProfile } from '../types';
import { fitInteger } from './writer';

export const DISTANCE_PARTIAL_SECONDS = 0.001;

export type DistanceKind = ExportDistanceProfile['kind'];

export interface DistanceInterval {
  readonly start: number;
  readonly end: number;
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

interface Accumulator {
  meters: number;
  covered: number;
  unresolved: boolean;
}

const malformed = (problem: string) =>
  new ExportError('page', `The ride data could not be read for export: a distance interval ${problem}.`);

const sameSegment = (a: number, b: number) => a === b || (Number.isNaN(a) && Number.isNaN(b));

export class DistancePoints {
  private total = 0;
  private previousEnd = NaN;
  private previousSegment = NaN;
  private started = false;
  private activity = 0;

  constructor(private readonly intervals: readonly ActivityInterval[]) {}

  // Writes [elapsed, cumulative meters, activity] triples from `at` and returns how many points it wrote.
  add(interval: DistanceInterval, out: Float64Array, at: number): number {
    const { start, end, meters, segment } = interval;
    if (![start, end, meters].every(Number.isFinite) || start < 0 || end <= start || meters < 0)
      throw malformed('is not a valid span');
    if (this.started && start < this.previousEnd) throw malformed('overlaps the previous one');
    const activity = this.containing(start, end);
    let added = 0;
    if (!this.started || !sameSegment(segment, this.previousSegment) || start !== this.previousEnd) {
      out[at] = start;
      out[at + 1] = this.total;
      out[at + 2] = activity;
      added++;
    }
    this.total += meters;
    out[at + 3 * added] = end;
    out[at + 3 * added + 1] = this.total;
    out[at + 3 * added + 2] = activity;
    added++;
    this.started = true;
    this.previousEnd = end;
    this.previousSegment = segment;
    return added;
  }

  private containing(start: number, end: number): number {
    const intervals = this.intervals;
    while (this.activity < intervals.length && intervals[this.activity]!.close < end) this.activity++;
    const candidate = intervals[this.activity];
    return candidate && candidate.open <= start && end <= candidate.close ? candidate.id : 0;
  }
}

export class DistanceRanges {
  private readonly laps: Accumulator[];
  private readonly session: Accumulator = { meters: 0, covered: 0, unresolved: false };
  private next = 0;
  private reached = -Infinity;
  private complete = false;

  constructor(
    readonly kind: DistanceKind,
    private readonly timeline: Timeline,
  ) {
    this.laps = timeline.laps.map(() => ({ meters: 0, covered: 0, unresolved: false }));
  }

  add(interval: DistanceInterval): void {
    const laps = this.timeline.laps;
    this.contribute(interval, 0, this.timeline.end, this.session);
    while (this.next < laps.length && laps[this.next]!.end <= interval.start) this.next++;
    for (let j = this.next; j < laps.length && laps[j]!.start < interval.end; j++)
      this.contribute(interval, laps[j]!.start, laps[j]!.end, this.laps[j]!);
    this.reached = interval.end;
  }

  finish(): void {
    this.complete = true;
  }

  covers(time: number): boolean {
    return this.complete || this.reached >= time;
  }

  lap(index: number): DistanceRange {
    const lap = this.timeline.laps[index]!;
    return this.range(this.laps[index]!, lap.start, lap.end);
  }

  total(): DistanceRange {
    return this.range(this.session, 0, this.timeline.end);
  }

  private range(accumulator: Accumulator, start: number, end: number): DistanceRange {
    const { meters, covered, unresolved } = accumulator;
    const partial = unresolved || this.timeline.timer(start, end) - covered > DISTANCE_PARTIAL_SECONDS;
    return { meters, covered, unresolved, partial };
  }

  private contribute(interval: DistanceInterval, a: number, b: number, into: Accumulator): void {
    const { start, end, meters } = interval;
    const lo = Math.max(a, start);
    const hi = Math.min(b, end);
    if (hi <= lo) return;
    if (lo === start && hi === end) {
      into.meters += meters;
      into.covered += end - start;
      return;
    }
    if (this.kind === 'health') {
      into.unresolved = true;
      return;
    }
    if (this.kind === 'controller' && Number.isFinite(interval.startSpeed) && Number.isFinite(interval.endSpeed)) {
      const clipped = clipControllerDistance(interval, a, b);
      into.meters += clipped.distance;
      into.covered += clipped.covered;
      return;
    }
    into.meters += (meters * (hi - lo)) / (end - start);
    into.covered += hi - lo;
  }
}

export function distanceEncodable(meters: number): boolean {
  return !Number.isNaN(fitInteger(meters * 100, 'uint32'));
}
