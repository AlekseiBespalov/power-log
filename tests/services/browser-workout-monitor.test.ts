import { afterEach, describe, expect, it, vi } from 'vitest';
import { currentMonitorPoint, MONITOR_METRICS } from '../../src/core/monitor';
import {
  BrowserRideStore,
  BROWSER_PAGE,
  TILE_SECONDS,
  mergeAggregate,
  rowAggregate,
  type BrowserRide,
  type RideRow,
  type RideTile,
} from '../../src/services/browser-ride-store';
import { browserWorkoutMonitorSource, registerBrowserWorkoutClock } from '../../src/services/browser-workout-monitor';
import { BrowserWorkoutRecorder } from '../../src/services/browser-workout-recorder';
import type { AdapterEvents } from '../../src/services/adapter';
import { TestTelemetryAdapter } from '../support/telemetry-adapter';
import { ensureBrowserDistance, browserDistanceCurrent } from '../../src/services/browser-distance-store';
import { MonitorReadController } from '../../src/features/monitor/monitor-read-controller';
import { syntheticSample } from '../fixtures/synthetic-sample';
import { browserRide, browserRow } from '../support/browser-ride';

vi.mock('../../src/services/browser-distance-store', async importOriginal => ({
  ...(await importOriginal<typeof import('../../src/services/browser-distance-store')>()),
  peekBrowserDistance: vi.fn(async () => undefined),
  ensureBrowserDistance: vi.fn(async () => {
    throw new Error('No distance provider in this fixture');
  }),
  browserDistanceCurrent: vi.fn(async () => true),
}));
afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  vi.useRealTimers();
});
const settle = async () => {
  for (let i = 0; i < 100; i++) await Promise.resolve();
};

function fixture(
  input: {
    time: number;
    power: number;
    cadence?: number;
    active?: boolean;
    interval?: number;
    connectionEpoch?: string;
    interruptionIndex?: number;
  }[],
) {
  const id = crypto.randomUUID(),
    store = new BrowserRideStore();
  const rows: RideRow[] = input.map(({ time, power, cadence = 72, ...rest }, sequence) => ({
    ...browserRow(time, sequence),
    ...rest,
    recordingId: id,
    humanPowerW: power,
    cadenceRpm: cadence,
  }));
  const record: BrowserRide = browserRide({
    id,
    samples: rows.length,
    elapsedSeconds: rows.at(-1)?.elapsedSeconds ?? 0,
  });
  vi.spyOn(store, 'get').mockImplementation(async () => record);
  vi.spyOn(store, 'bySequence').mockImplementation(async (_id, sequence) => rows[sequence]);
  vi.spyOn(store, 'page').mockImplementation(async (_id, start, end, after) =>
    rows
      .filter(
        row =>
          row.elapsedSeconds >= start &&
          row.elapsedSeconds <= end &&
          (!after || row.elapsedSeconds > after[0] || (row.elapsedSeconds === after[0] && row.sequence > after[1])),
      )
      .slice(0, BROWSER_PAGE),
  );
  vi.spyOn(store, 'neighbor').mockImplementation(async (_id, time, direction) =>
    direction === 'prev'
      ? rows.filter(row => row.elapsedSeconds <= time).at(-1)
      : rows.find(row => row.elapsedSeconds >= time),
  );
  vi.spyOn(store, 'tiles').mockImplementation(async (_id, level, first, last) => {
    const tiles = new Map<number, RideTile>();
    for (const row of rows) {
      const bucket = Math.floor(row.elapsedSeconds / TILE_SECONDS[level]!);
      if (bucket < first || bucket > last) continue;
      const tile = tiles.get(bucket) ?? { recordingId: id, level, bucket, stats: {} };
      for (const metric of MONITOR_METRICS) {
        const next = rowAggregate(row, metric.id);
        if (next) tile.stats[metric.id] = mergeAggregate(tile.stats[metric.id], next, metric.id);
      }
      tiles.set(bucket, tile);
    }
    return [...tiles.values()];
  });
  return { store, record, rows, source: browserWorkoutMonitorSource(id, false, store) };
}

