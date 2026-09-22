import path from 'node:path';
import { chromium } from '@playwright/test';
import { describe, expect, it } from 'vitest';
import { browserTestBundle } from '../helpers/browser-test-bundle';
import type * as Harness from '../fixtures/presentation-harness';

declare global { interface Window { presentationTests: typeof Harness } }
const platform = path.resolve('tests/fixtures/presentation-platform.ts');
const aliases: Record<string, string> = {
  react: path.resolve('node_modules/react/cjs/react.production.js'),
  'react/jsx-runtime': path.resolve('node_modules/react/cjs/react-jsx-runtime.production.js'),
  'react-dom': path.resolve('node_modules/react-dom/cjs/react-dom.production.js'),
  'react-dom/client': path.resolve('node_modules/react-dom/cjs/react-dom-client.production.js'),
  scheduler: path.resolve('node_modules/scheduler/cjs/scheduler.production.js'),
  'react-native': platform,
};
for (const name of ['src/services/device', 'src/services/monitor-preferences']) aliases[path.resolve(name)] = platform;
for (const name of ['workouts', 'ride-history']) aliases[path.resolve(`src/services/${name}`)] = path.resolve(`src/services/${name}.web.ts`);
const bundle = browserTestBundle('tests/fixtures/presentation-harness.tsx', 'presentationTests', aliases, { resolvePackages: true });

describe('production presentation providers with real browser capture', () => {
  it('isolates shared UI, suspends hidden publication, and retains every recorded original', async () => {
    const browser = await chromium.launch({ headless: true });
    try {
      const page = await browser.newPage();
      await page.route('http://127.0.0.1:43124/**', route => route.fulfill({ contentType: 'text/html', body: '<!doctype html><title>Presentation integration</title>' }));
      await page.goto('http://127.0.0.1:43124/'); await page.addScriptTag({ content: bundle });
      await page.evaluate(() => window.presentationTests.mount());
      await page.waitForFunction(() => document.getElementById('phase')?.textContent === 'idle');
      const result = await page.evaluate(async () => {
        const p = window.presentationTests;
        const ride = await p.workouts.start({ indoor: true, useWatch: false, saveToHealth: false, recordGPS: false, sampleHz: 8 });
        await p.emitFrames(0, 4); const before = { ...p.counts };
        await p.emitFrames(4, 24); const foreground = { ...p.counts };
        const hiddenRouteLoads = p.catalogLoads;
        p.setHistoryVisible(true); await new Promise(resolve => setTimeout(resolve, 80));
        const visibleRouteLoads = p.catalogLoads;
        p.setHistoryVisible(false); await p.workouts.pause(ride.id!); await p.workouts.resume(ride.id!);
        await new Promise(resolve => setTimeout(resolve, 30)); const afterRouteHide = p.catalogLoads;
        p.setHistoryVisible(true); await new Promise(resolve => setTimeout(resolve, 80));
        p.visibility(false); await new Promise(resolve => setTimeout(resolve, 20)); const hiddenStart = { ...p.counts };
        const backgroundLoads = p.catalogLoads;
        await p.workouts.pause(ride.id!); await p.workouts.resume(ride.id!);
        await p.emitFrames(28, 16); const hiddenEnd = { ...p.counts };
        const afterBackgroundLoads = p.catalogLoads;
        p.visibility(true); await new Promise(resolve => setTimeout(resolve, 100));
        await p.workouts.stop(ride.id!);
        return { before, foreground, hiddenStart, hiddenEnd, hiddenRouteLoads, visibleRouteLoads, afterRouteHide, backgroundLoads, afterBackgroundLoads,
          rows: (await p.recorded(ride.id!)).map(row => row.originalSequence) };
      });
      expect(result.foreground.session).toBe(result.before.session);
      expect(result.foreground.identity).toBe(result.before.identity);
      expect(result.foreground.workout - result.before.workout).toBeLessThanOrEqual(4);
      expect(result.hiddenEnd).toEqual(result.hiddenStart);
      expect(result.hiddenRouteLoads).toBe(0); expect(result.visibleRouteLoads).toBeGreaterThan(0);
      expect(result.afterRouteHide).toBe(result.visibleRouteLoads);
      expect(result.afterBackgroundLoads).toBe(result.backgroundLoads);
      expect(result.rows).toEqual(Array.from({ length: 44 }, (_, i) => i));
    } finally { await browser.close(); }
  }, 15000);
});
