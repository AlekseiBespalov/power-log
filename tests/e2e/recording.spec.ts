import { expect, test, type CDPSession, type Locator, type Page } from '@playwright/test';
import { exportCsv } from '../helpers/export-csv';
import { syntheticSample } from '../fixtures/synthetic-sample';

const samples = [0, 1, 2, 8, 9, 10].map((time, i) => ({ ...syntheticSample(time, i, `2026-09-08T00:00:${String(time).padStart(2, '0')}.000Z`), humanPowerW: 100 + i * 20, motorInputPowerW: 200 + i * 40, batteryVoltageV: 50, batteryCurrentA: (200 + i * 40) / 50, cadenceRpm: 60 + i * 2 }));
const csv = exportCsv(samples);
async function importRecording(page: Page) {
  await page.goto('/sessions');
  const chooserEvent = page.waitForEvent('filechooser');
  await page.getByRole('button', { name: 'Open CSV', exact: true }).click();
  await (await chooserEvent).setFiles({ name: 'example.csv', mimeType: 'text/csv', buffer: Buffer.from(csv) });
}

type Touch = { id: number; x: number; y: number };
const touch = (client: CDPSession, type: 'touchStart' | 'touchMove' | 'touchEnd', touchPoints: readonly Touch[]) =>
  client.send('Input.dispatchTouchEvent', { type, touchPoints: [...touchPoints] });
const pairAt = (center: number, halfSpan: number, y: number): Touch[] => [{ id: 1, x: center - halfSpan, y }, { id: 2, x: center + halfSpan, y }];
type PointerLog = { type: string; id: number; primary: boolean };
async function observePointerLifecycle(chart: Locator) {
  await chart.evaluate(element => {
    const events: PointerLog[] = [];
    for (const type of ['pointerdown', 'pointerup', 'pointercancel']) element.addEventListener(type, event => {
      const pointer = event as PointerEvent;
      events.push({ type, id: pointer.pointerId, primary: pointer.isPrimary });
      element.setAttribute('data-test-pointer-log', JSON.stringify(events));
    });
  });
}
const pointerLifecycle = (chart: Locator) => chart.evaluate(element => JSON.parse(element.getAttribute('data-test-pointer-log') ?? '[]') as PointerLog[]);
async function visibleRange(chart: Locator) {
  const text = (await chart.getAttribute('aria-valuetext'))!;
  const match = /Visible (\d{2}:\d{2}\.\d{3}) to (\d{2}:\d{2}\.\d{3})/.exec(text);
  expect(match, text).not.toBeNull();
  const seconds = (value: string) => value.split(':').reduce((total, part) => total * 60 + Number(part), 0);
  return { start: seconds(match![1]!), end: seconds(match![2]!) };
}
async function expectRange(chart: Locator, start: number, end: number) {
  await expect.poll(async () => {
    const range = await visibleRange(chart);
    return Math.max(Math.abs(range.start - start), Math.abs(range.end - end));
  }).toBeLessThan(0.04);
}
async function waitForFullscreen(chart: Locator) {
  await expect(chart).toBeVisible();
  await expect.poll(() => chart.evaluate(element => {
    for (let parent: Element | null = element; parent; parent = parent.parentElement) if (Number(getComputedStyle(parent).opacity) < 0.999) return false;
    return true;
  })).toBe(true);
}
async function expectSeparatePlots(page: Page, suffix = '') {
  const power = page.getByTestId(`monitor-chart-power${suffix}`);
  const cadence = page.getByTestId(`monitor-chart-cadenceRpm${suffix}`);
  await expect.poll(async () => {
    const [first, second] = await Promise.all([power.boundingBox(), cadence.boundingBox()]);
    return first && second ? second.y - first.y - first.height : -Infinity;
  }, { message: 'Power and cadence must occupy separate vertical lanes' }).toBeGreaterThan(10);
}

