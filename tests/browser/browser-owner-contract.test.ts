import { afterAll, beforeAll, expect, it } from 'vitest';
import { chromium, type Browser, type BrowserContext, type Page } from '@playwright/test';
import { browserTestBundle } from '../helpers/browser-test-bundle';
import type * as Harness from '../fixtures/browser-ride-harness';

declare global {
  interface Window {
    ownerContracts: typeof Harness;
  }
}
const bundle = browserTestBundle('tests/fixtures/browser-ride-harness.ts', 'ownerContracts');
let browser: Browser;
beforeAll(async () => {
  browser = await chromium.launch({ headless: true });
});
afterAll(async () => {
  await browser?.close();
});
async function load(page: Page) {
  await page.route('http://127.0.0.1:43121/**', route =>
    route.fulfill({ contentType: 'text/html', body: '<title>Owner contracts</title>' }),
  );
  await page.goto('http://127.0.0.1:43121/');
  await page.addScriptTag({ content: bundle });
}
async function run(work: (page: Page, context: BrowserContext) => Promise<void>) {
  const context = await browser.newContext();
  try {
    const page = await context.newPage();
    await load(page);
    await work(page, context);
  } finally {
    await context.close();
  }
}

it('fences an export opening after its initial metadata read races deletion', async () =>
  run(async page => {
    const result = await page.evaluate(async () => {
      const api = window.ownerContracts;
      const ride = await api.store.begin(
        'owner',
        { indoor: true, recordGPS: false, sampleHz: 2 },
        '2026-01-01T00:00:00Z',
      );
      await api.store.transition(ride.id, 'owner', 'stop', 1, 1, '2026-01-01T00:00:01Z');
      const read = api.store.find.bind(api.store);
      let ready!: () => void;
      const started = new Promise<void>(resolve => {
        ready = resolve;
      });
      let release!: () => void;
      const waiting = new Promise<void>(resolve => {
        release = resolve;
      });
      let first = true;
      api.store.find = async id => {
        const record = await read(id);
        if (first) {
          first = false;
          ready();
          await waiting;
        }
        return record;
      };
      const opening = api.browserExportSource
        .open({ rideId: ride.id, kind: 'zip', context: { platform: 'web', exportedAt: '2026-01-01T00:00:02Z' } })
        .then(
          () => 'opened',
          error => error.code,
        );
      await started;
      await api.store.remove(ride.id);
      release();
      return opening;
    });
    expect(result).toBe('deleted');
  }));

it('fences a production detail reader after deletion during its original pages', async () =>
  run(async page => {
    const result = await page.evaluate(async () => {
      const api = window.ownerContracts;
      const h = api.createRecorder();
      await h.source.connect();
      const ride = await h.recorder.start({ indoor: true });
      h.setTime(1);
      await h.recorder.stop();
      await api.ensureBrowserDistance(ride.id!, 'auto');
      const read = api.store.analysisPage.bind(api.store);
      let lifecyclePages = 0;
      api.store.analysisPage = async (...args) => {
        const rows = await read(...args);
        if (args[0] === 'ride-lifecycle' && ++lifecyclePages === 2) {
          await api.store.remove(ride.id!);
        }
        return rows;
      };
      return h.recorder.read(ride.id!).then(
        () => 'returned deleted detail',
        error => error.message,
      );
    });
    expect(result).not.toBe('returned deleted detail');
    expect(result).toMatch(/not found|deleted/);
  }));
