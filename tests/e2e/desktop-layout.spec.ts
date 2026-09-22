import { expect, test, type Page } from '@playwright/test';
import { exportCsv } from '../helpers/export-csv';
import { syntheticSample } from '../fixtures/synthetic-sample';

async function openExample(page: Page) {
  await page.goto('/sessions');
  const chooser = page.waitForEvent('filechooser');
  await page.getByRole('button', { name: 'Open CSV', exact: true }).click();
  const samples = Array.from({ length: 240 }, (_, index) => syntheticSample(index, index, new Date(Date.UTC(2026, 8, 8) + index * 1000).toISOString()));
  await (await chooser).setFiles({ name: 'Example ride.csv', mimeType: 'text/csv', buffer: Buffer.from(exportCsv(samples)) });
  await expect(page.getByTestId('monitor-chart-power')).toBeVisible();
}
async function chooseBattery(page: Page) {
  await page.getByTestId('monitor-view-picker').click();
  await page.getByRole('button', { name: /^Battery(?:\s+✓)?$/ }).click();
}
const cardOrder = (page: Page) => page.getByTestId('monitor-chart-grid').locator(':scope > [data-testid^="monitor-card-"]').evaluateAll(elements => elements.map(element => element.getAttribute('data-testid')!.replace('monitor-card-', '')));
const gridColumns = (page: Page) => page.getByTestId('monitor-chart-grid').evaluate(element => getComputedStyle(element).gridTemplateColumns.split(' ').length);

test('desktop grid contracts and expands without losing preferred layout, selection or zoom', async ({ page }, testInfo) => {
  await page.setViewportSize({ width: 1800, height: 1000 });
  await openExample(page);
  await chooseBattery(page);
  await page.getByRole('radio', { name: '3 columns', exact: true }).click();
  await expect.poll(() => gridColumns(page)).toBe(3);
  await page.getByTestId('monitor-zoom-in').click();
  const chart = page.getByTestId('monitor-chart-batteryVoltageV');
  await chart.focus(); await chart.press('ArrowRight');
  await expect(chart).toHaveAttribute('aria-valuetext', /Battery voltage [\d.]+ V/);
  const selection = await chart.getAttribute('aria-valuetext');
  for (const width of [1280, 900, 759, 390, 320, 1100, 1800]) {
    await page.setViewportSize({ width, height: 1000 });
    await expect(page.getByTestId('monitor-chart-batteryVoltageV')).toHaveAttribute('aria-valuetext', selection!);
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    const workspace = (await page.getByTestId('app-workspace').boundingBox())!;
    expect(workspace.width).toBeGreaterThan(Math.min(width, 1400) - 60);
    if (width >= 760) {
      await expect(page.getByRole('radio', { name: '3 columns', exact: true })).toBeChecked();
      const count = width >= 1800 ? 3 : 2;
      await expect.poll(() => gridColumns(page)).toBe(count);
      const cards = await page.locator('[data-testid^="monitor-card-"]').evaluateAll(elements => elements.map(element => { const box = element.getBoundingClientRect(); return { width: box.width, right: box.right }; }));
      expect(cards.every(card => card.width >= 350 && card.right <= width)).toBe(true);
    } else await expect(page.getByTestId('monitor-chart-grid')).toHaveCount(0);
  }
  await page.screenshot({ animations: 'disabled', path: testInfo.outputPath('desktop-battery.png'), fullPage: true });
});

test('chart handles support repeated drag, keyboard reorder, per-view persistence and fullscreen', async ({ page }, testInfo) => {
  await page.setViewportSize({ width: 1600, height: 1000 });
  await openExample(page); await chooseBattery(page);
  await page.getByRole('radio', { name: '2 columns', exact: true }).click();
  await expect.poll(() => cardOrder(page)).toEqual(['batteryVoltageV', 'current', 'power']);
  await page.getByTestId('monitor-drag-batteryVoltageV').dragTo(page.getByTestId('monitor-card-power'));
  await expect.poll(() => cardOrder(page)).toEqual(['current', 'power', 'batteryVoltageV']);
  await page.getByTestId('monitor-drag-power').dragTo(page.getByTestId('monitor-card-current'));
  await expect.poll(() => cardOrder(page)).toEqual(['power', 'current', 'batteryVoltageV']);
  await page.getByTestId('monitor-drag-power').focus();
  await page.getByTestId('monitor-drag-power').press('ArrowRight');
  await expect.poll(() => cardOrder(page)).toEqual(['current', 'power', 'batteryVoltageV']);
  await expect(page.getByTestId('monitor-drag-power')).toBeFocused();
  await page.getByTestId('monitor-expand').click();
  await expect(page.getByTestId('monitor-chart-power-fullscreen')).toBeVisible();
  await expect.poll(() => cardOrder(page)).toEqual(['current', 'power', 'batteryVoltageV']);
  await page.getByTestId('monitor-close').click();
  await page.getByRole('radio', { name: '1 column', exact: true }).click();
  await expect.poll(() => gridColumns(page)).toBe(1);
  await page.screenshot({ animations: 'disabled', path: testInfo.outputPath('desktop-stacked.png'), fullPage: true });
  await page.reload(); await openExample(page);
  await expect.poll(() => cardOrder(page)).toEqual(['current', 'power', 'batteryVoltageV']);
  await expect(page.getByRole('radio', { name: '1 column', exact: true })).toBeChecked();
  await page.getByTestId('monitor-view-picker').click();
  await page.getByRole('button', { name: /^Ride(?:\s+✓)?$/ }).click();
  await expect(page.getByRole('radio', { name: '2 columns', exact: true })).toBeChecked();
  await expect.poll(() => cardOrder(page)).toEqual(['power', 'cadenceRpm']);
});

test('one persistent header stays fixed during navigation and scrolling at phone and desktop widths', async ({ page }) => {
  for (const width of [390, 1440]) {
    await page.setViewportSize({ width, height: 900 });
    await page.goto('/');
    const header = page.getByTestId('app-header');
    await expect(header).toHaveCount(1);
    await header.evaluate(element => { element.setAttribute('data-persistent-header', 'yes'); });
    const box = await header.boundingBox();
    await page.evaluate(() => {
      const rectangles: { x: number; y: number }[] = []; Object.assign(window, { headerPositions: rectangles });
      const header = document.querySelector('[data-testid="app-header"]')!;
      const start = performance.now();
      const record = () => { const { x, y } = header.getBoundingClientRect(); rectangles.push({ x, y }); if (performance.now() - start < 600) requestAnimationFrame(record); };
      requestAnimationFrame(record);
    });
    await page.getByRole('link', { name: 'History', exact: true }).click();
    await expect(page.getByTestId('tab-transition-1')).toHaveCSS('transform', 'matrix(1, 0, 0, 1, 0, 0)');
    await page.getByRole('link', { name: 'Ride', exact: true }).click();
    await expect(header).toHaveAttribute('data-persistent-header', 'yes');
    await expect.poll(() => page.evaluate(() => (window as unknown as { headerPositions: { x: number; y: number }[] }).headerPositions.length)).toBeGreaterThan(2);
    const positions = await page.evaluate(() => (window as unknown as { headerPositions: { x: number; y: number }[] }).headerPositions);
    expect(positions.every(position => position.x === box!.x && position.y === box!.y)).toBe(true);
    expect(await header.evaluate(element => element.closest('[data-testid^="tab-transition-"]'))).toBeNull();
    await page.locator('[data-testid="monitor-chart-cadenceRpm"]:visible').scrollIntoViewIfNeeded();
    expect(await header.boundingBox()).toEqual(box);
  }
});
