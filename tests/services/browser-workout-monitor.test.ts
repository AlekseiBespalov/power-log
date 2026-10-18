import { isConsumptionMetric } from '../../src/core/consumption';
import { isDistanceMetric } from '../../src/core/distance';
import { InspectionPlotBucket, InspectionMetricPolicy, InspectionPlotQuery } from '../../shared/js';
import { presentFailure } from '../../src/core/failures';
import { browserInspectionItem, browserStatistics } from '../../src/services/browser-analytics';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { currentMonitorPoint, MONITOR_METRICS } from '../../src/core/monitor';
import {
  BrowserRideStore,
  BROWSER_PAGE,
  TILE_SECONDS,
  type BrowserRide,
  type RideRow,
  type RideLocationRow,
  type RideTile,
} from '../../src/services/browser-ride-store';
import { browserWorkoutMonitorSource, registerBrowserWorkoutClock } from '../../src/services/browser-workout-monitor';
import { BrowserWorkoutRecorder } from '../../src/services/browser-workout-recorder';
import type { AdapterEvents } from '../../src/services/adapter';
import { TestTelemetryAdapter } from '../support/telemetry-adapter';
import { ensureBrowserDistance, browserDistanceCurrent } from '../../src/services/browser-distance-store';
import { MonitorReadController } from '../../src/features/monitor/monitor-read-controller';
import { syntheticSample } from '../fixtures/synthetic-sample';
import { secondsUs } from '../../src/services/browser-storage-keys';
import { browserMemoryStore } from '../support/browser-memory-store';
import { browserRide, browserRow } from '../support/browser-ride';

vi.mock('../../src/services/browser-distance-store', async importOriginal => ({
  ...(await importOriginal<typeof import('../../src/services/browser-distance-store')>()),
  peekBrowserDistance: vi.fn(async () => undefined),
  ensureBrowserDistance: vi.fn(async () => {
    throw new Error('No distance provider in this fixture');
  }),
  browserDistanceCurrent: vi.fn(async () => true),
  browserConsumptionSummary: vi.fn(async () => ({
    source: 'controller',
    energyWh: null,
    chargeAh: null,
    partial: true,
  })),
  retainBrowserDistance: vi.fn(async () => async () => {}),
}));
afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  vi.useRealTimers();
});
const settle = async () => {
  for (let i = 0; i < 100; i++) await Promise.resolve();
};

