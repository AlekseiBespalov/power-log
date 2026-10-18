import { afterAll, beforeAll, expect, it } from 'vitest';
import { chromium, type Browser, type Page } from '@playwright/test';
import { browserTestBundle } from '../helpers/browser-test-bundle';
import type * as Harness from '../fixtures/browser-ride-harness';
import type { BrowserDistanceHandle } from '../../src/services/browser-distance-store';

declare global {
  interface Window {
    webCache: typeof Harness;
    distancePin: BrowserDistanceHandle;
    releaseDistance: () => Promise<void>;
    distanceBuild: Promise<BrowserDistanceHandle>;
    resumeDistanceBuild: () => void;
    distanceBuildBlocked: boolean;
    distanceBuildReads: number;
  }
}
const bundle = browserTestBundle('tests/fixtures/browser-ride-harness.ts', 'webCache');
let browser: Browser;
beforeAll(async () => {
  browser = await chromium.launch({ headless: true });
});
afterAll(async () => {
  await browser?.close();
});
async function tabs(work: (first: Page, second: Page) => Promise<void>) {
  const context = await browser.newContext();
  try {
    await context.route('http://127.0.0.1:43170/**', route =>
      route.fulfill({ contentType: 'text/html', body: '<title>Distance cache</title>' }),
    );
    const first = await context.newPage();
    const second = await context.newPage();
    for (const page of [first, second]) {
      await page.goto('http://127.0.0.1:43170/');
      await page.addScriptTag({ content: bundle });
    }
    await work(first, second);
  } finally {
    await context.close();
  }
}
async function seed(page: Page, now: number) {
  return page.evaluate(async clock => {
    Date.now = () => clock;
    const api = window.webCache;
    const ride = await api.store.begin(
      'owner',
      { indoor: true, recordGPS: false, sampleHz: 4 },
      '2026-01-01T00:00:00Z',
    );
    const rows = [0, 1, 2].map(n => ({
      ...api.browserRow(n, n, new Date(Date.UTC(2026, 0, 1) + n * 1000).toISOString()),
      recordingId: ride.id,
      controllerSpeedMps: 5,
      controllerModel: 'X6',
      controllerProtocol: '5.3',
      firmwareLabel: '20260101',
      speedRaw: 18,
    }));
    await api.store.append(ride.id, 'owner', rows, 2, 2, rows[2]!.timestamp);
    window.distancePin = await api.ensureBrowserDistance(ride.id);
    return { id: ride.id, generation: window.distancePin.profile.generation };
  }, now);
}
it('allocates distinct generations in separate tabs at the same clock without overlapping distance', async () =>
  tabs(async (first, second) => {
    const before = await seed(first, 1000);
    const result = await second.evaluate(async id => {
      Date.now = () => 1000;
      const api = window.webCache;
      await api.store.transition(id, 'owner', 'pause', 3, 3, '2026-01-01T00:00:03Z');
      const handle = await api.ensureBrowserDistance(id);
      return { generation: handle.profile.generation, stats: await api.browserDistanceStats(handle, 0, 2) };
    }, before.id);
    expect(result.stats).toMatchObject({ distance: 10, coveredSeconds: 2 });
    expect(result.generation).not.toBe(before.generation);
  }));
it('retains a generation across another tab’s rebuild and reclaims it only after the shared lease releases', async () =>
  tabs(async (first, second) => {
    const before = await seed(first, 1000);
    await first.evaluate(async () => {
      window.releaseDistance = await window.webCache.retainBrowserDistance(window.distancePin);
    });
    const replacement = await second.evaluate(async id => {
      Date.now = () => 2000;
      const api = window.webCache;
      await api.store.transition(id, 'owner', 'pause', 3, 3, '2026-01-01T00:00:03Z');
      window.distancePin = await api.ensureBrowserDistance(id);
      return window.distancePin.profile.generation;
    }, before.id);
    const retained = await first.evaluate(async () => {
      const api = window.webCache;
      const stats = await api.browserDistanceStats(window.distancePin, 0, 2);
      const profiles = await api.browserTransaction(['distance-profiles'], 'readonly', tx =>
        api.idbRequest(tx.objectStore('distance-profiles').getAll()),
      );
      await window.releaseDistance();
      return { stats, generations: [...new Set(profiles.map(p => p.generation))] };
    });
    expect(retained.stats).toMatchObject({ distance: 10, coveredSeconds: 2 });
    expect(retained.generations).toEqual(expect.arrayContaining([before.generation, replacement]));
    const reclaimed = await second.evaluate(async () => {
      const api = window.webCache;
      const profiles = await api.browserTransaction(['distance-profiles'], 'readonly', tx =>
        api.idbRequest(tx.objectStore('distance-profiles').getAll()),
      );
      return {
        generations: [...new Set(profiles.map(p => p.generation))],
        stats: await api.browserDistanceStats(window.distancePin, 0, 2),
      };
    });
    expect(reclaimed.generations).toEqual([replacement]);
    expect(reclaimed.stats).toMatchObject({ distance: 10, coveredSeconds: 2 });
  }));
