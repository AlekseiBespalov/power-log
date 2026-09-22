import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { BrowserAdapter } from '../../src/services/device.web';
import { crc16Xmodem, UART_NOTIFY, UART_WRITE } from '../../src/core/protocol';
import fixture from '../fixtures/protocol.json';
import { hex, toHex } from '../core/helpers';

function deferred<T>() {
  let resolve!: (value: T) => void; let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((success, failure) => { resolve = success; reject = failure; });
  return { promise, resolve, reject };
}
async function flush(): Promise<void> { for (let turn = 0; turn < 24; turn += 1) await Promise.resolve(); }

/** Synthetic peripheral. Tests exercise adapter lifecycle, not the platform Bluetooth implementation. */
class FakeCharacteristic extends EventTarget {
  value?: DataView;
  properties = { writeWithoutResponse: true };
  onWrite?: (bytes: Uint8Array) => void;
  startNotifications = vi.fn(async () => this);
  writeValueWithoutResponse = vi.fn(async (bytes: Uint8Array) => { this.onWrite?.(bytes); });
  writeValueWithResponse = vi.fn(async (bytes: Uint8Array) => { this.onWrite?.(bytes); });
  notify(bytes: Uint8Array): void {
    this.value = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
    this.dispatchEvent(new Event('characteristicvaluechanged'));
  }
  identity(command = 111, model?: string): void {
    const payload = model ? Uint8Array.of(command, 5, 3, ...Array.from(`${model} 20250604 `, char => char.charCodeAt(0)), 0x80, 0xff, 0) : hex(fixture.identity.payloadHex); payload[0] = command;
    const crc = crc16Xmodem(payload);
    this.notify(Uint8Array.of(2, payload.length, ...payload, crc >> 8, crc & 255, 3));
  }
  telemetry(): void { this.notify(hex(fixture.telemetry[3]!.frameHex!)); }
}
class FakeService {
  writer = new FakeCharacteristic(); reader = new FakeCharacteristic();
  getCharacteristic = vi.fn(async (uuid: string) => {
    if (uuid === UART_WRITE) return this.writer;
    if (uuid === UART_NOTIFY) return this.reader;
    throw new Error('Unexpected characteristic');
  });
}
class FakeGatt {
  connected = false;
  service = new FakeService();
  constructor(private readonly device: FakeDevice) {}
  connect = vi.fn(async () => { this.connected = true; return this; });
  getPrimaryService = vi.fn(async () => this.service);
  disconnect = vi.fn(() => { this.connected = false; this.device.dispatchEvent(new Event('gattserverdisconnected')); });
}
class FakeDevice extends EventTarget {
  id = 'synthetic-test-device'; name = 'CYC synthetic test device';
  gatt = new FakeGatt(this);
  asBluetooth(): BluetoothDevice { return this as unknown as BluetoothDevice; }
}

const adapters: BrowserAdapter[] = [];
function harness() {
  const device = new FakeDevice(); const clock = { seconds: 0 };
  const adapter = new BrowserAdapter(() => clock.seconds); adapters.push(adapter);
  const events = { device: vi.fn(), state: vi.fn(), sample: vi.fn() }; adapter.subscribe(events);
  const requestDevice = vi.fn(async () => device.asBluetooth());
  vi.stubGlobal('navigator', { bluetooth: { requestDevice } });
  return { adapter, device, clock, events, requestDevice, service: device.gatt.service };
}
function replyAutomatically(service: FakeService, identityCommand = 111): void {
  service.writer.onWrite = bytes => {
    if (bytes[2] === 111) service.reader.identity(identityCommand);
    else if (bytes[2] === 50) service.reader.telemetry();
    else throw new Error('Adapter sent a forbidden command');
  };
}

beforeEach(() => { vi.useFakeTimers(); });
afterEach(async () => {
  for (const adapter of adapters.splice(0)) await adapter.disconnect();
  vi.clearAllTimers(); vi.useRealTimers(); vi.unstubAllGlobals();
});

