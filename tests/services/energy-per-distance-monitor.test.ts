import { describe, expect, it, vi } from 'vitest';
import type { MonitorEnvelope, MonitorPlotRequest, MonitorPoint, MonitorSource } from '../../src/core/monitor';
import { isEnergyPerDistanceMetric, type EnergyPerDistanceResult } from '../../src/core/energy-per-distance';
import { withEnergyPerDistance, withEnergySmoothing } from '../../src/services/energy-per-distance-monitor';
import { MonitorReadController } from '../../src/features/monitor/monitor-read-controller';
import { TelemetryMonitor } from '../../src/core/monitor-data';
import { syntheticSample } from '../fixtures/synthetic-sample';
import { inspectionPlotBuckets } from '../../shared/js';

const settle = async () => {
  for (let i = 0; i < 20; i++) await Promise.resolve();
};
const envelope: MonitorEnvelope = { generation: 3, sourceId: 'fixture', revision: '7' };
const point: MonitorPoint = { elapsedSeconds: 60, timestamp: '2026-01-01T00:01:00Z', value: 12, derived: true };
const request = { generation: 3, expectedRevision: '7', metrics: ['batteryWhPerKm'], startSeconds: 0, endSeconds: 60 };
function setup(options: { preview?: boolean; now?: () => number } = {}) {
  const source: MonitorSource = {
    key: 'fixture',
    live: true,
    describeSource: vi.fn(async () => ({
      ...envelope,
      status: 'ok' as const,
      startedAt: '2026-01-01T00:00:00Z',
      availableMetrics: ['distanceMeters', 'humanPowerW'],
      domain: { start: 0, end: 62 },
      outcome: 'available' as const,
    })),
    readLatest: vi.fn(async () => ({
      ...envelope,
      status: 'ok' as const,
      nowSeconds: 62,
      points: {
        humanPowerW: { ...point, elapsedSeconds: 62, value: 180 },
        motorInputPowerW: { ...point, elapsedSeconds: 61, value: 1800 },
      },
      liveAcquiredAt: { humanPowerW: 1000, motorInputPowerW: 998 },
    })),
    readPlot: vi.fn(async () => ({ ...envelope, status: 'ok' as const, series: {} })),
    inspectAt: vi.fn(async () => ({ ...envelope, status: 'ok' as const, points: {}, gaps: {}, seconds: 60 })),
    rangeStats: vi.fn(async () => ({ ...envelope, status: 'ok' as const, statistics: {} })),
    changesSince: vi.fn(async () => ({
      ...envelope,
      status: 'ok' as const,
      resetRequired: false,
      changes: [{ startSeconds: 30, endSeconds: 32, metrics: ['humanPowerW'], kind: 'correction' as const }],
    })),
  };
  const value: EnergyPerDistanceResult = {
    averages: { batteryWhPerKm: { ...point, value: 10 }, riderWhPerKm: null },
    points: { batteryWhPerKm: point, riderWhPerKm: null },
    series: { batteryWhPerKm: [point], riderWhPerKm: [] },
    statistics: { batteryWhPerKm: { ratio: 10, coveredSeconds: 60 } },
    endpoints: { start: { batteryWhPerKm: point }, end: { batteryWhPerKm: point } },
  };
  const read = vi.fn(async (_request: import('../../src/core/monitor').MonitorPlotRequest) => ({
    ...envelope,
    status: 'ok' as const,
    ...value,
  }));
  return { source, read, monitor: withEnergyPerDistance(source, read, options) };
}

