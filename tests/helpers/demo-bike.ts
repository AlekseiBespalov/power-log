import { readFileSync } from 'node:fs';
import type { Page } from '@playwright/test';
import { framePayload } from '../support/controller-frames';

const hex = (bytes: Uint8Array) => Buffer.from(bytes).toString('hex');
const frame = (payloadHex: string) => hex(framePayload(Uint8Array.from(Buffer.from(payloadHex, 'hex'))));
const protocol = JSON.parse(readFileSync('tests/fixtures/protocol.json', 'utf8')) as {
  requests: { identity: string; selective: string };
  telemetry: { name: string; payloadHex: string }[];
};
const identity = JSON.parse(readFileSync('tests/fixtures/contract/telemetry/cyc/identity.json', 'utf8')) as {
  cases: { payloadHex: string; valid: boolean }[];
};

// The demo bike's answers: the recorded identity and live telemetry reply frames, 20 ms after each request.
const demoBike = {
  identityRequest: protocol.requests.identity,
  telemetryRequest: protocol.requests.selective,
  identity: frame(identity.cases.find(item => item.valid)!.payloadHex),
  telemetry: protocol.telemetry.filter(item => item.name.startsWith('live-')).map(item => frame(item.payloadHex)),
  replyDelayMs: 20,
};

declare global {
  interface Window {
    powerLogChooser?: { activation: boolean; options: unknown };
  }
}

export async function fakeBluetooth(page: Page) {
  await page.addInitScript(bike => {
    const service = '6e400001-b5a3-f393-e0a9-e50e24dcca9e';
    const write = '6e400002-b5a3-f393-e0a9-e50e24dcca9e';
    const notify = '6e400003-b5a3-f393-e0a9-e50e24dcca9e';
    const bytes = (text: string) => Uint8Array.from(text.match(/../g)!.map(pair => parseInt(pair, 16)));
    const same = (a: Uint8Array, b: Uint8Array) =>
      a.length === b.length && a.every((value, index) => value === b[index]);
    const identityRequest = bytes(bike.identityRequest);
    const telemetryRequest = bytes(bike.telemetryRequest);
    const telemetry = bike.telemetry.map(bytes);
    let next = 0;
    const missing = (what: string) => Promise.reject(new DOMException(`No ${what}.`, 'NotFoundError'));
    const properties = (writes: boolean) => ({
      broadcast: false,
      read: false,
      writeWithoutResponse: writes,
      write: writes,
      notify: !writes,
      indicate: false,
      authenticatedSignedWrites: false,
      reliableWrite: false,
      writableAuxiliaries: false,
    });
    const notifier = Object.assign(new EventTarget(), {
      uuid: notify,
      properties: properties(false),
      value: null as DataView | null,
      startNotifications: async () => notifier,
    });
    const writer = Object.assign(new EventTarget(), {
      uuid: write,
      properties: properties(true),
      writeValueWithoutResponse: async (value: ArrayBufferView) => {
        const sent = new Uint8Array(value.buffer.slice(value.byteOffset, value.byteOffset + value.byteLength));
        const reply = same(sent, identityRequest)
          ? bytes(bike.identity)
          : same(sent, telemetryRequest)
            ? telemetry[next++ % telemetry.length]!
            : null;
        if (!reply) return;
        setTimeout(() => {
          notifier.value = new DataView(reply.slice().buffer);
          notifier.dispatchEvent(new Event('characteristicvaluechanged'));
        }, bike.replyDelayMs);
      },
    });
    const uart = {
      uuid: service,
      getCharacteristic: (uuid: string) =>
        uuid === write ? Promise.resolve(writer) : uuid === notify ? Promise.resolve(notifier) : missing(uuid),
      getCharacteristics: async () => [writer, notifier],
    };
    const device: EventTarget & { id: string; name: string; gatt: Record<string, unknown> } = Object.assign(
      new EventTarget(),
      {
        id: 'power-log-demo-bike',
        name: 'CYCMOTOR',
        gatt: {
          connected: false,
          connect: async () => {
            device.gatt.connected = true;
            return device.gatt;
          },
          disconnect: () => {
            if (!device.gatt.connected) return;
            device.gatt.connected = false;
            setTimeout(() => device.dispatchEvent(new Event('gattserverdisconnected')));
          },
          getPrimaryService: (uuid: string) => (uuid === service ? Promise.resolve(uart) : missing(uuid)),
        },
      },
    );
    const bluetooth = Object.assign(new EventTarget(), {
      requestDevice: (options: unknown) => {
        window.powerLogChooser = { activation: navigator.userActivation.isActive, options };
        return Promise.resolve(device);
      },
    });
    Object.defineProperty(navigator, 'bluetooth', { configurable: true, value: bluetooth });
  }, demoBike);
}
