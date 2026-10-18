import { AnalyticsFitStatistics, type AnalyticsStatistics } from '../../../../shared/js';
import type { FitClock } from '../clock';
import type { Timeline } from '../timeline';
import type { DistanceRange, DistanceRanges } from './distance';
import type { FitGeometry } from './geometry';
import { type LifecycleItem, type TelemetryRows } from './records';
import type { FitWriter } from './writer';

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

const metric = (result: AnalyticsStatistics) => ({
  covered: result.coveredSeconds ?? 0,
  integral: result.integral ?? NaN,
  maximum: result.max?.value ?? NaN,
  average: result.average ?? NaN,
});

export class RideStatistics {
  private readonly core: AnalyticsFitStatistics;
  get maxSpeed(): number {
    return this.core.maximumSpeed() ?? NaN;
  }

  constructor(endUs: string) {
    this.core = new AnalyticsFitStatistics(endUs);
  }

  get power() {
    return metric(this.core.powerResult());
  }
  get cadence() {
    return metric(this.core.cadenceResult());
  }
  get motor() {
    return metric(this.core.motorResult());
  }
  get heartRate() {
    return metric(this.core.heartRateResult());
  }

  telemetry(rows: TelemetryRows): void {
    this.core.telemetry(rows.elapsed, rows.power, rows.cadence, rows.motor, rows.interval, rows.run);
  }

  heart(
    time: number,
    value: number,
    interval: number,
    interruption: number,
    clock: string | null,
    connection: string | null,
  ): void {
    this.core.heartRate(time, value, interval, interruption, clock, connection);
  }

  speed(time: number, value: number, interval: number): void {
    this.core.gpsSpeed(time, value, interval);
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
  const time = timeline.lapTimes[index]!;
  if (ranges && !ranges.covers(time.endUs)) throw new Error('Lap distance was written before its distance intervals');
  const last = index === timeline.laps.length - 1;
  writer.message('lap', [
    clock.time(lap.end),
    index,
    EVENT_LAP,
    EVENT_TYPE_STOP,
    clock.time(lap.start),
    Number(BigInt(time.endUs) - BigInt(time.startUs)) / 1000,
    Number(time.timerUs) / 1000,
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
  const averageSpeed = ranges?.averageSpeed() ?? NaN;
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
    Number(timeline.endUs) / 1000,
    Number(timeline.timerUs) / 1000,
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
    Number(timeline.timerUs) / 1000,
    1,
    ACTIVITY_MANUAL,
    EVENT_ACTIVITY,
    EVENT_TYPE_STOP,
  ]);
}
