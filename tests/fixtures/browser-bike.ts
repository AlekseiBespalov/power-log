import type { Page } from '@playwright/test';
import { crc16Xmodem, UART_WRITE } from '../../src/core/protocol';
import fixture from './protocol.json';

declare global {
  interface Window {
    testBike: { available: boolean; picks: number; connects: number; drop: () => void };
  }
}

export async function syntheticBike(page: Page) {
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
    const controls = { available: true, picks: 0, connects: 0, drop: () => device.gatt.disconnect() };
    const device = Object.assign(new EventTarget(), { id: 'synthetic-e2e-bike', name: 'CYC test bike', gatt: {
      connected: false,
      async connect() {
        controls.connects++;
        if (!controls.available) throw new DOMException('Bike unavailable', 'NetworkError');
        this.connected = true; return this;
      },
      async getPrimaryService() { return { getCharacteristic: async (id: string) => id === writerID ? writer : reader }; },
      disconnect() { this.connected = false; device.dispatchEvent(new Event('gattserverdisconnected')); },
    } });
    window.testBike = controls;
    Object.defineProperty(navigator, 'bluetooth', { configurable: true, value: { requestDevice: async () => { controls.picks++; return device; } } });
  }, { identity: [2, payload.length, ...payload, crc >> 8, crc & 255, 3], telemetry: [...Buffer.from(fixture.telemetry[3]!.frameHex!, 'hex')], writerID: UART_WRITE });
}
