import { describe, expect, it, vi } from 'vitest';
import { MonitorReadController } from '../../src/features/monitor/monitor-read-controller';
import { TelemetryMonitor } from '../../src/core/monitor-data';
import { currentMonitorPoint, metricById, type MonitorSource, type MonitorPlotResult } from '../../src/core/monitor';
import { syntheticSample } from '../fixtures/synthetic-sample';
import { monitorRasterScene } from '../../src/core/monitor-raster';
import { groupMonitorMetrics, monitorScale, monitorSelection } from '../../src/core/monitor-chart';
import { reads } from '../../src/services/read-scheduler';
const sample = (seconds: number, value = seconds) => ({ ...syntheticSample(seconds, seconds, new Date(Date.UTC(2026, 0, 1) + seconds * 1000).toISOString()), humanPowerW: value });
const settle = async () => { for (let i = 0; i < 100; i++) await Promise.resolve(); };
function setup() {
  const source = new TelemetryMonitor('workout:stable', false, Array.from({ length: 31 }, (_, i) => sample(i)));
  const controller = new MonitorReadController(source);
  controller.configure(['humanPowerW'], 'all'); controller.refresh();
  return { source, controller };
}
describe('independent monitor reads', () => {
  it('never admits incomplete native metadata into the chart clock and recovers on the next refresh', async () => {
    const source = new TelemetryMonitor('native-metadata', true, [sample(0), sample(1)]);
    const describe = source.describeSource.bind(source);
    const descriptions = vi.spyOn(source, 'describeSource');
    const retry = async (request: Parameters<typeof describe>[0]) => {
      const value = await describe(request);
      return { generation: value.generation, sourceId: value.sourceId, revision: value.revision, status: 'retry' } as unknown as Awaited<ReturnType<typeof describe>>;
    };
    descriptions.mockImplementationOnce(retry);
    const controller = new MonitorReadController(source);
    try {
      controller.configure(['humanPowerW'], 'all'); controller.refresh(); await settle();
      expect(controller.getSnapshot().description).toBeUndefined();
      expect(controller.getSnapshot().deferred.source).toBe(true);
      expect(() => controller.tick()).not.toThrow();
      controller.refresh(); await settle();
      const valid = controller.getSnapshot().description;
      expect(valid?.domain).toEqual({ start: 0, end: 10 });
      descriptions.mockImplementationOnce(retry);
      controller.refresh(); await settle();
      expect(controller.getSnapshot().description).toBe(valid);
      expect(() => controller.tick()).not.toThrow();
      controller.refresh(); await settle();
      expect(controller.getSnapshot().deferred.source).toBe(false);
    } finally { controller.dispose(); }
  });

  it('retains the plot when a native change journal returns an incomplete retry envelope', async () => {
    const { controller, source } = setup(); await settle();
    const before = controller.getSnapshot().data!.series;
    const changes = source.changesSince.bind(source);
    vi.spyOn(source, 'changesSince').mockImplementationOnce(async request => {
      const value = await changes(request);
      return { generation: value.generation, sourceId: value.sourceId, revision: value.revision, status: 'retry' } as unknown as Awaited<ReturnType<typeof changes>>;
    });
    try {
      source.append(sample(31)); controller.refresh(); await settle();
      expect(controller.getSnapshot().deferred.changes).toBe(true);
      expect(controller.getSnapshot().data!.series).toBe(before);
      expect(() => controller.tick()).not.toThrow();
      controller.refresh(); await settle();
      expect(controller.getSnapshot().deferred.changes).toBe(false);
      expect(controller.getSnapshot().data?.revision).toBe(controller.getSnapshot().description?.revision);
    } finally { controller.dispose(); }
  });

  it('retains chart data and viewport identities when only publication bookkeeping changes', async () => {
    const { controller } = setup(); await settle();
    const { data, viewport } = controller.getSnapshot();
    for (let i = 0; i < 20; i++) controller.tick();
    expect(controller.getSnapshot().data).toBe(data);
    expect(controller.getSnapshot().viewport).toBe(viewport);
    controller.dispose();
  });
  it('bounds catch-up under repeatedly invalidated snapshots and stops it while hidden', async () => {
    vi.useFakeTimers();
    const source = new TelemetryMonitor('retrying', true, [sample(0)]);
    const original = source.readPlot.bind(source);
    const plot = vi.spyOn(source, 'readPlot').mockImplementation(async request => ({ ...await original(request), status: 'retry' }));
    const controller = new MonitorReadController(source);
    try {
      controller.configure(['humanPowerW'], 'all'); controller.refresh();
      await vi.advanceTimersByTimeAsync(5000);
      expect(plot.mock.calls.length).toBeGreaterThan(1); expect(plot.mock.calls.length).toBeLessThanOrEqual(12);
      controller.deactivate(); const calls = plot.mock.calls.length;
      await vi.advanceTimersByTimeAsync(5000); expect(plot).toHaveBeenCalledTimes(calls);
      expect(vi.getTimerCount()).toBe(0);
    } finally { controller.dispose(); vi.useRealTimers(); }
  });
  it.each(['plot first', 'readouts first'])('keeps source captions and exact/range readouts coherent when %s completes', async order => {
    vi.useFakeTimers();
    let releasePlot!: () => void, releaseReadouts!: () => void;
    const plotGate = new Promise<void>(resolve => { releasePlot = resolve; }), readoutGate = new Promise<void>(resolve => { releaseReadouts = resolve; });
    function source(selection: string, value: number, delayed = false): MonitorSource {
      const revision = selection === 'auto' ? '7' : `distance:v1:${selection}:7`;
      const info = { source: selection === 'auto' ? 'gps:watch' : selection, label: selection === 'auto' ? 'GPS · Watch' : 'Controller estimate' };
      const point = (seconds: number) => ({ value: value + seconds, elapsedSeconds: seconds, timestamp: new Date(Date.UTC(2026, 0, 1) + seconds * 1000).toISOString(), observationId: `${selection}:${seconds}` });
      const envelope = (generation: number) => ({ generation, sourceId: 'workout:policy-test', revision, status: 'ok' as const });
      return { key: 'workout:policy-test', live: false, semanticKey: `distance:v1:${selection}`,
        describeSource: async r => ({ ...envelope(r.generation), startedAt: '2026-01-01T00:00:00Z', domain: { start: 0, end: 2 }, availableMetrics: ['distanceMeters'], outcome: 'available', warnings: [], metricSources: { distanceMeters: info } }),
        readLatest: async r => ({ ...envelope(r.generation), points: { distanceMeters: point(1) }, metricSources: { distanceMeters: info } }),
        readPlot: async r => { if (delayed) await plotGate; return { ...envelope(r.generation), series: { distanceMeters: [point(0), point(1)] }, latest: { distanceMeters: point(1) }, metricSources: { distanceMeters: info } }; },
        inspectAt: async r => { if (delayed) await readoutGate; return { ...envelope(r.generation), seconds: r.seconds, points: { distanceMeters: point(r.seconds) }, gaps: {} }; },
        rangeStats: async r => { if (delayed) await readoutGate; return { ...envelope(r.generation), statistics: { distanceMeters: { distance: value, coveredSeconds: 1 } }, ...(r.includeEndpoints ? { endpoints: { start: { distanceMeters: point(r.startSeconds) }, end: { distanceMeters: point(r.endSeconds) } } } : {}) }; },
        changesSince: async r => ({ ...envelope(r.generation), resetRequired: true, changes: [{ startSeconds: 0, endSeconds: 2, kind: 'semantics' }] }) };
    }
    const controller = new MonitorReadController(source('auto', 100));
    try {
      controller.configure(['distanceMeters'], 'all'); controller.refresh(); await settle(); controller.inspect(1); await settle(); controller.setReference(0); await settle();
      const old = controller.getSnapshot().data!;
      expect(monitorSelection(old, metricById('distanceMeters')!, 1)?.value).toBe(101);
      controller.updateSource(source('controller', 200, true)); controller.refresh(); await settle();
      await vi.advanceTimersByTimeAsync(2100); await settle();
      if (order === 'plot first') releasePlot(); else releaseReadouts();
      await settle();
      const intermediate = controller.getSnapshot(), data = intermediate.data!;
      if (order === 'plot first') {
        expect(data.metricSources?.distanceMeters?.source).toBe('controller');
        expect(monitorSelection(data, metricById('distanceMeters')!, 1)).toBeNull();
        expect(monitorSelection(data, metricById('distanceMeters')!, 0, 'reference')).toBeNull();
        expect(data.statistics.distanceMeters).toBeUndefined(); expect(intermediate.intervalStatistics.distanceMeters).toBeUndefined();
      } else {
        expect(data).toBe(old); expect(data.metricSources?.distanceMeters?.source).toBe('gps:watch');
        expect(monitorSelection(data, metricById('distanceMeters')!, 1)?.value).toBe(101);
        expect(data.statistics.distanceMeters?.distance).toBe(100); expect(intermediate.intervalStatistics.distanceMeters?.distance).toBe(100);
      }
      releasePlot(); releaseReadouts(); await settle(); await vi.advanceTimersByTimeAsync(20); await settle();
      const final = controller.getSnapshot();
      expect(final.data?.metricSources?.distanceMeters?.source).toBe('controller');
      expect(monitorSelection(final.data!, metricById('distanceMeters')!, 1)?.value).toBe(201);
      expect(final.data?.statistics.distanceMeters?.distance).toBe(200); expect(final.intervalStatistics.distanceMeters?.distance).toBe(200);
    } finally { releasePlot(); releaseReadouts(); controller.dispose(); vi.useRealTimers(); }
  });

  it('does not admit an old plotted anchor while a replacement source description is pending', async () => {
    vi.useFakeTimers();
    let releaseDescription!: () => void, releaseInspect!: () => void;
    const descriptionGate = new Promise<void>(resolve => { releaseDescription = resolve; });
    const inspectGate = new Promise<void>(resolve => { releaseInspect = resolve; });
    const anchors: (string | undefined)[] = [];
    function source(selection: string, value: number): MonitorSource {
      const delayed = selection !== 'auto', revision = delayed ? `distance:v1:${selection}:7` : '7';
      const point = { value, elapsedSeconds: 1, timestamp: '2026-01-01T00:00:01Z', observationId: `${selection}:1` };
      const envelope = (generation: number) => ({ generation, sourceId: 'workout:pending-policy', revision, status: 'ok' as const });
      const metricSources = { distanceMeters: { source: delayed ? selection : 'gps:watch', label: delayed ? 'Controller estimate' : 'GPS · Watch' } };
      return { key: 'workout:pending-policy', live: false, semanticKey: `distance:v1:${selection}`,
        describeSource: async r => { if (delayed) await descriptionGate; return { ...envelope(r.generation), startedAt: '2026-01-01T00:00:00Z', domain: { start: 0, end: 2 }, availableMetrics: ['distanceMeters'], outcome: 'available', warnings: [], metricSources }; },
        readLatest: async r => ({ ...envelope(r.generation), points: { distanceMeters: point }, metricSources }),
        readPlot: async r => ({ ...envelope(r.generation), series: { distanceMeters: [point] }, latest: { distanceMeters: point }, metricSources }),
        inspectAt: async r => { if (delayed) anchors.push(r.anchor?.observationId); if (r.expectedRevision !== revision) return { ...envelope(r.generation), status: 'retry' }; if (delayed) await inspectGate; return { ...envelope(r.generation), seconds: r.seconds, points: { distanceMeters: point }, gaps: {} }; },
        rangeStats: async r => ({ ...envelope(r.generation), statistics: {} }),
        changesSince: async r => ({ ...envelope(r.generation), resetRequired: true, changes: [] }) };
    }
    const controller = new MonitorReadController(source('auto', 100));
    try {
      controller.configure(['distanceMeters'], 'all'); controller.refresh(); await settle();
      const before = controller.getSnapshot().data!;
      const snap = { key: 'retained:1', sourceId: before.sourceId!, revision: before.revision!, metric: 'distanceMeters', point: before.series.distanceMeters![0]! };
      controller.updateSource(source('controller', 200)); controller.refresh(); await settle();
      controller.inspect(1, false, snap); await settle();
      expect(controller.getSnapshot().data).toBe(before);
      releaseDescription(); await settle();
      expect(controller.getSnapshot().data?.metricSources?.distanceMeters?.source).toBe('controller');
      expect(monitorSelection(controller.getSnapshot().data!, metricById('distanceMeters')!, 1)).toBeNull();
      expect(anchors.length).toBeGreaterThan(0); expect(anchors.every(anchor => anchor === undefined)).toBe(true);
      releaseInspect(); await settle(); await vi.advanceTimersByTimeAsync(20); await settle();
      expect(monitorSelection(controller.getSnapshot().data!, metricById('distanceMeters')!, 1)?.value).toBe(200);
    } finally { releaseDescription(); releaseInspect(); controller.dispose(); vi.useRealTimers(); }
  });

  it('fences a pending old preference response while retaining the current plot and viewport', async () => {
    const oldSource = Object.assign(new TelemetryMonitor('workout:same-ride', false, [sample(0, 100), sample(1, 100)]), { semanticKey: 'distance:v1:auto' });
    const controller = new MonitorReadController(oldSource);
    controller.configure(['humanPowerW'], 'all'); controller.refresh(); await settle();
    const before = controller.getSnapshot(), drawing = before.data!.series;
    const originalLatest = oldSource.readLatest.bind(oldSource);
    let complete!: () => void;
    vi.spyOn(oldSource, 'readLatest').mockImplementationOnce(async request => {
      const value = await originalLatest(request);
      await new Promise<void>(resolve => { complete = resolve; });
      return value.status === 'ok' ? { ...value, points: { humanPowerW: { elapsedSeconds: 1, timestamp: '2026-01-01T00:00:01.000Z', value: 999 } } } : value;
    });
    controller.refresh(); await settle();
    const nextSource = Object.assign(new TelemetryMonitor('workout:same-ride', false, [sample(0, 200), sample(1, 200), sample(2, 200)]), { semanticKey: 'distance:v1:controller' });
    controller.updateSource(nextSource);
    complete(); await settle();
    expect(controller.getSnapshot().latest!.humanPowerW!.value).toBe(100);
    expect(controller.getSnapshot().data!.series).toBe(drawing);
    controller.refresh(); await settle();
    expect(controller.getSnapshot().latest!.humanPowerW!.value).toBe(200);
    expect(controller.getSnapshot().data!.series.humanPowerW![0]!.value).toBe(200);
    expect(controller.getSnapshot().viewport).toEqual(before.viewport);
    controller.dispose();
  });
  it('publishes the completed plot source caption even when the original revision is unchanged', async () => {
    const source = new TelemetryMonitor('distance-caption', false, [sample(0), sample(1)]);
    const describe = source.describeSource.bind(source), plot = source.readPlot.bind(source), latest = source.readLatest.bind(source);
    vi.spyOn(source, 'describeSource').mockImplementation(async request => ({ ...await describe(request), metricSources: { humanPowerW: { source: 'pending', label: 'Calculating distance' } } }));
    vi.spyOn(source, 'readPlot').mockImplementation(async request => ({ ...await plot(request), metricSources: { humanPowerW: { source: 'controller', label: 'Controller estimate', estimated: true, partial: true } } }));
    vi.spyOn(source, 'readLatest').mockImplementation(async request => ({ ...await latest(request), metricSources: { humanPowerW: { source: 'gps:watch', label: 'GPS · Watch', partial: false } } }));
    const controller = new MonitorReadController(source); controller.configure(['humanPowerW'], 'all'); controller.refresh(); await settle();
    expect(controller.getSnapshot().data?.revision).toBe('2');
    expect(controller.getSnapshot().data?.metricSources?.humanPowerW).toMatchObject({ label: 'Controller estimate', partial: true });
    expect(controller.getSnapshot().latestMetricSources?.humanPowerW).toMatchObject({ label: 'GPS · Watch', partial: false });
    controller.dispose();
  });
  it('gives a new geometry query identity when a number-only metric changes reduction budget at the same revision', async () => {
    const { controller } = setup(); await settle();
    const group = groupMonitorMetrics(['humanPowerW'])[0]!;
    const before = controller.getSnapshot().data!;
    const first = monitorRasterScene(before, group, monitorScale(group.metrics, before), 1);
    controller.configure(['humanPowerW', 'batteryVoltageV'], 'all'); await settle();
    const after = controller.getSnapshot().data!;
    const second = monitorRasterScene(after, group, monitorScale(group.metrics, after), 1);
    expect(before.revision).toBe(after.revision);
    expect(before.plotViewport).toEqual(after.plotViewport);
    expect(after.plotGeneration).not.toBe(before.plotGeneration);
    expect(second.key).not.toBe(first.key);
    controller.dispose();
  });

  it('retains originals after failed or deferred lookup until a successful response establishes availability', async () => {
    const { source, controller } = setup(); await settle(); controller.inspect(10); await settle();
    const inspect = vi.spyOn(source, 'inspectAt');
    for (const message of ['Storage work queue is full. Retry the pending operation.', 'Read failed']) {
      inspect.mockRejectedValueOnce(new Error(message)); controller.inspect(20); await settle();
      expect(controller.getSnapshot().data!.selectionPending).toBe(true);
      expect(controller.getSnapshot().data!.selection!.humanPowerW!.elapsedSeconds).toBe(10);
    }
    controller.inspect(20); await settle();
    expect(controller.getSnapshot().data!.selectionPending).toBe(false);
    expect(controller.getSnapshot().data!.selection!.humanPowerW!.elapsedSeconds).toBe(20);
    controller.dispose();
  });

  it('retains exact originals throughout delayed cursor work, then clears confirmed unavailable and late results after clear', async () => {
    const { source, controller } = setup(); await settle();
    controller.inspect(10); await settle();
    const original = source.inspectAt.bind(source); let release!: () => void;
    vi.spyOn(source, 'inspectAt').mockImplementationOnce(async request => { const result = await original(request); await new Promise<void>(resolve => { release = resolve; }); return result; });
    const snapshots: ReturnType<typeof controller.getSnapshot>[] = [];
    const off = controller.subscribe(state => snapshots.push(state));
    controller.inspect(20); await settle();
    expect(snapshots.every(state => state.data?.selection?.humanPowerW?.elapsedSeconds === 10)).toBe(true);
    expect(controller.getSnapshot().data!.selectionPending).toBe(true);
    release(); await settle();
    expect(controller.getSnapshot().data!.selection!.humanPowerW!.elapsedSeconds).toBe(20);
    controller.inspect(90); await settle();
    expect(controller.getSnapshot().data!.selection!.humanPowerW).toBeNull();
    vi.mocked(source.inspectAt).mockImplementationOnce(async request => { const result = await original(request); await new Promise<void>(resolve => { release = resolve; }); return result; });
    controller.inspect(15); await settle(); controller.inspect(null); release(); await settle();
    expect(controller.getSnapshot().data!.selection).toEqual({});
    off(); controller.dispose();
  });
  it('keeps visible equal-time extrema identities through exact lookup and A/B without rereading plots', async () => {
    const source = new TelemetryMonitor('twins', false, [sample(0), sample(1, 50), sample(1, 900), sample(2)]);
    const controller = new MonitorReadController(source); controller.configure(['humanPowerW'], 'all'); controller.refresh(); await settle();
    const plots = vi.spyOn(source, 'readPlot'), inspect = vi.spyOn(source, 'inspectAt');
    const snap = (value: number) => ({ key: 'native:1', sourceId: 'twins', revision: '4', metric: 'humanPowerW', point: controller.getSnapshot().data!.series.humanPowerW!.find(point => point.value === value)! });
    controller.inspect(1, false, snap(900));
    expect(controller.getSnapshot().data!.selection!.humanPowerW!.value).toBe(900);
    expect(controller.getSnapshot().intervalStatistics).toEqual({});
    await settle(); controller.setReference(1); await settle(); controller.inspect(1, false, snap(50)); await settle();
    expect(controller.getSnapshot().data!.reference!.humanPowerW!.value).toBe(900);
    expect(controller.getSnapshot().data!.selection!.humanPowerW!.value).toBe(50);
    expect(inspect.mock.calls.some(([request]) => request.anchor?.observationId === 'twins:0000000000000002')).toBe(true);
    expect(plots).not.toHaveBeenCalled(); controller.dispose();
  });

  it('defers a prearmed statistics cadence timer until navigation settles', async () => {
    vi.useFakeTimers({ toFake: ['Date', 'performance', 'setTimeout', 'clearTimeout'] });
    const { source, controller } = setup(); await settle();
    try {
      const statistics = vi.spyOn(source, 'rangeStats');
      source.append(sample(31)); controller.refresh(); await settle(); // arms the two-second cadence
      expect(statistics).not.toHaveBeenCalled();
      for (let i = 0; i < 50; i++) {
        controller.changeViewport({ start: i / 10, end: 20 + i / 10 }, true);
        await vi.advanceTimersByTimeAsync(50); await settle();
      }
      expect(statistics).not.toHaveBeenCalled();
      controller.changeViewport({ start: 5, end: 25 }); await settle();
      expect(statistics).toHaveBeenCalledTimes(1);
    } finally { controller.dispose(); vi.useRealTimers(); }
  });
  it('keeps moving cursor reads independent of plots and avoids rereading stationary A or scanning every moving B', async () => {
    vi.useFakeTimers();
    const { source, controller } = setup(); await settle();
    try {
      const plots = vi.spyOn(source, 'readPlot'), statistics = vi.spyOn(source, 'rangeStats');
      for (let i = 0; i < 5; i++) { controller.inspect(10 + i, true); await settle(); await vi.advanceTimersByTimeAsync(50); }
      controller.inspect(15); await settle();
      expect(plots).not.toHaveBeenCalled(); expect(statistics).not.toHaveBeenCalled();
      controller.setReference(5); await settle(); statistics.mockClear();
      const cursor = vi.spyOn(source, 'inspectAt');
      for (let i = 0; i < 8; i++) { controller.inspect(16 + i, true); await settle(); await vi.advanceTimersByTimeAsync(50); }
      expect(statistics).not.toHaveBeenCalled();
      expect(cursor.mock.calls.every(([request]) => request.seconds !== 5)).toBe(true);
      controller.inspect(24); await settle();
      expect(statistics).toHaveBeenCalledTimes(1);
      expect(controller.getSnapshot().intervalStatistics.humanPowerW!.count).toBe(20);
      expect(plots).not.toHaveBeenCalled();
      controller.inspect(25, true); controller.inspect(null); await settle();
      await vi.advanceTimersByTimeAsync(200); expect(statistics).toHaveBeenCalledTimes(1);
      controller.inspect(26, true); controller.dispose(); await vi.advanceTimersByTimeAsync(200);
      expect(statistics).toHaveBeenCalledTimes(1);
    } finally { controller.dispose(); vi.useRealTimers(); }
  });
  it('keeps latest originals fresh in a fixed earlier viewport and exposes a real six-second outage', async () => {
    vi.useFakeTimers({ toFake: ['Date', 'performance', 'setTimeout', 'clearTimeout'] });
    vi.setSystemTime(Date.UTC(2026, 0, 1, 0, 0, 30));
    const source = new TelemetryMonitor('fixed-live', true, Array.from({ length: 31 }, (_, i) => sample(i)));
    const controller = new MonitorReadController(source);
    controller.configure(['humanPowerW'], 'all'); controller.refresh(); await settle();
    controller.changeViewport({ start: 5, end: 15 }); await settle();
    const plot = vi.spyOn(source, 'readPlot');
    for (let time = 31; time <= 38; time++) { vi.setSystemTime(Date.UTC(2026, 0, 1) + time * 1000); source.append(sample(time)); controller.refresh(); await settle(); }
    expect(plot).not.toHaveBeenCalled();
    expect(controller.getSnapshot().latest!.humanPowerW!.elapsedSeconds).toBe(38);
    expect(controller.getSnapshot().latestRevision).toBe('39');
    expect(controller.getSnapshot().viewport).toEqual({ start: 5, end: 15 });
    const latest = controller.getSnapshot().latest!.humanPowerW!;
    expect(latest.timestamp).toBe(sample(38).timestamp);
    const current = () => currentMonitorPoint(controller.getSnapshot().latest!.humanPowerW, true, controller.getSnapshot().nowSeconds, metricById('humanPowerW')!.gapSeconds);
    expect(current()).toBe(latest);
    await vi.advanceTimersByTimeAsync(5999); controller.tick();
    expect(current()).toBe(latest);
    await vi.advanceTimersByTimeAsync(1); controller.tick();
    expect(current()).toBeNull();
    expect(controller.getSnapshot().latest!.humanPowerW).toBe(latest);
    expect(controller.getSnapshot().latestRevision).toBe('39');
    controller.dispose(); vi.useRealTimers();
  });
  it('anchors freshness at actual description dispatch after the shared fast lane is held', async () => {
    vi.useFakeTimers({ toFake: ['Date', 'performance', 'setTimeout', 'clearTimeout'] });
    vi.setSystemTime(Date.UTC(2026, 0, 1, 0, 0, 30));
    let release!: () => void;
    const held = reads.schedule('fast', 'held-original-source', 'description', () => new Promise<void>(resolve => { release = resolve; }));
    const source = new TelemetryMonitor('new-live-source', true, [sample(30)]), controller = new MonitorReadController(source);
    controller.configure(['humanPowerW'], 'all'); controller.refresh();
    try {
      for (let time = 31; time <= 38; time++) { await vi.advanceTimersByTimeAsync(1000); source.append(sample(time)); }
      release(); await held; await settle();
      const state = controller.getSnapshot(), point = state.latest!.humanPowerW!;
      expect(state.nowSeconds).toBe(point.elapsedSeconds);
      expect(currentMonitorPoint(point, true, state.nowSeconds, 6)).toBe(point);
    } finally { controller.dispose(); vi.useRealTimers(); }
  });
  it('advances plots and latest while a statistics scan remains held, and labels retained extrema', async () => {
    const { source, controller } = setup(); await settle();
    const original = source.rangeStats.bind(source); let release!: () => void;
    vi.spyOn(source, 'rangeStats').mockImplementationOnce(async request => { const result = await original(request); await new Promise<void>(resolve => { release = resolve; }); return result; });
    controller.changeViewport({ start: 0, end: 20 }); await settle();
    source.append(sample(12, 900)); controller.refresh(); await settle();
    expect(controller.getSnapshot().data!.revision).toBe('32');
    expect(controller.getSnapshot().latestRevision).toBe('32');
    expect(controller.getSnapshot().data!.statisticsRevision).toBe('31');
    expect(controller.getSnapshot().data!.statisticsPending).toBe(true);
    expect(controller.getSnapshot().data!.statisticsViewport).toEqual({ start: 0, end: 30 });
    release(); await settle(); controller.dispose();
  });
  it('limits continuous live statistics to a two-second completion cadence while plots and latest advance', async () => {
    vi.useFakeTimers({ toFake: ['Date', 'performance', 'setTimeout', 'clearTimeout'] });
    vi.setSystemTime(Date.UTC(2026, 0, 1, 0, 0, 30));
    const { source, controller } = setup(); await settle();
    const statistics = vi.spyOn(source, 'rangeStats'), plots = vi.spyOn(source, 'readPlot');
    try {
      for (let i = 1; i <= 15; i++) {
        await vi.advanceTimersByTimeAsync(125); source.append(sample(30 + i / 8)); controller.refresh(); await settle();
      }
      expect(statistics).not.toHaveBeenCalled();
      expect(plots.mock.calls.length).toBeGreaterThan(1);
      expect(controller.getSnapshot().latestRevision).toBe('46');
      await vi.advanceTimersByTimeAsync(125); await settle();
      expect(statistics).toHaveBeenCalledTimes(1);
      controller.changeViewport({ start: 0, end: 20 }); await settle();
      expect(statistics).toHaveBeenCalledTimes(2); // deliberate gesture gets prompt exact statistics
    } finally { controller.dispose(); vi.useRealTimers(); }
  });
  it('admits a queued comparison at a newer revision and publishes its exact endpoints together', async () => {
    const { source, controller } = setup(); await settle();
    const original = source.rangeStats.bind(source); let release!: () => void;
    vi.spyOn(source, 'rangeStats').mockImplementationOnce(async request => { const result = await original(request); await new Promise<void>(resolve => { release = resolve; }); return result; });
    controller.changeViewport({ start: 0, end: 20 }); await settle();
    controller.inspect(20); controller.setReference(5); await settle();
    source.append(sample(8, 900)); controller.refresh(); await settle();
    release(); await settle();
    expect(controller.getSnapshot().data!.selectionRevision).toBe('32');
    expect(controller.getSnapshot().data!.referenceRevision).toBe('32');
    expect(controller.getSnapshot().intervalStatistics.humanPowerW!.max?.value).toBe(900);
    controller.dispose();
  });
  it('reactivates after an in-flight description without stuck loading or stale publication', async () => {
    const source = new TelemetryMonitor('reactivate', false, [sample(0), sample(1)]);
    const original = source.describeSource.bind(source); let release!: () => void;
    vi.spyOn(source, 'describeSource').mockImplementationOnce(async request => { const result = await original(request); await new Promise<void>(resolve => { release = resolve; }); return result; });
    const controller = new MonitorReadController(source);
    controller.configure(['humanPowerW'], 'all'); controller.refresh(); await settle();
    controller.deactivate(); controller.activate(); controller.refresh();
    expect(controller.getSnapshot().description).toBeUndefined();
    release(); await settle();
    expect(controller.getSnapshot().loading.source).toBe(false);
    expect(controller.getSnapshot().data!.revision).toBe('2');
    controller.dispose();
  });
  it('keeps pressure out of error presentation and clears real errors only after success', async () => {
    const { source, controller } = setup(); await settle();
    const original = source.readPlot.bind(source);
    vi.spyOn(source, 'readPlot').mockRejectedValueOnce(new Error('MONITOR_ADMISSION_PLOT'));
    controller.changeViewport({ start: 0, end: 20 }); await settle();
    expect(controller.getSnapshot().errors.plot).toBeUndefined(); expect(controller.getSnapshot().deferred.plot).toBe(true);
    source.readPlot = async () => { throw new Error('Damaged chart metadata'); };
    controller.changeViewport({ start: 0, end: 21 }); await settle();
    const visible: (string | undefined)[] = [];
    controller.subscribe(state => visible.push(state.errors.plot));
    controller.refresh(); await settle();
    expect(visible.every(error => error === 'Damaged chart metadata')).toBe(true);
    source.readPlot = original; controller.refresh(); await settle();
    expect(controller.getSnapshot().errors.plot).toBeUndefined(); controller.dispose();
  });
  it('retries deferred metric changes on a static source without waiting for a new revision', async () => {
    const { source, controller } = setup(); await settle(); controller.inspect(5); await settle();
    vi.spyOn(source, 'readPlot').mockRejectedValueOnce(new Error('MONITOR_CACHE_PLOT'));
    vi.spyOn(source, 'inspectAt').mockRejectedValueOnce(new Error('MONITOR_ADMISSION_FAST'));
    controller.configure(['humanPowerW', 'batteryVoltageV'], 'all'); await settle();
    expect(controller.getSnapshot().deferred).toMatchObject({ plot: true, cursor: true });
    controller.refresh(); await settle();
    expect(controller.getSnapshot().deferred).toMatchObject({ plot: false, cursor: false });
    expect(controller.getSnapshot().data!.series.batteryVoltageV).toBeDefined();
    expect(controller.getSnapshot().data!.selection!.batteryVoltageV).toBeDefined();
    controller.dispose();
  });
  it('moves immediately during a gesture, coalesces detail reads and reads statistics only on release', async () => {
    vi.useFakeTimers();
    const { source, controller } = setup(); await settle();
    const read = vi.spyOn(source, 'readPlot'), stats = vi.spyOn(source, 'rangeStats');
    const original = controller.getSnapshot().data!.series;
    try {
      for (let i = 0; i < 10; i++) controller.changeViewport({ start: i, end: i + 10 }, true);
      expect(controller.getSnapshot().viewport).toEqual({ start: 9, end: 19 });
      expect(controller.getSnapshot().data!.plotViewport).toEqual({ start: 0, end: 30 });
      expect(controller.getSnapshot().data!.series).toBe(original);
      expect(controller.getSnapshot().data!.statisticsPending).toBe(true);
      expect(read).not.toHaveBeenCalled(); expect(stats).not.toHaveBeenCalled();
      await vi.advanceTimersByTimeAsync(120);
      expect(read).toHaveBeenCalledTimes(1); expect(stats).not.toHaveBeenCalled();
      expect(read.mock.calls[0]![0]).toMatchObject({ startSeconds: 9, endSeconds: 19 });
      expect(controller.getSnapshot().data!.statisticsPending).toBe(true);
      controller.changeViewport({ start: 15, end: 25 }, true);
      controller.changeViewport({ start: 16, end: 26 }); await settle();
      expect(read).toHaveBeenCalledTimes(2); expect(stats).toHaveBeenCalledTimes(1);
      expect(controller.getSnapshot().data!.plotViewport).toEqual({ start: 16, end: 26 });
      expect(controller.getSnapshot().data!.statistics.humanPowerW!.min?.elapsedSeconds).toBe(16);
      await vi.advanceTimersByTimeAsync(500);
      expect(read).toHaveBeenCalledTimes(2);
    } finally { controller.dispose(); vi.useRealTimers(); }
  });
  it('settles a wheel gesture after inactivity and cancels delayed work on disposal', async () => {
    vi.useFakeTimers();
    const { source, controller } = setup(); await settle();
    const read = vi.spyOn(source, 'readPlot'), stats = vi.spyOn(source, 'rangeStats');
    try {
      controller.changeViewport({ start: 5, end: 15 }, true);
      await vi.advanceTimersByTimeAsync(210);
      expect(stats).toHaveBeenCalledTimes(1);
      expect(controller.getSnapshot().data!.statistics.humanPowerW!.count).toBe(11);
      controller.changeViewport({ start: 10, end: 20 }, true); controller.dispose();
      const count = read.mock.calls.length;
      await vi.advanceTimersByTimeAsync(500); expect(read).toHaveBeenCalledTimes(count);
    } finally { controller.dispose(); vi.useRealTimers(); }
  });
  it('keeps landscape and tablet plot requests within the native bucket limit', async () => {
    const { source, controller } = setup(); await settle();
    const read = vi.spyOn(source, 'readPlot');
    controller.configure(['humanPowerW'], 'all', 900); await settle();
    expect(read).toHaveBeenLastCalledWith(expect.objectContaining({ pixelWidth: 900, buckets: 512 }));
    expect(controller.getSnapshot().data!.series.humanPowerW).not.toHaveLength(0);
    controller.configure(['humanPowerW'], 'all', 320); await settle();
    expect(read).toHaveBeenLastCalledWith(expect.objectContaining({ pixelWidth: 320, buckets: 320 }));
    controller.dispose();
  });
  it('inspects and compares originals without scheduling plot reads or changing geometry identity', async () => {
    const { source, controller } = setup(); await settle();
    const read = vi.spyOn(source, 'readPlot');
    const geometry = controller.getSnapshot().data!.series;
    controller.inspect(12.4); controller.setReference(5); await settle();
    expect(read).not.toHaveBeenCalled();
    expect(controller.getSnapshot().data!.series).toBe(geometry);
    expect(controller.getSnapshot().data!.selection!.humanPowerW!.elapsedSeconds).toBe(12);
    expect(controller.getSnapshot().data!.reference!.humanPowerW!.elapsedSeconds).toBe(5);
    expect(controller.getSnapshot().intervalStatistics.humanPowerW!.count).toBe(8);
    controller.dispose();
  });
  it('discards an older viewport response and admits only the most recent queued viewport', async () => {
    const { source, controller } = setup(); await settle();
    const actual = source.readPlot.bind(source);
    let release!: (value: MonitorPlotResult) => void;
    const first = actual({ generation: 2, expectedRevision: '31', metrics: ['humanPowerW'], startSeconds: 0, endSeconds: 10 });
    const read = vi.spyOn(source, 'readPlot').mockImplementationOnce(() => new Promise(resolve => { release = resolve; }));
    controller.changeViewport({ start: 0, end: 10 });
    controller.changeViewport({ start: 10, end: 20 });
    controller.changeViewport({ start: 20, end: 30 });
    expect(read).toHaveBeenCalledTimes(1);
    release(await first); await settle();
    expect(read).toHaveBeenCalledTimes(2);
    expect(read.mock.calls[1]![0]).toMatchObject({ startSeconds: 20, endSeconds: 30 });
    expect(controller.getSnapshot().viewport).toEqual({ start: 20, end: 30 });
    expect(controller.getSnapshot().data!.series.humanPowerW![0]!.elapsedSeconds).toBe(19);
    controller.dispose();
  });
  it('preserves viewport/cursor/A-B through a later revision of the same ride', async () => {
    vi.useFakeTimers();
    const { source, controller } = setup(); await settle();
    controller.changeViewport({ start: 5, end: 15 }); controller.inspect(12); controller.setReference(6); await settle();
    source.append(sample(9.5, 900)); controller.refresh(); await settle(); await vi.advanceTimersByTimeAsync(2100);
    const state = controller.getSnapshot();
    expect(state.description!.revision).toBe('32');
    expect(state.viewport).toEqual({ start: 5, end: 15 });
    expect([state.cursorSeconds, state.referenceSeconds]).toEqual([12, 6]);
    expect(state.data!.statistics.humanPowerW!.max?.value).toBe(900);
    expect(state.data!.revision).toBe('32');
    controller.dispose(); vi.useRealTimers();
  });
  it('uses the change journal to preserve unaffected viewport geometry', async () => {
    const { source, controller } = setup(); await settle();
    controller.changeViewport({ start: 5, end: 15 }); await settle();
    const read = vi.spyOn(source, 'readPlot'), geometry = controller.getSnapshot().data!.series;
    source.append(sample(31)); controller.refresh(); await settle();
    expect(read).not.toHaveBeenCalled();
    expect(controller.getSnapshot().data!.series).toBe(geometry);
    expect(controller.getSnapshot().data!.revision).toBe('32');
    controller.dispose();
  });
  it('does not apply stale cursor data after clearing or replacing the selection', async () => {
    const { source, controller } = setup(); await settle();
    const original = source.inspectAt.bind(source);
    let release!: () => void;
    vi.spyOn(source, 'inspectAt').mockImplementationOnce(async request => { await new Promise<void>(resolve => { release = resolve; }); return original(request); });
    controller.inspect(5); controller.inspect(20); release(); await settle();
    expect(controller.getSnapshot().data!.selection!.humanPowerW!.elapsedSeconds).toBe(20);
    controller.inspect(null); await settle();
    expect(controller.getSnapshot().data!.selection).toEqual({});
    controller.dispose();
  });
  it('lets a delayed change journal trigger catchup without promoting an older proof past a late correction', async () => {
    vi.useFakeTimers();
    const { source, controller } = setup(); await settle();
    controller.changeViewport({ start: 5, end: 15 }); await settle();
    const original = source.changesSince.bind(source), releases: (() => void)[] = [];
    const changes = vi.spyOn(source, 'changesSince').mockImplementationOnce(async request => {
      const admitted = await original(request);
      await new Promise<void>(resolve => { releases.push(resolve); }); return admitted;
    });
    const plot = vi.spyOn(source, 'readPlot');
    source.append(sample(31)); controller.refresh(); await settle();
    source.append(sample(12, 900)); controller.refresh(); await settle();
    expect(controller.getSnapshot().description!.revision).toBe('32');
    expect(changes).toHaveBeenCalledTimes(1);
    releases[0]!(); await settle(); await vi.advanceTimersByTimeAsync(2100);
    expect(plot).toHaveBeenCalledTimes(1);
    expect(controller.getSnapshot().data!.revision).toBe('33');
    expect(controller.getSnapshot().data!.statistics.humanPowerW!.max?.value).toBe(900);
    controller.dispose(); vi.useRealTimers();
  });
  it('retries an explicit revision mismatch without displaying mixed exact results', async () => {
    const { source, controller } = setup(); await settle();
    source.append(sample(31, 500));
    controller.inspect(20); await settle();
    expect(controller.getSnapshot().description!.revision).toBe('32');
    expect(controller.getSnapshot().data!.selection!.humanPowerW!.elapsedSeconds).toBe(20);
    expect(controller.getSnapshot().errors).not.toHaveProperty('cursor', expect.any(String));
    controller.dispose();
  });
  it('resets selections only when the descriptor identifies a different connection', async () => {
    const { controller } = setup(); await settle();
    controller.changeViewport({ start: 5, end: 15 }); controller.inspect(12); controller.setReference(6); await settle();
    controller.updateSource(new TelemetryMonitor('new-connection', false, [sample(0), sample(1)])); controller.refresh(); await settle();
    expect(controller.getSnapshot().data!.sourceId).toBe('new-connection');
    expect(controller.getSnapshot().cursorSeconds).toBeNull(); expect(controller.getSnapshot().referenceSeconds).toBeNull();
    expect(controller.getSnapshot().following).toBe(true);
    controller.dispose();
  });
  it('renders available geometry even when the independent statistics read fails', async () => {
    const source = new TelemetryMonitor('partial', false, [sample(0), sample(1)]);
    vi.spyOn(source, 'rangeStats').mockRejectedValue(new Error('Summary storage read failed'));
    const controller = new MonitorReadController(source);
    controller.configure(['humanPowerW'], 'all'); controller.refresh(); await settle();
    expect(controller.getSnapshot().data!.series.humanPowerW).toHaveLength(2);
    expect(controller.getSnapshot().errors.statistics).toBe('Summary storage read failed');
    expect(controller.getSnapshot().errors.plot).toBeUndefined();
    controller.dispose();
  });
  it('does not starve a slow description when foreground polling repeats', async () => {
    const { source, controller } = setup(); await settle();
    const original = source.describeSource.bind(source);
    let release!: () => void;
    const describe = vi.spyOn(source, 'describeSource').mockImplementationOnce(async request => { await new Promise<void>(resolve => { release = resolve; }); return original(request); });
    source.append(sample(31)); controller.refresh(); controller.refresh(); controller.refresh(); await settle();
    expect(describe).toHaveBeenCalledTimes(1);
    release(); await settle();
    expect(controller.getSnapshot().description!.revision).toBe('32');
    expect(controller.getSnapshot().loading.source).toBe(false);
    controller.dispose();
  });

  it('publishes coherent cold plots and statistics while 8 Hz capture advances past several refreshes', async () => {
    vi.useFakeTimers(); vi.setSystemTime(new Date(Date.UTC(2026, 0, 1) + 30_000));
    const source = new TelemetryMonitor('continuous-live', true, Array.from({ length: 31 }, (_, i) => sample(i)));
    const plot = source.readPlot.bind(source), stats = source.rangeStats.bind(source);
    const readPlot = vi.spyOn(source, 'readPlot').mockImplementation(async request => {
      const admitted = await plot(request);
      await new Promise(resolve => setTimeout(resolve, 1800)); return admitted;
    });
    const readStats = vi.spyOn(source, 'rangeStats').mockImplementation(async request => {
      const admitted = await stats(request);
      await new Promise(resolve => setTimeout(resolve, 3500)); return admitted;
    });
    const controller = new MonitorReadController(source), published: { revision: string; statsRevision?: string; count?: number }[] = [];
    controller.subscribe(state => { if (state.data?.revision) published.push({ revision: state.data.revision, statsRevision: state.data.statisticsRevision, count: state.data.statistics.humanPowerW?.count }); });
    controller.configure(['humanPowerW'], 'all'); controller.refresh();
    let tick = 0;
    const capture = setInterval(() => { tick++; source.append(sample(30 + tick / 8)); }, 125);
    const refresh = setInterval(() => controller.refresh(), 750);
    try {
      await vi.advanceTimersByTimeAsync(2000);
      expect(controller.getSnapshot().description!.revision).not.toBe('31');
      expect(controller.getSnapshot().data!.revision).toBe('31');
      expect(readPlot).toHaveBeenCalledTimes(1);
      expect(readStats).toHaveBeenCalledTimes(1);
      await vi.advanceTimersByTimeAsync(1600);
      expect(published.some(value => value.statsRevision === '31' && value.count === 31)).toBe(true);
      expect(readPlot.mock.calls.length).toBeGreaterThanOrEqual(2);
      await vi.advanceTimersByTimeAsync(6000);
      const completed = published.filter(value => value.statsRevision !== undefined);
      expect(new Set(completed.map(value => value.revision)).size).toBeGreaterThanOrEqual(2);
      expect(completed.every(value => Number(value.statsRevision) <= Number(value.revision) && value.count !== undefined)).toBe(true);
      expect(readStats.mock.calls.length).toBeLessThanOrEqual(3);
      expect(readPlot.mock.calls.length).toBeGreaterThan(readStats.mock.calls.length);
    } finally { clearInterval(capture); clearInterval(refresh); controller.dispose(); await vi.runOnlyPendingTimersAsync(); vi.useRealTimers(); }
  });
  it('keeps indexed cursor reads responsive during an older cold plot without mixing A/B revisions', async () => {
    const { source, controller } = setup(); await settle();
    controller.inspect(20); controller.setReference(5); await settle();
    const originalPlot = source.readPlot.bind(source), originalInspect = source.inspectAt.bind(source);
    let releasePlot!: () => void, releaseReference!: () => void;
    vi.spyOn(source, 'readPlot').mockImplementationOnce(async request => {
      const admitted = await originalPlot(request); await new Promise<void>(resolve => { releasePlot = resolve; }); return admitted;
    });
    vi.spyOn(source, 'inspectAt').mockImplementation(async request => {
      const admitted = await originalInspect(request);
      if (request.seconds === 5) await new Promise<void>(resolve => { releaseReference = resolve; });
      return admitted;
    });
    source.append(sample(12, 999)); controller.refresh(); await settle();
    const pending = controller.getSnapshot().data!;
    expect(pending.revision).toBe('31');
    expect(pending.selectionRevision).toBe('32');
    expect(pending.selection!.humanPowerW!.elapsedSeconds).toBe(20);
    expect(pending.referenceRevision).toBe('32');
    expect(controller.getSnapshot().intervalStatistics.humanPowerW).toBeDefined();
    releaseReference(); await settle();
    expect(controller.getSnapshot().data!.referenceRevision).toBe('32');
    expect(controller.getSnapshot().intervalStatistics.humanPowerW).toBeDefined();
    releasePlot(); await settle();
    expect(controller.getSnapshot().data!.revision).toBe('32');
    controller.dispose();
  });

});
