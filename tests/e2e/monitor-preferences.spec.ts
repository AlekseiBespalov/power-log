import { expect, test, type Locator, type Page } from '@playwright/test';
import { exportCsv } from '../helpers/export-csv';
import { syntheticSample } from '../fixtures/synthetic-sample';

// Synthetic controller observations only: absent HealthKit/GPS data must remain absent.
const epoch = Date.UTC(2026, 8, 8);
const samples = Array.from({ length: 2401 }, (_, index) => {
  const seconds = index / 2;
  const sample = syntheticSample(seconds, index, new Date(epoch + seconds * 1000).toISOString());
  const batteryVoltageV = seconds === 137.5 ? 44.12 : seconds === 813.5 ? 45.62 : 52 - seconds / 1000;
  return { ...sample, batteryVoltageV, motorInputPowerW: batteryVoltageV * sample.batteryCurrentA };
});
const csv = exportCsv(samples);

async function importRecording(page: Page, navigate = true, contents = csv) {
  if (navigate) await page.goto('/sessions');
  const chooser = page.waitForEvent('filechooser');
  await page.getByRole('button', { name: 'Open CSV', exact: true }).click();
  await (await chooser).setFiles({ name: 'monitor-synthetic.csv', mimeType: 'text/csv', buffer: Buffer.from(contents) });
  await expect(page.getByTestId('monitor-view-picker')).toBeVisible();
}

async function chooseView(page: Page, name: 'Ride' | 'Battery' | 'Temperature') {
  await page.getByTestId('monitor-view-picker').click();
  await page.getByRole('button', { name: new RegExp(`^${name}(?:\\s+✓)?$`) }).click();
  await expect(page.getByTestId('monitor-view-picker')).toHaveAccessibleName(`Choose monitoring view, ${name}`);
}

async function addMetric(page: Page, name: string) {
  await page.getByRole('textbox', { name: 'Search metrics', exact: true }).fill(name);
  const choice = page.getByRole('checkbox', { name, exact: true });
  if (await choice.getAttribute('aria-checked') !== 'true') await choice.click();
  await expect(choice).toBeChecked();
}

// CDP touch coordinates do not get Playwright's actionability retry. Wait for
// ancestor modal animations before sampling the exact handle position.
async function waitForAncestorAnimations(handle: Locator) {
  await handle.evaluate(async element => {
    const pending: Promise<unknown>[] = [];
    for (let node: Element | null = element; node; node = node.parentElement) {
      for (const animation of node.getAnimations()) {
        if ((animation.playState === 'running' || animation.pending) && animation.effect?.getComputedTiming().iterations !== Infinity) pending.push(animation.finished.catch(() => undefined));
      }
    }
    await Promise.all(pending);
  });
}

async function numberOrder(page: Page) {
  return page.getByTestId('monitor-numbers').locator('[data-testid^="monitor-number-"]').evaluateAll(elements =>
    elements.map(element => element.getAttribute('data-testid')!.replace('monitor-number-', '')));
}

async function dragMetric(page: Page, id: string, distance: number) {
  const handle = page.getByTestId(`monitor-drag-${id}`);
  await waitForAncestorAnimations(handle);
  await handle.click({ trial: true });
  await waitForAncestorAnimations(handle);
  const box = (await handle.boundingBox())!;
  const x = box.x + box.width / 2, y = box.y + box.height / 2;
  await page.mouse.move(x, y);
  await page.mouse.down();
  await page.mouse.move(x, y + distance);
  await page.mouse.up();
}

async function rangeOf(chart: Locator) {
  const text = (await chart.getAttribute('aria-valuetext')) ?? '';
  const match = /Visible ((?:\d+:)?\d{2}:\d{2}\.\d{3}) to ((?:\d+:)?\d{2}:\d{2}\.\d{3})/.exec(text);
  expect(match, text).not.toBeNull();
  const seconds = (value: string) => value.split(':').reduce((total, part) => total * 60 + Number(part), 0);
  return { start: seconds(match![1]!), end: seconds(match![2]!) };
}

async function expectRange(chart: Locator, start: number, end: number) {
  await expect.poll(async () => {
    const range = await rangeOf(chart);
    return Math.max(Math.abs(range.start - start), Math.abs(range.end - end));
  }).toBeLessThan(0.01);
}

