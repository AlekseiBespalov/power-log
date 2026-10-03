import { describe, expect, it } from 'vitest';
import { ExportReads, projectionColumns, type ProjectionPage } from '../../src/core/export/pages';
import {
  FIT_MAX_LIFECYCLE_EVENTS,
  FIT_MAX_RIDE_SECONDS,
  RunTracker,
  TimelineBuilder,
  readTimeline,
  type ObservationProjection,
  type RunProjection,
  type Timeline,
} from '../../src/core/export/timeline';
import {
  ExportError,
  type ExportColumns,
  type ExportErrorCode,
  type ExportPage,
  type ExportPageRequest,
  type ExportPlatform,
  type ExportSource,
  type ProjectionName,
} from '../../src/core/export/types';

interface Event {
  t: number;
  action: string;
  producer?: string;
  sequence?: number;
  epoch?: string | null;
  interrupted?: boolean;
  cyc?: number;
}

interface Observation {
  t: number;
  producer?: string;
  sequence?: number;
  epoch?: string | null;
  active?: boolean;
  stored?: number;
  barrier?: boolean;
}

type Page<P extends ProjectionName> = { rows: number; columns: ExportColumns<P> };

function lifecycle(platform: ExportPlatform, events: Event[]): Page<'lifecycle'> {
  const columns: Record<string, Float64Array | (string | null)[]> = {
    elapsedSeconds: Float64Array.from(events.map(event => event.t)),
    action: events.map(event => event.action),
    producer: events.map(event => event.producer ?? (platform === 'web' ? 'browser' : 'phone')),
  };
  if (platform !== 'web') columns.interrupted = Float64Array.from(events.map(event => (event.interrupted ? 1 : 0)));
  if (platform === 'ios') {
    columns.producerSequence = Float64Array.from(events.map((event, i) => event.sequence ?? 1000 + i));
    columns.clockEpoch = events.map(event => event.epoch ?? null);
    columns.cycSequence = Float64Array.from(events.map(event => event.cyc ?? NaN));
  }
  return { rows: events.length, columns } as Page<'lifecycle'>;
}

function timelineOf(platform: ExportPlatform, end: number, events: Event[]): Timeline {
  const builder = new TimelineBuilder(platform, end);
  builder.add(lifecycle(platform, events));
  return builder.finish();
}

function observations<P extends ObservationProjection>(
  projection: P,
  platform: ExportPlatform,
  rows: Observation[],
): Page<P> {
  const columns: Record<string, Float64Array | (string | null)[]> = {
    elapsedSeconds: Float64Array.from(rows.map(row => row.t)),
  };
  if (projection !== 'telemetry') columns.producer = rows.map(row => row.producer ?? 'phone');
  if (platform === 'ios') {
    columns.producerSequence = Float64Array.from(rows.map((row, i) => row.sequence ?? i + 1));
    columns.clockEpoch = rows.map(row => row.epoch ?? null);
    if (projection === 'gps') columns.distanceBarrier = Float64Array.from(rows.map(row => (row.barrier ? 1 : 0)));
  } else columns.active = Float64Array.from(rows.map(row => (row.active === false ? 0 : 1)));
  if (platform === 'web') columns.interval = Float64Array.from(rows.map(row => row.stored ?? 0));
  return { rows: rows.length, columns } as Page<P>;
}

function intervals<P extends ObservationProjection>(
  timeline: Timeline,
  projection: P,
  rows: Observation[],
  size = rows.length,
) {
  const cursor = timeline.cursor(projection);
  const interval: number[] = [];
  const interruption: number[] = [];
  for (let start = 0; start < rows.length; start += size) {
    const result = cursor.map(observations(projection, timeline.platform, rows.slice(start, start + size)));
    interval.push(...result.interval);
    interruption.push(...result.interruption);
  }
  return { interval, interruption };
}

function code(run: () => unknown): ExportErrorCode | undefined {
  try {
    run();
  } catch (error) {
    if (error instanceof ExportError) return error.code;
    throw error;
  }
  return undefined;
}

const times = (values: number[]) => values.map(t => ({ t }));

