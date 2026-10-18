import path from 'node:path';
import { beforeAll, afterAll, expect, it } from 'vitest';
import { chromium, expect as ui, type Browser, type Page } from '@playwright/test';
import { browserTestBundle } from '../helpers/browser-test-bundle';
import type * as Harness from '../fixtures/watch-ui-harness';
declare global {
  interface Window {
    watchUI: typeof Harness;
  }
}
const busy = 'Power Log is busy saving your ride. Try again in a moment.';
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
  'src/services/session-context',
  'src/services/haptics',
  'src/services/monitor-preferences-store',
  'src/services/workout-monitor-source',
  'src/services/ride-export',
  'src/services/files',
  'src/components/icon',
  'src/components/app-shell',
  'src/components/modal-dialog',
  'src/components/route-preview',
  'src/features/monitor/monitor-panel',
])
  aliases[path.resolve(name)] = platform;
aliases[path.resolve('src/services/workouts')] = path.resolve('src/services/workouts.native.ts');
aliases[path.resolve('modules/power-log-bridge')] = path.resolve('tests/fixtures/watch-ui-native.ts');
const bundle =
  'var __DEV__ = false;' +
  browserTestBundle('tests/fixtures/watch-ui-harness.tsx', 'watchUI', aliases, { resolvePackages: true });
let browser: Browser;
beforeAll(async () => {
  browser = await chromium.launch({ headless: true });
});
afterAll(async () => {
  await browser?.close();
});
async function run(
  os: 'ios' | 'android' | 'web',
  surface: 'ride' | 'history' | 'settings',
  work: (page: Page) => Promise<void>,
  phase: 'interrupted' | 'running' | 'completed' | 'failed' = 'interrupted',
  initial: {
    record?: Partial<import('../../src/core/workouts').WorkoutMetadata>;
    state?: Partial<import('../../src/core/workouts').WorkoutState>;
  } = {},
) {
  const page = await browser.newPage();
  try {
    await page.setContent('<!doctype html><title>Watch protocol UI</title>');
    await page.addScriptTag({ content: bundle });
    await page.evaluate(
      ({ os, surface, phase, initial }) => {
        window.watchUI.setPlatform(os);
        window.watchUI.seed(os, phase);
        if (initial.record) window.watchUI.patchRecord(initial.record);
        if (initial.state) window.watchUI.patchState(initial.state);
        window.watchUI.mount(surface);
      },
      { os, surface, phase, initial },
    );
    await work(page);
  } finally {
    await page.close();
  }
}
it.each(['ios', 'android'] as const)('History renders one accessible Health summary on %s', os =>
  run(
    os,
    'history',
    async page => {
      const row = page.getByRole('button', { name: /^Open ride/ });
      const provider = os === 'ios' ? 'Apple Health' : 'Health Connect';
      const status = `${provider} save pending · 4 of 9 saved`;
      const summary = row.getByTestId('ride-health-sync-summary');
      await ui(summary).toHaveCount(1, { timeout: 1000 });
      await ui(summary).toHaveText(status);
      await ui(row).toHaveAccessibleName(new RegExp(status));
      expect((await row.getAttribute('aria-label'))?.split(status)).toHaveLength(2);
      await ui(row.getByTestId('ride-health-sync')).toHaveCount(0);
      await ui(row.getByText('Detailed Health results are on the Watch.', { exact: true })).toHaveCount(0);
      await ui(summary).toHaveCSS('white-space', 'nowrap');
      await ui(summary).toHaveCSS('text-overflow', 'ellipsis');
    },
    'completed',
    {
      record: {
        healthProvider: os === 'ios' ? 'appleHealth' : 'healthConnect',
        healthKitState: 'pending',
        watchEnabled: os === 'ios',
        componentResults: [
          'workout',
          'route',
          'laps',
          'rider-power',
          'cycling-cadence',
          'cycling-speed',
          'cycling-distance',
          'association',
          'readback',
        ].map((component, index) => ({
          component: component as import('../../src/core/workouts').HealthComponent,
          sourceRevision: '1',
          required: true,
          providerIds: [],
          state: index < 4 ? 'saved' : 'pending',
        })),
      },
    },
  ),
);

