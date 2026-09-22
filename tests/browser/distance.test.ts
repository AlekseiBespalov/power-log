import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { chromium, type Browser, type Page } from '@playwright/test';
import { browserTestBundle } from '../helpers/browser-test-bundle';
import type * as Harness from '../fixtures/browser-ride-harness';
declare global { interface Window { distanceTests: typeof Harness } }
const bundle = browserTestBundle('tests/fixtures/browser-ride-harness.ts', 'distanceTests');
let browser: Browser;
beforeAll(async () => { browser = await chromium.launch({ headless: true }); });
afterAll(async () => { await browser?.close(); });
async function run<T>(work: (page: Page) => Promise<T>) {
  const context = await browser.newContext();
  try { const page = await context.newPage(); await page.route('http://127.0.0.1:43120/**', route => route.fulfill({ contentType: 'text/html', body: '<!doctype html><title>Distance store</title>' }));
    await page.goto('http://127.0.0.1:43120/'); await page.addScriptTag({ content: bundle }); return await work(page);
  } finally { await context.close(); }
}

describe('controller distance with real IndexedDB', () => {
  it('captures a global selection without rewriting rides and keeps profile and raw caches reusable', async () => run(async page => {
    const result = await page.evaluate(async () => {
      const api = window.distanceTests, ride = await api.store.begin('owner', 'device', false, '2026-01-01T00:00:00.000Z');
      const rows = Array.from({ length: 8 }, (_, sequence) => ({ ...api.syntheticSample(sequence, sequence), recordingId: ride.id, controllerSpeedMps: 5, speedRaw: 18,
        controllerModel: 'X6', controllerProtocol: '5.3', firmwareLabel: '20260101', connectionEpoch: 'epoch', active: true, interval: 0 }));
      await api.store.append(ride.id, 'owner', rows, 7, 7, rows[7]!.timestamp); await api.store.transition(ride.id, 'owner', 'save', 7, 7, rows[7]!.timestamp);
      const before = JSON.stringify(await api.store.get(ride.id)), originals = JSON.stringify(await api.store.page(ride.id, 0, 10));
      const [automatic, unavailable] = await Promise.all([api.ensureBrowserDistance(ride.id), api.ensureBrowserDistance(ride.id, 'gps:watch')]);
      const autoSource = api.monitor(ride.id), controllerSource = api.monitor(ride.id, false, api.store, 'controller'), gpsSource = api.monitor(ride.id, false, api.store, 'gps:watch');
      const auto = await autoSource.describeSource({ generation: 1 }), controller = await controllerSource.describeSource({ generation: 1 }), gps = await gpsSource.describeSource({ generation: 1 });
      const request = { generation: 1, metrics: ['humanPowerW', 'distanceMeters'], startSeconds: 0, endSeconds: 7, pixelWidth: 240 };
      await autoSource.readPlot({ ...request, expectedRevision: auto.revision }); await autoSource.rangeStats({ ...request, expectedRevision: auto.revision });
      let rawPages = 0, profileInputPages = 0; const page = api.store.page, getAll = IDBObjectStore.prototype.getAll;
      IDBObjectStore.prototype.getAll = function(...args) { if (this.name === 'samples') profileInputPages++; return getAll.apply(this, args); };
      api.store.page = async (...args) => { rawPages++; return page(...args); };
      const selected = await controllerSource.readPlot({ ...request, expectedRevision: controller.revision });
      await controllerSource.rangeStats({ ...request, expectedRevision: controller.revision });
      const missing = await gpsSource.readPlot({ ...request, expectedRevision: gps.revision });
      const stats = await gpsSource.rangeStats({ ...request, expectedRevision: gps.revision });
      const latest = await gpsSource.readLatest({ generation: 2, metrics: ['distanceMeters'] });
      const inspect = await gpsSource.inspectAt({ ...request, expectedRevision: gps.revision, seconds: 3 });
      const stale = await controllerSource.readPlot({ ...request, expectedRevision: auto.revision });
      const changes = await controllerSource.changesSince({ generation: 2, sinceRevision: auto.revision });
      api.store.page = page; IDBObjectStore.prototype.getAll = getAll;
      const recorder = api.createRecorder().recorder;
      const [defaultSummary, missingSummary] = await Promise.all([recorder.read(ride.id), recorder.read(ride.id, 'gps:watch')]);
      const urls = await Promise.all([recorder.export(ride.id, 'auto'), recorder.export(ride.id, 'gps:watch')]);
      const csv = await Promise.all(urls.map(async url => { const text = await (await fetch(url)).text(); URL.revokeObjectURL(url); return text; }));
      return { automatic: automatic.info, unavailable: unavailable.info, sameGeneration: automatic.profile.generation === unavailable.profile.generation,
        auto, controller, gps, selected, missing, stats, latest, inspect, stale, changes, rawPages, profileInputPages, defaultSummary, missingSummary, csvSame: csv[0] === csv[1],
        metadataUnchanged: before === JSON.stringify(await api.store.get(ride.id)), originalsUnchanged: originals === JSON.stringify(await api.store.page(ride.id, 0, 10)) };
    });
    expect(result.automatic).toMatchObject({ selection: 'auto', selected: { source: 'controller', distanceMeters: 35 } });
    expect(result.unavailable).toMatchObject({ selection: 'gps:watch', selected: null }); expect(result.sameGeneration).toBe(true);
    expect(result.auto.revision).not.toBe(result.controller.revision); expect(result.controller.revision).not.toBe(result.gps.revision);
    expect(result.auto.sourceId).toBe(result.controller.sourceId); expect(result.stale.status).toBe('retry');
    expect(result.changes.resetRequired).toBe(true); expect(result.changes.changes[0]?.kind).toBe('semantics'); expect(result.rawPages).toBe(0); expect(result.profileInputPages).toBe(0);
    if (result.selected.status === 'ok') expect(result.selected.series.distanceMeters!.at(-1)!.value).toBe(35); else throw new Error('Selected plot failed');
    if (result.missing.status === 'ok') expect(result.missing.series.distanceMeters).toEqual([]); else throw new Error('Missing plot failed');
    if (result.stats.status === 'ok') expect(result.stats.statistics.distanceMeters).toBeUndefined(); else throw new Error('Missing statistics failed');
    if (result.latest.status === 'ok') expect(result.latest.points.distanceMeters).toBeNull(); else throw new Error('Missing latest failed');
    if (result.inspect.status === 'ok') expect(result.inspect.points.distanceMeters).toBeNull(); else throw new Error('Missing inspection failed');
    expect(result.defaultSummary.summary.distance?.selection).toBe('auto'); expect(result.missingSummary.summary.distance?.selected).toBeNull();
    expect(result.missingSummary.summary.distanceMeters).toBeUndefined(); expect(result.metadataUnchanged && result.originalsUnchanged && result.csvSame).toBe(true);
  }));

  it('keeps plot and statistics cancellation independent when their caller generations differ', async () => run(async page => {
    const result = await page.evaluate(async () => {
      const api = window.distanceTests, ride = await api.store.begin('owner', 'device', false, '2026-01-01T00:00:00.000Z');
      const rows = Array.from({ length: 8 }, (_, sequence) => ({ ...api.syntheticSample(sequence, sequence), recordingId: ride.id }));
      await api.store.append(ride.id, 'owner', rows, 7, 7, rows[7]!.timestamp);
      const source = api.monitor(ride.id), description = await source.describeSource({ generation: 1 });
      const base = { expectedRevision: description.revision, metrics: ['humanPowerW'], startSeconds: 0, endSeconds: 7 };
      const firstStats = await source.rangeStats({ ...base, generation: 40 });
      const followingPlot = await source.readPlot({ ...base, generation: 2, pixelWidth: 240 }).then(value => value.status, error => error.message);
      const newerPlot = await source.readPlot({ ...base, generation: 90, pixelWidth: 240 });
      const followingStats = await source.rangeStats({ ...base, startSeconds: .1, generation: 3 }).then(value => value.status, error => error.message);
      const comparison = await source.rangeStats({ ...base, startSeconds: .2, generation: 1, includeEndpoints: true });
      return { firstStats: firstStats.status, followingPlot, newerPlot: newerPlot.status, followingStats, comparison: comparison.status };
    });
    expect(result).toEqual({ firstStats: 'ok', followingPlot: 'ok', newerPlot: 'ok', followingStats: 'ok', comparison: 'ok' });
  }));
  it('does not reuse the previous ride subtotal when another browser owner starts a new ride', async () => run(async page => {
    const result = await page.evaluate(async () => {
      const api = window.distanceTests, first = api.createRecorder(), second = api.createRecorder();
      const options = { indoor: true, useWatch: false, saveToHealth: false, recordGPS: false };
      await first.source.connect(); await first.recorder.start(options);
      const speed = { speedRaw: 18, controllerSpeedMps: 5, controllerModel: 'X6', controllerProtocol: '5.3', firmwareLabel: '20260101' };
      first.source.sample(0, 100, speed); first.setTime(1); first.source.sample(1, 100, speed); await first.recorder.stop();
      let previous = await first.recorder.getState();
      for (let n = 0; n < 100 && previous.metrics.distanceMeters == null; n++) { await new Promise(resolve => setTimeout(resolve, 5)); previous = await first.recorder.getState(); }
      await second.source.connect(); const next = await second.recorder.start(options), observing = await first.recorder.getState();
      await second.recorder.discard(next.id!);
      return { previous, next, observing };
    });
    expect(result.previous.metrics.distanceMeters).toBe(5); expect(result.observing.id).toBe(result.next.id);
    expect(result.observing.metrics.distanceMeters).toBeNull(); expect(result.observing.distance?.selected).toBeNull();
  }));
  it('retains both non-aligned viewport edges in coarse long-range plots', async () => run(async page => {
    const results = await page.evaluate(async () => {
      const api = window.distanceTests, ride = await api.store.begin('owner', 'device', false, '2026-01-01T00:00:00.000Z');
      for (let offset = 0; offset < 1152; offset += 128) {
        const rows = Array.from({ length: 128 }, (_, n) => ({ ...api.syntheticSample(offset + n, offset + n), recordingId: ride.id, controllerSpeedMps: 5, speedRaw: 18, firmwareLabel: '20260101',
          controllerModel: 'X6', controllerProtocol: '5.3', connectionEpoch: 'epoch', active: true, interval: 0 }));
        const last = rows[127]!; await api.store.append(ride.id, 'owner', rows, last.elapsedSeconds, last.elapsedSeconds, last.timestamp);
      }
      const handle = await api.ensureBrowserDistance(ride.id);
      return Promise.all([4, 28, 240].map(async budget => {
        const points = await api.plotBrowserDistance(handle, 100.03, 1000.03, budget);
        const anchors = await Promise.all(points.map(point => api.inspectBrowserDistance(handle, point.elapsedSeconds, point.observationId)));
        return { budget, points, anchors };
      }));
    });
    for (const { budget, points, anchors } of results) {
      expect(points[0]!.elapsedSeconds).toBeLessThanOrEqual(100.03); expect(points.at(-1)!.elapsedSeconds).toBeGreaterThanOrEqual(1000.03);
      expect(points.length).toBeLessThanOrEqual(budget * 4 + 8);
      for (const point of points) expect(point.value).toBeCloseTo(point.elapsedSeconds * 5);
      expect(points.filter(point => point.startsSegment)).toHaveLength(1);
      expect(anchors.map(point => point?.observationId)).toEqual(points.map(point => point.observationId));
    }
  }));
  it('excludes saved pause intervals from range missing-time coverage without replaying samples', async () => run(async page => {
    const result = await page.evaluate(async () => {
      const api = window.distanceTests, ride = await api.store.begin('owner', 'device', true, '2026-01-01T00:00:00.000Z');
      const row = (time: number, sequence: number, interval: number) => ({ ...api.syntheticSample(time, sequence), recordingId: ride.id, controllerSpeedMps: 10, speedRaw: 36, firmwareLabel: '20260101',
        controllerModel: 'X6', controllerProtocol: '5.3', connectionEpoch: 'epoch', active: true, interval });
      await api.store.append(ride.id, 'owner', [row(0, 0, 0), row(1, 1, 0)], 1, 1, '2026-01-01T00:00:01.000Z');
      await api.ensureBrowserDistance(ride.id);
      await api.store.transition(ride.id, 'owner', 'pause', 1, 1, '2026-01-01T00:00:01.000Z');
      await api.store.transition(ride.id, 'owner', 'resume', 3, 1, '2026-01-01T00:00:03.000Z');
      await api.store.append(ride.id, 'owner', [row(3, 2, 1), row(4, 3, 1)], 4, 2, '2026-01-01T00:00:04.000Z');
      await api.store.transition(ride.id, 'owner', 'save', 4, 2, '2026-01-01T00:00:04.000Z');
      const handle = await api.ensureBrowserDistance(ride.id);
      return { stats: await api.browserDistanceStats(handle, 0, 4), subrange: await api.browserDistanceStats(handle, .5, 3.5), info: handle.info };
    });
    expect(result.stats).toEqual({ distance: 20, coveredSeconds: 2, unresolvedBoundary: false, partial: false });
    expect(result.subrange).toEqual({ distance: 10, coveredSeconds: 1, unresolvedBoundary: false, partial: false });
    expect(result.info.selected?.partial).toBe(false);
  }));
  it('shares one profile with summary, chart, exact boundaries and clipped statistics without changing originals', async () => run(async page => {
    const result = await page.evaluate(async () => {
      const api = window.distanceTests, ride = await api.store.begin('owner', 'device', true, '2026-01-01T00:00:00.000Z');
      const rows = [0, 1, 1.1, 2.1].map((time, sequence) => ({ ...api.syntheticSample(time, sequence), recordingId: ride.id, speedRaw: 36, controllerSpeedMps: 10,
        controllerModel: 'X6', controllerProtocol: '5.3', firmwareLabel: '20260101', connectionEpoch: sequence < 2 ? 'epoch-a' : 'epoch-b', active: true, interval: 0 }));
      await api.store.append(ride.id, 'owner', rows, 2.1, 2.1, rows[3]!.timestamp); await api.store.transition(ride.id, 'owner', 'save', 3, 3, '2026-01-01T00:00:03.000Z');
      const before = JSON.stringify(await api.store.page(ride.id, 0, 10)), handle = await api.ensureBrowserDistance(ride.id), monitor = api.monitor(ride.id), description = await monitor.describeSource({ generation: 1 });
      const request = { generation: 1, expectedRevision: description.revision, metrics: ['distanceMeters'] };
      const [plot, stats, latest, gap, summary] = await Promise.all([monitor.readPlot({ ...request, startSeconds: 0, endSeconds: 3, pixelWidth: 240 }), monitor.rangeStats({ ...request, startSeconds: .5, endSeconds: 1.6 }), monitor.readLatest(request), monitor.inspectAt({ ...request, seconds: 1.05 }), api.createRecorder().recorder.read(ride.id)]);
      const points = plot.status === 'ok' ? plot.series.distanceMeters! : [];
      const anchored = await monitor.inspectAt({ ...request, seconds: points[2]!.elapsedSeconds, anchor: { metric: 'distanceMeters', observationId: points[2]!.observationId! } });
      const inside = await monitor.readPlot({ ...request, startSeconds: .2, endSeconds: .4, pixelWidth: 240 });
      return { info: handle.info, description, plot, inside, stats, latest, gap, summary, anchored, unchanged: before === JSON.stringify(await api.store.page(ride.id, 0, 10)) };
    });
    expect(result.info.selected).toMatchObject({ source: 'controller', distanceMeters: 20, coveredSeconds: 2, uncoveredSeconds: 1, estimated: true, partial: true });
    expect(result.description.availableMetrics).toContain('distanceMeters'); expect(result.unchanged).toBe(true);
    expect(result.plot.status).toBe('ok'); if (result.plot.status === 'ok') expect(result.plot.series.distanceMeters!.map(p => [p.value, p.startsSegment])).toEqual([[0, true], [10, false], [10, true], [20, false]]);
    expect(result.stats.status).toBe('ok'); if (result.stats.status === 'ok') expect(result.stats.statistics.distanceMeters).toEqual({ distance: 10, coveredSeconds: 1, unresolvedBoundary: false, partial: true });
    expect(result.inside.status).toBe('ok'); if (result.inside.status === 'ok') expect(result.inside.series.distanceMeters!.map(p => p.elapsedSeconds)).toEqual([0, 1]);
    if (result.gap.status === 'ok') expect(result.gap.points.distanceMeters).toBeNull();
    if (result.anchored.status === 'ok') expect(result.anchored.points.distanceMeters).toMatchObject({ value: 10, elapsedSeconds: 1.1, derived: true });
    expect(result.summary.summary).toMatchObject({ distanceMeters: 20, averageSpeedMps: 10 });
  }));

  it('keeps observed zero eligible and rejects unknown-unit, paused and reset edges', async () => run(async page => {
    const result = await page.evaluate(async () => {
      const api = window.distanceTests, ride = await api.store.begin('owner', 'device', false, '2026-01-01T00:00:00.000Z');
      const rows = [0, 1, 2, 3, 4, 5].map((time, sequence) => ({ ...api.syntheticSample(time, sequence), recordingId: ride.id, speedRaw: 0, controllerSpeedMps: 0,
        controllerModel: 'X6', controllerProtocol: '5.3', firmwareLabel: '20260101', connectionEpoch: 'epoch', active: sequence !== 2, interval: sequence < 3 ? 0 : 1, originalSequence: sequence === 4 ? 0 : sequence }));
      await api.store.append(ride.id, 'owner', rows, 5, 4, rows[5]!.timestamp); await api.store.transition(ride.id, 'owner', 'save', 5, 4, rows[5]!.timestamp);
      const handle = await api.ensureBrowserDistance(ride.id), before = handle.profile.inputSequence;
      const updated = await api.ensureBrowserDistance(ride.id, 'controller');
      return { info: updated.info, before, after: updated.profile.inputSequence };
    });
    expect(result.info).toMatchObject({ selection: 'controller', selected: { distanceMeters: 0, coveredSeconds: 2 } });
    expect(result.before).toBe(result.after);
  }));

  it('checkpoints append work and never reads original rows for repeated cursor/range/plot queries', async () => run(async page => {
    const result = await page.evaluate(async () => {
      const api = window.distanceTests, ride = await api.store.begin('owner', 'device', false, '2026-01-01T00:00:00.000Z');
      const sample = (sequence: number) => ({ ...api.syntheticSample(sequence / 8, sequence), recordingId: ride.id, speedRaw: 18, controllerSpeedMps: 5,
        controllerModel: 'X6', controllerProtocol: '5.3', firmwareLabel: '20260101', connectionEpoch: 'epoch', active: true, interval: 0 });
      for (let offset = 0; offset < 640; offset += 128) { const rows = Array.from({ length: 128 }, (_, n) => sample(offset + n)); const last = rows[127]!; await api.store.append(ride.id, 'owner', rows, last.elapsedSeconds, last.elapsedSeconds, last.timestamp); }
      await api.ensureBrowserDistance(ride.id);
      const original = IDBObjectStore.prototype.getAll, originalCursor = IDBObjectStore.prototype.openCursor; let reads = 0, maximum = 0, cursorReads = 0;
      IDBObjectStore.prototype.getAll = function(...args) { if (this.name === 'samples') { reads++; maximum = Math.max(maximum, Number(args[1] ?? Infinity)); } return original.apply(this, args); };
      IDBObjectStore.prototype.openCursor = function(...args) { if (this.name === 'samples') cursorReads++; return originalCursor.apply(this, args); };
      const rows = Array.from({ length: 128 }, (_, n) => sample(640 + n)), last = rows[127]!; await api.store.append(ride.id, 'owner', rows, last.elapsedSeconds, last.elapsedSeconds, last.timestamp);
      const handle = await api.ensureBrowserDistance(ride.id), appendReads = reads;
      const monitor = api.monitor(ride.id), description = await monitor.describeSource({ generation: 1 }); reads = 0; cursorReads = 0;
      const request = { generation: 1, expectedRevision: description.revision, metrics: ['distanceMeters'] };
      for (let n = 0; n < 20; n++) { await monitor.inspectAt({ ...request, seconds: n + .03 }); await monitor.rangeStats({ ...request, startSeconds: n, endSeconds: n + 10 }); await monitor.readPlot({ ...request, startSeconds: n / 10, endSeconds: 95, pixelWidth: 30 }); await monitor.readLatest(request); }
      IDBObjectStore.prototype.getAll = original; IDBObjectStore.prototype.openCursor = originalCursor;
      return { appendReads, originalReads: reads + cursorReads, maximum, info: handle.info };
    });
    expect(result.appendReads).toBe(1); expect(result.originalReads).toBe(0); expect(result.maximum).toBeLessThanOrEqual(256);
    expect(result.info.selected?.distanceMeters).toBeCloseTo(767 / 8 * 5);
  }));
});
