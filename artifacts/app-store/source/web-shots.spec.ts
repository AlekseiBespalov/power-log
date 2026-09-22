import { expect, test, type Page } from '@playwright/test';
import path from 'node:path';
import { crc16Xmodem, decodeSelectiveValues, SELECTED_MASK, TELEMETRY_FIELDS, UART_WRITE } from '../../../src/core/protocol';
import fixture from '../../../tests/fixtures/protocol.json';

// Fictional example rides, recorded by the app itself from a synthetic CYC controller.
// The controller answers the app's own allowlisted requests with encoded telemetry frames,
// so every ride here is a real browser recording. No personal ride data is used.
const STATE = path.resolve(__dirname, '../.cache/web-shots-state.json');
const out = (platform: string, name: string) => path.resolve(__dirname, `../screenshots/${platform}/${name}.png`);

const smooth = (edge: number) => edge * edge * (3 - 2 * edge);
const drift = (t: number, scale: number, period: number, phase: number) => Math.sin(t / period + phase) * scale;
// A CYC X1 Pro on a 72 V nominal pack, charged to 84 V, with a 6 kW peak.
// Fractions of the ride: roll away, a climb, a crest and coast, a steady stretch,
// a short full-power effort, then an easy cruise home.
const PEAK_WATTS = 6000;
const KEYS = [
  { at: 0, watts: 55, cadence: 58, amps: 6 },
  { at: 0.08, watts: 120, cadence: 78, amps: 14 },
  { at: 0.2, watts: 190, cadence: 72, amps: 38 },
  { at: 0.32, watts: 215, cadence: 68, amps: 44 },
  { at: 0.39, watts: 60, cadence: 52, amps: 2 },
  { at: 0.46, watts: 145, cadence: 84, amps: 13 },
  { at: 0.64, watts: 160, cadence: 86, amps: 16 },
  { at: 0.7, watts: 260, cadence: 94, amps: 62 },
  { at: 0.75, watts: 290, cadence: 97, amps: 75 },
  { at: 0.8, watts: 110, cadence: 76, amps: 8 },
  { at: 1, watts: 95, cadence: 72, amps: 9 },
];
function shape(fraction: number) {
  const upper = Math.max(1, KEYS.findIndex(key => key.at >= fraction));
  const a = KEYS[Math.min(upper, KEYS.length - 1) - 1]!, b = KEYS[Math.min(upper, KEYS.length - 1)]!;
  const edge = smooth(Math.min(1, Math.max(0, (fraction - a.at) / Math.max(1e-9, b.at - a.at))));
  return { watts: a.watts + (b.watts - a.watts) * edge, cadence: a.cadence + (b.cadence - a.cadence) * edge, amps: a.amps + (b.amps - a.amps) * edge };
}

/** Encodes the wire layout the app decodes: command 50, the mask, then each selected field. */
function selectivePayload(values: Record<string, number>) {
  const bytes: number[] = [50, (SELECTED_MASK >>> 24) & 255, (SELECTED_MASK >>> 16) & 255, (SELECTED_MASK >>> 8) & 255, SELECTED_MASK & 255];
  for (let bit = 0; bit < TELEMETRY_FIELDS.length; bit += 1) {
    if (!(SELECTED_MASK & (1 << bit))) continue;
    for (const [name, format, divisor] of TELEMETRY_FIELDS[bit]!) {
      const raw = Math.round((values[name] ?? 0) * divisor);
      if (format === 'u8') bytes.push(raw & 255);
      else if (format === 'i16') bytes.push((raw >>> 8) & 255, raw & 255);
      else bytes.push((raw >>> 24) & 255, (raw >>> 16) & 255, (raw >>> 8) & 255, raw & 255);
    }
  }
  return bytes;
}

