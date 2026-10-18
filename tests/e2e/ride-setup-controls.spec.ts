import { expect, test } from '@playwright/test';
import { defaultMonitorPreferences, monitorPreferencesJson } from '../../src/core/monitor';
import { syntheticBike } from '../fixtures/browser-bike';
import { expectTouchTargets } from '../helpers/ui-targets';

const accent = 'rgb(255, 135, 60)';
const secondary = 'rgb(32, 38, 48)';
for (const width of [320, 390, 1440]) {
  test(`Ride empty state and setup have one primary action at ${width}px`, async ({ page }, testInfo) => {
    await page.setViewportSize({ width, height: 900 });
    await syntheticBike(page);
    const preferences = defaultMonitorPreferences();
    preferences.workoutOptions.recordGPS = false;
    await page.addInitScript(
      preferences => localStorage.setItem('power-log.monitor-preferences.v1', JSON.stringify(preferences)),
      JSON.parse(monitorPreferencesJson(preferences)),
    );
    await page.goto('/');
    for (const id of ['humanPowerW', 'cadenceRpm', 'motorInputPowerW', 'controllerSpeedMps'])
      await expect(page.getByTestId(`monitor-number-${id}`)).toContainText('—');
    await expect(page.getByText('No bike connected', { exact: true })).toBeVisible();
    await expect(
      page.getByText('Turn on your bike, then find it to see live readings.', { exact: true }),
    ).toBeVisible();
    const find = page.getByRole('button', { name: 'Find bike', exact: true });
    await expect(find).toHaveCSS('background-color', accent);
    await expect(page.getByTestId('start-ride')).toHaveText('Set up ride');
    await expect(page.getByTestId('start-ride')).toHaveCSS('background-color', secondary);
    await expectTouchTargets(page.locator('body'));
    await page.screenshot({
      path: testInfo.outputPath(`ride-empty-${width}.png`),
      fullPage: true,
      animations: 'disabled',
    });
    await page.getByTestId('start-ride').click();
    const sheet = page.getByTestId('ride-setup-sheet');
    for (const removed of [
      'This ride will record',
      'GPS route Off',
      'Health Off',
      'Connection details',
      'Permissions',
      'No permission changes needed',
      'Outdoor ·',
      'Firmware',
    ])
      await expect(sheet).not.toContainText(removed);
    await expect(sheet.getByRole('link', { name: 'Ride settings', exact: true })).toBeVisible();
    await expect(sheet.getByRole('button', { name: 'Done', exact: true })).toHaveCSS(
      'background-color',
      'rgba(0, 0, 0, 0)',
    );
    await expect(sheet.getByTestId('setup-start-ride')).toBeDisabled();
    await expect(sheet.getByTestId('setup-find-bike')).toHaveCSS('background-color', accent);
    await expect(sheet).toContainText('Connect your bike to start.');
    await expectTouchTargets(sheet);
    await expect(sheet.getByRole('heading', { name: 'Ride setup', exact: true })).toHaveCSS('font-size', '18px');
    await expect(sheet.getByRole('button', { name: 'Done', exact: true }).getByText('Done', { exact: true })).toHaveCSS(
      'font-size',
      '15px',
    );
    await page.screenshot({
      path: testInfo.outputPath(`ride-setup-disconnected-${width}.png`),
      fullPage: true,
      animations: 'disabled',
    });
    await sheet.getByRole('link', { name: 'Ride settings', exact: true }).click();
    await expect(page).toHaveURL(/\/settings$/);
    await expect(sheet).toBeHidden();
    await page.goBack();
    await expect(page.getByTestId('start-ride')).toBeVisible();
    await expect(sheet).toBeHidden();
    await page.getByTestId('start-ride').focus();
    await expect(page.getByTestId('start-ride')).toBeFocused();
    await page.getByTestId('start-ride').click();
    await sheet.getByTestId('setup-find-bike').click();
    await sheet.getByRole('button', { name: /^Connect CYC/ }).click();
    await expect(sheet.getByTestId('bike-connection-status')).toContainText('Connected');
    await expect(sheet.getByTestId('setup-start-ride')).toBeEnabled();
    await expect(sheet.getByTestId('setup-start-ride')).toHaveCSS('background-color', accent);
    await page.screenshot({
      path: testInfo.outputPath(`ride-setup-${width}.png`),
      fullPage: true,
      animations: 'disabled',
    });
    await sheet.getByTestId('setup-start-ride').click();
    const bar = page.getByTestId('ride-controls');
    const pause = bar.getByRole('button', { name: 'Pause', exact: true });
    const lap = bar.getByRole('button', { name: 'Lap', exact: true });
    const finish = bar.getByRole('button', { name: 'Finish', exact: true });
    await expect(bar.getByTestId('ride-state-badge')).toHaveText('Running');
    await expect(bar.getByTestId('ride-state-badge').getByText('Running', { exact: true })).toHaveCSS(
      'font-size',
      '12px',
    );
    await expectTouchTargets(bar);
    await expect(pause).toHaveText('Pause');
    await expect(lap).toHaveText('Lap');
    const before = {
      pause: await pause.boundingBox(),
      lap: await lap.boundingBox(),
      finish: await finish.boundingBox(),
    };
    await pause.click();
    const resume = bar.getByRole('button', { name: 'Resume', exact: true });
    await expect(bar.getByTestId('ride-state-badge')).toHaveText('Paused');
    await expect(resume).toHaveText('Resume');
    await expect(lap).toBeDisabled();
    for (const [button, bounds] of [
      [resume, before.pause],
      [lap, before.lap],
      [finish, before.finish],
    ] as const) {
      const after = (await button.boundingBox())!;
      expect(after.x).toBeCloseTo(bounds!.x, 1);
      expect(after.y).toBeCloseTo(bounds!.y, 1);
    }
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await page.screenshot({
      path: testInfo.outputPath(`ride-paused-${width}.png`),
      fullPage: true,
      animations: 'disabled',
    });
    await resume.click();
    await expect(bar.getByTestId('ride-state-badge')).toHaveText('Running');
    await finish.click();
    await page.getByRole('button', { name: 'Save ride', exact: true }).click();
    await expect(page.getByTestId('start-ride')).toBeVisible();
  });

  test(`drag handles reorder by keyboard and retain layout at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await page.goto('/');
    await page.getByTestId('monitor-edit').click();
    const editor = page.getByTestId('monitor-editor-dialog');
    const cadence = page.getByTestId('monitor-drag-cadenceRpm');
    await expect(cadence).toHaveAttribute('aria-valuenow', '2');
    await expect(editor.getByRole('button', { name: /^Move / })).toHaveCount(0);
    const box = (await cadence.boundingBox())!;
    expect(box.width).toBeGreaterThanOrEqual(48);
    expect(box.height).toBeGreaterThanOrEqual(48);
    await cadence.press('ArrowUp');
    await expect(cadence).toHaveAttribute('aria-valuenow', '1');
    await cadence.press('ArrowUp');
    await expect(cadence).toHaveAttribute('aria-valuenow', '1');
    await cadence.press('ArrowDown');
    await expect(cadence).toHaveAttribute('aria-valuenow', '2');
    await expect(cadence).toBeFocused();
    await editor.getByRole('tab', { name: 'Graphs', exact: true }).click();
    await expect(cadence).toHaveAttribute('aria-valuenow', '3');
    await cadence.press('ArrowUp');
    await expect(cadence).toHaveAttribute('aria-valuenow', '2');
    await cadence.press('ArrowUp');
    await expect(cadence).toHaveAttribute('aria-valuenow', '1');
    await editor.getByRole('button', { name: 'Done', exact: true }).click();
    await page.reload();
    await page.getByTestId('monitor-edit').click();
    await expect(cadence).toHaveAttribute('aria-valuenow', '2');
    await editor.getByRole('tab', { name: 'Graphs', exact: true }).click();
    await expect(cadence).toHaveAttribute('aria-valuenow', '1');
  });
}

for (const width of [320, 390, 1440])
  test(`setup isolates background controls and restores them on dismissal at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await page.goto('/');
    const header = page.getByTestId('app-header');
    await expect(header.getByRole('link')).toHaveCount(3);
    await page.getByTestId('start-ride').click();
    const sheet = page.getByTestId('ride-setup-sheet');
    await expect(sheet).toBeVisible();
    await expect(header.getByRole('link')).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Find bike', exact: true })).toHaveCount(1);
    expect(await header.evaluate(node => node.closest('[inert]') !== null)).toBe(true);
    await expect(page.getByRole('dialog')).toBeVisible();
    await sheet.getByRole('button', { name: 'Done', exact: true }).focus();
    for (let index = 0; index < 18; index++) {
      await page.keyboard.press('Tab');
      expect(await page.evaluate(() => document.activeElement?.closest('[aria-modal="true"]') !== null)).toBe(true);
    }
    await sheet.getByRole('button', { name: 'Done', exact: true }).click();
    await expect(sheet).toBeHidden();
    await expect(header.getByRole('link')).toHaveCount(3);
    expect(await header.evaluate(node => node.closest('[inert]') !== null)).toBe(false);
    await header.getByRole('link', { name: 'History', exact: true }).focus();
    await expect(header.getByRole('link', { name: 'History', exact: true })).toBeFocused();
  });
