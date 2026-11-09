import { expect, test, type Page } from '@playwright/test';
import { exportCsv } from '../helpers/export-csv';
import { saveRides } from '../helpers/saved-rides';
import { syntheticSample } from '../fixtures/synthetic-sample';
import { expectTouchTargets } from '../helpers/ui-targets';

async function seedHistory(page: Page, count = 2) {
  await page.goto('/sessions');
  await expect(page.getByText('No saved rides yet.', { exact: true })).toBeVisible();
  const ids = await saveRides(
    page,
    Array.from({ length: count }, (_, index) => ({
      startUtcMs: Date.parse('2026-09-06T11:30:00.000Z') + index * 60_000,
      durationMs: 2750,
      atMs: [0, 1250, 2750],
      powerW: [100, 604, 200],
      lapsMs: [0],
    })),
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
  await expect(page.getByTestId('ride-health-sync')).toHaveCount(0);
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

test('the ride page keeps the History actions, and Open CSV from it shows the imported ride in History', async ({
  page,
}) => {
  await seedHistory(page);
  await page
    .getByRole('button', { name: /^Open ride,/ })
    .first()
    .click();
  await expect(page.getByTestId('ride-summary')).toBeVisible();
  await page.getByRole('button', { name: 'History actions', exact: true }).click();
  const menu = page.getByTestId('history-actions-menu');
  await expect(menu.getByRole('button', { name: 'Refresh', exact: true })).toBeVisible();
  const chooser = page.waitForEvent('filechooser');
  await menu.getByRole('button', { name: 'Open CSV', exact: true }).click();
  const rows = Array.from({ length: 20 }, (_, i) =>
    syntheticSample(i / 2, i, new Date(Date.UTC(2026, 8, 12) + i * 500).toISOString()),
  );
  await (await chooser).setFiles({ name: 'Picked.csv', mimeType: 'text/csv', buffer: Buffer.from(exportCsv(rows)) });
  await expect(page).toHaveURL(/\/sessions$/);
  await expect(page.getByText('Picked.csv', { exact: true })).toBeVisible();
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
  const strava = (await page.getByRole('button', { name: 'Open Strava upload', exact: true }).boundingBox())!;
  await expect(page.getByRole('button', { name: 'Export FIT', exact: true })).toHaveCSS(
    'background-color',
    'rgb(255, 135, 60)',
  );
  await expect(page.getByText('Export FIT, then select the file on Strava.', { exact: true })).toBeVisible();
  await expect(page.getByTestId('monitor-range-picker')).toHaveText('Whole ride');
  await chart.press('ArrowLeft');
  await expect(page.getByTestId('monitor-range-picker')).toHaveText('Custom');
  await chart.press('Escape');
  await expect(page.getByTestId('monitor-range-picker')).toHaveText('Whole ride');
  expect(fit.y + fit.height).toBeLessThan(summaryBounds.y);
  expect(summaryBounds.y + summaryBounds.height).toBeLessThan(inspection.y);
  expect(strava.y).toBeGreaterThan(inspection.y);
  expect(deletion.y).toBeGreaterThan(strava.y);
  await page.getByRole('button', { name: 'Delete ride', exact: true }).click();
  await expect(page.getByTestId('delete-ride-sheet')).toBeVisible();
  await page.getByRole('button', { name: 'Keep ride', exact: true }).click();
  await expect(page).toHaveURL(new URL(`/sessions/ride?id=${id}`, page.url()).href);
});