async function chooseRange(page: Page, name: '10 min' | 'Whole ride') {
  await page.getByTestId('monitor-range-picker').click();
  await page.getByRole('button', { name: new RegExp(`^${name}(?:\\s+✓)?$`) }).click();
}

async function inspectCentered(chart: Locator, seconds: number) {
  await chart.scrollIntoViewIfNeeded();
  const range = await rangeOf(chart), span = range.end - range.start;
  const box = (await chart.boundingBox())!;
  const x = box.x + 8 + (box.width - 16) / 2, y = box.y + 70;
  // Pan the real chart to a narrow window, so half-second observations are distinguishable.
  await chart.evaluate((element, event) => element.dispatchEvent(new WheelEvent('wheel', {
    bubbles: true, cancelable: true, shiftKey: true, deltaX: event.deltaX, clientX: event.x, clientY: event.y,
  })), { deltaX: (seconds - (range.start + range.end) / 2) / span * (box.width - 16), x, y });
  await expectRange(chart, seconds - span / 2, seconds + span / 2);
  const settled = (await chart.boundingBox())!;
  await chart.click({ position: { x: 8 + (settled.width - 16) / 2, y: 70 } });
}

test('every preset can add temperatures, current and unavailable health or GPS metrics', async ({ page }, testInfo) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await importRecording(page);
  await page.getByTestId('monitor-edit').click();
  await expect(page.getByRole('heading', { name: 'Ride layout', exact: true })).toBeVisible();
  await page.screenshot({ animations: 'disabled', path: testInfo.outputPath('monitor-editor-390.png') });
  await addMetric(page, 'Motor temperature');
  await addMetric(page, 'Motor current');
  await page.getByRole('tab', { name: 'Graphs', exact: true }).click();
  for (const metric of ['Motor temperature', 'Motor current', 'Heart rate', 'GPS speed']) await addMetric(page, metric);
  await page.getByRole('button', { name: 'Done', exact: true }).click();
  for (const metric of ['motorTempC', 'motorCurrentA']) await expect(page.getByTestId(`monitor-number-${metric}`)).not.toContainText('—');
  for (const group of ['temperature', 'current', 'heartRateBpm', 'speed']) await expect(page.getByTestId(`monitor-chart-${group}`)).toBeVisible();
  for (const metric of ['heartRateBpm', 'speedMps']) await expect(page.getByTestId(`monitor-number-${metric}`)).toContainText('—');

  await chooseView(page, 'Temperature');
  await page.getByTestId('monitor-edit').click();
  await addMetric(page, 'Battery current');
  await page.getByRole('tab', { name: 'Graphs', exact: true }).click();
  await addMetric(page, 'Battery current');
  await page.getByRole('button', { name: 'Done', exact: true }).click();
  await expect(page.getByTestId('monitor-number-batteryCurrentA')).not.toContainText('—');
  await expect(page.getByTestId('monitor-chart-current')).toBeVisible();

  await chooseView(page, 'Battery');
  await page.getByTestId('monitor-edit').click();
  for (const metric of ['Controller temperature', 'Heart rate', 'GPS speed']) await addMetric(page, metric);
  await page.getByRole('button', { name: 'Done', exact: true }).click();
  await expect(page.getByTestId('monitor-number-controllerTempC')).not.toContainText('—');
  for (const metric of ['heartRateBpm', 'speedMps']) await expect(page.getByTestId(`monitor-number-${metric}`)).toContainText('—');
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
});

test('choosing a History layout leaves the Ride layout unchanged', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await importRecording(page);
  await chooseView(page, 'Temperature');
  await page.getByRole('link', { name: 'Ride', exact: true }).click();
  await expect(page.getByTestId('monitor-view-picker').filter({ visible: true })).toHaveAccessibleName(/^Choose monitoring view, Ride/);
  await page.getByRole('link', { name: 'History', exact: true }).click();
  await expect(page.getByTestId('monitor-view-picker').filter({ visible: true })).toHaveAccessibleName(/^Choose monitoring view, Temperature/);
});