describe('energy per distance through the common monitor boundary', () => {
  it('advertises two metrics without duplicating the underlying source metrics', async () => {
    const { monitor } = setup();
    expect((await monitor.describeSource({ generation: 3 })).availableMetrics).toEqual([
      'distanceMeters',
      'humanPowerW',
      'batteryWhPerKm',
      'riderWhPerKm',
    ]);
  });
  it('keeps ordinary queries on the original path', async () => {
    const { monitor, read, source } = setup();
    const input = { ...request, metrics: ['humanPowerW'] };
    await monitor.readPlot(input);
    expect(source.readPlot).toHaveBeenCalledWith(input);
    expect(read).not.toHaveBeenCalled();
  });
  it('uses weighted range averages and removes derived anchors from native requests', async () => {
    const { monitor, source, read } = setup();
    const anchor = { metric: 'batteryWhPerKm', observationId: 'batteryWhPerKm:60000000' };
    const inspected = await monitor.inspectAt({ ...request, seconds: 60, anchor });
    expect(inspected.status === 'ok' && inspected.points.batteryWhPerKm).toEqual(point);
    expect(source.inspectAt).toHaveBeenCalledWith(expect.objectContaining({ metrics: [], anchor: undefined }));
    const stats = await monitor.rangeStats({
      ...request,
      startAnchor: anchor,
      endAnchor: anchor,
      includeEndpoints: true,
    });
    expect(stats.status === 'ok' && stats.statistics.batteryWhPerKm?.ratio).toBe(10);
    expect(stats.status === 'ok' && stats.endpoints?.end.batteryWhPerKm).toEqual(point);
    for (const [input] of vi.mocked(read).mock.calls as unknown as [Record<string, unknown>][]) {
      expect(input).not.toHaveProperty('anchor');
      expect(input).not.toHaveProperty('startAnchor');
      expect(input).not.toHaveProperty('endAnchor');
      expect(input).not.toHaveProperty('includeEndpoints');
    }
  });
  it.each([{ revision: '8' }, { sourceId: 'other' }])('retries instead of mixing snapshots: %j', async changed => {
    const { monitor, source } = setup();
    vi.mocked(source.readPlot).mockResolvedValueOnce({ ...envelope, ...changed, status: 'ok', series: {} });
    expect((await monitor.readPlot(request)).status).toBe('retry');
  });
  it('reads a saved ride without sending derived metric ids to the source description', async () => {
    const { monitor, source } = setup();
    vi.mocked(source.readLatest).mockResolvedValue({
      ...envelope,
      status: 'ok',
      points: { humanPowerW: { ...point, value: 180 }, motorInputPowerW: { ...point, value: 1800 } },
    });
    const describe = source.describeSource;
    source.describeSource = vi.fn(async (request: Parameters<MonitorSource['describeSource']>[0]) => {
      if ((request as { metrics?: string[] }).metrics?.some(isEnergyPerDistanceMetric))
        throw new Error('Invalid monitor request.');
      return describe(request);
    });
    const result = await monitor.readLatest({ generation: 3, metrics: ['batteryWhPerKm', 'humanPowerW'] });
    expect(result.status === 'ok' && result.points.humanPowerW?.value).toBe(180);
    expect(result.status === 'ok' && result.points.batteryWhPerKm?.value).toBe(12);
  });
  it('reads the whole-ride average independently of the current point or viewport', async () => {
    const { monitor, source, read } = setup();
    const first = await monitor.readLatest(request);
    expect(first.status === 'ok' && first.averages?.batteryWhPerKm).toBeNull();
    expect(first.status === 'ok' && first.averagesPending).toBe(true);
    expect(first.status === 'ok' && first.points.batteryWhPerKm?.value).toBe(12);
    await settle();
    for (let i = 0; i < 2; i++) {
      const result = await monitor.readLatest(request);
      expect(result.status === 'ok' && result.averages?.batteryWhPerKm?.value).toBe(10);
      expect(result.status === 'ok' && result.averagesPending).toBeUndefined();
      expect(result.status === 'ok' && result.points.batteryWhPerKm?.value).toBe(12);
      expect(result.status === 'ok' && result.liveAcquiredAt?.batteryWhPerKm).toBe(997);
    }
    expect(source.readLatest).toHaveBeenCalledWith(
      expect.objectContaining({ metrics: ['humanPowerW', 'motorInputPowerW'] }),
    );
    expect(read).toHaveBeenCalledWith(expect.objectContaining({ startSeconds: 0, endSeconds: 62 }));
  });
  it('invalidates the dependent GPS hold and smoothing window when earlier observations change', async () => {
    const { monitor } = setup();
    const result = await monitor.changesSince({ generation: 3, sinceRevision: '6' });
    expect(result.changes).toEqual([
      {
        startSeconds: 30,
        endSeconds: 102,
        metrics: ['humanPowerW', 'batteryWhPerKm', 'riderWhPerKm'],
        kind: 'correction',
      },
    ]);
  });
});

