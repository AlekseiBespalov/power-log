import { expect, test } from '@playwright/test';
import { syntheticSample } from '../fixtures/synthetic-sample';

test('ride history pages beyond 100 IndexedDB records and refresh resets to the newest page', async ({ page }) => {
  await page.goto('/sessions');
  await expect(page.getByText('No saved rides yet.', { exact: true })).toBeVisible();
  await page.evaluate(async () => {
    const db = await new Promise<IDBDatabase>((resolve, reject) => { const request = indexedDB.open('power-log'); request.onsuccess = () => resolve(request.result); request.onerror = () => reject(request.error); });
    await new Promise<void>((resolve, reject) => {
      const tx = db.transaction('recordings', 'readwrite');
      for (let i = 0; i < 123; i++) tx.objectStore('recordings').put({ id: `catalog-${String(i).padStart(4, '0')}`, startedAt: '2026-01-01T00:00:00.000Z', endedAt: '2026-01-01T00:01:00.000Z', samples: 0, uri: 'indexeddb', source: 'device' });
      tx.oncomplete = () => resolve(); tx.onerror = () => reject(tx.error);
    }); db.close();
  });
  await page.getByRole('button', { name: 'Refresh', exact: true }).click();
  const rows = page.getByRole('button', { name: /^Open ride,/ });
  await expect(rows).toHaveCount(50);
  await page.getByRole('button', { name: 'Load more rides', exact: true }).click();
  await expect(rows).toHaveCount(100);
  await page.getByRole('button', { name: 'Load more rides', exact: true }).click();
  await expect(rows).toHaveCount(123);
  await expect(page.getByRole('button', { name: 'Load more rides', exact: true })).toHaveCount(0);
  await page.evaluate(async () => {
    const db = await new Promise<IDBDatabase>(resolve => { const request = indexedDB.open('power-log'); request.onsuccess = () => resolve(request.result); });
    await new Promise<void>(resolve => {
      const tx = db.transaction('recordings', 'readwrite');
      tx.objectStore('recordings').put({ id: 'newest-catalog-ride', startedAt: '2026-01-02T00:00:00.000Z', endedAt: '2026-01-02T00:01:00.000Z', samples: 0, uri: 'indexeddb', source: 'device' });
      tx.oncomplete = () => resolve();
    }); db.close();
  });
  await page.getByRole('button', { name: 'Refresh', exact: true }).click();
  await expect(rows).toHaveCount(50);
  await expect(rows.first()).toContainText('Jan 2, 2026');
});

for (const width of [390, 1440]) test(`ride deletion confirms intent, removes samples, and survives reload at ${width}px`, async ({ page }) => {
  await page.setViewportSize({ width, height: 900 });
  await page.goto('/sessions');
  await expect(page.getByText('No saved rides yet.', { exact: true })).toBeVisible();
  await page.evaluate(async () => {
    const db = await new Promise<IDBDatabase>(resolve => { const request = indexedDB.open('power-log'); request.onsuccess = () => resolve(request.result); });
    await new Promise<void>((resolve, reject) => {
      const tx = db.transaction(['recordings', 'samples'], 'readwrite');
      for (const id of ['delete-me', 'keep-me']) {
        tx.objectStore('recordings').put({ id, startedAt: id === 'delete-me' ? '2026-01-02T00:00:00.000Z' : '2026-01-01T00:00:00.000Z', endedAt: '2026-01-02T00:01:00.000Z', samples: 1, uri: 'indexeddb', source: 'device' });
        tx.objectStore('samples').put({ recordingId: id, sequence: 1, marker: id });
      }
      tx.oncomplete = () => resolve(); tx.onerror = () => reject(tx.error);
    }); db.close();
  });
  await page.getByRole('button', { name: 'Refresh', exact: true }).click();
  const rows = page.getByRole('button', { name: /^Open ride,/ });
  await expect(rows).toHaveCount(2);
  await page.getByRole('button', { name: 'Edit', exact: true }).click();
  await page.getByRole('button', { name: /^Delete ride,/ }).first().click();
  await page.getByRole('button', { name: 'Keep ride', exact: true }).click();
  await expect(rows).toHaveCount(2);
  await page.getByRole('button', { name: /^Delete ride,/ }).first().click();
  await page.getByRole('button', { name: 'Delete ride', exact: true }).click();
  await expect(page.getByTestId('delete-ride-sheet')).toHaveCount(0);
  await expect(rows).toHaveCount(1);
  expect(await page.evaluate(async () => {
    const db = await new Promise<IDBDatabase>(resolve => { const request = indexedDB.open('power-log'); request.onsuccess = () => resolve(request.result); });
    const values = await new Promise<unknown[]>(resolve => { const request = db.transaction('samples').objectStore('samples').getAll(); request.onsuccess = () => resolve(request.result); });
    db.close(); return values;
  })).toEqual([{ recordingId: 'keep-me', sequence: 1, marker: 'keep-me' }]);
  await page.reload();
  await expect(rows).toHaveCount(1);
  await expect(rows).toContainText('Jan 1, 2026');
});

test('deleting the open ride closes its charts', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 900 });
  await page.goto('/sessions');
  await expect(page.getByText('No saved rides yet.', { exact: true })).toBeVisible();
  const samples = [0, 1, 2].map(time => syntheticSample(time, time, `2026-01-01T00:00:0${time}.000Z`));
  await page.evaluate(async rows => {
    const db = await new Promise<IDBDatabase>(resolve => { const request = indexedDB.open('power-log'); request.onsuccess = () => resolve(request.result); });
    await new Promise<void>((resolve, reject) => {
      const tx = db.transaction(['recordings', 'samples'], 'readwrite');
      tx.objectStore('recordings').put({ id: 'open-record', startedAt: rows[0]!.timestamp, endedAt: rows[2]!.timestamp, samples: rows.length, uri: 'indexeddb', source: 'device' });
      for (const sample of rows) tx.objectStore('samples').put({ ...sample, recordingId: 'open-record' });
      tx.oncomplete = () => resolve(); tx.onerror = () => reject(tx.error);
    }); db.close();
  }, samples);
  await page.getByRole('button', { name: 'Refresh', exact: true }).click();
  await page.getByRole('button', { name: /^Open ride,/ }).click();
  await expect(page.getByTestId('monitor-chart-power')).toBeVisible();
  await page.getByRole('button', { name: 'Delete ride', exact: true }).click();
  await page.getByTestId('delete-ride-sheet').getByRole('button', { name: 'Delete ride', exact: true }).click();
  await expect(page.getByTestId('delete-ride-sheet')).toHaveCount(0);
  await expect(page.getByTestId('monitor-chart-power')).toHaveCount(0);
  await expect(page.getByText('No saved rides yet.', { exact: true })).toBeVisible();
});
