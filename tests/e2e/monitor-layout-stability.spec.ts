import { expect, test, type Page } from '@playwright/test';
import { mkdir, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { exportCsv } from '../helpers/export-csv';
import { defaultMonitorPreferences } from '../../src/core/monitor';
import { syntheticSample } from '../fixtures/synthetic-sample';

const phase = process.env.POWER_LOG_POLISH_PHASE ?? 'after';
const artifactDirectory = process.env.POWER_LOG_POLISH_ARTIFACTS ?? `/tmp/power-log-ui-polish-${phase}`;
const fixture = (long = false, finalValue?: number) => exportCsv([0, 1, 2, 20, 21, 22].map((seconds, index) => {
  const elapsed = long && index > 0 ? seconds + 36000 : seconds;
  const value = index === 5 && finalValue !== undefined ? finalValue : [8.9, 21.8, 100, 8.9, 21.8, 100][index]!;
  return { ...syntheticSample(elapsed, index, new Date(Date.UTC(2026, 8, 11) + elapsed * 1000).toISOString()),
    batteryCurrentA: value, motorCurrentA: value, motorTempC: value, controllerTempC: value, batteryVoltageV: 50 + index / 10,
    motorInputPowerW: (50 + index / 10) * value, speedRaw: value,
    controllerSpeedMps: value / 3.6, controllerModel: 'X6', firmwareLabel: '20260101', controllerProtocol: '5.3' };
}));
async function importFixture(page: Page, long = false, finalValue?: number) {
  const chooser = page.waitForEvent('filechooser');
  await page.getByRole('button', { name: 'Open CSV', exact: true }).click();
  await (await chooser).setFiles({ name: long ? 'Long synthetic timestamps.csv' : 'Synthetic width transitions.csv', mimeType: 'text/csv', buffer: Buffer.from(fixture(long, finalValue)) });
  await expect(page.getByTestId('monitor-chart-current')).toBeVisible();
}
async function measurements(page: Page) {
  return page.getByTestId('monitor-charts').evaluate(root => {
    const rectangle = (node: Element) => { const r = node.getBoundingClientRect(); return { width: r.width, height: r.height, x: r.x, y: r.y }; };
    const chartSurfaces = [...root.querySelectorAll('[data-testid^="monitor-chart-"]')].filter(node => node.getAttribute('aria-valuetext') !== null);
    const charts = chartSurfaces.map(node => {
      let card = node.parentElement!;
      while (card !== root && parseFloat(getComputedStyle(card).borderTopWidth) < 1) card = card.parentElement!;
      const c = rectangle(card), p = rectangle(node);
      return { id: node.getAttribute('data-testid'), plotHeight: p.height, cardHeight: c.height, plotTop: p.y - c.y, width: c.width, right: c.x + c.width };
    });
    const clipped: { id: string; text: string; textWidth: number; available: number }[] = [];
    for (const readout of document.querySelectorAll('[data-testid^="monitor-readout-"], [data-testid^="monitor-number-"]')) {
      const walker = document.createTreeWalker(readout, NodeFilter.SHOW_TEXT);
      while (walker.nextNode()) {
        const text = walker.currentNode; if (!text.textContent?.trim()) continue;
        const element = text.parentElement!, bounds = element.getBoundingClientRect(), range = document.createRange(); range.selectNodeContents(text);
        for (const rect of range.getClientRects()) if (rect.left < bounds.left - 1 || rect.right > bounds.right + 1 || rect.bottom > bounds.bottom + 1) {
          clipped.push({ id: readout.getAttribute('data-testid')!, text: text.textContent, textWidth: rect.width, available: bounds.width });
        }
      }
    }
    const numbers = [...document.querySelectorAll('[data-testid^="monitor-number-"]')].map(node => ({ id: node.getAttribute('data-testid'), ...rectangle(node) }));
    return { charts, numbers, clipped, horizontalOverflow: document.documentElement.scrollWidth - innerWidth,
      headerHeight: document.querySelector('[data-testid="monitor-header"]')!.getBoundingClientRect().height };
  });
}
type Measurements = Awaited<ReturnType<typeof measurements>>;
function instability(states: Measurements[]) {
  const initial = states[0]!;
  return states.flatMap((state, index) => state.charts.flatMap(chart => {
    const first = initial.charts.find(item => item.id === chart.id)!;
    return (['plotHeight', 'cardHeight', 'plotTop'] as const).filter(key => Math.abs(chart[key] - first[key]) > 1).map(key => ({ index, id: chart.id, key, initial: first[key], actual: chart[key] }));
  }));
}
async function screenshot(page: Page, name: string) {
  await mkdir(artifactDirectory, { recursive: true });
  // The app scrolls inside its persistent header. A focused-card capture must
  // not leave the next overview at a different position from its baseline.
  await page.getByRole('button', { name: 'Open CSV', exact: true }).evaluate(element => {
    for (let parent = element.parentElement; parent; parent = parent.parentElement) parent.scrollTop = 0;
  });
  await page.screenshot({ path: path.join(artifactDirectory, name), fullPage: true, animations: 'disabled' });
}
async function screenshotCard(page: Page, group: string, name: string) {
  await page.getByTestId(`monitor-chart-${group}`).evaluate(element => {
    let card = element.parentElement!;
    while (parseFloat(getComputedStyle(card).borderTopWidth) < 1) card = card.parentElement!;
    card.setAttribute('data-layout-capture', 'yes');
  });
  const card = page.locator('[data-layout-capture="yes"]'); await card.scrollIntoViewIfNeeded();
  await card.screenshot({ path: path.join(artifactDirectory, name), animations: 'disabled' });
  await card.evaluate(element => element.removeAttribute('data-layout-capture'));
}

for (const width of [320, 390, 430, 440, 768, 1440, 1920]) test(`readout widths, missing values and timestamps preserve chart layout at ${width}px`, async ({ page }) => {
  const preferences = defaultMonitorPreferences(); preferences.activeView = 'battery';
  preferences.views.battery.charts = ['batteryCurrentA', 'motorCurrentA', 'batteryVoltageV', 'motorTempC', 'controllerTempC', 'controllerSpeedMps', 'speedMps'];
  preferences.views.battery.numbers = ['controllerSpeedMps', 'motorCurrentA', 'heartRateBpm', 'motorTempC'];
  if (width === 430 || width === 440) preferences.views.battery.numbers = [];
  preferences.views.battery.webColumns = 3;
  await page.setViewportSize({ width, height: 1000 });
  await page.addInitScript(value => localStorage.setItem('power-log.monitor-preferences.v1', JSON.stringify(value)), preferences);
  await page.goto('/sessions'); await importFixture(page);
  const chart = page.getByTestId('monitor-chart-current');
  if (phase !== 'before') {
    const comparisonInsideVoltage = await page.getByTestId('monitor-chart-batteryVoltageV').evaluate(element => {
      let card = element.parentElement!;
      while (parseFloat(getComputedStyle(card).borderTopWidth) < 1) card = card.parentElement!;
      return card.contains(document.querySelector('[data-testid="monitor-reference-set"]'));
    });
    expect(comparisonInsideVoltage).toBe(true);
  }
  const states: Measurements[] = [await measurements(page)];
  await screenshot(page, `current-${width}-unselected.png`);
  await screenshotCard(page, 'speed', `speed-${width}-unselected.png`);
  await chart.focus(); await chart.press('Home'); await expect(chart).toHaveAttribute('aria-valuetext', /Battery current 8\.9 A/);
  await expect(page.getByTestId('monitor-readout-controllerSpeedMps')).toContainText('8.9');
  states.push(await measurements(page));
  await screenshot(page, `current-${width}-8.9.png`);
  await screenshotCard(page, 'speed', `speed-${width}-8.9.png`);
  for (const value of ['21.8', '100.0']) {
    await chart.press('ArrowRight'); await expect(chart).toHaveAttribute('aria-valuetext', new RegExp(`Battery current ${value.replace('.', '\\.')} A`));
    await expect(page.getByTestId('monitor-readout-controllerSpeedMps')).toContainText(value);
    states.push(await measurements(page)); await screenshot(page, `current-${width}-${value}.png`);
    await screenshotCard(page, 'speed', `speed-${width}-${value}.png`);
  }
  if (width !== 430 && width !== 440) await expect(page.getByTestId('monitor-number-heartRateBpm')).toContainText('—');
  // A real drag moves through narrow/wide values and a true acquisition gap.
  await chart.scrollIntoViewIfNeeded(); const box = (await chart.boundingBox())!;
  const x = (seconds: number) => box.x + 8 + seconds / 22 * (box.width - 16), y = box.y + 70;
  await page.mouse.move(x(21), y); await page.mouse.down();
  for (const [seconds, value] of [[0, '8.9'], [1, '21.8'], [2, '100.0'], [11, 'No sample']] as const) {
    await page.mouse.move(x(seconds), y, { steps: 4 });
    await expect(chart).toHaveAttribute('aria-valuetext', value === 'No sample' ? /Battery current: No sample/ : new RegExp(`Battery current ${value.replace('.', '\\.')} A`));
    states.push(await measurements(page));
  }
  await page.mouse.up(); await screenshot(page, `current-${width}-missing.png`);
  await screenshotCard(page, 'speed', `speed-${width}-missing.png`);
  await chart.press('Escape'); states.push(await measurements(page));
  await chart.press('Home'); await page.getByTestId('monitor-reference-set').click();
  const comparing: Measurements[] = [await measurements(page)];
  await chart.press('ArrowRight'); await expect(chart).toHaveAttribute('aria-valuetext', /Battery current 21\.8 A/);
  comparing.push(await measurements(page));
  await chart.press('ArrowRight'); await expect(chart).toHaveAttribute('aria-valuetext', /Battery current 100\.0 A/);
  comparing.push(await measurements(page)); await screenshotCard(page, 'batteryVoltageV', `voltage-${width}-comparison.png`);
  await importFixture(page, true); await chart.focus(); await chart.press('End');
  await expect(chart).toHaveAttribute('aria-valuetext', /10:00:22\.000 elapsed/);
  const longTimestamp = await measurements(page); await screenshot(page, `current-${width}-long-time.png`);
  const violations = { unstable: [...instability(states), ...instability(comparing)], clipped: [...states, ...comparing, longTimestamp].flatMap(value => value.clipped), overflow: [...states, ...comparing, longTimestamp].map(value => value.horizontalOverflow) };
  await writeFile(path.join(artifactDirectory, `bounds-${width}.json`), JSON.stringify({ width, phase, states, comparing, longTimestamp, violations }, null, 2));
  if (phase !== 'before') {
    expect(violations.unstable).toEqual([]); expect(violations.clipped).toEqual([]);
    expect(violations.overflow.every(value => value <= 1)).toBe(true);
    expect(states.every(value => value.headerHeight === states[0]!.headerHeight)).toBe(true);
    expect([...states, ...comparing, longTimestamp].every(value => value.charts.every(item => item.right <= width + 1))).toBe(true);
  }
});

test('intermediate widths keep Controller and GPS readouts stable through resize and clear selection', async ({ page }) => {
  test.setTimeout(90000);
  const preferences = defaultMonitorPreferences(); preferences.activeView = 'battery';
  preferences.views.battery.numbers = [];
  preferences.views.battery.charts = ['batteryCurrentA', 'controllerSpeedMps', 'speedMps'];
  await page.addInitScript(value => localStorage.setItem('power-log.monitor-preferences.v1', JSON.stringify(value)), preferences);
  await page.goto('/sessions'); await importFixture(page);
  const chart = page.getByTestId('monitor-chart-speed');
  const results = [];
  for (const width of [321, 337, 359, 375, 399, 414, 429, 431, 439, 441, 448, 480, 599, 759, 760, 761, 900, 1024, 1100, 1279, 1280, 1281, 1536, 1919]) {
    await page.setViewportSize({ width, height: 1000 });
    await chart.press('Escape');
    // RN onLayout and the bounded plot query settle before measuring a new container.
    await expect.poll(() => page.evaluate(() => innerWidth)).toBe(width);
    await chart.press('Home'); await expect(chart).toHaveAttribute('aria-valuetext', /Controller speed 8\.9 km\/h/);
    // The chart grid's column count follows the container's onLayout asynchronously; measure only a settled layout.
    await expect.poll(async () => {
      const first = await measurements(page); await page.waitForTimeout(60); const second = await measurements(page);
      return JSON.stringify(first.charts) === JSON.stringify(second.charts);
    }).toBe(true);
    const states = [await measurements(page)];
    if (width === 448) {
      const gps = (await page.getByTestId('monitor-readout-speedMps').boundingBox())!;
      const controller = (await page.getByTestId('monitor-readout-controllerSpeedMps').boundingBox())!;
      expect(controller.y).toBeCloseTo(gps.y, 1);
      expect(controller.width).toBeCloseTo(gps.width, 1);
    }
    for (const key of ['ArrowRight', 'ArrowRight', 'Escape'] as const) { await chart.press(key); states.push(await measurements(page)); }
    const violations = { unstable: instability(states), clipped: states.flatMap(value => value.clipped), overflow: states.map(value => value.horizontalOverflow) };
    results.push({ width, states, violations });
    expect(violations.unstable).toEqual([]); expect(violations.clipped).toEqual([]);
    expect(states.every(value => value.horizontalOverflow <= 1 && value.charts.every(item => item.right <= width + 1))).toBe(true);
    await expect(page.getByTestId('monitor-reference-set')).toHaveCount(0);
  }
  await mkdir(artifactDirectory, { recursive: true });
  await writeFile(path.join(artifactDirectory, 'bounds-intermediate-widths.json'), JSON.stringify(results, null, 2));
});

test('summary readings fit and retain height as values grow or remain unavailable', async ({ page }) => {
  const preferences = defaultMonitorPreferences(); preferences.activeView = 'battery';
  preferences.views.battery.numbers = ['controllerSpeedMps', 'motorCurrentA', 'heartRateBpm', 'motorTempC'];
  preferences.views.battery.charts = ['batteryCurrentA'];
  await page.addInitScript(value => localStorage.setItem('power-log.monitor-preferences.v1', JSON.stringify(value)), preferences);
  await page.goto('/sessions');
  const results = [];
  for (const width of [320, 390, 430, 440, 768, 1440, 1920]) {
    await page.setViewportSize({ width, height: 1000 });
    const states: Measurements[] = [];
    for (const value of [8.9, 21.8, 100]) {
      await importFixture(page, false, value);
      await expect(page.getByTestId('monitor-number-controllerSpeedMps')).toContainText(value.toFixed(1));
      await expect(page.getByTestId('monitor-number-heartRateBpm')).toContainText('—');
      states.push(await measurements(page));
    }
    expect(states.flatMap(value => value.clipped)).toEqual([]);
    for (const state of states) for (const number of state.numbers) {
      const initial = states[0]!.numbers.find(item => item.id === number.id)!;
      expect(number.height).toBe(initial.height); expect(number.width).toBe(initial.width);
      expect(number.x + number.width).toBeLessThanOrEqual(width + 1);
    }
    results.push({ width, states });
  }
  await mkdir(artifactDirectory, { recursive: true });
  await writeFile(path.join(artifactDirectory, 'bounds-summary-values.json'), JSON.stringify(results, null, 2));
});

test('non-voltage charts have no comparison control or empty comparison row', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 1000 });
  await page.goto('/sessions');
  const chooser = page.waitForEvent('filechooser'); await page.getByRole('button', { name: 'Open CSV', exact: true }).click();
  await (await chooser).setFiles({ name: 'Non-voltage.csv', mimeType: 'text/csv', buffer: Buffer.from(fixture()) });
  const chart = page.getByTestId('monitor-chart-power'); await expect(chart).toBeVisible();
  await expect(page.getByTestId('monitor-reference-set')).toHaveCount(0);
  await chart.focus(); await chart.press('Home'); await expect(page.getByTestId('monitor-reference-set')).toHaveCount(0);
  await screenshot(page, 'non-voltage-390.png');
  const comparisonRows = await page.getByTestId('monitor-charts').evaluate(element => [...element.querySelectorAll('button,[role="button"]')].filter(button => /Set A|Replace A/.test(button.textContent ?? '')).length);
  expect(comparisonRows).toBe(0);
});
