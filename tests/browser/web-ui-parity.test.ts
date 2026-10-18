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
  'src/services/monitor-preferences',
  'src/services/workouts',
  'src/services/haptics',
  'src/components/modal-dialog',
  'src/components/icon',
  'src/features/monitor/sortable-metrics',
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
it.each(['running', 'paused', 'interrupted'].flatMap(phase => ['ios', 'android', 'web'].map(os => ({ phase, os }))))(
  'requires confirmation before saving the local $phase ride on $os',
  async ({ phase, os }) =>
    run(async page => {
      await page.evaluate(
        ({ phase, os }) => {
          window.webParity.setPlatform(os as 'ios' | 'android' | 'web');
          window.webParity.setRidePhase(phase as 'running' | 'paused' | 'interrupted');
          window.webParity.mount('ride');
        },
        { phase, os },
      );
      await page.getByRole('button', { name: 'Finish', exact: true }).click();
      const sheet = page.getByTestId('finish-ride-sheet');
      await ui(sheet).toBeVisible();
      expect(await page.evaluate(() => window.webParity.stops.length)).toBe(0);
      await ui(sheet.getByRole('button', { name: 'Discard ride', exact: true })).toBeEnabled();
      await sheet.getByRole('button', { name: 'Save ride', exact: true }).click();
      await ui.poll(() => page.evaluate(() => window.webParity.stops.length)).toBe(1);
      await ui(sheet).toHaveCount(0);
    }),
);
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
  'shows the enabled Cancel or Disconnect control during %s connection setup',
  async os =>
    run(async page => {
      await page.evaluate(os => window.webParity.setPlatform(os), os);
      for (const status of ['connecting', 'reconnecting', 'connected'] as const) {
        await page.evaluate(status => window.webParity.setConnection(status), status);
        const label = status === 'connected' ? 'Disconnect' : 'Cancel connection';
        const button = page.getByRole('button', { name: label, exact: true });
        await ui(button).toBeEnabled();
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

it.each(['ios', 'android', 'web'] as const)('matches iPhone setup hierarchy and ride badges on %s', async os =>
  run(async page => {
    await page.evaluate(os => {
      window.webParity.setPlatform(os);
      window.webParity.setConnection('idle');
      window.webParity.mount('ride');
    }, os);
    await page.getByTestId('ride-setup').click();
    const setup = page.getByTestId('ride-setup-sheet');
    await ui(setup.getByRole('heading', { name: 'Ride setup', exact: true })).toHaveCSS('font-size', '18px');
    await ui(setup.getByRole('button', { name: 'Done', exact: true })).toHaveCSS(
      'background-color',
      'rgba(0, 0, 0, 0)',
    );
    await ui(setup).not.toContainText('This ride will record');
    await ui(setup).not.toContainText('Connection details');
    await ui(setup).not.toContainText('Permissions');
    await ui(setup).toContainText('Connect your bike to start.');
    await ui(setup.getByRole('link', { name: 'Ride settings', exact: true })).toBeVisible();
    await ui(setup.getByTestId('setup-start-ride')).toBeDisabled();
    await ui(setup.getByTestId('setup-find-bike')).toHaveCSS('background-color', 'rgb(255, 135, 60)');
    await page.evaluate(() => window.webParity.setConnection('connected'));
    await setup.getByTestId('setup-start-ride').click();
    const bar = page.getByTestId('ride-controls');
    await ui(bar.getByTestId('ride-state-badge')).toHaveText('Running');
    await bar.getByRole('button', { name: 'Pause', exact: true }).click();
    await ui(bar.getByTestId('ride-state-badge')).toHaveText('Paused');
    await ui(bar.getByRole('button', { name: 'Lap', exact: true })).toBeDisabled();
    await bar.getByRole('button', { name: 'Resume', exact: true }).click();
    await ui(bar.getByTestId('ride-state-badge')).toHaveText('Running');
    await page.evaluate(() => window.webParity.setRidePhase('finishing'));
    await ui(bar.getByTestId('ride-state-badge')).toHaveText('Finishing');
  }),
);

it.each(['ios', 'android'] as const)(
  'presents provider components independently of local completion in the %s workout layout',
  async os =>
    run(async page => {
      await page.evaluate(os => {
        window.webParity.setPlatform(os);
        window.webParity.setWorkoutHealth({
          phase: 'finishing',
          useWatch: true,
          saveToHealth: true,
          healthKitState: 'saved',
          componentResults: [
            {
              component: 'readback',
              sourceRevision: '1',
              required: true,
              state: 'pending',
              reason: 'locked',
              providerIds: [],
            },
          ],
        });
        window.webParity.mount('ride');
      }, os);
      await page.getByTestId('ride-setup').click();
      const rows = page.getByTestId('workout-health-sync');
      await ui(rows).toContainText(os === 'ios' ? 'Apple Health saved' : 'Health Connect saved');
      await ui(rows).toContainText(
        `Health readback pending: ${os === 'ios' ? 'Apple Health' : 'Health Connect'} is locked. Unlock the device to allow Health access.`,
      );
      await ui(rows).not.toContainText('syncing');
    }),
);
