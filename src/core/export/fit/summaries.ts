import type { FitClock } from '../clock';
import type { Timeline } from '../timeline';
import type { DistanceRange, DistanceRanges } from './distance';
import type { FitGeometry } from './geometry';
import { CADENCE_MAX, POWER_MAX, type LifecycleItem, type TelemetryRows } from './records';
import type { FitWriter } from './writer';

export const TELEMETRY_GAP_S = 2.5;
export const HEART_RATE_GAP_S = 10;

const FILE_ACTIVITY = 4;
const MANUFACTURER_DEVELOPMENT = 255;
const PRODUCT = 1;
const EVENT_TIMER = 0;
const EVENT_SESSION = 8;
const EVENT_LAP = 9;
const EVENT_ACTIVITY = 26;
const EVENT_TYPE_START = 0;
const EVENT_TYPE_STOP = 1;
const EVENT_TYPE_STOP_ALL = 4;
const SPORT_CYCLING = 2;
const SUB_SPORT_INDOOR_CYCLING = 6;
const SUB_SPORT_E_BIKE_FITNESS = 28;
const LAP_TRIGGER_MANUAL = 0;
const LAP_TRIGGER_SESSION_END = 7;
const ACTIVITY_MANUAL = 0;

export class Weighted {
  covered = 0;
  integral = 0;
  maximum = NaN;
  private time = NaN;
  private value = NaN;
  private key = 0;

  constructor(private readonly gap: number) {}

  reset(): void {
    this.time = NaN;
  }

  add(time: number, value: number, key: number): void {
    if (!(this.maximum >= value)) this.maximum = value;
    if (!Number.isNaN(this.time) && key === this.key) {
      const duration = time - this.time;
      if (duration > 0 && duration <= this.gap) {
        this.covered += duration;
        this.integral += (this.value + value) * 0.5 * duration;
      }
    }
    this.time = time;
    this.value = value;
    this.key = key;
  }

  addRows(rows: TelemetryRows, values: Float64Array, low: number, high: number): void {
    const { elapsed, interval, run } = rows;
    const count = rows.rows;
    const gap = this.gap;
    for (let i = 0; i < count; i++) {
      const value = values[i]!;
      if (interval[i] === 0 || !(value >= low && value <= high)) {
        this.time = NaN;
        continue;
      }
      if (!(this.maximum >= value)) this.maximum = value;
      const time = elapsed[i]!;
      const key = run[i]!;
      // After reset() the time is NaN, so is the duration, and the pair is skipped.
      const duration = time - this.time;
      if (key === this.key && duration > 0 && duration <= gap) {
        this.covered += duration;
        this.integral += (this.value + value) * 0.5 * duration;
      }
      this.time = time;
      this.value = value;
      this.key = key;
    }
  }

  get average(): number {
    return this.covered > 0 ? this.integral / this.covered : NaN;
  }
}

export class RideStatistics {
  readonly power = new Weighted(TELEMETRY_GAP_S);
  readonly cadence = new Weighted(TELEMETRY_GAP_S);
  readonly motor = new Weighted(TELEMETRY_GAP_S);
  readonly heartRate = new Weighted(HEART_RATE_GAP_S);
  maxSpeed = NaN;

  telemetry(rows: TelemetryRows): void {
    this.power.addRows(rows, rows.power, 0, POWER_MAX);
    this.cadence.addRows(rows, rows.cadence, 0, CADENCE_MAX);
    this.motor.addRows(rows, rows.motor, -Number.MAX_VALUE, Number.MAX_VALUE);
  }

  speed(value: number): void {
    if (!Number.isNaN(value) && !(this.maxSpeed >= value)) this.maxSpeed = value;
  }
}

const atLeastZero = (value: number) => (value >= 0 ? value : NaN);
const distanceOf = (range: DistanceRange | null) =>
  range !== null && range.covered > 0 && !range.unresolved ? range.meters * 100 : NaN;

export interface SummaryInput {
  readonly writer: FitWriter;
  readonly clock: FitClock;
  readonly timeline: Timeline;
  readonly ranges: DistanceRanges | null;
}

export function writeFileId({ writer, clock }: SummaryInput): void {
  writer.message('fileId', [FILE_ACTIVITY, MANUFACTURER_DEVELOPMENT, PRODUCT, clock.time(0)]);
}

export function writeTimer({ writer, clock }: SummaryInput, elapsed: number, start: boolean): void {
  writer.message('event', [clock.time(elapsed), EVENT_TIMER, start ? EVENT_TYPE_START : EVENT_TYPE_STOP_ALL, 0]);
}

export function writeLap({ writer, clock, timeline, ranges }: SummaryInput, index: number): void {
  const lap = timeline.laps[index]!;
  if (ranges && !ranges.covers(lap.end)) throw new Error('Lap distance was written before its distance intervals');
  const last = index === timeline.laps.length - 1;
  writer.message('lap', [
    clock.time(lap.end),
    index,
    EVENT_LAP,
    EVENT_TYPE_STOP,
    clock.time(lap.start),
    (lap.end - lap.start) * 1000,
    lap.timerSeconds * 1000,
    distanceOf(ranges ? ranges.lap(index) : null),
    last ? LAP_TRIGGER_SESSION_END : LAP_TRIGGER_MANUAL,
    SPORT_CYCLING,
  ]);
}

export function lifecycleWriter(input: SummaryInput): (item: LifecycleItem) => void {
  return item => {
    if (item.kind === 'lap') writeLap(input, item.lap);
    else writeTimer(input, item.elapsed, item.kind === 'start');
  };
}

export interface SessionInput extends SummaryInput {
  readonly indoor: boolean;
  readonly statistics: RideStatistics;
  readonly geometry: FitGeometry | null;
  readonly calories: number;
}

export function writeSession(input: SessionInput): void {
  const { writer, clock, timeline, ranges, statistics, geometry } = input;
  const end = timeline.end;
  const total = ranges ? ranges.total() : null;
  const averageSpeed = total !== null && !total.partial && total.covered > 0 ? total.meters / total.covered : NaN;
  const altitude = geometry !== null && geometry.altitudeSegments > 0;
  const { power, cadence, heartRate, motor } = statistics;
  writer.message('session', [
    clock.time(end),
    0,
    EVENT_SESSION,
    EVENT_TYPE_STOP,
    clock.time(0),
    SPORT_CYCLING,
    input.indoor ? SUB_SPORT_INDOOR_CYCLING : SUB_SPORT_E_BIKE_FITNESS,
    end * 1000,
    timeline.timerSeconds * 1000,
    distanceOf(total),
    input.calories,
    averageSpeed * 1000,
    statistics.maxSpeed * 1000,
    heartRate.average,
    heartRate.maximum,
    cadence.average,
    cadence.maximum,
    power.average,
    power.maximum,
    altitude ? geometry.ascent : NaN,
    altitude ? geometry.descent : NaN,
    0,
    timeline.laps.length,
    power.covered > 0 ? power.integral : NaN,
    atLeastZero(motor.average),
    atLeastZero(motor.maximum),
  ]);
}

export function writeActivity({ writer, clock, timeline }: SummaryInput): void {
  writer.message('activity', [
    clock.time(timeline.end),
    timeline.timerSeconds * 1000,
    1,
    ACTIVITY_MANUAL,
    EVENT_ACTIVITY,
    EVENT_TYPE_STOP,
  ]);
}
