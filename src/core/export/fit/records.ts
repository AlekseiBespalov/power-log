import { FIT_DEVELOPER_FIELDS } from '../catalog';
import { roundHalfAway, type FitClock } from '../clock';
import type { ExportSlicer, ProjectionPage } from '../pages';
import type { ActivityInterval, RunTracker, Timeline, TimelineCursor } from '../timeline';
import { ExportError, type Producer } from '../types';
import { DistancePoints, distanceEncodable, type DistanceInterval, type DistanceRanges } from './distance';
import { FitGeometry, emptyFix, validAltitude, validSpeed } from './geometry';
import { healthProducer, heartRateKind, validHeartRate, type HeartRatePlan } from './health';
import type { RideStatistics } from './summaries';
import { FLOAT32_OVERFLOW, finiteAsFloat32, type FitWriter } from './writer';

export const TELEMETRY = 0;
export const GPS = 1;
export const HEART_RATE = 2;
export const DISTANCE = 3;

export const POWER_MAX = 32766;
export const CADENCE_MAX = 254;
export const ASSIST_MAX = 254;

export const SAME_BIN = 0;
export const MERGED_BIN = 1;
export const NEW_RECORD = 2;

const START_SEGMENT = 0;
const FIRST_SEGMENT = 1;
const CHANGE_SEGMENT = 2;

const BATCH_ROWS = 256;
const SEMICIRCLES = 2147483648;
const DEVELOPER_COUNT = FIT_DEVELOPER_FIELDS.length;
const FLOAT32_CHANNELS = FIT_DEVELOPER_FIELDS.map(field => field.type === 'float32');
const LAST_CHANNELS = FIT_DEVELOPER_FIELDS.map(field => field.policy === 'last');

const inRange = (value: number, max: number) => value >= 0 && value <= max;
const validAssist = (value: number) => inRange(value, ASSIST_MAX) && Number.isInteger(value);

export type LifecycleItem =
  | { readonly kind: 'start' | 'stop'; readonly elapsed: number; readonly interval: number }
  | { readonly kind: 'lap'; readonly elapsed: number; readonly lap: number };

export interface LifecycleGroup {
  readonly elapsed: number;
  readonly items: readonly LifecycleItem[];
}

interface StreamRows {
  readonly rows: number;
  readonly elapsed: Float64Array;
  readonly second: Float64Array;
  readonly interval: Int32Array;
  readonly run: Int32Array;
  readonly witness: Uint8Array;
}

export interface TelemetryRows extends StreamRows {
  readonly stream: typeof TELEMETRY;
  readonly power: Float64Array;
  readonly cadence: Float64Array;
  readonly assist: Float64Array;
  readonly motor: Float64Array;
  readonly channels: readonly (Float64Array | undefined)[];
}

export interface GpsRows extends StreamRows {
  readonly stream: typeof GPS;
  readonly latitude: Float64Array;
  readonly longitude: Float64Array;
  readonly altitude: Float64Array;
  readonly speed: Float64Array;
}

export interface HeartRateRows extends StreamRows {
  readonly stream: typeof HEART_RATE;
  readonly heartRate: Float64Array;
}

export interface DistanceRows extends StreamRows {
  readonly stream: typeof DISTANCE;
  readonly distance: Float64Array;
}

// The loaded rows of one stream; a row whose witness flag is 1 contributes to a record.
export type WitnessRows = TelemetryRows | GpsRows | HeartRateRows | DistanceRows;

export interface MergeConsumer {
  witnesses(rows: WitnessRows, from: number, to: number): void;
  tie(tie: WitnessTie): void;
  lifecycle(item: LifecycleItem): void;
  drain?(): Promise<void>;
}

export interface WitnessStream {
  readonly rank: number;
  // The elapsed of rows[index]: NaN once the loaded rows are used up, Infinity after the last page.
  readonly head: number;
  readonly index: number;
  readonly rows: WitnessRows;
  load(): Promise<void>;
  advance(to: number): void;
}