it('switches plot and inspection smoothing together without changing top averages', async () => {
  const { monitor, read } = setup();
  const smooth = withEnergySmoothing(monitor, 5);
  expect(smooth.semanticKey).not.toBe(withEnergySmoothing(monitor, 0).semanticKey);
  await smooth.readPlot(request);
  await smooth.inspectAt({ ...request, seconds: 60 });
  await smooth.rangeStats(request);
  expect(read.mock.calls.slice(0, 3)).toEqual(Array(3).fill([expect.objectContaining({ energySmoothingSeconds: 5 })]));
  await smooth.readLatest(request);
  expect(read).toHaveBeenLastCalledWith(
    expect.objectContaining({ startSeconds: 0, energySmoothingSeconds: 0, energyTotals: true }),
  );
});

it('graphs preview from the first moving frame, without waiting for ride totals', async () => {
  const raw = new TelemetryMonitor('preview', true, [], () => 1);
  const monitor = withEnergyPerDistance(raw, input => raw.readEnergyPerDistance(input), { preview: true });
  const append = (seconds: number, speed: number) =>
    raw.append({
      ...syntheticSample(seconds, seconds),
      humanPowerW: 180,
      motorInputPowerW: 1800,
      controllerSpeedMps: speed,
    });
  append(1, 5);
  let description = await monitor.describeSource({ generation: 1 });
  expect(description.availableMetrics).toContain('batteryWhPerKm');
  const request = { generation: 1, metrics: ['batteryWhPerKm', 'riderWhPerKm'] };
  let result = await monitor.readPlot({
    ...request,
    expectedRevision: description.revision,
    startSeconds: 0,
    endSeconds: 1,
  });
  expect(result.status === 'ok' && result.series.batteryWhPerKm?.[0]?.value).toBeCloseTo(100);
  expect(result.status === 'ok' && result.series.riderWhPerKm?.[0]?.value).toBeCloseTo(10);
  append(2, 10);
  description = await monitor.describeSource({ generation: 1 });
  const common = { ...request, expectedRevision: description.revision, startSeconds: 0, endSeconds: 2 };
  for (const smooth of [0, 5, 30, 60] as const) {
    const source = withEnergySmoothing(monitor, smooth);
    const result = await source.rangeStats(common);
    expect(result.status === 'ok' && result.statistics.batteryWhPerKm?.ratio).toBeUndefined();
    expect(result.status === 'ok' && result.statistics.batteryWhPerKm?.count).toBe(2);
    const latest = await source.readLatest(request);
    expect(latest.status === 'ok' && latest.averages?.batteryWhPerKm).toBeNull();
  }
  append(3, 0);
  description = await monitor.describeSource({ generation: 1 });
  result = await monitor.readPlot({
    ...request,
    expectedRevision: description.revision,
    startSeconds: 3,
    endSeconds: 3,
  });
  expect(result.status === 'ok' && result.series.batteryWhPerKm?.some(point => point.elapsedSeconds === 3)).toBe(false);
  const atRest = await monitor.inspectAt({ ...request, expectedRevision: description.revision, seconds: 3 });
  expect(atRest.status === 'ok' && atRest.points.batteryWhPerKm).toBeNull();
  expect((await monitor.readPlot({ ...common, expectedRevision: '1' })).status).toBe('retry');
});

