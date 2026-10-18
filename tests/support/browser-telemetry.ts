import { vi } from 'vitest';
import { BrowserAdapter } from '../../src/services/device.web';
import { UART_NOTIFY, UART_WRITE } from '../../src/services/controller-gatt';
import fixture from '../fixtures/protocol.json';
import { hex } from '../core/helpers';
import { framePayload } from './controller-frames';
export function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((success, failure) => {
    resolve = success;
    reject = failure;
  });
  return { promise, resolve, reject };
}
export async function flush(): Promise<void> {
  for (let turn = 0; turn < 64; turn += 1) await Promise.resolve();
}

export class FakeCharacteristic extends EventTarget {
  value?: DataView;
  properties = { writeWithoutResponse: true };
  onWrite?: (bytes: Uint8Array) => void;
  startNotifications = vi.fn(async () => this);
  writeValueWithoutResponse = vi.fn(async (bytes: Uint8Array) => {
    this.onWrite?.(bytes);
  });
  writeValueWithResponse = vi.fn(async (bytes: Uint8Array) => {
    this.onWrite?.(bytes);
  });
  notify(bytes: Uint8Array): void {
    this.value = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
    this.dispatchEvent(new Event('characteristicvaluechanged'));
  }
  identity(command = 111, model?: string): void {
    const payload = model
      ? Uint8Array.of(command, 5, 3, ...Array.from(`${model} 20250604 `, char => char.charCodeAt(0)), 0x80, 0xff, 0)
      : hex(fixture.identity.payloadHex);
    payload[0] = command;
    this.notify(framePayload(payload));
  }
  telemetry(): void {
    this.notify(hex(fixture.telemetry[3]!.frameHex!));
  }
}
export class FakeService {
  writer = new FakeCharacteristic();
  reader = new FakeCharacteristic();
  getCharacteristic = vi.fn(async (uuid: string) => {
    if (uuid === UART_WRITE) return this.writer;
    if (uuid === UART_NOTIFY) return this.reader;
    throw new Error('Unexpected characteristic');
  });
}
export class FakeGatt {
  connected = false;
  automaticDisconnectEvents = true;
  private disconnectEvents: Event[] = [];
  service = new FakeService();
  constructor(private readonly device: FakeDevice) {}
  connect = vi.fn(async () => {
    this.connected = true;
    return this;
  });
  getPrimaryService = vi.fn(async () => this.service);
  disconnect = vi.fn(() => {
    if (!this.connected) return;
    this.connected = false;
    this.disconnectEvents.push(new Event('gattserverdisconnected'));
    if (this.automaticDisconnectEvents) void Promise.resolve().then(() => this.deliverDisconnect());
  });
  deliverDisconnect(): void {
    const event = this.disconnectEvents.shift();
    if (!event) throw new Error('No pending disconnect completion');
    this.device.dispatchEvent(event);
  }
}
export class FakeDevice extends EventTarget {
  id = 'synthetic-test-device';
  name = 'CYC synthetic test device';
  gatt = new FakeGatt(this);
  asBluetooth(): BluetoothDevice {
    return this as unknown as BluetoothDevice;
  }
}

export const adapters: BrowserAdapter[] = [];
export function harness() {
  const device = new FakeDevice();
  const origin = performance.now();
  let injectedSeconds = 0;
  const clock = {
    get seconds() {
      return Math.max(injectedSeconds, (performance.now() - origin) / 1000);
    },
    set seconds(value: number) {
      injectedSeconds = value;
    },
  };
  const adapter = new BrowserAdapter(() => clock.seconds);
  adapters.push(adapter);
  const events = { device: vi.fn(), state: vi.fn(), sample: vi.fn() };
  adapter.subscribe(events);
  const requestDevice = vi.fn(async () => device.asBluetooth());
  vi.stubGlobal('navigator', { bluetooth: { requestDevice } });
  return { adapter, device, clock, events, requestDevice, service: device.gatt.service };
}
export function replyAutomatically(service: FakeService, identityCommand = 111): void {
  service.writer.onWrite = bytes => {
    if (bytes[2] === 111) service.reader.identity(identityCommand);
    else if (bytes[2] === 50) service.reader.telemetry();
    else throw new Error('Adapter sent a forbidden command');
  };
}