describe('iPhone activity intervals', () => {
  it('opens on resume and closes on pause, left-closed and right-open', () => {
    const ride = timelineOf('ios', 3.125, [
      { t: 0, action: 'start' },
      { t: 0.875, action: 'pause' },
      { t: 1.875, action: 'resume' },
      { t: 3.125, action: 'stop' },
    ]);
    expect(ride.intervals).toEqual([
      { id: 1, open: 0, close: 0.875 },
      { id: 2, open: 1.875, close: 3.125 },
    ]);
    expect(ride.transitions).toEqual([
      { kind: 'close', elapsed: 0.875, interval: 1 },
      { kind: 'open', elapsed: 1.875, interval: 2 },
    ]);
    expect(intervals(ride, 'telemetry', times([0.125, 0.875, 1.875, 2.125, 3.125])).interval).toEqual([1, 0, 2, 2, 2]);
    expect(ride.timerSeconds).toBe(2.125);
  });

  it.each([false, true])(
    'orders equal-elapsed telemetry around a hard interruption by cycSequence, same epoch %s',
    sameEpoch => {
      const ride = timelineOf('ios', 2, [
        { t: 0, action: 'start', sequence: 1 },
        { t: 1, action: 'pause', sequence: 2, interrupted: true, epoch: 'old-process', cyc: 260 },
        { t: 1, action: 'resume', sequence: 3, epoch: 'new-process' },
      ]);
      const rows = Array.from({ length: 520 }, (_, i) => ({
        t: 1,
        sequence: i + 1,
        epoch: i < 260 || sameEpoch ? 'old-process' : 'new-process',
      }));
      const expected = {
        interval: rows.map((_, i) => (i < 260 ? 1 : 2)),
        interruption: rows.map((_, i) => (i < 260 ? 0 : 1)),
      };
      expect(intervals(ride, 'telemetry', rows)).toEqual(expected);
      expect(intervals(ride, 'telemetry', rows, 17)).toEqual(expected);
    },
  );

  it.each([false, true])('orders the interrupted producer by its own sequence, same epoch %s', sameEpoch => {
    const ride = timelineOf('ios', 1.1, [
      { t: 1, action: 'pause', sequence: 3, interrupted: true, epoch: 'before-restart', cyc: 2 },
      { t: 1, action: 'resume', sequence: 4, epoch: 'after-restart' },
    ]);
    const epochs = sameEpoch
      ? ['before-restart', 'before-restart', 'before-restart', 'before-restart']
      : ['before-restart', 'before-restart', 'after-restart', 'after-restart'];
    const fixes = [0, 1, 1, 1.1].map((t, i) => ({ t, sequence: [1, 2, 5, 6][i], epoch: epochs[i] }));
    expect(intervals(ride, 'gps', fixes)).toEqual({ interval: [1, 1, 2, 2], interruption: [0, 0, 1, 1] });
    const telemetry = [0, 1, 1, 1.1].map((t, i) => ({ t, sequence: i + 1, epoch: epochs[i] }));
    expect(intervals(ride, 'telemetry', telemetry).interval).toEqual([1, 1, 2, 2]);
  });

  it('orders other producers by clock epoch when both epochs are known', () => {
    const ride = (epoch: string | null) =>
      timelineOf('ios', 2, [
        { t: 1, action: 'pause', producer: 'phone', sequence: 3, interrupted: true, epoch, cyc: 2 },
        { t: 1, action: 'resume', producer: 'phone', sequence: 4, epoch: 'after-restart' },
      ]);
    const rows = [
      { t: 1, producer: 'watch', sequence: 10, epoch: 'before-restart' },
      { t: 1, producer: 'watch', sequence: 11, epoch: 'after-restart' },
      { t: 1, producer: 'watch', sequence: 12, epoch: null },
    ];
    expect(intervals(ride('before-restart'), 'healthZip', rows)).toEqual({
      interval: [1, 2, 1],
      interruption: [0, 1, 0],
    });
    expect(intervals(ride('before-restart'), 'gps', rows).interval).toEqual([1, 2, 1]);
    expect(intervals(ride(null), 'healthFit', rows)).toEqual({ interval: [1, 1, 1], interruption: [0, 0, 0] });
  });

  it('keeps the ride running after an equal-elapsed interruption pause and resume', () => {
    const ride = timelineOf('ios', 1, [
      { t: 0.4, action: 'pause', producer: 'phone', sequence: 1, interrupted: true, cyc: 2 },
      { t: 0.4, action: 'resume', producer: 'phone', sequence: 2 },
    ]);
    expect(ride.intervals).toEqual([
      { id: 1, open: 0, close: 0.4 },
      { id: 2, open: 0.4, close: 1 },
    ]);
    expect(ride.transitions).toEqual([
      { kind: 'close', elapsed: 0.4, interval: 1 },
      { kind: 'open', elapsed: 0.4, interval: 2 },
    ]);
    expect(ride.timerSeconds).toBe(1);
    const sampled = [0.1, 0.4, 0.5, 0.9];
    const telemetry = sampled.map((t, i) => ({ t, sequence: i + 1, epoch: 'initial' }));
    expect(intervals(ride, 'telemetry', telemetry)).toEqual({ interval: [1, 1, 2, 2], interruption: [0, 0, 1, 1] });
    const health = sampled.map((t, i) => ({ t, producer: 'watch', sequence: i + 1, epoch: 'initial' }));
    expect(intervals(ride, 'healthFit', health)).toEqual({ interval: [1, 1, 2, 2], interruption: [0, 0, 1, 1] });
  });

  it('ignores redundant transitions and closes on an interruption action', () => {
    const ride = timelineOf('ios', 10, [
      { t: 0, action: 'start' },
      { t: 2, action: 'pause' },
      { t: 3, action: 'pause' },
      { t: 5, action: 'resume' },
      { t: 6, action: 'resume' },
      { t: 8, action: 'interruption', cyc: 5 },
      { t: 8.5, action: 'pause', interrupted: true, cyc: 7 },
      { t: 9, action: 'resume' },
      { t: 10, action: 'stop' },
    ]);
    expect(ride.intervals).toEqual([
      { id: 1, open: 0, close: 2 },
      { id: 2, open: 5, close: 8 },
      { id: 3, open: 9, close: 10 },
    ]);
    expect(ride.transitions.map(item => [item.kind, item.elapsed])).toEqual([
      ['close', 2],
      ['open', 5],
      ['close', 8],
      ['open', 9],
    ]);
    expect(ride.timerSeconds).toBe(6);
    const rows = [1, 3, 5.5, 6, 8, 8, 8.5, 8.5, 9, 10].map((t, i) => ({ t, sequence: i + 1 }));
    expect(intervals(ride, 'telemetry', rows)).toEqual({
      interval: [1, 0, 2, 2, 2, 0, 0, 0, 3, 3],
      interruption: [0, 0, 0, 0, 0, 1, 1, 2, 2, 2],
    });
  });

  it('includes observations at the final stop elapsed only in an interval closing there', () => {
    const running = timelineOf('ios', 10, [{ t: 0, action: 'start' }]);
    expect(intervals(running, 'telemetry', times([10, 10.5])).interval).toEqual([1, 0]);
    const paused = timelineOf('ios', 10, [{ t: 8, action: 'pause' }]);
    expect(intervals(paused, 'telemetry', times([10])).interval).toEqual([0]);
    const pausedAtEnd = timelineOf('ios', 10, [{ t: 10, action: 'pause' }]);
    expect(pausedAtEnd.intervals).toEqual([{ id: 1, open: 0, close: 10 }]);
    expect(intervals(pausedAtEnd, 'telemetry', times([10])).interval).toEqual([1]);
    const resumedAtEnd = timelineOf('ios', 10, [
      { t: 5, action: 'pause' },
      { t: 10, action: 'resume' },
    ]);
    expect(resumedAtEnd.intervals).toEqual([
      { id: 1, open: 0, close: 5 },
      { id: 2, open: 10, close: 10 },
    ]);
    expect(intervals(resumedAtEnd, 'telemetry', times([10])).interval).toEqual([2]);
    expect(resumedAtEnd.timerSeconds).toBe(5);
  });

  it('ignores lifecycle events after the final stop for intervals and laps', () => {
    const ride = timelineOf('ios', 10, [
      { t: 0, action: 'start' },
      { t: 4, action: 'lap' },
      { t: 12, action: 'pause' },
      { t: 12, action: 'lap' },
    ]);
    expect(ride.intervals).toEqual([{ id: 1, open: 0, close: 10 }]);
    expect(ride.laps.map(lap => [lap.start, lap.end])).toEqual([
      [0, 4],
      [4, 10],
    ]);
    expect(intervals(ride, 'telemetry', times([11, 12, 13])).interval).toEqual([0, 0, 0]);
  });

  it.each([
    ['without a retained telemetry sequence', { cyc: NaN }],
    ['with a negative telemetry sequence', { cyc: -1 }],
    ['with a fractional telemetry sequence', { cyc: 2.5 }],
    ['without a producer sequence', { cyc: 2, sequence: NaN }],
  ])('rejects an interruption %s, even after the final stop', (_, fields) => {
    for (const t of [1, 20])
      expect(code(() => timelineOf('ios', 10, [{ t, action: 'pause', interrupted: true, ...fields }]))).toBe('page');
  });

  it('walks equal-elapsed interruptions in stored order and stops at the first one an observation does not follow', () => {
    const ride = timelineOf('ios', 2, [
      { t: 1, action: 'pause', producer: 'phone', sequence: 3, interrupted: true, cyc: 2 },
      { t: 1, action: 'pause', producer: 'watch', sequence: 5, interrupted: true, cyc: 4 },
      { t: 1, action: 'resume', producer: 'watch', sequence: 6 },
    ]);
    expect(ride.intervals).toEqual([
      { id: 1, open: 0, close: 1 },
      { id: 2, open: 1, close: 2 },
    ]);
    const telemetry = [2, 3, 5].map(sequence => ({ t: 1, sequence }));
    expect(intervals(ride, 'telemetry', telemetry)).toEqual({ interval: [1, 2, 2], interruption: [0, 1, 2] });
    const health = [4, 7].map(sequence => ({ t: 1, producer: 'watch', sequence }));
    expect(intervals(ride, 'healthFit', health)).toEqual({ interval: [1, 1], interruption: [0, 0] });
  });

  it('keeps equal-elapsed transition groups intact across pages', () => {
    const builder = new TimelineBuilder('ios', 3);
    const events = [1, 2].flatMap(t =>
      Array.from({ length: 1536 }, (_, index) => ({ t, action: index % 2 === 0 ? 'resume' : 'pause' })),
    );
    for (let start = 0; start < events.length; start += 128)
      builder.add(lifecycle('ios', events.slice(start, start + 128)));
    const ride = builder.finish();
    expect(ride.intervals).toHaveLength(1536);
    expect(ride.intervals[0]).toEqual({ id: 1, open: 0, close: 1 });
    expect(ride.intervals.slice(1).every(interval => interval.open === interval.close)).toBe(true);
    expect(ride.timerSeconds).toBe(1);
    expect(intervals(ride, 'telemetry', times([0.5, 1, 2, 3])).interval).toEqual([1, 0, 0, 0]);
  });

  it('marks only interruptions as boundaries on the iPhone', () => {
    const ride = timelineOf('ios', 10, [
      { t: 1, action: 'pause' },
      { t: 1, action: 'resume' },
    ]);
    expect(intervals(ride, 'telemetry', [{ t: 1, sequence: 1 }])).toEqual({ interval: [2], interruption: [0] });
  });
});

