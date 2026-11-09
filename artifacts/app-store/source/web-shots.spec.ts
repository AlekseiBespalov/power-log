import { expect, test, type Locator, type Page } from '@playwright/test';
import path from 'node:path';
import { mkdir, writeFile } from 'node:fs/promises';
import { UART_WRITE } from '../../../tests/fixtures/browser-bike';
import { controllerReadingFrame, framePayload } from '../../../tests/support/controller-frames';
import fixture from '../../../tests/fixtures/protocol.json';
import { saveRides, type SavedRide } from '../../../tests/helpers/saved-rides';

const STATE = path.resolve(__dirname, '../.cache/web-shots-state.json');
type IndexedDBOrigin = { indexedDB?: { name: string }[] };
const out = (platform: string, name: string) => path.resolve(__dirname, `../screenshots/${platform}/${name}.png`);

const smooth = (edge: number) => edge * edge * (3 - 2 * edge);
const drift = (t: number, scale: number, period: number, phase: number) => Math.sin(t / period + phase) * scale;
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
  const upper = Math.max(
    1,
    KEYS.findIndex(key => key.at >= fraction),
  );
  const a = KEYS[Math.min(upper, KEYS.length - 1) - 1]!,
    b = KEYS[Math.min(upper, KEYS.length - 1)]!;
  const edge = smooth(Math.min(1, Math.max(0, (fraction - a.at) / Math.max(1e-9, b.at - a.at))));
  return {
    watts: a.watts + (b.watts - a.watts) * edge,
    cadence: a.cadence + (b.cadence - a.cadence) * edge,
    amps: a.amps + (b.amps - a.amps) * edge,
  };
}

type Reading = Parameters<typeof controllerReadingFrame>[0];

function rideReadings(seconds: number, stepSeconds = 0.25) {
  const readings: { atMs: number; reading: Reading }[] = [];
  let ampSeconds = 0,
    wattSeconds = 0,
    motorTemp = 32,
    controllerTemp = 28;
  for (let time = 0; time <= seconds; time += stepSeconds) {
    const form = shape(time / seconds);
    const watts = Math.max(0, form.watts + drift(time, 7, 23, 0.4) + drift(time, 3, 6.1, 1.2));
    const cadence = Math.max(0, form.cadence + drift(time, 1.6, 17, 0.9) + drift(time, 0.7, 4.3, 2.1));
    const current = Math.max(0.2, form.amps + drift(time, 0.9, 8.7, 0.3) + drift(time, 0.4, 2.9, 1.7));
    // A full pack rests near 84 V, drops as charge leaves it and sags under current.
    const voltage = 83.6 - (ampSeconds / 3600) * 0.42 - current * 0.045;
    const motorInput = voltage * current;
    // First-order thermal response: the motor heats under load and cools when it eases.
    motorTemp += (34 + 44 * (motorInput / PEAK_WATTS) - motorTemp) * (stepSeconds / 45);
    controllerTemp += (29 + 28 * (motorInput / PEAK_WATTS) - controllerTemp) * (stepSeconds / 60);
    ampSeconds += current * stepSeconds;
    wattSeconds += motorInput * stepSeconds;
    readings.push({
      atMs: Math.round(time * 1000),
      reading: {
        controllerTempC: controllerTemp,
        motorTempC: motorTemp,
        motorCurrentA: current * 1.9,
        batteryCurrentA: current,
        motorRpm: Math.round(cadence * 45),
        batteryVoltageV: voltage,
        consumedAh: ampSeconds / 3600,
        consumedWh: wattSeconds / 3600,
        cadenceRpm: cadence,
        throttleVoltageV: 0.83,
        pedalTorqueNm: cadence > 0 ? watts / ((cadence * 2 * Math.PI) / 60) : 0,
        faultCode: 0,
        humanPowerW: Math.round(watts),
        speedRaw: 0,
        raceMode: 0,
        assistLevel: 3,
      },
    });
  }
  return readings;
}

function rideFrames(seconds: number) {
  return rideReadings(seconds).map(({ reading }) => Buffer.from(controllerReadingFrame(reading)).toString('hex'));
}

