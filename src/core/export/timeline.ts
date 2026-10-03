import { PROJECTIONS } from './catalog';
import { readProjection, type ExportReads, type ProjectionPage } from './pages';
import {
  ExportError,
  type ExportColumns,
  type ExportPlatform,
  type ExportSource,
  type ProjectionColumn,
  type ProjectionName,
} from './types';

export const FIT_MAX_LIFECYCLE_EVENTS = 10_000;
export const FIT_MAX_RIDE_SECONDS = 2_678_400;

export type ObservationProjection = 'telemetry' | 'gps' | 'gpsDiscovery' | 'healthZip' | 'healthFit';
export type RunProjection = 'telemetry' | 'gps';

export interface ActivityInterval {
  readonly id: number;
  readonly open: number;
  readonly close: number;
}

export interface TimelineLap {
  readonly start: number;
  readonly end: number;
  readonly timerSeconds: number;
}

export type TimelineTransition =
  | { readonly kind: 'open' | 'close'; readonly elapsed: number; readonly interval: number }
  | { readonly kind: 'lap'; readonly elapsed: number; readonly lap: number };

export interface TimelineOptions {
  readonly fitLimits: boolean;
}

export interface TimelineAssignment {
  readonly interval: Int32Array;
  readonly interruption: Int32Array;
}

interface PageLike<P extends ProjectionName> {
  readonly rows: number;
  readonly columns: ExportColumns<P>;
}

interface InterruptionBoundary {
  readonly time: number;
  readonly producer: string;
  readonly sequence: number;
  readonly clockEpoch: string | null;
  readonly cycSequence: number;
}

interface RunState {
  interval: number;
  interruption: number;
  connection: number;
  clockEpoch: string | null;
  barrier: boolean;
  run: number;
}

type Texts = readonly (string | null)[];
type ColumnMap = Readonly<Record<string, Float64Array | Texts | undefined>>;

const malformed = (projection: ProjectionName, problem: string) =>
  new ExportError('page', `The ride data could not be read for export: a ${projection} page ${problem}.`);

const listed = (projection: ProjectionName, platform: ExportPlatform, name: string) =>
  (PROJECTIONS[projection].columns as readonly ProjectionColumn[]).some(
    column => column.name === name && column.platforms.includes(platform),
  );

function column<T extends Float64Array | Texts>(
  page: PageLike<ProjectionName>,
  projection: ProjectionName,
  platform: ExportPlatform,
  name: string,
): T | undefined {
  const value = (page.columns as ColumnMap)[name];
  if (value === undefined && page.rows > 0 && listed(projection, platform, name))
    throw malformed(projection, `has no ${name} column`);
  return value as T | undefined;
}

function precedes(
  boundary: InterruptionBoundary,
  time: number,
  producer: string | null,
  sequence: number | null,
  clockEpoch: string | null,
): boolean {
  if (boundary.time !== time) return boundary.time < time;
  if (boundary.producer === producer && sequence !== null) return boundary.sequence < sequence;
  if (producer === 'cyc' && sequence !== null) return boundary.cycSequence < sequence;
  return boundary.clockEpoch !== null && clockEpoch !== null && boundary.clockEpoch !== clockEpoch;
}

export class Timeline {
  readonly timerSeconds: number;
  readonly laps: readonly TimelineLap[];

  constructor(
    readonly platform: ExportPlatform,
    readonly end: number,
    readonly intervals: readonly ActivityInterval[],
    lapEnds: readonly number[],
    readonly transitions: readonly TimelineTransition[],
    private readonly boundaries: readonly InterruptionBoundary[],
  ) {
    this.timerSeconds = this.timer(0, end);
    const marks = [0, ...lapEnds, end];
    this.laps = marks
      .slice(1)
      .map((lapEnd, index) => ({ start: marks[index]!, end: lapEnd, timerSeconds: this.timer(marks[index]!, lapEnd) }));
  }

  timer(start: number, end: number): number {
    let total = 0;
    for (const interval of this.intervals)
      total += Math.max(0, Math.min(end, interval.close) - Math.max(start, interval.open));
    return total;
  }

  cursor<P extends ObservationProjection>(projection: P): TimelineCursor<P> {
    return new TimelineCursor(this, projection, this.boundaries);
  }
}

export class TimelineBuilder {
  private events = 0;
  private previous = 0;
  private open: number | null = 0;
  private readonly intervals: ActivityInterval[] = [];
  private readonly lapEnds: number[] = [];
  private readonly transitions: TimelineTransition[] = [];
  private readonly boundaries: InterruptionBoundary[] = [];
  private readonly fitLimits: boolean;

  constructor(
    readonly platform: ExportPlatform,
    readonly end: number,
    options: Partial<TimelineOptions> = {},
  ) {
    if (!Number.isFinite(end) || end < 0) throw new ExportError('gate', 'The ride has no valid elapsed time.');
    this.fitLimits = options.fitLimits ?? false;
    if (this.fitLimits && end > FIT_MAX_RIDE_SECONDS)
      throw new ExportError(
        'limit',
        'A FIT file cannot hold a ride longer than 31 days. The ride-data ZIP is still available.',
      );
  }