test('Ride keeps connection setup in a sheet and the chart directly below compact controls', async ({ page }, testInfo) => {
  const consoleErrors: string[] = []; page.on('console', message => { if (message.type() === 'error') consoleErrors.push(message.text()); });
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/');
  await expect(page.getByRole('link', { name: 'Ride', exact: true })).toBeVisible();
  await expect(page.getByRole('link', { name: 'History', exact: true })).toBeVisible();
  await expect(page.getByTestId('ride-setup')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Find bike', exact: true })).toHaveCount(0);
  const status = (await page.getByTestId('ride-status').boundingBox())!;
  expect(status.height).toBeLessThanOrEqual(48);
  const bar = (await page.getByTestId('ride-controls').boundingBox())!;
  expect(Math.round(bar.y + bar.height)).toBeGreaterThanOrEqual(844 - 16);
  await page.mouse.wheel(0, 800);
  const scrolledBar = (await page.getByTestId('ride-controls').boundingBox())!;
  expect(Math.round(scrolledBar.y + scrolledBar.height)).toBeGreaterThanOrEqual(844 - 16);
  expect((await page.getByTestId('monitor-view-picker').boundingBox())!.y).toBeLessThan(180);
  await page.screenshot({ animations: 'disabled', path: testInfo.outputPath('compact-ride-390.png') });
  await page.getByTestId('ride-setup').click();
  await expect(page.getByRole('heading', { name: 'Ride setup', exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Find bike', exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: /Hz$/ })).toHaveCount(0);
  await page.screenshot({ animations: 'disabled', path: testInfo.outputPath('ride-setup-390.png') });
  await page.getByRole('button', { name: 'Done', exact: true }).click();
  await expect(page.getByTestId('ride-setup-sheet')).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Find bike', exact: true })).toHaveCount(0);
  await expect(page.getByText(/Motor input is battery voltage/)).toHaveCount(0);
  await expect(page.getByRole('link', { name: 'Workout', exact: true })).toHaveCount(0);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.goto('/workout');
  await expect(page).toHaveURL(/\/$/);
  await expect(page.getByTestId('ride-setup')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Find bike', exact: true })).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Set up ride', exact: true })).toBeVisible();
  expect(consoleErrors).toEqual([]);
});

test('CSV review retains data and can export after the redesign', async ({ page }) => {
  const errors: string[] = []; page.on('pageerror', error => errors.push(error.message));
  await importRecording(page);
  await expect(page.getByText(/Imported/).first()).toBeVisible();
  const downloadEvent = page.waitForEvent('download');
  await page.getByRole('button', { name: 'Export CSV', exact: true }).click();
  const download = await downloadEvent;
  const { readFile } = await import('node:fs/promises');
  expect(await readFile((await download.path())!, 'utf8')).toBe(csv);
  expect(errors).toEqual([]);
});

test('charts inspect original values, synchronize time and preserve gaps', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await importRecording(page);
  const chart = page.getByTestId('monitor-chart-power');
  await chart.scrollIntoViewIfNeeded();
  const bounds = await chart.boundingBox();
  expect(bounds).not.toBeNull();
  const x = (seconds: number) => bounds!.x + 8 + seconds / 10 * (bounds!.width - 16);
  const y = bounds!.y + bounds!.height - 65;
  await page.mouse.click(x(1), y);
  await expect(page.getByTestId('monitor-readout-humanPowerW')).toContainText('120');
  await expect(page.getByTestId('monitor-readout-cadenceRpm')).toContainText('62');
  await page.mouse.move(x(1), y);
  await page.mouse.down();
  await page.mouse.move(x(9), y, { steps: 10 });
  await page.mouse.up();
  await expect(page.getByTestId('monitor-readout-humanPowerW')).toContainText('180');
  await page.mouse.click(x(5), y);
  await expect(chart).toHaveAttribute('aria-valuetext', /No sample selected/);
  await page.mouse.click(x(5), y);
  await expect(page.getByTestId('monitor-readout-humanPowerW')).toContainText('No sample');
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
});

test('one-finger inspection works when zoomed and fullscreen retains selection and viewport', async ({ page }, testInfo) => {
  await page.setViewportSize({ width: 390, height: 844 });
  const errors: string[] = []; page.on('pageerror', error => errors.push(error.message));
  await importRecording(page);
  const chart = page.getByTestId('monitor-chart-power');
  await chart.scrollIntoViewIfNeeded();
  await expectSeparatePlots(page);
  await page.screenshot({ animations: 'disabled', path: testInfo.outputPath('chart-inline-390.png') });
  await page.getByTestId('monitor-charts').screenshot({ animations: 'disabled', path: testInfo.outputPath('chart-group-inline-390.png') });
  await page.getByRole('button', { name: 'Zoom in charts', exact: true }).click();
  await expect(chart).toHaveAttribute('aria-valuetext', /Visible 00:02.500 to 00:07.500/);
  // The visible window is a real gap; keyboard navigation finds the next observation.
  await chart.focus(); await page.keyboard.press('ArrowRight');
  await expectRange(chart, 5, 10);
  const before = await visibleRange(chart);
  let box = (await chart.boundingBox())!;
  const x = (seconds: number) => box.x + 8 + (seconds - 5) / 5 * (box.width - 16);
  await page.mouse.move(x(8), box.y + 60); await page.mouse.down();
  await page.mouse.move(x(9), box.y + 60, { steps: 8 }); await page.mouse.up();
  await expect(chart).toHaveAttribute('aria-valuetext', /2026-09-08T00:00:09.000Z/);
  await expect(page.getByTestId('monitor-readout-humanPowerW')).toContainText('180');
  expect(await visibleRange(chart)).toEqual(before);
  await page.screenshot({ animations: 'disabled', path: testInfo.outputPath('chart-zoomed-390.png') });
  const selected = await chart.getAttribute('aria-valuetext');
  await page.getByRole('button', { name: 'Expand charts', exact: true }).click();
  const full = page.getByTestId('monitor-chart-power-fullscreen');
  await waitForFullscreen(full);
  await expectSeparatePlots(page, '-fullscreen');
  await expect(full).toHaveAttribute('aria-valuetext', selected!);
  await page.screenshot({ animations: 'disabled', path: testInfo.outputPath('chart-fullscreen-390.png') });
  await page.getByRole('button', { name: 'Close fullscreen charts', exact: true }).click();
  await expect(chart).toHaveAttribute('aria-valuetext', selected!);
  box = (await chart.boundingBox())!;
  await page.mouse.click(x(9), box.y + 60);
  await expect(chart).toHaveAttribute('aria-valuetext', /No sample selected/);
  await page.mouse.click(x(9), box.y + 60);
  await expect(chart).toHaveAttribute('aria-valuetext', /2026-09-08T00:00:09.000Z/);
  expect(await visibleRange(chart)).toEqual(before);
  await page.getByRole('button', { name: 'Expand charts', exact: true }).click();
  await waitForFullscreen(full);
  await full.focus(); await page.keyboard.press('0');
  await expect(full).toHaveAttribute('aria-valuetext', /Visible 00:00.000 to 00:10.000/);
  await page.keyboard.press('Home');
  await expect(full).toHaveAttribute('aria-valuetext', /2026-09-08T00:00:00.000Z/);
  await page.keyboard.press('Escape');
  await expect(full).toHaveAttribute('aria-valuetext', /No sample selected/);
  await page.getByRole('button', { name: 'Close fullscreen charts', exact: true }).click();
  await expect(chart).toBeVisible();
  await expect(chart).toHaveAttribute('aria-valuetext', /Visible 00:00.000 to 00:10.000/);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  expect(errors).toEqual([]);
});

test('inline two-finger navigation freezes the remaining finger until a fresh inspection gesture', async ({ browser }) => {
  const context = await browser.newContext({ viewport: { width: 390, height: 844 }, hasTouch: true, isMobile: true });
  const page = await context.newPage();
  await importRecording(page);
  const chart = page.getByTestId('monitor-chart-power');
  await chart.scrollIntoViewIfNeeded();
  let box = (await chart.boundingBox())!;
  await chart.evaluate((element, args) => element.dispatchEvent(new WheelEvent('wheel', { bubbles: true, cancelable: true, ctrlKey: true, deltaY: -80, clientX: args.x, clientY: args.y })), { x: box.x + box.width / 2, y: box.y + 60 });
  await expect(page.getByRole('button', { name: 'Reset chart zoom', exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Reset chart zoom', exact: true }).click();
  await expectRange(chart, 0, 10);
  // Reset can move the page's scroll anchor. CDP uses viewport coordinates and does not wait for layout.
  await chart.scrollIntoViewIfNeeded();
  await observePointerLifecycle(chart);
  const client = await context.newCDPSession(page);
  box = (await chart.boundingBox())!;
  const plotWidth = box.width - 16;
  const middle = box.x + 8 + plotWidth / 2; const y = box.y + 70;
  await expectSeparatePlots(page);
  expect(await chart.evaluate((element, positions) => positions.every(position => element.contains(document.elementFromPoint(position.x, position.y))), pairAt(middle, 20, y))).toBe(true);
  await touch(client, 'touchStart', pairAt(middle, 20, y));
  for (const distance of [25, 30, 35, 40]) await touch(client, 'touchMove', pairAt(middle, distance, y));
  await expectRange(chart, 2.5, 7.5);
  const twoFingerView = await chart.getAttribute('aria-valuetext');
  // CDP ends the supplied pointer; omitting it from touchMove does not release it.
  await touch(client, 'touchEnd', [{ id: 2, x: middle + 40, y }]);
  for (const distance of [55, 70, 85]) await touch(client, 'touchMove', [{ id: 1, x: middle - distance, y }]);
  const lifecycle = await pointerLifecycle(chart);
  const down = lifecycle.filter(event => event.type === 'pointerdown');
  expect(down).toHaveLength(2);
  expect(lifecycle.filter(event => event.type === 'pointerup')).toEqual([{ ...down[1]!, type: 'pointerup' }]);
  expect(lifecycle.filter(event => event.type === 'pointercancel')).toEqual([]);
  await expect(chart).toHaveAttribute('aria-valuetext', twoFingerView!);
  await touch(client, 'touchEnd', []);
  await expect(chart).toHaveAttribute('aria-valuetext', twoFingerView!);
  // A fresh one-finger gesture inspects at this zoom without moving the viewport.
  await touch(client, 'touchStart', [{ id: 3, x: middle, y }]);
  await touch(client, 'touchMove', [{ id: 3, x: middle + 20, y }]);
  await touch(client, 'touchEnd', []);
  await expect(chart).not.toHaveAttribute('aria-valuetext', /No sample selected/);
  await expectRange(chart, 2.5, 7.5);
  await touch(client, 'touchStart', [{ id: 3, x: middle + 20, y }]);
  await touch(client, 'touchEnd', []);
  await expect(chart).toHaveAttribute('aria-valuetext', /No sample selected/);
  await context.close();
});

test('fullscreen touch pinch and constant-span pan preserve geometry through pointer changes', async ({ browser }, testInfo) => {
  const context = await browser.newContext({ viewport: { width: 390, height: 844 }, hasTouch: true, isMobile: true });
  const page = await context.newPage();
  const errors: string[] = []; page.on('pageerror', error => errors.push(error.message));
  await importRecording(page);
  await page.getByRole('button', { name: 'Expand charts', exact: true }).click();
  const chart = page.getByTestId('monitor-chart-power-fullscreen');
  await waitForFullscreen(chart);
  const box = (await chart.boundingBox())!;
  const plotWidth = box.width - 16;
  const middle = box.x + 8 + plotWidth / 2;
  const y = box.y + Math.min(100, box.height / 2);
  const client = await context.newCDPSession(page);

  // Actual two-finger pinch, without pressing either zoom button.
  await touch(client, 'touchStart', pairAt(middle, 20, y));
  for (const halfSpan of [25, 30, 35, 40]) await touch(client, 'touchMove', pairAt(middle, halfSpan, y));
  await touch(client, 'touchEnd', []);
  await expectRange(chart, 2.5, 7.5);

  // Moving both fingers left by 20% shifts time by one second; span stays five.
  await touch(client, 'touchStart', pairAt(middle, 25, y));
  for (const fraction of [0.05, 0.1, 0.15, 0.2]) await touch(client, 'touchMove', pairAt(middle - plotWidth * fraction, 25, y));
  await expectRange(chart, 3.5, 8.5);
  await touch(client, 'touchEnd', []);
  await expect(chart).toHaveAttribute('aria-valuetext', /No sample selected/);
  await page.screenshot({ animations: 'disabled', path: testInfo.outputPath('chart-fullscreen-two-finger-pan-390.png') });

  // Simultaneous translation and zoom: time 6 stays under the moving centroid.
  await touch(client, 'touchStart', pairAt(middle, 25, y));
  for (const progress of [0.25, 0.5, 0.75, 1]) {
    await touch(client, 'touchMove', pairAt(middle - plotWidth * 0.2 * progress, 25 + 12.5 * progress, y));
  }
  await expectRange(chart, 5, 5 + 5 / 1.5);
  const transformed = await chart.getAttribute('aria-valuetext');
  const finalPair = pairAt(middle - plotWidth * 0.2, 37.5, y);
  await touch(client, 'touchEnd', [finalPair[1]!]);
  for (const offset of [10, 25, 40]) await touch(client, 'touchMove', [{ ...finalPair[0]!, x: finalPair[0]!.x + offset }]);
  await expect(chart).toHaveAttribute('aria-valuetext', transformed!);
  await touch(client, 'touchEnd', []);
  await expect(chart).toHaveAttribute('aria-valuetext', transformed!);

  // A third finger invalidates the pair; it must not produce a viewport jump.
  await touch(client, 'touchStart', pairAt(middle, 25, y));
  await touch(client, 'touchStart', [...pairAt(middle, 25, y), { id: 3, x: middle, y: y + 50 }]);
  await touch(client, 'touchMove', [...pairAt(middle - 30, 35, y), { id: 3, x: middle - 30, y: y + 50 }]);
  await expect(chart).toHaveAttribute('aria-valuetext', transformed!);
  await touch(client, 'touchEnd', []);

  // Fresh one-finger inspection at zoomed time 8 gives the original 160 W sample.
  const range = await visibleRange(chart);
  const sampleX = box.x + 8 + (8 - range.start) / (range.end - range.start) * plotWidth;
  await touch(client, 'touchStart', [{ id: 4, x: sampleX - 20, y }]);
  await touch(client, 'touchMove', [{ id: 4, x: sampleX, y }]);
  await touch(client, 'touchEnd', []);
  await expect(chart).toHaveAttribute('aria-valuetext', /2026-09-08T00:00:08.000Z/);
  await expect(page.getByTestId('monitor-readout-humanPowerW-fullscreen')).toContainText('160');
  expect(await visibleRange(chart)).toEqual(range);
  const inspected = await chart.getAttribute('aria-valuetext');
  await page.screenshot({ animations: 'disabled', path: testInfo.outputPath('chart-fullscreen-two-finger-inspected-390.png') });
  await page.getByRole('button', { name: 'Close fullscreen charts', exact: true }).click();
  await expect(page.getByTestId('monitor-chart-power')).toHaveAttribute('aria-valuetext', inspected!);
  await expect(page.getByTestId('monitor-readout-cadenceRpm')).toContainText('66');
  expect(errors).toEqual([]);
  await context.close();
});

test('touch scrubbing inspects values while vertical gestures scroll the page', async ({ browser }) => {
  const context = await browser.newContext({ viewport: { width: 390, height: 640 }, hasTouch: true, isMobile: true });
  const page = await context.newPage();
  await importRecording(page);
  const chart = page.getByTestId('monitor-chart-power');
  await chart.scrollIntoViewIfNeeded();
  let box = (await chart.boundingBox())!;
  const client = await context.newCDPSession(page);
  const touch = (type: 'touchStart' | 'touchMove' | 'touchEnd', x?: number, y?: number) => client.send('Input.dispatchTouchEvent', { type, touchPoints: type === 'touchEnd' ? [] : [{ x: x!, y: y! }] });
  const x = (seconds: number) => box.x + 8 + seconds / 10 * (box.width - 16);
  await touch('touchStart', x(1), box.y + 80);
  await touch('touchMove', x(2), box.y + 80);
  await touch('touchEnd');
  await expect(page.getByTestId('monitor-readout-humanPowerW')).toContainText('140');
  const readout = await page.getByTestId('monitor-readout-humanPowerW').textContent();
  const scrollPosition = () => page.evaluate(() => [...document.querySelectorAll('*')].reduce((total, element) => total + element.scrollTop, 0));
  const before = await scrollPosition();
  box = (await chart.boundingBox())!;
  expect(await chart.evaluate(element => getComputedStyle(element).touchAction)).toBe('pan-y');
  const room = await chart.evaluate(element => {
    for (let parent = element.parentElement; parent; parent = parent.parentElement) {
      if (/(auto|scroll)/.test(getComputedStyle(parent).overflowY) && parent.scrollHeight > parent.clientHeight + 1) {
        return { up: parent.scrollTop, down: parent.scrollHeight - parent.clientHeight - parent.scrollTop };
      }
    }
    return null;
  });
  expect(room, 'The fixture must have a scrollable ancestor with room for the gesture').not.toBeNull();
  expect(Math.max(room!.up, room!.down)).toBeGreaterThan(96);
  const direction = room!.down >= room!.up ? -1 : 1;
  const initialY = box.y + (direction < 0 ? 140 : 40);
  await touch('touchStart', box.x + box.width / 2, initialY);
  // Send a real-duration drag; back-to-back CDP moves can be coalesced before scrolling starts.
  for (let step = 1; step <= 8; step++) {
    await page.waitForTimeout(16);
    await touch('touchMove', box.x + box.width / 2, initialY + direction * step * 12);
  }
  await touch('touchEnd');
  await expect.poll(scrollPosition).not.toBe(before);
  await expect(page.getByTestId('monitor-readout-humanPowerW')).toHaveText(readout!);
  await context.close();
});


test('retained Ride screen suspends its monitor clocks in History and resumes one consumer', async ({ page }) => {
  await page.addInitScript(() => {
    const originalSet = window.setInterval, originalClear = window.clearInterval;
    const timers = new Map<number, number>(); const fired: Record<number, number> = {};
    Object.assign(window, { monitorClockProbe: { active: () => [...timers.values()], fired } });
    window.setInterval = ((callback: TimerHandler, interval?: number, ...args: unknown[]) => {
      const id = originalSet(() => { fired[interval ?? 0] = (fired[interval ?? 0] ?? 0) + 1; if (typeof callback === 'function') callback(...args); }, interval);
      timers.set(id, interval ?? 0); return id;
    }) as typeof window.setInterval;
    window.clearInterval = ((id?: number) => { if (id !== undefined) timers.delete(id); originalClear(id); }) as typeof window.clearInterval;
  });
  const clocks = () => page.evaluate(() => {
    const probe = (window as unknown as { monitorClockProbe: { active: () => number[]; fired: Record<number, number> } }).monitorClockProbe;
    return { read: probe.active().filter(value => value === 750).length, display: probe.active().filter(value => value === 250).length, fired: probe.fired[750] ?? 0 };
  });
  await page.goto('/');
  await expect.poll(async () => (await clocks()).read).toBe(1);
  await page.evaluate(() => { Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'hidden' }); document.dispatchEvent(new Event('visibilitychange')); });
  await expect.poll(async () => (await clocks()).read).toBe(0);
  await expect.poll(async () => (await clocks()).display).toBe(0);
  await page.evaluate(() => { Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'visible' }); document.dispatchEvent(new Event('visibilitychange')); });
  await expect.poll(async () => (await clocks()).read).toBe(1);
  await expect.poll(async () => (await clocks()).display).toBe(1);
  await page.getByTestId('monitor-view-picker').evaluate(element => element.setAttribute('data-retained-ride', 'true'));
  await page.getByRole('link', { name: 'History', exact: true }).click();
  await expect(page).toHaveURL(/\/sessions$/);
  await expect(page.locator('[data-retained-ride="true"]')).toHaveCount(1); // actual Stack retains the same DOM
  await expect(page.locator('[data-retained-ride="true"]')).toBeHidden();
  await expect.poll(async () => (await clocks()).read).toBe(0);
  await expect.poll(async () => (await clocks()).display).toBe(0);
  const frozen = (await clocks()).fired;
  await page.waitForTimeout(900);
  expect((await clocks()).fired).toBe(frozen);
  await page.getByRole('link', { name: 'Ride', exact: true }).click();
  await expect.poll(async () => (await clocks()).read).toBe(1);
  await expect.poll(async () => (await clocks()).display).toBe(1);
  await expect(page.locator('[data-testid="monitor-view-picker"]:visible')).toHaveCount(1);
  await page.goBack();
  await expect.poll(async () => (await clocks()).read).toBe(0);
  await page.goBack();
  await expect(page.locator('[data-testid="monitor-view-picker"]:visible')).toHaveCount(1);
  await expect.poll(async () => (await clocks()).read).toBe(1);
  await expect.poll(async () => (await clocks()).display).toBe(1);
});

