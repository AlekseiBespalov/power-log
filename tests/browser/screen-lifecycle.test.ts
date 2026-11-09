import path from 'node:path';
import { chromium, type Browser, type Page } from '@playwright/test';
import { afterAll, beforeAll, expect, it } from 'vitest';
import { browserTestBundle } from '../helpers/browser-test-bundle';
import type * as Harness from './screen-lifecycle-harness';

declare global {
  interface Window {
    lifecycle: typeof Harness;
  }
}
const bundle = browserTestBundle('tests/browser/screen-lifecycle-harness.ts', 'lifecycle', {
  react: path.resolve('node_modules/react/cjs/react.production.js'),
  'react-dom': path.resolve('node_modules/react-dom/cjs/react-dom.production.js'),
  'react-dom/client': path.resolve('node_modules/react-dom/cjs/react-dom-client.production.js'),
  scheduler: path.resolve('node_modules/scheduler/cjs/scheduler.production.js'),
  'expo-router': path.resolve('tests/browser/screen-lifecycle-router.ts'),
});
let browser: Browser;
beforeAll(async () => {
  browser = await chromium.launch({ headless: true });
});
afterAll(async () => {
  await browser?.close();
});

const calls = (page: Page) => page.evaluate(() => window.lifecycle.snapshot().calls);

it('closes a holder while its route loses focus or the app leaves the foreground, keeping its snapshot', async () => {
  const page = await browser.newPage();
  try {
    await page.setContent('<!doctype html><title>Screen lifecycle</title>');
    await page.addScriptTag({ content: bundle });
    await page.evaluate(() => window.lifecycle.mount());
    await page.waitForFunction(() => window.lifecycle.snapshot().calls.includes('activate'));

    await page.evaluate(() => window.lifecycle.setFocused(false));
    await page.waitForFunction(() => window.lifecycle.snapshot().calls.at(-1) === 'deactivate');
    await page.evaluate(() => window.lifecycle.setFocused(true));
    await page.waitForFunction(() => window.lifecycle.snapshot().calls.at(-1) === 'activate');
    await page.evaluate(() => window.lifecycle.setForeground(false));
    await page.waitForFunction(() => window.lifecycle.snapshot().calls.at(-1) === 'deactivate');
    expect(await page.evaluate(() => window.lifecycle.snapshot().shown)).toBe('2');
    await page.evaluate(() => window.lifecycle.setForeground(true));
    await page.waitForFunction(() => window.lifecycle.snapshot().calls.at(-1) === 'activate');

    expect(await calls(page)).toEqual(['activate', 'deactivate', 'activate', 'deactivate', 'activate']);
  } finally {
    await page.close();
  }
});