it('History keeps all nine per-item Health results on the ride page', () =>
  run(
    'ios',
    'history',
    async page => {
      const row = page.getByRole('button', { name: /^Open ride/ });
      await ui(row.getByTestId('ride-health-sync-summary')).toHaveText('Apple Health save pending · 0 of 9 saved');
      await row.click();
      const health = page.getByTestId('ride-health-sync');
      await ui(health).toBeVisible();
      await ui(health.getByText('Apple Health save pending', { exact: true })).toBeVisible();
      await ui(health.getByText('Detailed Health results are on the Watch.', { exact: true })).toBeVisible();
      for (const label of [
        'Workout',
        'Route',
        'Laps',
        'Rider power',
        'Cycling cadence',
        'Cycling speed',
        'Cycling distance',
        'Workout association',
        'Health readback',
      ])
        await ui(health.getByText(`${label} pending`, { exact: true })).toBeVisible();
      await ui(page.getByRole('button', { name: 'Export FIT', exact: true })).toBeEnabled();
      await ui(page.getByTestId('ride-health-sync-summary')).toHaveCount(0);
    },
    'completed',
    {
      record: {
        healthKitState: 'pending',
        saveToHealth: true,
        componentResults: [
          'workout',
          'route',
          'laps',
          'rider-power',
          'cycling-cadence',
          'cycling-speed',
          'cycling-distance',
          'association',
          'readback',
        ].map(component => ({
          component: component as import('../../src/core/workouts').HealthComponent,
          sourceRevision: '1',
          required: true,
          providerIds: [],
          state: 'pending',
        })),
      },
    },
  ));

it('History omits Health summaries for browser rides', () =>
  run(
    'web',
    'history',
    async page => {
      const row = page.getByRole('button', { name: /^Open ride/ });
      await ui(row).toBeVisible();
      await ui(row.getByTestId('ride-health-sync-summary')).toHaveCount(0);
      await ui(row.getByTestId('ride-health-sync')).toHaveCount(0);
      await ui(row).not.toHaveAccessibleName(/Health|Watch|Saved only in Power Log/);
    },
    'completed',
    { record: { storage: 'browser', watchEnabled: false } },
  ));
it('refreshes Health data independently after local completion', () =>
  run(
    'ios',
    'history',
    async page => {
      await page.getByRole('button', { name: /^Open ride/ }).click();
      const sync = page.getByRole('button', { name: 'Refresh Health data', exact: true });
      await ui(sync).toBeEnabled({ timeout: 1000 });
      await ui(page.getByRole('button', { name: 'Retry', exact: true })).toHaveCount(0);
      await sync.click();
      await page.waitForFunction(() => window.watchUI.calls.refresh.length === 1);
    },
    'completed',
  ));
it('does not show unrelated ready ride errors in Settings', () =>
  run(
    'ios',
    'settings',
    async page => {
      await ui(page.getByTestId('settings-distance')).toBeVisible();
      await page.evaluate(() => window.watchUI.failRide('storageBusy'));
      await ui(page.getByTestId('watch-state')).toContainText(`"error":"${busy}"`);
      await ui(page.getByText(busy, { exact: true })).toHaveCount(0, { timeout: 1000 });
    },
    'running',
  ));

const externalFinish = {
  rideId: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
  controlToken: '66666666-6666-4666-8666-666666666666',
  expectedPhase: 'running',
  action: 'finish' as const,
};
it('opens the existing Android Finish sheet from the notification and cancels without a command', () =>
  run(
    'android',
    'ride',
    async page => {
      await page.evaluate(
        request => window.watchUI.patchState({ controlToken: request.controlToken, externalFinish: request }),
        externalFinish,
      );
      const sheet = page.getByTestId('finish-ride-sheet');
      await ui(sheet).toBeVisible();
      expect(await page.evaluate(() => window.watchUI.calls.external)).toEqual([]);
      await sheet.getByRole('button', { name: 'Keep recording', exact: true }).click();
      await ui(sheet).toHaveCount(0);
      expect(await page.evaluate(() => window.watchUI.calls.external)).toEqual([]);
      expect(await page.evaluate(() => window.watchUI.calls.cancelExternal)).toEqual([externalFinish.controlToken]);
    },
    'running',
  ));
it('revalidates the Android notification request after Save in the shared Finish sheet', () =>
  run(
    'android',
    'ride',
    async page => {
      await page.evaluate(
        request => window.watchUI.patchState({ controlToken: request.controlToken, externalFinish: request }),
        externalFinish,
      );
      const sheet = page.getByTestId('finish-ride-sheet');
      await ui(sheet).toBeVisible();
      expect(await page.evaluate(() => window.watchUI.calls.external)).toEqual([]);
      await sheet.getByRole('button', { name: 'Save ride', exact: true }).click();
      await ui(sheet).toHaveCount(0);
      expect(await page.evaluate(() => window.watchUI.calls.external)).toEqual([
        { request: externalFinish, discard: false },
      ]);
    },
    'running',
  ));
