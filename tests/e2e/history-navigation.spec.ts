import { expect, test, type Page } from '@playwright/test';
import { browserRow } from '../support/browser-ride';
import { browserTestBundle } from '../helpers/browser-test-bundle';
import { expectTouchTargets } from '../helpers/ui-targets';

declare global {
  interface Window {
    historyNavigationStore: typeof import('../../src/services/browser-ride-store');
  }
}
const storeBundle = browserTestBundle('src/services/browser-ride-store.ts', 'historyNavigationStore');

async function seedHistory(page: Page, count = 2) {
  await page.goto('/sessions');
  await expect(page.getByText('No saved rides yet.', { exact: true })).toBeVisible();
  const startedAt = '2026-09-06T11:30:00.000Z';
  const samples = [0, 1.25, 2.75].map((time, sequence) => ({
    ...browserRow(time, sequence, new Date(Date.parse(startedAt) + time * 1000).toISOString()),
    humanPowerW: [100, 604, 200][sequence]!,
  }));
  await page.addScriptTag({ content: storeBundle });
  const ids = await page.evaluate(
    async ({ startedAt, count, samples }) => {
      const store = new window.historyNavigationStore.BrowserRideStore();
      const ids: string[] = [];
      for (let index = 0; index < count; index++) {
        const at = new Date(Date.parse(startedAt) + index * 60000).toISOString();
        const end = new Date(Date.parse(at) + 2750).toISOString();
        const ride = await store.begin('navigation-tests', { indoor: true, recordGPS: false, sampleHz: 2 }, at);
        await store.transition(ride.id, 'navigation-tests', 'lap', 0, 0, at);
        await store.append(
          ride.id,
          'navigation-tests',
          samples.map(sample => {
            const timestamp = new Date(Date.parse(at) + sample.elapsedSeconds * 1000).toISOString();
            return {
              ...sample,
              recordingId: ride.id,
              timestamp,
              queryUs: window.historyNavigationStore.timestampUs(timestamp),
            };
          }),
          2.75,
          2.75,
          end,
        );
        await store.transition(ride.id, 'navigation-tests', 'stop', 2.75, 2.75, end);
        ids.push(ride.id);
      }
      return ids;
    },
    { startedAt, count, samples },
  );
  await page.getByRole('button', { name: 'History actions', exact: true }).click();
  await page.getByRole('button', { name: 'Refresh', exact: true }).click();
  await expect(page.getByRole('button', { name: /^Open ride,/ })).toHaveCount(Math.min(50, count));
  await expect(page.getByTestId('ride-health-sync-summary')).toHaveCount(0);
  await expect(page.getByRole('button', { name: /^Open ride,/ }).first()).not.toHaveAccessibleName(
    /Health|Watch|Saved only in Power Log/,
  );
  await expectTouchTargets(page.locator('body'));
  return ids;
}

test('saved ride routing survives reload, keeps History active, and returns to the list', async ({ page }) => {
  const [id] = await seedHistory(page);
  await page
    .getByRole('button', { name: /^Open ride,/ })
    .last()
    .click();
  await expect(page).toHaveURL(new URL(`/sessions/ride?id=${id}`, page.url()).href);
  await expect(page.getByTestId('tab-transition-1').filter({ visible: true })).toBeVisible();
  await expect(page.getByRole('link', { name: 'History', exact: true })).toHaveAttribute('aria-current', 'page');
  await page.reload();
  await expect(page.getByTestId('ride-summary')).toBeVisible();
  await expect(page.getByTestId('ride-health-sync')).toBeEmpty();
  await expectTouchTargets(page.locator('body'));
  await expect(page.getByRole('heading', { name: 'Ride summary', exact: true })).toHaveCSS('font-size', '18px');
  await expect(page.getByRole('link', { name: 'History', exact: true })).toHaveAttribute('aria-current', 'page');
  await page.getByRole('button', { name: '‹ History', exact: true }).click();
  await expect(page).toHaveURL(/\/sessions$/);
  await expect(page.getByRole('button', { name: /^Open ride,/ })).toHaveCount(2);
  await page.getByRole('button', { name: 'History actions', exact: true }).click();
  const menu = page.getByTestId('history-actions-menu');
  await expect(menu.getByRole('button', { name: 'Refresh', exact: true })).toBeVisible();
  await expect(menu.getByRole('button', { name: 'Open CSV', exact: true })).toBeVisible();
  await expectTouchTargets(menu);
  await page.getByRole('button', { name: 'Edit', exact: true }).click();
  await expectTouchTargets(page.locator('body'));
});

test('a cold deep link reads a ride outside the first catalog page', async ({ page }) => {
  const [id] = await seedHistory(page, 80);
  await page.goto(`/sessions/ride?id=${id}`);
  await expect(page.getByTestId('ride-summary')).toBeVisible();
  await expect(page.getByTestId('monitor-chart-power')).toBeVisible();
  await page.getByRole('button', { name: '‹ History', exact: true }).click();
  await expect(page.getByRole('button', { name: /^Open ride,/ })).toHaveCount(50);
});

test('History separates the summary from cursor and visible-range readouts and orders archive actions', async ({
  page,
}) => {
  const [id] = await seedHistory(page);
  await page
    .getByRole('button', { name: /^Open ride,/ })
    .last()
    .click();
  const summary = page.getByTestId('ride-summary');
  const chart = page.getByTestId('monitor-chart-power');
  const reading = page.getByTestId('monitor-readout-humanPowerW');
  await expect(summary).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Ride summary', exact: true })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Chart inspection', exact: true })).toBeVisible();
  await expect(page.getByText(/Recorded · Indoor · Browser · 1 lap$/, { exact: true })).toBeVisible();
  await expect(reading).toContainText('avg');
  await expect(reading).toContainText('max 604 W');
  await expect(reading).not.toContainText('Last in range');
  const summaryBefore = await summary.textContent();
  await chart.focus();
  await chart.press('ArrowLeft');
  await expect(reading).toContainText('00:02');
  await expect(reading).not.toContainText('avg');
  await expect(reading).not.toContainText('00:02.');
  await expect(summary).toHaveText(summaryBefore!);
  await chart.press('Escape');
  await expect(reading).toContainText('avg');
  const fit = (await page.getByRole('button', { name: 'Export FIT', exact: true }).boundingBox())!;
  const summaryBounds = (await summary.boundingBox())!;
  const inspection = (await page.getByRole('heading', { name: 'Chart inspection', exact: true }).boundingBox())!;
  const deletion = (await page.getByRole('button', { name: 'Delete ride', exact: true }).boundingBox())!;
  await expect(page.getByRole('button', { name: 'Delete ride', exact: true })).toHaveCSS(
    'background-color',
    'rgb(255, 137, 148)',
  );
  expect(fit.y + fit.height).toBeLessThan(summaryBounds.y);
  expect(summaryBounds.y + summaryBounds.height).toBeLessThan(inspection.y);
  expect(deletion.y).toBeGreaterThan(inspection.y);
  await page.getByRole('button', { name: 'Delete ride', exact: true }).click();
  await expect(page.getByTestId('delete-ride-sheet')).toBeVisible();
  await page.getByRole('button', { name: 'Keep ride', exact: true }).click();
  await expect(page).toHaveURL(new URL(`/sessions/ride?id=${id}`, page.url()).href);
});
