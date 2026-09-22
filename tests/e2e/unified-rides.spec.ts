import { expect, test, type Page } from '@playwright/test';
import { crc16Xmodem, UART_WRITE } from '../../src/core/protocol';
import fixture from '../fixtures/protocol.json';

async function syntheticBike(page: Page) {
  const payload = Uint8Array.from(Buffer.from(fixture.identity.payloadHex, 'hex')), crc = crc16Xmodem(payload);
  await page.addInitScript(({ identity, telemetry, writerID }) => {
    class Characteristic extends EventTarget {
      value?: DataView;
      properties = { writeWithoutResponse: true };
      async startNotifications() { return this; }
      async writeValueWithoutResponse(bytes: Uint8Array) {
        const frame = bytes[2] === 111 ? identity : bytes[2] === 50 ? telemetry : null;
        if (!frame) throw new Error('Unexpected controller command');
        reader.value = new DataView(Uint8Array.from(frame).buffer);
        reader.dispatchEvent(new Event('characteristicvaluechanged'));
      }
    }
    const reader = new Characteristic(), writer = new Characteristic();
    const device = Object.assign(new EventTarget(), { id: 'synthetic-e2e-bike', name: 'CYC test bike', gatt: {
      connected: false,
      async connect() { this.connected = true; return this; },
      async getPrimaryService() { return { getCharacteristic: async (id: string) => id === writerID ? writer : reader }; },
      disconnect() { this.connected = false; device.dispatchEvent(new Event('gattserverdisconnected')); },
    } });
    Object.defineProperty(navigator, 'bluetooth', { configurable: true, value: { requestDevice: async () => device } });
  }, { identity: [2, payload.length, ...payload, crc >> 8, crc & 255, 3], telemetry: [...Buffer.from(fixture.telemetry[3]!.frameHex!, 'hex')], writerID: UART_WRITE });
}
async function stored(page: Page): Promise<{ phase?: string; samples: number; timerSeconds?: number; id: string }[]> {
  return page.evaluate(async () => {
    const db = await new Promise<IDBDatabase>((resolve, reject) => { const r = indexedDB.open('power-log'); r.onsuccess = () => resolve(r.result); r.onerror = () => reject(r.error); });
    try { return await new Promise((resolve, reject) => { const r = db.transaction('recordings').objectStore('recordings').getAll(); r.onsuccess = () => resolve(r.result); r.onerror = () => reject(r.error); }); }
    finally { db.close(); }
  });
}
for (const width of [390, 1440]) test(`one browser ride flow saves, pauses and discards at ${width}px`, async ({ page }, testInfo) => {
  await page.setViewportSize({ width, height: 1000 });
  await syntheticBike(page); await page.goto('/');
  await page.getByTestId('ride-setup').click();
  await expect(page.getByRole('switch', { name: 'Save to Apple Health' })).toHaveCount(0);
  await expect(page.getByRole('switch', { name: 'Use Apple Watch' })).toHaveCount(0);
  await page.getByRole('button', { name: 'Find bike', exact: true }).click();
  await page.getByRole('button', { name: /^Connect CYC/ }).click();
  await expect(page.getByTestId('bike-connection-status')).toContainText('Connected');
  await page.getByRole('button', { name: 'Done', exact: true }).click();
  await page.getByTestId('start-ride').filter({ visible: true }).click();
  await expect(page.getByTestId('ride-controls').filter({ visible: true }).getByRole('button', { name: 'Pause', exact: true })).toBeVisible();
  await expect.poll(async () => (await stored(page))[0]?.samples ?? 0).toBeGreaterThan(1);
  const beforeInitialBack = (await stored(page))[0]!;
  await page.getByRole('link', { name: 'Settings', exact: true }).click();
  await page.getByTestId('settings-distance').click();
  await page.goBack();
  await expect(page.getByTestId('settings-options')).toBeHidden();
  await expect(page.getByTestId('ride-controls').filter({ visible: true }).getByRole('button', { name: 'Pause', exact: true })).toBeVisible();
  expect((await stored(page))[0]?.id).toBe(beforeInitialBack.id);
  await expect.poll(async () => (await stored(page))[0]?.samples ?? 0).toBeGreaterThan(beforeInitialBack.samples + 1);
  expect((await stored(page))[0]?.phase).toBe('running');
  await page.getByTestId('ride-controls').filter({ visible: true }).getByRole('button', { name: 'Pause', exact: true }).click();
  await expect(page.getByTestId('ride-controls').filter({ visible: true }).getByRole('button', { name: 'Resume', exact: true })).toBeVisible();
  await expect.poll(async () => (await stored(page))[0]?.phase).toBe('paused');
  const paused = (await stored(page))[0]!;
  await expect.poll(async () => (await stored(page))[0]?.samples ?? 0).toBeGreaterThan(paused.samples + 1);
  expect((await stored(page))[0]?.timerSeconds).toBe(paused.timerSeconds);
  await page.getByTestId('ride-controls').filter({ visible: true }).getByRole('button', { name: 'Resume', exact: true }).click();
  await page.getByRole('link', { name: 'History', exact: true }).click();
  await page.getByRole('link', { name: 'Ride', exact: true }).click();
  await page.getByRole('button', { name: 'Finish', exact: true }).click();
  await expect(page.getByTestId('finish-ride-sheet')).toBeVisible();
  await page.goBack();
  await expect(page.getByTestId('finish-ride-sheet')).toBeHidden();
  await page.getByRole('link', { name: 'Ride', exact: true }).click();
  await expect(page.getByTestId('finish-ride-sheet')).toBeHidden();
  expect((await stored(page))[0]?.phase).toBe('running');
  await page.getByRole('button', { name: 'Finish', exact: true }).click();
  await page.getByTestId('finish-ride-sheet').getByRole('button', { name: 'Keep recording', exact: true }).click();
  await expect(page.getByTestId('ride-controls').filter({ visible: true }).getByRole('button', { name: 'Pause', exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Finish', exact: true }).click();
  await page.getByTestId('finish-ride-sheet').getByRole('button', { name: 'Save ride', exact: true }).click();
  await expect(page.getByTestId('start-ride').filter({ visible: true })).toBeVisible();
  await page.getByRole('link', { name: 'History', exact: true }).click();
  await expect(page.getByRole('tab', { name: 'Telemetry', exact: true })).toHaveCount(0);
  await page.getByRole('button', { name: /^Open ride,/ }).click();
  await expect(page.getByTestId('tab-transition-1').getByTestId('monitor-chart-power')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Export CSV', exact: true })).toBeEnabled();
  await expect(page.getByTestId('ride-distance-source')).toContainText('Controller estimate');
  await page.getByTestId('tab-transition-1').evaluate(node => node.setAttribute('data-retained-saved-history', 'true'));
  await expect(page.getByRole('button', { name: 'Distance source', exact: true })).toHaveCount(0);
  await page.getByRole('link', { name: 'Settings', exact: true }).click();
  await page.getByTestId('settings-distance').click();
  await page.getByRole('radio', { name: 'Controller estimate', exact: true }).click();
  await page.getByRole('link', { name: 'History', exact: true }).click();
  const retainedHistory = page.locator('[data-retained-saved-history="true"]');
  await expect(retainedHistory).toBeVisible();
  await expect.poll(() => retainedHistory.evaluate(node => new DOMMatrixReadOnly(getComputedStyle(node).transform).m41)).toBe(0);
  await page.waitForTimeout(250);
  await expect(retainedHistory.getByTestId('monitor-chart-power')).toBeVisible();
  await expect(page.getByTestId('tab-transition-1')).toHaveCount(1);
  await expect(page.getByTestId('distance-source-caption')).toContainText('Controller estimate');
  await page.getByTestId('tab-transition-1').getByTestId('monitor-edit').click();
  const editor = page.getByTestId('monitor-editor-dialog');
  await editor.getByRole('textbox', { name: 'Search metrics' }).fill('Ride distance');
  await editor.getByRole('checkbox', { name: 'Ride distance', exact: true }).check();
  await editor.getByRole('tab', { name: 'Graphs', exact: true }).click();
  await editor.getByRole('textbox', { name: 'Search metrics' }).fill('Ride distance');
  await editor.getByRole('checkbox', { name: 'Ride distance', exact: true }).check();
  await editor.getByRole('button', { name: 'Done', exact: true }).click();
  await expect(page.getByTestId('tab-transition-1').getByTestId('monitor-number-source-distanceMeters')).toContainText('Controller estimate');
  await expect(editor).toBeHidden();
  const distanceChart = page.getByTestId('tab-transition-1').getByTestId('monitor-chart-distanceMeters');
  await expect(distanceChart).toBeVisible();
  await expect(distanceChart.locator('path').first()).toHaveAttribute('d', /L/);
  const monitorStatus = page.getByTestId('tab-transition-1').getByTestId('monitor-status');
  await testInfo.attach('monitor-status', { body: (await page.getByTestId('tab-transition-1').getByTestId('monitor-view-picker').getAttribute('aria-label')) ?? '', contentType: 'text/plain' });
  await expect(monitorStatus.filter({ hasText: 'Chart error' })).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Export FIT', exact: true })).toHaveCount(0);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  await page.screenshot({ path: testInfo.outputPath(`saved-ride-${width}.png`), fullPage: true });
  await distanceChart.screenshot({ path: testInfo.outputPath(`saved-distance-chart-${width}.png`) });
  const savedID = (await stored(page))[0]!.id;
  await page.getByRole('link', { name: 'Ride', exact: true }).click();
  await expect(page.getByTestId('start-ride').filter({ visible: true })).toHaveCount(1);
  await page.getByTestId('start-ride').filter({ visible: true }).click();
  await expect(page.getByTestId('ride-controls').filter({ visible: true }).getByRole('button', { name: 'Pause', exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Finish', exact: true }).click();
  await page.getByTestId('finish-ride-sheet').getByRole('button', { name: 'Discard ride', exact: true }).click();
  await page.getByTestId('finish-ride-sheet').getByRole('button', { name: 'Discard for good', exact: true }).click();
  await expect(page.getByTestId('start-ride').filter({ visible: true })).toBeVisible();
  expect((await stored(page)).map(ride => ride.id)).toEqual([savedID]);
  await page.getByRole('link', { name: 'History', exact: true }).click();
  await page.reload();
  await expect(page.getByRole('button', { name: /^Open ride,/ })).toHaveCount(1);
  await page.getByRole('link', { name: 'Settings', exact: true }).click();
  await page.getByRole('link', { name: 'History', exact: true }).click();
  await page.getByRole('button', { name: 'Edit', exact: true }).click();
  await page.getByRole('button', { name: /^Delete ride,/ }).click();
  await expect(page.getByTestId('delete-ride-sheet')).toBeVisible();
  await page.goBack();
  await expect(page.getByTestId('delete-ride-sheet')).toBeHidden();
  expect((await stored(page)).map(ride => ride.id)).toEqual([savedID]);
  await page.getByRole('link', { name: 'History', exact: true }).click();
  await expect(page.getByTestId('delete-ride-sheet')).toBeHidden();
  await expect(page.getByRole('button', { name: /^Open ride,/ })).toHaveCount(1);
});