it('closes a notification confirmation when its phase changes', () =>
  run(
    'android',
    'ride',
    async page => {
      await page.evaluate(
        request => window.watchUI.patchState({ controlToken: request.controlToken, externalFinish: request }),
        externalFinish,
      );
      await ui(page.getByTestId('finish-ride-sheet')).toBeVisible();
      await page.evaluate(() => window.watchUI.patchState({ phase: 'paused' }));
      await ui(page.getByTestId('finish-ride-sheet')).toHaveCount(0);
      expect(await page.evaluate(() => window.watchUI.calls.external)).toEqual([]);
    },
    'running',
  ));

it.each(['ios', 'android', 'web'] as const)(
  'disarms a screen Finish confirmation when recording becomes interrupted on %s',
  os =>
    run(
      os,
      'ride',
      async page => {
        await page.evaluate(() => window.watchUI.patchState({ useWatch: false, phase: 'paused' }));
        await ui(page.getByTestId('watch-state')).toContainText('"phase":"paused"');
        await page.getByRole('button', { name: 'Finish', exact: true }).click();
        const sheet = page.getByTestId('finish-ride-sheet');
        await ui(sheet).toBeVisible();
        await sheet.getByRole('button', { name: 'Discard ride', exact: true }).click();
        await ui(sheet.getByRole('button', { name: 'Discard for good', exact: true })).toBeVisible();
        await page.evaluate(() => window.watchUI.patchState({ phase: 'interrupted' }));
        await ui(sheet).toHaveCount(0, { timeout: 1000 });
        await ui(page.getByTestId('watch-state')).toContainText('"phase":"interrupted"');
        await page.getByRole('button', { name: 'Finish', exact: true }).click();
        await ui(sheet.getByRole('button', { name: 'Save ride', exact: true })).toBeEnabled();
        await ui(sheet.getByRole('button', { name: 'Discard for good', exact: true })).toHaveCount(0);
        expect(await page.evaluate(() => window.watchUI.calls.external)).toEqual([]);
      },
      'running',
    ),
);
it.each([
  ['ios', 'Save'],
  ['ios', 'Discard'],
  ['android', 'Save'],
  ['android', 'Discard'],
  ['web', 'Save'],
  ['web', 'Discard'],
] as const)('keeps screen Finish and %s %s through completion of a pending Pause', (os, choice) =>
  run(
    os,
    'ride',
    async page => {
      await page.evaluate(
        token => window.watchUI.patchState({ useWatch: false, pendingAction: 'pause', controlToken: token }),
        externalFinish.controlToken,
      );
      await page.getByRole('button', { name: 'Finish', exact: true }).click();
      const sheet = page.getByTestId('finish-ride-sheet');
      await ui(sheet).toBeVisible();
      expect(
        await page.evaluate(() => ({ stop: window.watchUI.calls.stop, discard: window.watchUI.calls.discard })),
      ).toEqual({ stop: [], discard: [] });
      await page.evaluate(() =>
        window.watchUI.patchState({
          phase: 'paused',
          pendingAction: null,
          controlToken: '77777777-7777-4777-8777-777777777777',
        }),
      );
      await ui(page.getByTestId('watch-state')).toContainText('"phase":"paused"');
      await ui(sheet).toBeVisible({ timeout: 1000 });
      if (choice === 'Discard') await sheet.getByRole('button', { name: 'Discard ride', exact: true }).click();
      await sheet
        .getByRole('button', { name: choice === 'Save' ? 'Save ride' : 'Discard for good', exact: true })
        .click();
      await ui(sheet).toHaveCount(0);
      expect(
        await page.evaluate(() => ({
          stop: window.watchUI.calls.stop,
          discard: window.watchUI.calls.discard,
          external: window.watchUI.calls.external,
        })),
      ).toEqual({
        stop: choice === 'Save' ? [externalFinish.rideId] : [],
        discard: choice === 'Discard' ? [externalFinish.rideId] : [],
        external: [],
      });
    },
    'running',
  ),
);

it.each(['Save', 'Discard'] as const)('keeps screen Finish and %s through a Watch-originated Pause', choice =>
  run(
    'ios',
    'ride',
    async page => {
      await page.getByRole('button', { name: 'Finish', exact: true }).click();
      const sheet = page.getByTestId('finish-ride-sheet');
      if (choice === 'Discard') await sheet.getByRole('button', { name: 'Discard ride', exact: true }).click();
      await page.evaluate(() => window.watchUI.patchState({ phase: 'paused', pendingAction: null }));
      await ui(page.getByTestId('watch-state')).toContainText('"phase":"paused"');
      await ui(sheet).toBeVisible({ timeout: 1000 });
      await sheet
        .getByRole('button', { name: choice === 'Save' ? 'Save ride' : 'Discard for good', exact: true })
        .click();
      await ui(sheet).toHaveCount(0);
      expect(
        await page.evaluate(() => ({ stop: window.watchUI.calls.stop, discard: window.watchUI.calls.discard })),
      ).toEqual({
        stop: choice === 'Save' ? [externalFinish.rideId] : [],
        discard: choice === 'Discard' ? [externalFinish.rideId] : [],
      });
    },
    'running',
  ),
);