it.each([
  [30, 2],
  [60, 5],
  [600, 15],
  [3600, 30],
  [28800, 30],
])(
  'refreshes the %s-second ride average every %s seconds while current readings remain fresh',
  async (elapsed, interval) => {
    let clock = 100,
      revision = 7;
    const { source, read, monitor } = setup({ now: () => clock });
    const latest = await source.readLatest(request);
    vi.mocked(source.readLatest).mockImplementation(async () => ({
      ...latest,
      revision: String(revision),
      nowSeconds: elapsed + clock - 100,
    }));
    const value = await read(request);
    read.mockClear();
    read.mockImplementation(async () => ({ ...value, revision: String(revision) }));
    const totals = () => read.mock.calls.filter(([input]) => input.energyTotals === true);
    await monitor.readLatest(request);
    expect(totals()).toHaveLength(1);
    await settle();
    revision++;
    clock += interval - 0.1;
    const held = await monitor.readLatest(request);
    expect(totals()).toHaveLength(1);
    expect(held.status === 'ok' && held.revision).toBe('8');
    expect(held.status === 'ok' && held.averagesRevision).toBe('7');
    expect(held.status === 'ok' && held.averagesPending).toBeUndefined();
    const currentRead = read.mock.calls.at(-1)![0];
    expect(currentRead.startSeconds).toBeCloseTo(elapsed + interval - 0.1);
    expect(currentRead.endSeconds).toBe(currentRead.startSeconds);
    expect(currentRead.energyTotals).toBe(false);
    clock = 100 + interval;
    const due = await monitor.readLatest(request);
    expect(totals()).toHaveLength(2);
    expect(due.status === 'ok' && due.averagesRevision).toBe('7');
    expect(due.status === 'ok' && due.averagesPending).toBe(true);
    await settle();
    const refreshed = await monitor.readLatest(request);
    expect(totals()).toHaveLength(2);
    expect(refreshed.status === 'ok' && refreshed.averagesRevision).toBe('8');
    // Neither a new display generation nor a smoothing choice invalidates unchanged totals.
    await withEnergySmoothing(monitor, 60).readLatest({ ...request, generation: 4 });
    expect(totals()).toHaveLength(2);
    revision++;
    clock += 0.1;
    vi.mocked(source.rangeStats).mockResolvedValue({
      ...envelope,
      revision: String(revision),
      status: 'ok',
      statistics: {},
    });
    expect((await monitor.rangeStats({ ...request, expectedRevision: String(revision) })).status).toBe('ok');
    expect(totals()).toHaveLength(3);
  },
);

it('keeps history exact and refreshes immediately when a source changes or its clock resets', async () => {
  const { source, monitor, read } = setup({ now: () => 100 });
  const latest = await source.readLatest(request);
  const value = await read(request);
  read.mockClear();
  let current = { ...envelope, nowSeconds: 3600 };
  vi.mocked(source.readLatest).mockImplementation(async () => ({ ...latest, ...current }));
  read.mockImplementation(async () => ({ ...value, ...current }));
  const totals = () => read.mock.calls.filter(([input]) => input.energyTotals === true);
  await monitor.readLatest(request);
  source.live = false;
  current = { ...current, revision: '8' };
  await monitor.readLatest(request);
  expect(totals()).toHaveLength(2);
  await monitor.readLatest(request);
  expect(totals()).toHaveLength(2);
  source.live = true;
  current = { ...current, sourceId: 'another' };
  await monitor.readLatest(request);
  expect(totals()).toHaveLength(3);
  current = { ...current, nowSeconds: 0 };
  await monitor.readLatest(request);
  expect(totals()).toHaveLength(4);
});

it('never scans preview history for ride averages', async () => {
  const { monitor, read } = setup({ preview: true });
  const result = await monitor.readLatest(request);
  expect(read).toHaveBeenCalledTimes(1);
  expect(read).toHaveBeenCalledWith(expect.objectContaining({ startSeconds: 62, endSeconds: 62, energyTotals: false }));
  expect(result.status === 'ok' && result.averages?.batteryWhPerKm).toBeNull();
});

