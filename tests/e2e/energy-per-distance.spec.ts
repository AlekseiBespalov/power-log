import { expect, test } from '@playwright/test';
import { saveRides } from '../helpers/saved-rides';
import { seedSettings } from '../helpers/settings-store';

const seconds = Array.from({ length: 121 }, (_, t) => t);

for (const width of [390, 1440])
  test(`saved ride displays Wh/km trends and weighted averages at ${width}px`, async ({ page }, testInfo) => {
    await page.setViewportSize({ width, height: 1200 });
    await seedSettings(page, {
      app: {
        units: 'metric',
        historyRange: 'all',
        views: {
          ride: {
            numbers: ['batteryWhPerKm', 'riderWhPerKm'],
            charts: ['batteryWhPerKm', 'riderWhPerKm', 'controllerSpeedMps'],
          },
        },
      },
    });
    await expect(page.getByText('No saved rides yet.', { exact: true })).toBeVisible();
    const [id] = await saveRides(page, [
      {
        startUtcMs: Date.UTC(2026, 0, 1),
        durationMs: 120_000,
        sampleHz: 4,
        battery: 'calculated',
        atMs: seconds.map(t => t * 1000),
        columns: {
          humanPowerW: seconds.map(() => 180),
          speedRaw: seconds.map(t => (t <= 60 ? 18 : 36)),
          batteryVoltageV: seconds.map(() => 50),
          batteryCurrentA: seconds.map(() => 36),
          motorInputPowerW: seconds.map(() => 1800),
          consumedWh: seconds.map(t => 100 + t * 2),
        },
      },
    ]);
    await page.goto(`/sessions/ride?id=${id}`);
    const battery = page.getByTestId('monitor-readout-batteryWhPerKm');
    const rider = page.getByTestId('monitor-readout-riderWhPerKm');
    await expect(battery).toContainText('avg 66.9');
    await expect(rider).toContainText('avg 6.7');
    const chart = page.getByTestId('monitor-chart-energy-per-distance');
    await expect(chart).toHaveCount(1);
    await expect(chart).not.toContainText('No samples');
    await expect(page.getByTestId('monitor-number-batteryWhPerKm')).toContainText('66.9');
    await expect(page.getByTestId('monitor-number-riderWhPerKm')).toContainText('6.7');
    await expect(page.getByText('Wh/km · ride avg', { exact: true })).toHaveCount(2);
    const smooth = page.getByTestId('monitor-energy-smoothing');
    for (const seconds of [5, 30, 60, 0]) {
      await smooth.click();
      const menu = page.getByTestId('monitor-energy-smoothing-menu');
      await expect(menu).toBeVisible();
      const box = await menu.boundingBox();
      expect(box!.width).toBeLessThan(160);
      expect(box!.height).toBeLessThan(220);
      await page.getByTestId(`monitor-energy-smoothing-${seconds}`).click();
      await expect(menu).not.toBeVisible();
      await expect(smooth).toHaveText(seconds ? `Smooth · ${seconds} s ▾` : 'Smooth ▾');
      await expect(battery).toContainText('avg 66.9');
      await expect(page.getByTestId('monitor-number-batteryWhPerKm')).toContainText('66.9');
    }
    await smooth.click();
    await expect(page.getByTestId('monitor-energy-smoothing-menu')).toBeVisible();
    await page.keyboard.press('Escape');
    await expect(page.getByTestId('monitor-energy-smoothing-menu')).not.toBeVisible();
    await smooth.click();
    await expect(page.getByTestId('monitor-energy-smoothing-menu')).toBeVisible();
    const triggerBox = await smooth.boundingBox(),
      menuBox = await page.getByTestId('monitor-energy-smoothing-menu').boundingBox();
    expect(Math.abs(menuBox!.y - (triggerBox!.y + triggerBox!.height))).toBeLessThan(2);
    await page.screenshot({ path: testInfo.outputPath(`smoothing-menu-${width}.png`) });
    await page.getByTestId('monitor-energy-smoothing-backdrop').click({ position: { x: 1, y: 1 } });
    await expect(page.getByTestId('monitor-energy-smoothing-menu')).not.toBeVisible();
    await chart.scrollIntoViewIfNeeded();
    await page.screenshot({ path: testInfo.outputPath(`energy-per-distance-${width}.png`), fullPage: true });
  });