describe('browser monitor projections', () => {
  it('keeps stored acquisitions fixed across rereads and clears evidence when capture stops', async () => {
    const { store, record } = fixture([{ time: 1.125, power: 100 }]);
    const origin = 100.456;
    let now = origin + 2;
    let accepting = true;
    const release = registerBrowserWorkoutClock(
      record.id,
      origin,
      () => (accepting ? { nowSeconds: now - origin, monotonicAt: now } : undefined),
      store,
    );
    const source = browserWorkoutMonitorSource(record.id, true, store);
    try {
      for (const advance of [0, 4, 8, 0.333]) {
        now += advance;
        const latest = await source.readLatest({ generation: 1, metrics: ['humanPowerW', 'heartRateBpm', 'speedMps'] });
        expect(latest).toMatchObject({
          monotonicAt: now,
          nowSeconds: now - origin,
          liveAcquiredAt: { humanPowerW: origin + 1.125, heartRateBpm: null, speedMps: null },
        });
      }
      accepting = false;
      const description = await source.describeSource({ generation: 2 });
      const latest = await source.readLatest({ generation: 2, metrics: ['humanPowerW'] });
      for (const value of [description, latest]) {
        expect(value).not.toHaveProperty('nowSeconds');
        expect(value).not.toHaveProperty('monotonicAt');
        expect(value).not.toHaveProperty('liveAcquiredAt');
      }
    } finally {
      release();
    }
  });
  it.each([false, true])(
    'separates hard interruptions from BLE reconnects in raw, reduced and every tile level: hard=%s',
    async hard => {
      const { source, rows } = fixture(
        Array.from({ length: 80 }, (_, time) => ({
          time,
          power: 100,
          interruptionIndex: hard && time >= 32 ? 1 : 0,
          connectionEpoch: !hard && time >= 32 ? 'reconnected' : 'test-epoch',
        })),
      );
      const description = await source.describeSource({ generation: 1 });
      const query = {
        generation: 1,
        expectedRevision: description.revision,
        metrics: ['humanPowerW'],
        startSeconds: 0,
        pixelWidth: 4,
      };
      for (const endSeconds of [79, ...TILE_SECONDS.map(size => size * 3)]) {
        const plot = await source.readPlot({ ...query, endSeconds });
        if (plot.status !== 'ok') throw new Error('Unexpected retry');
        expect(
          plot.series.humanPowerW!.filter(point => point.startsSegment).map(point => point.elapsedSeconds),
        ).toEqual(hard ? [0, 32] : [0]);
      }
      const raw = await source.readPlot({ ...query, startSeconds: 30, endSeconds: 34, pixelWidth: 240 });
      if (raw.status !== 'ok') throw new Error('Unexpected retry');
      expect(raw.series.humanPowerW!.filter(point => point.startsSegment).map(point => point.elapsedSeconds)).toEqual(
        hard ? [30, 32] : [30],
      );
      expect(await source.rangeStats({ ...query, endSeconds: 128 })).toMatchObject({
        statistics: { humanPowerW: { count: 80, coveredSeconds: 78, integral: 7800 } },
      });
      expect(
        await source.rangeStats({ ...query, startSeconds: 31.25, endSeconds: 31.75, includeEndpoints: true }),
      ).toMatchObject({
        statistics: {},
        ...(hard ? { endpoints: { start: { humanPowerW: null }, end: { humanPowerW: null } } } : {}),
      });
      const selection = await source.inspectAt({ ...query, seconds: 31.5 });
      expect(selection).toMatchObject({ points: { humanPowerW: hard ? null : { elapsedSeconds: 31 } } });
      for (const seconds of [31, 32])
        expect(
          await source.inspectAt({
            ...query,
            seconds,
            anchor: { metric: 'humanPowerW', observationId: `${rows[seconds]!.recordingId}:${seconds}` },
          }),
        ).toMatchObject({ points: { humanPowerW: { elapsedSeconds: seconds, timestamp: rows[seconds]!.timestamp } } });
    },
  );
  it('does not lend the last original through a sealed interruption on a retained live source', async () => {
    const { store, record } = fixture([{ time: 1, power: 100 }]);
    const source = browserWorkoutMonitorSource(record.id, true, store);
    const query = {
      generation: 1,
      expectedRevision: (await source.describeSource({ generation: 1 })).revision,
      metrics: ['humanPowerW'],
      seconds: 2,
    };
    expect(await source.inspectAt(query)).toMatchObject({ points: { humanPowerW: { elapsedSeconds: 1 } } });
    Object.assign(record, {
      phase: 'completed',
      interrupted: true,
      endedAt: record.checkpointAt,
      revision: record.revision + 1,
    });
    query.expectedRevision = (await source.describeSource({ generation: 1 })).revision;
    expect(await source.inspectAt(query)).toMatchObject({ points: { humanPowerW: null } });
    expect(await source.inspectAt({ ...query, seconds: 1 })).toMatchObject({
      points: { humanPowerW: { elapsedSeconds: 1 } },
    });
  });
  it.each([
    { name: 'empty', rows: [], maximum: undefined },
    { name: 'single point at zero', rows: [{ time: 0, power: 91 }], maximum: 91 },
    {
      name: 'paused spike',
      rows: [
        { time: 0, power: 100 },
        { time: 1, power: 999, cadence: 999, active: false },
        { time: 2, power: 150, interval: 1 },
      ],
      maximum: 150,
    },
    {
      name: 'bucket boundary',
      rows: [
        { time: 15, power: 100 },
        { time: 16, power: 270 },
        { time: 17, power: 999, cadence: 999, active: false },
        { time: 64, power: 180, interval: 1 },
      ],
      maximum: 270,
    },
  ])('summarizes active maxima from aggregates: $name', async ({ rows: input, maximum }) => {
    const { store, record } = fixture(input),
      recorder = new BrowserWorkoutRecorder(store);
    vi.mocked(ensureBrowserDistance).mockResolvedValueOnce({
      record,
      profile: {
        recordingId: record.id,
        generation: 'test',
        inputSequence: record.samples - 1,
        state: { distance: 0, covered: 0, intervals: 0 },
        segment: -1,
        plotSegment: 0,
        building: false,
        recordRevision: String(record.revision),
        lifecycleRevision: 0,
        lifecycleCount: 0,
        activeIntervals: [],
        activeOpen: null,
      },
      info: { selection: 'auto', selected: null, available: [] },
    });
    vi.mocked(browserDistanceCurrent).mockResolvedValue(true);
    const { summary } = await recorder.read(record.id);
    expect(summary.maximumRiderPowerW).toBe(maximum);
    expect(summary.maximumCadenceRpm).toBe(input.length ? 72 : undefined);
  });

  it.each([
    { times: [0, 2], powers: [100, 200], start: 0.5, end: 1.5, covered: 1, integral: 150 },
    { times: [0, 1], powers: [100, 100], start: 0.2, end: 0.8, covered: 0.6, integral: 60 },
  ])(
    'integrates between originals without adding observations: $start to $end',
    async ({ times, powers, start, end, covered, integral }) => {
      const { source } = fixture(times.map((time, i) => ({ time, power: powers[i]! })));
      const description = await source.describeSource({ generation: 1 });
      const result = await source.rangeStats({
        generation: 1,
        expectedRevision: description.revision,
        metrics: ['humanPowerW'],
        startSeconds: start,
        endSeconds: end,
      });
      if (result.status !== 'ok') throw new Error('Unexpected retry');
      const stats = result.statistics.humanPowerW!;
      expect(stats.count).toBe(0);
      expect(stats.coveredSeconds).toBeCloseTo(covered);
      expect(stats.integral).toBeCloseTo(integral);
      expect(stats.min).toBeUndefined();
      expect(stats.max).toBeUndefined();
      expect(stats.sampleMean).toBeUndefined();
    },
  );

  it('keeps pauses out of statistics while retaining paused originals in coarse plots and exact inspection', async () => {
    const { source } = fixture([
      { time: 0, power: 100 },
      { time: 1, power: 200 },
      { time: 2, power: 1000, active: false },
      { time: 3, power: 300, interval: 1 },
      { time: 4, power: 400, interval: 1 },
    ]);
    const description = await source.describeSource({ generation: 1 });
    const query = {
      generation: 1,
      expectedRevision: description.revision,
      metrics: ['humanPowerW'],
      startSeconds: 0,
      endSeconds: 256,
      pixelWidth: 4,
    };
    const stats = await source.rangeStats(query),
      paused = await source.rangeStats({ ...query, startSeconds: 1.5, endSeconds: 2.5 }),
      plot = await source.readPlot(query),
      exact = await source.inspectAt({ ...query, seconds: 2 });
    expect(stats).toMatchObject({
      statistics: {
        humanPowerW: {
          count: 4,
          min: { value: 100 },
          max: { value: 400 },
          sampleMean: 250,
          coveredSeconds: 2,
          integral: 500,
        },
      },
    });
    if (paused.status !== 'ok' || plot.status !== 'ok') throw new Error('Unexpected retry');
    expect(paused.statistics.humanPowerW).toMatchObject({ count: 0, coveredSeconds: 0, integral: 0 });
    expect(paused.statistics.humanPowerW?.min).toBeUndefined();
    expect(paused.statistics.humanPowerW?.max).toBeUndefined();
    expect(paused.statistics.humanPowerW?.sampleMean).toBeUndefined();
    expect(plot.series.humanPowerW?.some(point => point.value === 1000)).toBe(true);
    expect(exact).toMatchObject({ points: { humanPowerW: { elapsedSeconds: 2, value: 1000 } } });
  });

  it('applies the controller integration limit to whole tiles, clipped edges and empty ranges', async () => {
    const { source } = fixture([0, 2.5, 6].map(time => ({ time, power: 100 })));
    const description = await source.describeSource({ generation: 1 });
    const query = {
      generation: 1,
      expectedRevision: description.revision,
      metrics: ['humanPowerW', 'motorInputPowerW', 'batteryVoltageV', 'controllerTempC', 'assistLevel'],
    };
    for (const [startSeconds, endSeconds, covered] of [
      [0, 16, 2.5],
      [1, 5, 1.5],
    ]) {
      const result = await source.rangeStats({ ...query, startSeconds: startSeconds!, endSeconds: endSeconds! });
      if (result.status !== 'ok') throw new Error('Unexpected retry');
      for (const metric of query.metrics) expect(result.statistics[metric]?.coveredSeconds, metric).toBe(covered);
    }
    const empty = await source.rangeStats({ ...query, startSeconds: 3, endSeconds: 5 });
    if (empty.status !== 'ok') throw new Error('Unexpected retry');
    expect(empty.statistics).toEqual({});
  });

  it.each([
    { times: [0, 1, 8, 9, 16, 17], end: 256, starts: [0, 8, 16] },
    { times: [62, 63, 64, 65, 72, 73], end: 192, starts: [62, 72] },
  ])('preserves run boundaries within and across wide tiles: $end', async ({ times, end, starts }) => {
    const powers = [0, 100, 50, 51, 0, 100],
      { source } = fixture(times.map((time, i) => ({ time, power: powers[i]! })));
    const description = await source.describeSource({ generation: 1 });
    const result = await source.readPlot({
      generation: 1,
      expectedRevision: description.revision,
      metrics: ['humanPowerW'],
      startSeconds: 0,
      endSeconds: end,
      pixelWidth: 4,
    });
    if (result.status !== 'ok') throw new Error('Unexpected retry');
    expect(result.series.humanPowerW?.map(point => point.elapsedSeconds)).toEqual(times);
    expect(result.series.humanPowerW?.map(point => point.value)).toEqual(powers);
    expect(result.series.humanPowerW?.filter(point => point.startsSegment).map(point => point.elapsedSeconds)).toEqual(
      starts,
    );
  });

  it('records notification acquisition rather than delayed delivery in storage and live presentation', async () => {
    vi.useFakeTimers({ toFake: ['Date', 'performance', 'setTimeout', 'clearTimeout', 'setInterval', 'clearInterval'] });
    vi.setSystemTime(Date.UTC(2026, 0, 1));
    vi.stubGlobal('indexedDB', {});
    vi.stubGlobal('navigator', {
      locks: { request: (_name: string, _options: unknown, work: (lock: object) => Promise<void>) => work({}) },
    });
    const { store, record, rows } = fixture([]);
    vi.spyOn(store, 'recoverOrphan').mockResolvedValue(null);
    vi.spyOn(store, 'begin').mockResolvedValue(record);
    vi.spyOn(store, 'append').mockImplementation(async (_id, _token, batch, elapsed, timer, at) => {
      expect(elapsed).toBe(batch.at(-1)!.elapsedSeconds);
      expect(timer).toBe(elapsed);
      expect(at).toBe(batch.at(-1)!.timestamp);
      rows.push(...batch);
      return Object.assign(record, {
        elapsedSeconds: elapsed,
        timerSeconds: timer,
        checkpointAt: at,
        samples: rows.length,
        revision: record.revision! + 1,
      });
    });
    vi.spyOn(store, 'transition').mockImplementation(async (_id, _token, action, elapsed, timer, at) =>
      Object.assign(record, {
        elapsedSeconds: elapsed,
        timerSeconds: timer,
        ...(action === 'save' ? { endedAt: at } : {}),
        revision: record.revision! + 1,
      }),
    );
    class Source extends TestTelemetryAdapter {
      override subscribe(events: AdapterEvents) {
        return super.subscribe({
          ...events,
          sample: (sample, delivery) => {
            setTimeout(() => events.sample(sample, delivery), 2000);
          },
        });
      }
      sample(sequence: number) {
        this.emitSample({
          ...syntheticSample(performance.now() / 1000, sequence, new Date().toISOString()),
          connectionEpoch: this.connectionEpoch,
        });
      }
    }
    const source = new Source(),
      recorder = new BrowserWorkoutRecorder(store);
    recorder.setTelemetrySource(source);
    await source.connect();
    await recorder.start({ indoor: true, useWatch: false });
    const live = browserWorkoutMonitorSource(record.id, true, store),
      controller = new MonitorReadController(live);
    try {
      await vi.advanceTimersByTimeAsync(1000);
      source.sample(0);
      await vi.advanceTimersByTimeAsync(2000);
      await recorder.lap();
      expect(rows).toHaveLength(1);
      expect(rows[0]).toMatchObject({ elapsedSeconds: 1, originalElapsedSeconds: 1 });
      expect((await recorder.getState()).streams.cyc.status).toBe('receiving');
      controller.configure(['humanPowerW'], 'all');
      controller.refresh();
      await settle();
      expect(controller.getSnapshot().liveAcquiredAt.humanPowerW).toBe(1);
      await vi.advanceTimersByTimeAsync(3999);
      expect((await recorder.getState()).streams.cyc.status).toBe('receiving');
      await vi.advanceTimersByTimeAsync(1);
      expect((await recorder.getState()).streams.cyc.status).toBe('stale');
      controller.tick();
      const state = controller.getSnapshot();
      expect(
        currentMonitorPoint(
          state.latest!.humanPowerW,
          true,
          state.monotonicAt,
          6,
          state.liveAcquiredAt.humanPowerW,
          state.mapMonotonicSeconds,
        ),
      ).toBeNull();
      expect(rows).toHaveLength(1);
      await recorder.stop();
    } finally {
      controller.dispose();
    }
  });

  it('uses the recorder monotonic clock for live source reads and controller freshness across a wall-clock jump', async () => {
    vi.useFakeTimers({ toFake: ['Date', 'performance', 'setTimeout', 'clearTimeout', 'setInterval', 'clearInterval'] });
    vi.setSystemTime(Date.UTC(2026, 0, 1));
    vi.stubGlobal('indexedDB', {});
    vi.stubGlobal('navigator', {
      locks: { request: (_name: string, _options: unknown, work: (lock: object) => Promise<void>) => work({}) },
    });
    const { store, record, rows } = fixture([]);
    vi.spyOn(store, 'recoverOrphan').mockResolvedValue(null);
    vi.spyOn(store, 'begin').mockResolvedValue(record);
    vi.spyOn(store, 'append').mockImplementation(async (_id, _token, batch, elapsed, timer, at) => {
      rows.push(...batch);
      return Object.assign(record, {
        elapsedSeconds: elapsed,
        timerSeconds: timer,
        checkpointAt: at,
        samples: rows.length,
        revision: record.revision! + 1,
      });
    });
    vi.spyOn(store, 'transition').mockImplementation(async (_id, _token, action, elapsed, timer, at) =>
      Object.assign(record, {
        elapsedSeconds: elapsed,
        timerSeconds: timer,
        ...(action === 'save' ? { endedAt: at } : {}),
        revision: record.revision! + 1,
      }),
    );
    class Source extends TestTelemetryAdapter {
      sample(sequence: number) {
        this.emitSample({
          ...syntheticSample(performance.now() / 1000, sequence, new Date().toISOString()),
          connectionEpoch: this.connectionEpoch,
        });
      }
    }
    const source = new Source(),
      recorder = new BrowserWorkoutRecorder(store);
    recorder.setTelemetrySource(source);
    await source.connect();
    await recorder.start({ indoor: true, useWatch: false });
    const live = browserWorkoutMonitorSource(record.id, true, store),
      controller = new MonitorReadController(live);
    try {
      await vi.advanceTimersByTimeAsync(1000);
      source.sample(0);
      await recorder.lap();
      await settle();
      controller.configure(['humanPowerW'], 'all');
      controller.refresh();
      await settle();
      vi.setSystemTime(Date.now() + 3_600_000);
      await vi.advanceTimersByTimeAsync(1000);
      source.sample(1);
      await recorder.lap();
      await settle();
      controller.refresh();
      await settle();
      const state = controller.getSnapshot(),
        point = state.latest!.humanPowerW!;
      expect(state.nowSeconds).toBe(2);
      expect(point.elapsedSeconds).toBe(2);
      expect(point.timestamp).toBe('2026-01-01T01:00:02.000Z');
      expect(currentMonitorPoint(point, true, state.monotonicAt, 6, state.liveAcquiredAt.humanPowerW)).toBe(point);
      await vi.advanceTimersByTimeAsync(1000);
      controller.tick();
      expect(controller.getSnapshot().nowSeconds).toBe(3);
      expect((await live.describeSource({ generation: 2 })).nowSeconds).toBe(3);
      expect(
        (await browserWorkoutMonitorSource(record.id, false, store).describeSource({ generation: 2 })).nowSeconds,
      ).toBeUndefined();
      await recorder.stop();
      await vi.advanceTimersByTimeAsync(1000);
      const stopped = await live.describeSource({ generation: 3 });
      expect(stopped.nowSeconds).toBeUndefined();
      expect(stopped).not.toHaveProperty('liveAcquiredAt');
      expect(await live.readLatest({ generation: 4, metrics: ['humanPowerW'] })).not.toHaveProperty('liveAcquiredAt');
    } finally {
      controller.dispose();
    }
  });
});
