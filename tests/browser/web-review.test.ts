import { afterAll, beforeAll, expect, it } from 'vitest';
import { chromium, type Browser, type Page } from '@playwright/test';
import { browserTestBundle } from '../helpers/browser-test-bundle';
import type * as Harness from '../fixtures/browser-ride-harness';
declare global {
  interface Window {
    webReview: typeof Harness;
  }
}
const bundle = browserTestBundle('tests/fixtures/browser-ride-harness.ts', 'webReview');
let browser: Browser;
beforeAll(async () => {
  browser = await chromium.launch({ headless: true });
});
afterAll(async () => {
  await browser?.close();
});
async function run(work: (page: Page) => Promise<void>) {
  const context = await browser.newContext();
  try {
    const page = await context.newPage();
    await page.route('http://127.0.0.1:43170/**', route =>
      route.fulfill({ contentType: 'text/html', body: '<title>Web review</title>' }),
    );
    await page.goto('http://127.0.0.1:43170/');
    await page.addScriptTag({ content: bundle });
    await work(page);
  } finally {
    await context.close();
  }
}
it('publishes distance intervals and progress atomically across a failed profile write and retry', async () =>
  run(async page => {
    const result = await page.evaluate(async () => {
      const api = window.webReview;
      const ride = await api.store.begin(
        'owner',
        { indoor: true, recordGPS: false, sampleHz: 4 },
        '2026-01-01T00:00:00Z',
      );
      const row = (n: number) => ({
        ...api.browserRow(n, n, new Date(Date.UTC(2026, 0, 1) + n * 1000).toISOString()),
        recordingId: ride.id,
        controllerSpeedMps: 5,
        controllerModel: 'X6',
        controllerProtocol: '5.3',
        firmwareLabel: '20260101',
        speedRaw: 18,
      });
      await api.store.append(ride.id, 'owner', [row(0), row(1), row(2)], 2, 2, row(2).timestamp);
      await api.ensureBrowserDistance(ride.id);
      await api.store.append(ride.id, 'owner', [row(3)], 3, 3, row(3).timestamp);
      const original = IDBObjectStore.prototype.put;
      let failed = false;
      IDBObjectStore.prototype.put = function (...args) {
        if (this.name === 'distance-profiles' && !failed) {
          failed = true;
          throw new Error('profile publication failed');
        }
        return original.apply(this, args);
      };
      const error = await api.ensureBrowserDistance(ride.id).then(
        () => '',
        error => error.message,
      );
      IDBObjectStore.prototype.put = original;
      await api.store.append(ride.id, 'owner', [row(4)], 4, 4, row(4).timestamp);
      const handle = await api.ensureBrowserDistance(ride.id);
      const stats = await api.browserDistanceStats(handle, 0, 4);
      const count = await api.browserTransaction(['distance-intervals'], 'readonly', tx =>
        api.idbRequest(
          tx
            .objectStore('distance-intervals')
            .count(IDBKeyRange.bound([ride.id, 'controller'], [ride.id, 'controller', []])),
        ),
      );
      return { error, stats, selected: handle.info.selected, count };
    });
    expect(result.error).toBe('profile publication failed');
    expect(result.selected).toMatchObject({ distanceMeters: 20, coveredSeconds: 4 });
    expect(result.stats).toMatchObject({ distance: 20, coveredSeconds: 4 });
    expect(result.count).toBe(4);
  }));
it('increments GPS distance with bounded intervals and reclaims superseded generations', async () =>
  run(async page => {
    const result = await page.evaluate(async () => {
      const api = window.webReview;
      const ride = await api.store.begin(
        'owner',
        { indoor: false, recordGPS: true, sampleHz: 4 },
        '2026-01-01T00:00:00Z',
      );
      const row = (n: number) => ({
        ...api.browserRow(n, n, new Date(Date.UTC(2026, 0, 1) + n * 1000).toISOString()),
        recordingId: ride.id,
        controllerSpeedMps: 5,
        controllerModel: 'X6',
        controllerProtocol: '5.3',
        firmwareLabel: '20260101',
        speedRaw: 18,
      });
      const gps = (n: number) => ({
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
      });
      await api.store.append(ride.id, 'owner', [row(0), row(1), row(2)], 2, 2, row(2).timestamp);
      await api.store.appendLocations(ride.id, 'owner', [gps(0), gps(1)], 2, 2, row(2).timestamp);
      const before = await api.ensureBrowserDistance(ride.id);
      const original = api.store.analysisPage.bind(api.store);
      let controllerReads = 0,
        gpsReads = 0;
      api.store.analysisPage = async (...args) => {
        const rows = await original(...args);
        if (args[0] === 'samples') controllerReads += rows.length;
        if (args[0] === 'ride-locations') gpsReads += rows.length;
        return rows;
      };
      const counts: number[] = [];
      let handle = before;
      for (let n = 2; n < 32; n++) {
        await api.store.appendLocations(
          ride.id,
          'owner',
          [gps(n)],
          Math.max(n, 2),
          Math.max(n, 2),
          row(Math.max(n, 2)).timestamp,
        );
        handle = await api.ensureBrowserDistance(ride.id);
        counts.push(
          await api.browserTransaction(['distance-intervals'], 'readonly', tx =>
            api.idbRequest(
              tx
                .objectStore('distance-intervals')
                .count(IDBKeyRange.bound([ride.id, 'gps:phone'], [ride.id, 'gps:phone', []])),
            ),
          ),
        );
      }
      api.store.analysisPage = original;
      await api.store.transition(ride.id, 'owner', 'stop', 32, 32, row(32).timestamp);
      const completed = await api.createRecorder().recorder.read(ride.id);
      const profiles = await api.browserTransaction(['distance-profiles'], 'readonly', tx =>
        api.idbRequest(tx.objectStore('distance-profiles').getAll()),
      );
      const intervals = await api.browserTransaction(['distance-intervals'], 'readonly', tx =>
        api.idbRequest(
          tx
            .objectStore('distance-intervals')
            .count(IDBKeyRange.bound([ride.id, 'gps:phone'], [ride.id, 'gps:phone', []])),
        ),
      );
      return {
        counts,
        controllerReads,
        gpsReads,
        sameGeneration: before.profile.generation === handle.profile.generation,
        generations: new Set(profiles.map(p => p.generation)).size,
        intervals,
        cachedDistance: completed.summary.distanceMeters,
        rawGPSDistance: completed.summary.gpsDistanceMeters,
      };
    });
    expect(result.counts).toEqual(Array.from({ length: 30 }, (_, i) => i + 2));
    expect(result.controllerReads).toBe(0);
    expect(result.gpsReads).toBe(30);
    expect(result.sameGeneration).toBe(true);
    expect(result.generations).toBe(1);
    expect(result.intervals).toBe(31);
    expect(result.cachedDistance).toBe(result.rawGPSDistance);
  }));