describe('lifecycle and observation order', () => {
  it('rejects lifecycle events without a valid elapsed time or out of order', () => {
    expect(code(() => timelineOf('ios', 10, [{ t: NaN, action: 'lap' }]))).toBe('page');
    expect(code(() => timelineOf('android', 10, [{ t: -0.5, action: 'pause' }]))).toBe('page');
    expect(
      code(() =>
        timelineOf('web', 10, [
          { t: 5, action: 'pause' },
          { t: 4, action: 'resume' },
        ]),
      ),
    ).toBe('cursor');
    const builder = new TimelineBuilder('ios', 10);
    builder.add(lifecycle('ios', [{ t: 5, action: 'lap' }]));
    expect(code(() => builder.add(lifecycle('ios', [{ t: 4, action: 'lap' }])))).toBe('cursor');
  });

  it('maps across pages with one cursor and rejects observations out of elapsed order', () => {
    const ride = timelineOf('ios', 10, [
      { t: 2, action: 'pause' },
      { t: 4, action: 'resume' },
    ]);
    expect(intervals(ride, 'telemetry', times([0, 1, 2, 3, 4, 5]), 1).interval).toEqual([1, 1, 0, 0, 2, 2]);
    const cursor = ride.cursor('telemetry');
    cursor.map(observations('telemetry', 'ios', times([5])));
    expect(code(() => cursor.map(observations('telemetry', 'ios', times([4.5]))))).toBe('cursor');
    expect(
      Array.from(ride.cursor('telemetry').map(observations('telemetry', 'ios', times([1, NaN, 5]))).interval),
    ).toEqual([1, 0, 2]);
  });

  it('requires the envelope columns its platform lists', () => {
    const ride = timelineOf('ios', 10, [{ t: 0, action: 'start' }]);
    const page = observations('telemetry', 'ios', times([1]));
    delete (page.columns as Record<string, unknown>).clockEpoch;
    expect(code(() => ride.cursor('telemetry').map(page))).toBe('page');
    const lifecyclePage = lifecycle('ios', [{ t: 1, action: 'pause' }]);
    delete (lifecyclePage.columns as Record<string, unknown>).interrupted;
    expect(code(() => new TimelineBuilder('ios', 10).add(lifecyclePage))).toBe('page');
    const android = timelineOf('android', 10, [{ t: 0, action: 'start' }]);
    const captured = observations('telemetry', 'android', times([1]));
    delete (captured.columns as Record<string, unknown>).active;
    expect(code(() => android.cursor('telemetry').map(captured))).toBe('page');
    const web = timelineOf('web', 10, [{ t: 0, action: 'start' }]);
    const stored = observations('telemetry', 'web', times([1]));
    delete (stored.columns as Record<string, unknown>).interval;
    expect(code(() => web.cursor('telemetry').map(stored))).toBe('page');
  });
});

