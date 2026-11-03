import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { chromium, type Browser, type Page } from '@playwright/test';
import { browserTestBundle } from '../helpers/browser-test-bundle';
import type * as Harness from '../fixtures/browser-ride-harness';
import exactKeys from '../fixtures/contract/storage/exact-index-keys.json';
import { TILE_SECONDS } from '../../src/services/browser-ride-store';
declare global {
  interface Window {
    distanceTests: typeof Harness;
  }
}
const bundle = browserTestBundle('tests/fixtures/browser-ride-harness.ts', 'distanceTests');
let browser: Browser;
beforeAll(async () => {
  browser = await chromium.launch({ headless: true });
});
afterAll(async () => {
  await browser?.close();
});
async function run<T>(work: (page: Page) => Promise<T>) {
  const context = await browser.newContext();
  try {
    const page = await context.newPage();
    await page.route('http://127.0.0.1:43120/**', route =>
      route.fulfill({ contentType: 'text/html', body: '<!doctype html><title>Distance store</title>' }),
    );
    await page.goto('http://127.0.0.1:43120/');
    await page.addScriptTag({ content: bundle });
    return await work(page);
  } finally {
    await context.close();
  }
}

describe('controller distance with real IndexedDB', () => {
  it('graphs the first moving frame before any distance interval exists', async () =>
    run(async page => {
      const result = await page.evaluate(async () => {
        const api = window.distanceTests;
        const ride = await api.store.begin(
          'owner',
          { indoor: true, recordGPS: false, sampleHz: 4, distanceSource: 'controller' },
          '2026-01-01T00:00:00Z',
        );
        const row = {
          ...api.browserRow(1, 1, '2026-01-01T00:00:01Z'),
          recordingId: ride.id,
          humanPowerW: 180,
          motorInputPowerW: 1800,
          batteryVoltageV: 50,
          batteryCurrentA: 36,
          controllerSpeedMps: 5,
          speedRaw: 18,
          controllerModel: 'X6',
          controllerProtocol: '5.3',
          firmwareLabel: '20260101',
        };
        await api.store.append(ride.id, 'owner', [row], 1, 1, row.timestamp);
        const monitor = api.monitor(ride.id);
        await api.ensureBrowserDistance(ride.id);
        return api.readSettledLatest(monitor, {
          generation: 1,
          metrics: ['batteryWhPerKm', 'riderWhPerKm'],
        });
      });
      if (result.status !== 'ok') throw new Error('Unexpected retry');
      expect(result.points.batteryWhPerKm?.value).toBeCloseTo(100);
      expect(result.points.riderWhPerKm?.value).toBeCloseTo(10);
      expect(result.averages?.batteryWhPerKm).toBeNull();
    }));

  it('charts selected battery consumption and rider work with exact A–B averages', async () =>
    run(async page => {
      const result = await page.evaluate(async () => {
        const api = window.distanceTests;
        const ride = await api.store.begin(
          'owner',
          {
            indoor: true,
            recordGPS: false,
            sampleHz: 4,
            distanceSource: 'controller',
            batteryConsumptionSource: 'calculated',
          },
          '2026-01-01T00:00:00Z',
        );
        const rows = Array.from({ length: 121 }, (_, t) => ({
          ...api.browserRow(t, t, new Date(Date.UTC(2026, 0, 1) + t * 1000).toISOString()),
          recordingId: ride.id,
          humanPowerW: 180,
          controllerSpeedMps: t <= 60 ? 5 : 10,
          speedRaw: t <= 60 ? 18 : 36,
          controllerModel: 'X6',
          controllerProtocol: '5.3',
          firmwareLabel: '20260101',
          batteryVoltageV: 50,
          batteryCurrentA: 36,
          motorInputPowerW: 1800,
          consumedWh: 100 + 2 * t,
        }));
        for (let offset = 0; offset < rows.length; offset += 64) {
          const batch = rows.slice(offset, offset + 64);
          const last = batch.at(-1)!;
          await api.store.append(ride.id, 'owner', batch, last.elapsedSeconds, last.elapsedSeconds, last.timestamp);
        }
        const monitor = api.monitor(ride.id);
        await api.ensureBrowserDistance(ride.id);
        const description = await monitor.describeSource({ generation: 1 });
        const request = {
          generation: 1,
          expectedRevision: description.revision,
          metrics: ['batteryWhPerKm', 'riderWhPerKm'],
          startSeconds: 0,
          endSeconds: 120,
          buckets: 64,
        };
        return {
          description,
          plot: await monitor.readPlot(request),
          stats: await monitor.rangeStats({ ...request, includeEndpoints: true }),
          inspect: await monitor.inspectAt({ ...request, seconds: 30 }),
          instant: await monitor.inspectAt({ ...request, seconds: 61 }),
          smooth: await monitor.inspectAt({ ...request, seconds: 61, energySmoothingSeconds: 5 }),
          longer: await Promise.all(
            ([30, 60] as const).map(energySmoothingSeconds =>
              monitor.inspectAt({ ...request, seconds: 61, energySmoothingSeconds }),
            ),
          ),
          latest: await api.readSettledLatest(monitor, request),
          partial: await monitor.rangeStats({ ...request, startSeconds: 60, endSeconds: 120 }),
        };
      });
      expect(result.description.availableMetrics).toEqual(expect.arrayContaining(['batteryWhPerKm', 'riderWhPerKm']));
      if (
        result.plot.status !== 'ok' ||
        result.stats.status !== 'ok' ||
        result.inspect.status !== 'ok' ||
        result.instant.status !== 'ok' ||
        result.smooth.status !== 'ok' ||
        result.latest.status !== 'ok' ||
        result.partial.status !== 'ok'
      )
        throw new Error('Unexpected retry');
      expect(result.instant.points.batteryWhPerKm?.value).toBe(50);
      expect(result.smooth.points.batteryWhPerKm?.value).toBeCloseTo(95, 8);
      result.longer.forEach((value, index) => {
        if (value.status !== 'ok') throw new Error('Unexpected retry');
        expect(value.points.batteryWhPerKm?.value).toBeCloseTo(100 - 25 / [30, 60][index]!, 8);
      });
      expect(result.latest.averages?.batteryWhPerKm?.value).toBeCloseTo(60 / 0.8975, 8);
      expect(result.latest.points.batteryWhPerKm?.value).toBe(50);
      expect(result.stats.statistics.batteryWhPerKm?.ratio).toBeCloseTo(60 / 0.8975, 8);
      expect(result.stats.statistics.riderWhPerKm?.ratio).toBeCloseTo(6 / 0.8975, 8);
      expect(result.partial.statistics.batteryWhPerKm?.ratio).toBeCloseTo(30 / 0.5975, 8);
      expect(result.inspect.points.batteryWhPerKm?.value).toBeCloseTo(100, 8);
      expect(result.inspect.points.riderWhPerKm?.value).toBeCloseTo(10, 8);
      expect(result.plot.series.batteryWhPerKm?.some(point => point.value < 50.3)).toBe(true);
      expect(result.plot.series.batteryWhPerKm?.some(point => point.value > 99.9)).toBe(true);
    }));

  it('keeps consumption unavailable outside observed intervals while admitting a valid zero baseline', async () =>
    run(async page => {
      const result = await page.evaluate(async () => {
        const api = window.distanceTests;
        const ride = await api.store.begin(
          'owner',
          { indoor: true, recordGPS: false, sampleHz: 8 },
          '2026-01-01T00:00:00Z',
        );
        await api.store.append(
          ride.id,
          'owner',
          [{ ...api.browserRow(10, 0, '2026-01-01T00:00:10Z'), recordingId: ride.id }],
          10,
          10,
          '2026-01-01T00:00:10Z',
        );
        await api.store.transition(ride.id, 'owner', 'stop', 20, 20, '2026-01-01T00:00:20Z');
        const monitor = api.monitor(ride.id);
        const description = await monitor.describeSource({ generation: 1 });
        return Promise.all(
          [
            [0, 5],
            [12, 15],
            [10, 10],
            [9, 11],
          ].map(([startSeconds, endSeconds]) =>
            monitor.rangeStats({
              generation: 1,
              expectedRevision: description.revision,
              metrics: ['controllerEnergyWh', 'calculatedEnergyWh'],
              startSeconds: startSeconds!,
              endSeconds: endSeconds!,
            }),
          ),
        );
      });
      for (const [index, value] of result.entries()) {
        if (value.status !== 'ok') throw new Error('Unexpected retry');
        for (const metric of ['controllerEnergyWh', 'calculatedEnergyWh']) {
          expect(value.statistics[metric]?.amount).toBe(index < 2 ? undefined : 0);
          expect(value.statistics[metric]?.coveredSeconds).toBe(0);
        }
      }
    }));
  it('excludes an ordinary Pause endpoint consistently from incremental charts and derived ZIP', async () =>
    run(async page => {
      const result = await page.evaluate(async () => {
        const api = window.distanceTests;
        const ride = await api.store.begin(
          'owner',
          { indoor: true, recordGPS: false, sampleHz: 8 },
          '2026-01-01T00:00:00Z',
        );
        const append = async (t: number, sequence: number, wh: number) => {
          const row = {
            ...api.browserRow(t, sequence, new Date(Date.UTC(2026, 0, 1) + t * 1000).toISOString()),
            recordingId: ride.id,
            consumedWh: wh,
            consumedAh: wh / 10,
            batteryVoltageV: 50,
            batteryCurrentA: 10,
            motorInputPowerW: 500,
          };
          await api.store.append(ride.id, 'owner', [row], t, t < 4 ? t : t - 2, row.timestamp);
          await api.ensureBrowserDistance(ride.id);
        };
        await append(0, 0, 100);
        await append(1, 1, 101);
        await append(2, 2, 102);
        await api.store.transition(ride.id, 'owner', 'pause', 2, 2, '2026-01-01T00:00:02Z');
        await api.ensureBrowserDistance(ride.id);
        await api.store.transition(ride.id, 'owner', 'resume', 4, 2, '2026-01-01T00:00:04Z');
        await append(4, 3, 110);
        await append(5, 4, 111);
        await api.store.transition(ride.id, 'owner', 'stop', 5, 3, '2026-01-01T00:00:05Z');
        return {
          latest: await api
            .monitor(ride.id)
            .readLatest({ generation: 1, metrics: ['controllerEnergyWh', 'calculatedEnergyWh'] }),
          csv: await api.consumptionCsv(ride.id),
        };
      });
      if (result.latest.status !== 'ok') throw new Error('Unexpected retry');
      expect(result.latest.points.controllerEnergyWh?.value).toBe(2);
      expect(result.latest.points.calculatedEnergyWh?.value).toBeCloseTo(1000 / 3600, 10);
      const lines = result.csv
        .trim()
        .split('\n')
        .map(line => line.split(','));
      expect(lines[3]?.slice(2)).toEqual(['', '', '', '']);
      expect(Number(lines.at(-1)?.[2])).toBe(2);
      expect(Number(lines.at(-1)?.[4])).toBeCloseTo(1000 / 3600, 10);
    }));
  it('queries both distance sources and four ride battery totals, preserving raw counters in export', async () =>
    run(async page => {
      const result = await page.evaluate(async () => {
        const api = window.distanceTests;
        const ride = await api.store.begin(
          'owner',
          {
            indoor: false,
            recordGPS: true,
            sampleHz: 8,
            distanceSource: 'controller',
            batteryConsumptionSource: 'calculated',
          },
          '2026-01-01T00:00:00Z',
        );
        const row = (n: number) => ({
          ...api.browserRow(n, n, new Date(Date.UTC(2026, 0, 1) + n * 1000).toISOString()),
          recordingId: ride.id,
          controllerSpeedMps: 5,
          speedRaw: 18,
          controllerModel: 'X6',
          controllerProtocol: '5.3',
          firmwareLabel: '20260101',
          consumedWh: 100 + n * 0.5,
          consumedAh: 10 + n * 0.01,
          batteryVoltageV: 50,
          batteryCurrentA: 10,
          motorInputPowerW: 500,
        });
        const metrics = [
          'distanceMeters',
          'gpsDistanceMeters',
          'controllerDistanceMeters',
          'controllerEnergyWh',
          'controllerChargeAh',
          'calculatedEnergyWh',
          'calculatedChargeAh',
        ];
        await api.store.append(ride.id, 'owner', [row(0)], 0, 0, row(0).timestamp);
        const monitor = api.monitor(ride.id);
        const initial = await monitor.readLatest({ generation: 1, metrics });
        await api.store.append(ride.id, 'owner', [row(1), row(2)], 2, 2, row(2).timestamp);
        await api.store.appendLocations(
          ride.id,
          'owner',
          [0, 1, 2].map(n => ({
            recordingId: ride.id,
            eventId: crypto.randomUUID(),
            producer: 'browser' as const,
            producerSequence: String(n + 2),
            elapsedUs: api.secondsUs(n),
            queryUs: api.timestampUs(row(n).timestamp),
            timestamp: row(n).timestamp,
            originalTimestamp: row(n).timestamp,
            elapsedSeconds: n,
            originalElapsedSeconds: n,
            acquiredAtSeconds: n,
            clockEpoch: 'gps',
            active: true,
            interval: 0,
            distanceBarrier: false,
            latitude: 0,
            longitude: n * 0.00001,
            horizontalAccuracyM: 5,
            speedMps: 2,
            originalAltitudeDatum: 'unknown' as const,
          })),
          2,
          2,
          row(2).timestamp,
        );
        await api.store.transition(ride.id, 'owner', 'stop', 2, 2, row(2).timestamp);
        const latest = await monitor.readLatest({ generation: 2, metrics });
        const description = await monitor.describeSource({ generation: 2 });
        const request = {
          generation: 2,
          expectedRevision: description.revision,
          metrics,
          startSeconds: 0,
          endSeconds: 2,
        };
        const plot = await monitor.readPlot(request);
        const stats = await monitor.rangeStats(request);
        const clipped = await monitor.rangeStats({ ...request, startSeconds: 0.5, endSeconds: 1.5 });
        const inspected = await monitor.inspectAt({ ...request, seconds: 1 });
        const changes = await monitor.changesSince({ generation: 2, sinceRevision: initial.revision });
        const csv = await api.consumptionCsv(ride.id);
        const raw = await api.telemetryCsv(ride.id);
        const detail = await api.createRecorder().recorder.read(ride.id);
        // Disposable projections must replay to identical totals from original rows.
        await api.browserTransaction(
          ['distance-profiles', 'distance-intervals', 'distance-tiles'],
          'readwrite',
          async tx => {
            for (const store of ['distance-profiles', 'distance-intervals', 'distance-tiles'])
              await api.idbRequest(tx.objectStore(store).clear());
          },
        );
        const rebuilt = await api.monitor(ride.id).readLatest({ generation: 3, metrics });
        const next = await api.store.begin('owner', { indoor: true, recordGPS: false, sampleHz: 8 }, row(2).timestamp);
        await api.store.append(next.id, 'owner', [{ ...row(0), recordingId: next.id }], 0, 0, row(2).timestamp);
        const nextLatest = await api.monitor(next.id).readLatest({ generation: 4, metrics });
        return {
          initial,
          latest,
          description,
          plot,
          stats,
          clipped,
          inspected,
          changes,
          csv,
          raw,
          detail,
          rebuilt,
          nextLatest,
        };
      });
      for (const metric of ['controllerEnergyWh', 'controllerChargeAh', 'calculatedEnergyWh', 'calculatedChargeAh']) {
        expect(result.initial.status).toBe('ok');
        if (result.initial.status === 'ok') expect(result.initial.points[metric]?.value).toBe(0);
        if (result.nextLatest.status === 'ok') expect(result.nextLatest.points[metric]?.value).toBe(0);
      }
      expect(result.description.availableMetrics).toEqual(
        expect.arrayContaining(['gpsDistanceMeters', 'controllerDistanceMeters']),
      );
      expect(result.latest.status).toBe('ok');
      if (
        result.latest.status !== 'ok' ||
        result.rebuilt.status !== 'ok' ||
        result.stats.status !== 'ok' ||
        result.clipped.status !== 'ok' ||
        result.plot.status !== 'ok' ||
        result.inspected.status !== 'ok'
      )
        throw new Error('Unexpected retry');
      expect(result.latest.points.controllerDistanceMeters?.value).toBe(10);
      expect(result.latest.points.gpsDistanceMeters?.value).toBeGreaterThan(2);
      expect(result.latest.points.gpsDistanceMeters?.value).toBeLessThan(3);
      expect(result.latest.points.distanceMeters?.value).toBe(10);
      for (const [metric, amount] of Object.entries({
        controllerEnergyWh: 1,
        controllerChargeAh: 0.02,
        calculatedEnergyWh: 1000 / 3600,
        calculatedChargeAh: 20 / 3600,
      })) {
        expect(result.latest.points[metric]?.value).toBeCloseTo(amount, 8);
        expect(result.rebuilt.points[metric]?.value).toBeCloseTo(amount, 8);
        expect(result.plot.series[metric]?.at(-1)?.value).toBeCloseTo(amount, 8);
        expect(result.stats.statistics[metric]?.amount).toBeCloseTo(amount, 8);
        expect(result.stats.statistics[metric]?.coveredSeconds).toBe(2);
        expect(result.inspected.points[metric]?.value).toBeCloseTo(amount / 2, 8);
      }
      expect(result.clipped.statistics.controllerEnergyWh).toMatchObject({ amount: 0, partial: true });
      expect(result.clipped.statistics.calculatedEnergyWh?.amount).toBeCloseTo(500 / 3600, 8);
      expect(result.detail.summary?.batteryConsumption).toMatchObject({ source: 'calculated', partial: false });
      expect(result.changes.changes.length).toBeGreaterThan(0);
      const lines = result.csv.trim().split('\n');
      expect(lines[0]).toContain('controllerEnergyWh,controllerChargeAh,calculatedEnergyWh,calculatedChargeAh');
      expect(lines.at(-1)?.split(',').slice(2).map(Number)).toEqual([1, 0.02, 1000 / 3600, 20 / 3600]);
      const raw = result.raw
        .trim()
        .split('\n')
        .map(line => line.split(','));
      expect(Number(raw[1]![raw[0]!.indexOf('consumedWh')])).toBe(100);
    }));
  it('replays only the crossing suffix when Pause shares the latest sample time', async () =>
    run(async page => {
      const result = await page.evaluate(async () => {
        const api = window.distanceTests;
        const seed = async (warm: boolean) => {
          const ride = await api.store.begin(
            'owner',
            { indoor: true, recordGPS: false, sampleHz: 2 },
            '2026-01-01T00:00:00Z',
          );
          for (let sequence = 0; sequence < 20; sequence++) {
            const row = {
              ...api.browserRow(sequence, sequence),
              recordingId: ride.id,
              controllerSpeedMps: 10,
              speedRaw: 36,
              controllerModel: 'X6',
              controllerProtocol: '5.3',
              firmwareLabel: '20260101',
              connectionEpoch: 'one',
            };
            await api.store.append(ride.id, 'owner', [row], sequence, sequence, row.timestamp);
            if (warm) await api.ensureBrowserDistance(ride.id);
          }
          await api.store.transition(ride.id, 'owner', 'pause', 19, 19, '2026-01-01T00:00:19Z');
          const page = api.store.analysisPage.bind(api.store);
          let samples = 0;
          api.store.analysisPage = async (...args) => {
            const rows = await page(...args);
            if (args[0] === 'samples') samples += rows.length;
            return rows;
          };
          const counts = { intervalReads: 0, intervalWrites: 0, tileProjections: 0 };
          const open = IDBObjectStore.prototype.openCursor;
          const getAll = IDBObjectStore.prototype.getAll;
          const put = IDBObjectStore.prototype.put;
          IDBObjectStore.prototype.openCursor = function (...args) {
            const request = open.apply(this, args);
            if (this.name === 'distance-intervals')
              request.addEventListener('success', () => {
                if (request.result) counts.intervalReads++;
              });
            return request;
          };
          IDBObjectStore.prototype.getAll = function (...args) {
            const request = getAll.apply(this, args);
            if (this.name === 'distance-intervals')
              request.addEventListener('success', () => {
                counts.intervalReads += request.result.length;
              });
            return request;
          };
          IDBObjectStore.prototype.put = function (...args) {
            if (this.name === 'distance-intervals') counts.intervalWrites++;
            if (this.name === 'distance-tiles') counts.tileProjections++;
            return put.apply(this, args);
          };
          let handle;
          try {
            handle = await api.ensureBrowserDistance(ride.id);
          } finally {
            api.store.analysisPage = page;
            IDBObjectStore.prototype.openCursor = open;
            IDBObjectStore.prototype.getAll = getAll;
            IDBObjectStore.prototype.put = put;
          }
          await api.store.transition(ride.id, 'owner', 'stop', 19, 19, '2026-01-01T00:00:19Z');
          return {
            samples,
            counts,
            stats: await api.browserDistanceStats(handle, 0, 19),
            points: (await api.plotBrowserDistance(handle, 0, 19, 100)).map(point => [
              point.elapsedSeconds,
              point.value,
            ]),
          };
        };
        return { warm: await seed(true), cold: await seed(false) };
      });
      expect(result.warm.stats).toEqual(result.cold.stats);
      expect(result.warm.points).toEqual(result.cold.points);
      expect(result.warm.counts.intervalReads).toBeLessThanOrEqual(8);
      expect(result.warm.counts.intervalWrites).toBeLessThanOrEqual(1);
      expect(result.warm.counts.tileProjections).toBeLessThanOrEqual(2 * TILE_SECONDS.length);
      expect(result.cold.counts.intervalWrites).toBeGreaterThan(1);
      expect(result.warm.samples).toBe(1);
      expect(result.cold.samples).toBe(20);
    }));

  it('reuses interval and tile prefixes through Pause, Resume and a later append', async () =>
    run(async page => {
      const result = await page.evaluate(async () => {
        const api = window.distanceTests;
        const seed = async (warm: boolean) => {
          const ride = await api.store.begin(
            'owner',
            { indoor: true, recordGPS: false, sampleHz: 2 },
            '2026-01-01T00:00:00Z',
          );
          const append = async (sequence: number, time: number, timer: number) => {
            const row = {
              ...api.browserRow(time, sequence),
              recordingId: ride.id,
              controllerSpeedMps: 10,
              speedRaw: 36,
              controllerModel: 'X6',
              controllerProtocol: '5.3',
              firmwareLabel: '20260101',
              connectionEpoch: 'one',
            };
            await api.store.append(ride.id, 'owner', [row], time, timer, row.timestamp);
          };
          await append(0, 0, 0);
          await append(1, 1, 1);
          const old = warm ? await api.ensureBrowserDistance(ride.id) : undefined;
          const release = old ? await api.retainBrowserDistance(old) : undefined;
          await api.store.transition(ride.id, 'owner', 'pause', 2, 2, '2026-01-01T00:00:02Z');
          if (warm) await api.ensureBrowserDistance(ride.id);
          await api.store.transition(ride.id, 'owner', 'resume', 3, 2, '2026-01-01T00:00:03Z');
          if (warm) await api.ensureBrowserDistance(ride.id);
          await append(2, 3, 2);
          await append(3, 4, 3);
          const handle = await api.ensureBrowserDistance(ride.id);
          const oldStats = old ? await api.browserDistanceStats(old, 0, 1) : undefined;
          await release?.();
          const intervals = await api.browserTransaction(['distance-intervals'], 'readonly', tx =>
            api.idbRequest<number>(
              tx
                .objectStore('distance-intervals')
                .count(IDBKeyRange.bound([ride.id, 'controller'], [ride.id, 'controller', []])),
            ),
          );
          const result = {
            oldStats,
            intervals,
            stats: await api.browserDistanceStats(handle, 0, 4),
            points: (await api.plotBrowserDistance(handle, 0, 4, 100)).map(point => [
              point.elapsedSeconds,
              point.value,
              point.startsSegment,
            ]),
          };
          const stopped = await api.store.transition(ride.id, 'owner', 'stop', 4, 3, '2026-01-01T00:00:04Z');
          return { ...result, phase: stopped.phase, current: await api.store.current() };
        };
        return { warm: await seed(true), cold: await seed(false) };
      });
      expect(result.warm).toMatchObject({ phase: 'completed', current: null });
      expect(result.cold).toMatchObject({ phase: 'completed', current: null });
      expect(result.warm.oldStats?.distance).toBe(10);
      expect(result.warm.stats?.distance).toBe(20);
      expect(result.warm.stats).toEqual(result.cold.stats);
      expect(result.warm.points).toEqual(result.cold.points);
      expect(result.warm.intervals).toBe(2);
      expect(result.cold.intervals).toBe(2);
    }));

  it('prunes redundant profiles while retained handles and lifecycle checkpoints stay readable', async () =>
    run(async page => {
      const result = await page.evaluate(async () => {
        const api = window.distanceTests;
        const ride = await api.store.begin(
          'owner',
          { indoor: true, recordGPS: false, sampleHz: 2 },
          '2026-01-01T00:00:00Z',
        );
        const append = async (n: number) => {
          const row = {
            ...api.browserRow(n, n),
            recordingId: ride.id,
            speedRaw: 36,
            controllerSpeedMps: 10,
            controllerModel: 'X6',
            controllerProtocol: '5.3',
            firmwareLabel: '20260101',
            connectionEpoch: 'one',
          };
          await api.store.append(ride.id, 'owner', [row], n, n, row.timestamp);
          return api.ensureBrowserDistance(ride.id);
        };
        await append(0);
        const retained = await append(1);
        const release = await api.retainBrowserDistance(retained);
        for (let n = 2; n < 20; n++) await append(n);
        const open = IDBIndex.prototype.openCursor;
        let sampleReads = 0;
        IDBIndex.prototype.openCursor = function (...args) {
          const request = open.apply(this, args);
          if (this.objectStore.name === 'samples')
            request.addEventListener('success', () => {
              if (request.result) sampleReads++;
            });
          return request;
        };
        const work: number[] = [];
        for (const [action, time] of [
          ['lap', 19.25],
          ['pause', 19.5],
          ['resume', 20],
        ] as const) {
          await api.store.transition(
            ride.id,
            'owner',
            action,
            time,
            Math.min(time, 19.5),
            new Date(Date.UTC(2026, 0, 1) + time * 1000).toISOString(),
          );
          sampleReads = 0;
          const handle = await api.ensureBrowserDistance(ride.id);
          if (handle.info.selected?.distanceMeters !== 190) throw new Error('Distance changed');
          work.push(sampleReads);
        }
        IDBIndex.prototype.openCursor = open;
        const oldStats = await api.browserDistanceStats(retained, 0, 1);
        const profiles = await api.browserTransaction(['distance-profiles'], 'readonly', tx =>
          api.idbRequest<{ generation: string }[]>(tx.objectStore('distance-profiles').getAll()),
        );
        await release();
        const generations = [...new Set(profiles.map(profile => profile.generation))];
        return {
          oldStats,
          profiles: generations.map(generation => profiles.filter(profile => profile.generation === generation).length),
          work,
        };
      });
      expect(result.profiles).toHaveLength(2);
      expect(result.profiles.every(count => count <= 12)).toBe(true);
      expect(result.oldStats?.distance).toBe(10);
      expect(result.work).toEqual([0, 0, 0]);
    }));
  it('isolates an uncached older pin from a newer completed generation', async () =>
    run(async page => {
      const result = await page.evaluate(async () => {
        const api = window.distanceTests;
        const ride = await api.store.begin(
          'owner',
          { indoor: true, recordGPS: false, sampleHz: 2 },
          '2026-01-01T00:00:00Z',
        );
        const append = async (sequence: number) => {
          const row = {
            ...api.browserRow(sequence, sequence),
            recordingId: ride.id,
            speedRaw: 36,
            controllerSpeedMps: 10,
            controllerModel: 'X6',
            controllerProtocol: '5.3',
            firmwareLabel: '20260101',
            connectionEpoch: 'one',
          };
          return api.store.append(ride.id, 'owner', [row], sequence, sequence, row.timestamp);
        };
        await append(0);
        const first = await api.ensureBrowserDistance(ride.id);
        const releaseFirst = await api.retainBrowserDistance(first);
        const older = await append(1);
        await append(2);
        const newer = await api.ensureBrowserDistance(ride.id);
        const releaseNewer = await api.retainBrowserDistance(newer);
        const [pinned] = await Promise.all([
          api.ensureBrowserDistance(ride.id, 'auto', older),
          api.ensureBrowserDistance(ride.id),
        ]);
        const rows = await api.browserDistanceIntervals(newer, null, 20);
        const stats = await api.browserDistanceStats(newer, 0, 2);
        const isolated = pinned.profile.generation !== newer.profile.generation;
        await releaseFirst();
        await releaseNewer();
        return { rows, stats, isolated };
      });
      expect(result.rows).toHaveLength(2);
      expect(result.stats?.distance).toBe(20);
      expect(result.isolated).toBe(true);
    }));
  it.each([exactKeys.producerOrder.expectedOrder, exactKeys.producerOrder.insertionOrder])(
    'pages sparse producer positions %j without repeating the previous interval',
    async (...positions) =>
      run(async page => {
        const result = await page.evaluate(async positions => {
          const api = window.distanceTests,
            ride = await api.store.begin(
              'owner',
              { indoor: true, recordGPS: false, sampleHz: 2 },
              '2026-01-01T00:00:00Z',
            );
          const rows = positions.map((producerSequence, sequence) => ({
            ...api.browserRow(sequence, sequence),
            recordingId: ride.id,
            producerSequence,
            controllerSpeedMps: 10,
            speedRaw: 36,
            controllerModel: 'X6',
            controllerProtocol: '5.3',
            firmwareLabel: '20260101',
            connectionEpoch: 'connection',
          }));
          await api.store.append(ride.id, 'owner', rows, 2, 2, rows[2]!.timestamp);
          const handle = await api.ensureBrowserDistance(ride.id);
          const first = (await api.browserDistanceIntervals(handle, null, 1))![0]!;
          const second = (await api.browserDistanceIntervals(handle, [Number(first.endUs), first.sequence], 1))![0]!;
          const tail = await api.browserDistanceIntervals(handle, [Number(second.endUs), second.sequence], 1);
          return { sequences: [first.sequence, second.sequence], ends: [first.end, second.end], tail };
        }, positions);
        expect(result.sequences).toEqual([1, 2]);
        expect(result.ends).toEqual([1, 2]);
        expect(result.tail).toEqual([]);
      }),
  );
  for (const [gap, interrupted] of [
    [2, false],
    [6, false],
    [8, false],
    [2, true],
  ] as const)
    it(`renders a ${gap}-second distance gap with interruption=${interrupted}`, async () =>
      run(async page => {
        const result = await page.evaluate(
          async ({ gap, interrupted }) => {
            const api = window.distanceTests;
            const ride = await api.store.begin(
              'owner',
              { indoor: true, recordGPS: false, sampleHz: 2 },
              '2026-01-01T00:00:00.000Z',
            );
            const rows = [0, 1, 1 + gap, 2 + gap].map((time, sequence) => ({
              ...api.browserRow(time, sequence),
              recordingId: ride.id,
              controllerSpeedMps: 10,
              speedRaw: 36,
              controllerModel: 'X6',
              controllerProtocol: '5.3',
              firmwareLabel: '20260101',
              connectionEpoch: sequence < 2 ? 'first' : 'second',
              interruptionIndex: interrupted && sequence >= 2 ? 1 : 0,
              active: true,
              interval: sequence < 2 ? 0 : 1,
            }));
            await api.store.append(ride.id, 'owner', rows, 2 + gap, 2 + gap, rows[3]!.timestamp);
            const handle = await api.ensureBrowserDistance(ride.id);
            const plots = await Promise.all([2 + gap, 256].map(end => api.plotBrowserDistance(handle, 0, end, 32)));
            return {
              plots,
              info: handle.info.selected,
              stats: await api.browserDistanceStats(handle, 0, 2 + gap),
              inspected: await api.inspectBrowserDistance(handle, 1 + gap / 2),
              telemetryCount: (await api.store.page(ride.id, 0, 10)).length,
            };
          },
          { gap, interrupted },
        );
        const joined = gap < 6;
        const distance = joined ? 20 + 10 * gap : 20;
        const covered = joined ? 2 + gap : 2;
        for (const [index, points] of result.plots.entries()) {
          if (index === 1 && joined) {
            expect(points.map(p => [p.elapsedSeconds, p.value, p.startsSegment])).toEqual([
              [0, 0, true],
              [2 + gap, distance, false],
            ]);
            continue;
          }
          expect(points.map(p => [p.elapsedSeconds, p.value, p.startsSegment])).toEqual([
            [0, 0, true],
            [1, 10, false],
            [1 + gap, distance - 10, !joined],
            [2 + gap, distance, false],
          ]);
        }
        expect(result.info).toMatchObject({ distanceMeters: distance, coveredSeconds: covered, partial: !joined });
        expect(result.stats).toEqual({
          distance,
          coveredSeconds: covered,
          partial: !joined,
          unresolvedBoundary: false,
        });
        if (joined) expect(result.inspected).toMatchObject({ elapsedSeconds: 1, value: 10 });
        else expect(result.inspected).toBeNull();
        expect(result.telemetryCount).toBe(4);
      }));
  it('joins a controller loss shorter than six seconds across connection epochs within one activity interval', async () =>
    run(async page => {
      const result = await page.evaluate(async () => {
        const api = window.distanceTests;
        const at = (time: number) => new Date(Date.parse('2026-01-01T00:00:00.000Z') + time * 1000).toISOString();
        const distance = async (resumed: number, paused: boolean) => {
          const ride = await api.store.begin(
            'owner',
            { indoor: true, recordGPS: false, sampleHz: 2 },
            '2026-01-01T00:00:00.000Z',
          );
          const rows = [0, 1, resumed, resumed + 1].map((time, sequence) => ({
            ...api.browserRow(time, sequence),
            recordingId: ride.id,
            controllerSpeedMps: 10,
            speedRaw: 36,
            controllerModel: 'X6',
            controllerProtocol: '5.3',
            firmwareLabel: '20260101',
            connectionEpoch: sequence < 2 ? 'first' : 'second',
            active: true,
            interval: paused && sequence >= 2 ? 1 : 0,
          }));
          await api.store.append(ride.id, 'owner', rows.slice(0, 2), 1, 1, at(1));
          if (paused) {
            await api.store.transition(ride.id, 'owner', 'pause', 2, 2, at(2));
            await api.store.transition(ride.id, 'owner', 'resume', 3, 2, at(3));
          }
          const timer = resumed + (paused ? 0 : 1);
          await api.store.append(ride.id, 'owner', rows.slice(2), resumed + 1, timer, at(resumed + 1));
          const { selected } = (await api.ensureBrowserDistance(ride.id)).info;
          await api.store.transition(ride.id, 'owner', 'stop', resumed + 2, timer + 1, at(resumed + 2));
          return selected;
        };
        return { joined: await distance(6, false), long: await distance(7, false), paused: await distance(6, true) };
      });
      expect(result.joined).toMatchObject({ distanceMeters: 70, coveredSeconds: 7 });
      expect(result.long).toMatchObject({ distanceMeters: 20, coveredSeconds: 2 });
      expect(result.paused).toMatchObject({ distanceMeters: 20, coveredSeconds: 2 });
    }));
  it('keeps the Start-time distance despite later settings and reuses profile and raw caches', async () =>
    run(async page => {
      const result = await page.evaluate(async () => {
        const api = window.distanceTests,
          ride = await api.store.begin(
            'owner',
            { indoor: false, recordGPS: false, sampleHz: 2 },
            '2026-01-01T00:00:00.000Z',
          );
        const rows = Array.from({ length: 8 }, (_, sequence) => ({
          ...api.browserRow(sequence, sequence),
          recordingId: ride.id,
          controllerSpeedMps: 5,
          speedRaw: 18,
          controllerModel: 'X6',
          controllerProtocol: '5.3',
          firmwareLabel: '20260101',
          connectionEpoch: 'epoch',
          active: true,
          interval: 0,
        }));
        await api.store.append(ride.id, 'owner', rows, 7, 7, rows[7]!.timestamp);
        await api.store.transition(ride.id, 'owner', 'stop', 7, 7, rows[7]!.timestamp);
        const before = JSON.stringify(await api.store.get(ride.id)),
          originals = JSON.stringify(await api.store.page(ride.id, 0, 10));
        const [automatic, unavailable] = await Promise.all([
          api.ensureBrowserDistance(ride.id),
          api.ensureBrowserDistance(ride.id, 'gps:watch'),
        ]);
        const autoSource = api.monitor(ride.id),
          controllerSource = api.monitor(ride.id, false, api.store, 'controller'),
          gpsSource = api.monitor(ride.id, false, api.store, 'gps:watch');
        const auto = await autoSource.describeSource({ generation: 1 }),
          controller = await controllerSource.describeSource({ generation: 1 }),
          gps = await gpsSource.describeSource({ generation: 1 });
        const request = {
          generation: 1,
          metrics: ['humanPowerW', 'distanceMeters'],
          startSeconds: 0,
          endSeconds: 7,
          pixelWidth: 240,
        };
        const automaticPlot = await autoSource.readPlot({ ...request, expectedRevision: auto.revision });
        await autoSource.rangeStats({ ...request, expectedRevision: auto.revision });
        let rawPages = 0,
          maximumRawPage = 0,
          profileInputPages = 0;
        const page = api.store.pageExact.bind(api.store),
          getAll = IDBObjectStore.prototype.getAll;
        IDBObjectStore.prototype.getAll = function (...args) {
          if (this.name === 'samples') profileInputPages++;
          return getAll.apply(this, args);
        };
        api.store.pageExact = async (...args) => {
          rawPages++;
          const rows = await page(...args);
          maximumRawPage = Math.max(maximumRawPage, rows.length);
          return rows;
        };
        const selected = await controllerSource.readPlot({ ...request, expectedRevision: controller.revision });
        await controllerSource.rangeStats({ ...request, expectedRevision: controller.revision });
        const missing = await gpsSource.readPlot({ ...request, expectedRevision: gps.revision });
        const stats = await gpsSource.rangeStats({ ...request, expectedRevision: gps.revision });
        const latest = await gpsSource.readLatest({ generation: 2, metrics: ['distanceMeters'] });
        const rawPagesBeforeInspection = rawPages;
        const inspect = await gpsSource.inspectAt({ ...request, expectedRevision: gps.revision, seconds: 3 });
        const stale = await controllerSource.readPlot({ ...request, expectedRevision: auto.revision });
        const changes = await controllerSource.changesSince({ generation: 2, sinceRevision: auto.revision });
        api.store.pageExact = page;
        IDBObjectStore.prototype.getAll = getAll;
        const recorder = api.createRecorder().recorder;
        const [defaultSummary, missingSummary] = await Promise.all([
          recorder.read(ride.id),
          recorder.read(ride.id, 'gps:watch'),
        ]);
        return {
          automatic: automatic.info,
          unavailable: unavailable.info,
          sameGeneration: automatic.profile.generation === unavailable.profile.generation,
          auto,
          automaticPlot,
          controller,
          gps,
          selected,
          missing,
          stats,
          latest,
          inspect,
          inspectedPower: rows[3]!.humanPowerW,
          stale,
          changes,
          rawPages,
          rawPagesBeforeInspection,
          maximumRawPage,
          profileInputPages,
          defaultSummary,
          missingSummary,
          metadataUnchanged: before === JSON.stringify(await api.store.get(ride.id)),
          originalsUnchanged: originals === JSON.stringify(await api.store.page(ride.id, 0, 10)),
        };
      });
      expect(result.automatic).toMatchObject({
        selection: 'controller',
        selected: { source: 'controller', distanceMeters: 35 },
      });
      expect(result.unavailable).toEqual(result.automatic);
      expect(result.sameGeneration).toBe(true);
      expect(result.auto.revision).toBe(result.controller.revision);
      expect(result.controller.revision).toBe(result.gps.revision);
      expect(result.auto.sourceId).toBe(result.controller.sourceId);
      expect(result.stale.status).toBe('ok');
      expect(result.changes.resetRequired).toBe(false);
      expect(result.changes.changes).toEqual([]);
      expect(result.rawPagesBeforeInspection).toBe(0);
      expect(result.rawPages).toBe(1);
      expect(result.maximumRawPage).toBeLessThanOrEqual(256);
      expect(result.profileInputPages).toBe(0);
      if (result.automaticPlot.status === 'ok' && result.selected.status === 'ok') {
        expect(result.selected.series.distanceMeters!.at(-1)!.value).toBe(35);
        expect(result.selected.series.humanPowerW!.length).toBeGreaterThan(0);
        expect(result.selected.series.humanPowerW).toEqual(result.automaticPlot.series.humanPowerW);
      } else throw new Error('Selected plot failed');
      if (result.missing.status === 'ok') expect(result.missing.series.distanceMeters?.at(-1)?.value).toBe(35);
      else throw new Error('Missing plot failed');
      if (result.stats.status === 'ok') expect(result.stats.statistics.distanceMeters).toBeDefined();
      else throw new Error('Missing statistics failed');
      if (result.latest.status === 'ok') expect(result.latest.points.distanceMeters?.value).toBe(35);
      else throw new Error('Missing latest failed');
      if (result.inspect.status === 'ok') {
        expect(result.inspect.points.distanceMeters?.value).toBe(15);
        expect(result.inspect.points.humanPowerW).toMatchObject({ elapsedSeconds: 3, value: result.inspectedPower });
      } else throw new Error('Missing inspection failed');
      expect(result.defaultSummary.summary.distance?.selection).toBe('controller');
      expect(result.missingSummary.summary.distance).toEqual(result.defaultSummary.summary.distance);
      expect(result.missingSummary.summary.distanceMeters).toBe(35);
      expect(result.metadataUnchanged && result.originalsUnchanged).toBe(true);
    }));

  it('keeps plot and statistics cancellation independent when their caller generations differ', async () =>
    run(async page => {
      const result = await page.evaluate(async () => {
        const api = window.distanceTests,
          ride = await api.store.begin(
            'owner',
            { indoor: false, recordGPS: false, sampleHz: 2 },
            '2026-01-01T00:00:00.000Z',
          );
        const rows = Array.from({ length: 8 }, (_, sequence) => ({
          ...api.browserRow(sequence, sequence),
          recordingId: ride.id,
        }));
        await api.store.append(ride.id, 'owner', rows, 7, 7, rows[7]!.timestamp);
        const source = api.monitor(ride.id),
          description = await source.describeSource({ generation: 1 });
        const base = {
          expectedRevision: description.revision,
          metrics: ['humanPowerW'],
          startSeconds: 0,
          endSeconds: 7,
        };
        const firstStats = await source.rangeStats({ ...base, generation: 40 });
        const followingPlot = await source.readPlot({ ...base, generation: 2, pixelWidth: 240 }).then(
          value => value.status,
          error => error.message,
        );
        const newerPlot = await source.readPlot({ ...base, generation: 90, pixelWidth: 240 });
        const followingStats = await source.rangeStats({ ...base, startSeconds: 0.1, generation: 3 }).then(
          value => value.status,
          error => error.message,
        );
        const comparison = await source.rangeStats({
          ...base,
          startSeconds: 0.2,
          generation: 1,
          includeEndpoints: true,
        });
        return {
          firstStats: firstStats.status,
          followingPlot,
          newerPlot: newerPlot.status,
          followingStats,
          comparison: comparison.status,
        };
      });
      expect(result).toEqual({
        firstStats: 'ok',
        followingPlot: 'ok',
        newerPlot: 'ok',
        followingStats: 'ok',
        comparison: 'ok',
      });
    }));
  it('retains both non-aligned viewport edges in coarse long-range plots', async () =>
    run(async page => {
      const results = await page.evaluate(async () => {
        const api = window.distanceTests,
          ride = await api.store.begin(
            'owner',
            { indoor: false, recordGPS: false, sampleHz: 2 },
            '2026-01-01T00:00:00.000Z',
          );
        for (let offset = 0; offset < 1152; offset += 64) {
          const rows = Array.from({ length: 64 }, (_, n) => ({
            ...api.browserRow(offset + n, offset + n),
            recordingId: ride.id,
            controllerSpeedMps: 5,
            speedRaw: 18,
            firmwareLabel: '20260101',
            controllerModel: 'X6',
            controllerProtocol: '5.3',
            connectionEpoch: 'epoch',
            active: true,
            interval: 0,
          }));
          const last = rows[63]!;
          await api.store.append(ride.id, 'owner', rows, last.elapsedSeconds, last.elapsedSeconds, last.timestamp);
        }
        const handle = await api.ensureBrowserDistance(ride.id);
        return Promise.all(
          [4, 28, 240].map(async budget => {
            const points = await api.plotBrowserDistance(handle, 100.03, 1000.03, budget);
            const anchors = await Promise.all(
              points.map(point => api.inspectBrowserDistance(handle, point.elapsedSeconds, point.observationId)),
            );
            return { budget, points, anchors };
          }),
        );
      });
      for (const { budget, points, anchors } of results) {
        expect(points[0]!.elapsedSeconds).toBeLessThanOrEqual(100.03);
        expect(points.at(-1)!.elapsedSeconds).toBeGreaterThanOrEqual(1000.03);
        expect(points.length).toBeLessThanOrEqual(budget * 4 + 8);
        for (const point of points) expect(point.value).toBeCloseTo(point.elapsedSeconds * 5);
        expect(points.filter(point => point.startsSegment)).toHaveLength(1);
        expect(anchors.map(point => point?.observationId)).toEqual(points.map(point => point.observationId));
      }
    }));
  it('excludes saved pause intervals from range missing-time coverage without replaying samples', async () =>
    run(async page => {
      const result = await page.evaluate(async () => {
        const api = window.distanceTests,
          ride = await api.store.begin(
            'owner',
            { indoor: true, recordGPS: false, sampleHz: 2 },
            '2026-01-01T00:00:00.000Z',
          );
        const row = (time: number, sequence: number, interval: number) => ({
          ...api.browserRow(time, sequence),
          recordingId: ride.id,
          controllerSpeedMps: 10,
          speedRaw: 36,
          firmwareLabel: '20260101',
          controllerModel: 'X6',
          controllerProtocol: '5.3',
          connectionEpoch: 'epoch',
          active: true,
          interval,
        });
        await api.store.append(ride.id, 'owner', [row(0, 0, 0), row(1, 1, 0)], 1, 1, '2026-01-01T00:00:01.000Z');
        await api.ensureBrowserDistance(ride.id);
        await api.store.transition(ride.id, 'owner', 'pause', 1, 1, '2026-01-01T00:00:01.000Z');
        await api.store.transition(ride.id, 'owner', 'resume', 3, 1, '2026-01-01T00:00:03.000Z');
        await api.store.append(ride.id, 'owner', [row(3, 2, 1), row(4, 3, 1)], 4, 2, '2026-01-01T00:00:04.000Z');
        await api.store.transition(ride.id, 'owner', 'stop', 4, 2, '2026-01-01T00:00:04.000Z');
        const handle = await api.ensureBrowserDistance(ride.id);
        return {
          stats: await api.browserDistanceStats(handle, 0, 4),
          subrange: await api.browserDistanceStats(handle, 0.5, 3.5),
          info: handle.info,
        };
      });
      expect(result.stats).toEqual({ distance: 10, coveredSeconds: 1, unresolvedBoundary: false, partial: true });
      expect(result.subrange).toEqual({ distance: 5, coveredSeconds: 0.5, unresolvedBoundary: false, partial: true });
      expect(result.info.selected?.partial).toBe(true);
    }));
  it('shares one profile with summary, chart, exact boundaries and clipped statistics without changing originals', async () =>
    run(async page => {
      const result = await page.evaluate(async () => {
        const api = window.distanceTests,
          ride = await api.store.begin(
            'owner',
            { indoor: true, recordGPS: false, sampleHz: 2 },
            '2026-01-01T00:00:00.000Z',
          );
        const rows = [0, 1, 1.1, 2.1].map((time, sequence) => ({
          ...api.browserRow(time, sequence),
          recordingId: ride.id,
          speedRaw: 36,
          controllerSpeedMps: 10,
          controllerModel: 'X6',
          controllerProtocol: '5.3',
          firmwareLabel: '20260101',
          connectionEpoch: sequence < 2 ? 'epoch-a' : 'epoch-b',
          active: true,
          interval: 0,
        }));
        await api.store.append(ride.id, 'owner', rows, 2.1, 2.1, rows[3]!.timestamp);
        await api.store.transition(ride.id, 'owner', 'stop', 3, 3, '2026-01-01T00:00:03.000Z');
        const before = JSON.stringify(await api.store.page(ride.id, 0, 10)),
          handle = await api.ensureBrowserDistance(ride.id),
          monitor = api.monitor(ride.id),
          description = await monitor.describeSource({ generation: 1 });
        const request = { generation: 1, expectedRevision: description.revision, metrics: ['distanceMeters'] };
        const [plot, stats, latest, joined, summary] = await Promise.all([
          monitor.readPlot({ ...request, startSeconds: 0, endSeconds: 3, pixelWidth: 240 }),
          monitor.rangeStats({ ...request, startSeconds: 0.5, endSeconds: 1.6 }),
          monitor.readLatest(request),
          monitor.inspectAt({ ...request, seconds: 1.05 }),
          api.createRecorder().recorder.read(ride.id),
        ]);
        const points = plot.status === 'ok' ? plot.series.distanceMeters! : [];
        const anchored = await monitor.inspectAt({
          ...request,
          seconds: points[2]!.elapsedSeconds,
          anchor: { metric: 'distanceMeters', observationId: points[2]!.observationId! },
        });
        const inside = await monitor.readPlot({ ...request, startSeconds: 0.2, endSeconds: 0.4, pixelWidth: 240 });
        return {
          info: handle.info,
          description,
          plot,
          inside,
          stats,
          latest,
          joined,
          summary,
          anchored,
          unchanged: before === JSON.stringify(await api.store.page(ride.id, 0, 10)),
        };
      });
      expect(result.info.selected).toMatchObject({
        source: 'controller',
        distanceMeters: 21,
        coveredSeconds: 2.1,
        uncoveredSeconds: 0.9,
        estimated: true,
        partial: true,
      });
      expect(result.description.availableMetrics).toContain('distanceMeters');
      expect(result.unchanged).toBe(true);
      expect(result.plot.status).toBe('ok');
      if (result.plot.status === 'ok')
        expect(result.plot.series.distanceMeters!.map(p => [p.value, p.startsSegment])).toEqual([
          [0, true],
          [10, false],
          [11, false],
          [21, false],
        ]);
      expect(result.stats.status).toBe('ok');
      if (result.stats.status === 'ok')
        expect(result.stats.statistics.distanceMeters).toEqual({
          distance: 11,
          coveredSeconds: 1.1,
          unresolvedBoundary: false,
          partial: false,
        });
      expect(result.inside.status).toBe('ok');
      if (result.inside.status === 'ok')
        expect(result.inside.series.distanceMeters!.map(p => p.elapsedSeconds)).toEqual([0, 1]);
      if (result.joined.status === 'ok')
        expect(result.joined.points.distanceMeters).toMatchObject({ value: 10, elapsedSeconds: 1, derived: true });
      if (result.anchored.status === 'ok')
        expect(result.anchored.points.distanceMeters).toMatchObject({ value: 11, elapsedSeconds: 1.1, derived: true });
      expect(result.summary.summary).toMatchObject({ distanceMeters: 21, averageSpeedMps: 10 });
    }));

  it('keeps observed zero eligible, rejects unknown-unit and paused edges and joins a short reconnect', async () =>
    run(async page => {
      const result = await page.evaluate(async () => {
        const api = window.distanceTests,
          ride = await api.store.begin(
            'owner',
            { indoor: false, recordGPS: false, sampleHz: 2 },
            '2026-01-01T00:00:00.000Z',
          );
        const rows = [0, 1, 2, 3, 4, 5].map((time, sequence) => ({
          ...api.browserRow(time, sequence),
          recordingId: ride.id,
          speedRaw: 0,
          controllerSpeedMps: 0,
          controllerModel: 'X6',
          controllerProtocol: '5.3',
          firmwareLabel: '20260101',
          connectionEpoch: sequence < 4 ? 'epoch-a' : 'epoch-b',
          active: sequence !== 2,
          interval: sequence < 3 ? 0 : 1,
          originalSequence: sequence === 4 ? 0 : sequence,
        }));
        await api.store.append(ride.id, 'owner', rows, 5, 4, rows[5]!.timestamp);
        await api.store.transition(ride.id, 'owner', 'stop', 5, 4, rows[5]!.timestamp);
        const handle = await api.ensureBrowserDistance(ride.id),
          before = handle.profile.inputSequence;
        const updated = await api.ensureBrowserDistance(ride.id, 'controller');
        return { info: updated.info, before, after: updated.profile.inputSequence };
      });
      expect(result.info).toMatchObject({
        selection: 'controller',
        selected: { distanceMeters: 0, coveredSeconds: 3 },
      });
      expect(result.before).toBe(result.after);
    }));

  it('uses epochs instead of source sequence resets for continuity', async () =>
    run(async page => {
      const result = await page.evaluate(async () => {
        const api = window.distanceTests,
          ride = await api.store.begin(
            'owner',
            { indoor: false, recordGPS: false, sampleHz: 2 },
            '2026-01-01T00:00:00Z',
          );
        const rows = [0, 1, 2, 3].map((time, sequence) => ({
          ...api.browserRow(time, sequence),
          recordingId: ride.id,
          speedRaw: 18,
          controllerSpeedMps: 5,
          controllerModel: 'X6',
          controllerProtocol: '5.3',
          firmwareLabel: '20260101',
          originalSequence: sequence % 2,
        }));
        await api.store.append(ride.id, 'owner', rows, 3, 3, rows[3]!.timestamp);
        return (await api.ensureBrowserDistance(ride.id)).info;
      });
      expect(result.selected).toMatchObject({ distanceMeters: 15, coveredSeconds: 3 });
    }));

  it('recomputes a shortened original prefix with a new generation and rejects corrupted cached progress', async () =>
    run(async page => {
      const result = await page.evaluate(async () => {
        const api = window.distanceTests,
          ride = await api.store.begin(
            'owner',
            { indoor: false, recordGPS: false, sampleHz: 2 },
            '2026-01-01T00:00:00Z',
          );
        const rows = [0, 1, 2, 3].map((time, sequence) => ({
          ...api.browserRow(time, sequence),
          recordingId: ride.id,
          speedRaw: 18,
          controllerSpeedMps: 5,
          controllerModel: 'X6',
          controllerProtocol: '5.3',
          firmwareLabel: '20260101',
        }));
        const record = await api.store.append(ride.id, 'owner', rows, 3, 3, rows[3]!.timestamp),
          before = await api.ensureBrowserDistance(ride.id);
        await api.browserTransaction(['recordings', 'samples'], 'readwrite', async tx => {
          await api.idbRequest(
            tx
              .objectStore('samples')
              .delete(
                IDBKeyRange.bound(
                  [ride.id, 'controller', api.browserIndexKey('3')],
                  [ride.id, 'controller', api.browserIndexKey('4')],
                ),
              ),
          );
          await api.idbRequest(
            tx.objectStore('recordings').put({
              ...record,
              telemetryCount: 2,
              originalMembershipCount: 3,
              elapsedSeconds: 1,
              timerSeconds: 1,
              revision: (BigInt(record.revision) + 1n).toString(),
            }),
          );
        });
        const after = await api.ensureBrowserDistance(ride.id),
          oldCurrent = await api.browserDistanceCurrent(before);
        await api.browserTransaction(['distance-profiles'], 'readwrite', tx =>
          api.idbRequest(tx.objectStore('distance-profiles').put({ ...after.profile, inputSequence: NaN })),
        );
        const error = await api.ensureBrowserDistance(ride.id).then(
          () => '',
          error => error.message,
        );
        return {
          before: before.info,
          after: after.info,
          newGeneration: before.profile.generation !== after.profile.generation,
          oldCurrent,
          error,
          record: await api.store.get(ride.id),
        };
      });
      expect(result.before.selected?.distanceMeters).toBe(15);
      expect(result.after.selected?.distanceMeters).toBe(5);
      expect(result.newGeneration).toBe(true);
      expect(result.oldCurrent).toBe(false);
      expect(result.error).toMatch(/Invalid browser distance profile/);
      expect(result.record.telemetryCount).toBe(2);
    }));

  it('checkpoints append work and never reads original rows for repeated cursor/range/plot queries', async () =>
    run(async page => {
      const result = await page.evaluate(async () => {
        const api = window.distanceTests,
          ride = await api.store.begin(
            'owner',
            { indoor: false, recordGPS: false, sampleHz: 2 },
            '2026-01-01T00:00:00.000Z',
          );
        const sample = (sequence: number) => ({
          ...api.browserRow(sequence / 8, sequence),
          recordingId: ride.id,
          speedRaw: 18,
          controllerSpeedMps: 5,
          controllerModel: 'X6',
          controllerProtocol: '5.3',
          firmwareLabel: '20260101',
          connectionEpoch: 'epoch',
          active: true,
          interval: 0,
        });
        for (let offset = 0; offset < 640; offset += 64) {
          const rows = Array.from({ length: 64 }, (_, n) => sample(offset + n));
          const last = rows[63]!;
          await api.store.append(ride.id, 'owner', rows, last.elapsedSeconds, last.elapsedSeconds, last.timestamp);
        }
        await api.ensureBrowserDistance(ride.id);
        const originalIndexCursor = IDBIndex.prototype.openCursor;
        const originalIndex = IDBIndex.prototype.getAll;
        const original = IDBObjectStore.prototype.getAll,
          originalCursor = IDBObjectStore.prototype.openCursor;
        let reads = 0,
          maximum = 0,
          cursorReads = 0;
        IDBIndex.prototype.openCursor = function (...args) {
          const request = originalIndexCursor.apply(this, args);
          if (this.objectStore.name === 'samples') {
            reads++;
            let count = 0;
            request.addEventListener('success', () => {
              if (request.result) maximum = Math.max(maximum, ++count);
            });
          }
          return request;
        };
        IDBIndex.prototype.getAll = function (...args) {
          if (this.objectStore.name === 'samples') {
            reads++;
            maximum = Math.max(maximum, Number(args[1] ?? Infinity));
          }
          return originalIndex.apply(this, args);
        };
        IDBObjectStore.prototype.getAll = function (...args) {
          if (this.name === 'samples') {
            reads++;
            maximum = Math.max(maximum, Number(args[1] ?? Infinity));
          }
          return original.apply(this, args);
        };
        IDBObjectStore.prototype.openCursor = function (...args) {
          if (this.name === 'samples') cursorReads++;
          return originalCursor.apply(this, args);
        };
        const rows = Array.from({ length: 64 }, (_, n) => sample(640 + n)),
          last = rows[63]!;
        await api.store.append(ride.id, 'owner', rows, last.elapsedSeconds, last.elapsedSeconds, last.timestamp);
        reads = 0;
        const handle = await api.ensureBrowserDistance(ride.id),
          appendReads = reads;
        const monitor = api.monitor(ride.id),
          description = await monitor.describeSource({ generation: 1 });
        reads = 0;
        cursorReads = 0;
        const request = { generation: 1, expectedRevision: description.revision, metrics: ['distanceMeters'] };
        for (let n = 0; n < 20; n++) {
          await monitor.inspectAt({ ...request, seconds: n + 0.03 });
          await monitor.rangeStats({ ...request, startSeconds: n, endSeconds: n + 10 });
          await monitor.readPlot({ ...request, startSeconds: n / 10, endSeconds: 95, pixelWidth: 30 });
          await monitor.readLatest(request);
        }
        IDBObjectStore.prototype.getAll = original;
        IDBIndex.prototype.getAll = originalIndex;
        IDBIndex.prototype.openCursor = originalIndexCursor;
        IDBObjectStore.prototype.openCursor = originalCursor;
        return { appendReads, originalReads: reads + cursorReads, maximum, info: handle.info };
      });
      expect(result.appendReads).toBe(1);
      expect(result.originalReads).toBe(0);
      expect(result.maximum).toBeLessThanOrEqual(256);
      expect(result.info.selected?.distanceMeters).toBeCloseTo((703 / 8) * 5);
    }));
});
