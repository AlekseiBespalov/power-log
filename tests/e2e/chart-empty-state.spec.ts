import { expect, test, type Locator } from '@playwright/test';
import { defaultMonitorPreferences } from '../../src/core/monitor';
import { exportCsv } from '../helpers/export-csv';
import { syntheticSample } from '../fixtures/synthetic-sample';

async function expectCentered(chart: Locator) {
  await expect(chart.getByText('No samples', { exact: true })).toBeVisible();
  await expect.poll(() => chart.evaluate(element => {
    const svg = element.querySelector('svg')!;
    const matrix = svg.getScreenCTM()!;
    const grid = [...svg.querySelectorAll('line[stroke-dasharray]')];
    const endpoints = grid.flatMap(line => [
      new DOMPoint(Number(line.getAttribute('x1')), Number(line.getAttribute('y1'))).matrixTransform(matrix),
      new DOMPoint(Number(line.getAttribute('x2')), Number(line.getAttribute('y2'))).matrixTransform(matrix),
    ]);
    const label = [...element.querySelectorAll('*')].find(node => node.childNodes.length === 1 && node.firstChild?.nodeType === Node.TEXT_NODE && node.textContent === 'No samples')!;
    const bounds = label.getBoundingClientRect();
    const centerX = (Math.min(...endpoints.map(p => p.x)) + Math.max(...endpoints.map(p => p.x))) / 2;
    const centerY = (Math.min(...endpoints.map(p => p.y)) + Math.max(...endpoints.map(p => p.y))) / 2;
    return Math.max(Math.abs(bounds.x + bounds.width / 2 - centerX), Math.abs(bounds.y + bounds.height / 2 - centerY));
  })).toBeLessThanOrEqual(1);
}

test('empty label stays at the plot center through resize, text enlargement and fullscreen', async ({ page }, testInfo) => {
  const preferences = defaultMonitorPreferences();
  preferences.views.ride.numbers = [];
  preferences.views.ride.charts = ['speedMps', 'controllerSpeedMps', 'motorInputPowerW'];
  await page.addInitScript(value => localStorage.setItem('power-log.monitor-preferences.v1', JSON.stringify(value)), preferences);
  await page.goto('/sessions');
  const chooser = page.waitForEvent('filechooser');
  await page.getByRole('button', { name: 'Open CSV', exact: true }).click();
  const csv = exportCsv([0, 22].map((seconds, index) => syntheticSample(seconds, index, new Date(Date.UTC(2026, 8, 12) + seconds * 1000).toISOString())));
  await (await chooser).setFiles({ name: 'Synthetic missing speed.csv', mimeType: 'text/csv', buffer: Buffer.from(csv) });
  const chart = page.getByTestId('monitor-chart-speed');
  for (const [width, height] of [[320, 640], [440, 956], [768, 800], [1440, 900], [1920, 1080], [440, 956]]) {
    await page.setViewportSize({ width: width!, height: height! });
    await expectCentered(chart);
  }
  const label = chart.getByText('No samples', { exact: true });
  await label.evaluate(element => { (element as HTMLElement).style.fontSize = '24px'; });
  await expectCentered(chart);
  await chart.screenshot({ path: testInfo.outputPath('empty-chart-enlarged.png') });
  await expect(page.getByTestId('monitor-chart-power').getByText('No samples', { exact: true })).toHaveCount(0);
  await page.getByTestId('monitor-expand').click();
  const fullscreen = page.getByTestId('monitor-chart-speed-fullscreen');
  for (const [width, height] of [[440, 956], [956, 440], [1440, 1000]]) {
    await page.setViewportSize({ width: width!, height: height! });
    await expectCentered(fullscreen);
  }
  await fullscreen.screenshot({ path: testInfo.outputPath('empty-chart-fullscreen.png') });
  await page.getByTestId('monitor-close').click();
  for (const hasSpeed of [true, false]) {
    const file = page.waitForEvent('filechooser');
    await page.getByRole('button', { name: 'Open CSV', exact: true }).click();
    const samples = [0, 22].map((seconds, index) => ({
      ...syntheticSample(seconds, index, new Date(Date.UTC(2026, 8, 12) + seconds * 1000).toISOString()),
      controllerSpeedMps: hasSpeed && index === 0 ? 0 : undefined,
      controllerModel: hasSpeed && index === 0 ? 'X6' : undefined,
      firmwareLabel: hasSpeed && index === 0 ? '20260101' : undefined,
      controllerProtocol: hasSpeed && index === 0 ? '5.3' : undefined,
    }));
    await (await file).setFiles({ name: `Synthetic speed ${hasSpeed}.csv`, mimeType: 'text/csv', buffer: Buffer.from(exportCsv(samples)) });
    if (hasSpeed) {
      await expect(chart.getByText('No samples', { exact: true })).toHaveCount(0);
      await expect(chart.locator('svg circle')).toHaveCount(1);
    } else await expectCentered(chart);
  }
});
