import { expect, test, type Page } from '@playwright/test';
import { defaultMonitorPreferences } from '../../src/core/monitor';
import { exportCsv } from '../helpers/export-csv';
import { syntheticSample } from '../fixtures/synthetic-sample';

async function settingsLayout(page: Page) {
  return page.evaluate(() => {
    const boxes: Record<string, { x: number; y: number; width: number; height: number }> = {};
    for (const id of ['app-header', 'settings-display', 'settings-recording', 'settings-speed', 'settings-distance']) {
      const { x, y, width, height } = document.querySelector(`[data-testid="${id}"]`)!.getBoundingClientRect();
      boxes[id] = { x, y, width, height };
    }
    return boxes;
  });
}

for (const width of [320, 390, 900, 1440]) test(`Settings selectors stay stable and fit ${width}px`, async ({ page }, testInfo) => {
  await page.setViewportSize({ width, height: 900 });
  await page.goto('/settings');
  const distance = page.getByTestId('settings-distance');
  await expect(distance).toBeVisible();
  await expect(page.getByRole('link', { name: 'Settings', exact: true })).toHaveAttribute('aria-current', 'page');
  await expect(page.getByRole('link', { name: 'Ride', exact: true })).not.toHaveAttribute('aria-current', 'page');
  await expect(page.getByRole('switch', { name: 'Use Apple Watch' })).toHaveCount(0);
  await expect(page.getByRole('switch', { name: 'Save to Apple Health' })).toHaveCount(0);
  const before = await settingsLayout(page);
  if (width < 900) expect(before['settings-recording']!.y).toBeGreaterThan(before['settings-display']!.y + before['settings-display']!.height);
  else expect(before['settings-recording']!.y).toBe(before['settings-display']!.y);
  for (const link of await page.getByTestId('app-header').getByRole('link').all()) {
    const box = (await link.boundingBox())!;
    expect(box.height).toBeGreaterThanOrEqual(44);
    expect(box.x).toBeGreaterThanOrEqual(0);
    expect(box.x + box.width).toBeLessThanOrEqual(width);
  }
  await distance.click();
  const dialog = page.getByTestId('settings-options');
  await expect(dialog).toBeVisible();
  expect(await settingsLayout(page)).toEqual(before);
  await expect(dialog.getByRole('radio', { name: 'Auto', exact: true })).toHaveAttribute('aria-checked', 'true');
  await expect(dialog.getByRole('radio')).toHaveCount(6);
  const labels = await dialog.getByRole('radio').evaluateAll(nodes => nodes.map(node => node.firstElementChild!.getBoundingClientRect().x));
  expect(new Set(labels).size).toBe(1);
  await dialog.evaluate(element => {
    const frames: { height: number; title: string | null }[] = [];
    Object.assign(window, { settingsDismissalFrames: frames });
    const record = () => {
      if (!element.isConnected) return;
      const height = element.getBoundingClientRect().height;
      if (height > 0) frames.push({ height, title: element.querySelector('[role="heading"]')?.textContent ?? null });
      requestAnimationFrame(record);
    };
    record();
  });
  await dialog.getByRole('radio', { name: 'Controller estimate', exact: true }).click();
  await expect(dialog).toBeHidden();
  const dismissal = await page.evaluate(() => (window as unknown as { settingsDismissalFrames: { height: number; title: string | null }[] }).settingsDismissalFrames);
  expect(dismissal.length).toBeGreaterThan(0);
  expect(dismissal.every(frame => frame.height === dismissal[0]!.height && frame.title === 'Distance source')).toBe(true);
  await expect(distance).toHaveAccessibleName('Distance source, Controller estimate');
  expect(await settingsLayout(page)).toEqual(before);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.screenshot({ path: testInfo.outputPath(`settings-${width}.png`), fullPage: true });
});

test('Settings choices persist and keyboard dismissal returns focus without remounting Ride', async ({ page }) => {
  await page.emulateMedia({ reducedMotion: 'reduce' });
  await page.goto('/');
  await expect(page.getByTestId('ride-setup')).toBeVisible();
  await page.getByTestId('tab-transition-0').evaluate(node => node.setAttribute('data-settings-retained-ride', 'true'));
  await page.getByRole('link', { name: 'Settings', exact: true }).click();
  const speed = page.getByTestId('settings-speed');
  await expect(speed).toBeVisible();
  await speed.focus(); await page.keyboard.press('Enter');
  await page.getByRole('radio', { name: 'km/h', exact: true }).focus();
  await page.keyboard.press('End');
  await expect(page.getByRole('radio', { name: 'm/s', exact: true })).toBeFocused();
  await page.keyboard.press('Space');
  await expect(page.getByTestId('settings-options')).toBeHidden();
  await expect(speed).toBeFocused();
  await expect(speed).toHaveAccessibleName('Speed units, m/s');
  await page.getByTestId('settings-environment').click();
  await page.getByRole('radio', { name: 'Indoor', exact: true }).click();
  await page.getByTestId('settings-sampleHz').click();
  await page.getByRole('radio', { name: '8 Hz', exact: true }).click();
  await expect.poll(() => page.evaluate(() => JSON.parse(localStorage.getItem('power-log.monitor-preferences.v1')!).sampleHz)).toBe(8);
  await page.getByRole('link', { name: 'History', exact: true }).click();
  await expect(page.getByTestId('tab-transition-1').filter({ visible: true })).toHaveCount(1);
  await page.getByRole('link', { name: 'Ride', exact: true }).click();
  await expect(page.locator('[data-settings-retained-ride="true"]')).toBeVisible();
  await expect(page.locator('[data-settings-retained-ride="true"]')).toHaveAttribute('data-testid', 'tab-transition-0');
  await page.getByRole('link', { name: 'Settings', exact: true }).click();
  await page.reload();
  await expect(page.getByTestId('settings-speed')).toHaveAccessibleName('Speed units, m/s');
  await expect(page.getByTestId('settings-environment')).toHaveAccessibleName('Ride type, Indoor');
  await expect(page.getByTestId('settings-sampleHz')).toHaveAccessibleName('Bike sample rate, 8 Hz');
});

