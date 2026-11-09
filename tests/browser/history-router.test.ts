import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { chromium, expect as ui, type Browser, type Page } from '@playwright/test';
import { startHistoryRouterServer } from './history-router-server';
import type * as Harness from './history-router-entry';
import { expectTouchTargets } from '../helpers/ui-targets';

declare global {
  interface Window {
    historyRouterTests: typeof Harness;
  }
}
let origin: string;
let browser: Browser;
let stop: () => void;
beforeAll(async () => {
  ({ origin, stop } = await startHistoryRouterServer());
  browser = await chromium.launch({ headless: true });
}, 120000);
afterAll(async () => {
  stop?.();
  await browser?.close();
}, 60000);
async function run(route: string, work: (page: Page) => Promise<void>) {
  const context = await browser.newContext({ hasTouch: true });
  try {
    const page = await context.newPage();
    const errors: string[] = [];
    page.on('pageerror', error => errors.push(error.stack ?? error.message));
    page.setDefaultTimeout(5000);
    await page.route(`${origin}/**`, async request => {
      if (request.request().resourceType() === 'document')
        await request.fulfill({
          contentType: 'text/html',
          body: `<!doctype html><html><body><script src="/entry.bundle?platform=web&dev=false&minify=false"></script></body></html>`,
        });
      else await request.continue();
    });
    await page.goto(`${origin}${route}`);
    try {
      await page.waitForFunction(() => window.historyRouterTests?.routerReady() === true);
    } catch (error) {
      throw new Error(`Router did not mount: ${errors.join('\n')}`, { cause: error });
    }
    await work(page);
  } finally {
    await context.close();
  }
}
async function expectRide(page: Page, id: string) {
  await ui(page).toHaveURL(`${origin}/sessions/ride?id=${encodeURIComponent(id)}`);
  await ui(page.getByTestId('monitor-chart-power').filter({ visible: true })).toBeVisible();
  await page.waitForFunction(id => window.historyRouterTests.plottedRide() === id, id);
}
async function openMenu(page: Page) {
  if (!(await page.getByRole('button', { name: 'Open CSV', exact: true }).isVisible()))
    await page.getByRole('button', { name: 'History actions', exact: true }).click();
}
describe('History with the production Expo Router stack', { timeout: 30000 }, () => {
  it('opens a ride by deep link, reloads it, and returns to History', async () =>
    run('/sessions/ride?id=ride-0', async page => {
      await expectRide(page, 'ride-0');
      await ui(page.getByRole('link', { name: 'History', exact: true })).toHaveAttribute('aria-current', 'page');
      await page.reload();
      await expectRide(page, 'ride-0');
      await page.getByRole('button', { name: '‹ History', exact: true }).click();
      await ui(page).toHaveURL(/\/sessions$/);
      await ui(page.getByRole('button', { name: /^Open ride,/ })).toHaveCount(2);
      await page.getByRole('button', { name: 'History actions', exact: true }).click();
      const menu = page.getByTestId('history-actions-menu');
      await ui(menu.getByRole('button', { name: 'Refresh', exact: true })).toBeVisible();
      await ui(menu.getByRole('button', { name: 'Open CSV', exact: true })).toBeVisible();
      await expectTouchTargets(menu);
    }));
  it('opens an off-page ride on a cold deep link', async () =>
    run('/sessions', async page => {
      await page.evaluate(() => window.historyRouterTests.persistSeed(80));
      await page.goto(`${origin}/sessions/ride?id=ride-0`);
      await expectRide(page, 'ride-0');
      await page.getByRole('button', { name: '‹ History', exact: true }).click();
      await ui(page.getByRole('button', { name: /^Open ride,/ })).toHaveCount(50);
      expect(await page.evaluate(() => window.historyRouterTests.listedRides())).not.toContain('ride-0');
    }));
  it('retains the selected ride when returning to the History tab', async () =>
    run('/sessions/ride?id=ride-0', async page => {
      for (const id of ['ride-0', 'ride-1']) {
        if (id === 'ride-1') await page.evaluate(() => window.historyRouterTests.push('ride-1'));
        await expectRide(page, id);
        await page.getByRole('link', { name: 'Settings', exact: true }).click();
        await ui(page).toHaveURL(/\/settings$/);
        await page.getByRole('link', { name: 'History', exact: true }).click();
        await expectRide(page, id);
      }
      await page.getByRole('button', { name: '‹ History', exact: true }).click();
      await ui(page).toHaveURL(/\/sessions$/);
      await ui(page.getByRole('button', { name: /^Open ride,/ })).toHaveCount(2);
    }));
  it('discards a delayed CSV choice after a newer route selection', async () =>
    run('/sessions', async page => {
      await openMenu(page);
      await page.getByRole('button', { name: 'Open CSV', exact: true }).click();
      await ui(page.getByRole('button', { name: 'Open CSV', exact: true })).toBeDisabled();
      await page.evaluate(() => window.historyRouterTests.push('ride-0'));
      await expectRide(page, 'ride-0');
      await page.evaluate(() => window.historyRouterTests.releaseCsv());
      await page.waitForFunction(() => window.historyRouterTests.closedImports().includes('late'));
      await page.getByRole('button', { name: '‹ History', exact: true }).click();
      await ui(page).toHaveURL(/\/sessions$/);
      await openMenu(page);
      await ui(page.getByRole('button', { name: 'Open CSV', exact: true })).toBeEnabled();
      await ui(page.getByRole('button', { name: /^Open ride,/ })).toHaveCount(2);
      await ui(page.getByText('late.csv', { exact: true })).toHaveCount(0);
    }));
  it('shows a plain screen error with a way back to the other tabs and a working retry', async () =>
    run('/sessions', async page => {
      await page.evaluate(() => window.historyRouterTests.failSettings(true));
      await page.getByRole('link', { name: 'Settings', exact: true }).click();
      const screen = page.getByTestId('error-screen');
      await ui(screen.getByText('Something went wrong on this screen.', { exact: true })).toBeVisible();
      await ui(screen.getByRole('button', { name: 'Try again', exact: true })).toBeVisible();
      await ui(screen.getByText('Settings failed to render', { exact: false })).toHaveCount(0);
      await page.getByRole('link', { name: 'History', exact: true }).click();
      await ui(page.getByRole('button', { name: /^Open ride,/ })).toHaveCount(2);
      await page.getByRole('link', { name: 'Settings', exact: true }).click();
      await page.evaluate(() => window.historyRouterTests.failSettings(false));
      await page.getByRole('button', { name: 'Try again', exact: true }).click();
      await ui(page.getByTestId('settings-placeholder')).toBeAttached();
      await ui(page.getByTestId('error-screen')).toHaveCount(0);
    }));
  it('refreshes from the keyboard menu and a touch pull while retaining revision refresh', async () =>
    run('/sessions', async page => {
      await ui(page.getByText('Pull to refresh · 2 rides', { exact: true })).toBeVisible();
      let before = await page.evaluate(() => window.historyRouterTests.pageCalls());
      await page.getByRole('button', { name: 'History actions', exact: true }).focus();
      await page.getByRole('button', { name: 'History actions', exact: true }).press('Enter');
      await page.getByRole('button', { name: 'Refresh', exact: true }).press('Enter');
      await page.waitForFunction(before => window.historyRouterTests.pageCalls() > before, before);
      await page.getByRole('button', { name: 'History actions', exact: true }).click();
      before = await page.evaluate(() => window.historyRouterTests.pageCalls());
      const hint = (await page.getByText('Pull to refresh · 2 rides', { exact: true }).boundingBox())!;
      const client = await page.context().newCDPSession(page);
      await client.send('Input.dispatchTouchEvent', {
        type: 'touchStart',
        touchPoints: [{ id: 1, x: hint.x + 10, y: hint.y + 5 }],
      });
      await client.send('Input.dispatchTouchEvent', {
        type: 'touchMove',
        touchPoints: [{ id: 1, x: hint.x + 10, y: hint.y + 105 }],
      });
      await client.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
      await client.detach();
      await page.waitForFunction(before => window.historyRouterTests.pageCalls() > before, before);
      before = await page.evaluate(() => window.historyRouterTests.pageCalls());
      await page.evaluate(() => window.historyRouterTests.moveHistory());
      await page.waitForFunction(before => window.historyRouterTests.pageCalls() > before, before);
    }));
});