  add(page: PageLike<'lifecycle'>): void {
    this.events += page.rows;
    if (this.fitLimits && this.events > FIT_MAX_LIFECYCLE_EVENTS)
      throw new ExportError(
        'limit',
        'A FIT file cannot hold more than 10,000 start, pause, resume, lap or stop events. The ride-data ZIP is still available.',
      );
    if (page.rows === 0) return;
    const platform = this.platform;
    const ios = platform === 'ios';
    const elapsed = column<Float64Array>(page, 'lifecycle', platform, 'elapsedSeconds')!;
    const actions = column<Texts>(page, 'lifecycle', platform, 'action')!;
    const interrupted = ios ? column<Float64Array>(page, 'lifecycle', platform, 'interrupted')! : undefined;
    for (let i = 0; i < page.rows; i++) {
      const t = elapsed[i]!;
      if (!Number.isFinite(t) || t < 0) throw malformed('lifecycle', 'has an event without a valid elapsed time');
      if (t < this.previous) throw new ExportError('cursor', 'The ride events are out of elapsed order.');
      this.previous = t;
      const action = actions[i];
      if (ios && (interrupted![i] === 1 || action === 'interruption')) this.interruption(page, i, t);
      if (t > this.end) continue;
      if (action === 'pause' || action === 'interruption') {
        if (this.open !== null) {
          const id = this.intervals.length + 1;
          this.intervals.push({ id, open: this.open, close: t });
          this.transitions.push({ kind: 'close', elapsed: t, interval: id });
          this.open = null;
        }
      } else if (action === 'resume') {
        if (this.open === null) {
          this.open = t;
          this.transitions.push({ kind: 'open', elapsed: t, interval: this.intervals.length + 1 });
        }
      } else if (action === 'lap' && t > 0 && t < this.end && this.lapEnds[this.lapEnds.length - 1] !== t) {
        this.lapEnds.push(t);
        this.transitions.push({ kind: 'lap', elapsed: t, lap: this.lapEnds.length });
      }
    }
  }

  private interruption(page: PageLike<'lifecycle'>, index: number, time: number): void {
    const producer = column<Texts>(page, 'lifecycle', 'ios', 'producer')![index];
    const sequence = column<Float64Array>(page, 'lifecycle', 'ios', 'producerSequence')![index]!;
    const clockEpoch = column<Texts>(page, 'lifecycle', 'ios', 'clockEpoch')![index] ?? null;
    const cycSequence = column<Float64Array>(page, 'lifecycle', 'ios', 'cycSequence')![index]!;
    if (typeof producer !== 'string' || !Number.isSafeInteger(sequence))
      throw malformed('lifecycle', 'has an interruption without its producer sequence');
    if (!Number.isSafeInteger(cycSequence) || cycSequence < 0)
      throw new ExportError('page', 'An interruption in this ride has no retained telemetry sequence.');
    this.boundaries.push({ time, producer, sequence, clockEpoch, cycSequence });
  }

  finish(): Timeline {
    const intervals = [...this.intervals];
    if (this.open !== null) intervals.push({ id: intervals.length + 1, open: this.open, close: this.end });
    return new Timeline(
      this.platform,
      this.end,
      intervals,
      [...this.lapEnds],
      [...this.transitions],
      [...this.boundaries],
    );
  }
}

export class TimelineCursor<P extends ObservationProjection> {
  private previous = -Infinity;
  private started = 0;
  private opened = 0;
  private passed = 0;

  constructor(
    private readonly timeline: Timeline,
    readonly projection: P,
    private readonly boundaries: readonly InterruptionBoundary[],
  ) {}

