import path from 'node:path';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { chromium, expect as ui, type Browser, type Page } from '@playwright/test';
import { browserTestBundle } from '../helpers/browser-test-bundle';
import type * as Harness from '../fixtures/monitor-render-harness';

declare global {
  interface Window {
    renderTests: typeof Harness;
  }
}
const fixture = (name: string) => path.resolve(`tests/fixtures/${name}`);
const modules = fixture('monitor-render-modules.tsx');
const panel = fixture('monitor-render-panel.tsx');
const aliases: Record<string, string> = {
  react: path.resolve('node_modules/react/cjs/react.production.js'),
  'react/jsx-runtime': path.resolve('node_modules/react/cjs/react-jsx-runtime.production.js'),
  'react-dom': path.resolve('node_modules/react-dom/cjs/react-dom.production.js'),
  'react-dom/client': path.resolve('node_modules/react-dom/cjs/react-dom-client.production.js'),
  scheduler: path.resolve('node_modules/scheduler/cjs/scheduler.production.js'),
  'react-native': fixture('monitor-render-native.tsx'),
  'react-native-gesture-handler': modules,
  'react-native-reanimated': modules,
  'react-native-worklets': modules,
  'react-native-safe-area-context': modules,
  'expo-router': modules,
  'expo-modules-core': modules,
  'react-native-svg': fixture('monitor-render-svg.tsx'),
  [path.resolve('src/core/monitor-hit-test')]: fixture('monitor-render-hit-probe.tsx'),
  [path.resolve('src/core/monitor-chart')]: fixture('monitor-render-chart-probe.tsx'),
  [path.resolve('src/components/monitor-raster')]: path.resolve('src/components/monitor-raster.native.tsx'),
};
for (const name of [
  'src/services/monitor-preferences',
  'src/features/monitor/monitor-editor',
  'src/components/modal-dialog',
  'src/components/icon',
])
  aliases[path.resolve(name)] = panel;
const bundle = browserTestBundle('tests/fixtures/monitor-render-harness.tsx', 'renderTests', aliases, {
  resolvePackages: true,
});
const metrics = [
  'humanPowerW',
  'cadenceRpm',
  'batteryVoltageV',
  'batteryCurrentA',
  'motorCurrentA',
  'pedalTorqueNm',
  'motorTempC',
  'controllerTempC',
  'motorRpm',
  'throttleVoltageV',
  'consumedAh',
  'consumedWh',
];
const members: Record<string, string[]> = {
  power: ['humanPowerW'],
  cadenceRpm: ['cadenceRpm'],
  batteryVoltageV: ['batteryVoltageV'],
  current: ['batteryCurrentA', 'motorCurrentA'],
  pedalTorqueNm: ['pedalTorqueNm'],
  temperature: ['motorTempC', 'controllerTempC'],
  motorRpm: ['motorRpm'],
  throttleVoltageV: ['throttleVoltageV'],
  consumedAh: ['consumedAh'],
  consumedWh: ['consumedWh'],
};
const ids = Object.keys(members);
const geometryBuilds = (scenes: Record<string, number>, id: string) =>
  [id, ...members[id]!].reduce((total, key) => total + (scenes[key] ?? 0), 0);

let browser: Browser;
beforeAll(async () => {
  browser = await chromium.launch({ headless: true });
});
afterAll(async () => {
  await browser?.close();
});
async function run(work: (page: Page) => Promise<void>) {
  const context = await browser.newContext({ viewport: { width: 420, height: 760 } });
  try {
    const page = await context.newPage();
    page.setDefaultTimeout(5000);
    const errors: string[] = [];
    page.on('pageerror', error => errors.push(error.message));
    await page.setContent('<!doctype html><title>Monitor rendering</title><body style="margin:0"></body>');
    await page.addScriptTag({ content: bundle });
    await work(page);
    expect(errors).toEqual([]);
  } finally {
    await context.close();
  }
}
async function settle(page: Page) {
  for (let i = 0; i < 3; i++) {
    await page.waitForTimeout(350);
    await page.waitForFunction(() => window.renderTests.idle());
  }
}
const charts = (page: Page) => page.evaluate(() => window.renderTests.charts());
const counts = (page: Page) => page.evaluate(() => window.renderTests.countsSnapshot());

