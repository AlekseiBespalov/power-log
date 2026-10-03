import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { writeFileSync } from 'node:fs';
import { chromium, type Browser, type BrowserContext, type Page } from '@playwright/test';
import { browserTestBundle } from '../helpers/browser-test-bundle';
import type * as Harness from '../fixtures/browser-ride-harness';
import { currentMonitorPoint } from '../../src/core/monitor';
import { TILE_SECONDS } from '../../src/services/browser-ride-store';
import { parseCsv } from '../support/csv';

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
  it.each([
    [4, "Power Log can't open rides saved by an earlier version. Clear this site's data to start over."],
    [6, 'Update Power Log to open your rides'],
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
  it.each(['2025-12-31T23:00:00.000Z', '2026-01-01T01:00:00.000Z'])(
    'retains the last capture checkpoint across reopen independently of UTC: %s',
    async cutoff =>
      run(async (page, context) => {
        const id = await page.evaluate(async cutoff => {
          const { store, browserRow } = window.rideTests;
          const record = await store.begin('checkpoint-token', true, '2026-01-01T00:00:00.000Z');
          await store.transition(record.id, 'checkpoint-token', 'pause', 1, 1, '2026-01-01T00:00:01.000Z');
          await store.transition(record.id, 'checkpoint-token', 'resume', 3, 1, '2026-01-01T00:00:03.000Z');
          await store.append(
            record.id,
            'checkpoint-token',
            [{ ...browserRow(4, 0), recordingId: record.id, timestamp: cutoff }],
            4,
            2,
            cutoff,
          );
          return record.id;
        }, cutoff);
        await page.close();
        const reopened = await pageIn(context);
        const result = await reopened.evaluate(async id => {
          const { recorder } = window.rideTests.createRecorder();
          const state = await recorder.getState();
          const record = await window.rideTests.store.get(id);
          const csv = await window.rideTests.telemetryCsv(id);
          return { state, record, csv, repeated: await window.rideTests.store.recoverOrphan() };
        }, id);
        expect(result.record).toMatchObject({
          interrupted: true,
          phase: 'completed',
          elapsedSeconds: 4,
          timerSeconds: 2,
          checkpointAt: cutoff,
          endedAt: cutoff,
          samples: 1,
        });
        expect(result.state.phase).toBe('completed');
        expect(result.repeated).toBeNull();
        expect(parseCsv(result.csv).samples).toMatchObject([{ elapsedSeconds: 4, timestamp: cutoff, run: 1 }]);
      }),
  );

  it('preserves committed-stop precedence on reopen', async () =>
    run(async (page, context) => {
      const id = await page.evaluate(async () => {
        const { store, browserRow } = window.rideTests;
        const record = await store.begin('stop-token', true, '2026-01-01T00:00:00.000Z');
        await store.append(
          record.id,
          'stop-token',
          [{ ...browserRow(1, 0), recordingId: record.id }],
          1,
          1,
          '2026-01-01T00:00:01.000Z',
        );
        await store.transition(record.id, 'stop-token', 'save', 2, 2, '2025-12-31T23:00:00.000Z');
        return record.id;
      });
      await page.close();
      const reopened = await pageIn(context);
      expect(
        await reopened.evaluate(
          async id => ({
            recovered: await window.rideTests.store.recoverOrphan(),
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

  it('persists hard boundaries and connection evidence at every tile level', async () =>
    run(async page => {
      const result = await page.evaluate(
        async sizes => {
          const { store, browserRow, monitor } = window.rideTests;
          const record = await store.begin('tile-token', true, '2026-01-01T00:00:00.000Z');
          const rows = [30, 31, 32, 33].map((time, sequence) => ({
            ...browserRow(time, sequence),
            recordingId: record.id,
            humanPowerW: 100,
            interruptionIndex: sequence < 2 ? 0 : 1,
          }));
          await store.append(record.id, 'tile-token', rows, 33, 33, rows[3]!.timestamp);
          const tiles = await Promise.all(sizes.map((_size, level) => store.tiles(record.id, level, 0, 3)));
          const source = monitor(record.id);
          const query = {
            generation: 1,
            expectedRevision: (await source.describeSource({ generation: 1 })).revision,
            metrics: ['humanPowerW'],
            startSeconds: 0,
            pixelWidth: 4,
          };
          const plots = await Promise.all(
            [34, ...sizes.map(size => size * 3)].map(endSeconds => source.readPlot({ ...query, endSeconds })),
          );
          return {
            tiles,
            plots,
            inspection: await source.inspectAt({ ...query, seconds: 31.5 }),
            stats: await source.rangeStats({ ...query, endSeconds: 65536 }),
          };
        },
        [...TILE_SECONDS],
      );
      for (const tiles of result.tiles) {
        const aggregates = tiles.map(tile => tile.stats.humanPowerW!);
        expect(aggregates[0]!.firstInterruptionIndex).toBe(0);
        expect(aggregates.at(-1)!.lastInterruptionIndex).toBe(1);
        expect(aggregates.flatMap(aggregate => aggregate.runs)).toHaveLength(2);
      }
      for (const plot of result.plots) {
        if (plot.status !== 'ok') throw new Error('Unexpected retry');
        expect(
          plot.series.humanPowerW!.filter(point => point.startsSegment).map(point => point.elapsedSeconds),
        ).toEqual([30, 32]);
      }
      expect(result.inspection).toMatchObject({ points: { humanPowerW: null } });
      expect(result.stats).toMatchObject({ statistics: { humanPowerW: { coveredSeconds: 2, integral: 200 } } });
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
        recordGPS: false,
        capabilities: {
          phoneWorkout: false,
          watchWorkout: false,
          phoneHealth: false,
          watchHealth: false,
          healthProvider: null,
          gps: false,
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
        eventCount: 5,
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

  it('rejects a foreign tab and recovers only after its owning document closes', async () =>
    run(async (page, context) => {
      const id = await page.evaluate(async options => {
        window.fixture = window.rideTests.createRecorder();
        await window.fixture.source.connect();
        const ride = await window.fixture.recorder.start(options);
        window.fixture.source.sample(1);
        window.fixture.setTime(1);
        await window.fixture.recorder.pause();
        return ride.id!;
      }, options);
      const other = await pageIn(context);
      const foreign = await other.evaluate(
        async ({ id, options }) => {
          window.fixture = window.rideTests.createRecorder();
          await window.fixture.source.connect();
          const state = await window.fixture.recorder.getState();
          const errors = await Promise.all(
            [
              window.fixture.recorder.start(options),
              window.fixture.recorder.pause(id),
              window.fixture.recorder.stop(id),
              window.fixture.recorder.remove(id),
            ].map(p =>
              p.then(
                () => '',
                e => e.message,
              ),
            ),
          );
          return { state, errors };
        },
        { id, options },
      );
      expect(foreign.state.pendingAction).toBe('anotherBrowserTab');
      expect(foreign.errors.every(Boolean)).toBe(true);
      await page.close();
      // Page closure can precede Web Locks cleanup in the browser process.
      await expect
        .poll(
          () =>
            other.evaluate(async () => {
              const { held } = await navigator.locks.query();
              return held?.some(lock => lock.name === window.rideTests.BROWSER_RECORDING_LOCK);
            }),
          { timeout: 3000 },
        )
        .toBe(false);
      const recovered = await other.evaluate(
        async id => ({
          state: await window.fixture.recorder.getState(),
          record: await window.rideTests.store.get(id),
          detail: await window.fixture.recorder.read(id),
          list: await window.fixture.recorder.list(),
        }),
        id,
      );
      expect(recovered.record).toMatchObject({
        id,
        phase: 'completed',
        interrupted: true,
        elapsedSeconds: 1,
        timerSeconds: 1,
        samples: 1,
        endedAt: '2026-01-01T00:00:01.000Z',
      });
      expect(recovered.state.pendingAction).toBeNull();
      expect(recovered.record.writer).toBeUndefined();
      expect(recovered.detail.metadata).toMatchObject({ id, interrupted: true, phase: 'completed', elapsedSeconds: 1 });
      expect(recovered.list.records[0]!.elapsedSeconds).toBe(1);
      expect(recovered.list.records.map(record => record.id)).toEqual([id]);
    }));

  it('rolls back originals, projections and metadata together and fences stale tokens', async () =>
    run(async page => {
      const result = await page.evaluate(async () => {
        const { store, browserRow } = window.rideTests,
          ride = await store.begin('owner-token', true, '2026-01-01T00:00:00.000Z');
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
        const countAfterFailure = (await store.get(ride.id)).samples,
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
          count: (await store.get(ride.id)).samples,
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

  it('halts on write failure, preserves the committed prefix, and recovers without counting unseen time', async () =>
    run(async page => {
      const result = await page.evaluate(async options => {
        const { recorder, source, setTime } = window.rideTests.createRecorder();
        await source.connect();
        const ride = await recorder.start(options);
        source.sample(0);
        setTime(1);
        await recorder.pause();
        await recorder.resume();
        const original = window.rideTests.store.append;
        window.rideTests.store.append = async () => {
          throw new DOMException('quota', 'QuotaExceededError');
        };
        setTime(2);
        source.sample(1);
        await new Promise(resolve => setTimeout(resolve, 350));
        const failure = await recorder.getState();
        setTime(900);
        window.rideTests.store.append = original;
        const recovered = await recorder.stop();
        return { failure, recovered, record: await window.rideTests.store.get(ride.id!) };
      }, options);
      expect(result.failure.phase).toBe('recoverable');
      expect(result.failure.error).toContain('quota');
      expect(result.record).toMatchObject({
        samples: 1,
        interrupted: true,
        elapsedSeconds: 1,
        timerSeconds: 1,
        endedAt: '2026-01-01T00:00:01.000Z',
        checkpointAt: '2026-01-01T00:00:01.000Z',
      });
      expect(result.recovered.phase).toBe('completed');
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

  it('uses indexed chart reads with exact extrema, A/B identity, gaps, clipped integration and bounded geometry', async () =>
    run(async page => {
      const result = await page.evaluate(async () => {
        const { store, browserRow, monitor } = window.rideTests,
          ride = await store.begin('token', true, '2026-01-01T00:00:00.000Z');
        for (let offset = 0; offset < 1600; offset += 128) {
          const rows = Array.from({ length: Math.min(128, 1600 - offset) }, (_, i) => {
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
        const plot = await source.readPlot({ ...query, pixelWidth: 100 });
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
      expect(result.plot.series.humanPowerW!.length).toBeLessThanOrEqual(408);
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
          ride = await store.begin('token', true, '2026-01-01T00:00:00.000Z');
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
          metrics: description.availableMetrics.filter(id => id !== 'distanceMeters'),
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
        for (const [metric, stats] of Object.entries(value.statistics))
          expect(stats.coveredSeconds, `${key}: ${metric}`).toBeCloseTo(covered);
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
      expect(result.gap.statistics).toEqual({});
    }));

  it.each([
    { times: [0, 1, 8, 9, 16, 17], values: [0, 100, 50, 51, 0, 100], end: 256, starts: [0, 8, 16] },
    { times: [62, 63, 64, 65, 72, 73], values: [0, 100, 50, 51, 0, 100], end: 192, starts: [62, 72] },
  ])('retains every continuous run in wide tiles ending at $end', async ({ times, values, end, starts }) =>
    run(async page => {
      const result = await page.evaluate(
        async ({ times, values, end }) => {
          const { store, browserRow, monitor } = window.rideTests,
            ride = await store.begin('token', true, '2026-01-01T00:00:00.000Z');
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

  it('bounds a stalled writer queue and clearly retains only its committed prefix', async () =>
    run(async page => {
      const result = await page.evaluate(async options => {
        const { recorder, source, setTime } = window.rideTests.createRecorder(),
          { store } = window.rideTests;
        await source.connect();
        const started = await recorder.start(options),
          original = store.append.bind(store);
        let release!: () => void;
        const stalled = new Promise<void>(resolve => {
          release = resolve;
        });
        store.append = async (...args) => {
          await stalled;
          return original(...args);
        };
        for (let seq = 0; seq < 700; seq++) {
          setTime(seq / 8);
          source.sample(seq);
        }
        const stopped = await recorder.getState();
        release();
        while ((await store.get(started.id!)).samples < 128) await new Promise(resolve => setTimeout(resolve, 1));
        await new Promise(resolve => setTimeout(resolve, 5));
        store.append = original;
        const recovered = await recorder.stop();
        return { stopped, recovered, record: await store.get(started.id!) };
      }, options);
      expect(result.stopped.phase).toBe('recoverable');
      expect(result.stopped.error).toMatch(/could not keep up/);
      expect(result.record).toMatchObject({ samples: 128, interrupted: true, elapsedSeconds: 127 / 8 });
      expect(result.recovered.phase).toBe('completed');
    }));

  it('keeps saved records and charts readable without capture capability', async () =>
    run(async page => {
      const result = await page.evaluate(async () => {
        const { store, browserRow, monitor, createRecorder } = window.rideTests;
        const record = await store.begin('owner', true, '2026-01-01T00:00:00.000Z');
        const rows = Array.from({ length: 80 }, (_, sequence) => ({
          ...browserRow(sequence / 8, sequence),
          recordingId: record.id,
        }));
        await store.append(record.id, 'owner', rows, 79 / 8, 79 / 8, rows.at(-1)!.timestamp);
        await store.transition(record.id, 'owner', 'save', 20, 20, '2026-01-01T00:00:20.000Z');
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
      expect(result.failure).toMatch(/Web Locks/);
      expect(result.records.records).toHaveLength(1);
      expect(result.records.records[0]).toMatchObject({ storage: 'browser', saveToHealth: false });
      if (result.stats.status !== 'ok') throw new Error('Unexpected retry');
      expect(result.stats.statistics.humanPowerW?.count).toBe(80);
      expect(result.unchanged?.originalSequence).toBe(0);
    }));

  it.runIf(process.env.POWER_LOG_BROWSER_STRESS === '1')(
    'retains all 57-minute and eight-hour 8 Hz originals with bounded chart work',
    async () =>
      run(async page => {
        const reports: unknown[] = [];
        page.on('console', entry => {
          if (entry.type() === 'log') console.log(entry.text());
        });
        for (const seconds of [57 * 60, 8 * 60 * 60]) {
          const result = await page.evaluate(
            async ({ seconds, options }) => {
              const storageBefore = await navigator.storage.estimate();
              const { recorder, source, setTime } = window.rideTests.createRecorder(),
                { store, monitor, syntheticSample } = window.rideTests;
              await source.connect();
              const ride = await recorder.start(options),
                count = seconds * 8,
                started = performance.now();
              for (let offset = 0; offset < count; offset += 128) {
                const end = Math.min(count, offset + 128);
                for (let seq = offset; seq < end; seq++) {
                  setTime(seq / 8);
                  source.sample(seq, Math.round(150 + 75 * Math.sin(seq / 100)), {
                    speedRaw: (seq % 400) / 10,
                    controllerSpeedMps: (seq % 400) / 36,
                    controllerModel: 'X6',
                    firmwareLabel: '20260101',
                    controllerProtocol: '5.3',
                  });
                }
                // The production recorder dispatches full batches immediately and partial ones at 250 ms.
                while ((await store.get(ride.id!)).samples < end) {
                  const state = await recorder.getState();
                  if (state.error) throw new Error(state.error);
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
                      humanPowerW: Math.round(150 + 75 * Math.sin(seq / 100)),
                      speedRaw: (seq % 400) / 10,
                      controllerSpeedMps: (seq % 400) / 36,
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
              let expectedDistance = 0;
              for (let i = 1; i < count; i++) expectedDistance += (((i - 1) % 400) + (i % 400)) / 36 / 2 / 8;
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
                sampleCount: detail.metadata.eventCount,
              };
            },
            { seconds, options },
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
          });
          expect(result.statsCount).toBe(seconds * 8 - 10);
          expect(result.chartRows).toBeLessThanOrEqual(1024);
          expect(result.pointCount).toBeLessThanOrEqual(4096);
          expect(result.tileBytes).toBeLessThan(result.originalBytes);
          expect(result.actualDistance).toBeCloseTo(result.expectedDistance, 7);
          expect(result.coveredSeconds).toBe((seconds * 8 - 1) / 8);
        }
      }),
    600_000,
  );
});
