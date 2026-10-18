import { expect, test, type Page } from '@playwright/test';
import { browserTestBundle } from '../helpers/browser-test-bundle';
import type * as Harness from '../fixtures/browser-ride-harness';
declare global {
  interface Window {
    catalogStorage: typeof Harness;
  }
}
const bundle = browserTestBundle('tests/fixtures/browser-ride-harness.ts', 'catalogStorage');
async function seed(page: Page, count: number, day = 1, withSamples = false): Promise<string[]> {
  await page.addScriptTag({ content: bundle });
  return page.evaluate(
    async ({ count, day, withSamples }) => {
      const h = window.catalogStorage,
        ids: string[] = [];
      const start = `2026-01-${String(day).padStart(2, '0')}T00:00:00.000Z`,
        end = `2026-01-${String(day).padStart(2, '0')}T00:01:00.000Z`;
      for (let n = 0; n < count; n++) {
        const ride = await h.store.begin('catalog-owner', { indoor: true, sampleHz: 2, recordGPS: false }, start);
        if (withSamples) {
          const rows = [0, 1, 2].map(time => ({
            ...h.browserRow(time, time, `2026-01-${String(day).padStart(2, '0')}T00:00:0${time}.000Z`),
            recordingId: ride.id,
          }));
          await h.store.append(ride.id, 'catalog-owner', rows, 2, 2, rows[2]!.timestamp);
        }
        await h.store.transition(ride.id, 'catalog-owner', 'stop', 60, 60, end);
        ids.push(ride.id);
      }
      return ids;
    },
    { count, day, withSamples },
  );
}

test('ride history pages beyond 100 IndexedDB records and refresh resets to the newest page', async ({ page }) => {
  await page.goto('/sessions');
  await expect(page.getByText('No saved rides yet.', { exact: true })).toBeVisible();
  await seed(page, 123);
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
  await seed(page, 1, 2);
  if (!(await page.getByRole('button', { name: 'Refresh', exact: true }).isVisible()))
    await page.getByRole('button', { name: 'History actions', exact: true }).click();
  await page.getByRole('button', { name: 'Refresh', exact: true }).click();
  await expect(rows).toHaveCount(50);
  await expect(rows.first()).toContainText('Jan 2');
});

for (const width of [390, 1440])
  test(`ride deletion confirms intent, removes samples, and survives reload at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await page.goto('/sessions');
    await expect(page.getByText('No saved rides yet.', { exact: true })).toBeVisible();
    await seed(page, 1, 2, true);
    const keep = (await seed(page, 1, 1, true))[0]!;
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
    expect(
      await page.evaluate(async () => {
        const db = await new Promise<IDBDatabase>(resolve => {
          const request = indexedDB.open('power-log');
          request.onsuccess = () => resolve(request.result);
        });
        const values = await new Promise<unknown[]>(resolve => {
          const request = db.transaction('samples').objectStore('samples').getAll();
          request.onsuccess = () => resolve(request.result);
        });
        db.close();
        return values.map(value => (value as { recordingId: string }).recordingId);
      }),
    ).toEqual([keep, keep, keep]);
    await page.reload();
    await expect(rows).toHaveCount(1);
    await expect(rows).toContainText('Jan 1');
  });

test('deleting the open ride closes its charts', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 900 });
  await page.goto('/sessions');
  await expect(page.getByText('No saved rides yet.', { exact: true })).toBeVisible();
  await seed(page, 1, 1, true);
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
