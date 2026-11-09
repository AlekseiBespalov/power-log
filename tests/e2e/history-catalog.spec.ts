import { expect, test, type Page } from '@playwright/test';
import { saveRides } from '../helpers/saved-rides';

const day = (n: number) => Date.UTC(2026, 0, n);
const withSamples = (n: number) => ({
  startUtcMs: day(n),
  durationMs: 60_000,
  atMs: [0, 1000, 2000],
  powerW: [150, 150, 150],
});

async function archived(page: Page, store: 'ride' | 'facts'): Promise<string[]> {
  return page.evaluate(async store => {
    const name = (await indexedDB.databases())
      .map(database => database.name)
      .find(name => name?.startsWith('power-log-archive-'))!;
    const db = await new Promise<IDBDatabase>(resolve => {
      const request = indexedDB.open(name);
      request.onsuccess = () => resolve(request.result);
    });
    const values = await new Promise<unknown[]>(resolve => {
      const request = db.transaction(store).objectStore(store).getAll();
      request.onsuccess = () => resolve(request.result);
    });
    db.close();
    return values.map(value => {
      const record = value as { uuid?: string; ride?: string };
      return (record.uuid ?? record.ride)!;
    });
  }, store);
}

test('ride history pages beyond 100 rides and refresh keeps the loaded rows with the newest first', async ({
  page,
}) => {
  await page.goto('/sessions');
  await expect(page.getByText('No saved rides yet.', { exact: true })).toBeVisible();
  await saveRides(
    page,
    Array.from({ length: 123 }, () => ({ startUtcMs: Date.UTC(2026, 0, 1), durationMs: 60_000 })),
  );
  if (!(await page.getByRole('button', { name: 'Refresh', exact: true }).isVisible()))
    await page.getByRole('button', { name: 'History actions', exact: true }).click();
  await page.getByRole('button', { name: 'Refresh', exact: true }).click();
  const rows = page.getByRole('button', { name: /^Open ride,/ });
  await expect(rows).toHaveCount(50);
  await page.getByRole('button', { name: 'Load more rides', exact: true }).click();
  await expect(rows).toHaveCount(100);
  await page.getByRole('button', { name: 'Load more rides', exact: true }).click();
  await expect(rows).toHaveCount(123);
  await expect(page.getByRole('button', { name: 'Load more rides', exact: true })).toHaveCount(0);
  await saveRides(page, [{ startUtcMs: Date.UTC(2026, 0, 2), durationMs: 60_000 }]);
  if (!(await page.getByRole('button', { name: 'Refresh', exact: true }).isVisible()))
    await page.getByRole('button', { name: 'History actions', exact: true }).click();
  await page.getByRole('button', { name: 'Refresh', exact: true }).click();
  await expect(rows).toHaveCount(124);
  await expect(rows.first()).toContainText('Jan 2');
});

for (const width of [390, 1440])
  test(`ride deletion confirms intent, removes samples, and survives reload at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await page.goto('/sessions');
    await expect(page.getByText('No saved rides yet.', { exact: true })).toBeVisible();
    await saveRides(page, [withSamples(2)]);
    const [keep] = await saveRides(page, [withSamples(1)]);
    if (!(await page.getByRole('button', { name: 'Refresh', exact: true }).isVisible()))
      await page.getByRole('button', { name: 'History actions', exact: true }).click();
    await page.getByRole('button', { name: 'Refresh', exact: true }).click();
    const rows = page.getByRole('button', { name: /^Open ride,/ });
    await expect(rows).toHaveCount(2);
    await page.getByRole('button', { name: 'Edit', exact: true }).click();
    await page
      .getByRole('button', { name: /^Delete ride,/ })
      .first()
      .click();
    await page.getByRole('button', { name: 'Keep ride', exact: true }).click();
    await expect(rows).toHaveCount(2);
    await page
      .getByRole('button', { name: /^Delete ride,/ })
      .first()
      .click();
    await page.getByRole('button', { name: 'Delete ride', exact: true }).click();
    await expect(page.getByTestId('delete-ride-sheet')).toHaveCount(0);
    await expect(rows).toHaveCount(1);
    expect(await archived(page, 'ride')).toEqual([keep]);
    expect(new Set(await archived(page, 'facts'))).toEqual(new Set([keep]));
    await page.reload();
    await expect(rows).toHaveCount(1);
    await expect(rows).toContainText('Jan 1');
  });

test('deleting the open ride closes its charts', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 900 });
  await page.goto('/sessions');
  await expect(page.getByText('No saved rides yet.', { exact: true })).toBeVisible();
  await saveRides(page, [withSamples(1)]);
  if (!(await page.getByRole('button', { name: 'Refresh', exact: true }).isVisible()))
    await page.getByRole('button', { name: 'History actions', exact: true }).click();
  await page.getByRole('button', { name: 'Refresh', exact: true }).click();
  await page.getByRole('button', { name: /^Open ride,/ }).click();
  await expect(page.getByTestId('monitor-chart-power')).toBeVisible();
  await page.getByRole('button', { name: 'Delete ride', exact: true }).click();
  await page.getByTestId('delete-ride-sheet').getByRole('button', { name: 'Delete ride', exact: true }).click();
  await expect(page.getByTestId('delete-ride-sheet')).toHaveCount(0);
  await expect(page.getByTestId('monitor-chart-power')).toHaveCount(0);
  await expect(page.getByText('No saved rides yet.', { exact: true })).toBeVisible();
});