export function lifecycleGroups(timeline: Timeline): LifecycleGroup[] {
  const { intervals, transitions, laps, end } = timeline;
  const last = intervals[intervals.length - 1]!;
  const closedByEvent = transitions.some(item => item.kind === 'close' && item.interval === last.id);
  const items: LifecycleItem[] = [{ kind: 'start', elapsed: 0, interval: 1 }];
  for (const transition of transitions) {
    if (transition.kind === 'lap') items.push({ kind: 'lap', elapsed: transition.elapsed, lap: transition.lap - 1 });
    else
      items.push({
        kind: transition.kind === 'open' ? 'start' : 'stop',
        elapsed: transition.elapsed,
        interval: transition.interval,
      });
  }
  items.push({ kind: 'stop', elapsed: end, interval: closedByEvent ? 0 : last.id });
  items.push({ kind: 'lap', elapsed: end, lap: laps.length - 1 });
  const groups: { elapsed: number; items: LifecycleItem[] }[] = [];
  for (const item of items) {
    const group = groups[groups.length - 1];
    if (group && group.elapsed === item.elapsed) group.items.push(item);
    else groups.push({ elapsed: item.elapsed, items: [item] });
  }
  return groups;
}

export class BinBreaks {
  private started = false;
  private interval = 0;
  private readonly runs = new Int32Array(2);

  next(stream: number, interval: number, run: number): boolean {
    let brk = !this.started || interval !== this.interval;
    if (stream === TELEMETRY || stream === GPS) {
      const previous = this.runs[stream]!;
      if (previous !== 0 && previous !== run) brk = true;
      this.runs[stream] = run;
    }
    this.started = true;
    this.interval = interval;
    return brk;
  }

  tie(tie: WitnessTie, breaks: boolean[]): void {
    const segments = tie.segments;
    breaks.length = segments.length;
    for (let i = 0; i < segments.length; i++) {
      const { kind, stream } = segments[i]!;
      let brk = kind === CHANGE_SEGMENT;
      if (kind === FIRST_SEGMENT) {
        const previous = this.runs[stream]!;
        brk = previous !== 0 && previous !== tie.firstRun[stream];
      }
      breaks[i] = brk || (i === 0 && (!this.started || tie.interval !== this.interval));
    }
    this.started = true;
    this.interval = tie.interval;
    for (const stream of [TELEMETRY, GPS]) if (tie.firstRun[stream] !== 0) this.runs[stream] = tie.lastRun[stream]!;
  }
}

export class RecordBins {
  private bin = NaN;
  private second = NaN;

  constructor(
    readonly origin: number,
    readonly interval: number,
  ) {}

  next(second: number, brk: boolean): number {
    const bin = Math.floor((second - this.origin) / this.interval);
    if (!brk && bin === this.bin) return SAME_BIN;
    this.bin = bin;
    if (second === this.second) return MERGED_BIN;
    this.second = second;
    return NEW_RECORD;
  }

  // Equals calling next(second, false) for every second after the last one seen up to `through`.
  span(through: number): number {
    const bin = Math.floor((through - this.origin) / this.interval);
    const records = bin - this.bin;
    if (records > 0) {
      this.bin = bin;
      this.second = this.origin + bin * this.interval;
    }
    return records;
  }
}

class Mean {
  sum = 0;
  count = 0;

  reset(): void {
    this.sum = 0;
    this.count = 0;
  }

  addRows(column: Float64Array, from: number, to: number, low: number, high: number): void {
    let sum = this.sum;
    let count = this.count;
    for (let i = from; i < to; i++) {
      const value = column[i]!;
      if (value >= low && value <= high) {
        sum += value;
        count++;
      }
    }
    this.sum = sum;
    this.count = count;
  }

  merge(other: Mean): void {
    this.sum += other.sum;
    this.count += other.count;
  }

  get value(): number {
    return this.count > 0 ? this.sum / this.count : NaN;
  }
}

class FieldSums {
  protected readonly power = new Mean();
  protected readonly cadence = new Mean();
  protected readonly motor = new Mean();
  protected readonly sums = new Float64Array(DEVELOPER_COUNT);
  protected readonly counts = new Float64Array(DEVELOPER_COUNT);
  protected readonly lasts = new Float64Array(DEVELOPER_COUNT).fill(NaN);
  protected assist = NaN;
  protected heartRate = NaN;
  protected latitude = NaN;
  protected longitude = NaN;
  protected altitude = NaN;
  protected speed = NaN;
  protected distance = NaN;