function rideFrames(seconds: number, stepSeconds = 0.25) {
  const frames: string[] = [];
  let ampSeconds = 0, wattSeconds = 0, motorTemp = 32, controllerTemp = 28;
  for (let time = 0; time <= seconds; time += stepSeconds) {
    const form = shape(time / seconds);
    const watts = Math.max(0, form.watts + drift(time, 7, 23, 0.4) + drift(time, 3, 6.1, 1.2));
    const cadence = Math.max(0, form.cadence + drift(time, 1.6, 17, 0.9) + drift(time, 0.7, 4.3, 2.1));
    const current = Math.max(0.2, form.amps + drift(time, 0.9, 8.7, 0.3) + drift(time, 0.4, 2.9, 1.7));
    // A full pack rests near 84 V, drops as charge leaves it and sags under current.
    const voltage = 83.6 - (ampSeconds / 3600) * 0.42 - current * 0.045;
    const motorInput = voltage * current;
    // First-order thermal response: the motor heats under load and cools when it eases.
    motorTemp += ((34 + 44 * (motorInput / PEAK_WATTS)) - motorTemp) * (stepSeconds / 45);
    controllerTemp += ((29 + 28 * (motorInput / PEAK_WATTS)) - controllerTemp) * (stepSeconds / 60);
    ampSeconds += current * stepSeconds;
    wattSeconds += motorInput * stepSeconds;
    const payload = selectivePayload({
      controllerTempC: controllerTemp, motorTempC: motorTemp,
      motorCurrentA: current * 1.9, batteryCurrentA: current, motorRpm: Math.round(cadence * 45),
      batteryVoltageV: voltage, consumedAh: ampSeconds / 3600, consumedWh: wattSeconds / 3600,
      cadenceRpm: cadence, throttleVoltageV: 0.83, pedalTorqueNm: cadence > 0 ? watts / (cadence * 2 * Math.PI / 60) : 0,
      faultCode: 0, humanPowerW: Math.round(watts), speedRaw: 0, raceMode: 0, assistLevel: 3,
    });
    decodeSelectiveValues(Uint8Array.from(payload)); // the app's own decoder must accept every frame
    const crc = crc16Xmodem(Uint8Array.from(payload));
    frames.push(Buffer.from([2, payload.length, ...payload, (crc >> 8) & 255, crc & 255, 3]).toString('hex'));
  }
  return frames;
}

async function syntheticBike(page: Page, seconds: number) {
  const payload = Uint8Array.from(Buffer.from(fixture.identity.payloadHex, 'hex')), crc = crc16Xmodem(payload);
  await page.addInitScript(({ identity, telemetry, writerID }) => {
    const started = Date.now();
    const bytes = (hex: string) => Uint8Array.from(hex.match(/../g)!.map(pair => parseInt(pair, 16)));
    class Characteristic extends EventTarget {
      value?: DataView;
      properties = { writeWithoutResponse: true };
      async startNotifications() { return this; }
      async writeValueWithoutResponse(command: Uint8Array) {
        const step = Math.min(telemetry.length - 1, Math.floor((Date.now() - started) / 250));
        const frame = command[2] === 111 ? identity : command[2] === 50 ? telemetry[step]! : null;
        if (!frame) throw new Error('Unexpected controller command');
        reader.value = new DataView(bytes(frame).buffer);
        reader.dispatchEvent(new Event('characteristicvaluechanged'));
      }
    }
    const reader = new Characteristic(), writer = new Characteristic();
    const device = Object.assign(new EventTarget(), { id: 'example-controller', name: 'CYC example controller', gatt: {
      connected: false,
      async connect() { this.connected = true; return this; },
      async getPrimaryService() { return { getCharacteristic: async (id: string) => id === writerID ? writer : reader }; },
      disconnect() { this.connected = false; device.dispatchEvent(new Event('gattserverdisconnected')); },
    } });
    Object.defineProperty(navigator, 'bluetooth', { configurable: true, value: { requestDevice: async () => device } });
  }, { identity: Buffer.from([2, payload.length, ...payload, crc >> 8, crc & 255, 3]).toString('hex'),
    telemetry: rideFrames(seconds), writerID: UART_WRITE });
}

async function connect(page: Page) {
  await page.goto('/');
  await page.getByTestId('ride-setup').click();
  await page.getByRole('button', { name: 'Find bike', exact: true }).click();
  await page.getByRole('button', { name: /^Connect CYC/ }).click();
  await expect(page.getByTestId('bike-connection-status')).toContainText('Connected');
  await page.getByRole('button', { name: 'Done', exact: true }).click();
}

