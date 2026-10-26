import { afterAll, beforeAll, expect, it } from 'vitest';
import { chromium, type Browser } from '@playwright/test';
import { browserTestBundle } from '../helpers/browser-test-bundle';
import type * as Harness from './plot-summary-harness';

declare global {
  interface Window {
    plotSummaries: typeof Harness;
  }
}
const bundle = browserTestBundle('tests/browser/plot-summary-harness.ts', 'plotSummaries');
let browser: Browser;
beforeAll(async () => {
  browser = await chromium.launch({ headless: true });
});
afterAll(async () => {
  await browser?.close();
});
async function inPage<T>(work: (arg: string[]) => Promise<T>, arg: string[] = []): Promise<T> {
  const context = await browser.newContext();
  try {
    const page = await context.newPage();
    await page.route('http://127.0.0.1:43184/**', route =>
      route.fulfill({ contentType: 'text/html', body: '<!doctype html><title>Energy checkpoints</title>' }),
    );
    await page.goto('http://127.0.0.1:43184/');
    await page.addScriptTag({ content: bundle });
    return await page.evaluate(work, arg);
  } finally {
    await context.close();
  }
}

it.each([
  ['controller', 'calculated'],
  ['gps:phone', 'controller'],
] as const)(
  'resumes whole-ride Wh/km totals from a checkpoint bit-identically: %s distance, %s battery',
  async (distance, battery) => {
    const steps = await inPage(
      ([distance, battery]) =>
        window.plotSummaries.energyScenario(distance as 'controller' | 'gps:phone', battery as 'controller'),
      [distance, battery],
    );
    expect(steps.flatMap(step => step.mismatches.map(mismatch => `${step.label}: ${mismatch}`))).toEqual([]);
    for (const [index, step] of steps.entries()) {
      if (index === 0 || /paused|lap|late rows/.test(step.label)) {
        expect(step.reads.samples, step.label).toBeGreaterThanOrEqual(step.telemetryCount);
        continue;
      }
      const span = /after out-of-order/.test(step.label) ? 2 : 1;
      expect(step.reads.samples, step.label).toBeLessThanOrEqual(2 * span * step.newSamples + 16);
      expect(step.reads['ride-locations'] ?? 0, step.label).toBeLessThanOrEqual(2 * span * step.newLocations + 16);
      expect(step.reads['distance-intervals'] ?? 0, step.label).toBeLessThanOrEqual(
        4 * span * (step.newSamples + step.newLocations) + 32,
      );
    }
    expect(steps.at(-1)!.reads.samples).toBeLessThan(steps.at(-1)!.telemetryCount / 4);
  },
  300_000,
);