  reset(): void {
    this.power.reset();
    this.cadence.reset();
    this.motor.reset();
    this.sums.fill(0);
    this.counts.fill(0);
    this.lasts.fill(NaN);
    this.assist = this.heartRate = this.latitude = this.longitude = NaN;
    this.altitude = this.speed = this.distance = NaN;
  }

  add(rows: WitnessRows, i: number): void {
    switch (rows.stream) {
      case TELEMETRY:
        this.addTelemetry(rows, i, i + 1);
        break;
      case GPS: {
        this.latitude = rows.latitude[i]!;
        this.longitude = rows.longitude[i]!;
        const altitude = rows.altitude[i]!;
        if (!Number.isNaN(altitude)) this.altitude = altitude;
        const speed = rows.speed[i]!;
        if (!Number.isNaN(speed)) this.speed = speed;
        break;
      }
      case HEART_RATE:
        this.heartRate = rows.heartRate[i]!;
        break;
      default:
        this.distance = rows.distance[i]!;
    }
  }

  // Adds telemetry rows from..to-1, all of them witnesses.
  protected addTelemetry(rows: TelemetryRows, from: number, to: number): void {
    this.power.addRows(rows.power, from, to, 0, POWER_MAX);
    this.cadence.addRows(rows.cadence, from, to, 0, CADENCE_MAX);
    this.motor.addRows(rows.motor, from, to, -Number.MAX_VALUE, Number.MAX_VALUE);
    const assist = rows.assist;
    for (let i = from; i < to; i++) if (validAssist(assist[i]!)) this.assist = assist[i]!;
    // A channel records only what its policy reads: a sum for a mean, the last value otherwise.
    for (let j = 0; j < DEVELOPER_COUNT; j++) {
      const column = rows.channels[j];
      if (!column) continue;
      const limit = FLOAT32_CHANNELS[j] ? FLOAT32_OVERFLOW : Infinity;
      const low = -limit;
      let count = this.counts[j]!;
      if (LAST_CHANNELS[j]) {
        let last = this.lasts[j]!;
        for (let i = from; i < to; i++) {
          const value = column[i]!;
          if (value > low && value < limit) {
            count++;
            last = value;
          }
        }
        this.lasts[j] = last;
      } else {
        let sum = this.sums[j]!;
        for (let i = from; i < to; i++) {
          const value = column[i]!;
          if (value > low && value < limit) {
            sum += value;
            count++;
          }
        }
        this.sums[j] = sum;
      }
      this.counts[j] = count;
    }
  }

  merge(other: FieldSums): void {
    this.power.merge(other.power);
    this.cadence.merge(other.cadence);
    this.motor.merge(other.motor);
    for (let j = 0; j < DEVELOPER_COUNT; j++) {
      if (other.counts[j] === 0) continue;
      this.sums[j] = this.sums[j]! + other.sums[j]!;
      this.counts[j] = this.counts[j]! + other.counts[j]!;
      this.lasts[j] = other.lasts[j]!;
    }
    if (!Number.isNaN(other.assist)) this.assist = other.assist;
    if (!Number.isNaN(other.heartRate)) this.heartRate = other.heartRate;
    if (!Number.isNaN(other.latitude)) {
      this.latitude = other.latitude;
      this.longitude = other.longitude;
    }
    if (!Number.isNaN(other.altitude)) this.altitude = other.altitude;
    if (!Number.isNaN(other.speed)) this.speed = other.speed;
    if (!Number.isNaN(other.distance)) this.distance = other.distance;
  }
}

class RecordFields extends FieldSums {
  second = 0;
  private telemetry: TelemetryRows | null = null;
  private from = 0;
  private to = 0;

  start(second: number): void {
    this.second = second;
    this.reset();
  }

  reset(): void {
    super.reset();
    this.telemetry = null;
  }

  // Consecutive telemetry witnesses of one page are summed together once something else needs the sums.
  add(rows: WitnessRows, i: number): void {
    if (rows.stream !== TELEMETRY) {
      super.add(rows, i);
      return;
    }
    if (rows === this.telemetry && i === this.to) {
      this.to = i + 1;
      return;
    }
    this.settle();
    this.telemetry = rows;
    this.from = i;
    this.to = i + 1;
  }