describe('captured activity on Android and web', () => {
  it('lets the Android interval that opens at a shared elapsed win', () => {
    const ride = timelineOf('android', 30, [
      { t: 0, action: 'start' },
      { t: 10, action: 'pause' },
      { t: 10, action: 'resume' },
      { t: 20, action: 'pause' },
      { t: 25, action: 'resume' },
      { t: 30, action: 'stop', interrupted: true },
    ]);
    expect(ride.intervals).toEqual([
      { id: 1, open: 0, close: 10 },
      { id: 2, open: 10, close: 20 },
      { id: 3, open: 25, close: 30 },
    ]);
    const rows = [
      { t: 5 },
      { t: 5, active: false },
      { t: 10 },
      { t: 10, active: false },
      { t: 20 },
      { t: 22 },
      { t: 25 },
      { t: 30 },
      { t: 31 },
    ];
    expect(intervals(ride, 'telemetry', rows)).toEqual({
      interval: [1, 0, 2, 0, 2, 0, 3, 3, 0],
      interruption: [0, 0, 0, 0, 0, 0, 0, 0, 0],
    });
    expect(intervals(ride, 'gps', rows).interval).toEqual([1, 0, 2, 0, 2, 0, 3, 3, 0]);
    expect(ride.timerSeconds).toBe(25);
  });

  it('lets the stored web interval choose between intervals meeting at one elapsed', () => {
    const ride = timelineOf('web', 30, [
      { t: 0, action: 'start' },
      { t: 10, action: 'pause' },
      { t: 10, action: 'resume' },
      { t: 15, action: 'lap' },
      { t: 20, action: 'pause' },
      { t: 25, action: 'resume' },
      { t: 30, action: 'save' },
    ]);
    const rows = [
      { t: 5, stored: 0 },
      { t: 10, stored: 0 },
      { t: 10, stored: 0, active: false },
      { t: 10, stored: 1 },
      { t: 20, stored: 1 },
      { t: 25, stored: 2 },
      { t: 27, stored: 7 },
      { t: 30, stored: 2 },
    ];
    expect(intervals(ride, 'telemetry', rows).interval).toEqual([1, 1, 0, 2, 2, 3, 3, 3]);
    expect(ride.laps.map(lap => [lap.start, lap.end, lap.timerSeconds])).toEqual([
      [0, 15, 15],
      [15, 30, 10],
    ]);
  });

  it('treats a web interruption as the final stop', () => {
    const ride = timelineOf('web', 20, [
      { t: 0, action: 'start' },
      { t: 10, action: 'pause' },
      { t: 12, action: 'resume' },
      { t: 20, action: 'interrupted' },
    ]);
    expect(ride.intervals).toEqual([
      { id: 1, open: 0, close: 10 },
      { id: 2, open: 12, close: 20 },
    ]);
    expect(intervals(ride, 'telemetry', [{ t: 20, stored: 1 }]).interval).toEqual([2]);
  });
});