it('does not cache a mismatched average and reads it again on the next due request', async () => {
  const { monitor, read } = setup();
  const value = await read(request);
  read.mockClear();
  read.mockResolvedValueOnce(value).mockResolvedValueOnce({ ...value, revision: '8' });
  const first = await monitor.readLatest(request);
  expect(first.status).toBe('ok');
  expect(first.status === 'ok' && first.averagesPending).toBe(true);
  await settle();
  const second = await monitor.readLatest(request);
  expect(second.status === 'ok' && second.averages?.batteryWhPerKm).toBeNull();
  expect(second.status === 'ok' && second.averagesPending).toBe(true);
  await settle();
  const third = await monitor.readLatest(request);
  expect(third.status === 'ok' && third.averages?.batteryWhPerKm?.value).toBe(10);
  expect(read.mock.calls.filter(([input]) => input.energyTotals)).toHaveLength(2);
});

it.each(['source change', 'clock reset'] as const)('drops a pending whole-ride average after a %s', async change => {
  const { source, monitor, read } = setup({ now: () => 100 });
  const latest = await source.readLatest(request);
  const value = await read(request);
  read.mockClear();
  let current = { ...envelope, nowSeconds: 3600 };
  vi.mocked(source.readLatest).mockImplementation(async () => ({ ...latest, ...current }));
  const releases: (() => void)[] = [];
  read.mockImplementation(async input => {
    const result = {
      ...value,
      ...current,
      averages: { batteryWhPerKm: { ...point, value: current.nowSeconds === 3600 ? 10 : 20 }, riderWhPerKm: null },
    };
    if (input.energyTotals) await new Promise<void>(resolve => releases.push(resolve));
    return result;
  });
  const totals = () => read.mock.calls.filter(([input]) => input.energyTotals === true);
  try {
    await monitor.readLatest(request);
    expect(totals()).toHaveLength(1);
    current =
      change === 'source change'
        ? { ...current, sourceId: 'another', nowSeconds: 3700 }
        : { ...current, nowSeconds: 5 };
    const replaced = await monitor.readLatest(request);
    expect(replaced.status === 'ok' && replaced.averages?.batteryWhPerKm).toBeNull();
    expect(replaced.status === 'ok' && replaced.averagesPending).toBe(true);
    releases.shift()!();
    await settle();
    expect(totals()).toHaveLength(2);
    const waiting = await monitor.readLatest(request);
    expect(waiting.status === 'ok' && waiting.averages?.batteryWhPerKm).toBeNull();
    releases.shift()!();
    await settle();
    const refreshed = await monitor.readLatest(request);
    expect(refreshed.status === 'ok' && refreshed.averages?.batteryWhPerKm?.value).toBe(20);
    expect(refreshed.status === 'ok' && refreshed.averagesRevision).toBe('7');
    expect(totals()).toHaveLength(2);
  } finally {
    releases.splice(0).forEach(resolve => resolve());
    await settle();
  }
});