  merge(other: FieldSums): void {
    this.settle();
    super.merge(other);
  }

  encode(values: Float64Array, developer: Float64Array, clock: FitClock): void {
    this.settle();
    values[0] = clock.timestamp(this.second);
    const longitude = (this.longitude / 180) * SEMICIRCLES;
    values[1] = (this.latitude / 180) * SEMICIRCLES;
    values[2] = roundHalfAway(longitude) >= SEMICIRCLES - 1 ? -SEMICIRCLES : longitude;
    values[3] = (this.altitude + 500) * 5;
    values[4] = this.speed * 1000;
    values[5] = this.distance * 100;
    values[6] = this.heartRate;
    values[7] = this.power.value;
    values[8] = this.cadence.value;
    const motor = this.motor.value;
    values[9] = motor >= 0 ? motor : NaN;
    values[10] = this.assist;
    for (let j = 0; j < DEVELOPER_COUNT; j++) {
      const mean = this.counts[j]! > 0 ? this.sums[j]! / this.counts[j]! : NaN;
      developer[j] = LAST_CHANNELS[j] ? this.lasts[j]! : mean;
    }
  }

  private settle(): void {
    if (this.telemetry === null) return;
    this.addTelemetry(this.telemetry, this.from, this.to);
    this.telemetry = null;
  }
}

class TieSegment extends FieldSums {
  constructor(
    readonly kind: number,
    readonly stream: number,
  ) {
    super();
  }
}

// Witnesses of one interval at one elapsed share a second. Only the first witness, each run stream's first witness and
// each stream's first run change can start a new record, so segments split there.
export class WitnessTie {
  count = 0;
  readonly segments: TieSegment[] = [];
  readonly firstRun = new Int32Array(2);
  readonly lastRun = new Int32Array(2);
  private readonly changed = [false, false];

  constructor(
    readonly interval: number,
    readonly second: number,
  ) {}

  add(rows: WitnessRows, i: number): void {
    const stream = rows.stream;
    let kind = this.segments.length === 0 ? START_SEGMENT : -1;
    if (stream === TELEMETRY || stream === GPS) {
      const run = rows.run[i]!;
      if (this.firstRun[stream] === 0) {
        this.firstRun[stream] = run;
        kind = FIRST_SEGMENT;
      } else if (!this.changed[stream] && run !== this.lastRun[stream]) {
        this.changed[stream] = true;
        kind = CHANGE_SEGMENT;
      }
      this.lastRun[stream] = run;
    }
    if (kind !== -1) this.segments.push(new TieSegment(kind, stream));
    this.segments[this.segments.length - 1]!.add(rows, i);
    this.count++;
  }
}

export class RecordEmitter implements MergeConsumer {
  records = 0;
  private readonly breaks = new BinBreaks();
  private readonly bins: RecordBins;
  private readonly fields = new RecordFields();
  private readonly queue: LifecycleItem[] = [];
  private readonly values = new Float64Array(11);
  private readonly developer = new Float64Array(DEVELOPER_COUNT);
  private readonly tieBreaks: boolean[] = [];
  private pending = false;
  private lastStream = -1;
  private lastInterval = 0;
  private lastRun = 0;
  private lastSecond = NaN;

  constructor(
    private readonly writer: FitWriter,
    private readonly clock: FitClock,
    interval: number,
    private readonly writeLifecycle: (item: LifecycleItem) => void,
  ) {
    this.bins = new RecordBins(clock.second(0), interval);
  }

  witnesses(rows: WitnessRows, from: number, to: number): void {
    const { stream, witness, second, interval, run } = rows;
    for (let i = from; i < to; i++) {
      if (witness[i] === 0) continue;
      const at = second[i]!;
      const id = interval[i]!;
      const runId = run[i]!;
      // Repeating the previous witness's stream, interval, run and second changes neither the breaks nor the bins.
      if (stream !== this.lastStream || id !== this.lastInterval || runId !== this.lastRun || at !== this.lastSecond) {
        if (this.bins.next(at, this.breaks.next(stream, id, runId)) === NEW_RECORD) this.begin(at);
        this.lastStream = stream;
        this.lastInterval = id;
        this.lastRun = runId;
        this.lastSecond = at;
      }
      this.fields.add(rows, i);
    }
  }

