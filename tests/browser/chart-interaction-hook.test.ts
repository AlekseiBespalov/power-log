import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import path from 'node:path';
import { chromium, type Browser, type Page } from '@playwright/test';
import { browserTestBundle } from '../helpers/browser-test-bundle';
import type * as Harness from './chart-interaction-harness';

declare global {
  interface Window {
    chartTests: typeof Harness;
  }
}
const platform = path.resolve('tests/helpers/chart-interaction-platform.ts');
const bundle = browserTestBundle(
  'tests/browser/chart-interaction-harness.ts',
  'chartTests',
  {
    react: path.resolve('node_modules/react/cjs/react.production.js'),
    'react-dom': path.resolve('node_modules/react-dom/cjs/react-dom.production.js'),
    'react-dom/client': path.resolve('node_modules/react-dom/cjs/react-dom-client.production.js'),
    scheduler: path.resolve('node_modules/scheduler/cjs/scheduler.production.js'),
    'react-native': platform,
    'expo-router': platform,
    'react-native-reanimated': platform,
    'react-native-worklets': platform,
  },
  { resolvePackages: true },
);
let browser: Browser;
beforeAll(async () => {
  browser = await chromium.launch({ headless: true });
});
afterAll(async () => {
  await browser?.close();
});
async function run(work: (page: Page) => Promise<void>, cursor: number | null = null) {
  const context = await browser.newContext();
  try {
    const page = await context.newPage();
    page.setDefaultTimeout(2000);
    const errors: string[] = [];
    page.on('pageerror', error => errors.push(error.message));
    await page.setContent('<!doctype html><title>Chart interaction lifecycle</title>');
    await page.addScriptTag({ content: bundle });
    await page.evaluate(cursor => window.chartTests.mount({ ...window.chartTests.initial, cursor }), cursor);
    await page.waitForFunction(() => window.chartTests.snapshot()?.foreground);
    await page.evaluate(() => window.chartTests.flushUI());
    await work(page);
    expect(errors).toEqual([]);
  } finally {
    await context.close();
  }
}
const snapshot = (page: Page) => page.evaluate(() => window.chartTests.snapshot()!);
async function waitForEpoch(page: Page, previous: number) {
  await page.waitForFunction(previous => window.chartTests.snapshot()!.epoch > previous, previous);
}
async function expectNoCallbacks(page: Page) {
  const state = await snapshot(page);
  expect(state.inspectCalls).toEqual([]);
  expect(state.viewportCalls).toEqual([]);
}