async function recordRide(page: Page, seconds: number) {
  await page.getByTestId('start-ride').filter({ visible: true }).click();
  await expect(page.getByRole('button', { name: 'Finish', exact: true })).toBeVisible();
  await page.waitForTimeout(seconds * 1000);
}

async function saveRide(page: Page) {
  await page.getByRole('button', { name: 'Finish', exact: true }).click();
  await page.getByRole('button', { name: 'Save ride', exact: true }).click();
  await expect(page.getByTestId('start-ride').filter({ visible: true })).toBeVisible();
}

async function chooseView(page: Page, name: 'Ride' | 'Battery' | 'Temperature') {
  await page.getByTestId('monitor-view-picker').filter({ visible: true }).click();
  await page.getByRole('button', { name: new RegExp(`^${name}(?:\\s+✓)?$`) }).click();
  await expect(page.getByTestId('monitor-view-picker').filter({ visible: true })).toHaveAccessibleName(new RegExp(`^Choose monitoring view, ${name}`));
  await page.waitForTimeout(900);
}

async function inspect(page: Page, group: string, fraction: number) {
  const chart = page.getByTestId(`monitor-chart-${group}`).filter({ visible: true }).first();
  await chart.scrollIntoViewIfNeeded();
  const box = (await chart.boundingBox())!;
  await chart.click({ position: { x: 8 + (box.width - 16) * fraction, y: 70 } });
  await page.waitForTimeout(700);
}

test.describe.configure({ mode: 'serial' });

test('record the example rides', async ({ page, context }) => {
  await syntheticBike(page, 240);
  await connect(page);
  await recordRide(page, 240);
  await saveRide(page);
  await recordRide(page, 100);
  await saveRide(page);
  await context.storageState({ path: STATE, indexedDB: true });
});

for (const [platform, viewport, scale] of [['web', { width: 440, height: 956 }, 3], ['macos', { width: 1440, height: 900 }, 2]] as const) {
  test.describe(platform, () => {
    test.use({ storageState: STATE, viewport, deviceScaleFactor: scale });

    test(`${platform} story`, async ({ page }) => {
      await syntheticBike(page, 150);
      await connect(page);
      await recordRide(page, 100);
      await page.screenshot({ path: out(platform, '01-live-recording'), animations: 'disabled' });
      await saveRide(page);

      await page.getByRole('link', { name: 'History', exact: true }).click();
      const rows = page.getByRole('button', { name: /^Open ride/ });
      await expect(rows.first()).toBeVisible();
      await page.waitForTimeout(600);
      await page.screenshot({ path: out(platform, '02-history'), animations: 'disabled' });

      await rows.last().click();
      await expect(page.getByTestId('monitor-view-picker').filter({ visible: true })).toBeVisible();
      await page.waitForTimeout(1400);
      await page.screenshot({ path: out(platform, '03-ride-summary'), animations: 'disabled' });

      await inspect(page, 'power', 0.42);
      await page.screenshot({ path: out(platform, '04-effort-analysis'), animations: 'disabled' });

      await chooseView(page, 'Battery');
      await inspect(page, 'batteryVoltageV', 0.42);
      await page.screenshot({ path: out(platform, '05-battery'), animations: 'disabled' });

      await chooseView(page, 'Temperature');
      await page.screenshot({ path: out(platform, '06-temperature'), animations: 'disabled' });

      await chooseView(page, 'Ride');
      await page.getByTestId('monitor-edit').filter({ visible: true }).click();
      await expect(page.getByRole('heading', { name: /layout$/ })).toBeVisible();
      await page.waitForTimeout(600);
      await page.screenshot({ path: out(platform, '07-custom-dashboard'), animations: 'disabled' });
      await page.getByRole('button', { name: 'Done', exact: true }).click();

      await page.getByRole('link', { name: 'Settings', exact: true }).click();
      await expect(page.getByRole('heading', { name: 'Settings' })).toBeVisible();
      await page.waitForTimeout(600);
      await page.screenshot({ path: out(platform, '08-settings'), animations: 'disabled' });
    });
  });
}