  tie(tie: WitnessTie): void {
    this.lastStream = -1;
    const breaks = this.tieBreaks;
    this.breaks.tie(tie, breaks);
    const segments = tie.segments;
    for (let i = 0; i < segments.length; i++) {
      if (this.bins.next(tie.second, breaks[i]!) === NEW_RECORD) this.begin(tie.second);
      this.fields.merge(segments[i]!);
    }
  }

  lifecycle(item: LifecycleItem): void {
    if (this.pending) this.queue.push(item);
    else this.writeLifecycle(item);
  }

  drain(): Promise<void> {
    return this.writer.drain();
  }

  finish(): void {
    this.flush();
  }

  private begin(second: number): void {
    this.flush();
    this.fields.start(second);
    this.pending = true;
  }

  private flush(): void {
    if (!this.pending) return;
    this.fields.encode(this.values, this.developer, this.clock);
    this.writer.message('record', this.values, this.developer);
    this.records++;
    this.pending = false;
    for (const item of this.queue) this.writeLifecycle(item);
    this.queue.length = 0;
  }
}

function earliest(streams: readonly WitnessStream[]): WitnessStream | undefined {
  let best: WitnessStream | undefined;
  for (let k = 0; k < streams.length; k++) if (streams[k]!.head < (best ? best.head : Infinity)) best = streams[k];
  return best;
}

// Feeds runs of rows in merge order until a stream has used up its loaded rows, which it returns, or until the pending
// group is due, every stream has ended or the batch is full. A run is the earliest stream's rows before every other
// stream's next row and the pending group, so the stream that ends a run is the earliest one afterwards.
function feedUntil(
  streams: readonly WitnessStream[],
  pending: LifecycleGroup | undefined,
  consumer: MergeConsumer,
  batch: { rows: number },
): WitnessStream | undefined {
  let stream = earliest(streams);
  while (stream && !(pending && pending.elapsed <= stream.head)) {
    let bound: WitnessStream | undefined;
    let boundTime = pending ? pending.elapsed : Infinity;
    let boundRank = -1;
    for (let k = 0; k < streams.length; k++) {
      const other = streams[k]!;
      if (other !== stream && (other.head < boundTime || (other.head === boundTime && other.rank < boundRank))) {
        bound = other;
        boundTime = other.head;
        boundRank = other.rank;
      }
    }
    const inclusive = stream.rank < boundRank;
    const { rows, index } = stream;
    const elapsed = rows.elapsed;
    let end = index + 1;
    while (end < rows.rows && (elapsed[end]! < boundTime || (inclusive && elapsed[end] === boundTime))) end++;
    consumer.witnesses(rows, index, end);
    stream.advance(end);
    batch.rows += end - index;
    if (Number.isNaN(stream.head)) return stream;
    if (batch.rows >= BATCH_ROWS) return undefined;
    stream = bound;
  }
  return undefined;
}

