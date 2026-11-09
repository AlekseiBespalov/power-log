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
  'expo-modules-core': path.resolve('tests/helpers/expo-modules-core.ts'),
};
for (const name of [
  'src/components/haptics',
  'src/components/modal-dialog',
  'src/components/icon',
  'src/features/charts/sortable-metrics',
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

it.each(['ios', 'android', 'web'] as const)('uses the iPhone type tokens and minimum targets on %s', async os =>
  run(async page => {
    await page.evaluate(os => {
      window.webParity.setPlatform(os);
      window.webParity.mount('type');
    }, os);
    for (const [label, size] of [
      ['Label token', 11],
      ['Body token', 14],
      ['Title token', 18],
      ['Control token', 15],
      ['Caption token', 12],
    ] as const)
      await ui(page.getByText(label, { exact: true })).toHaveCSS('font-size', `${size}px`);
    for (const [id, size] of [
      ['value-token', 26],
      ['hero-token', 38],
    ] as const)
      await ui(page.getByTestId(id).getByText('237', { exact: true })).toHaveCSS('font-size', `${size}px`);
    const button = await page.getByRole('button', { name: 'Control token', exact: true }).boundingBox();
    expect(button!.width).toBeGreaterThanOrEqual(48);
    expect(button!.height).toBeGreaterThanOrEqual(48);
  }),
);
