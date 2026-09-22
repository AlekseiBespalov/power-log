import { expect, test, type Page } from '@playwright/test';
import { exportCsv } from '../helpers/export-csv';
import { syntheticSample } from '../fixtures/synthetic-sample';

async function openSpeedExample(page: Page, known: boolean) {
  await page.goto('/sessions');
  const chooser = page.waitForEvent('filechooser');
  await page.getByRole('button', { name: 'Open CSV', exact: true }).click();
  const samples = Array.from({ length: 40 }, (_, index) => ({
    ...syntheticSample(index, index, new Date(Date.UTC(2026, 8, 8) + index * 1000).toISOString()), speedRaw: 36,
    ...(known ? { controllerSpeedMps: 10, controllerModel: 'X6', firmwareLabel: '20250604', controllerProtocol: '5.3' } : {}),
  }));
  await (await chooser).setFiles({ name: 'Controller speed.csv', mimeType: 'text/csv', buffer: Buffer.from(exportCsv(samples)) });
  await expect(page.getByTestId('monitor-edit')).toBeVisible();
}

for (const width of [390, 1440]) test(`controller speed units and legacy fallback at ${width}px`, async ({ page }) => {
  await page.setViewportSize({ width, height: 1000 });
  await openSpeedExample(page, true);
  await page.getByTestId('monitor-edit').click();
  await page.getByRole('checkbox', { name: 'Controller speed', exact: true }).click();
  await page.getByRole('tab', { name: 'Graphs', exact: true }).click();
  await page.getByRole('checkbox', { name: 'GPS speed', exact: true }).click();
  await page.getByRole('checkbox', { name: 'Controller speed', exact: true }).click();
  await page.getByRole('button', { name: 'Done', exact: true }).click();
  await expect(page.getByTestId('monitor-number-controllerSpeedMps')).toContainText('36.0');
  await expect(page.getByTestId('monitor-number-controllerSpeedMps')).toContainText('km/h');
  await page.getByTestId('tab-transition-1').evaluate(node => node.setAttribute('data-retained-speed-history', 'true'));
  await page.getByRole('link', { name: 'Settings', exact: true }).click();
  await page.getByTestId('settings-speed').click();
  await page.getByRole('radio', { name: 'mph', exact: true }).click();
  await page.getByRole('link', { name: 'History', exact: true }).click();
  const retainedHistory = page.locator('[data-retained-speed-history="true"]');
  await expect(retainedHistory).toBeVisible();
  await expect.poll(() => retainedHistory.evaluate(node => new DOMMatrixReadOnly(getComputedStyle(node).transform).m41)).toBe(0);
  await page.waitForTimeout(250);
  await expect(retainedHistory.getByTestId('monitor-number-controllerSpeedMps')).toBeVisible();
  await expect(page.getByTestId('monitor-number-controllerSpeedMps')).toContainText('22.4');
  await expect(page.getByTestId('monitor-number-controllerSpeedMps')).toContainText('mph');
  await page.getByTestId('monitor-chart-speed').scrollIntoViewIfNeeded();
  await expect(page.getByTestId('monitor-chart-speed')).toBeVisible();
  await expect(page.getByTestId('monitor-readout-speedMps')).toContainText('GPS');
  await expect(page.getByTestId('monitor-readout-controllerSpeedMps')).toContainText('Controller');
  await expect(page.getByTestId('monitor-chart-speed')).toHaveCount(1);
  if (width > 760) {
    const card = page.getByTestId('monitor-card-speed');
    await expect(card).toContainText('mph');
    await expect(card.getByTestId('monitor-readout-speedMps')).toHaveCount(1);
    await expect(card.getByTestId('monitor-readout-controllerSpeedMps')).toHaveCount(1);
  }
  await page.reload();
  await openSpeedExample(page, false);
  await expect(page.getByTestId('monitor-number-speedRaw')).toContainText('36.00');
  await expect(page.getByTestId('monitor-number-speedRaw')).toContainText('unit unknown');
  await expect(page.getByTestId('monitor-number-speedRaw')).not.toContainText('mph');
  await expect(page.getByTestId('monitor-chart-speed')).toHaveCount(1);
  await expect(page.getByTestId('monitor-chart-speedRaw')).toHaveCount(1);
  await page.getByTestId('monitor-edit').click();
  await expect(page.getByTestId('monitor-editor-dialog').getByRole('radio')).toHaveCount(0);
  await expect(page.getByRole('checkbox', { name: 'Controller speed', exact: true })).toHaveAttribute('aria-checked', 'true');
});
