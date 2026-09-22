import { expect, test, type Locator, type Page } from '@playwright/test';
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
    const device = Object.assign(new EventTarget(), { id: 'synthetic-layout-bike', name: 'CYC layout bike', gatt: {
      connected: false,
      async connect() { this.connected = true; return this; },
      async getPrimaryService() { return { getCharacteristic: async (id: string) => id === writerID ? writer : reader }; },
      disconnect() { this.connected = false; device.dispatchEvent(new Event('gattserverdisconnected')); },
    } });
    Object.defineProperty(navigator, 'bluetooth', { configurable: true, value: { requestDevice: async () => device } });
  }, { identity: [2, payload.length, ...payload, crc >> 8, crc & 255, 3], telemetry: [...Buffer.from(fixture.telemetry[3]!.frameHex!, 'hex')], writerID: UART_WRITE });
}

async function settled(dialog: Locator) {
  await expect(dialog).toBeVisible();
  await expect.poll(() => dialog.evaluate(element => {
    for (let node: Element | null = element; node; node = node.parentElement) {
      const style = getComputedStyle(node), transform = new DOMMatrixReadOnly(style.transform);
      if (Number(style.opacity) < 0.999 || Math.abs(transform.m41) > 0.1 || Math.abs(transform.m42) > 0.1) return false;
    }
    return true;
  })).toBe(true);
}

async function boundedDialog(page: Page, id: string, inset: number) {
  const dialog = page.getByTestId(id);
  await settled(dialog);
  const box = (await dialog.boundingBox())!;
  expect(box.y).toBeGreaterThanOrEqual(inset - 1);
  expect(box.y + box.height).toBeLessThanOrEqual(page.viewportSize()!.height - inset + 1);
  expect(box.x).toBeGreaterThanOrEqual(0);
  expect(box.x + box.width).toBeLessThanOrEqual(page.viewportSize()!.width);
  return dialog;
}

// Text-only browser magnification probes layout without reducing text size.
// Native Dynamic Type is verified separately; RN Web's fontScale stays 1.
async function doubleText(root: Locator) {
  await root.evaluate(element => {
    const nodes = [...new Set([element, ...element.querySelectorAll('[dir="auto"],a')])]
      .filter(node => node.textContent?.trim() !== 'ϟ')
      .map(node => ({ node: node as HTMLElement, size: parseFloat(getComputedStyle(node).fontSize), line: parseFloat(getComputedStyle(node).lineHeight) }));
    for (const { node, size, line } of nodes) {
      node.style.fontSize = `${size * 2}px`;
      if (Number.isFinite(line)) node.style.lineHeight = `${line * 2}px`;
    }
  });
}

test('full metric labels fit narrow number cells without moving value baselines', async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 640 });
  await page.goto('/');
  await page.getByTestId('monitor-view-picker').click();
  await page.getByRole('button', { name: /^Temperature(?:\s+✓)?$/ }).click();
  const temperatures = ['motorTempC', 'controllerTempC'];
  const baseline: number[] = [];
  for (const id of temperatures) {
    const tile = page.getByTestId(`monitor-number-${id}`);
    const label = tile.locator('[aria-label]').first();
    const bounds = await label.evaluate(element => ({ width: element.clientWidth, contentWidth: element.scrollWidth, height: element.clientHeight, contentHeight: element.scrollHeight }));
    expect(bounds.contentWidth).toBeLessThanOrEqual(bounds.width);
    expect(bounds.contentHeight).toBeLessThanOrEqual(bounds.height);
    baseline.push((await tile.getByText('—', { exact: true }).boundingBox())!.y);
  }
  expect(baseline[0]).toBe(baseline[1]);
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(320);
});