describe('runs', () => {
  const projected = <P extends RunProjection>(page: Page<P>, connection: number[] | null) =>
    ({ ...page, connection: connection ? Int32Array.from(connection) : null }) as ProjectionPage<P>;
  const runs = <P extends RunProjection>(
    tracker: RunTracker<P>,
    page: Page<P>,
    interval: number[],
    connection: number[] | null = null,
  ) =>
    Array.from(
      tracker.map(projected(page, connection), {
        interval: Int32Array.from(interval),
        interruption: new Int32Array(interval.length),
      }),
    );
  const mapped = <P extends RunProjection>(
    timeline: Timeline,
    tracker: RunTracker<P>,
    page: Page<P>,
    connection: number[] | null = null,
  ) => {
    const assignment = timeline.cursor(tracker.projection).map(page);
    return {
      interval: Array.from(assignment.interval),
      interruption: Array.from(assignment.interruption),
      run: Array.from(tracker.map(projected(page, connection), assignment)),
    };
  };

  it('starts a run at an interrupted resume while the ride is already running', () => {
    const ride = timelineOf('ios', 10, [
      { t: 0, action: 'start', sequence: 1 },
      { t: 4, action: 'resume', sequence: 2, interrupted: true, cyc: 3 },
    ]);
    expect(ride.intervals).toEqual([{ id: 1, open: 0, close: 10 }]);
    const telemetry = observations(
      'telemetry',
      'ios',
      [1, 2, 3, 5, 6].map((t, i) => ({ t, sequence: i + 1, epoch: 'e' })),
    );
    expect(mapped(ride, new RunTracker('ios', 'telemetry'), telemetry, [1, 1, 1, 1, 1])).toEqual({
      interval: [1, 1, 1, 1, 1],
      interruption: [0, 0, 0, 1, 1],
      run: [1, 1, 1, 2, 2],
    });
    const fixes = observations('gps', 'ios', [
      { t: 3, producer: 'phone', sequence: 1, epoch: 'p' },
      { t: 3.5, producer: 'watch', sequence: 1, epoch: 'w' },
      { t: 5, producer: 'phone', sequence: 3, epoch: 'p' },
      { t: 5.5, producer: 'watch', sequence: 2, epoch: 'w' },
    ]);
    expect(mapped(ride, new RunTracker('ios', 'gps'), fixes)).toEqual({
      interval: [1, 1, 1, 1],
      interruption: [0, 0, 1, 1],
      run: [1, 2, 3, 4],
    });
  });

  it('starts a run between the two sides of an interruption at the final stop', () => {
    const ride = timelineOf('ios', 10, [
      { t: 0, action: 'start', sequence: 1 },
      { t: 10, action: 'pause', sequence: 5, interrupted: true, epoch: 'old', cyc: 2 },
    ]);
    expect(ride.intervals).toEqual([{ id: 1, open: 0, close: 10 }]);
    const telemetry = observations(
      'telemetry',
      'ios',
      [9, 10, 10].map((t, i) => ({ t, sequence: i + 1, epoch: 'old' })),
    );
    const expected = { interval: [1, 1, 1], interruption: [0, 0, 1], run: [1, 1, 2] };
    expect(mapped(ride, new RunTracker('ios', 'telemetry'), telemetry, [1, 1, 1])).toEqual(expected);
    const fixes = observations(
      'gps',
      'ios',
      [9, 10, 10].map((t, i) => ({ t, producer: 'phone', sequence: [1, 4, 6][i], epoch: 'old' })),
    );
    expect(mapped(ride, new RunTracker('ios', 'gps'), fixes)).toEqual(expected);
  });

  it('starts a telemetry run at a reconnect, even back to an earlier connection', () => {
    const page = observations('telemetry', 'ios', times([1, 2, 3, 4, 5]));
    expect(runs(new RunTracker('ios', 'telemetry'), page, [1, 1, 1, 1, 1], [1, 1, 2, 2, 1])).toEqual([1, 1, 2, 2, 3]);
  });

  it.each([
    [0.1, 0.4, 0.5, 0.9, 1.1],
    [0.1, 0.4, 0.4, 0.9, 1.1],
  ])('starts a telemetry run at a clock-epoch change without a lifecycle change (%s, %s, %s, ...)', (...sampled) => {
    const ride = timelineOf('ios', 1.1, [
      { t: 0.4, action: 'pause', producer: 'watch', sequence: 1, interrupted: true, cyc: 2 },
    ]);
    const epochs = ['first', 'first', 'second', 'second', 'third'];
    const rows = sampled.map((t, i) => ({ t, sequence: i + 1, epoch: epochs[i] }));
    const page = observations('telemetry', 'ios', rows);
    expect(mapped(ride, new RunTracker('ios', 'telemetry'), page, [1, 1, 1, 1, 1])).toEqual({
      interval: [1, 1, 0, 0, 0],
      interruption: [0, 0, 1, 1, 1],
      run: [1, 1, 2, 2, 3],
    });
  });

  it('starts a run when the activity interval changes, keeping paused readings together', () => {
    const page = observations('telemetry', 'android', times([1, 2, 3, 4, 5, 6]));
    expect(runs(new RunTracker('android', 'telemetry'), page, [1, 1, 0, 0, 2, 2], [1, 1, 1, 1, 1, 1])).toEqual([
      1, 1, 2, 2, 3, 3,
    ]);
  });

  it('isolates every GPS barrier fix in its own run', () => {
    const fixes = [1, 2, 3, 4, 5, 6].map((t, i) => ({ t, barrier: [false, true, false, true, true, false][i] }));
    const page = observations('gps', 'ios', fixes);
    expect(runs(new RunTracker('ios', 'gps'), page, [1, 1, 1, 1, 1, 1])).toEqual([1, 2, 3, 4, 5, 6]);
  });

  it('tracks GPS runs per producer and numbers them per table in order of first appearance', () => {
    const fixes = [
      { t: 1, producer: 'phone', epoch: 'p' },
      { t: 1, producer: 'watch', epoch: 'w' },
      { t: 2, producer: 'phone', epoch: 'p' },
      { t: 2, producer: 'watch', epoch: 'w' },
      { t: 3, producer: 'phone', epoch: 'p', barrier: true },
      { t: 3, producer: 'watch', epoch: 'w2' },
      { t: 4, producer: 'phone', epoch: 'p' },
      { t: 4, producer: 'watch', epoch: 'w2' },
    ];
    const page = observations('gps', 'ios', fixes);
    const tracker = new RunTracker('ios', 'gps');
    expect(runs(tracker, page, [1, 1, 1, 1, 1, 1, 1, 1])).toEqual([1, 2, 1, 2, 3, 4, 5, 4]);
    expect(runs(tracker, observations('gps', 'ios', [{ t: 5, producer: 'watch', epoch: 'w2' }]), [1])).toEqual([4]);
    expect(runs(new RunTracker('ios', 'gps'), observations('gps', 'ios', fixes.slice(1, 2)), [1])).toEqual([1]);
  });

  it('starts an Android GPS run at a pause without clock epochs', () => {
    const page = observations('gps', 'android', times([1, 2, 3]));
    expect(runs(new RunTracker('android', 'gps'), page, [1, 1, 2])).toEqual([1, 1, 2]);
  });
});