test('reordered and removed numbers and graphs survive reload independently per view', async ({ page }) => {
  await importRecording(page);
  await page.getByTestId('monitor-edit').click();
  await page.getByRole('button', { name: 'Remove Cadence from numbers', exact: true }).click();
  await dragMetric(page, 'speedMps', -52);
  await addMetric(page, 'Motor temperature');
  await page.getByRole('tab', { name: 'Graphs', exact: true }).click();
  await page.getByRole('button', { name: 'Remove Cadence from graphs', exact: true }).click();
  await dragMetric(page, 'motorInputPowerW', -52);
  await page.getByRole('button', { name: 'Done', exact: true }).click();
  const rideNumbers = ['humanPowerW', 'speedMps', 'heartRateBpm', 'motorTempC'];
  await expect.poll(() => numberOrder(page)).toEqual(rideNumbers);
  await expect(page.getByTestId('monitor-chart-cadenceRpm')).toHaveCount(0);

  await chooseView(page, 'Battery');
  await expect.poll(() => numberOrder(page)).toEqual(['batteryVoltageV', 'batteryCurrentA', 'motorInputPowerW']);
  await page.getByTestId('monitor-edit').click();
  await page.getByRole('button', { name: 'Remove Motor input power from numbers', exact: true }).click();
  await page.getByRole('button', { name: 'Done', exact: true }).click();
  await page.reload();
  await importRecording(page, false);
  await expect(page.getByTestId('monitor-view-picker')).toHaveAccessibleName('Choose monitoring view, Battery');
  await expect.poll(() => numberOrder(page)).toEqual(['batteryVoltageV', 'batteryCurrentA']);
  await expect(page.getByTestId('monitor-chart-power')).toBeVisible();

  await chooseView(page, 'Ride');
  await expect.poll(() => numberOrder(page)).toEqual(rideNumbers);
  await expect(page.getByTestId('monitor-chart-cadenceRpm')).toHaveCount(0);
  await page.getByTestId('monitor-edit').click();
  await page.getByRole('tab', { name: 'Graphs', exact: true }).click();
  await expect(page.getByTestId('monitor-drag-motorInputPowerW')).toHaveAttribute('aria-valuenow', '1');
  await expect(page.getByTestId('monitor-drag-humanPowerW')).toHaveAttribute('aria-valuenow', '2');
  await page.getByRole('button', { name: 'Done', exact: true }).click();
  await chooseView(page, 'Temperature');
  await expect.poll(() => numberOrder(page)).toEqual(['motorTempC', 'controllerTempC', 'motorInputPowerW']);
});

test('dragging scrolls through a long selection and keyboard reordering remains available', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await importRecording(page);
  await page.getByTestId('monitor-edit').click();
  for (const choice of await page.getByRole('checkbox').all()) if (await choice.getAttribute('aria-checked') !== 'true') await choice.click();
  const handle = page.getByTestId('monitor-drag-humanPowerW');
  await handle.scrollIntoViewIfNeeded();
  const box = (await handle.boundingBox())!, viewport = (await page.getByTestId('monitor-editor-scroll').boundingBox())!;
  const x = box.x + box.width / 2;
  await page.mouse.move(x, box.y + box.height / 2);
  await page.mouse.down();
  await page.mouse.move(x, viewport.y + viewport.height - 8, { steps: 20 });
  await expect(handle).toHaveAttribute('aria-valuenow', (await handle.getAttribute('aria-valuemax'))!, { timeout: 10000 });
  await page.mouse.up();
  await handle.focus();
  await page.keyboard.press('Home');
  await expect(handle).toHaveAttribute('aria-valuenow', '1');
  await page.keyboard.press('ArrowDown');
  await expect(handle).toHaveAttribute('aria-valuenow', '2');
  await page.getByRole('button', { name: 'Done', exact: true }).click();
  await expect.poll(async () => (await numberOrder(page)).slice(0, 2)).toEqual(['cadenceRpm', 'humanPowerW']);
});

