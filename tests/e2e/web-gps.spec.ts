import { expect, test } from '@playwright/test';
import { syntheticBike } from '../fixtures/browser-bike';

test('records GPS, finishes the saved portion after reload and exports it', async ({ page }) => {
  await syntheticBike(page);
  await page.addInitScript(() => {
    let next = 0;
    const watches = new Map<number, PositionCallback>();
    const fix = () =>
      ({
        timestamp: Date.now(),
        coords: {
          latitude: 0,
          longitude: performance.now() / 1000000000,
          accuracy: 5,
          altitude: 12,
          altitudeAccuracy: 2,
          heading: 90,
          speed: 2,
        },
      }) as GeolocationPosition;
    Object.defineProperty(navigator, 'geolocation', {
      configurable: true,
      value: {
        getCurrentPosition: (success: PositionCallback) => success(fix()),
        watchPosition: (success: PositionCallback) => {
          const id = ++next;
          watches.set(id, success);
          return id;
        },
        clearWatch: (id: number) => watches.delete(id),
      },
    });
    setInterval(() => {
      for (const receive of watches.values()) receive(fix());
    }, 200);
  });
  await page.goto('/');
  await page.getByTestId('start-ride').click();
  const setup = page.getByTestId('ride-setup-sheet');
  await expect(setup.getByTestId('setup-start-ride')).toBeDisabled();
  await setup.getByTestId('setup-find-bike').click();
  await setup.getByRole('button', { name: /^Connect CYC/ }).click();
  await expect(setup.getByTestId('setup-start-ride')).toBeEnabled();
  await setup.getByTestId('setup-start-ride').click();
  await expect(page.getByTestId('ride-state-badge')).toHaveText('Running');
  await expect
    .poll(() =>
      page.evaluate(async () => {
        const name = (await indexedDB.databases())
          .map(database => database.name)
          .find(name => name?.startsWith('power-log-archive-'))!;
        const db = await new Promise<IDBDatabase>((resolve, reject) => {
          const open = indexedDB.open(name);
          open.onerror = () => reject(open.error);
          open.onsuccess = () => resolve(open.result);
        });
        const facts = await new Promise<{ kind: string; rows: number }[]>(resolve => {
          const read = db.transaction('facts').objectStore('facts').getAll();
          read.onsuccess = () => resolve(read.result as { kind: string; rows: number }[]);
        });
        db.close();
        return facts.filter(batch => batch.kind === 'location').reduce((total, batch) => total + batch.rows, 0);
      }),
    )
    .toBeGreaterThan(2);
  await page.reload();
  await expect(page.getByTestId('ride-state-badge')).toHaveText('Needs attention');
  await expect(page.getByRole('button', { name: 'Resume', exact: true })).toHaveCount(0);
  await page.getByRole('button', { name: 'Finish', exact: true }).click();
  await page.getByTestId('finish-ride-sheet').getByRole('button', { name: 'Save ride', exact: true }).click();
  await page.getByRole('link', { name: /History/ }).click();
  await page.getByRole('button', { name: /^Open ride,/ }).click();
  await expect(page.getByRole('img', { name: 'Recorded GPS route preview', exact: true })).toBeVisible();
  const download = page.waitForEvent('download');
  await page.getByRole('button', { name: 'Export ZIP', exact: true }).click();
  expect((await download).suggestedFilename()).toMatch(/\.zip$/);
});

test('checks denied browser GPS permission without opening native Settings', async ({ page }) => {
  await page.addInitScript(() => {
    const permission = { state: 'denied' };
    Object.assign(window, { browserLocationPermission: permission });
    Object.defineProperty(navigator, 'permissions', { configurable: true, value: { query: async () => permission } });
  });
  await page.goto('/');
  await page.getByTestId('ride-setup').click();
  const setup = page.getByTestId('ride-setup-sheet');
  await expect(setup).toContainText("Allow location in this site's browser settings, then check permissions.");
  await expect(setup.getByRole('button', { name: 'Check permissions', exact: true })).toBeVisible();
  await page.evaluate(() => {
    (window as unknown as { browserLocationPermission: { state: string } }).browserLocationPermission.state = 'granted';
  });
  await setup.getByRole('button', { name: 'Check permissions', exact: true }).click();
  await expect(setup.getByRole('button', { name: 'Check permissions', exact: true })).toHaveCount(0);
  await expect(setup.getByRole('heading', { name: 'Permissions', exact: true })).toHaveCount(0);
  await expect(setup).not.toContainText('No permission changes needed.');
});