test('pending and current exact minima keep the narrow chart card height stable', async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 844 });
  await importRecording(page);
  await page.getByTestId('monitor-view-picker').click();
  await page.getByRole('button', { name: 'Battery', exact: true }).click();
  const minimum = page.locator('[aria-label^="Minimum "]').first();
  await expect(minimum).toHaveAttribute('aria-label', /, interval /);
  const chart = page.getByTestId('monitor-chart-batteryVoltageV');
  await chart.scrollIntoViewIfNeeded();
  await minimum.evaluate(element => {
    const observations: { prior: boolean; lineHeight: number; cardHeight: number }[] = [];
    const record = () => observations.push({ prior: element.getAttribute('aria-label')!.includes('previous interval'), lineHeight: element.getBoundingClientRect().height, cardHeight: element.closest('[data-testid="monitor-charts"]')!.getBoundingClientRect().height });
    Object.assign(window, { minimumLayoutProbe: observations });
    new MutationObserver(record).observe(element, { attributes: true, childList: true, subtree: true });
    record();
  });
  const box = (await chart.boundingBox())!;
  await chart.evaluate((element, position) => element.dispatchEvent(new WheelEvent('wheel', { bubbles: true, cancelable: true, ctrlKey: true, deltaY: -80, clientX: position.x, clientY: position.y })), { x: box.x + 8 + 0.2 * (box.width - 16), y: box.y + 60 });
  await expect(minimum).toHaveAttribute('aria-label', /previous interval 00:00.000 to 00:10.000/);
  await expect(minimum).toHaveAttribute('aria-label', /, interval /);
  const observations = await page.evaluate(() => (window as unknown as { minimumLayoutProbe: { prior: boolean; lineHeight: number; cardHeight: number }[] }).minimumLayoutProbe);
  expect(observations.some(value => value.prior)).toBe(true);
  expect(new Set(observations.map(value => value.lineHeight)).size).toBe(1);
  expect(Math.max(...observations.map(value => value.cardHeight)) - Math.min(...observations.map(value => value.cardHeight))).toBeLessThanOrEqual(1);
});
