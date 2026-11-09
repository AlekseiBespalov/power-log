import path from 'node:path';
import { afterAll, beforeAll, it } from 'vitest';
import { chromium, expect as ui, type Browser, type Page } from '@playwright/test';
import { browserTestBundle } from '../helpers/browser-test-bundle';
import type * as Harness from '../fixtures/settings-ui-harness';

declare global {
  interface Window {
    settingsUI: typeof Harness;
  }
}
const platform = path.resolve('tests/fixtures/watch-ui-platform.tsx');
const aliases: Record<string, string> = {
  react: path.resolve('node_modules/react/cjs/react.production.js'),
  'react/jsx-runtime': path.resolve('node_modules/react/cjs/react-jsx-runtime.production.js'),
  'react-dom': path.resolve('node_modules/react-dom/cjs/react-dom.production.js'),
  'react-dom/client': path.resolve('node_modules/react-dom/cjs/react-dom-client.production.js'),
  scheduler: path.resolve('node_modules/scheduler/cjs/scheduler.production.js'),
  'react-native': path.resolve('node_modules/react-native-web/dist/cjs/index.js'),
  'react-native-reanimated': platform,
  'expo-router': platform,
  'react-native-safe-area-context': platform,
};
for (const name of [
  'src/components/haptics',
  'src/components/icon',
  'src/components/app-shell',
  'src/components/modal-dialog',
])
  aliases[path.resolve(name)] = platform;
aliases[path.resolve('modules/power-log')] = path.resolve('tests/fixtures/watch-ui-native.ts');
aliases[path.resolve('modules/power-log/src/transport')] = path.resolve(
  'modules/power-log/src/testing/fake-transport.ts',
);
const bundle =
  'var __DEV__ = false;' +
  browserTestBundle('tests/fixtures/settings-ui-harness.tsx', 'settingsUI', aliases, { resolvePackages: true });
let browser: Browser;
beforeAll(async () => {
  browser = await chromium.launch({ headless: true });
});
afterAll(async () => {
  await browser?.close();
});
async function settings(work: (page: Page) => Promise<void>) {
  const page = await browser.newPage();
  try {
    await page.setContent('<!doctype html><title>Settings</title>');
    await page.addScriptTag({ content: bundle });
    await page.evaluate(() => window.settingsUI.mount());
    await work(page);
  } finally {
    await page.close();
  }
}

it('keeps Share diagnostics in view and the raw Bluetooth capture under a collapsed Advanced section', () =>
  settings(async page => {
    const panel = page.getByTestId('bluetooth-diagnostics');
    await ui(panel.getByRole('button', { name: 'Share diagnostics', exact: true })).toBeEnabled();
    const advanced = panel.getByRole('button', { name: 'Advanced', exact: true });
    await ui(advanced).toHaveAttribute('aria-expanded', 'false');
    for (const name of ['Start capture', 'Export log', 'Clear'])
      await ui(panel.getByRole('button', { name, exact: true })).toHaveCount(0);
    await ui(panel).not.toContainText('Back to ride');
    await advanced.click();
    await ui(advanced).toHaveAttribute('aria-expanded', 'true');
    await ui(panel.getByText('No capture', { exact: true })).toBeVisible();
    await ui(panel.getByRole('button', { name: 'Start capture', exact: true })).toBeEnabled();
    await ui(panel.getByRole('button', { name: 'Export log', exact: true })).toBeDisabled();
    await ui(panel).toContainText('Back to ride');
    await advanced.click();
    await ui(panel.getByRole('button', { name: 'Start capture', exact: true })).toHaveCount(0);
  }));