it.each(['ios', 'android', 'web'] as const)(
  'disarms a screen Finish confirmation when the active ride changes on %s',
  os =>
    run(
      os,
      'ride',
      async page => {
        await page.getByRole('button', { name: 'Finish', exact: true }).click();
        const sheet = page.getByTestId('finish-ride-sheet');
        await sheet.getByRole('button', { name: 'Discard ride', exact: true }).click();
        await page.evaluate(() => window.watchUI.patchState({ id: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb' }));
        await ui(sheet).toHaveCount(0, { timeout: 1000 });
        await page.evaluate(() => window.watchUI.patchState({ id: window.watchUI.rideId }));
        await ui(sheet).toHaveCount(0, { timeout: 1000 });
        await page.getByRole('button', { name: 'Finish', exact: true }).click();
        await ui(sheet.getByRole('button', { name: 'Save ride', exact: true })).toBeEnabled();
        await ui(sheet.getByRole('button', { name: 'Discard for good', exact: true })).toHaveCount(0);
        expect(await page.evaluate(() => window.watchUI.calls.discard)).toEqual([]);
      },
      'running',
    ),
);

it.each(['ios', 'android', 'web'] as const)(
  'keeps screen Finish and armed Discard through a control token change on %s',
  os =>
    run(
      os,
      'ride',
      async page => {
        await page.getByRole('button', { name: 'Finish', exact: true }).click();
        const sheet = page.getByTestId('finish-ride-sheet');
        await sheet.getByRole('button', { name: 'Discard ride', exact: true }).click();
        await page.evaluate(() => window.watchUI.patchState({ controlToken: '77777777-7777-4777-8777-777777777777' }));
        await ui(page.getByTestId('watch-state')).toContainText(
          '"controlToken":"77777777-7777-4777-8777-777777777777"',
        );
        await ui(sheet).toBeVisible({ timeout: 1000 });
        await sheet.getByRole('button', { name: 'Discard for good', exact: true }).click();
        await ui(sheet).toHaveCount(0);
        expect(await page.evaluate(() => window.watchUI.calls.discard)).toEqual([externalFinish.rideId]);
      },
      'running',
    ),
);

it('closes and disarms a notification Finish confirmation on a token change', () =>
  run(
    'android',
    'ride',
    async page => {
      await page.evaluate(
        request => window.watchUI.patchState({ controlToken: request.controlToken, externalFinish: request }),
        externalFinish,
      );
      const sheet = page.getByTestId('finish-ride-sheet');
      await ui(sheet).toBeVisible();
      await sheet.getByRole('button', { name: 'Discard ride', exact: true }).click();
      await page.evaluate(() => window.watchUI.patchState({ controlToken: '77777777-7777-4777-8777-777777777777' }));
      await ui(page.getByTestId('watch-state')).toContainText('"controlToken":"77777777-7777-4777-8777-777777777777"');
      await ui(sheet).toHaveCount(0, { timeout: 1000 });
      expect(await page.evaluate(() => window.watchUI.calls.external)).toEqual([]);
      await page.evaluate(() => window.watchUI.patchState({ externalFinish: null }));
      await page.getByRole('button', { name: 'Finish', exact: true }).click();
      await ui(sheet.getByRole('button', { name: 'Save ride', exact: true })).toBeEnabled();
      await ui(sheet.getByRole('button', { name: 'Discard for good', exact: true })).toHaveCount(0);
    },
    'running',
  ));

const repairRecord = {
  watchEnabled: false,
  saveToHealth: true,
  healthProvider: 'healthConnect' as const,
  healthKitState: 'saved' as const,
  phase: 'completed' as const,
  endedAt: '2026-09-09T00:00:02.000Z',
  exportEligible: true,
};
it('preserves the browser historical completion without a Health repair action', () =>
  run(
    'web',
    'history',
    async page => {
      await page.getByRole('button', { name: /^Open ride/ }).click();
      await ui(page.getByRole('button', { name: 'Retry', exact: true })).toHaveCount(0, { timeout: 1000 });
      await ui(page.getByRole('button', { name: 'Finish saving', exact: true })).toHaveCount(0, { timeout: 1000 });
    },
    'completed',
    {
      record: {
        ...repairRecord,
        storage: 'browser',
        saveToHealth: false,
        healthKitState: 'notRequested',
      },
      state: { id: null, phase: 'idle', pendingAction: null, recoveryState: 'idle', useWatch: false },
    },
  ));