export async function mergeWitnesses(
  streams: readonly WitnessStream[],
  groups: readonly LifecycleGroup[],
  intervals: readonly ActivityInterval[],
  consumer: MergeConsumer,
  slicer: ExportSlicer,
): Promise<void> {
  const batch = { rows: 0 };
  const pause = async () => {
    batch.rows = 0;
    await slicer.tick();
    await consumer.drain?.();
  };
  const group = async ({ elapsed, items }: LifecycleGroup) => {
    const ties = new Map<number, WitnessTie>();
    for (const stream of streams)
      while (stream.head === elapsed) {
        const { rows, index } = stream;
        stream.advance(index + 1);
        if (rows.witness[index] === 1) {
          const id = rows.interval[index]!;
          const interval = intervals[id - 1]!;
          if (interval.close === elapsed && interval.open < elapsed) consumer.witnesses(rows, index, index + 1);
          else {
            let tie = ties.get(id);
            if (!tie) ties.set(id, (tie = new WitnessTie(id, rows.second[index]!)));
            tie.add(rows, index);
          }
        }
        if (Number.isNaN(stream.head)) await stream.load();
        if (++batch.rows >= BATCH_ROWS) await pause();
      }
    const inside = (tie: WitnessTie) => intervals[tie.interval - 1]!.close === elapsed;
    for (const item of items) {
      const tie = item.kind === 'stop' ? ties.get(item.interval) : undefined;
      if (tie && inside(tie)) {
        consumer.tie(tie);
        ties.delete(tie.interval);
      }
      consumer.lifecycle(item);
    }
    for (const tie of ties.values()) if (inside(tie)) consumer.tie(tie);
    for (const tie of ties.values()) if (!inside(tie)) consumer.tie(tie);
  };
  for (const stream of streams) await stream.load();
  let next = 0;
  for (;;) {
    const stream = earliest(streams);
    const pending = groups[next];
    if (!stream && !pending) break;
    if (pending && (!stream || pending.elapsed <= stream.head)) {
      await group(pending);
      next++;
      continue;
    }
    const used = feedUntil(streams, pending, consumer, batch);
    if (used) await used.load();
    if (batch.rows >= BATCH_ROWS) await pause();
  }
}

type Texts = readonly (string | null)[];
const malformed = (projection: string, problem: string) =>
  new ExportError('page', `The ride data could not be read for export: a ${projection} page ${problem}.`);
const filled = (column: Float64Array | undefined, rows: number) => column ?? new Float64Array(rows).fill(NaN);

abstract class PageStream<P extends 'telemetry' | 'gps' | 'healthFit', R extends WitnessRows> implements WitnessStream {
  head = NaN;
  index = 0;
  rows!: R;
  private loaded = 0;

  constructor(
    readonly rank: number,
    readonly projection: P,
    private readonly pages: AsyncIterator<ProjectionPage<P>>,
    protected readonly clock: FitClock,
  ) {}

  async load(): Promise<void> {
    while (this.index >= this.loaded) {
      const next = await this.pages.next();
      if (next.done) {
        this.head = Infinity;
        return;
      }
      this.receive(next.value);
    }
    this.head = this.rows.elapsed[this.index]!;
  }

  advance(to: number): void {
    this.index = to;
    this.head = to < this.loaded ? this.rows.elapsed[to]! : NaN;
  }

  protected abstract prepare(page: ProjectionPage<P>, elapsed: Float64Array, second: Float64Array): R;

  private receive(page: ProjectionPage<P>): void {
    this.loaded = page.rows;
    this.index = 0;
    if (page.rows === 0) return;
    const elapsed = (page.columns as Readonly<Record<string, unknown>>).elapsedSeconds;
    if (!(elapsed instanceof Float64Array)) throw malformed(this.projection, 'has no elapsedSeconds column');
    for (let i = 0; i < page.rows; i++)
      if (!Number.isFinite(elapsed[i]!)) throw malformed(this.projection, 'has an observation without a valid time');
    this.rows = this.prepare(page, elapsed, this.clock.seconds(elapsed, page.rows));
  }
}

export class TelemetryStream extends PageStream<'telemetry', TelemetryRows> {
  constructor(
    pages: AsyncIterator<ProjectionPage<'telemetry'>>,
    clock: FitClock,
    private readonly cursor: TimelineCursor<'telemetry'>,
    private readonly runs: RunTracker<'telemetry'>,
    private readonly statistics: RideStatistics | null,
  ) {
    super(2, 'telemetry', pages, clock);
  }