test.describe('touch editor', () => {
test.use({ hasTouch: true });
test('the lifted row follows the finger on repeated drags of the same and different metrics', async ({ page }) => {
  // This isolates row tracking from edge scrolling, which the other tests cover
  // at phone height. All four rows fit without an out-of-band DOM scroll reset.
  await page.setViewportSize({ width: 390, height: 1200 });
  await importRecording(page);
  await page.getByTestId('monitor-edit').click();
  const touch = await page.context().newCDPSession(page);
  for (const [id, distance, expectedPosition] of [['cadenceRpm', 52, 3], ['cadenceRpm', -52, 2], ['heartRateBpm', -52, 2], ['humanPowerW', 104, 3]] as const) {
    const handle = page.getByTestId(`monitor-drag-${id}`);
    await waitForAncestorAnimations(handle);
    await handle.click({ trial: true });
    await waitForAncestorAnimations(handle);
    await expect(handle).toHaveCSS('cursor', 'grab');
    await expect.poll(() => page.getByTestId('monitor-editor-scroll').evaluate(element => element.scrollTop)).toBe(0);
    const box = (await handle.boundingBox())!;
    const x = box.x + box.width / 2, startY = box.y + box.height / 2;
    await touch.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x, y: startY }] });
    for (const fraction of [0.5, 0.8, 1]) {
      const y = startY + distance * fraction;
      await touch.send('Input.dispatchTouchEvent', { type: 'touchMove', touchPoints: [{ x, y }] });
      await expect.poll(async () => {
        const current = (await handle.boundingBox())!;
        return Math.abs(current.y + current.height / 2 - y);
      }, { message: `${id} follows the finger at ${distance * fraction}px on this drag` }).toBeLessThan(3);
    }
    await touch.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
    // A CDP dispatch acknowledgement precedes React's release commit.
    await expect(handle).toHaveCSS('cursor', 'grab');
    await expect(handle).toHaveAttribute('aria-valuenow', String(expectedPosition));
  }
  await page.getByRole('button', { name: 'Done', exact: true }).click();
  await expect.poll(() => numberOrder(page)).toEqual(['heartRateBpm', 'cadenceRpm', 'humanPowerW', 'speedMps']);
});
test('touch handles reorder, cancelled drags restore order, and labels still scroll', async ({ page }, testInfo) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await importRecording(page);
  await page.getByTestId('monitor-edit').click();
  const touch = await page.context().newCDPSession(page);
  const handle = page.getByTestId('monitor-drag-heartRateBpm');
  await waitForAncestorAnimations(handle);
  await handle.click({ trial: true });
  await waitForAncestorAnimations(handle);
  const box = (await handle.boundingBox())!;
  const x = box.x + box.width / 2;
  let y = box.y + box.height / 2;
  const start = () => touch.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x, y }] });
  const move = async () => {
    for (let step = 1; step <= 8; step++) await touch.send('Input.dispatchTouchEvent', { type: 'touchMove', touchPoints: [{ x, y: y - 104 * step / 8 }] });
  };
  await start(); await move();
  await expect(handle).toHaveAttribute('aria-valuenow', '1');
  await touch.send('Input.dispatchTouchEvent', { type: 'touchCancel', touchPoints: [] });
  await expect(handle).toHaveAttribute('aria-valuenow', '3');
  // Cancellation restores order while retaining the scroll position reached during the drag.
  await waitForAncestorAnimations(handle);
  await handle.click({ trial: true });
  await waitForAncestorAnimations(handle);
  y = (await handle.boundingBox())!.y + box.height / 2;
  await start(); await move();
  await touch.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
  await expect(handle).toHaveAttribute('aria-valuenow', '1');
  await page.screenshot({ path: testInfo.outputPath('monitor-drag-handles-390.png') });
  const viewport = (await page.getByTestId('monitor-editor-scroll').boundingBox())!;
  const initialScroll = await page.getByTestId('monitor-editor-scroll').evaluate(element => element.scrollTop);
  const scrollY = viewport.y + Math.min(240, viewport.height - 40);
  await touch.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x: 100, y: scrollY }] });
  for (let step = 1; step <= 8; step++) await touch.send('Input.dispatchTouchEvent', { type: 'touchMove', touchPoints: [{ x: 100, y: scrollY - 120 * step / 8 }] });
  await touch.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
  await expect.poll(() => page.getByTestId('monitor-editor-scroll').evaluate(element => element.scrollTop)).toBeGreaterThan(initialScroll + 30);
  await page.getByRole('button', { name: 'Done', exact: true }).click();
  await expect.poll(() => numberOrder(page)).toEqual(['heartRateBpm', 'humanPowerW', 'cadenceRpm', 'speedMps']);
});
});

