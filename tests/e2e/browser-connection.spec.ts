import { expect, test, type Page } from '@playwright/test';
import { syntheticBike } from '../fixtures/browser-bike';
import type { BrowserRide, RideRow } from '../../src/services/browser-ride-store';

for (const outcome of ['cancel', 'timeout'] as const) {
  test(`a stalled bike connection can ${outcome} without leaving the controls busy`, async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await page.addInitScript(() => {
      const device = Object.assign(new EventTarget(), {
        id: 'synthetic-stalled-bike', name: 'CYC test bike',
        gatt: { connected: false, connect: () => new Promise(() => {}), disconnect: () => {} },
      });
      Object.defineProperty(navigator, 'bluetooth', { configurable: true, value: { requestDevice: async () => device } });
    });
    await page.goto('/');
    await page.getByTestId('ride-setup').click();
    await page.getByRole('button', { name: 'Find bike', exact: true }).click();
    const connect = page.getByRole('button', { name: /^Connect CYC test bike/ });
    await expect(connect).toBeEnabled();
    await page.clock.install();
    await connect.click();
    await expect(page.getByTestId('bike-connection-status')).toHaveText('Connecting…');
    const cancel = page.getByRole('button', { name: 'Cancel connection', exact: true });
    await expect(cancel).toBeEnabled();
    if (outcome === 'cancel') await cancel.click();
    else {
      await page.clock.runFor(15001);
      await expect(page.getByTestId('ride-setup-sheet').getByRole('alert')).toContainText('Bluetooth connection timed out');
    }
    await expect(page.getByTestId('bike-connection-status')).toHaveText('Not connected');
    await expect(connect).toBeEnabled();
    await expect(page.getByRole('button', { name: 'Find bike', exact: true })).toBeEnabled();
    await connect.click();
    await expect(cancel).toBeEnabled();
    await cancel.click();
    await expect(connect).toBeEnabled();
    await expect(page.getByRole('alert')).toHaveCount(0);
  });
}

async function storedRide(page: Page): Promise<{ rides: BrowserRide[]; rows: RideRow[] }> {
  return page.evaluate(async () => {
    const db = await new Promise<IDBDatabase>((resolve, reject) => {
      const request = indexedDB.open('power-log'); request.onsuccess = () => resolve(request.result); request.onerror = () => reject(request.error);
    });
    try {
      const transaction = db.transaction(['recordings', 'samples']);
      const read = <T>(name: string) => new Promise<T[]>((resolve, reject) => {
        const request = transaction.objectStore(name).getAll(); request.onsuccess = () => resolve(request.result); request.onerror = () => reject(request.error);
      });
      const [rides, rows] = await Promise.all([read<BrowserRide>('recordings'), read<RideRow>('samples')]);
      return { rides, rows };
    } finally { db.close(); }
  });
}

test('a ride survives short and long bike outages, keeping actual gaps and resuming its charts', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await syntheticBike(page); await page.goto('/');
  await page.getByTestId('ride-setup').click();
  await page.getByRole('button', { name: 'Find bike', exact: true }).click();
  await page.clock.install();
  await page.getByRole('button', { name: /^Connect CYC test bike/ }).click();
  const status = page.getByTestId('bike-connection-status');
  await expect(status).toHaveText('Connected');
  await page.getByRole('button', { name: 'Done', exact: true }).click();
  await page.getByTestId('start-ride').filter({ visible: true }).click();
  await page.clock.runFor(2000);
  await expect.poll(async () => (await storedRide(page)).rows.length).toBeGreaterThan(1);
  await expect(page.getByTestId('monitor-chart-power').locator('path').first()).toHaveAttribute('d', /L/);
  const before = await storedRide(page);
  await page.getByTestId('ride-setup').click();
  await page.evaluate(() => { window.testBike.available = false; window.testBike.drop(); });
  await page.clock.runFor(1500);
  await expect(status).toHaveText('Connected');
  await expect(page.getByRole('alert')).toHaveCount(0);
  // The first retry failed; the next one succeeds within the six-second display hold.
  await page.evaluate(() => { window.testBike.available = true; });
  await page.clock.runFor(2500);
  await expect(status).toHaveText('Connected');
  await expect(page.getByRole('alert')).toHaveCount(0);
  await expect.poll(async () => (await storedRide(page)).rows.length).toBeGreaterThan(before.rows.length);
  expect(await page.evaluate(() => window.testBike.picks)).toBe(1);

  await page.evaluate(() => { window.testBike.available = false; window.testBike.drop(); });
  await page.clock.runFor(1000);
  const beforeLongGap = await storedRide(page);
  await page.clock.runFor(6000);
  await expect(status).toHaveText('Reconnecting…');
  await expect(page.getByTestId('ride-setup-sheet').getByRole('alert')).toBeVisible();
  expect((await storedRide(page)).rows).toEqual(beforeLongGap.rows);
  expect((await storedRide(page)).rides[0]?.phase).toBe('running');
  await page.evaluate(() => { window.testBike.available = true; });
  await page.clock.runFor(7000);
  await expect(status).toHaveText('Connected');
  await expect(page.getByRole('alert')).toHaveCount(0);
  await expect.poll(async () => (await storedRide(page)).rows.length).toBeGreaterThan(beforeLongGap.rows.length);
  const after = await storedRide(page);
  expect(after.rides).toHaveLength(1);
  expect(after.rides[0]).toMatchObject({ id: before.rides[0]!.id, phase: 'running' });
  expect(after.rows.slice(0, before.rows.length)).toEqual(before.rows);
  const lastBefore = beforeLongGap.rows.at(-1)!;
  const firstAfter = after.rows[beforeLongGap.rows.length]!;
  expect(firstAfter.elapsedSeconds - lastBefore.elapsedSeconds).toBeGreaterThan(6);
  expect(firstAfter.originalElapsedSeconds! - lastBefore.originalElapsedSeconds!).toBeGreaterThan(6);
  expect(firstAfter.originalSequence).toBe(lastBefore.originalSequence! + 1);
  expect(firstAfter.connectionEpoch).not.toBe(lastBefore.connectionEpoch);
  await page.getByRole('button', { name: 'Done', exact: true }).click();
  await expect(page.getByTestId('monitor-chart-power').locator('path').first()).toHaveAttribute('d', /L/);
  await page.getByRole('button', { name: 'Finish', exact: true }).click();
  await page.getByTestId('finish-ride-sheet').getByRole('button', { name: 'Save ride', exact: true }).click();
  await expect.poll(async () => (await storedRide(page)).rides[0]?.phase).toBe('completed');
  await page.getByRole('link', { name: 'History', exact: true }).click();
  await page.getByRole('button', { name: /^Open ride,/ }).click();
  const chart = page.getByTestId('tab-transition-1').getByTestId('monitor-chart-power');
  await expect(chart.locator('path').first()).toHaveAttribute('d', /L/);
});