it('does not scan originals for unavailable inspection metrics', async () => {
  const h = fixture([
    { time: 0, power: 100 },
    { time: 1, power: 100 },
  ]);
  h.record.availableMetrics = ['humanPowerW'];
  const description = await h.source.describeSource({ generation: 1 });
  const result = await h.source.inspectAt({
    generation: 1,
    expectedRevision: description.revision,
    seconds: 0.5,
    metrics: ['heartRateBpm'],
  });
  expect(h.store.inspectionGroups).not.toHaveBeenCalled();
  expect(result).toMatchObject({ status: 'ok', points: { heartRateBpm: null } });
});
it.each(['heartRateBpm', 'controllerSpeedMps', 'speedMps'])(
  'does not read tiles or originals for unavailable plot and statistics metric %s',
  async metric => {
    const { source, store, record } = fixture([
      { time: 0, power: 100 },
      { time: 1, power: 200 },
    ]);
    vi.mocked(store.tiles).mockResolvedValue([]);
    const locations = vi.spyOn(store, 'locationPage').mockResolvedValue([]);
    expect(record.availableMetrics).not.toContain(metric);
    const description = await source.describeSource({ generation: 1 });
    const request = {
      generation: 1,
      expectedRevision: description.revision,
      startSeconds: 0,
      endSeconds: 1,
      metrics: [metric],
    };
    const plot = await source.readPlot(request);
    const stats = await source.rangeStats({ ...request, includeEndpoints: true });
    const directStats = await browserStatistics(record, store, 0, 1, [metric]);
    const emptyStatistics =
      metric === 'controllerSpeedMps' ? { [metric]: { count: 0, coveredSeconds: 0, integral: 0 } } : {};
    expect(plot).toMatchObject({ status: 'ok', series: { [metric]: [] } });
    expect(stats).toMatchObject({
      status: 'ok',
      endpoints: { start: { [metric]: null }, end: { [metric]: null } },
    });
    if (stats.status !== 'ok') throw new Error('Unexpected retry');
    expect(stats.statistics).toEqual(emptyStatistics);
    expect(directStats).toEqual(emptyStatistics);
    expect(store.page).not.toHaveBeenCalled();
    expect(store.pageExact).not.toHaveBeenCalled();
    expect(locations).not.toHaveBeenCalled();
    expect(store.neighbor).not.toHaveBeenCalled();
    expect(store.locationNeighbor).not.toHaveBeenCalled();
    expect(store.tiles).not.toHaveBeenCalled();
    expect(store.inspectionGroups).not.toHaveBeenCalled();
  },
);
it('keeps available readings and cached response shapes when absent metrics share a request', async () => {
  const { source, store } = fixture([
    { time: 0, power: 100 },
    { time: 1, power: 200 },
  ]);
  vi.mocked(store.tiles).mockResolvedValue([]);
  const description = await source.describeSource({ generation: 1 });
  const request = {
    generation: 1,
    expectedRevision: description.revision,
    startSeconds: 0,
    endSeconds: 1,
    metrics: ['humanPowerW'],
  };
  const plot = await source.readPlot(request);
  const stats = await source.rangeStats(request);
  const mixed = { ...request, metrics: ['humanPowerW', 'controllerSpeedMps', 'heartRateBpm'] };
  const mixedPlot = await source.readPlot(mixed);
  const mixedStats = await source.rangeStats(mixed);
  if (plot.status !== 'ok' || stats.status !== 'ok' || mixedPlot.status !== 'ok' || mixedStats.status !== 'ok')
    throw new Error('Unexpected retry');
  expect(plot.series.humanPowerW?.map(point => point.value)).toEqual([100, 200]);
  expect(stats.statistics.humanPowerW).toMatchObject({ count: 2, sampleMean: 150 });
  expect(mixedPlot.series).toEqual({ ...plot.series, controllerSpeedMps: [], heartRateBpm: [] });
  expect(mixedStats.statistics).toEqual({
    ...stats.statistics,
    controllerSpeedMps: { count: 0, coveredSeconds: 0, integral: 0 },
  });
  expect(await source.readPlot(request)).toEqual(plot);
  expect(await source.rangeStats(request)).toEqual(stats);
});
it('bounds inspection prefetch by the shared metric gap', async () => {
  const h = fixture(Array.from({ length: 301 }, (_, time) => ({ time, power: 100 })));
  Object.assign(h.rows[0]!, {
    controllerSpeedMps: 10,
    speedRaw: 36,
    controllerModel: 'X6',
    controllerProtocol: '5.3',
    firmwareLabel: '20260101',
  });
  h.record.availableMetrics = ['controllerSpeedMps'];
  const seconds = 150;
  const description = await h.source.describeSource({ generation: 1 });
  const result = await h.source.inspectAt({
    generation: 1,
    expectedRevision: description.revision,
    seconds,
    metrics: ['controllerSpeedMps'],
  });
  expect(result).toMatchObject({ status: 'ok', points: { controllerSpeedMps: null } });
  const pages = await Promise.all(
    [...vi.mocked(h.store.page).mock.results, ...vi.mocked(h.store.pageExact).mock.results].map(
      result => result.value as Promise<RideRow[]>,
    ),
  );
  expect(pages).toHaveLength(1);
  expect(h.store.pageExact).toHaveBeenCalledWith(
    h.record.id,
    '149000000',
    '156000000',
    null,
    BROWSER_PAGE,
    h.record.revision,
  );
  const target = BigInt(secondsUs(seconds));
  const gap = BigInt(new InspectionMetricPolicy('controllerSpeedMps').plotGapUs);
  expect(pages[0]!.length).toBeGreaterThan(0);
  expect(
    pages.flat().every(row => BigInt(row.elapsedUs) >= target - gap && BigInt(row.elapsedUs) <= target + gap),
  ).toBe(true);
});