test('ten-minute and whole-ride ranges retain older samples and use interval-specific voltage minima', async ({ page }, testInfo) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await importRecording(page);
  await chooseView(page, 'Battery');
  const chart = page.getByTestId('monitor-chart-batteryVoltageV');
  const readout = page.getByTestId('monitor-readout-batteryVoltageV');
  await expectRange(chart, 0, 1200);
  await expect(readout).toContainText('Min 44.12 V · 02:17.500');
  await chooseRange(page, '10 min');
  await expectRange(chart, 600, 1200);
  await expect(readout).toContainText('Min 45.62 V · 13:33.500');
  await chart.scrollIntoViewIfNeeded();
  await page.screenshot({ animations: 'disabled', path: testInfo.outputPath('monitor-battery-390.png') });
  await chooseRange(page, 'Whole ride');
  await expectRange(chart, 0, 1200);
  await expect(readout).toContainText('Min 44.12 V · 02:17.500');
});

test('battery comparison resolves original observations outside the current window', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await importRecording(page);
  await chooseView(page, 'Battery');
  const chart = page.getByTestId('monitor-chart-batteryVoltageV');
  await expectRange(chart, 0, 1200);
  // Narrow from twenty minutes to 75 seconds; these interior samples are absent from the overview vertices.
  for (let step = 0; step < 4; step++) await page.getByTestId('monitor-zoom-in').click();
  await expectRange(chart, 562.5, 637.5);
  await inspectCentered(chart, 123.5);
  await expect(chart).toHaveAttribute('aria-valuetext', /Battery voltage 51\.88 V, 02:03\.500 elapsed, 2026-09-08T00:02:03\.500Z/);
  await page.getByTestId('monitor-reference-set').click();
  await inspectCentered(chart, 876.5);
  await expect(chart).toHaveAttribute('aria-valuetext', /Battery voltage 51\.12 V, 14:36\.500 elapsed, 2026-09-08T00:14:36\.500Z/);
  const comparison = page.getByTestId('monitor-compare-batteryVoltageV');
  await expect(comparison).toContainText('Δ -0.75 V');
  await expect(comparison).toContainText('A 51.88 V · 02:03.500');
  await expect(comparison).toContainText('B 51.12 V · 14:36.500');
  await page.getByTestId('monitor-reference-clear').click();
  await expect(comparison).toHaveCount(0);
});

test('chart navigation preserves layout while exact viewport statistics catch up', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await importRecording(page);
  await chooseView(page, 'Battery');
  const chart = page.getByTestId('monitor-chart-batteryVoltageV');
  const readout = page.getByTestId('monitor-readout-batteryVoltageV');
  await expect(readout).toContainText('Min 44.12 V');
  await chart.scrollIntoViewIfNeeded();
  const before = (await chart.boundingBox())!;
  await page.clock.install();
  await chart.dispatchEvent('wheel', { ctrlKey: true, deltaY: -40, clientX: before.x + before.width / 2 });
  await page.clock.runFor(32);
  await expect(readout).toContainText('Min 44.12 V');
  await expect(readout).toContainText('Previous range 00:00–20:00');
  expect((await chart.boundingBox())!.y).toBeCloseTo(before.y, 1);
  await expect(page.getByText('Updating charts…', { exact: true })).toHaveCount(0);
  await page.clock.runFor(350);
  await expect(readout).toContainText(/Min \d/);
  expect((await chart.boundingBox())!.y).toBeCloseTo(before.y, 1);
  await expect(chart).not.toHaveAttribute('aria-valuetext', /Visible 00:00\.000 to 20:00\.000/);
});

test('re-importing a changed CSV with the same filename replaces the monitor source', async ({ page }) => {
  await importRecording(page);
  const number = page.getByTestId('monitor-number-humanPowerW');
  await expect(number).not.toContainText('—');
  await expect(number).not.toContainText('333');
  const replacement = exportCsv(samples.map(sample => ({ ...sample, humanPowerW: 333 })));
  await importRecording(page, false, replacement);
  await expect(number).toContainText('333');
  const chart = page.getByTestId('monitor-chart-power');
  await chart.focus();
  await page.keyboard.press('Home');
  await expect(page.getByTestId('monitor-readout-humanPowerW')).toContainText('333');
  await expect(chart).toHaveAttribute('aria-valuetext', /2026-09-08T00:00:00\.000Z/);
});