it('publishes no distance cache or result when the ride is deleted after originals are read', async () =>
  tabs(async first => {
    const before = await seed(first, 1000);
    const result = await first.evaluate(async id => {
      const api = window.webCache;
      await api.store.transition(id, 'owner', 'pause', 3, 3, '2026-01-01T00:00:03Z');
      const read = api.store.analysisPage.bind(api.store);
      let deleted = false;
      api.store.analysisPage = async (...args) => {
        const rows = await read(...args);
        if (args[0] === 'samples' && !deleted) {
          deleted = true;
          await api.store.discard(id, 'owner');
        }
        return rows;
      };
      const error = await api.ensureBrowserDistance(id).then(
        () => '',
        error => error.message,
      );
      const counts = await api.browserTransaction(
        ['recordings', 'distance-profiles', 'distance-intervals', 'distance-tiles'],
        'readonly',
        async tx =>
          Promise.all(
            ['recordings', 'distance-profiles', 'distance-intervals', 'distance-tiles'].map(name =>
              api.idbRequest(tx.objectStore(name).count()),
            ),
          ),
      );
      return { error, counts };
    }, before.id);
    expect(result.error).toBe('Recording not found');
    expect(result.counts).toEqual([0, 0, 0, 0]);
  }));

it('protects an incremental generation while another tab rebuilds during a bounded-page scan', async () =>
  tabs(async (first, second) => {
    const before = await seed(first, 1000);
    await first.evaluate(async id => {
      const api = window.webCache;
      const rows = Array.from({ length: 597 }, (_, index) => {
        const n = index + 3;
        return {
          ...api.browserRow(n, n, new Date(Date.UTC(2026, 0, 1) + n * 1000).toISOString()),
          recordingId: id,
          controllerSpeedMps: 5,
          controllerModel: 'X6',
          controllerProtocol: '5.3',
          firmwareLabel: '20260101',
          speedRaw: 18,
        };
      });
      for (let offset = 0; offset < rows.length; offset += api.BROWSER_BATCH) {
        const batch = rows.slice(offset, offset + api.BROWSER_BATCH);
        const tail = batch.at(-1)!;
        await api.store.append(id, 'owner', batch, tail.elapsedSeconds, tail.elapsedSeconds, tail.timestamp);
      }
      const read = api.store.analysisPage.bind(api.store);
      let pages = 0;
      const blocked = new Promise<void>(release => {
        window.resumeDistanceBuild = release;
      });
      api.store.analysisPage = async (...args) => {
        const rows = await read(...args);
        if (args[0] === 'samples' && ++pages === 2) {
          window.distanceBuildBlocked = true;
          await blocked;
        }
        return rows;
      };
      window.distanceBuild = api.ensureBrowserDistance(id);
      void window.distanceBuild.catch(() => {});
    }, before.id);
    await first.waitForFunction(() => window.distanceBuildBlocked);
    await second.evaluate(async id => {
      Date.now = () => 2000;
      const api = window.webCache;
      const read = api.store.analysisPage.bind(api.store);
      window.distanceBuildReads = 0;
      api.store.analysisPage = async (...args) => {
        window.distanceBuildReads++;
        return read(...args);
      };
      window.distanceBuild = api.ensureBrowserDistance(id);
      void window.distanceBuild.catch(() => {});
    }, before.id);
    await second.waitForFunction(
      async id => (await navigator.locks.query()).pending?.some(lock => lock.name === `power-log-distance-build:${id}`),
      before.id,
    );
    expect(await second.evaluate(() => window.distanceBuildReads)).toBe(0);
    const result = await first.evaluate(async () => {
      window.resumeDistanceBuild();
      const handle = await window.distanceBuild;
      return { selected: handle.info.selected, stats: await window.webCache.browserDistanceStats(handle, 0, 599) };
    });
    const other = await second.evaluate(async () => {
      const handle = await window.distanceBuild;
      return {
        generation: handle.profile.generation,
        selected: handle.info.selected,
        reads: window.distanceBuildReads,
      };
    });
    expect(other.generation).toBe(before.generation);
    expect(other.reads).toBe(0);
    expect(other.selected).toEqual(result.selected);
    expect(result.selected).toMatchObject({ distanceMeters: 2995, coveredSeconds: 599 });
    expect(result.stats).toMatchObject({ distance: 2995, coveredSeconds: 599 });
  }));
