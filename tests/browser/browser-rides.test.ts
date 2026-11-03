import { currentMonitorPoint, metricAllowsMean, metricById } from '../../src/core/monitor';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { writeFileSync } from 'node:fs';
import { chromium, type Browser, type BrowserContext, type Page } from '@playwright/test';
import { browserTestBundle } from '../helpers/browser-test-bundle';
import type * as Harness from '../fixtures/browser-ride-harness';
import { TILE_SECONDS } from '../../src/services/browser-ride-store';
import { parseCsv } from '../support/csv';
import { presentationFailure } from '../../shared/js';

declare global {
  interface Window {
    rideTests: typeof Harness;
    fixture: ReturnType<typeof Harness.createRecorder>;
  }
}
const bundle = browserTestBundle('tests/fixtures/browser-ride-harness.ts', 'rideTests');
let browser: Browser;
beforeAll(async () => {
  browser = await chromium.launch({ headless: true });
});
afterAll(async () => {
  await browser?.close();
});
async function pageIn(context: BrowserContext) {
  const page = await context.newPage();
  await page.route('http://127.0.0.1:43119/**', route =>
    route.fulfill({ contentType: 'text/html', body: '<!doctype html><title>Browser storage tests</title>' }),
  );
  await page.goto('http://127.0.0.1:43119/');
  await page.addScriptTag({ content: bundle });
  return page;
}
async function run<T>(work: (page: Page, context: BrowserContext) => Promise<T>) {
  const context = await browser.newContext();
  try {
    return await work(await pageIn(context), context);
  } finally {
    await context.close();
  }
}
const options = { indoor: true, useWatch: true, saveToHealth: true, recordGPS: true };

