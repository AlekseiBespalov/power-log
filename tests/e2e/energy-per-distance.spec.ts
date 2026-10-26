import { expect, test } from '@playwright/test';
import { defaultMonitorPreferences, monitorPreferencesJson } from '../../src/core/monitor';
import { browserTestBundle } from '../helpers/browser-test-bundle';
import type * as Harness from '../fixtures/browser-ride-harness';

declare global {
  interface Window {
    energyDistanceStorage: typeof Harness;
  }
}
const bundle = browserTestBundle('tests/fixtures/browser-ride-harness.ts', 'energyDistanceStorage');

for (const width of [390, 1440])
  test(`saved ride displays Wh/km trends and weighted averages at ${width}px`, async ({ page }, testInfo) => {
    await page.setViewportSize({ width, height: 1200 });
    const preferences = defaultMonitorPreferences();
    preferences.historyRange = 'all';
    preferences.views.ride.numbers = ['batteryWhPerKm', 'riderWhPerKm'];
    preferences.views.ride.charts = ['batteryWhPerKm', 'riderWhPerKm', 'controllerSpeedMps'];
    await page.addInitScript(
      value => localStorage.setItem('power-log.monitor-preferences.v1', JSON.stringify(value)),
      JSON.parse(monitorPreferencesJson(preferences)),
    );
    await page.goto('/sessions');
    await expect(page.getByText('No saved rides yet.', { exact: true })).toBeVisible();
    await page.addScriptTag({ content: bundle });
    const id = await page.evaluate(async () => {
      const h = window.energyDistanceStorage;
      const ride = await h.store.begin(
        'energy-test',
        {
          indoor: true,
          recordGPS: false,
          sampleHz: 4,
          distanceSource: 'controller',
          batteryConsumptionSource: 'calculated',
        },
        '2026-01-01T00:00:00Z',
      );
      for (let start = 0; start <= 120; start += 64) {
        const rows = Array.from({ length: Math.min(64, 121 - start) }, (_, i) => {
          const t = start + i;
          return {
            ...h.browserRow(t, t, new Date(Date.UTC(2026, 0, 1) + t * 1000).toISOString()),
            recordingId: ride.id,
            humanPowerW: 180,
            controllerSpeedMps: t <= 60 ? 5 : 10,
            speedRaw: t <= 60 ? 18 : 36,
            controllerModel: 'X6',
            controllerProtocol: '5.3',
            firmwareLabel: '20260101',
            batteryVoltageV: 50,
            batteryCurrentA: 36,
            motorInputPowerW: 1800,
            consumedWh: 100 + t * 2,
          };
        });
        const last = rows.at(-1)!;
        await h.store.append(ride.id, 'energy-test', rows, last.elapsedSeconds, last.elapsedSeconds, last.timestamp);
      }
      await h.store.transition(ride.id, 'energy-test', 'stop', 120, 120, '2026-01-01T00:02:00Z');
      return ride.id;
    });
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
