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
    await page.route('http://127.0.0.1:43183/**', route =>
      route.fulfill({ contentType: 'text/html', body: '<!doctype html><title>Plot summaries</title>' }),
    );
    await page.goto('http://127.0.0.1:43183/');
    await page.addScriptTag({ content: bundle });
    return await page.evaluate(work, arg);
  } finally {
    await context.close();
  }
}

it('extends live whole-ride plots from appended rows only and matches a fresh aligned reduction', async () => {
  const steps = await inPage(() => window.plotSummaries.wholeRideScenario());
  expect(steps.flatMap(step => step.mismatches.map(mismatch => `${step.label}: ${mismatch}`))).toEqual([]);
  const first = steps.findIndex(step => step.aligned);
  expect(first).toBeGreaterThan(0);
  for (const [index, step] of steps.entries()) {
    expect(step.reads['ride-tiles'] ?? 0, step.label).toBe(0);
    if (index < first) {
      expect(step.reads.samples, step.label).toBeGreaterThanOrEqual(step.telemetryCount);
      expect(step.reads.samples, step.label).toBeLessThanOrEqual(step.telemetryCount + 2);
      continue;
    }
    const tail = index === first ? 0 : step.newSamples;
    const rescanned = index === first || /late rows|equal-time row before/.test(step.label);
    expect(step.reads.samples ?? 0, step.label).toBe(tail + (rescanned ? step.telemetryCount : 0));
    expect(step.reads['ride-locations'] ?? 0, step.label).toBe(
      index === first ? step.locationCount : step.newLocations,
    );
    if (index > first && !/paused|lap/.test(step.label))
      expect(step.reads['distance-intervals'] ?? 0, step.label).toBeLessThanOrEqual(
        2 * step.newSamples + step.newLocations + 8,
      );
  }
}, 300_000);

it('rebuilds a live derived summary once an interval written earlier joins the profile', async () => {
  expect(await inPage(() => window.plotSummaries.unownedIntervalScenario())).toEqual([]);
}, 120_000);