describe('programmatic native interaction ownership in React', () => {
  it('publishes cancellation ownership when controls retain the same cursor and viewport', async () =>
    run(async page => {
      const first = await snapshot(page);
      await page.evaluate(() => window.chartTests.cancel());
      await waitForEpoch(page, first.epoch);
      await page.evaluate(() => window.chartTests.flushUI());
      const next = await snapshot(page);
      expect(next.shared.epoch).toBe(next.epoch);
      expect(next.epoch).toBeGreaterThan(first.epoch);
      expect(next.shared.cursor).toBe(first.shared.cursor);
      expect(next.shared.view).toEqual(first.shared.view);
    }));

  it('initializes a selected epoch without publishing a prior clear at the same sequence', async () =>
    run(async page => {
      const first = await snapshot(page);
      expect(
        await page.evaluate(() => {
          const h = window.chartTests;
          h.queueEvent('cursor');
          return h.cancel({ ...h.initial, cursor: 42 });
        }),
      ).toBe(0);
      await waitForEpoch(page, first.epoch);
      const presentations = await page.evaluate(() => window.chartTests.flushUI());
      expect(presentations.length).toBeGreaterThan(0);
      expect(
        presentations.every(value => value.epoch > first.epoch && value.cursor === 42 && value.sequence === 0),
      ).toBe(true);
      const selected = await snapshot(page);
      expect(selected.shared.epoch).toBe(selected.epoch);
      await page.evaluate(() => window.chartTests.flushRN());
      await expectNoCallbacks(page);
    }));

  it('clears a selected epoch and accepts a programmatic retap without stale callbacks', async () =>
    run(async page => {
      const first = await snapshot(page);
      await page.evaluate(() => {
        const h = window.chartTests;
        h.queueEvent('cursor');
        h.cancel(h.initial);
      });
      await waitForEpoch(page, first.epoch);
      await page.evaluate(() => window.chartTests.flushUI());
      const cleared = await snapshot(page);
      expect(cleared.shared.cursor).toBeNull();
      await page.evaluate(() => {
        const h = window.chartTests;
        h.queueEvent('viewport');
        h.cancel({ ...h.initial, cursor: 90 });
      });
      await waitForEpoch(page, cleared.epoch);
      await page.evaluate(() => {
        window.chartTests.flushUI();
        window.chartTests.flushRN();
      });
      const selected = await snapshot(page);
      expect(selected.shared.epoch).toBeGreaterThan(cleared.epoch);
      expect(selected.shared.cursor).toBe(90);
      await expectNoCallbacks(page);
    }, 42));

  it('invalidates queued cursor and viewport callbacks when the source changes', async () =>
    run(async page => {
      const first = await snapshot(page);
      await page.evaluate(() => {
        const h = window.chartTests;
        h.queueEvent('cursor');
        h.queueEvent('viewport');
        h.render({ ...h.initial, cursor: 12, view: { start: 0, end: 40 } }, 'other-source');
      });
      await waitForEpoch(page, first.epoch);
      await page.evaluate(() => window.chartTests.flushRN());
      await expectNoCallbacks(page);
      await page.evaluate(() => window.chartTests.flushUI());
      const changed = await snapshot(page);
      expect(changed.scope).toBe('other-source');
      expect(changed.shared.epoch).toBe(changed.epoch);
      expect(changed.shared.cursor).toBe(12);
      expect(changed.shared.view).toEqual({ start: 0, end: 40 });
      await page.evaluate(() => {
        const h = window.chartTests;
        h.queueEvent('cursor', 24);
        h.queueEvent('viewport');
        h.flushRN();
      });
      const current = await snapshot(page);
      expect(current.inspectCalls).toEqual([[24, false, null], [null]]);
      expect(current.viewportCalls).toEqual([[{ start: 20, end: 80 }, false]]);
    }));

  it('rejects callbacks after foreground loss and resumes only with the returning epoch', async () =>
    run(async page => {
      const first = await snapshot(page);
      await page.evaluate(() => {
        const h = window.chartTests;
        h.queueEvent('cursor');
        h.queueEvent('viewport');
        h.setForeground(false);
      });
      await waitForEpoch(page, first.epoch);
      await page.evaluate(() => {
        const h = window.chartTests;
        h.flushUI();
        h.flushRN();
        h.queueEvent('cursor');
        h.queueEvent('viewport');
        h.flushRN();
      });
      await expectNoCallbacks(page);
      const hidden = await snapshot(page);
      await page.evaluate(() => {
        const h = window.chartTests;
        h.queueEvent('cursor');
        h.setForeground(true);
      });
      await waitForEpoch(page, hidden.epoch);
      await page.evaluate(() => {
        window.chartTests.flushUI();
        window.chartTests.flushRN();
      });
      await expectNoCallbacks(page);
      await page.evaluate(() => {
        window.chartTests.queueEvent('cursor', 36);
        window.chartTests.flushRN();
      });
      expect((await snapshot(page)).inspectCalls).toEqual([[36, false, null]]);
    }));

  it('invalidates callbacks on unmount even when a replacement mount reuses the epoch', async () =>
    run(async page => {
      const first = await snapshot(page);
      await page.evaluate(() => {
        const h = window.chartTests;
        h.queueEvent('cursor');
        h.queueEvent('viewport');
        h.unmount();
        h.mount({ ...h.initial, cursor: 42 });
      });
      await page.waitForFunction(() => {
        const state = window.chartTests.snapshot();
        return state?.foreground && state.presentation.cursor === 42;
      });
      await page.evaluate(() => {
        window.chartTests.flushUI();
        window.chartTests.flushRN();
      });
      await expectNoCallbacks(page);
      const replacement = await snapshot(page);
      expect(replacement.epoch).toBe(first.epoch);
      expect(replacement.shared.cursor).toBe(42);
      await page.evaluate(() => {
        window.chartTests.queueEvent('cursor', 64);
        window.chartTests.flushRN();
      });
      expect((await snapshot(page)).inspectCalls).toEqual([[64, false, null]]);
    }));
});