describe('browser rides with real IndexedDB and Web Locks', () => {
  it('returns empty plots and statistics for absent metrics without reading originals or tiles', async () =>
    run(async page => {
      const result = await page.evaluate(async () => {
        const api = window.rideTests;
        const ride = await api.store.begin(
          'owner',
          { indoor: true, recordGPS: false, sampleHz: 2 },
          '2026-01-01T00:00:00Z',
        );
        const rows = [0, 1].map((time, index) => ({
          ...api.browserRow(time, index),
          recordingId: ride.id,
        }));
        const record = await api.store.append(ride.id, 'owner', rows, 1, 1, rows[1]!.timestamp);
        const source = api.monitor(ride.id);
        const request = {
          generation: 1,
          expectedRevision: (await source.describeSource({ generation: 1 })).revision,
          metrics: ['controllerSpeedMps', 'heartRateBpm', 'speedMps'],
          startSeconds: 0,
          endSeconds: 1,
        };
        let originalReads = 0;
        let tileReads = 0;
        const open = IDBIndex.prototype.openCursor;
        IDBIndex.prototype.openCursor = function (...args) {
          if (this.objectStore.name === 'samples' || this.objectStore.name === 'ride-locations') originalReads++;
          return open.apply(this, args);
        };
        const tiles = api.store.tiles.bind(api.store);
        api.store.tiles = async (...args) => {
          tileReads++;
          return tiles(...args);
        };
        const plot = await source.readPlot(request);
        const stats = await source.rangeStats({ ...request, includeEndpoints: true });
        const directStats = await api.browserStatistics(record, api.store, 0, 1, request.metrics);
        return { available: record.availableMetrics, plot, stats, directStats, originalReads, tileReads };
      });
      for (const metric of ['controllerSpeedMps', 'heartRateBpm', 'speedMps'])
        expect(result.available).not.toContain(metric);
      expect(result.plot.status).toBe('ok');
      if (result.plot.status !== 'ok' || result.stats.status !== 'ok') throw new Error('Unexpected retry');
      expect(result.plot.series).toEqual({ controllerSpeedMps: [], heartRateBpm: [], speedMps: [] });
      const statistics = { controllerSpeedMps: { count: 0, coveredSeconds: 0, integral: 0 } };
      expect(result.stats.statistics).toEqual(statistics);
      expect(result.directStats).toEqual(statistics);
      const points = { controllerSpeedMps: null, heartRateBpm: null, speedMps: null };
      expect(result.stats.endpoints).toEqual({ start: points, end: points });
      expect(result.originalReads).toBe(0);
      expect(result.tileReads).toBe(0);
    }));

  it('keeps capture and streams originals when a chart cache reaches core capacity', async () =>
    run(async page => {
      const result = await page.evaluate(async () => {
        const api = window.rideTests;
        const query = new api.InspectionPlotQuery('humanPowerW', '0', '100000000', 1, 4);
        let capacity: unknown;
        try {
          for (let i = 0; i < 8; i++)
            query.append(
              api.browserInspectionItem({ ...api.browserRow(i * 10, i), recordingId: 'capacity' }),
              true,
              false,
            );
          query.result();
        } catch (error) {
          capacity = error;
        }
        if (!(capacity instanceof Error) || capacity.name !== 'InspectionCapacityException')
          throw new Error('Missing capacity error');
        const aggregate = api.InspectionPlotBucket.prototype.aggregate;
        api.InspectionPlotBucket.prototype.aggregate = () => {
          throw capacity;
        };
        const ride = await api.store.begin(
          'owner',
          { indoor: true, recordGPS: false, sampleHz: 2 },
          '2026-01-01T00:00:00Z',
        );
        const rows = [0, 1].map((time, index) => ({
          ...api.browserRow(time, index),
          recordingId: ride.id,
          humanPowerW: 100 + 100 * index,
        }));
        await api.store.append(ride.id, 'owner', rows, 1, 1, rows[1]!.timestamp);
        api.InspectionPlotBucket.prototype.aggregate = aggregate;
        const source = api.monitor(ride.id);
        const request = {
          generation: 1,
          expectedRevision: (await source.describeSource({ generation: 1 })).revision,
          metrics: ['humanPowerW'],
          startSeconds: 0,
          endSeconds: 2,
          pixelWidth: 32,
        };
        return {
          record: await api.store.get(ride.id),
          plot: await source.readPlot(request),
          stats: await source.rangeStats(request),
        };
      });
      expect(result.record.telemetryCount).toBe(2);
      expect(result.plot).toMatchObject({ status: 'ok' });
      if (result.plot.status === 'ok')
        expect(result.plot.series.humanPowerW?.map(point => point.value)).toEqual([100, 200]);
      expect(result.stats).toMatchObject({ statistics: { humanPowerW: { count: 2 } } });
    }));

  it('bounds inspection by the metric gap while preserving invalid barriers', async () =>
    run(async page => {
      const result = await page.evaluate(async () => {
        const api = window.rideTests;
        const ride = await api.store.begin(
          'owner',
          { indoor: true, recordGPS: false, sampleHz: 2 },
          '2026-01-01T00:00:00Z',
        );
        const rows = Array.from({ length: 301 }, (_, sequence) => ({
          ...api.browserRow(sequence, sequence),
          recordingId: ride.id,
          ...(sequence === 0
            ? {
                controllerSpeedMps: 10,
                speedRaw: 36,
                controllerModel: 'X6',
                controllerProtocol: '5.3',
                firmwareLabel: '20260101',
              }
            : {}),
        }));
        const validity = rows.map(row =>
          new api.InspectionOriginalInput(api.browserInspectionItem(row)).plotValid('controllerSpeedMps'),
        );
        if (!validity[0] || validity.slice(1).some(Boolean)) throw new Error('Invalid barrier fixture');
        for (let offset = 0; offset < rows.length; offset += api.BROWSER_BATCH) {
          const batch = rows.slice(offset, offset + api.BROWSER_BATCH);
          const tail = batch.at(-1)!;
          await api.store.append(ride.id, 'owner', batch, tail.elapsedSeconds, tail.elapsedSeconds, tail.timestamp);
        }
        const source = api.monitor(ride.id);
        const expectedRevision = (await source.describeSource({ generation: 1 })).revision;
        const open = IDBIndex.prototype.openCursor;
        const inspected: number[] = [];
        IDBIndex.prototype.openCursor = function (...args) {
          const request = open.apply(this, args);
          if (this.objectStore.name === 'samples')
            request.addEventListener('success', () => {
              if (request.result) inspected.push(request.result.value.elapsedSeconds);
            });
          return request;
        };
        const point = await source.inspectAt({
          generation: 1,
          expectedRevision,
          seconds: 150,
          metrics: ['controllerSpeedMps'],
        });
        IDBIndex.prototype.openCursor = open;
        return { point, inspected };
      });
      expect(result.point).toMatchObject({ status: 'ok', points: { controllerSpeedMps: null } });
      expect(result.inspected.length).toBeLessThanOrEqual(25);
      expect(result.inspected.every(time => Math.abs(time - 150) <= 6)).toBe(true);
    }));

  it.each([
    [7, "Power Log can't open rides saved by an earlier version. Clear this site's data to start over."],
    [10, 'Update Power Log to open your rides'],
  ])('refuses a version %i database and leaves it unchanged', async (version, message) =>
    run(async page => {
      const result = await page.evaluate(async version => {
        const open = (upgrade?: (db: IDBDatabase) => void, requested?: number) =>
          new Promise<IDBDatabase>((resolve, reject) => {
            const req = requested === undefined ? indexedDB.open('power-log') : indexedDB.open('power-log', requested);
            req.onupgradeneeded = () => upgrade?.(req.result);
            req.onsuccess = () => resolve(req.result);
            req.onerror = () => reject(req.error);
          });
        const read = (db: IDBDatabase) =>
          new Promise<unknown>((resolve, reject) => {
            const req = db.transaction('recordings').objectStore('recordings').get('kept');
            req.onsuccess = () => resolve(req.result);
            req.onerror = () => reject(req.error);
          });
        (await open(db => db.createObjectStore('recordings', { keyPath: 'id' }).put({ id: 'kept' }), version)).close();
        const error = await window.rideTests.browserDatabase().then(
          () => null,
          (reason: Error) => reason.message,
        );
        const db = await open();
        return { error, version: db.version, kept: await read(db) };
      }, version);
      expect(result).toEqual({ error: message, version, kept: { id: 'kept' } });
    }),
  );

  it('preserves committed-stop precedence on reopen', async () =>
    run(async (page, context) => {
      const id = await page.evaluate(async () => {
        const { store, browserRow } = window.rideTests;
        const record = await store.begin(
          'stop-token',
          { indoor: true, recordGPS: false, sampleHz: 2 },
          '2026-01-01T00:00:00.000Z',
        );
        await store.append(
          record.id,
          'stop-token',
          [{ ...browserRow(1, 0), recordingId: record.id }],
          1,
          1,
          '2026-01-01T00:00:01.000Z',
        );
        await store.transition(record.id, 'stop-token', 'stop', 2, 2, '2025-12-31T23:00:00.000Z');
        return record.id;
      });
      await page.close();
      const reopened = await pageIn(context);
      expect(
        await reopened.evaluate(
          async id => ({
            recovered: await window.rideTests.store.interruptCurrent(),
            record: await window.rideTests.store.get(id),
          }),
          id,
        ),
      ).toMatchObject({
        recovered: null,
        record: {
          interrupted: false,
          phase: 'completed',
          elapsedSeconds: 2,
          timerSeconds: 2,
          endedAt: '2025-12-31T23:00:00.000Z',
        },
      });
    }));
  it('removes a start that fails after the ride began and admits the next Start', async () =>
    run(async page => {
      const result = await page.evaluate(async () => {
        const { recorder, source } = window.rideTests.createRecorder();
        await source.connect();
        const setSampleRate = source.setSampleRate.bind(source);
        source.setSampleRate = async () => {
          throw new DOMException('GATT operation failed for unknown reason.', 'NetworkError');
        };
        const failed = await recorder.start({ indoor: true, useWatch: false }).then(
          () => null,
          (error: { code?: string; message?: string }) => ({ code: error.code, message: error.message }),
        );
        const afterFailure = await recorder.getState();
        const rides = (await window.rideTests.store.list()).records.length;
        source.setSampleRate = setSampleRate;
        const started = await recorder.start({ indoor: true, useWatch: false });
        await recorder.discard(started.id!);
        return {
          failed,
          rides,
          state: { id: afterFailure.id, phase: afterFailure.phase, failure: afterFailure.failure },
          started: started.phase,
        };
      });
      expect(result).toEqual({
        failed: { code: 'bikeUnavailable', message: presentationFailure('bikeUnavailable').text },
        rides: 0,
        state: { id: null, phase: 'idle', failure: null },
        started: 'running',
      });
    }));
  it('keeps the live elapsed clock and latest observation fresh through a one-hour UTC correction', async () =>
    run(async page => {
      const result = await page.evaluate(async () => {
        const { recorder, source, setTime } = window.rideTests.createRecorder();
        await source.connect();
        const ride = await recorder.start({ indoor: true, useWatch: false }),
          monitor = window.rideTests.monitor(ride.id!, true);
        const originalNow = Date.now,
          base = Date.parse((await window.rideTests.store.get(ride.id!)).startedAt);
        try {
          Date.now = () => base + 1000;
          setTime(1);
          source.sample(0);
          await recorder.lap();
          const before = await monitor.describeSource({ generation: 1 });
          Date.now = () => base + 3_602_000;
          setTime(2);
          source.sample(1, 150, { timestamp: new Date(Date.now()).toISOString() });
          await recorder.lap();
          const after = await monitor.describeSource({ generation: 2 });
          const latest = await monitor.readLatest({ generation: 2, metrics: ['humanPowerW'] });
          setTime(3);
          const live = await monitor.describeSource({ generation: 3 });
          const historical = await window.rideTests.monitor(ride.id!).describeSource({ generation: 3 });
          await recorder.stop();
          setTime(100);
          return { before, after, latest, live, historical, stopped: await monitor.describeSource({ generation: 4 }) };
        } finally {
          Date.now = originalNow;
        }
      });
      expect(result.before.nowSeconds).toBe(1);
      expect(result.after.nowSeconds).toBe(2);
      expect(result.live.nowSeconds).toBe(3);
      expect(result.historical.nowSeconds).toBeUndefined();
      expect(result.stopped.nowSeconds).toBeUndefined();
      expect(result.live.monotonicAt).toBe(3);
      expect(result.historical).not.toHaveProperty('liveAcquiredAt');
      expect(result.stopped).not.toHaveProperty('liveAcquiredAt');
      if (result.latest.status !== 'ok') throw new Error('Unexpected retry');
      const point = result.latest.points.humanPowerW!;
      expect(point).toMatchObject({ elapsedSeconds: 2, timestamp: '2026-01-01T01:00:02.000Z', value: 150 });
      expect(
        currentMonitorPoint(point, true, result.latest.monotonicAt, 6, result.latest.liveAcquiredAt?.humanPowerW),
      ).toEqual(point);
    }));

  it('freezes the next-ride sample rate before asynchronous start and applies it to an already connected source', async () =>
    run(async page => {
      const result = await page.evaluate(async () => {
        const { recorder, source, setTime } = window.rideTests.createRecorder();
        await source.connect();
        const options = { indoor: true, useWatch: false, sampleHz: 4 as 2 | 4 | 8 };
        const starting = recorder.start(options);
        options.sampleHz = 8;
        options.indoor = false;
        const first = await starting,
          activeRate = source.sampleRates.at(-1);
        setTime(1);
        await recorder.stop();
        const second = await recorder.start(options);
        await recorder.discard(second.id!);
        const invalid = await recorder.start({ ...options, sampleHz: 3 as never }).then(
          () => '',
          error => error.message,
        );
        return {
          first,
          second,
          activeRate,
          rates: source.sampleRates,
          invalid,
          count: (await recorder.list()).records.length,
        };
      });
      expect(result.first.indoor).toBe(true);
      expect(result.second.indoor).toBe(false);
      expect(result.activeRate).toBe(4);
      expect(result.rates).toEqual([4, 8]);
      expect(result.invalid).toMatch(/2, 4, or 8/);
      expect(result.count).toBe(1);
    }));

  it('persists one lifecycle, all paused raw fields, active aggregates and CSV', async () =>
    run(async page => {
      const result = await page.evaluate(async options => {
        const { recorder, source, setTime } = window.rideTests.createRecorder();
        await source.connect();
        const started = await recorder.start(options);
        source.sample(10, 100, {
          speedRaw: 12.24,
          controllerSpeedMps: 3.4,
          controllerModel: 'X6',
          firmwareLabel: '20260101',
          controllerProtocol: '5.3',
        });
        setTime(1);
        source.sample(11, 200);
        setTime(2);
        await recorder.pause(started.id!);
        setTime(3);
        source.sample(12, 1000);
        setTime(10);
        await recorder.resume(started.id!);
        source.sample(13, 300);
        setTime(11);
        source.sample(14, 400);
        setTime(12);
        await recorder.stop(started.id!);
        const rows = await window.rideTests.store.page(started.id!, 0, 20),
          detail = await recorder.read(started.id!);
        const csv = await window.rideTests.telemetryCsv(started.id!);
        const monitor = window.rideTests.monitor(started.id!),
          description = await monitor.describeSource({ generation: 1 });
        const query = {
          generation: 1,
          expectedRevision: description.revision,
          metrics: ['humanPowerW'],
          startSeconds: 0,
          endSeconds: 256,
          pixelWidth: 4,
        };
        return {
          started,
          rows,
          detail,
          csv,
          count: (await recorder.list()).records.length,
          available: description.availableMetrics,
          stats: await monitor.rangeStats(query),
          paused: await monitor.rangeStats({ ...query, startSeconds: 2, endSeconds: 9 }),
          plot: await monitor.readPlot(query),
        };
      }, options);
      expect(result.started).toMatchObject({
        phase: 'running',
        useWatch: false,
        saveToHealth: false,
        recordGPS: true,
        capabilities: {
          phoneWorkout: false,
          watchWorkout: false,
          phoneHealth: false,
          watchHealth: false,
          healthProvider: null,
          gps: true,
          foregroundOnly: true,
        },
      });
      expect(result.count).toBe(1);
      expect(result.rows).toHaveLength(5);
      expect(result.available).toContain('controllerSpeedMps');
      expect(result.rows[0]).toMatchObject({
        originalSequence: 10,
        sequence: 0,
        controllerModel: 'X6',
        speedRaw: 12.24,
        controllerSpeedMps: 3.4,
      });
      expect(result.rows[2]).toMatchObject({ active: false, humanPowerW: 1000, elapsedSeconds: 3 });
      expect(result.detail.metadata).toMatchObject({
        storage: 'browser',
        phase: 'completed',
        eventCount: '9',
        healthKitState: 'notRequested',
      });
      expect(result.detail.summary).toMatchObject({
        timerSeconds: 4,
        elapsedSeconds: 12,
        telemetryCoveredSeconds: 2,
        riderWorkJoules: 500,
        averageRiderPowerW: 250,
        maximumRiderPowerW: 400,
      });
      if (result.stats.status !== 'ok' || result.paused.status !== 'ok' || result.plot.status !== 'ok')
        throw new Error('Unexpected retry');
      expect(result.stats.statistics.humanPowerW).toMatchObject({
        count: 4,
        min: { value: 100 },
        max: { value: 400 },
        sampleMean: 250,
        coveredSeconds: 2,
        integral: 500,
      });
      expect(result.paused.statistics.humanPowerW).toMatchObject({ count: 0, coveredSeconds: 0, integral: 0 });
      expect(result.paused.statistics.humanPowerW?.min).toBeUndefined();
      expect(result.paused.statistics.humanPowerW?.max).toBeUndefined();
      expect(result.paused.statistics.humanPowerW?.sampleMean).toBeUndefined();
      expect(result.plot.series.humanPowerW?.some(point => point.value === 1000)).toBe(true);
      expect(result.csv.split('\n')).toHaveLength(7);
      expect(parseCsv(result.csv).samples.map(sample => [sample.activeInterval, sample.run])).toEqual([
        [1, 1],
        [1, 1],
        [null, 2],
        [2, 3],
        [2, 3],
      ]);
    }));

  it('does not save on disconnect and freezes source identity while allowing same-bike reconnect', async () =>
    run(async page => {
      const result = await page.evaluate(async options => {
        const fixture = window.rideTests.createRecorder(),
          { source, recorder } = fixture;
        await source.connect();
        const ride = await recorder.start(options);
        source.sample(5);
        fixture.setTime(1);
        await source.disconnect();
        fixture.setTime(20);
        const disconnected = await recorder.getState();
        const wrongBike = await source.connect({ deviceId: 'wrong-bike', hz: 8 }).then(
          () => '',
          e => e.message,
        );
        await source.connect();
        source.sample(0);
        fixture.setTime(21);
        await recorder.stop();
        return { disconnected, wrongBike, rows: await window.rideTests.store.page(ride.id!, 0, 30) };
      }, options);
      expect(result.disconnected).toMatchObject({ phase: 'running', timerSeconds: 20 });
      expect(result.wrongBike).toMatch(/bike/);
      expect(result.rows.map(row => row.elapsedSeconds)).toEqual([0, 20]);
      expect(result.rows.map(row => row.originalSequence)).toEqual([5, 0]);
    }));

  it('rolls back originals, projections and metadata together and fences stale tokens', async () =>
    run(async page => {
      const result = await page.evaluate(async () => {
        const { store, browserRow } = window.rideTests,
          ride = await store.begin(
            'owner-token',
            { indoor: true, recordGPS: false, sampleHz: 2 },
            '2026-01-01T00:00:00.000Z',
          );
        const row = { ...browserRow(0, 0), recordingId: ride.id };
        const originalPut = IDBObjectStore.prototype.put;
        IDBObjectStore.prototype.put = function (...args) {
          if (this.name === 'recordings') throw new DOMException('Injected quota failure', 'QuotaExceededError');
          return originalPut.apply(this, args);
        };
        const failed = await store.append(ride.id, 'owner-token', [row], 0, 0, row.timestamp).then(
          () => '',
          e => e.name,
        );
        IDBObjectStore.prototype.put = originalPut;
        const countAfterFailure = (await store.get(ride.id)).telemetryCount,
          rowsAfterFailure = (await store.page(ride.id, 0, 10)).length,
          tilesAfterFailure = (await store.tiles(ride.id, 0, 0, 2)).length;
        const stale = await store.append(ride.id, 'foreign-token', [row], 0, 0, row.timestamp).then(
          () => '',
          e => e.message,
        );
        await store.append(ride.id, 'owner-token', [row], 0, 0, row.timestamp);
        return {
          failed,
          countAfterFailure,
          rowsAfterFailure,
          tilesAfterFailure,
          stale,
          count: (await store.get(ride.id)).telemetryCount,
        };
      });
      expect(result).toMatchObject({
        failed: 'QuotaExceededError',
        countAfterFailure: 0,
        rowsAfterFailure: 0,
        tilesAfterFailure: 0,
        count: 1,
      });
      expect(result.stale).toMatch(/owns/);
    }));

  it('discards only the owned ride and leaves an earlier saved ride intact', async () =>
    run(async page => {
      const records = await page.evaluate(async options => {
        const { recorder, source } = window.rideTests.createRecorder();
        await source.connect();
        const first = await recorder.start(options);
        source.sample(0);
        await recorder.stop();
        const second = await recorder.start(options);
        source.sample(1);
        await recorder.discard(second.id!);
        return {
          first: first.id,
          list: await recorder.list(),
          rows: await window.rideTests.store.page(second.id!, 0, 10),
          tiles: await window.rideTests.store.tiles(second.id!, 0, 0, 10),
        };
      }, options);
      expect(records.list.records.map(row => row.id)).toEqual([records.first]);
      expect(records.rows).toEqual([]);
      expect(records.tiles).toEqual([]);
    }));

  it('exposes and discards a retained ride when reload cannot write its interruption', async () =>
    run(async (page, context) => {
      const ids = await page.evaluate(async () => {
        const { recorder, source, setTime } = window.rideTests.createRecorder();
        await source.connect();
        const saved = await recorder.start({ indoor: true, useWatch: false });
        setTime(1);
        source.sample(0);
        await recorder.stop();
        const retained = await recorder.start({ indoor: true, useWatch: false });
        setTime(2);
        source.sample(1);
        await recorder.lap();
        return { saved: saved.id!, retained: retained.id! };
      });
      await page.close();
      const reopened = await pageIn(context);
      const result = await reopened.evaluate(async ids => {
        const api = window.rideTests;
        const { recorder, source } = api.createRecorder();
        const before = await api.store.get(ids.retained);
        const savedCsv = await api.telemetryCsv(ids.saved);
        const put = IDBObjectStore.prototype.put;
        let rejectedWrites = 0;
        IDBObjectStore.prototype.put = function (...args) {
          if (this.name === 'recordings' || this.name === 'ride-control') {
            rejectedWrites++;
            throw new DOMException('Injected quota failure', 'QuotaExceededError');
          }
          return put.apply(this, args);
        };
        try {
          const state = await recorder.getState();
          const retained = await api.store.get(ids.retained);
          const writesBeforeDiscard = rejectedWrites;
          const discarded = await recorder.discard(ids.retained);
          const writesForDiscard = rejectedWrites - writesBeforeDiscard;
          IDBObjectStore.prototype.put = put;
          await source.connect();
          const next = await recorder.start({ indoor: true, useWatch: false });
          await recorder.discard(next.id!);
          return {
            state,
            unchanged: JSON.stringify(before) === JSON.stringify(retained),
            discarded,
            writesForDiscard,
            next: next.phase,
            remaining: (await recorder.list()).records.map(record => record.id),
            savedUnchanged: savedCsv === (await api.telemetryCsv(ids.saved)),
            originals: await api.store.page(ids.retained, 0, 10),
          };
        } finally {
          IDBObjectStore.prototype.put = put;
        }
      }, ids);
      expect(result.state).toMatchObject({
        id: ids.retained,
        phase: 'interrupted',
        foreignOwner: null,
        failure: { code: 'storageFull' },
      });
      expect(result.unchanged).toBe(true);
      expect(result.discarded).toMatchObject({ id: null, phase: 'idle', failure: null });
      expect(result.writesForDiscard).toBe(0);
      expect(result.next).toBe('running');
      expect(result.remaining).toEqual([ids.saved]);
      expect(result.savedUnchanged).toBe(true);
      expect(result.originals).toEqual([]);
    }));

  it('rejects a stale retained-ride Discard after another tab takes control', async () =>
    run(async (page, context) => {
      const oldID = await page.evaluate(async () => {
        const api = window.rideTests;
        const old = await api.store.begin(
          'abandoned',
          { indoor: true, recordGPS: false, sampleHz: 2 },
          '2026-01-01T00:00:00.000Z',
        );
        window.fixture = api.createRecorder();
        await window.fixture.recorder.getState();
        return old.id;
      });
      const owner = await pageIn(context);
      const newID = await owner.evaluate(async oldID => {
        window.fixture = window.rideTests.createRecorder();
        const { recorder, source } = window.fixture;
        await recorder.getState();
        await recorder.stop(oldID);
        await source.connect();
        return (await recorder.start({ indoor: true, useWatch: false })).id!;
      }, oldID);
      const result = await page.evaluate(
        async ({ oldID, newID }) => {
          const { recorder } = window.fixture;
          const rejected = await recorder.discard(oldID).then(
            () => null,
            (error: { code: string }) => error.code,
          );
          return {
            rejected,
            old: await window.rideTests.store.get(oldID),
            current: await window.rideTests.store.get(newID),
            state: await recorder.getState(),
          };
        },
        { oldID, newID },
      );
      expect(result.rejected).toBe('ownerForeign');
      expect(result.old.phase).toBe('completed');
      expect(result.current.phase).toBe('running');
      expect(result.state).toMatchObject({ id: newID, phase: 'running', foreignOwner: 'anotherBrowserTab' });
      await owner.evaluate(async id => window.fixture.recorder.discard(id), newID);
    }));

  it('refuses an unreadable retained record without changing or deleting it', async () =>
    run(async page => {
      const result = await page.evaluate(async () => {
        const api = window.rideTests;
        const record = await api.store.begin(
          'abandoned',
          { indoor: true, recordGPS: false, sampleHz: 2 },
          '2026-01-01T00:00:00.000Z',
        );
        const invalid = { ...record, sampleHz: 3 };
        await api.browserTransaction(['recordings'], 'readwrite', tx =>
          api.idbRequest(tx.objectStore('recordings').put(invalid)),
        );
        const rejected = await api
          .createRecorder()
          .recorder.getState()
          .then(
            () => null,
            (error: Error) => error.message,
          );
        const retained = await api.browserTransaction(['recordings'], 'readonly', tx =>
          api.idbRequest(tx.objectStore('recordings').get(record.id)),
        );
        return { rejected, unchanged: JSON.stringify(retained) === JSON.stringify(invalid) };
      });
      expect(result).toEqual({ rejected: 'Invalid recording sampleHz', unchanged: true });
    }));

  it('finishes on the first attempt after a controller write fails and records an interruption', async () =>
    run(async page => {
      const id = await page.evaluate(async () => {
        window.fixture = window.rideTests.createRecorder();
        const { recorder, source, setTime } = window.fixture;
        await source.connect();
        const ride = await recorder.start({ indoor: true, useWatch: false, sampleHz: 8 });
        for (let sequence = 0; sequence < 8; sequence++) {
          setTime((sequence + 1) / 8);
          source.sample(sequence);
        }
        return ride.id!;
      });
      await expect
        .poll(() => page.evaluate(async id => (await window.rideTests.store.get(id)).telemetryCount, id))
        .toBe(8);
      await page.evaluate(() => {
        const add = IDBObjectStore.prototype.add;
        IDBObjectStore.prototype.add = function (...args) {
          if (this.name === 'samples') {
            IDBObjectStore.prototype.add = add;
            throw new DOMException('Injected sample write failure', 'QuotaExceededError');
          }
          return add.apply(this, args);
        };
        const { source, setTime } = window.fixture;
        for (let sequence = 8; sequence < 16; sequence++) {
          setTime((sequence + 1) / 8);
          source.sample(sequence);
        }
      });
      await expect
        .poll(() => page.evaluate(async id => (await window.rideTests.store.get(id)).phase, id))
        .toBe('interrupted');
      const result = await page.evaluate(async id => {
        const api = window.rideTests;
        const { recorder, setTime } = window.fixture;
        setTime(120);
        const state = await recorder.stop(id);
        const record = await api.store.get(id);
        const events = (await api.store.lifecycleAfter(id, null, 10)).rows;
        return { state, record, events, csv: await api.telemetryCsv(id) };
      }, id);
      expect(result.state).toMatchObject({ phase: 'completed', timerSeconds: 1, failure: null });
      expect(result.record).toMatchObject({
        telemetryCount: 8,
        elapsedSeconds: 1,
        endedAt: '2026-01-01T00:00:01.000Z',
      });
      expect(result.events.map(event => event.action)).toEqual(['start', 'pause', 'stop']);
      expect(result.events.map(event => event.producerSequence)).toEqual(['1', '2', '3']);
      expect(parseCsv(result.csv).samples).toHaveLength(8);
    }));

  it('uses indexed chart reads with exact extrema, A/B identity, gaps, clipped integration and bounded geometry', async () =>
    run(async page => {
      const result = await page.evaluate(async () => {
        const { store, browserRow, monitor } = window.rideTests,
          ride = await store.begin(
            'token',
            { indoor: true, recordGPS: false, sampleHz: 2 },
            '2026-01-01T00:00:00.000Z',
          );
        for (let offset = 0; offset < 1600; offset += 64) {
          const rows = Array.from({ length: Math.min(64, 1600 - offset) }, (_, i) => {
            const seq = offset + i,
              seconds = seq / 8 + (seq >= 800 ? 20 : 0);
            return {
              ...browserRow(seconds, seq),
              humanPowerW: seq === 500 ? 1 : seq === 1400 ? 999 : 100,
              recordingId: ride.id,
            };
          });
          const last = rows[rows.length - 1]!;
          await store.append(ride.id, 'token', rows, last.elapsedSeconds, last.elapsedSeconds, last.timestamp);
        }
        const source = monitor(ride.id),
          description = await source.describeSource({ generation: 1 });
        let rowsRead = 0;
        const originalPage = store.page.bind(store);
        store.page = async (...args) => {
          const rows = await originalPage(...args);
          rowsRead += rows.length;
          return rows;
        };
        const query = {
          generation: 1,
          expectedRevision: description.revision,
          metrics: ['humanPowerW'],
          startSeconds: 0,
          endSeconds: 220,
        };
        const stats = await source.rangeStats({ ...query, includeEndpoints: true });
        const plot = await source.readPlot({ ...query, pixelWidth: 8 });
        const min = await source.inspectAt({
          ...query,
          seconds: 62.5,
          anchor: { metric: 'humanPowerW', observationId: `${ride.id}:500` },
        });
        const gap = await source.inspectAt({ ...query, seconds: 110 });
        const clipped = await source.rangeStats({ ...query, startSeconds: 0.05, endSeconds: 0.3 });
        const narrow = await source.readPlot({ ...query, startSeconds: 60, endSeconds: 70, pixelWidth: 240 });
        return { stats, plot, min, gap, clipped, narrow, rowsRead };
      });
      expect(result.stats.status).toBe('ok');
      if (
        result.stats.status !== 'ok' ||
        result.plot.status !== 'ok' ||
        result.min.status !== 'ok' ||
        result.gap.status !== 'ok' ||
        result.clipped.status !== 'ok'
      )
        throw new Error('Unexpected retry');
      expect(result.stats.statistics.humanPowerW).toMatchObject({
        count: 1600,
        min: { value: 1, elapsedSeconds: 62.5 },
        max: { value: 999 },
      });
      expect(result.min.points.humanPowerW?.value).toBe(1);
      expect(result.gap.points.humanPowerW).toBeNull();
      expect(result.clipped.statistics.humanPowerW?.coveredSeconds).toBeCloseTo(0.25);
      expect(result.clipped.statistics.humanPowerW?.count).toBe(2);
      expect(result.plot.series.humanPowerW!.length).toBeLessThanOrEqual(40);
      expect(result.rowsRead).toBeLessThan(1600);
      expect(result.plot.series.humanPowerW!.some(point => point.startsSegment === false)).toBe(true);
      if (result.narrow.status !== 'ok') throw new Error('Unexpected retry');
      expect(result.narrow.series.humanPowerW).toHaveLength(81);
      expect(result.narrow.series.humanPowerW!.find(point => point.elapsedSeconds === 62.5)?.value).toBe(1);
    }));

  it('integrates clipped empty ranges and applies the controller gap to tiles and boundary intervals', async () =>
    run(async page => {
      const result = await page.evaluate(async () => {
        const { store, browserRow, monitor } = window.rideTests,
          ride = await store.begin(
            'token',
            { indoor: true, recordGPS: false, sampleHz: 2 },
            '2026-01-01T00:00:00.000Z',
          );
        const rows = [0, 1, 4, 6, 8.5, 11.1].map((time, sequence) => ({
          ...browserRow(time, sequence),
          recordingId: ride.id,
          humanPowerW: time < 6 ? 100 : 200,
          speedRaw: 10.8,
          controllerSpeedMps: 3,
          controllerModel: 'X6',
          firmwareLabel: '20260101',
          controllerProtocol: '5.3',
        }));
        const last = rows.at(-1)!;
        await store.append(ride.id, 'token', rows, last.elapsedSeconds, last.elapsedSeconds, last.timestamp);
        const source = monitor(ride.id),
          description = await source.describeSource({ generation: 1 });
        const query = {
          generation: 1,
          expectedRevision: description.revision,
          metrics: description.availableMetrics.filter(
            id => !['distanceMeters', 'batteryWhPerKm', 'riderWhPerKm'].includes(id),
          ),
        };
        return {
          full: await source.rangeStats({ ...query, startSeconds: 0, endSeconds: 16 }),
          edge: await source.rangeStats({ ...query, startSeconds: 1.5, endSeconds: 4.5 }),
          ramp: await source.rangeStats({ ...query, startSeconds: 4.5, endSeconds: 5.5 }),
          constant: await source.rangeStats({ ...query, startSeconds: 0.2, endSeconds: 0.8 }),
          gap: await source.rangeStats({ ...query, startSeconds: 1.5, endSeconds: 3.5 }),
        };
      });
      for (const [key, covered] of [
        ['full', 5.5],
        ['edge', 0.5],
        ['ramp', 1],
        ['constant', 0.6],
      ] as const) {
        const value = result[key];
        if (value.status !== 'ok') throw new Error('Unexpected retry');
        for (const [metric, stats] of Object.entries(value.statistics)) {
          if (metricAllowsMean(metricById(metric)!))
            expect(stats.coveredSeconds, `${key}: ${metric}`).toBeCloseTo(covered);
          else {
            expect(stats).not.toHaveProperty('coveredSeconds');
            expect(stats).not.toHaveProperty('integral');
          }
        }
      }
      for (const [key, integral] of [
        ['ramp', 150],
        ['constant', 60],
      ] as const) {
        const value = result[key];
        if (value.status !== 'ok') throw new Error('Unexpected retry');
        const stats = value.statistics.humanPowerW!;
        expect(stats.count).toBe(0);
        expect(stats.integral).toBeCloseTo(integral);
        expect(stats.min).toBeUndefined();
        expect(stats.max).toBeUndefined();
        expect(stats.sampleMean).toBeUndefined();
      }
      if (result.gap.status !== 'ok') throw new Error('Unexpected retry');
      for (const stats of Object.values(result.gap.statistics)) expect(stats.count).toBe(0);
    }));

  it.each([
    { times: [0, 1, 8, 9, 16, 17], values: [0, 100, 50, 51, 0, 100], end: 256, starts: [0, 8, 16] },
    { times: [62, 63, 64, 65, 72, 73], values: [0, 100, 50, 51, 0, 100], end: 192, starts: [62, 72] },
  ])('retains every continuous run in wide tiles ending at $end', async ({ times, values, end, starts }) =>
    run(async page => {
      const result = await page.evaluate(
        async ({ times, values, end }) => {
          const { store, browserRow, monitor } = window.rideTests,
            ride = await store.begin(
              'token',
              { indoor: true, recordGPS: false, sampleHz: 2 },
              '2026-01-01T00:00:00.000Z',
            );
          const rows = times.map((time, sequence) => ({
            ...browserRow(time, sequence),
            humanPowerW: values[sequence]!,
            recordingId: ride.id,
          }));
          const last = rows.at(-1)!;
          await store.append(ride.id, 'token', rows, last.elapsedSeconds, last.elapsedSeconds, last.timestamp);
          const source = monitor(ride.id),
            description = await source.describeSource({ generation: 1 });
          return source.readPlot({
            generation: 1,
            expectedRevision: description.revision,
            metrics: ['humanPowerW'],
            startSeconds: 0,
            endSeconds: end,
            pixelWidth: 4,
          });
        },
        { times, values, end },
      );
      if (result.status !== 'ok') throw new Error('Unexpected retry');
      expect(result.series.humanPowerW?.map(point => point.elapsedSeconds)).toEqual(times);
      expect(result.series.humanPowerW?.map(point => point.value)).toEqual(values);
      expect(
        result.series.humanPowerW?.filter(point => point.startsSegment).map(point => point.elapsedSeconds),
      ).toEqual(starts);
    }),
  );

  it('keeps saved records and charts readable without capture capability', async () =>
    run(async page => {
      const result = await page.evaluate(async () => {
        const { store, browserRow, monitor, createRecorder } = window.rideTests;
        const record = await store.begin(
          'owner',
          { indoor: true, recordGPS: false, sampleHz: 2 },
          '2026-01-01T00:00:00.000Z',
        );
        const rows = Array.from({ length: 80 }, (_, sequence) => ({
          ...browserRow(sequence / 8, sequence),
          recordingId: record.id,
        }));
        for (let offset = 0; offset < rows.length; offset += 64) {
          const batch = rows.slice(offset, offset + 64),
            last = batch.at(-1)!;
          await store.append(record.id, 'owner', batch, last.elapsedSeconds, last.elapsedSeconds, last.timestamp);
        }
        await store.transition(record.id, 'owner', 'stop', 20, 20, '2026-01-01T00:00:20.000Z');
        Object.defineProperty(navigator, 'locks', { configurable: true, value: undefined });
        const { recorder, source } = createRecorder();
        await source.connect();
        const state = await recorder.getState();
        const failure = await recorder.start({ indoor: true, useWatch: false }).then(
          () => '',
          e => e.message,
        );
        const description = await monitor(record.id).describeSource({ generation: 1 });
        const stats = await monitor(record.id).rangeStats({
          generation: 1,
          expectedRevision: description.revision,
          startSeconds: 0,
          endSeconds: 10,
          metrics: ['humanPowerW'],
        });
        return {
          state,
          failure,
          records: await recorder.list(),
          stats,
          unchanged: await store.bySequence(record.id, 0),
        };
      });
      expect(result.state.supported).toBe(false);
      expect(result.failure).toBe(presentationFailure('actionUnavailable').text);
      expect(result.records.records).toHaveLength(1);
      expect(result.records.records[0]).toMatchObject({ storage: 'browser', saveToHealth: false });
      if (result.stats.status !== 'ok') throw new Error('Unexpected retry');
      expect(result.stats.statistics.humanPowerW?.count).toBe(80);
      expect(result.unchanged?.originalSequence).toBe(0);
    }));

  it(
    'retains all 57-minute and eight-hour 8 Hz originals with bounded chart work',
    async () =>
      run(async page => {
        const reports: unknown[] = [];
        page.on('console', entry => {
          if (entry.type() === 'log') console.log(entry.text());
        });
        const workloads = [
          { seconds: 600, expectedDistance: 3324.307288 },
          { seconds: 57 * 60, expectedDistance: 18885.557269 },
          { seconds: 8 * 60 * 60, expectedDistance: 159599.3071 },
        ];
        for (const { seconds, expectedDistance } of process.env.POWER_LOG_BROWSER_STRESS_SIZE === 'full'
          ? workloads.slice(1)
          : workloads.slice(0, 1)) {
          const result = await page.evaluate(
            async ({ seconds, expectedDistance, options }) => {
              Object.defineProperty(navigator, 'geolocation', {
                value: { watchPosition: () => 1, clearWatch: () => {} },
              });
              const storageBefore = await navigator.storage.estimate();
              const { recorder, source, setTime } = window.rideTests.createRecorder(),
                { store, monitor, syntheticSample } = window.rideTests;
              await source.connect();
              const ride = await recorder.start(options),
                count = seconds * 8,
                started = performance.now();
              for (let offset = 0; offset < count; offset += 64) {
                const end = Math.min(count, offset + 64);
                for (let seq = offset; seq < end; seq++) {
                  setTime(seq / 8);
                  source.sample(seq, Math.round(150 + 75 * Math.sin(seq / 100)), {
                    speedRaw: (seq % 400) / 10,
                    controllerSpeedMps: (seq % 400) / 10 / 3.6,
                    controllerModel: 'X6',
                    firmwareLabel: '20260101',
                    controllerProtocol: '5.3',
                  });
                }
                // The production recorder dispatches full batches immediately and partial ones at 250 ms.
                while ((await store.get(ride.id!)).telemetryCount < end) {
                  const state = await recorder.getState();
                  if (state.failure) throw new Error(state.failure.message);
                  await new Promise(resolve => setTimeout(resolve, 1));
                }
                if (end % 32768 === 0) console.log(`Browser stress ${seconds}s: ${end}/${count}`);
              }
              setTime(seconds);
              await recorder.stop();
              const captureMs = performance.now() - started;
              let after: [number, number] | undefined,
                exact = true,
                retained = 0,
                originalBytes = 0,
                maxRead = 0;
              while (true) {
                const rows = await store.page(ride.id!, 0, Infinity, after);
                maxRead = Math.max(maxRead, rows.length);
                for (const row of rows) {
                  const seq = retained++,
                    expected = {
                      ...syntheticSample(seq / 8, seq, new Date(Date.UTC(2026, 0, 1) + seq * 125).toISOString()),
                      connectionEpoch: source.connectionEpoch,
                      humanPowerW: Math.round(150 + 75 * Math.sin(seq / 100)),
                      speedRaw: (seq % 400) / 10,
                      controllerSpeedMps: (seq % 400) / 10 / 3.6,
                      controllerModel: 'X6',
                      firmwareLabel: '20260101',
                      controllerProtocol: '5.3',
                    };
                  exact &&=
                    Object.entries(expected).every(([key, value]) => row[key as keyof typeof row] === value) &&
                    row.originalSequence === seq &&
                    row.originalElapsedSeconds === seq / 8;
                  originalBytes += JSON.stringify(row).length;
                }
                if (rows.length < 256) break;
                const last = rows[rows.length - 1]!;
                after = [last.elapsedSeconds, last.sequence];
              }
              const chart = monitor(ride.id!),
                description = await chart.describeSource({ generation: 1 });
              let chartRows = 0;
              const originalPage = store.page.bind(store);
              store.page = async (...args) => {
                const rows = await originalPage(...args);
                chartRows += rows.length;
                return rows;
              };
              const query = {
                generation: 1,
                expectedRevision: description.revision,
                startSeconds: 1.1,
                endSeconds: seconds - 0.2,
                metrics: ['humanPowerW', 'motorInputPowerW', 'cadenceRpm', 'controllerSpeedMps'],
              };
              const chartStarted = performance.now(),
                plot = await chart.readPlot({ ...query, pixelWidth: 4 }),
                stats = await chart.rangeStats(query);
              const chartMs = performance.now() - chartStarted;
              store.page = originalPage;
              if (plot.status !== 'ok' || stats.status !== 'ok') throw new Error('Unexpected chart revision');
              const distanceStarted = performance.now(),
                detail = await recorder.read(ride.id!),
                distanceReadyMs = performance.now() - distanceStarted;
              const distanceQueryStarted = performance.now();
              for (let i = 0; i < 30; i++) {
                const base = { generation: 1, expectedRevision: description.revision, metrics: ['distanceMeters'] };
                const range = await chart.rangeStats({ ...base, startSeconds: i + 0.03, endSeconds: seconds - i });
                const cursor = await chart.inspectAt({ ...base, seconds: seconds / 2 + i + 0.03 });
                if (
                  range.status !== 'ok' ||
                  cursor.status !== 'ok' ||
                  range.statistics.distanceMeters?.distance === undefined ||
                  !cursor.points.distanceMeters
                )
                  throw new Error('Distance query failed');
              }
              const distanceQueryMs = performance.now() - distanceQueryStarted;
              const csvStarted = performance.now(),
                csv = await window.rideTests.telemetryCsv(ride.id!);
              const csvMs = performance.now() - csvStarted;
              let tileBytes = 0,
                tileCount = 0;
              await window.rideTests.browserTransaction(
                ['ride-tiles'],
                'readonly',
                tx =>
                  new Promise<void>((resolve, reject) => {
                    const req = tx.objectStore('ride-tiles').openCursor(IDBKeyRange.bound([ride.id], [ride.id, []]));
                    req.onerror = () => reject(req.error);
                    req.onsuccess = () => {
                      const cursor = req.result;
                      if (!cursor) {
                        resolve();
                        return;
                      }
                      tileCount++;
                      tileBytes += JSON.stringify(cursor.value).length;
                      cursor.continue();
                    };
                  }),
              );
              let distanceBytes = 0,
                distanceRows = 0;
              for (const name of ['distance-profiles', 'distance-intervals', 'distance-tiles'])
                await window.rideTests.browserTransaction(
                  [name],
                  'readonly',
                  tx =>
                    new Promise<void>((resolve, reject) => {
                      const range =
                        name === 'distance-profiles'
                          ? IDBKeyRange.only(ride.id!)
                          : IDBKeyRange.bound([ride.id], [ride.id, []]);
                      const req = tx.objectStore(name).openCursor(range);
                      req.onerror = () => reject(req.error);
                      req.onsuccess = () => {
                        const cursor = req.result;
                        if (!cursor) {
                          resolve();
                          return;
                        }
                        distanceRows++;
                        distanceBytes += JSON.stringify(cursor.value).length;
                        cursor.continue();
                      };
                    }),
                );
              const storageAfter = await navigator.storage.estimate();
              return {
                seconds,
                count,
                retained,
                exact,
                maxRead,
                captureMs,
                chartRows,
                chartMs,
                pointCount: Object.values(plot.series).reduce((sum, points) => sum + points.length, 0),
                statsCount: stats.statistics.humanPowerW!.count,
                originStorageBefore: storageBefore.usage,
                originStorageAfter: storageAfter.usage,
                originStorageDelta: (storageAfter.usage ?? 0) - (storageBefore.usage ?? 0),
                originalBytes,
                tileBytes,
                tileCount,
                distanceBytes,
                distanceRows,
                distanceReadyMs,
                distanceQueryMs,
                expectedDistance,
                actualDistance: detail.summary.distanceMeters,
                coveredSeconds: detail.summary.distance?.selected?.coveredSeconds,
                csvBytes: new TextEncoder().encode(csv).length,
                csvMs,
                timerSeconds: detail.summary.timerSeconds,
                sampleCount: (await store.get(ride.id!)).telemetryCount,
                originalCount: detail.metadata.eventCount,
              };
            },
            { seconds, expectedDistance, options },
          );
          console.log(`BROWSER_RIDE_STRESS ${JSON.stringify(result)}`);
          reports.push(result);
          if (process.env.POWER_LOG_BROWSER_STRESS_REPORT)
            writeFileSync(process.env.POWER_LOG_BROWSER_STRESS_REPORT, JSON.stringify(reports, null, 2));
          expect(result).toMatchObject({
            count: seconds * 8,
            retained: seconds * 8,
            exact: true,
            maxRead: 256,
            timerSeconds: seconds,
            sampleCount: seconds * 8,
            originalCount: String(seconds * 8 + 2),
          });
          expect(result.statsCount).toBe(seconds * 8 - 10);
          expect.soft(result.chartRows).toBeLessThanOrEqual(1024);
          expect.soft(result.pointCount).toBeLessThanOrEqual(4096);
          expect.soft(result.tileBytes).toBeLessThan(result.originalBytes);
          expect
            .soft(result.tileCount)
            .toBeLessThanOrEqual(TILE_SECONDS.reduce((count, size) => count + Math.ceil(seconds / size), 0));
          expect.soft(result.actualDistance).toBeCloseTo(result.expectedDistance, 7);
          expect.soft(result.coveredSeconds).toBe((seconds * 8 - 1) / 8);
        }
      }),
    600_000,
  );
});

it('rejects historical Delete of a interrupted target without a selection pointer', async () =>
  run(async page => {
    const result = await page.evaluate(async () => {
      const { store, createRecorder } = window.rideTests;
      const ride = await store.begin(
        'orphan',
        { indoor: true, recordGPS: false, sampleHz: 2 },
        '2026-01-01T00:00:00.000Z',
      );
      await store.interruptCurrent();
      await window.rideTests.browserTransaction(['ride-control'], 'readwrite', async tx => {
        await window.rideTests.idbRequest(tx.objectStore('ride-control').delete('current'));
      });
      const deletion = await store.remove(ride.id).then(
        () => 'deleted',
        error => error.message,
      );
      return { deletion, retained: await store.find(ride.id), recorder: Boolean(createRecorder) };
    });
    expect(result.deletion).not.toBe('deleted');
    expect(result.retained?.phase).toBe('interrupted');
  }));