it('preserves invalid readings and tied anchors when inspection reuses its bounded prefetch', async () => {
  const h = fixture([148, 149, 150, 150, 150, 151, 152, 153].map(time => ({ time, power: 100 })));
  const speeds = [10, 11, 12, undefined, 20, undefined, 14, 15];
  h.rows.forEach((row, index) => {
    const speed = speeds[index];
    if (speed !== undefined)
      Object.assign(row, {
        controllerSpeedMps: speed,
        speedRaw: speed * 3.6,
        controllerModel: 'X6',
        controllerProtocol: '5.3',
        firmwareLabel: '20260101',
      });
  });
  h.record.availableMetrics = ['controllerSpeedMps'];
  const description = await h.source.describeSource({ generation: 1 });
  const request = { generation: 1, expectedRevision: description.revision, metrics: ['controllerSpeedMps'] };
  await h.source.inspectAt({ ...request, seconds: 149.25 });
  for (const target of [
    { seconds: 150 },
    { seconds: 150, anchor: { metric: 'controllerSpeedMps', observationId: `${h.record.id}:4` } },
    { seconds: 151 },
  ]) {
    vi.mocked(h.store.inspectionGroups).mockClear();
    const cached = await h.source.inspectAt({ ...request, ...target });
    expect(h.store.inspectionGroups).not.toHaveBeenCalled();
    const fresh = await browserWorkoutMonitorSource(h.record.id, false, h.store).inspectAt({ ...request, ...target });
    expect(cached).toEqual(fresh);
    if (target.anchor)
      expect(cached).toMatchObject({
        points: { controllerSpeedMps: { value: 20, observationId: target.anchor.observationId } },
      });
  }
});