describe.each(['ios', 'web'] as const)('monitor chart rendering on %s', os => {
  it('re-renders only on-screen charts on clock ticks and rebuilds no chart geometry', async () =>
    run(async page => {
      await page.evaluate(
        ({ os, metrics }) => window.renderTests.mountCharts({ os, live: true, metrics, seconds: 600 }),
        { os, metrics },
      );
      await settle(page);
      const placed = await charts(page);
      expect(placed.map(chart => chart.id)).toEqual(ids);
      const onScreen = placed.filter(chart => chart.onScreen).map(chart => chart.id);
      const offScreen = placed.filter(chart => !chart.onScreen).map(chart => chart.id);
      expect(onScreen.length).toBeGreaterThan(1);
      expect(offScreen.length).toBeGreaterThan(3);
      await page.evaluate(() => window.renderTests.resetCounts());
      for (let i = 0; i < 8; i++) {
        await page.evaluate(() => window.renderTests.tick());
        await page.evaluate(() => new Promise(requestAnimationFrame));
      }
      const { renders, scenes, nativeScenes } = await counts(page);
      for (const id of offScreen) expect(renders[`monitor-chart-${id}`], id).toBeUndefined();
      for (const id of onScreen) expect(renders[`monitor-chart-${id}`], id).toBeGreaterThan(0);
      expect(scenes).toEqual({});
      expect(nativeScenes).toEqual({});
    }));

  it('reads one snapshot for the charts scrolled into view without re-rendering unchanged charts', async () =>
    run(async page => {
      await page.evaluate(
        ({ os, metrics }) => window.renderTests.mountCharts({ os, live: false, metrics, seconds: 600 }),
        { os, metrics },
      );
      await settle(page);
      const version = await page.evaluate(() => window.renderTests.snapshot().interactionVersion);
      for (const step of ['unchanged', 'entering'] as const) {
        const before = await charts(page);
        const drawn = await page.evaluate(() => window.renderTests.snapshot().series);
        const requests = await page.evaluate(() => window.renderTests.plotRequests.length);
        await page.evaluate(() => window.renderTests.resetCounts());
        await page.evaluate(() => window.renderTests.scrollBy(600));
        await settle(page);
        const after = await charts(page);
        const { renders, scenes } = await counts(page);
        const was = (id: string) => before.find(chart => chart.id === id)!;
        const reads = await page.evaluate(() => window.renderTests.plotRequests);
        expect(reads.length, step).toBe(requests + 1);
        expect(reads.at(-1), step).toEqual(after.filter(chart => chart.read).flatMap(chart => members[chart.id]!));
        for (const chart of after) {
          const builds = geometryBuilds(scenes, chart.id),
            rendered = renders[`monitor-chart-${chart.id}`];
          if (!chart.onScreen) expect(rendered, `${step} ${chart.id}`).toBeUndefined();
          else if (was(chart.id).onScreen) {
            expect(rendered, `${step} ${chart.id}`).toBeUndefined();
            expect(builds, `${step} ${chart.id}`).toBe(0);
          } else if (!members[chart.id]!.some(metric => drawn.includes(metric))) {
            expect(rendered, `${step} ${chart.id}`).toBeGreaterThan(0);
            expect(builds, `${step} ${chart.id}`).toBeGreaterThan(0);
            expect(chart.text, `${step} ${chart.id}`).not.toContain('Loading');
          }
        }
        if (step === 'unchanged') expect(after.some(chart => chart.onScreen && was(chart.id).onScreen)).toBe(true);
        else
          expect(
            after.some(chart => chart.onScreen && !members[chart.id]!.some(metric => drawn.includes(metric))),
          ).toBe(true);
      }
      expect(await page.evaluate(() => window.renderTests.snapshot().interactionVersion)).toBe(version);
    }));
});

it(
  'keeps a focused chart stepping and announcing its cursor after it scrolls off screen',
  async () =>
    run(async page => {
      await page.evaluate(
        ({ metrics }) => window.renderTests.mountCharts({ os: 'web', live: false, metrics, seconds: 600 }),
        { metrics },
      );
      await settle(page);
      const chart = page.getByTestId('monitor-chart-power');
      const cursor = () => page.evaluate(() => window.renderTests.snapshot().cursorSeconds);
      await chart.press('Home');
      await expect.poll(cursor).toBe(0);
      await page.evaluate(() => window.renderTests.scrollBy(10000));
      await settle(page);
      expect((await charts(page)).find(item => item.id === 'power')!.onScreen).toBe(false);
      let previous = 0;
      for (let step = 0; step < 3; step++) {
        await page.keyboard.press('ArrowRight');
        await expect.poll(cursor, { timeout: 1500 }).toBeGreaterThan(previous);
        previous = (await cursor())!;
        const timestamp = new Date(Date.UTC(2026, 0, 1) + previous * 1000).toISOString();
        await ui(chart).toHaveAttribute('aria-valuetext', new RegExp(`elapsed, ${timestamp}`));
      }
    }),
  15000,
);

describe('monitor panel reads', () => {
  const requests = (page: Page) => page.evaluate(() => window.renderTests.plotRequests);

  it.each(['ios', 'web'] as const)(
    'plots the first screenful before layout, follows scrolling and never plots numbers on %s',
    async os =>
      run(async page => {
        await page.evaluate(
          ({ os, metrics }) =>
            window.renderTests.mountPanel({ os, numbers: ['heartRateBpm'], charts: metrics, seconds: 600 }),
          { os, metrics },
        );
        await page.waitForFunction(() => window.renderTests.plotRequests.length > 0);
        const [first] = await requests(page);
        expect(first!.length).toBeGreaterThan(1);
        expect(first!.length).toBeLessThan(metrics.length);
        expect(metrics.slice(0, first!.length)).toEqual(first);
        await page.waitForTimeout(800);
        await page.evaluate(() => window.renderTests.scrollBy(10000));
        await page.waitForFunction(() => window.renderTests.plotRequests.at(-1)!.includes('consumedWh'));
        const all = await requests(page);
        expect(all.at(-1)).not.toContain('humanPowerW');
        expect(all.flat()).not.toContain('heartRateBpm');
      }),
  );

  it('shows a number-only view without plot reads or a chart loading message', async () =>
    run(async page => {
      await page.evaluate(() =>
        window.renderTests.mountPanel({ os: 'ios', numbers: ['humanPowerW', 'cadenceRpm'], charts: [], seconds: 60 }),
      );
      await page.getByTestId('monitor-number-humanPowerW').getByText(/^\d+$/).waitFor();
      await page.waitForTimeout(500);
      expect(await requests(page)).toEqual([]);
      expect(await page.getByText('Loading charts…').count()).toBe(0);
    }));
});
