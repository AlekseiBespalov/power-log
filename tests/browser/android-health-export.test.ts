import { readFileSync } from 'node:fs';
import { afterAll, beforeAll, expect, it } from 'vitest';
import { chromium, type Browser } from '@playwright/test';
import { browserTestBundle } from '../helpers/browser-test-bundle';
import type * as Harness from '../fixtures/android-health-export-harness';
import { readNativeFixture } from '../fixtures/export/native';
import { readFit } from '../fixtures/export/fit/ride';
import { entryText, readZip } from '../fixtures/export/zip/harness';

declare global {
  interface Window {
    androidHealthExports: typeof Harness;
  }
}
const fixture = JSON.parse(
  readFileSync('tests/fixtures/export/android-health-native.json', 'utf8'),
) as Harness.Captured;
const bundle = browserTestBundle('tests/fixtures/android-health-export-harness.ts', 'androidHealthExports');
let browser: Browser;
beforeAll(async () => {
  browser = await chromium.launch({ headless: true });
});
afterAll(async () => {
  await browser?.close();
});
async function exported(kind: 'zip' | 'fit', input = fixture, platform: 'ios' | 'android' = 'android') {
  const page = await browser.newPage();
  try {
    await page.addScriptTag({ content: bundle });
    return Uint8Array.from(
      await page.evaluate(({ fixture, kind, platform }) => window.androidHealthExports.run(fixture, kind, platform), {
        fixture: input,
        kind,
        platform,
      }),
    );
  } finally {
    await page.close();
  }
}
it('exports Android FIT through the browser and core N5 with the native golden', async () => {
  const file = await exported('fit');
  const golden = JSON.parse(readFileSync('tests/fixtures/export/android-health.fit.json', 'utf8')) as {
    base64: string;
  };
  expect(Buffer.from(file).toString('base64')).toBe(golden.base64);
  expect(
    readFit(file)
      .find(message => message.global === 18)!
      .fields.get(11),
  ).toBe(10);
});
it('exports Android ZIP through browser compression with retained provenance', async () => {
  const file = await exported('zip');
  const entries = Object.fromEntries(readZip(file).map(entry => [entry.name, entryText(entry)]));
  const golden = JSON.parse(readFileSync('tests/fixtures/export/android-health.zip.json', 'utf8')) as Record<
    string,
    string
  >;
  expect(entries).toEqual(golden);
  expect(entries['PowerLog-original/health.csv']).toContain('healthConnect');
  expect(entries['PowerLog-original/health.csv']).toContain('app.generated.lab');
});

it.each(['ios', 'android'] as const)('clips N5 energy through %s production pages in the browser', async platform => {
  const path = platform === 'ios' ? 'review-ios' : 'energy-android';
  const captures = readNativeFixture(`tests/fixtures/contract/export/${path}.json`) as Record<
    string,
    Harness.Captured['fit']
  >;
  for (const [name, expected] of [
    ['energy-n5', 5],
    ['energy-spanning', 10],
  ] as const) {
    const captured = captures[name]!;
    const file = await exported('fit', { fit: captured, zip: captured }, platform);
    expect(
      readFit(file)
        .find(message => message.global === 18)!
        .fields.get(11),
    ).toBe(expected);
  }
});

it.each(['ios', 'android'] as const)(
  'reduces cross-page %s energy and basal operands in the browser',
  async platform => {
    const path = platform === 'ios' ? 'review-ios' : 'energy-android';
    const captures = readNativeFixture(`tests/fixtures/contract/export/${path}.json`) as Record<
      string,
      Harness.Captured['fit']
    >;
    const captured = captures['overlapping-energy']!;
    const file = await exported('fit', { fit: captured, zip: captured }, platform);
    expect(
      readFit(file)
        .find(message => message.global === 18)!
        .fields.get(11),
    ).toBe(110);
  },
);