it('presents a core chart-capacity outcome as a smaller-range action', async () => {
  const capacity = new InspectionPlotQuery('humanPowerW', '0', '100000000', 1, 4);
  let failure: unknown;
  try {
    for (let i = 0; i < 8; i++)
      capacity.append(browserInspectionItem({ ...browserRow(i * 10, i), recordingId: 'capacity' }), true, false);
    capacity.result();
  } catch (error) {
    failure = error;
  }
  expect(failure).toMatchObject({ name: 'InspectionCapacityException' });
  const h = fixture([
    { time: 0, power: 100 },
    { time: 1, power: 100 },
  ]);
  vi.spyOn(InspectionPlotQuery.prototype, 'result').mockImplementationOnce(() => {
    throw failure;
  });
  const description = await h.source.describeSource({ generation: 1 });
  const error = await h.source
    .readPlot({ generation: 1, expectedRevision: description.revision, metrics: ['humanPowerW'] })
    .catch(error => error);
  expect(presentFailure(error).text).toBe('Choose a smaller range with fewer chart discontinuities.');
});

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
    telemetryCount: rows.length,
    availableMetrics: MONITOR_METRICS.filter(metric => rows.some(row => Object.hasOwn(row, metric.id))).map(
      metric => metric.id,
    ),
    elapsedSeconds: rows.at(-1)?.elapsedSeconds ?? 0,
  });
  vi.spyOn(store, 'openRead').mockImplementation(id => store.get(id));
  vi.spyOn(store, 'checkRead').mockResolvedValue();
  vi.spyOn(store, 'inspectionGroups').mockImplementation(async (_id, _seconds, _at, locations) =>
    locations ? [] : rows,
  );
  vi.spyOn(store, 'locationNeighbor').mockResolvedValue(undefined);
  const lifecycle: import('../../src/services/browser-ride-store').RideLifecycleRow[] = [];
  let active = true;
  for (const [index, row] of rows.entries()) {
    const previous = rows[index - 1];
    if (previous && row.interruptionIndex > previous.interruptionIndex) {
      lifecycle.push({
        recordingId: id,
        eventId: `cutoff-${index}`,
        producer: 'browser',
        producerSequence: String(index * 2 + 1),
        elapsedUs: previous.elapsedUs,
        queryUs: previous.queryUs,
        revision: '1',
        timestamp: previous.timestamp,
        elapsedSeconds: previous.elapsedSeconds,
        timerSeconds: 0,
        action: 'pause',
        interrupted: true,
        interruption: {
          controllerSequence: previous.producerSequence,
          cutoffUs: previous.elapsedUs,
          producer: previous.producer,
          producerSequence: previous.producerSequence,
          eventId: previous.eventId,
        },
      });
      lifecycle.push({
        recordingId: id,
        eventId: `resume-${index}`,
        producer: 'browser',
        producerSequence: String(index * 2 + 2),
        elapsedUs: row.elapsedUs,
        queryUs: row.queryUs,
        revision: '1',
        timestamp: row.timestamp,
        elapsedSeconds: row.elapsedSeconds,
        timerSeconds: 0,
        action: 'resume',
        interrupted: false,
      });
    }
    if (row.active === active) continue;
    active = row.active;
    lifecycle.push({
      recordingId: id,
      eventId: `lifecycle-${index}`,
      producer: 'browser',
      producerSequence: String(index + 1),
      elapsedUs: row.elapsedUs,
      queryUs: row.queryUs,
      revision: '1',
      timestamp: row.timestamp,
      elapsedSeconds: row.elapsedSeconds,
      timerSeconds: 0,
      action: active ? 'resume' : 'pause',
      interrupted: false,
    });
  }
  vi.spyOn(store, 'analysisPage').mockImplementation(async (name, _id, after) => {
    if (name === 'ride-lifecycle') return after ? [] : [...lifecycle];
    if (name !== 'samples') return [];
    return rows
      .filter(
        row =>
          !after ||
          BigInt(row.elapsedUs) > BigInt(after[0]) ||
          (row.elapsedUs === after[0] && BigInt(row.producerSequence) > BigInt(after[2])),
      )
      .slice(0, BROWSER_PAGE);
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
  vi.spyOn(store, 'pageExact').mockImplementation(async (_id, start, end, after) =>
    rows
      .filter(
        row =>
          BigInt(row.elapsedUs) >= BigInt(start) &&
          BigInt(row.elapsedUs) <= BigInt(end) &&
          (!after ||
            BigInt(row.elapsedUs) > BigInt(after[0]) ||
            (row.elapsedUs === after[0] && BigInt(row.producerSequence) > BigInt(after[2]))),
      )
      .slice(0, BROWSER_PAGE),
  );
  vi.spyOn(store, 'neighbor').mockImplementation(async (_id, time, direction) =>
    direction === 'prev'
      ? rows.filter(row => row.elapsedSeconds <= time).at(-1)
      : rows.find(row => row.elapsedSeconds >= time),
  );
  vi.spyOn(store, 'tiles').mockImplementation(async (_id, level, first, last) => {
    const tiles: RideTile[] = [];
    const size = TILE_SECONDS[level]!;
    for (let bucket = first; bucket <= last; bucket++) {
      const raw = rows.filter(row => row.elapsedSeconds >= bucket * size && row.elapsedSeconds < (bucket + 1) * size);
      if (!raw.length) continue;
      const stats: RideTile['stats'] = {};
      for (const metric of MONITOR_METRICS) {
        if (
          isDistanceMetric(metric.id) ||
          isConsumptionMetric(metric.id) ||
          new InspectionMetricPolicy(metric.id).channel !== 'telemetry'
        )
          continue;
        const reducer = new InspectionPlotBucket(metric.id, secondsUs(bucket * size), secondsUs((bucket + 1) * size));
        raw.map(browserInspectionItem).forEach(item => reducer.append(item, true));
        stats[metric.id] = { plot: reducer.aggregate().payload };
      }
      tiles.push({ recordingId: id, level, bucket, stats });
    }
    return tiles;
  });

  return { store, record, rows, source: browserWorkoutMonitorSource(id, false, store) };
}

describe('browser monitor projections', () => {
  it('shares pinned boundary pages across metric statistics', async () => {
    const { store, record, source } = fixture(
      Array.from({ length: 32 }, (_, index) => ({ time: index / 8, power: index + 10, cadence: index + 60 })),
    );
    const description = await source.describeSource({ generation: 1 });
    const result = await source.rangeStats({
      generation: 1,
      expectedRevision: description.revision,
      metrics: ['humanPowerW', 'cadenceRpm'],
      startSeconds: 0,
      endSeconds: record.elapsedSeconds,
    });
    expect(result).toMatchObject({
      status: 'ok',
      statistics: { humanPowerW: { count: 32 }, cadenceRpm: { count: 32 } },
    });
    const pages = await Promise.all(
      vi.mocked(store.page).mock.results.map(result => result.value as Promise<RideRow[]>),
    );
    expect(pages.reduce((count, rows) => count + rows.length, 0)).toBeLessThanOrEqual(32);
  });

  it('rebuilds plots from pinned originals when all tile payloads are missing', async () => {
    const { store, record, source } = fixture([
      { time: 0, power: 10 },
      { time: 1, power: 20 },
      { time: 2, power: 30 },
    ]);
    vi.mocked(store.tiles).mockResolvedValue([]);
    const description = await source.describeSource({ generation: 1 });
    const result = await source.readPlot({
      generation: 1,
      expectedRevision: description.revision,
      metrics: ['humanPowerW'],
      startSeconds: 0,
      endSeconds: 2,
      pixelWidth: 4,
    });
    if (result.status !== 'ok') throw new Error('Unexpected retry');
    expect(result.series.humanPowerW?.map(point => [point.elapsedSeconds, point.value])).toEqual([
      [0, 10],
      [1, 20],
      [2, 30],
    ]);
    expect(vi.mocked(store.page).mock.calls.every(call => call[5] === record.revision)).toBe(true);
  });

  it('retains historical GPS but clears its live freshness evidence on recovery', async () => {
    const { store, record } = fixture([]);
    const location = {
      recordingId: record.id,
      eventId: '00000000-0000-4000-8000-000000000002',
      elapsedUs: '1000000',
      queryUs: '1767225601000000',
      revision: '1',
      originalTimestamp: '2026-01-01T00:00:01.000Z',
      originalElapsedSeconds: 1,
      clockEpoch: 'original-gps',
      interval: 0,
      originalAltitudeDatum: 'unknown',
      producer: 'browser',
      producerSequence: '2',
      elapsedSeconds: 1,
      timestamp: '2026-01-01T00:00:01.000Z',
      speedMps: 2,
      acquiredAtSeconds: 1001,
      latitude: 0,
      longitude: 0,
      active: true,
      distanceBarrier: false,
    } as RideLocationRow;
    vi.mocked(store.locationNeighbor).mockResolvedValue(location);
    record.locationCount = 1;
    const release = registerBrowserWorkoutClock(record.id, 0, () => ({ nowSeconds: 2, monotonicAt: 2 }), store, 0, '3');
    try {
      const source = browserWorkoutMonitorSource(record.id, true, store);
      const old = await source.readLatest({ generation: 1, metrics: ['speedMps'] });
      expect(old).toMatchObject({
        points: { speedMps: { observationId: `${record.id}:00000000-0000-4000-8000-000000000002`, value: 2 } },
        liveAcquiredAt: { speedMps: null },
      });
      location.producerSequence = '3';
      location.acquiredAtSeconds = 1.5;
      expect(await source.readLatest({ generation: 2, metrics: ['speedMps'] })).toMatchObject({
        liveAcquiredAt: { speedMps: 1.5 },
      });
    } finally {
      release();
    }
  });
  it('streams GPS chart geometry and inspects the original independently of reduction', async () => {
    const { store, record, source } = fixture([]);
    const rows: RideLocationRow[] = Array.from({ length: 1000 }, (_, index) => {
      const elapsed = index / 100;
      return {
        recordingId: record.id,
        eventId: crypto.randomUUID(),
        producer: 'browser',
        producerSequence: String(index + 2),
        elapsedUs: secondsUs(elapsed),
        queryUs: '1767225600000000',
        timestamp: '2026-01-01T00:00:00Z',
        originalTimestamp: '2026-01-01T00:00:00Z',
        originalElapsedSeconds: elapsed,
        elapsedSeconds: elapsed,
        acquiredAtSeconds: elapsed,
        clockEpoch: 'clock',
        active: true,
        interval: 0,
        distanceBarrier: index === 500,
        latitude: -1,
        longitude: -1,
        horizontalAccuracyM: 5,
        speedMps: index === 523 ? 99 : 2,
        originalAltitudeDatum: 'wgs84Ellipsoid',
      };
    });
    record.locationCount = rows.length;
    record.availableMetrics = ['speedMps'];
    record.elapsedSeconds = 10;
    vi.spyOn(store, 'locationPage').mockImplementation(async (_id, start, end, after) =>
      rows
        .filter(
          row =>
            BigInt(row.elapsedUs) >= BigInt(start) &&
            BigInt(row.elapsedUs) <= BigInt(end) &&
            (!after || BigInt(row.producerSequence) > BigInt(after[2])),
        )
        .slice(0, BROWSER_PAGE),
    );
    vi.mocked(store.analysisPage).mockImplementation(async (name, _id, after) =>
      name === 'ride-locations'
        ? rows.filter(row => !after || BigInt(row.producerSequence) > BigInt(after[2])).slice(0, BROWSER_PAGE)
        : [],
    );
    vi.mocked(store.locationNeighbor).mockImplementation(async (_id, seconds, direction) =>
      direction === 'prev'
        ? rows.filter(row => row.elapsedSeconds <= seconds).at(-1)
        : rows.find(row => row.elapsedSeconds >= seconds),
    );
    const description = await source.describeSource({ generation: 1 });
    const query = {
      generation: 1,
      expectedRevision: description.revision,
      metrics: ['speedMps'],
      startSeconds: 0,
      endSeconds: 10,
      pixelWidth: 4,
    };
    const result = await source.readPlot(query);
    if (result.status !== 'ok') throw new Error('Unexpected retry');
    expect(result.series.speedMps!.length).toBeLessThan(40);
    expect(result.series.speedMps!.filter(point => point.startsSegment).map(point => point.elapsedSeconds)).toEqual([
      0,
    ]);
    expect(result.series.speedMps).toContainEqual(
      expect.objectContaining({ observationId: `${record.id}:${rows[523]!.eventId}`, elapsedSeconds: 5.23, value: 99 }),
    );
    vi.spyOn(store, 'inspectionGroups').mockResolvedValue(rows);
    const inspected = await source.inspectAt({ ...query, seconds: 5.23 });
    expect(inspected).toMatchObject({
      points: { speedMps: { observationId: `${record.id}:${rows[523]!.eventId}`, elapsedSeconds: 5.23, value: 99 } },
    });
    const stats = await source.rangeStats(query);
    expect(stats).toMatchObject({ statistics: { speedMps: { count: 1000, max: { value: 99 } } } });
    vi.mocked(store.locationPage).mockClear();
    vi.mocked(store.analysisPage).mockClear();
    const narrow = { ...query, startSeconds: 5.015, endSeconds: 5.205 };
    const narrowStats = await source.rangeStats(narrow);
    const narrowPlot = await source.readPlot(narrow);
    expect(narrowStats).toMatchObject({ statistics: { speedMps: { count: 19, max: { value: 2 } } } });
    expect(narrowPlot).toMatchObject({ status: 'ok' });
    expect(vi.mocked(store.locationPage).mock.calls).toHaveLength(2);
    expect(vi.mocked(store.locationPage).mock.calls.every(call => call[1] === '5015000' && call[2] === '5205000')).toBe(
      true,
    );
    expect(vi.mocked(store.analysisPage).mock.calls.every(call => call[0] !== 'ride-locations')).toBe(true);
  });
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
    registerBrowserWorkoutClock(record.id, 100, () => ({ nowSeconds: 2, monotonicAt: 102 }), store);
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
      revision: (BigInt(record.revision) + 1n).toString(),
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
        source: 'controller',
        generationKey: '09223372036854775809',
        revisionKey: '09223372036854775809',
        inputSequence: record.telemetryCount - 1,
        intervalEndUs: '0',
        prefixes: [],
        state: { intervals: 0 },
        building: false,
        recordRevision: String(record.revision),
        coveredUs: '0',
        distanceQ: '0',
        timerUs: '0',
        telemetryCount: record.telemetryCount,
        locationCount: 0,
        lifecycleCount: 0,
      },
      info: { selection: 'auto', selected: null, available: [] },
      profiles: [],
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
      for (const metric of query.metrics) {
        if (metric === 'assistLevel') {
          expect(result.statistics[metric]).not.toHaveProperty('coveredSeconds');
          expect(result.statistics[metric]).not.toHaveProperty('integral');
        } else expect(result.statistics[metric]?.coveredSeconds, metric).toBe(covered);
      }
    }
    const empty = await source.rangeStats({ ...query, startSeconds: 3, endSeconds: 5 });
    if (empty.status !== 'ok') throw new Error('Unexpected retry');
    for (const metric of query.metrics) expect(empty.statistics[metric]).toMatchObject({ count: 0 });
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
    const retained = [0, 1, 2, 3, 4, 5];
    expect(result.series.humanPowerW?.map(point => point.elapsedSeconds)).toEqual(retained.map(i => times[i]));
    expect(result.series.humanPowerW?.map(point => point.value)).toEqual(retained.map(i => powers[i]));
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
    const memory = browserMemoryStore(store, record);
    const append = memory.append.getMockImplementation()!;
    vi.spyOn(store, 'append').mockImplementation(async (_id, _token, batch, elapsed, timer, at) => {
      await append(_id, _token, batch, elapsed, timer, at);
      expect(elapsed).toBe(batch.at(-1)!.elapsedSeconds);
      expect(timer).toBe(elapsed);
      expect(at).toBe(batch.at(-1)!.timestamp);
      rows.push(...batch);
      return Object.assign(record, {
        elapsedSeconds: elapsed,
        timerSeconds: timer,
        checkpointAt: at,
        telemetryCount: rows.length,
        revision: (BigInt(record.revision) + 1n).toString(),
      });
    });
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
      expect((await recorder.getState()).streams.controller.status).toBe('receiving');
      controller.configure(['humanPowerW'], 'all');
      controller.refresh();
      await settle();
      expect(controller.getSnapshot().liveAcquiredAt.humanPowerW).toBe(1);
      await vi.advanceTimersByTimeAsync(3999);
      expect((await recorder.getState()).streams.controller.status).toBe('receiving');
      await vi.advanceTimersByTimeAsync(1);
      expect((await recorder.getState()).streams.controller.status).toBe('stale');
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
    const memory = browserMemoryStore(store, record);
    const append = memory.append.getMockImplementation()!;
    vi.spyOn(store, 'append').mockImplementation(async (_id, _token, batch, elapsed, timer, at) => {
      await append(_id, _token, batch, elapsed, timer, at);
      rows.push(...batch);
      return Object.assign(record, {
        elapsedSeconds: elapsed,
        timerSeconds: timer,
        checkpointAt: at,
        telemetryCount: rows.length,
        revision: (BigInt(record.revision) + 1n).toString(),
      });
    });
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

it('inspects every original in an equal-time group before applying core identity order', async () => {
  const { source, record } = fixture([
    { time: 0, power: 100 },
    { time: 0.5, power: 100 },
    ...Array.from({ length: 11 }, (_, i) => ({ time: 1, power: i + 2 })),
  ]);
  const description = await source.describeSource({ generation: 1 });
  const result = await source.inspectAt({
    generation: 1,
    expectedRevision: description.revision,
    metrics: ['humanPowerW'],
    seconds: 1,
  });
  expect(result).toMatchObject({
    status: 'ok',
    points: { humanPowerW: { observationId: `${record.id}:10`, value: 10 } },
  });
});