describe('laps and timer', () => {
  it('drops laps at 0 and at the final stop', () => {
    const ride = timelineOf('ios', 10, [
      { t: 0, action: 'lap' },
      { t: 10, action: 'lap' },
    ]);
    expect(ride.laps).toEqual([{ start: 0, end: 10, timerSeconds: 10 }]);
    expect(ride.transitions).toEqual([]);
  });

  it('collapses a repeated lap', () => {
    const ride = timelineOf('android', 10, [
      { t: 5, action: 'lap' },
      { t: 5, action: 'lap' },
    ]);
    expect(ride.laps).toEqual([
      { start: 0, end: 5, timerSeconds: 5 },
      { start: 5, end: 10, timerSeconds: 5 },
    ]);
    expect(ride.transitions).toEqual([{ kind: 'lap', elapsed: 5, lap: 1 }]);
  });

  it('counts only active time of a lap marked while paused', () => {
    const ride = timelineOf('ios', 10, [
      { t: 3, action: 'pause' },
      { t: 5, action: 'lap' },
      { t: 7, action: 'resume' },
    ]);
    expect(ride.laps).toEqual([
      { start: 0, end: 5, timerSeconds: 3 },
      { start: 5, end: 10, timerSeconds: 3 },
    ]);
    expect(ride.transitions.map(item => item.kind)).toEqual(['close', 'lap', 'open']);
  });

  it('sums activity intervals for the session and any range', () => {
    const ride = timelineOf('ios', 14, [
      { t: 0, action: 'start' },
      { t: 3, action: 'pause' },
      { t: 7, action: 'resume' },
      { t: 8, action: 'lap' },
      { t: 14, action: 'stop' },
    ]);
    expect(ride.timerSeconds).toBe(10);
    expect(ride.laps).toEqual([
      { start: 0, end: 8, timerSeconds: 4 },
      { start: 8, end: 14, timerSeconds: 6 },
    ]);
    expect([ride.timer(1, 8), ride.timer(3, 7), ride.timer(5, 5), ride.timer(13, 20)]).toEqual([3, 0, 0, 1]);
  });

  it('writes one zero-length lap for a ride stopped at its start', () => {
    const ride = timelineOf('ios', 0, [
      { t: 0, action: 'start' },
      { t: 0, action: 'stop' },
    ]);
    expect(ride.intervals).toEqual([{ id: 1, open: 0, close: 0 }]);
    expect(ride.laps).toEqual([{ start: 0, end: 0, timerSeconds: 0 }]);
    expect(intervals(ride, 'telemetry', times([0])).interval).toEqual([1]);
  });
});

