import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import path from 'node:path';
import { chromium, expect as ui, type Browser, type Page } from '@playwright/test';
import { browserTestBundle } from '../helpers/browser-test-bundle';
import type * as Harness from '../fixtures/history-source-layout-harness';

declare global { interface Window { historySourceTests: typeof Harness; sourceFrames: number[][]; sourceSampling: boolean } }
const platform = path.resolve('tests/fixtures/history-source-layout-platform.tsx');
const backend = path.resolve('tests/fixtures/history-deletion-backend.ts');
const aliases: Record<string, string> = {
  react: path.resolve('node_modules/react/cjs/react.production.js'),
  'react/jsx-runtime': path.resolve('node_modules/react/cjs/react-jsx-runtime.production.js'),
  'react-dom': path.resolve('node_modules/react-dom/cjs/react-dom.production.js'),
  'react-dom/client': path.resolve('node_modules/react-dom/cjs/react-dom-client.production.js'),
  scheduler: path.resolve('node_modules/scheduler/cjs/scheduler.production.js'),
  'react-native': path.resolve('node_modules/react-native-web/dist/cjs/index.js'),
  'react-native-safe-area-context': platform, 'expo-router': platform,
};
for (const name of ['src/services/workouts', 'src/services/ride-history']) aliases[path.resolve(name)] = backend;
for (const name of ['src/services/session-context', 'src/services/device', 'src/services/monitor-preferences', 'src/services/workout-monitor-source',
  'src/services/files', 'src/components/route-preview', 'src/features/history/csv-ride-details', 'src/features/monitor/monitor-panel']) aliases[path.resolve(name)] = platform;
const bundle = browserTestBundle('tests/fixtures/history-source-layout-harness.tsx', 'historySourceTests', aliases, { resolvePackages: true });
let browser: Browser;
beforeAll(async () => { browser = await chromium.launch({ headless: true }); });
afterAll(async () => { await browser?.close(); });
async function run(width: number, work: (page: Page) => Promise<void>) {
  const context = await browser.newContext({ viewport: { width, height: 1600 } });
  try {
    const page = await context.newPage(); page.setDefaultTimeout(3000);
    await page.route('http://127.0.0.1:43121/**', route => route.fulfill({ contentType: 'text/html', body: '<!doctype html><title>Source layout integration</title>' }));
    await page.goto('http://127.0.0.1:43121/'); await page.addScriptTag({ content: bundle });
    await page.evaluate(() => window.historySourceTests.mount());
    await page.getByRole('button', { name: /^Open ride,/ }).first().click();
    await ui(page.getByTestId('ride-summary')).toBeVisible();
    await work(page);
  } finally { await context.close(); }
}
async function frames(page: Page, count = 8) {
  await page.evaluate(async count => { for (let i = 0; i < count; i++) await new Promise(requestAnimationFrame); }, count);
}
async function sampleLayout(page: Page) {
  await page.evaluate(() => {
    window.sourceFrames = []; window.sourceSampling = true;
    function sample() {
      const summary = document.querySelector('[data-testid="ride-summary"]')!, source = document.querySelector('[data-testid="ride-distance-source"]')!, chart = document.querySelector('[data-testid="selected-ride-chart"]')!;
      if (!window.sourceSampling || !summary || !source || !chart) return;
      const buttons = [...document.querySelectorAll('[role="button"]')];
      const exportButton = buttons.find(node => node.textContent === 'Export FIT')!;
      const r = summary.getBoundingClientRect(), s = source.getBoundingClientRect(), c = chart.getBoundingClientRect();
      window.sourceFrames.push([r.top + scrollY, r.height, s.top + scrollY, s.height, c.top + scrollY, Number(getComputedStyle(exportButton).opacity)]);
      requestAnimationFrame(sample);
    }
    sample();
  });
}

describe('production saved History source layout with real RN Web', () => {
  for (const width of [320, 390, 768, 1440]) it(`retains layout and matching values through delayed repeated source choices at ${width}px`, async () => run(width, async page => {
    const source = page.getByTestId('ride-distance-source');
    await frames(page); await sampleLayout(page);
    if (width === 390) await page.screenshot({ path: '/tmp/power-log-source-layout-gps.png', fullPage: true });
    for (const choice of ['controller', 'gps:watch', 'auto', 'controller'] as const) {
      const oldCaption = await source.locator('[data-testid="distance-source-caption"]').textContent();
      const oldSummary = await page.getByTestId('ride-summary').textContent();
      await page.evaluate(() => window.historySourceTests.delayNext());
      await page.evaluate(choice => window.historySourceTests.setGlobalDistanceSource(choice), choice);
      await page.waitForFunction(choice => window.historySourceTests.phases.includes('read:' + choice), choice);
      await ui(page.getByRole('button', { name: 'Export FIT', exact: true })).toBeEnabled();
      await frames(page);
      expect(await page.getByTestId('ride-summary').textContent()).toBe(oldSummary);
      expect(await source.locator('[data-testid="distance-source-caption"]').textContent()).toBe(oldCaption);
      await page.evaluate(() => window.historySourceTests.finishRead());
      await ui(source.locator('[data-testid="distance-source-caption"]')).toHaveText(choice === 'controller' ? 'Controller estimate' : 'GPS · Watch · Partial');
      await ui(page.getByTestId('ride-summary')).toContainText(choice === 'controller' ? '0.90' : '0.00');
      await frames(page);
    }
    const captured = await page.evaluate(() => { window.sourceSampling = false; return window.sourceFrames; });
    expect(captured.length).toBeGreaterThan(50);
    for (let column = 0; column < 5; column++) expect(Math.max(...captured.map(frame => frame[column]!)) - Math.min(...captured.map(frame => frame[column]!))).toBeLessThanOrEqual(1);
    expect(captured.every(frame => frame[5] === 1)).toBe(true);
    if (width === 390) await page.screenshot({ path: '/tmp/power-log-source-layout-controller.png', fullPage: true });
  }));
  it('keeps read failures visible and never publishes an obsolete source response into a new ride', async () => run(390, async page => {
    await page.evaluate(() => { window.historySourceTests.delayNext('Source read failed'); window.historySourceTests.setGlobalDistanceSource('controller'); });
    await page.waitForFunction(() => window.historySourceTests.phases.includes('read:controller'));
    await page.evaluate(() => window.historySourceTests.finishRead());
    await ui(page.getByText('Source read failed', { exact: true })).toBeVisible();
    await ui(page.getByTestId('ride-summary')).toContainText('0.00');
    await ui(page.getByTestId('distance-source-caption')).toHaveText('GPS · Watch · Partial');
    await page.getByRole('button', { name: 'Dismiss', exact: true }).click();
    await page.evaluate(() => { window.historySourceTests.delayNext('Obsolete source read'); window.historySourceTests.setGlobalDistanceSource('gps:watch'); });
    await page.waitForFunction(() => window.historySourceTests.phases.includes('read:gps:watch'));
    await page.getByRole('button', { name: '‹ Rides', exact: true }).click();
    await page.evaluate(() => window.historySourceTests.finishRead());
    await page.getByRole('button', { name: /^Open ride,/ }).last().click();
    await ui(page.getByTestId('ride-summary')).toBeVisible(); await frames(page);
    await ui(page.getByText('Obsolete source read', { exact: true })).toHaveCount(0);
  }));
});
