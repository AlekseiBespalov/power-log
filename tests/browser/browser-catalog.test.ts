import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { chromium, type Browser, type Page } from '@playwright/test';
import { browserTestBundle } from '../helpers/browser-test-bundle';
import type * as Harness from '../fixtures/browser-ride-harness';

declare global {
  interface Window {
    catalogTests: typeof Harness;
  }
}
const bundle = browserTestBundle('tests/fixtures/browser-ride-harness.ts', 'catalogTests');
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
    await page.route('http://127.0.0.1:43127/**', route =>
      route.fulfill({ contentType: 'text/html', body: '<!doctype html><title>Catalog validation</title>' }),
    );
    await page.goto('http://127.0.0.1:43127/');
    await page.addScriptTag({ content: bundle });
    await work(page);
  } finally {
    await context.close();
  }
}

describe('browser catalog partial pages', () => {
  it('scans past corrupt records before and between healthy rows and preserves one pagination cursor', async () =>
    run(async page => {
      const result = await page.evaluate(async () => {
        const h = window.catalogTests;
        await h.browserTransaction(['recordings'], 'readwrite', async tx => {
          for (let i = 0; i < 123; i++)
            await h.idbRequest(
              tx.objectStore('recordings').put(h.browserRide({ id: `ride-${String(i).padStart(4, '0')}` })),
            );
          for (const id of ['zzz-corrupt', 'ride-0098z', 'ride-0072z', 'ride-0020z'])
            await h.idbRequest(tx.objectStore('recordings').put({ ...h.browserRide({ id }), samples: -1 }));
        });
        const requests: unknown[] = [],
          catalog = new h.HistoryCatalog(request => {
            requests.push(request);
            return h.store.list(request);
          });
        await catalog.refresh();
        const first = catalog.getSnapshot();
        while (catalog.getSnapshot().hasMore) await catalog.loadMore();
        return { first, final: catalog.getSnapshot(), requests };
      });
      expect(result.first.records).toHaveLength(50);
      expect(result.first.unreadableCount).toBe(3);
      expect(result.first.records.at(-1)?.id).toBe('ride-0073');
      expect(result.requests[1]).toMatchObject({ beforeID: 'ride-0073', limit: 51 });
      expect(result.final.records.map(row => row.id)).toEqual(
        Array.from({ length: 123 }, (_, i) => `ride-${String(122 - i).padStart(4, '0')}`),
      );
      expect(result.final.hasMore).toBe(false);
      expect(result.final.error).toBeNull();
    }));
  it('reports a fully unreadable page and separately counts malformed keys absent from the index', async () =>
    run(async page => {
      const result = await page.evaluate(async () => {
        const h = window.catalogTests;
        await h.browserTransaction(['recordings'], 'readwrite', async tx => {
          for (let i = 0; i < 80; i++)
            await h.idbRequest(
              tx.objectStore('recordings').put({ ...h.browserRide({ id: `bad-${i}` }), revision: undefined }),
            );
          await h.idbRequest(
            tx.objectStore('recordings').put({ ...h.browserRide({ id: 'missing-index-key' }), startedAt: null }),
          );
        });
        const result = await h.store.list({ limit: 51 });
        const retained = await h.browserTransaction(['recordings'], 'readonly', tx =>
          h.idbRequest(tx.objectStore('recordings').count()),
        );
        return { result, retained };
      });
      expect(result.result).toEqual({ records: [], unreadableCount: 80, unindexedCount: 1 });
      expect(result.retained).toBe(81);
    }));
  it('rejects missing epochs at admission and leaves the committed prefix untouched', async () =>
    run(async page => {
      const result = await page.evaluate(async () => {
        const h = window.catalogTests,
          ride = await h.store.begin('owner', true, '2026-01-01T00:00:00Z');
        const row = { ...h.browserRow(0, 0), recordingId: ride.id };
        delete (row as Partial<typeof row>).connectionEpoch;
        const error = await h.store.append(ride.id, 'owner', [row], 0, 0, row.timestamp).then(
          () => '',
          error => error.message,
        );
        return { error, record: await h.store.get(ride.id), rows: await h.store.page(ride.id, 0, 1) };
      });
      expect(result.error).toMatch(/observation/);
      expect(result.record.samples).toBe(0);
      expect(result.rows).toEqual([]);
    }));
});
