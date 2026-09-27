import path from 'node:path';
import { afterAll, beforeAll, expect, it } from 'vitest';
import { chromium, expect as ui, type Browser, type Page } from '@playwright/test';
import { browserTestBundle } from '../helpers/browser-test-bundle';
import type * as Harness from '../fixtures/web-ui-parity-harness';

declare global {
  interface Window {
    webParity: typeof Harness;
  }
}
const platform = path.resolve('tests/fixtures/web-ui-parity-platform.tsx');
const aliases: Record<string, string> = {
  react: path.resolve('node_modules/react/cjs/react.production.js'),
  'react/jsx-runtime': path.resolve('node_modules/react/cjs/react-jsx-runtime.production.js'),
  'react-dom': path.resolve('node_modules/react-dom/cjs/react-dom.production.js'),
  'react-dom/client': path.resolve('node_modules/react-dom/cjs/react-dom-client.production.js'),
  scheduler: path.resolve('node_modules/scheduler/cjs/scheduler.production.js'),
  'react-native': path.resolve('node_modules/react-native-web/dist/cjs/index.js'),
  'react-native-safe-area-context': platform,
  'expo-router': platform,
};
for (const name of [
  'src/services/session-context',
  'src/services/workout-context',
  'src/components/modal-dialog',
  'src/components/icon',
  'src/features/monitor/sortable-metrics',
  'src/features/dashboard/connection-health',
])
  aliases[path.resolve(name)] = platform;
const bundle = browserTestBundle('tests/fixtures/web-ui-parity-harness.tsx', 'webParity', aliases, {
  resolvePackages: true,
});
let browser: Browser;
beforeAll(async () => {
  browser = await chromium.launch({ headless: true });
});
afterAll(async () => {
  await browser?.close();
});
async function run(work: (page: Page) => Promise<void>) {
  const page = await browser.newPage();
  try {
    await page.setContent('<!doctype html><title>Web UI parity</title>');
    await page.addScriptTag({ content: bundle });
    await page.evaluate(() => window.webParity.mount());
    await work(page);
  } finally {
    await page.close();
  }
}
it.each(['web', 'android'] as const)('clears metric search on %s while retaining input focus', async os =>
  run(async page => {
    await page.evaluate(os => window.webParity.setPlatform(os), os);
    const input = page.getByRole('textbox', { name: 'Search metrics' });
    await input.fill('temperature');
    await ui(page.getByRole('checkbox', { name: 'Rider power', exact: true })).toHaveCount(0);
    const clear = page.getByRole('button', { name: 'Clear search', exact: true });
    if (os === 'web') await clear.click();
    else await clear.dispatchEvent('click');
    await ui(input).toHaveValue('');
    await ui(input).toBeFocused();
    await ui(page.getByRole('button', { name: 'Clear search', exact: true })).toHaveCount(0);
    await ui(page.getByRole('checkbox', { name: 'Rider power', exact: true })).toBeVisible();
    await input.fill('cadence');
    await page.getByRole('button', { name: 'Switch tab', exact: true }).focus();
    await ui(page.getByRole('button', { name: 'Clear search', exact: true })).toHaveCount(0);
  }),
);
it.each(['web', 'android', 'ios'] as const)(
  'shows the enabled Disconnect control during %s connection setup',
  async os =>
    run(async page => {
      await page.evaluate(os => window.webParity.setPlatform(os), os);
      for (const status of ['connecting', 'reconnecting', 'connected'] as const) {
        await page.evaluate(status => window.webParity.setConnection(status), status);
        const button = page.getByRole('button', { name: 'Disconnect', exact: true });
        await ui(button).toBeEnabled();
        await ui(page.getByRole('button', { name: 'Cancel connection', exact: true })).toHaveCount(0);
        await button.click();
      }
      expect(await page.evaluate(() => window.webParity.disconnects)).toEqual([
        'disconnect',
        'disconnect',
        'disconnect',
      ]);
    }),
);
it('keeps route content stationary on a web tab switch', async () =>
  run(async page => {
    const content = page.getByTestId('route-content');
    await ui(content).toHaveText('Route 0');
    await page.getByRole('button', { name: 'Switch tab', exact: true }).click();
    await ui(content).toHaveText('Route 1');
    const transforms = await content.evaluate(element => {
      const values: string[] = [];
      for (let current: Element | null = element; current; current = current.parentElement)
        values.push(getComputedStyle(current).transform);
      return values;
    });
    expect(transforms.every(value => value === 'none')).toBe(true);
  }));
