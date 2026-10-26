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
    await page.route('http://127.0.0.1:43185/**', route =>
      route.fulfill({ contentType: 'text/html', body: '<!doctype html><title>Energy series</title>' }),
    );
    await page.goto('http://127.0.0.1:43185/');
    await page.addScriptTag({ content: bundle });
    return await page.evaluate(work, arg);
  } finally {
    await context.close();
  }
}

it.each(['controller', 'gps:phone'])(
  'reads whole-ride Wh/km series on the aligned grid from an incremental cache: %s distance',
  async distance => {
    const steps = await inPage(
      ([distance]) => window.plotSummaries.energySeriesScenario(distance as 'controller' | 'gps:phone'),
      [distance],
    );
    expect(steps.flatMap(step => step.mismatches.map(mismatch => `${step.label}: ${mismatch}`))).toEqual([]);
    expect(steps.some(step => step.aligned && step.gridMatters)).toBe(true);
    for (const [index, step] of steps.entries()) {
      if (index === 0 || /late rows/.test(step.label)) {
        expect(step.reads.samples, step.label).toBeGreaterThanOrEqual(step.telemetryCount);
        continue;
      }
      expect(step.reads.samples, step.label).toBeLessThanOrEqual(2 * step.newSamples + 16);
      expect(step.reads['ride-locations'] ?? 0, step.label).toBeLessThanOrEqual(2 * step.newLocations + 16);
    }
    expect(steps.at(-1)!.reads.samples).toBeLessThan(steps.at(-1)!.telemetryCount / 4);
  },
  300_000,
);
