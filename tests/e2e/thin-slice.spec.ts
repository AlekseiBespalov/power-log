import { expect, test, type Page } from '@playwright/test';
import { fakeBluetooth } from '../helpers/demo-bike';

async function choose(page: Page, row: string, choice: string) {
  await page.getByRole('button', { name: new RegExp(`^${row}, `) }).click();
  await page.getByTestId('settings-options').getByRole('radio', { name: choice, exact: true }).click();
  await expect(page.getByRole('button', { name: `${row}, ${choice}`, exact: true })).toBeVisible();
}

const seconds = (timer: string) =>
  timer
    .split(':')
    .map(Number)
    .reduce((total, part) => total * 60 + part, 0);

test('the thin slice records an indoor 8 Hz ride from the bike and charts it from History', async ({ page }) => {
  test.setTimeout(240_000);
  await page.setViewportSize({ width: 390, height: 844 });
  await fakeBluetooth(page);
  await page.goto('/');

  await page.getByTestId('ride-setup').click();
  const sheet = page.getByTestId('ride-setup-sheet');
  await sheet.getByTestId('setup-find-bike').click();
  await sheet.getByTestId('setup-connect-bike').click();
  await expect(sheet.getByTestId('bike-connection-status')).toHaveText('Connected', { timeout: 30_000 });
  expect(await page.evaluate(() => window.powerLogChooser?.activation)).toBe(true);
  expect(await page.evaluate(() => window.powerLogChooser?.options)).toEqual({
    filters: [{ services: ['6e400001-b5a3-f393-e0a9-e50e24dcca9e'] }, { namePrefix: 'CYCMOTOR' }],
    optionalServices: ['6e400001-b5a3-f393-e0a9-e50e24dcca9e'],
  });
  await sheet.getByRole('button', { name: 'Done', exact: true }).click();

  await page.getByRole('link', { name: 'Settings', exact: true }).click();
  await choose(page, 'Ride type', 'Indoor');
  await choose(page, 'Bike sample rate', '8 Hz');
  const gps = page.getByRole('switch', { name: 'Record GPS route', exact: true });
  await expect(gps, 'Indoor keeps the outdoor GPS choice, as today').toHaveAttribute('aria-checked', 'true');
  await gps.click();
  await expect(gps).toHaveAttribute('aria-checked', 'false');
  await page.getByRole('link', { name: 'Ride', exact: true }).click();

  await page.getByTestId('ride-setup').click();
  await expect(sheet.getByTestId('setup-start-ride')).toBeEnabled({ timeout: 30_000 });
  await expect(sheet.getByText('Permissions', { exact: true })).toHaveCount(0);
  await expect(sheet.getByText(/Health|location/i)).toHaveCount(0);
  await sheet.getByRole('button', { name: 'Done', exact: true }).click();
  const start = page.getByTestId('start-ride');
  await expect(start).toHaveText('Start ride');
  await start.click();
  const badge = page.getByTestId('ride-state-badge');
  await expect(badge).toHaveText('Running', { timeout: 30_000 });
  const timer = page.getByTestId('ride-timer');
  await expect
    .poll(async () => seconds(await timer.innerText()), { timeout: 120_000, intervals: [1_000] })
    .toBeGreaterThanOrEqual(61);

  const otherTab = await page.context().newPage();
  await otherTab.setViewportSize({ width: 390, height: 844 });
  await otherTab.goto('/sessions');
  const listed = otherTab.getByTestId('history-ride');
  await expect(listed).toHaveCount(1, { timeout: 30_000 });
  await expect(listed).toContainText('Recording');

  await page.getByTestId('finish-ride').click();
  await page.getByTestId('finish-ride-sheet').getByTestId('save-ride').click();
  await expect(page.getByTestId('finish-ride-sheet')).toBeHidden({ timeout: 30_000 });
  await expect(page.getByTestId('start-ride')).toBeVisible({ timeout: 30_000 });
  await expect(listed, 'History reloads on the finished ride’s commit').not.toContainText('Recording', {
    timeout: 30_000,
  });
  await expect(listed).toContainText(/Recorded · Indoor · 01:\d\d/);
  await otherTab.close();

  await page.getByRole('link', { name: 'History', exact: true }).click();
  const ride = page.getByTestId('history-ride');
  await expect(ride).toHaveCount(1, { timeout: 30_000 });
  await expect(ride).toContainText(/Recorded · Indoor · 01:\d\d/);
  await ride.click();

  const chart = page.getByTestId('monitor-chart-power').filter({ visible: true });
  await expect(chart).toBeVisible({ timeout: 30_000 });
  await chart.scrollIntoViewIfNeeded();
  const vertices = () =>
    chart
      .locator('path')
      .evaluateAll(paths => Math.max(0, ...paths.map(path => path.getAttribute('d')?.match(/[ML]/g)?.length ?? 0)));
  await expect
    .poll(vertices, { message: 'about 8 readings a second for over a minute', timeout: 30_000 })
    .toBeGreaterThan(300);
  await expect(page.getByTestId('monitor-status').filter({ visible: true })).toHaveCount(0);
  await expect(page.getByText(/Something went wrong/)).toHaveCount(0);
});
