import { expect, test, type Page } from '@playwright/test';
import { fakeBluetooth } from '../helpers/demo-bike';

type Batch = { batchNo: number; rows: number; firstUs: number; lastUs: number; values: number[] };
type Archived = { phase: string | null; batches: Batch[] };

async function archived(page: Page): Promise<Archived> {
  return page.evaluate(async () => {
    const name = (await indexedDB.databases())
      .map(database => database.name)
      .find(name => name?.startsWith('power-log-archive-'));
    if (!name) return { phase: null, batches: [] };
    const db = await new Promise<IDBDatabase>((resolve, reject) => {
      const request = indexedDB.open(name);
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => reject(request.error);
    });
    const all = (store: string) =>
      new Promise<Record<string, unknown>[]>(resolve => {
        const request = db.transaction(store).objectStore(store).getAll();
        request.onsuccess = () => resolve(request.result as Record<string, unknown>[]);
      });
    const rides = await all('ride');
    const facts = await all('facts');
    db.close();
    return {
      phase: rides.length === 1 ? String(rides[0]!.phase) : null,
      batches: facts
        .filter(record => record.kind === 'telemetry')
        .map(record => ({
          batchNo: Number(record.batchNo),
          rows: Number(record.rows),
          firstUs: Number(record.firstUs),
          lastUs: Number(record.lastUs),
          values: Array.from(record.values as ArrayLike<number>),
        })),
    };
  });
}

async function recordAt8Hz(page: Page) {
  await page.setViewportSize({ width: 390, height: 844 });
  await fakeBluetooth(page);
  await page.goto('/');
  await page.getByTestId('ride-setup').click();
  const sheet = page.getByTestId('ride-setup-sheet');
  await sheet.getByTestId('setup-find-bike').click();
  await sheet.getByTestId('setup-connect-bike').click();
  await expect(sheet.getByTestId('bike-connection-status')).toHaveText('Connected', { timeout: 30_000 });
  await sheet.getByRole('button', { name: 'Done', exact: true }).click();
  await page.getByRole('link', { name: 'Settings', exact: true }).click();
  for (const [row, choice] of [
    ['Ride type', 'Indoor'],
    ['Bike sample rate', '8 Hz'],
  ]) {
    await page.getByRole('button', { name: new RegExp(`^${row}, `) }).click();
    await page.getByTestId('settings-options').getByRole('radio', { name: choice, exact: true }).click();
  }
  const gps = page.getByRole('switch', { name: 'Record GPS route', exact: true });
  if ((await gps.getAttribute('aria-checked')) === 'true') await gps.click();
  await page.getByRole('link', { name: 'Ride', exact: true }).click();
  await page.getByTestId('ride-setup').click();
  await expect(sheet.getByTestId('setup-start-ride')).toBeEnabled({ timeout: 30_000 });
  await sheet.getByRole('button', { name: 'Done', exact: true }).click();
  await page.getByTestId('start-ride').click();
  await expect(page.getByTestId('ride-state-badge')).toHaveText('Running', { timeout: 30_000 });
}

async function nextCommit(page: Page, after: number): Promise<number> {
  let count = after;
  await expect
    .poll(async () => (count = (await archived(page)).batches.length), { timeout: 30_000, intervals: [50] })
    .toBeGreaterThan(after);
  return count;
}

for (const pending of [false, true])
  test(`a reload during an 8 Hz capture ${pending ? 'with readings not yet committed' : 'just after a commit'} leaves the ride interrupted with its committed telemetry`, async ({
    page,
  }) => {
    test.setTimeout(120_000);
    await recordAt8Hz(page);
    const committed = await nextCommit(page, await nextCommit(page, 0));
    if (pending) await page.waitForTimeout(400);
    const before = await archived(page);
    expect(before.phase).toBe('running');
    expect(before.batches.length).toBeGreaterThanOrEqual(committed);
    await page.reload();
    await expect(page.getByTestId('ride-state-badge')).not.toHaveText('Running', { timeout: 30_000 });
    await expect.poll(async () => (await archived(page)).phase, { timeout: 30_000 }).toBe('interrupted');
    const after = await archived(page);
    const kept = new Map(after.batches.map(batch => [batch.batchNo, batch]));
    for (const batch of before.batches) expect(kept.get(batch.batchNo), `batch ${batch.batchNo}`).toEqual(batch);
    const rows = (batches: Batch[]) => batches.reduce((total, batch) => total + batch.rows, 0);
    expect(rows(before.batches)).toBeGreaterThan(8);
    expect(rows(after.batches)).toBeGreaterThanOrEqual(rows(before.batches));
    const ends = after.batches.map(batch => batch.lastUs);
    expect(ends).toEqual([...ends].sort((a, b) => a - b));
  });