describe('foreground browser BLE lifecycle', () => {
  it('keeps the admitted sample rate on an active same-bike reconnect and uses the new default after release', async () => {
    const h = harness(); replyAutomatically(h.service);
    await h.adapter.startScan(); await h.adapter.connect({ deviceId: h.device.id, hz: 4 }); await flush();
    h.adapter.setWorkoutOwner(h.device.id);
    await expect(h.adapter.setSampleRate(8)).rejects.toThrow('Finish the ride');
    h.device.gatt.disconnect(); await h.adapter.connect({ deviceId: h.device.id, hz: 8 }); await flush();
    const before = h.events.sample.mock.calls.length;
    await vi.advanceTimersByTimeAsync(249); expect(h.events.sample).toHaveBeenCalledTimes(before);
    await vi.advanceTimersByTimeAsync(1); expect(h.events.sample).toHaveBeenCalledTimes(before + 1);
    h.adapter.setWorkoutOwner(null); await h.adapter.disconnect(); await h.adapter.connect({ deviceId: h.device.id, hz: 8 }); await flush();
    const next = h.events.sample.mock.calls.length;
    await vi.advanceTimersByTimeAsync(124); expect(h.events.sample).toHaveBeenCalledTimes(next);
    await vi.advanceTimersByTimeAsync(1); expect(h.events.sample).toHaveBeenCalledTimes(next + 1);
  });

  it.each([0, 111])('accepts identity response %i and sends only allowlisted reads', async command => {
    const h = harness(); replyAutomatically(h.service, command);
    const selection = h.adapter.startScan();
    expect(h.requestDevice).toHaveBeenCalledTimes(1); // Called synchronously within the user's gesture.
    await selection; await h.adapter.connect({ deviceId: h.device.id, hz: 2 }); await flush();
    expect((await h.adapter.getState()).status).toBe('connected');
    expect(h.events.sample).toHaveBeenCalledTimes(1);
    expect(h.service.writer.writeValueWithoutResponse.mock.calls.map(([bytes]) => toHex(bytes))).toEqual([fixture.requests.identity, fixture.requests.selective]);
  });

  it('identifies X12 on this connection, retains its scan label, and uses only approved reads', async () => {
    const h = harness();
    h.service.writer.onWrite = bytes => {
      if (bytes[2] === 111) h.service.reader.identity(111, 'X12');
      else if (bytes[2] === 50) h.service.reader.telemetry();
      else throw new Error('Forbidden controller request');
    };
    await h.adapter.startScan(); await h.adapter.connect({ deviceId: h.device.id, hz: 2 }); await flush();
    expect(await h.adapter.getState()).toMatchObject({ status: 'connected', deviceId: h.device.id, controllerModel: 'X12', firmwareLabel: '20250604' });
    expect(h.events.sample).toHaveBeenCalledTimes(1);
    await h.adapter.disconnect(); await h.adapter.startScan();
    expect(h.events.device.mock.lastCall![0]).toMatchObject({ id: h.device.id, controllerModel: 'X12' });
    // Remembered display metadata must never bypass a fresh identity handshake.
    h.service.writer.onWrite = () => h.service.reader.identity(111, 'X120');
    await expect(h.adapter.connect({ deviceId: h.device.id, hz: 2 })).rejects.toThrow('Unsupported controller');
    expect((await h.adapter.getState()).status).toBe('error');
    expect(h.events.sample).toHaveBeenCalledTimes(1);
  });

  it('uses write-with-response when the characteristic requires it', async () => {
    const h = harness(); h.service.writer.properties.writeWithoutResponse = false; replyAutomatically(h.service);
    await h.adapter.startScan(); await h.adapter.connect({ deviceId: h.device.id, hz: 2 }); await flush();
    expect(h.service.writer.writeValueWithResponse).toHaveBeenCalledTimes(2);
    expect(h.service.writer.writeValueWithoutResponse).not.toHaveBeenCalled();
  });

  it('ignores a chooser result after Stop and never replaces a newer selected device', async () => {
    const h = harness(); const oldChoice = deferred<BluetoothDevice>();
    h.requestDevice.mockReturnValueOnce(oldChoice.promise);
    const oldScan = h.adapter.startScan(); await h.adapter.stopScan();
    const newer = new FakeDevice(); newer.id = 'synthetic-new-device';
    h.requestDevice.mockResolvedValueOnce(newer.asBluetooth());
    await h.adapter.startScan(); oldChoice.resolve(h.device.asBluetooth()); await oldScan;
    expect(h.events.device).toHaveBeenCalledTimes(1);
    expect(h.events.device.mock.calls[0]![0].id).toBe(newer.id);
    await expect(h.adapter.connect({ deviceId: h.device.id, hz: 2 })).rejects.toThrow('Choose');
  });

  it('ignores a rejected chooser from an older generation', async () => {
    const h = harness(); const oldChoice = deferred<BluetoothDevice>();
    h.requestDevice.mockReturnValueOnce(oldChoice.promise);
    const oldScan = h.adapter.startScan(); await h.adapter.stopScan();
    await h.adapter.startScan(); replyAutomatically(h.service);
    await h.adapter.connect({ deviceId: h.device.id, hz: 2 });
    oldChoice.reject(new Error('Old picker cancelled')); await oldScan;
    expect((await h.adapter.getState()).status).toBe('connected');
  });

  it('rejects a response arriving past the monotonic deadline before the delayed timeout callback runs', async () => {
    const h = harness(); await h.adapter.startScan();
    const connecting = h.adapter.connect({ deviceId: h.device.id, hz: 2 }).catch(error => error as Error);
    await flush(); expect(h.service.writer.writeValueWithoutResponse).toHaveBeenCalledTimes(1);
    h.clock.seconds = 2.501; // Simulate a suspended tab without running its queued timer.
    h.service.reader.identity(); await flush();
    expect(await connecting).toBeInstanceOf(Error);
    expect((await h.adapter.getState()).status).toBe('error');
    expect(h.service.writer.writeValueWithoutResponse).toHaveBeenCalledTimes(1);
    expect(h.events.sample).not.toHaveBeenCalled();
  });

  it('drops late telemetry instead of publishing it as a fresh sample', async () => {
    const h = harness(); h.service.writer.onWrite = bytes => { if (bytes[2] === 111) h.service.reader.identity(); };
    await h.adapter.startScan(); await h.adapter.connect({ deviceId: h.device.id, hz: 2 }); await flush();
    h.clock.seconds = 3; h.service.reader.telemetry(); await flush();
    expect(h.events.sample).not.toHaveBeenCalled(); expect((await h.adapter.getState()).status).toBe('error');
  });

  it('applies the deadline if an on-time response waits behind a delayed write continuation', async () => {
    const h = harness(); const write = deferred<void>();
    h.service.writer.writeValueWithoutResponse.mockImplementationOnce(async () => { h.service.reader.identity(); await write.promise; });
    await h.adapter.startScan();
    const connecting = h.adapter.connect({ deviceId: h.device.id, hz: 2 }).catch(error => error as Error);
    await flush(); h.clock.seconds = 3; write.resolve(); await flush();
    expect(await connecting).toBeInstanceOf(Error); expect((await h.adapter.getState()).status).toBe('error');
    expect(h.events.sample).not.toHaveBeenCalled();
  });

  it('times out a hanging write even if its response already arrived', async () => {
    const h = harness(); const write = deferred<void>();
    h.service.writer.writeValueWithoutResponse.mockImplementationOnce(async () => { h.service.reader.identity(); await write.promise; });
    await h.adapter.startScan();
    const connecting = h.adapter.connect({ deviceId: h.device.id, hz: 2 }).catch(error => error as Error);
    await flush(); h.clock.seconds = 2.5; await vi.advanceTimersByTimeAsync(2500);
    expect(await connecting).toBeInstanceOf(Error); expect((await h.adapter.getState()).status).toBe('error');
    write.resolve(); await flush();
    expect(h.events.sample).not.toHaveBeenCalled();
  });

  it.each(['gatt', 'service', 'writer', 'reader', 'notifications'] as const)('does not issue writes after cancellation during %s discovery', async stage => {
    const h = harness(); const gate = deferred<void>();
    if (stage === 'gatt') h.device.gatt.connect.mockImplementationOnce(async () => { await gate.promise; return h.device.gatt; });
    if (stage === 'service') h.device.gatt.getPrimaryService.mockImplementationOnce(async () => { await gate.promise; return h.service; });
    if (stage === 'writer' || stage === 'reader') h.service.getCharacteristic.mockImplementation(async uuid => {
      if ((stage === 'writer' && uuid === UART_WRITE) || (stage === 'reader' && uuid === UART_NOTIFY)) await gate.promise;
      return uuid === UART_WRITE ? h.service.writer : h.service.reader;
    });
    if (stage === 'notifications') h.service.reader.startNotifications.mockImplementationOnce(async () => { await gate.promise; return h.service.reader; });
    await h.adapter.startScan(); const connecting = h.adapter.connect({ deviceId: h.device.id, hz: 2 });
    await flush(); await h.adapter.disconnect(); gate.resolve(); await connecting; await flush();
    expect(h.service.writer.writeValueWithoutResponse).not.toHaveBeenCalled();
    expect((await h.adapter.getState()).status).toBe('idle');
    expect(h.events.sample).not.toHaveBeenCalled();
  });

  it('does not let an old delayed setup failure disconnect a successful newer connection', async () => {
    const h = harness(); const oldService = deferred<FakeService>();
    h.device.gatt.getPrimaryService.mockReturnValueOnce(oldService.promise); replyAutomatically(h.service);
    await h.adapter.startScan(); const oldConnect = h.adapter.connect({ deviceId: h.device.id, hz: 2 }); await flush();
    await h.adapter.connect({ deviceId: h.device.id, hz: 2 }); await flush();
    const disconnects = h.device.gatt.disconnect.mock.calls.length;
    oldService.reject(new Error('Stale service discovery failed')); await oldConnect; await flush();
    expect(h.device.gatt.disconnect).toHaveBeenCalledTimes(disconnects);
    expect((await h.adapter.getState()).status).toBe('connected'); expect(h.events.sample).toHaveBeenCalledTimes(1);
    h.clock.seconds = 0.5; await vi.advanceTimersByTimeAsync(500);
    expect(h.events.sample).toHaveBeenCalledTimes(2);
  });

  it('does not let a late connect completion close the newer connection on the same GATT device', async () => {
    const h = harness(); const oldServer = deferred<FakeGatt>();
    h.device.gatt.connect.mockReturnValueOnce(oldServer.promise); replyAutomatically(h.service);
    await h.adapter.startScan(); const oldConnect = h.adapter.connect({ deviceId: h.device.id, hz: 2 }); await flush();
    await h.adapter.connect({ deviceId: h.device.id, hz: 2 }); await flush();
    const disconnects = h.device.gatt.disconnect.mock.calls.length;
    oldServer.resolve(h.device.gatt); await oldConnect;
    expect(h.device.gatt.disconnect).toHaveBeenCalledTimes(disconnects);
    expect(h.device.gatt.getPrimaryService).toHaveBeenCalledTimes(1);
    expect((await h.adapter.getState()).status).toBe('connected');
  });

  it('cancels a pending write immediately and ignores its later rejection', async () => {
    const h = harness(); const oldWrite = deferred<void>();
    h.service.writer.writeValueWithoutResponse.mockImplementationOnce(() => oldWrite.promise);
    await h.adapter.startScan(); const oldConnect = h.adapter.connect({ deviceId: h.device.id, hz: 2 }); await flush();
    await h.adapter.disconnect(); await oldConnect;
    replyAutomatically(h.service); await h.adapter.connect({ deviceId: h.device.id, hz: 2 }); await flush();
    const disconnects = h.device.gatt.disconnect.mock.calls.length;
    oldWrite.reject(new Error('Old write rejected')); await flush();
    expect(h.device.gatt.disconnect).toHaveBeenCalledTimes(disconnects);
    expect((await h.adapter.getState()).status).toBe('connected');
  });

  it('clears polling and event listeners on a real disconnect without accepting stale notifications', async () => {
    const h = harness(); replyAutomatically(h.service);
    await h.adapter.startScan(); await h.adapter.connect({ deviceId: h.device.id, hz: 2 }); await flush();
    expect(h.events.sample).toHaveBeenCalledTimes(1);
    h.device.gatt.disconnect(); h.service.reader.telemetry(); await vi.advanceTimersByTimeAsync(1000);
    expect((await h.adapter.getState()).status).toBe('error');
    expect(h.events.sample).toHaveBeenCalledTimes(1);
    expect(h.service.writer.writeValueWithoutResponse).toHaveBeenCalledTimes(2);
  });

  it('ignores an old queued disconnect event after GATT has already reconnected', async () => {
    const h = harness(); replyAutomatically(h.service);
    await h.adapter.startScan(); await h.adapter.connect({ deviceId: h.device.id, hz: 2 }); await flush();
    expect(h.device.gatt.connected).toBe(true);
    h.device.dispatchEvent(new Event('gattserverdisconnected'));
    expect((await h.adapter.getState()).status).toBe('connected');
    h.clock.seconds = 0.5; await vi.advanceTimersByTimeAsync(500);
    expect(h.events.sample).toHaveBeenCalledTimes(2);
  });
});
