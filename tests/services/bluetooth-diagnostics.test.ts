import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { adapters, flush, harness } from '../support/browser-telemetry';
import { framePayload } from '../support/controller-frames';
import { hex } from '../core/helpers';

beforeEach(() => vi.useFakeTimers());
afterEach(async () => {
  for (const adapter of adapters.splice(0)) await adapter.disconnect();
  vi.clearAllTimers();
  vi.useRealTimers();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

it('exports the exact fragmented rejected reply only after explicit capture', async () => {
  const h = harness();
  const trace = h.adapter.bluetoothDiagnostics;
  expect((await trace.getBluetoothTrace()).eventCount).toBe(0);
  await trace.startBluetoothTrace();
  const frame = framePayload(hex('6f0503583100'));
  h.service.writer.onWrite = () => {
    h.service.reader.notify(frame.slice(0, 4));
    h.service.reader.notify(frame.slice(4));
  };
  await h.adapter.startScan();
  await h.adapter.connect({ deviceId: h.device.id, hz: 2 });
  await flush();
  await trace.stopBluetoothTrace();
  const report = JSON.parse(await trace.readBluetoothTrace());
  const received = report.events.filter((row: { event: string }) => row.event === 'notification');
  expect(received.map((row: { hex: string }) => row.hex).join('')).toBe(Buffer.from(frame).toString('hex'));
  expect(report.events).toContainEqual(
    expect.objectContaining({ event: 'rejected', detail: 'request=identity; reason=identity' }),
  );
  expect(h.events.sample).not.toHaveBeenCalled();
  expect(h.service.writer.writeValueWithoutResponse).toHaveBeenCalledOnce();
  expect((await h.adapter.getState()).status).toBe('error');
  await trace.clearBluetoothTrace();
  expect((await trace.getBluetoothTrace()).eventCount).toBe(0);
});
