import { expect, test, type Page } from '@playwright/test';
import { exportCsv } from '../helpers/export-csv';
import { syntheticSample } from '../fixtures/synthetic-sample';

async function importedMonitor(page: Page) {
  await page.goto('/sessions');
  const chooser = page.waitForEvent('filechooser');
  await page.getByRole('button', { name: 'Open CSV', exact: true }).click();
  await (await chooser).setFiles({ name: 'layout.csv', mimeType: 'text/csv', buffer: Buffer.from(exportCsv([0, 1, 2].map(index => syntheticSample(index, index, `2026-09-08T00:00:0${index}.000Z`)))) });
  await expect(page.getByTestId('monitor-chart-power')).toBeVisible();
}
async function layout(page: Page) {
  return page.getByTestId('monitor-numbers').evaluate(element => {
    const rect = (node: Element) => { const { x, y, width, height } = node.getBoundingClientRect(); return { x, y, width, height }; };
    return { grid: rect(element), cells: [...element.children].map(rect), header: rect(document.querySelector('[data-testid="monitor-header"]')!) };
  });
}
async function chooseView(page: Page, name: string) {
  await page.getByTestId('monitor-view-picker').click();
  await page.getByRole('button', { name: new RegExp(`^${name}(?:\\s+✓)?$`) }).click();
}

for (const width of [320, 390, 900]) test(`monitor uses stable compact spacing for three/four metrics and status at ${width}px`, async ({ page }, testInfo) => {
  await page.setViewportSize({ width, height: 900 });
  await page.addInitScript(() => {
    const get = Storage.prototype.getItem;
    Storage.prototype.getItem = function (key) {
      if (key === 'power-log.monitor-preferences.v1') throw new Error('Settings unavailable for layout regression');
      return get.call(this, key);
    };
  });
  await importedMonitor(page);
  await expect(page.getByTestId('monitor-status')).toContainText('Settings error');
  const withStatus = await layout(page);
  expect(withStatus.header.height).toBe(44);
  expect(withStatus.grid.y - withStatus.header.y - withStatus.header.height).toBeCloseTo(12, 0);
  // Status opens its full explanation through the same accessible view control.
  await page.getByTestId('monitor-status').click();
  await expect(page.getByTestId('monitor-status-details')).toContainText('Settings unavailable for layout regression');
  await page.getByRole('button', { name: /^Ride\s+✓$/ }).click();
  await expect(page.getByTestId('monitor-status')).toHaveCount(0);
  const four = await layout(page);
  expect(four).toEqual(withStatus);
  expect(four.cells).toHaveLength(4);
  const columns = Math.max(1, Math.min(four.grid.width >= 600 ? 4 : 2, Math.floor((four.grid.width + 14) / 140)));
  for (const name of ['Battery', 'Temperature']) {
    await chooseView(page, name);
    const three = await layout(page);
    expect(three.cells).toHaveLength(3);
    expect(three.grid.height).toBeCloseTo(four.grid.height, 0);
    for (let index = 0; index < three.cells.length; index++) {
      const cell = three.cells[index]!;
      expect(cell.width).toBeCloseTo(four.cells[index]!.width, 0);
      expect(cell.height).toBeCloseTo(four.cells[index]!.height, 0);
      expect(cell.x - three.grid.x).toBeCloseTo((index % columns) * (cell.width + 14), 0);
      expect(cell.y - three.grid.y).toBeCloseTo(Math.floor(index / columns) * (cell.height + 14), 0);
    }
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  }
  await expect(page.getByTestId('monitor-number-motorTempC').getByText('Motor temperature', { exact: true })).toHaveCSS('color', 'rgb(155, 166, 182)');
  await page.screenshot({ animations: 'disabled', path: testInfo.outputPath(`temperature-compact-${width}.png`) });
});