describe('guards', () => {
  const laps = (count: number) => Array.from({ length: count }, (_, i) => ({ t: (i + 1) / 1000, action: 'lap' }));
  const build = (count: number, fitLimits: boolean) => {
    const builder = new TimelineBuilder('ios', 100, { fitLimits });
    const events = laps(count);
    for (let start = 0; start < count; start += 4096) builder.add(lifecycle('ios', events.slice(start, start + 4096)));
    return builder.finish();
  };

  it('accepts at most 10,000 lifecycle events for FIT', () => {
    expect(FIT_MAX_LIFECYCLE_EVENTS).toBe(10_000);
    expect(build(9_999, true).laps).toHaveLength(10_000);
    expect(build(10_000, true).laps).toHaveLength(10_001);
    expect(code(() => build(10_001, true))).toBe('limit');
  });

  it('accepts a final stop elapsed of at most 31 days for FIT', () => {
    expect(FIT_MAX_RIDE_SECONDS).toBe(31 * 86_400);
    expect(new TimelineBuilder('android', 2_678_400, { fitLimits: true }).finish().timerSeconds).toBe(2_678_400);
    expect(code(() => new TimelineBuilder('android', 2_678_400.001, { fitLimits: true }))).toBe('limit');
  });

  it('applies neither FIT limit without the FIT option', () => {
    expect(build(10_001, false).laps).toHaveLength(10_002);
    expect(new TimelineBuilder('web', 2_678_401).finish().timerSeconds).toBe(2_678_401);
    expect(new TimelineBuilder('android', 2_678_401, { fitLimits: false }).finish().intervals).toEqual([
      { id: 1, open: 0, close: 2_678_401 },
    ]);
  });

  it.each([true, false])('rejects a final stop elapsed that is not a time, with FIT limits %s', fitLimits => {
    expect(code(() => new TimelineBuilder('web', NaN, { fitLimits }))).toBe('gate');
    expect(code(() => new TimelineBuilder('ios', -1, { fitLimits }))).toBe('gate');
    expect(code(() => new TimelineBuilder('android', Infinity, { fitLimits }))).toBe('gate');
  });
});