test('Settings hydrate retained choices and expose a failed write without hiding controls', async ({ page }) => {
  const preferences = { ...defaultMonitorPreferences(), speedUnit: 'mph', distanceSource: 'health:watch', sampleHz: 4 };
  await page.addInitScript(preferences => {
    localStorage.setItem('power-log.monitor-preferences.v1', JSON.stringify(preferences));
    const set = Storage.prototype.setItem;
    Storage.prototype.setItem = function (key, value) {
      if (key === 'power-log.monitor-preferences.v1') throw new Error('Test settings write failed');
      set.call(this, key, value);
    };
  }, preferences);
  await page.goto('/settings');
  await expect(page.getByTestId('settings-speed')).toHaveAccessibleName('Speed units, mph');
  await expect(page.getByTestId('settings-distance')).toHaveAccessibleName('Distance source, Health · Watch');
  await expect(page.getByTestId('settings-sampleHz')).toHaveAccessibleName('Bike sample rate, 4 Hz');
  const before = await settingsLayout(page);
  await page.getByTestId('settings-speed').click();
  await page.getByRole('radio', { name: 'km/h', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('Test settings write failed');
  expect(await settingsLayout(page)).toEqual(before);
  await page.getByRole('button', { name: 'Dismiss', exact: true }).click();
  await expect(page.getByTestId('settings-error')).toBeHidden();
  await expect(page.getByTestId('settings-distance')).toBeEnabled();
  expect(await page.evaluate(() => JSON.parse(localStorage.getItem('power-log.monitor-preferences.v1')!).speedUnit)).toBe('mph');
});

test('History keeps the same open CSV after Settings, Ride and repeated settled returns', async ({ page }) => {
  await page.goto('/sessions');
  const chooser = page.waitForEvent('filechooser');
  await page.getByRole('button', { name: 'Open CSV', exact: true }).click();
  await (await chooser).setFiles({ name: 'settings-retained.csv', mimeType: 'text/csv', buffer: Buffer.from(exportCsv([0, 1, 2].map(index => syntheticSample(index, index, `2026-09-08T00:00:0${index}.000Z`)))) });
  const history = page.getByTestId('tab-transition-1');
  await expect(history.getByTestId('monitor-chart-power')).toBeVisible();
  await history.evaluate(node => node.setAttribute('data-settings-retained-history', 'true'));
  const retained = page.locator('[data-settings-retained-history="true"]');
  for (const unit of ['mph', 'm/s']) {
    await page.getByRole('link', { name: 'Settings', exact: true }).click();
    await page.getByTestId('settings-speed').click();
    await page.getByRole('radio', { name: unit, exact: true }).click();
    await page.getByRole('link', { name: 'History', exact: true }).click();
    await expect(retained).toBeVisible();
    await expect.poll(() => retained.evaluate(node => new DOMMatrixReadOnly(getComputedStyle(node).transform).m41)).toBe(0);
    // A departing stale screen can satisfy text assertions before the next frame hides it.
    await page.waitForTimeout(250);
    await expect(retained.getByTestId('monitor-chart-power')).toBeVisible();
    await expect(retained.getByRole('button', { name: 'Export CSV', exact: true })).toBeVisible();
    await expect(page.getByTestId('tab-transition-1')).toHaveCount(1);
    await page.getByRole('link', { name: 'Ride', exact: true }).click();
    await expect(page.getByTestId('ride-setup').filter({ visible: true })).toBeVisible();
    await page.getByRole('link', { name: 'History', exact: true }).click();
    await page.waitForTimeout(250);
    await expect(retained.getByTestId('monitor-chart-power')).toBeVisible();
  }
});

test('browser Back closes a Settings selector before showing Ride controls', async ({ page }) => {
  await page.goto('/');
  await expect(page.getByTestId('ride-setup')).toBeVisible();
  await page.getByRole('link', { name: 'Settings', exact: true }).click();
  await page.getByTestId('settings-distance').click();
  await expect(page.getByTestId('settings-options')).toBeVisible();
  await page.goBack();
  await expect(page.getByTestId('settings-options')).toBeHidden();
  // Expo may recreate the initial root scene; unified-rides checks ongoing capture across this path.
  await expect(page.getByTestId('ride-setup').filter({ visible: true })).toBeVisible();
  await page.getByRole('link', { name: 'Settings', exact: true }).click();
  await expect(page.getByTestId('settings-distance')).toBeVisible();
  await expect(page.getByTestId('settings-options')).toBeHidden();
});

for (const [opener, modal] of [
  ['monitor-view-picker', 'monitor-menu-dialog'], ['monitor-edit', 'monitor-editor-dialog'],
  ['monitor-expand', 'monitor-close'], ['ride-setup', 'ride-setup-sheet'],
] as const) test(`${opener} closes on retained-route Back and stays closed on return`, async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 900 });
  await page.goto('/');
  await page.getByRole('link', { name: 'History', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Open CSV', exact: true })).toBeVisible();
  await page.getByRole('link', { name: 'Ride', exact: true }).click();
  await page.getByTestId(opener).filter({ visible: true }).click();
  await expect(page.getByTestId(modal)).toBeVisible();
  await page.goBack();
  await expect(page.getByTestId(modal)).toBeHidden();
  await expect(page.getByRole('button', { name: 'Open CSV', exact: true })).toBeVisible();
  await page.getByRole('link', { name: 'Ride', exact: true }).click();
  await expect(page.getByTestId(opener).filter({ visible: true })).toBeVisible();
  await expect(page.getByTestId(modal)).toBeHidden();
});

for (const width of [320, 390]) test(`Settings reserve longest labels with doubled browser text at ${width}px`, async ({ page }, testInfo) => {
  await page.setViewportSize({ width, height: 900 });
  await page.goto('/settings');
  await expect(page.getByTestId('settings-distance')).toBeVisible();
  const doubleText = async () => page.evaluate(() => {
    const nodes = [...document.querySelectorAll<HTMLElement>('[dir="auto"],a')].filter(node => !node.dataset.settingsDoubled && node.textContent?.trim() !== 'ϟ');
    const sizes = nodes.map(node => ({ node, size: parseFloat(getComputedStyle(node).fontSize), line: parseFloat(getComputedStyle(node).lineHeight) }));
    for (const { node, size, line } of sizes) {
      node.dataset.settingsDoubled = 'true'; node.style.fontSize = `${size * 2}px`;
      if (Number.isFinite(line)) node.style.lineHeight = `${line * 2}px`;
    }
  });
  await doubleText();
  const before = await settingsLayout(page);
  await page.evaluate(() => {
    const frames: number[][] = []; Object.assign(window, { settingsFrames: frames, recordSettingsFrames: true });
    const record = () => {
      if (!(window as unknown as { recordSettingsFrames: boolean }).recordSettingsFrames) return;
      frames.push(['settings-display', 'settings-recording', 'settings-distance'].flatMap(id => {
        const { x, y, width, height } = document.querySelector(`[data-testid="${id}"]`)!.getBoundingClientRect();
        return [x, y, width, height];
      }));
      requestAnimationFrame(record);
    };
    requestAnimationFrame(record);
  });
  await page.getByTestId('settings-distance').click(); await doubleText();
  await page.getByRole('radio', { name: 'Controller estimate', exact: true }).click();
  await expect(page.getByTestId('settings-options')).toBeHidden();
  expect(await settingsLayout(page)).toEqual(before);
  const frames = await page.evaluate(() => {
    Object.assign(window, { recordSettingsFrames: false });
    return (window as unknown as { settingsFrames: number[][] }).settingsFrames;
  });
  expect(frames.length).toBeGreaterThan(2);
  expect(frames.every(frame => JSON.stringify(frame) === JSON.stringify(frames[0]))).toBe(true);
  const overflowing = await page.getByTestId('settings-screen').evaluate(element => [...element.querySelectorAll<HTMLElement>('[dir="auto"]')].filter(node => node.scrollWidth > node.clientWidth + 1).map(node => node.textContent));
  expect(overflowing).toEqual([]);
  for (const link of await page.getByTestId('app-header').getByRole('link').all()) {
    expect(await link.evaluate(node => node.scrollWidth <= node.clientWidth + 1)).toBe(true);
    const box = (await link.boundingBox())!; expect(box.x + box.width).toBeLessThanOrEqual(width);
  }
  await page.screenshot({ path: testInfo.outputPath(`settings-text2x-${width}.png`), fullPage: true });
  await page.setViewportSize({ width: 1440, height: 1000 });
  await expect.poll(async () => { const wide = await settingsLayout(page); return wide['settings-recording']!.y === wide['settings-display']!.y; }).toBe(true);
  await page.setViewportSize({ width, height: 900 });
  await expect.poll(() => settingsLayout(page)).toEqual(before);
});