function savedRide(startUtcMs: number, seconds: number): SavedRide {
  const readings = rideReadings(seconds);
  const column = (name: keyof Reading) => readings.map(({ reading }) => Number(reading[name]));
  return {
    startUtcMs,
    durationMs: seconds * 1000,
    indoor: false,
    atMs: readings.map(({ atMs }) => atMs),
    powerW: column('humanPowerW'),
    columns: {
      ...Object.fromEntries(
        (
          [
            'cadenceRpm',
            'batteryCurrentA',
            'batteryVoltageV',
            'motorCurrentA',
            'motorRpm',
            'motorTempC',
            'controllerTempC',
            'consumedAh',
            'consumedWh',
            'pedalTorqueNm',
            'speedRaw',
          ] as const
        ).map(name => [name, column(name)]),
      ),
      motorInputPowerW: readings.map(({ reading }) => Number(reading.batteryVoltageV) * Number(reading.batteryCurrentA)),
    },
  };
}

async function syntheticBike(page: Page, seconds: number) {
  const identity = framePayload(Uint8Array.from(Buffer.from(fixture.identity.payloadHex, 'hex')));
  await page.addInitScript(
    ({ identity, telemetry, writerID }) => {
      const started = Date.now();
      const bytes = (hex: string) => Uint8Array.from(hex.match(/../g)!.map(pair => parseInt(pair, 16)));
      class Characteristic extends EventTarget {
        value?: DataView;
        properties = { writeWithoutResponse: true, notify: true };
        async startNotifications() {
          return this;
        }
        async writeValueWithoutResponse(command: Uint8Array) {
          const step = Math.min(telemetry.length - 1, Math.floor((Date.now() - started) / 250));
          const frame = command[2] === 111 ? identity : command[2] === 50 ? telemetry[step]! : null;
          if (!frame) throw new Error('Unexpected controller command');
          reader.value = new DataView(bytes(frame).buffer);
          reader.dispatchEvent(new Event('characteristicvaluechanged'));
        }
      }
      const reader = new Characteristic(),
        writer = new Characteristic();
      const device = Object.assign(new EventTarget(), {
        id: 'example-controller',
        name: 'CYC example controller',
        gatt: {
          connected: false,
          async connect() {
            this.connected = true;
            return this;
          },
          async getPrimaryService() {
            return { getCharacteristic: async (id: string) => (id === writerID ? writer : reader) };
          },
          disconnect() {
            this.connected = false;
            device.dispatchEvent(new Event('gattserverdisconnected'));
          },
        },
      });
      Object.defineProperty(navigator, 'bluetooth', {
        configurable: true,
        value: { requestDevice: async () => device },
      });
    },
    { identity: Buffer.from(identity).toString('hex'), telemetry: rideFrames(seconds), writerID: UART_WRITE },
  );
}

async function connect(page: Page) {
  await page.goto('/settings');
  await expect(page.getByTestId('settings-loading')).toHaveCount(0);
  const gps = page.getByRole('switch', { name: 'Record GPS route', exact: true });
  await expect(gps).toBeVisible();
  if ((await gps.getAttribute('aria-checked')) === 'true') await gps.click();
  await expect(gps).toHaveAttribute('aria-checked', 'false');
  for (const [field, choice] of [
    ['units', 'Metric'],
    ['temperature', '°C'],
  ] as const) {
    const row = page.getByTestId(`settings-${field}`);
    await row.click();
    await page.getByRole('radio', { name: choice, exact: true }).click();
    await expect(page.getByTestId('settings-options')).toBeHidden();
    await expect(row).toHaveAccessibleName(new RegExp(`, ${choice}$`));
  }
  await page.goto('/');
  await page.getByTestId('ride-setup').click();
  const setup = page.getByTestId('ride-setup-sheet');
  await setup.getByRole('button', { name: 'Find bike', exact: true }).click();
  await setup.getByRole('button', { name: /^Connect CYC/ }).click();
  await expect(setup.getByTestId('bike-connection-status')).toContainText('Connected');
  await setup.getByRole('button', { name: 'Done', exact: true }).click();
  await expect(setup).toHaveCount(0);
}