  protected prepare(page: ProjectionPage<'telemetry'>, elapsed: Float64Array, second: Float64Array): TelemetryRows {
    const { rows, columns } = page;
    const assignment = this.cursor.map(page);
    const interval = assignment.interval;
    const run = this.runs.map(page, assignment);
    const power = filled(columns.humanPowerW, rows);
    const cadence = filled(columns.cadenceRpm, rows);
    const assist = filled(columns.assistLevel, rows);
    const motor = filled(columns.motorInputPowerW, rows);
    const channels = FIT_DEVELOPER_FIELDS.map(field => columns[field.channel]);
    const float32 = channels.filter(
      (column, j): column is Float64Array => column !== undefined && FLOAT32_CHANNELS[j]!,
    );
    const witness = new Uint8Array(rows);
    for (let i = 0; i < rows; i++) {
      if (interval[i] === 0) continue;
      let usable = inRange(power[i]!, POWER_MAX) || inRange(cadence[i]!, CADENCE_MAX) || validAssist(assist[i]!);
      for (let k = 0; !usable && k < float32.length; k++) usable = finiteAsFloat32(float32[k]![i]!);
      if (usable) witness[i] = 1;
    }
    const telemetry: TelemetryRows = {
      stream: TELEMETRY,
      rows,
      elapsed,
      second,
      interval,
      run,
      witness,
      power,
      cadence,
      assist,
      motor,
      channels,
    };
    this.statistics?.telemetry(telemetry);
    return telemetry;
  }
}

export class GpsStream extends PageStream<'gps', GpsRows> {
  private readonly fix = emptyFix();

  constructor(
    pages: AsyncIterator<ProjectionPage<'gps'>>,
    clock: FitClock,
    private readonly cursor: TimelineCursor<'gps'>,
    private readonly runs: RunTracker<'gps'>,
    private readonly producer: Producer,
    readonly geometry: FitGeometry,
    private readonly statistics: RideStatistics | null,
  ) {
    super(3, 'gps', pages, clock);
  }

  protected prepare(page: ProjectionPage<'gps'>, elapsed: Float64Array, second: Float64Array): GpsRows {
    const { rows, columns } = page;
    const assignment = this.cursor.map(page);
    const { interval, interruption } = assignment;
    const run = this.runs.map(page, assignment);
    const producers = columns.producer as Texts | undefined;
    const epochs = columns.clockEpoch as Texts | undefined;
    const barriers = columns.distanceBarrier;
    const latitude = filled(columns.latitude, rows);
    const longitude = filled(columns.longitude, rows);
    const horizontalAccuracy = filled(columns.horizontalAccuracyM, rows);
    const speeds = filled(columns.speedMps, rows);
    const speedAccuracy = filled(columns.speedAccuracyMps, rows);
    const altitudes = filled(columns.altitudeMeters, rows);
    const verticalAccuracy = filled(columns.verticalAccuracyM, rows);
    const witness = new Uint8Array(rows);
    const altitude = new Float64Array(rows);
    const speed = new Float64Array(rows);
    const fix = this.fix;
    for (let i = 0; i < rows; i++) {
      if (producers?.[i] !== this.producer) continue;
      fix.time = elapsed[i]!;
      fix.latitude = latitude[i]!;
      fix.longitude = longitude[i]!;
      fix.horizontalAccuracy = horizontalAccuracy[i]!;
      fix.speed = speeds[i]!;
      fix.speedAccuracy = speedAccuracy[i]!;
      fix.altitude = altitudes[i]!;
      fix.verticalAccuracy = verticalAccuracy[i]!;
      fix.clockEpoch = epochs?.[i] ?? null;
      fix.interval = interval[i]!;
      fix.interruption = interruption[i]!;
      fix.barrier = barriers !== undefined && barriers[i] === 1;
      if (!this.geometry.accept(fix)) continue;
      const valid = validSpeed(fix.speed, fix.speedAccuracy);
      this.statistics?.speed(valid);
      witness[i] = 1;
      altitude[i] = validAltitude(fix.altitude, fix.verticalAccuracy) ? fix.altitude : NaN;
      speed[i] = valid;
    }
    return { stream: GPS, rows, elapsed, second, interval, run, witness, latitude, longitude, altitude, speed };
  }
}

export class HeartRateStream extends PageStream<'healthFit', HeartRateRows> {
  private key = 0;
  private previous: { interval: number; clock: string | null; connection: string | null; interruption: number } = {
    interval: -1,
    clock: null,
    connection: null,
    interruption: -1,
  };

  constructor(
    pages: AsyncIterator<ProjectionPage<'healthFit'>>,
    clock: FitClock,
    private readonly cursor: TimelineCursor<'healthFit'>,
    private readonly plan: HeartRatePlan,
    private readonly statistics: RideStatistics | null,
  ) {
    super(4, 'healthFit', pages, clock);
  }