it('returns coherent pinned statistics and endpoints while every scan admits an append', async () =>
  run(async page => {
    const result = await page.evaluate(async () => {
      const api = window.webReview;
      const ride = await api.store.begin(
        'owner',
        { indoor: true, recordGPS: false, sampleHz: 4 },
        '2026-01-01T00:00:00Z',
      );
      const row = (n: number) => ({
        ...api.browserRow(n, n, new Date(Date.UTC(2026, 0, 1) + n * 1000).toISOString()),
        recordingId: ride.id,
        humanPowerW: n < 2 ? 100 : 200,
        controllerSpeedMps: 5,
        controllerModel: 'X6',
        controllerProtocol: '5.3',
        firmwareLabel: '20260101',
        speedRaw: 18,
      });
      await api.store.append(ride.id, 'owner', [row(0), row(1)], 1, 1, row(1).timestamp);
      await api.ensureBrowserDistance(ride.id);
      const source = api.monitor(ride.id);
      const original = api.store.analysisPage.bind(api.store);
      let sequence = 2;
      const results = [];
      for (let i = 0; i < 3; i++) {
        const description = await source.describeSource({ generation: i });
        let appended = false;
        api.store.analysisPage = async (...args) => {
          const values = await original(...args);
          if (args[0] === 'samples' && !appended) {
            appended = true;
            const next = row(sequence++);
            await api.store.append(ride.id, 'owner', [next], next.elapsedSeconds, next.elapsedSeconds, next.timestamp);
          }
          return values;
        };
        const value = await source.rangeStats({
          generation: i,
          expectedRevision: description.revision,
          startSeconds: 0,
          endSeconds: 1,
          metrics: ['humanPowerW', 'distanceMeters'],
          includeEndpoints: true,
        });
        results.push({ value, revision: description.revision });
        api.store.analysisPage = original;
      }
      return results;
    });
    for (const { value, revision } of result) {
      expect(value.status).toBe('ok');
      expect(value.revision).toBe(revision);
      if (value.status !== 'ok') throw new Error('Pinned statistics discarded');
      expect(value.statistics.humanPowerW).toMatchObject({
        count: 2,
        sampleMean: 100,
        integral: 100,
        coveredSeconds: 1,
      });
      expect(value.statistics.distanceMeters).toMatchObject({ distance: 5, coveredSeconds: 1 });
      expect(value.endpoints?.end.humanPowerW?.value).toBe(100);
    }
  }));

it('completes a summary at its admitted revision when originals append during composition', async () =>
  run(async page => {
    const result = await page.evaluate(async () => {
      const api = window.webReview;
      const ride = await api.store.begin(
        'owner',
        { indoor: true, recordGPS: false, sampleHz: 4 },
        '2026-01-01T00:00:00Z',
      );
      const row = (n: number) => ({
        ...api.browserRow(n, n, new Date(Date.UTC(2026, 0, 1) + n * 1000).toISOString()),
        recordingId: ride.id,
        humanPowerW: n < 2 ? 100 : 200,
      });
      const pin = await api.store.append(ride.id, 'owner', [row(0), row(1)], 1, 1, row(1).timestamp);
      await api.ensureBrowserDistance(ride.id);
      const original = api.store.analysisPage.bind(api.store);
      let appended = false;
      api.store.analysisPage = async (...args) => {
        const rows = await original(...args);
        if (args[0] === 'samples' && !appended) {
          appended = true;
          await api.store.append(ride.id, 'owner', [row(2)], 2, 2, row(2).timestamp);
        }
        return rows;
      };
      const detail = await api.createRecorder().recorder.read(ride.id);
      api.store.analysisPage = original;
      return { detail, revision: pin.revision, latest: (await api.store.get(ride.id)).revision };
    });
    expect(result.detail.metadata.collectionRevision).toBe(result.revision);
    expect(result.latest).not.toBe(result.revision);
    expect(result.detail.summary).toMatchObject({ telemetryCount: '2', averageRiderPowerW: 100, riderWorkJoules: 100 });
  }));