async function recordRide(page: Page, seconds: number) {
  await page.getByTestId('ride-setup').filter({ visible: true }).click();
  const setup = page.getByTestId('ride-setup-sheet');
  await expect(setup.getByTestId('setup-start-ride')).toBeEnabled();
  await setup.getByTestId('setup-start-ride').click();
  await expect(setup.getByTestId('setup-start-ride')).toHaveCount(0);
  const done = setup.getByRole('button', { name: 'Done', exact: true });
  if (await done.isVisible()) {
    try {
      await done.click({ timeout: 3000 });
    } catch (error) {
      if (await setup.count()) throw error;
    }
  }
  await expect(setup).toHaveCount(0);
  const controls = page.getByTestId('ride-controls').filter({ visible: true });
  await expect(controls.getByTestId('ride-state-badge')).toHaveText('Running');
  await expect(controls.getByRole('button', { name: 'Finish', exact: true })).toBeEnabled();
  await expect
    .poll(
      async () => {
        const timer = (await controls.textContent())?.match(/(\d+):(\d{2})(?::(\d{2}))?/);
        if (!timer) return -1;
        return timer[3] === undefined
          ? Number(timer[1]) * 60 + Number(timer[2])
          : Number(timer[1]) * 3600 + Number(timer[2]) * 60 + Number(timer[3]);
      },
      { timeout: seconds * 1000 + 30000, intervals: [1000] },
    )
    .toBeGreaterThanOrEqual(seconds);
  await expect(controls.getByTestId('ride-state-badge')).toHaveText('Running');
}

async function saveRide(page: Page) {
  await page.getByRole('button', { name: 'Finish', exact: true }).click();
  const finish = page.getByTestId('finish-ride-sheet');
  await expect(finish.getByRole('heading', { name: 'Finish this ride?', exact: true })).toBeVisible();
  await finish.getByRole('button', { name: 'Save ride', exact: true }).click();
  await expect(finish).toHaveCount(0);
  await expect(page.getByTestId('start-ride').filter({ visible: true })).toBeVisible();
}

async function chooseView(page: Page, name: 'Ride' | 'Battery' | 'Temperature') {
  await page.getByTestId('monitor-view-picker').filter({ visible: true }).click();
  const menu = page.getByTestId('monitor-menu-dialog');
  await menu.getByRole('button', { name: new RegExp(`^${name}(?:\\s+✓)?$`) }).click();
  await expect(menu).toHaveCount(0);
  await expect(page.getByTestId('monitor-view-picker').filter({ visible: true })).toHaveAccessibleName(
    `Choose monitoring view, ${name}`,
  );
}

async function expectChart(page: Page, group: string, metrics: readonly string[], live = false) {
  const chart = page.getByTestId(`monitor-chart-${group}`).filter({ visible: true }).first();
  await expect(chart).toBeVisible();
  await chart.scrollIntoViewIfNeeded();
  await expect
    .poll(() =>
      chart
        .locator('svg path[stroke]')
        .evaluateAll(paths => paths.some(path => (path.getAttribute('d') ?? '').includes('L'))),
    )
    .toBe(true);
  await expect(chart.getByText('No samples', { exact: true })).toHaveCount(0);
  for (const metric of metrics) {
    const reading = page.getByTestId(`monitor-readout-${metric}`).filter({ visible: true });
    await expect(reading).toContainText(/\d+(?:\.\d+)?\s+(?:W|rpm|V|A|°C)/);
    await expect(reading).not.toContainText(/Unavailable|No sample|Updating range|—/);
    if (!live) await expect(reading).toContainText(/avg [\d.]+[\s\S]*max [\d.]+/);
  }
  return chart;
}

async function inspect(page: Page, group: string, metric: string, fraction: number) {
  const chart = await expectChart(page, group, [metric]);
  await chart.scrollIntoViewIfNeeded();
  const box = (await chart.boundingBox())!;
  await chart.click({ position: { x: 8 + (box.width - 16) * fraction, y: 70 } });
  const reading = page.getByTestId(`monitor-readout-${metric}`).filter({ visible: true });
  await expect(reading).toContainText(/\d{2}:\d{2}/);
  await expect(reading).not.toContainText(/avg|Whole ride|No sample|Unavailable/);
  await expect(chart).toBeInViewport({ ratio: 1 });
}