  protected prepare(page: ProjectionPage<'healthFit'>, elapsed: Float64Array, second: Float64Array): HeartRateRows {
    const { rows, columns } = page;
    const { interval, interruption } = this.cursor.map(page);
    const { producer, representation, sampleCount, heartRateBpm, clockEpoch, connectionEpoch } = columns;
    const heartRate = filled(heartRateBpm, rows);
    const witness = new Uint8Array(rows);
    const statistics = this.statistics?.heartRate;
    for (let i = 0; i < rows; i++) {
      const source = healthProducer(producer?.[i]);
      const kind = heartRateKind(representation?.[i] ?? null, sampleCount ? sampleCount[i]! : NaN);
      const value = heartRate[i]!;
      const id = interval[i]!;
      if (source !== this.plan.source || kind !== this.plan.kind || Number.isNaN(value) || id === 0) continue;
      if (!validHeartRate(value)) {
        statistics?.reset();
        continue;
      }
      if (statistics) {
        const clock = clockEpoch?.[i] ?? null;
        const connection = connectionEpoch?.[i] ?? null;
        const cut = interruption[i]!;
        const previous = this.previous;
        if (
          previous.interval !== id ||
          previous.clock !== clock ||
          previous.connection !== connection ||
          previous.interruption !== cut
        ) {
          this.key++;
          this.previous = { interval: id, clock, connection, interruption: cut };
        }
        statistics.add(elapsed[i]!, value, this.key);
      }
      witness[i] = 1;
    }
    return { stream: HEART_RATE, rows, elapsed, second, interval, run: new Int32Array(rows), witness, heartRate };
  }
}

export class DistanceStream implements WitnessStream {
  readonly rank = 1;
  head = NaN;
  index = 0;
  rows!: DistanceRows;
  private loaded = 0;
  private points = new Float64Array(0);
  private readonly builder: DistancePoints;

  constructor(
    private readonly pages: AsyncIterator<ProjectionPage<'distance'>>,
    private readonly clock: FitClock,
    timeline: Timeline,
    private readonly ranges: DistanceRanges | null,
  ) {
    this.builder = new DistancePoints(timeline.intervals);
  }

  async load(): Promise<void> {
    while (this.index >= this.loaded) {
      const next = await this.pages.next();
      if (next.done) {
        this.ranges?.finish();
        this.head = Infinity;
        return;
      }
      this.receive(next.value);
    }
    this.head = this.rows.elapsed[this.index]!;
  }

  advance(to: number): void {
    this.index = to;
    this.head = to < this.loaded ? this.rows.elapsed[to]! : NaN;
  }

  private receive({ rows, columns }: ProjectionPage<'distance'>): void {
    if (this.points.length < 6 * rows) this.points = new Float64Array(6 * rows);
    const value = (column: Float64Array | undefined, i: number) => (column ? column[i]! : NaN);
    let count = 0;
    for (let i = 0; i < rows; i++) {
      const interval: DistanceInterval = {
        start: value(columns.start, i),
        end: value(columns.end, i),
        meters: value(columns.meters, i),
        segment: value(columns.segment, i),
        startSpeed: value(columns.startSpeed, i),
        endSpeed: value(columns.endSpeed, i),
      };
      count += this.builder.add(interval, this.points, 3 * count);
      this.ranges?.add(interval);
    }
    this.loaded = count;
    this.index = 0;
    if (count > 0) this.rows = this.prepare(count);
  }

  private prepare(count: number): DistanceRows {
    const points = this.points;
    const elapsed = new Float64Array(count);
    const distance = new Float64Array(count);
    const interval = new Int32Array(count);
    const witness = new Uint8Array(count);
    for (let k = 0; k < count; k++) {
      elapsed[k] = points[3 * k]!;
      distance[k] = points[3 * k + 1]!;
      interval[k] = points[3 * k + 2]!;
      if (interval[k] !== 0 && distanceEncodable(distance[k]!)) witness[k] = 1;
    }
    const second = this.clock.seconds(elapsed, count);
    return { stream: DISTANCE, rows: count, elapsed, second, interval, run: new Int32Array(count), witness, distance };
  }
}