test('Ride and History enter from their relative side, including repeated navigation and Back', async ({ page }) => {
  await page.addInitScript(() => {
    const samples: { tab: string; x: number }[] = [];
    Object.assign(window, { tabMotion: samples });
    const record = () => {
      for (const node of document.querySelectorAll('[data-testid^="tab-transition-"]')) {
        if (!(node as HTMLElement).checkVisibility()) continue;
        const x = new DOMMatrixReadOnly(getComputedStyle(node).transform).m41;
        if (Math.abs(x) > 0.1) samples.push({ tab: node.getAttribute('data-testid')!, x });
      }
      requestAnimationFrame(record);
    };
    requestAnimationFrame(record);
  });
  await page.goto('/');
  await expect(page.getByTestId('ride-setup')).toBeVisible();
  await page.getByTestId('tab-transition-0').evaluate(element => { element.setAttribute('data-original-ride-tab', 'true'); });
  const assertDirection = async (index: number, action: () => Promise<unknown>) => {
    await page.evaluate(() => { (window as unknown as { tabMotion: unknown[] }).tabMotion.length = 0; });
    await action();
    const moving = page.locator(`[data-testid="tab-transition-${index}"]:visible`);
    await expect(moving).toHaveCount(1);
    await expect.poll(() => moving.evaluate(element => new DOMMatrixReadOnly(getComputedStyle(element).transform).m41)).toBe(0);
    const positions = await page.evaluate(index => (window as unknown as { tabMotion: { tab: string; x: number }[] }).tabMotion.filter(item => item.tab === `tab-transition-${index}`).map(item => item.x), index);
    expect(positions.length).toBeGreaterThan(1);
    expect(positions.every(x => index === 1 ? x > 0 : x < 0)).toBe(true);
    expect(Math.abs(positions[positions.length - 1]!)).toBeLessThan(Math.abs(positions[0]!));
  };
  await assertDirection(1, () => page.getByRole('link', { name: 'History', exact: true }).click());
  await expect(page.locator('[data-original-ride-tab="true"]')).toHaveAttribute('data-testid', 'tab-transition-0');
  await expect(page.locator('[data-original-ride-tab="true"]')).toBeHidden();
  await assertDirection(0, () => page.getByRole('link', { name: 'Ride', exact: true }).click());
  await assertDirection(1, () => page.getByRole('link', { name: 'History', exact: true }).click());
  await assertDirection(0, () => page.goBack());
});

test('tab transitions respect reduced motion', async ({ page }) => {
  await page.emulateMedia({ reducedMotion: 'reduce' });
  await page.goto('/');
  await page.evaluate(() => {
    const motion: number[] = []; Object.assign(window, { reducedMotionProbe: motion });
    const record = () => {
      for (const element of document.querySelectorAll('[data-testid^="tab-transition-"]')) {
        if ((element as HTMLElement).checkVisibility()) motion.push(new DOMMatrixReadOnly(getComputedStyle(element).transform).m41);
      }
      requestAnimationFrame(record);
    };
    requestAnimationFrame(record);
  });
  await page.getByRole('link', { name: 'History', exact: true }).click();
  await expect(page.locator('[data-testid="tab-transition-1"]:visible')).toHaveCSS('transform', 'matrix(1, 0, 0, 1, 0, 0)');
  await page.waitForTimeout(220);
  await page.getByRole('link', { name: 'Ride', exact: true }).click();
  await expect(page.locator('[data-testid="tab-transition-0"]:visible')).toHaveCSS('transform', 'matrix(1, 0, 0, 1, 0, 0)');
  await page.waitForTimeout(220);
  const motion = await page.evaluate(() => (window as unknown as { reducedMotionProbe: number[] }).reducedMotionProbe);
  expect(motion.length).toBeGreaterThan(5);
  expect(motion.every(value => value === 0)).toBe(true);
});


test('empty live preview waits for measurements without claiming a ride is syncing', async ({ page }) => {
  await page.goto('/');
  await expect(page.getByTestId('monitor-status')).toHaveText('Awaiting data');
  await page.getByTestId('monitor-view-picker').click();
  await expect(page.getByTestId('monitor-status-details')).toContainText('Waiting for measurements.');
  await expect(page.getByTestId('monitor-status-details')).not.toContainText('Syncing');
});