async function scrollTo(page: Page, target: Locator, inset = 0) {
  await target.evaluate((node, inset) => {
    for (let parent = node.parentElement; parent; parent = parent.parentElement) {
      if (/(auto|scroll)/.test(getComputedStyle(parent).overflowY) && parent.scrollHeight > parent.clientHeight) {
        parent.scrollTop += node.getBoundingClientRect().top - parent.getBoundingClientRect().top - inset;
        return;
      }
    }
  }, inset);
  await expect(target).toBeInViewport({ ratio: 1 });
  await expect(page.getByTestId('app-header')).toBeInViewport({ ratio: 1 });
}

async function capture(page: Page, platform: string, name: string, surface: Locator) {
  await expect(surface).toBeVisible();
  await expect
    .poll(() =>
      surface.evaluate(node => {
        for (let parent: Element | null = node; parent; parent = parent.parentElement) {
          const style = getComputedStyle(parent);
          const matrix = new DOMMatrixReadOnly(style.transform);
          if (Number(style.opacity) !== 1 || Math.abs(matrix.m41) > 0.1 || Math.abs(matrix.m42) > 0.1) return false;
        }
        return !node.getAnimations({ subtree: true }).some(animation => animation.playState === 'running');
      }),
    )
    .toBe(true);
  await expect(page.getByRole('alert').filter({ visible: true })).toHaveCount(0);
  await expect(page.getByTestId('monitor-status').filter({ visible: true })).toHaveCount(0);
  await expect(page.getByTestId('settings-developer').filter({ visible: true })).toHaveCount(0);
  await expect(
    page.getByText(/Refreshing\.\.\.|Development builds only|Uncaught Error/).filter({ visible: true }),
  ).toHaveCount(0);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  const png = await page.screenshot({ path: out(platform, name), animations: 'disabled', omitBackground: false });
  const dimensions = platform === 'web' ? [1320, 2868] : [2880, 1800];
  expect([png.readUInt32BE(16), png.readUInt32BE(20)]).toEqual(dimensions);
  expect(png[25], 'PNG colour type must be RGB without alpha').toBe(2);
}

test.describe.configure({ mode: 'serial' });

test('record the example rides', async ({ page, context }) => {
  await page.goto('/sessions');
  await expect(page.getByText('No saved rides yet.', { exact: true })).toBeVisible();
  const now = Date.now();
  await saveRides(page, [savedRide(now - 3 * 3_600_000, 240), savedRide(now - 3_600_000, 100)]);
  const state = await context.storageState({ indexedDB: true });
  const archives = state.origins.flatMap(origin =>
    ((origin as typeof origin & IndexedDBOrigin).indexedDB ?? []).filter(database =>
      database.name.startsWith('power-log-archive-'),
    ),
  );
  expect(archives).toHaveLength(1);
  await mkdir(path.dirname(STATE), { recursive: true });
  await writeFile(STATE, JSON.stringify(state));
});