describe('timeline read', () => {
  it('builds the timeline from the paged lifecycle projection', async () => {
    const events: Event[] = [
      { t: 0, action: 'start' },
      { t: 2, action: 'pause' },
      { t: 4, action: 'resume' },
      { t: 6, action: 'lap' },
    ];
    const pageOf = (slice: Event[], last: number[] | null, done: boolean) => {
      const values = lifecycle('ios', slice).columns as Record<string, unknown>;
      const columns: Record<string, unknown> = {};
      for (const column of projectionColumns('lifecycle', 'ios', 'fit')) columns[column.name] = values[column.name];
      return { rows: slice.length, last, done, columns } as ExportPage<'lifecycle'>;
    };
    const pages = [pageOf(events.slice(0, 3), [4, 1, 3, 3], false), pageOf(events.slice(3), [6, 1, 4, 4], true)];
    const requests: ExportPageRequest[] = [];
    const source: ExportSource = {
      open: () => Promise.reject(new Error('unused')),
      close: () => Promise.resolve(),
      page: <P extends ProjectionName>(request: ExportPageRequest<P>) => {
        requests.push(request);
        return Promise.resolve(pages[requests.length - 1] as unknown as ExportPage<P>);
      },
    };
    const reads = new ExportReads({ platform: 'ios', kind: 'fit', slicer: { tick: () => Promise.resolve() } });
    const ride = await readTimeline(source, 'session', 10, reads, { fitLimits: true });
    expect(ride.intervals).toEqual([
      { id: 1, open: 0, close: 2 },
      { id: 2, open: 4, close: 10 },
    ]);
    expect(ride.laps.map(lap => lap.end)).toEqual([6, 10]);
    expect(reads.passes.rows('lifecycle')).toBe(4);
    expect(requests.map(request => request.projection)).toEqual(['lifecycle', 'lifecycle']);
  });

  it('applies the FIT limits only when its caller asks for them', async () => {
    let requests = 0;
    const source: ExportSource = {
      open: () => Promise.reject(new Error('unused')),
      close: () => Promise.resolve(),
      page: <P extends ProjectionName>() => {
        requests++;
        return Promise.resolve({ rows: 0, last: null, done: true, columns: {} } as ExportPage<P>);
      },
    };
    const read = (fitLimits: boolean) =>
      readTimeline(
        source,
        'session',
        2_678_401,
        new ExportReads({ platform: 'web', kind: 'zip', slicer: { tick: () => Promise.resolve() } }),
        { fitLimits },
      );
    expect(await read(true).catch((error: ExportError) => error.code)).toBe('limit');
    expect(requests).toBe(0);
    expect((await read(false)).intervals).toEqual([{ id: 1, open: 0, close: 2_678_401 }]);
    expect(requests).toBe(1);
  });
});