  map(page: PageLike<P>): TimelineAssignment {
    const rows = page.rows;
    const interval = new Int32Array(rows);
    const interruption = new Int32Array(rows);
    if (rows === 0) return { interval, interruption };
    const { intervals, end, platform } = this.timeline;
    const projection = this.projection;
    const elapsed = column<Float64Array>(page, projection, platform, 'elapsedSeconds');
    if (!elapsed) throw malformed(projection, 'has no elapsedSeconds column');
    const ios = platform === 'ios';
    const boundaries = this.boundaries;
    const producers =
      ios && projection !== 'telemetry' ? column<Texts>(page, projection, platform, 'producer') : undefined;
    const sequences = ios ? column<Float64Array>(page, projection, platform, 'producerSequence') : undefined;
    const epochs = ios ? column<Texts>(page, projection, platform, 'clockEpoch') : undefined;
    const active = ios ? undefined : column<Float64Array>(page, projection, platform, 'active');
    const stored = platform === 'web' ? column<Float64Array>(page, projection, platform, 'interval') : undefined;
    for (let i = 0; i < rows; i++) {
      const t = elapsed[i]!;
      const ordered = Number.isFinite(t);
      if (ordered) {
        if (t < this.previous)
          throw new ExportError('cursor', `The ${projection} observations are out of elapsed order.`);
        this.previous = t;
        while (this.started < intervals.length && intervals[this.started]!.open < t) this.started++;
        while (this.opened < intervals.length && intervals[this.opened]!.open <= t) this.opened++;
        while (this.passed < boundaries.length && boundaries[this.passed]!.time < t) this.passed++;
      }
      if (ios) {
        if (!ordered) {
          interruption[i] = this.passed;
          continue;
        }
        const producer = projection === 'telemetry' ? 'cyc' : (producers?.[i] ?? null);
        const value = sequences ? sequences[i]! : NaN;
        const sequence = Number.isNaN(value) ? null : value;
        const clockEpoch = epochs?.[i] ?? null;
        const passed = this.passed;
        const atBoundary = passed < boundaries.length && boundaries[passed]!.time === t;
        let index = passed;
        while (index < boundaries.length && precedes(boundaries[index]!, t, producer, sequence, clockEpoch)) index++;
        interruption[i] = index;
        const cutoff = atBoundary && index === passed;
        const low = cutoff ? this.started : this.opened;
        if (low === 0) continue;
        const close = intervals[low - 1]!.close;
        if (t < close || ((t === end || cutoff) && t === close)) interval[i] = low;
      } else {
        const opened = this.opened;
        if (!ordered || !active || active[i] !== 1 || opened === 0 || intervals[opened - 1]!.close < t) continue;
        const choice = stored ? stored[i]! : NaN;
        interval[i] =
          Number.isInteger(choice) && choice >= 0 && choice < opened && intervals[choice]!.close >= t
            ? choice + 1
            : opened;
      }
    }
    return { interval, interruption };
  }
}

export class RunTracker<P extends RunProjection> {
  private runs = 0;
  private readonly series = new Map<string, RunState>();
  private lastSeries: string | null = null;
  private lastState: RunState | undefined;

  constructor(
    readonly platform: ExportPlatform,
    readonly projection: P,
  ) {}

  next(
    series: string,
    interval: number,
    interruption: number,
    connection: number,
    clockEpoch: string | null,
    barrier: boolean,
  ): number {
    if (series !== this.lastSeries) {
      this.lastSeries = series;
      this.lastState = this.series.get(series);
    }
    const state = this.lastState;
    if (
      state &&
      !barrier &&
      !state.barrier &&
      state.interval === interval &&
      state.interruption === interruption &&
      state.connection === connection &&
      state.clockEpoch === clockEpoch
    )
      return state.run;
    const run = ++this.runs;
    if (state) Object.assign(state, { interval, interruption, connection, clockEpoch, barrier, run });
    else this.series.set(series, (this.lastState = { interval, interruption, connection, clockEpoch, barrier, run }));
    return run;
  }

  map(page: ProjectionPage<P>, assignment: TimelineAssignment): Int32Array {
    const rows = page.rows;
    const runs = new Int32Array(rows);
    if (rows === 0) return runs;
    const { interval, interruption } = assignment;
    const { platform, projection } = this;
    const epochs = column<Texts>(page, projection, platform, 'clockEpoch');
    if (projection === 'telemetry') {
      const connections = page.connection;
      let previousConnection = NaN;
      let previousEpoch: string | null | undefined;
      for (let i = 0; i < rows; i++) {
        const connection = connections ? connections[i]! : 0;
        const clockEpoch = epochs?.[i] ?? null;
        // A row equal to the one before it continues that row's run.
        if (
          i > 0 &&
          interval[i] === interval[i - 1] &&
          interruption[i] === interruption[i - 1] &&
          connection === previousConnection &&
          clockEpoch === previousEpoch
        )
          runs[i] = runs[i - 1]!;
        else runs[i] = this.next('', interval[i]!, interruption[i]!, connection, clockEpoch, false);
        previousConnection = connection;
        previousEpoch = clockEpoch;
      }
      return runs;
    }
    const producers = column<Texts>(page, projection, platform, 'producer');
    const barriers = column<Float64Array>(page, projection, platform, 'distanceBarrier');
    for (let i = 0; i < rows; i++)
      runs[i] = this.next(
        producers?.[i] ?? '',
        interval[i]!,
        interruption[i]!,
        0,
        epochs?.[i] ?? null,
        barriers?.[i] === 1,
      );
    return runs;
  }
}

export async function readTimeline(
  source: ExportSource,
  session: string,
  elapsedEnd: number,
  reads: ExportReads,
  options: TimelineOptions,
): Promise<Timeline> {
  const builder = new TimelineBuilder(reads.options.platform, elapsedEnd, options);
  for await (const page of readProjection(source, session, 'lifecycle', reads)) builder.add(page);
  return builder.finish();
}