for (const [platform, viewport, scale] of [
  ['web', { width: 440, height: 956 }, 3],
  ['macos', { width: 1440, height: 900 }, 2],
] as const) {
  test.describe(platform, () => {
    test.use({ storageState: STATE, viewport, deviceScaleFactor: scale });

    test(`${platform} story`, async ({ page }) => {
      await syntheticBike(page, 150);
      await connect(page);
      await recordRide(page, 100);
      await expectChart(page, 'power', ['humanPowerW', 'motorInputPowerW'], true);
      await expectChart(page, 'cadenceRpm', ['cadenceRpm'], true);
      if (platform === 'web') {
        await scrollTo(page, page.getByTestId('monitor-numbers').filter({ visible: true }));
        await expect(page.getByTestId('monitor-chart-power').filter({ visible: true })).toBeInViewport({ ratio: 1 });
        await expect(page.getByTestId('monitor-chart-cadenceRpm').filter({ visible: true })).toBeInViewport({
          ratio: 1,
        });
      } else {
        await scrollTo(page, page.getByTestId('ride-controls').filter({ visible: true }));
        const charts = page.getByTestId('monitor-charts').filter({ visible: true });
        for (const [group, title, lineCount] of [
          ['power', 'Power', 2],
          ['cadenceRpm', 'Cadence', 1],
        ] as const) {
          await expect(charts.getByText(title, { exact: true }).first()).toBeInViewport({ ratio: 1 });
          const lines = page
            .getByTestId(`monitor-chart-${group}`)
            .filter({ visible: true })
            .locator('svg path[stroke][fill="none"]');
          await expect(lines).toHaveCount(lineCount);
          for (const line of await lines.all()) {
            await expect(line).toHaveAttribute('d', /L/);
            await expect(line).toBeInViewport();
          }
        }
        await expect(page.getByTestId('monitor-view-picker').filter({ visible: true })).toBeInViewport({ ratio: 1 });
        await expect(
          page
            .getByRole('radiogroup', {
              name: 'Maximum chart columns, automatically reduced to fit the window',
              exact: true,
            })
            .filter({ visible: true }),
        ).toBeInViewport({ ratio: 1 });
      }
      const controls = page.getByTestId('ride-controls').filter({ visible: true });
      await expect(controls).toBeInViewport({ ratio: 1 });
      await expect(controls.getByText(/^\d+:\d{2}(?::\d{2})?$/)).toBeInViewport({ ratio: 1 });
      await expect(controls.getByTestId('ride-state-badge')).toHaveText('Running');
      for (const name of ['Pause', 'Lap', 'Finish']) {
        const button = controls.getByRole('button', { name, exact: true });
        await expect(button).toBeEnabled();
        await expect(button).toBeInViewport({ ratio: 1 });
      }
      await capture(page, platform, '01-live-recording', controls);
      await saveRide(page);

      await page.getByRole('link', { name: 'History', exact: true }).click();
      const rows = page.getByRole('button', { name: /^Open ride/ });
      await expect(rows).toHaveCount(3);
      for (const row of await rows.all()) {
        await expect(row).toBeEnabled();
        await expect(row).toContainText(/Recorded · Outdoor · \d{2}:\d{2}/);
      }
      await expect(page.getByText('Loading saved rides…', { exact: true })).toHaveCount(0);
      await capture(page, platform, '02-history', page.getByTestId('history-toolbar'));

      await rows.last().click();
      await expect(page).toHaveURL(/\/sessions\/ride\?id=/);
      const summary = page.getByTestId('ride-summary');
      await expect(summary).toContainText(/Avg power\d+W/);
      await expect(summary).toContainText(/Avg cadence\d+rpm/);
      await expect(page.getByRole('button', { name: 'Export FIT', exact: true })).toBeEnabled();
      await expectChart(page, 'power', ['humanPowerW', 'motorInputPowerW']);
      await expectChart(page, 'cadenceRpm', ['cadenceRpm']);
      await summary.evaluate(node => {
        for (let parent = node.parentElement; parent; parent = parent.parentElement)
          if (/(auto|scroll)/.test(getComputedStyle(parent).overflowY) && parent.scrollHeight > parent.clientHeight) {
            parent.scrollTop = 0;
            return;
          }
      });
      await capture(page, platform, '03-ride-summary', summary);

      await scrollTo(
        page,
        platform === 'web'
          ? page.getByTestId('monitor-numbers').filter({ visible: true })
          : page.getByRole('heading', { name: 'Chart inspection', exact: true }),
        platform === 'web' ? 16 : 0,
      );
      await inspect(page, 'power', 'humanPowerW', 0.42);
      if (platform === 'web') {
        await expect(page.getByTestId('monitor-chart-cadenceRpm').filter({ visible: true })).toBeInViewport({
          ratio: 1,
        });
      }
      await capture(
        page,
        platform,
        '04-effort-analysis',
        page.getByTestId('monitor-readout-humanPowerW').filter({ visible: true }),
      );

      await chooseView(page, 'Battery');
      await expectChart(page, 'batteryVoltageV', ['batteryVoltageV']);
      await expectChart(page, 'current', ['batteryCurrentA']);
      await expectChart(page, 'power', ['motorInputPowerW']);
      if (platform === 'web') {
        await page.getByTestId('monitor-edit').filter({ visible: true }).click();
        const editor = page.getByTestId('monitor-editor-dialog');
        await editor.getByRole('tab', { name: 'Graphs', exact: true }).click();
        for (const name of ['Battery current', 'Battery input power']) {
          const metric = editor.getByRole('checkbox', { name, exact: true });
          await expect(metric).toHaveAttribute('aria-checked', 'true');
          await metric.click();
          await expect(metric).toHaveAttribute('aria-checked', 'false');
        }
        await expect(editor.getByTestId('monitor-selected-metrics')).toContainText('Battery voltage');
        await editor.getByRole('button', { name: 'Done', exact: true }).click();
        await expect(editor).toHaveCount(0);
        await expect(page.getByTestId('monitor-chart-current').filter({ visible: true })).toHaveCount(0);
        await expect(page.getByTestId('monitor-chart-power').filter({ visible: true })).toHaveCount(0);
        for (const metric of ['batteryVoltageV', 'batteryCurrentA', 'motorInputPowerW']) {
          await expect(page.getByTestId(`monitor-number-${metric}`).filter({ visible: true })).toContainText(/\d/);
        }
      }
      await scrollTo(
        page,
        platform === 'macos'
          ? page.getByRole('heading', { name: 'Chart inspection', exact: true })
          : page.getByTestId('monitor-header').filter({ visible: true }),
      );
      await inspect(page, 'batteryVoltageV', 'batteryVoltageV', 0.42);
      if (platform === 'macos') {
        await expect(page.getByTestId('monitor-chart-current').filter({ visible: true })).toBeInViewport({ ratio: 1 });
        await expect(page.getByTestId('monitor-chart-power').filter({ visible: true })).toBeInViewport({ ratio: 1 });
      }
      await capture(
        page,
        platform,
        '05-battery',
        page.getByTestId('monitor-readout-batteryVoltageV').filter({ visible: true }),
      );

      await chooseView(page, 'Temperature');
      await expectChart(page, 'temperature', ['motorTempC', 'controllerTempC']);
      await scrollTo(
        page,
        platform === 'macos'
          ? page.getByRole('heading', { name: 'Chart inspection', exact: true })
          : page.getByTestId('monitor-header').filter({ visible: true }),
      );
      await capture(page, platform, '06-temperature', page.getByTestId('monitor-charts').filter({ visible: true }));

      await chooseView(page, 'Ride');
      await page.getByTestId('monitor-edit').filter({ visible: true }).click();
      const editor = page.getByTestId('monitor-editor-dialog');
      await expect(editor.getByRole('heading', { name: 'Ride layout', exact: true })).toBeVisible();
      await editor.getByRole('tab', { name: 'Numbers', exact: true }).click();
      await expect(editor.getByRole('textbox', { name: 'Search metrics', exact: true })).toBeVisible();
      await expect(editor.getByTestId('monitor-selected-metrics')).toContainText('Rider power');
      await expect(editor.getByTestId('monitor-selected-metrics')).toContainText('Controller speed');
      await expect(editor.getByLabel('Reorder Cadence', { exact: true })).toBeVisible();
      await capture(page, platform, '07-custom-dashboard', editor);
      await editor.getByRole('button', { name: 'Done', exact: true }).click();
      await expect(editor).toHaveCount(0);

      await page.getByRole('link', { name: 'Settings', exact: true }).click();
      await expect(page.getByRole('heading', { name: 'Settings' })).toBeVisible();
      await expect(page.getByTestId('settings-loading')).toHaveCount(0);
      await expect(page.getByTestId('settings-units')).toHaveAccessibleName('Units, Metric');
      await expect(page.getByTestId('settings-temperature')).toHaveAccessibleName('Temperature, °C');
      await expect(page.getByTestId('settings-distance')).toHaveAccessibleName('Distance source, Auto');
      await expect(page.getByTestId('settings-sampleHz')).toHaveAccessibleName('Bike sample rate, 2 Hz');
      await expect(page.getByRole('switch', { name: 'Record GPS route', exact: true })).toHaveAttribute(
        'aria-checked',
        'false',
      );
      await expect(page.getByTestId('settings-storage')).toContainText(
        /Protected from automatic browser cleanup|The browser may clear saved rides/,
      );
      await capture(page, platform, '08-settings', page.getByTestId('settings-screen'));
    });
  });
}