describe('whole-ride averages outside the shared fast lane', () => {
  const ride = (t: number) => ({
    ...syntheticSample(t, t, new Date(Date.UTC(2026, 0, 1) + t * 1000).toISOString()),
    motorInputPowerW: 1800,
    controllerSpeedMps: 5,
  });
  function heldTotals(live: boolean) {
    const raw = new TelemetryMonitor(
      live ? 'workout:held-live' : 'workout:held-history',
      live,
      Array.from({ length: 61 }, (_, t) => ride(t)),
    );
    let release!: () => void;
    const gate = new Promise<void>(resolve => {
      release = resolve;
    });
    const totals: MonitorPlotRequest[] = [];
    const monitor = withEnergyPerDistance(raw, async input => {
      if (!input.energyTotals) return raw.readEnergyPerDistance(input);
      totals.push(input);
      await gate;
      return {
        generation: input.generation,
        sourceId: raw.key,
        revision: input.expectedRevision,
        status: 'ok' as const,
        averages: { batteryWhPerKm: { ...point, value: 42 }, riderWhPerKm: null },
        series: {},
        points: {},
        statistics: {},
        endpoints: { start: {}, end: {} },
      };
    });
    return { raw, monitor, totals, release };
  }

  it('publishes descriptions and current readings while a whole-ride total is still running', async () => {
    const { raw, monitor, totals, release } = heldTotals(true);
    const controller = new MonitorReadController(monitor);
    try {
      controller.configure(['batteryWhPerKm', 'humanPowerW'], 'all');
      controller.refresh();
      await settle();
      expect(totals).toHaveLength(1);
      expect(controller.getSnapshot().latestRevision).toBe('61');
      expect(controller.getSnapshot().averages?.batteryWhPerKm).toBeNull();
      raw.append(ride(61));
      controller.refresh();
      await settle();
      expect(controller.getSnapshot().description?.revision).toBe('62');
      expect(controller.getSnapshot().latestRevision).toBe('62');
      expect(controller.getSnapshot().latest?.humanPowerW?.elapsedSeconds).toBe(61);
      expect(totals).toHaveLength(1);
      release();
      await settle();
      controller.refresh();
      await settle();
      expect(controller.getSnapshot().averages?.batteryWhPerKm?.value).toBe(42);
    } finally {
      release();
      controller.dispose();
      await settle();
    }
  });

  it('shows a saved ride average once its separate total completes, without another refresh', async () => {
    vi.useFakeTimers();
    const { monitor, release } = heldTotals(false);
    const latest = vi.spyOn(monitor, 'readLatest');
    const controller = new MonitorReadController(monitor);
    try {
      controller.configure(['batteryWhPerKm'], 'all');
      controller.refresh();
      await settle();
      expect(controller.getSnapshot().averages?.batteryWhPerKm).toBeNull();
      await vi.advanceTimersByTimeAsync(2000);
      expect(controller.getSnapshot().averages?.batteryWhPerKm).toBeNull();
      release();
      await vi.advanceTimersByTimeAsync(600);
      expect(controller.getSnapshot().averages?.batteryWhPerKm?.value).toBe(42);
      const reads = latest.mock.calls.length;
      await vi.advanceTimersByTimeAsync(5000);
      expect(latest).toHaveBeenCalledTimes(reads);
    } finally {
      release();
      controller.dispose();
      vi.useRealTimers();
      await settle();
    }
  });
});

it('keeps every operation of a class source through the smoothing and energy wrappers', async () => {
  const raw = new TelemetryMonitor(
    'history:class',
    false,
    [0, 1, 2].map(t => syntheticSample(t, t)),
  );
  const sources = [
    withEnergySmoothing(raw, 5),
    withEnergySmoothing(
      withEnergyPerDistance(raw, input => raw.readEnergyPerDistance(input)),
      5,
    ),
  ];
  for (const source of sources) {
    const description = await source.describeSource({ generation: 1 });
    expect(description.status).toBe('ok');
    const changes = await source.changesSince({ generation: 1, sinceRevision: description.revision });
    expect(changes.status).toBe('ok');
    expect(source.mapMonotonicSeconds?.(7)).toBe(raw.mapMonotonicSeconds(7));
  }
});

it('gives the Wh/km chart the per-metric bucket budget of the plot request it rides with', async () => {
  const { monitor, read } = setup();
  const raw = Array.from({ length: 23 }, (_, index) => `metric${index}`);
  await monitor.readPlot({ ...request, metrics: [...raw, 'batteryWhPerKm', 'riderWhPerKm'], buckets: 400 });
  expect(read.mock.calls[0]![0].buckets).toBe(inspectionPlotBuckets(400, 23));
  expect(read.mock.calls[0]![0].buckets).toBe(176);
  read.mockClear();
  await monitor.readPlot({ ...request, metrics: ['batteryWhPerKm'], buckets: 400 });
  expect(read.mock.calls[0]![0].buckets).toBe(400);
});