test('Finish and Delete keep their text and actions reachable in a short window', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 640 });
  await syntheticBike(page);
  await page.goto('/');
  await page.getByTestId('ride-setup').click();
  await page.getByRole('button', { name: 'Find bike', exact: true }).click();
  await page.getByRole('button', { name: /^Connect CYC/ }).click();
  await expect(page.getByTestId('bike-connection-status')).toContainText('Connected');
  await page.getByRole('button', { name: 'Done', exact: true }).click();
  await page.getByTestId('start-ride').filter({ visible: true }).click();
  await page.getByRole('button', { name: 'Finish', exact: true }).click();
  await page.setViewportSize({ width: 760, height: 240 });
  let dialog = await boundedDialog(page, 'finish-ride-sheet', 24);
  expect(await page.getByTestId('finish-ride-scroll').evaluate(element => element.scrollHeight > element.clientHeight)).toBe(true);
  await dialog.getByRole('button', { name: 'Keep recording', exact: true }).click();
  await expect(page.getByTestId('finish-ride-sheet')).toHaveCount(0);
  await page.getByRole('button', { name: 'Finish', exact: true }).click();
  dialog = await boundedDialog(page, 'finish-ride-sheet', 24);
  await dialog.getByRole('button', { name: 'Save ride', exact: true }).click();
  await expect(page.getByTestId('start-ride').filter({ visible: true })).toBeVisible();
  await page.getByRole('link', { name: 'History', exact: true }).click();
  await page.getByRole('button', { name: 'Edit', exact: true }).click();
  await page.getByRole('button', { name: /^Delete ride,/ }).click();
  dialog = await boundedDialog(page, 'delete-ride-sheet', 24);
  expect(await page.getByTestId('delete-ride-scroll').evaluate(element => element.scrollHeight > element.clientHeight)).toBe(true);
  await dialog.getByRole('button', { name: 'Keep ride', exact: true }).click();
  await expect(page.getByRole('button', { name: /^Open ride,/ })).toHaveCount(1);
  await page.getByRole('button', { name: /^Delete ride,/ }).click();
  dialog = await boundedDialog(page, 'delete-ride-sheet', 24);
  await dialog.getByRole('button', { name: 'Delete ride', exact: true }).click();
  await expect(page.getByRole('button', { name: /^Open ride,/ })).toHaveCount(0);
});

test('app and History headers keep enlarged text and navigation inside320px', async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 640 });
  await page.goto('/');
  const header = page.getByTestId('app-header');
  await doubleText(header);
  const brand = (await header.getByText('Power Log', { exact: true }).boundingBox())!;
  for (const name of ['Ride', 'History']) {
    const link = header.getByRole('link', { name, exact: true });
    const box = (await link.boundingBox())!;
    expect(box.y).toBeGreaterThanOrEqual(brand.y + brand.height);
    expect(box.height).toBeGreaterThanOrEqual(44);
    expect(box.x + box.width).toBeLessThanOrEqual(320);
  }
  await header.getByRole('link', { name: 'History', exact: true }).click();
  const toolbar = page.getByTestId('history-toolbar');
  await settled(toolbar);
  await doubleText(toolbar);
  for (const name of ['Refresh', 'Open CSV']) {
    const button = toolbar.getByRole('button', { name, exact: true });
    const box = (await button.boundingBox())!;
    expect(box.x).toBeGreaterThanOrEqual(0);
    expect(box.x + box.width).toBeLessThanOrEqual(320);
    expect(box.height).toBeGreaterThanOrEqual(44);
  }
  await toolbar.getByRole('button', { name: 'Refresh', exact: true }).click();
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(320);
});

test('short editor keeps enlarged title/actions and repeated accessible reorder after scrolling', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 640 });
  await page.goto('/');
  await page.getByTestId('monitor-view-picker').click();
  await page.getByRole('button', { name: /^Temperature(?:\s+✓)?$/ }).click();
  await page.getByTestId('monitor-edit').click();
  await page.setViewportSize({ width: 760, height: 320 });
  const editor = page.getByTestId('monitor-editor-dialog');
  await doubleText(editor.getByRole('heading', { name: 'Temperature layout', exact: true }));
  await doubleText(editor.getByRole('button', { name: 'Done', exact: true }));
  await doubleText(editor.getByRole('button', { name: 'Reset layout', exact: true }));
  await boundedDialog(page, 'monitor-editor-dialog', 24);
  const scroll = page.getByTestId('monitor-editor-scroll');
  expect(await scroll.getByRole('textbox', { name: 'Search metrics', exact: true }).count()).toBe(1);
  const handle = page.getByTestId('monitor-drag-controllerTempC');
  for (const [key, position] of [['Home', '1'], ['End', '3'], ['Home', '1']] as const) {
    await handle.scrollIntoViewIfNeeded();
    expect(await scroll.evaluate(element => element.scrollTop)).toBeGreaterThan(0);
    await handle.focus(); await handle.press(key);
    await expect(handle).toHaveAttribute('aria-valuenow', position);
  }
  await editor.getByRole('button', { name: 'Done', exact: true }).click();
  const first = page.getByTestId('monitor-numbers').locator('[data-testid^="monitor-number-"]').first();
  await expect(first).toHaveAttribute('data-testid', 'monitor-number-controllerTempC');
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
});
