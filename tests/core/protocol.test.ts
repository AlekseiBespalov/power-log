import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import protocol from '../fixtures/protocol.json';
import framing from '../fixtures/contract/telemetry/cyc/framing.json';
import identities from '../fixtures/contract/telemetry/cyc/identity.json';
import wireMetrics from '../fixtures/contract/telemetry/cyc/wire-metrics.json';
import { adapters, flush, harness } from '../support/browser-telemetry';
import { framePayload } from '../support/controller-frames';
import { hex, toHex } from './helpers';
import { controllerCrc } from '../../shared/js';

beforeEach(() => vi.useFakeTimers());
afterEach(async () => {
  for (const adapter of adapters.splice(0)) await adapter.disconnect();
  vi.clearAllTimers();
  vi.useRealTimers();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

async function connected(
  identity = hex(protocol.identity.payloadHex),
  notifications: Uint8Array[] = [hex(protocol.telemetry[3]!.frameHex!)],
) {
  const h = harness();
  h.service.writer.onWrite = bytes => {
    if (toHex(bytes) === protocol.requests.identity) h.service.reader.notify(framePayload(identity));
    else if (toHex(bytes) === protocol.requests.selective)
      for (const notification of notifications) h.service.reader.notify(notification);
    else throw new Error('Adapter sent a forbidden request');
  };
  await h.adapter.startScan();
  await h.adapter.connect({ deviceId: h.device.id, hz: 2 });
  await flush();
  return h;
}

async function measured(payload: Uint8Array) {
  return connected(hex(protocol.identity.payloadHex), [framePayload(payload)]);
}

describe('controller contract fixtures through the production web adapter', () => {
  it('matches the CRC oracle and writes only the exact two allowlisted requests', async () => {
    expect(controllerCrc(new Int8Array(new TextEncoder().encode(protocol.crc.ascii)))).toBe(protocol.crc.expected);
    const h = await connected();
    expect(h.service.writer.writeValueWithoutResponse.mock.calls.map(([bytes]) => toHex(bytes))).toEqual([
      protocol.requests.identity,
      protocol.requests.selective,
    ]);
  });

  it.each(identities.cases)('admits only the supported public identity: $payloadHex', async row => {
    const payload = hex(protocol.telemetry[3]!.payloadHex);
    new DataView(payload.buffer).setInt32(48, Math.round(row.speedRaw * 100), false);
    const h = await connected(hex(row.payloadHex), [framePayload(payload)]);
    if (row.valid) {
      expect(await h.adapter.getState()).toMatchObject({
        status: 'connected',
        controllerModel: row.model,
        firmwareLabel: row.firmware,
      });
      const sample = h.events.sample.mock.lastCall![0];
      expect(sample.controllerSpeedMps ?? null).toBe(row.speedMps);
      expect(sample.speedRaw).toBe(row.speedRaw);
      expect(Object.keys(sample).some(key => /serial|tail|payload|productString/i.test(key))).toBe(false);
    } else {
      expect((await h.adapter.getState()).status).toBe('error');
      expect(h.events.sample).not.toHaveBeenCalled();
      expect(h.service.writer.writeValueWithoutResponse).toHaveBeenCalledOnce();
    }
  });

  it.each([0, 111])('accepts the legacy identity command %s with an opaque suffix', async command => {
    const payload = Uint8Array.of(
      command,
      5,
      3,
      ...new TextEncoder().encode('X6        20250604 '),
      0x80,
      0xff,
      0,
      0xfe,
    );
    const h = await connected(payload);
    expect(await h.adapter.getState()).toMatchObject({
      status: 'connected',
      controllerModel: 'X6',
      firmwareLabel: '20250604',
    });
  });

  it('retains identity NUL, ASCII grammar and payload length bounds', async () => {
    for (const label of [
      'X1 20250604 ',
      'X60 20250604 ',
      'X120 20250604 ',
      ' X6 20250604 ',
      'X6-Other 20250604 ',
      'X6 20250 ',
      'X6 202506041 ',
      'X6 20250604a ',
      'X6 20250604/extra ',
      'X6 20250604\n',
      'X6\t20250604 ',
      `X6${'A'.repeat(31)} 20250604 `,
      `X6 20250604${'A'.repeat(9)} `,
    ]) {
      const h = await connected(Uint8Array.of(111, 5, 3, ...new TextEncoder().encode(label), 0x80, 0xff, 0));
      expect((await h.adapter.getState()).status, label).toBe('error');
      await h.adapter.disconnect();
    }
    const prefix = new TextEncoder().encode('X6 20250604 ');
    const accepted = Uint8Array.of(111, 5, 3, ...prefix, ...new Array<number>(128 - prefix.length).fill(0xff), 0);
    const good = await connected(accepted);
    expect((await good.adapter.getState()).status).toBe('connected');
    await good.adapter.disconnect();
    for (const payload of [
      Uint8Array.of(...accepted.slice(0, -1), 0xff, 0),
      accepted.slice(0, -1),
      new Uint8Array(1025),
    ]) {
      const h = await connected(payload);
      expect(h.events.sample).not.toHaveBeenCalled();
      await h.adapter.disconnect();
    }
  });

  it('reassembles every telemetry fragmentation size without admitting coalesced duplicates', async () => {
    const wire = hex(protocol.telemetry[3]!.frameHex!);
    for (let size = 1; size <= wire.length; size += 1) {
      const chunks: Uint8Array[] = [];
      for (let offset = 0; offset < wire.length; offset += size) chunks.push(wire.slice(offset, offset + size));
      chunks.push(wire);
      const h = await connected(hex(protocol.identity.payloadHex), chunks);
      expect(h.events.sample, `chunk ${size}`).toHaveBeenCalledOnce();
      await h.adapter.disconnect();
    }
    const h = await connected(hex(protocol.identity.payloadHex), [Uint8Array.of(...wire, ...wire)]);
    expect(h.events.sample).toHaveBeenCalledOnce();
  });

  it('resynchronizes after malformed frames, noise and corrupt CRC', async () => {
    const wire = hex(protocol.telemetry[3]!.frameHex!);
    const notifications = [
      hex(framing.noiseHex),
      ...framing.malformedFrameHex.map(hex),
      hex(protocol.framing.corrupted),
      hex(protocol.framing.longFrameHex).slice(0, 70),
      hex(protocol.framing.longFrameHex).slice(70),
      wire,
    ];
    const h = await connected(hex(protocol.identity.payloadHex), notifications);
    expect(h.events.sample).toHaveBeenCalledOnce();
  });

  it('bounds notification IO and recovers selected frames after a large noise callback', async () => {
    const h = await connected(hex(protocol.identity.payloadHex), [new Uint8Array(60_000).fill(2)]);
    expect(h.events.sample).not.toHaveBeenCalled();
    h.service.reader.telemetry();
    await flush();
    expect(h.events.sample).toHaveBeenCalledOnce();
    await h.adapter.disconnect();
    const oversized = await connected(hex(protocol.identity.payloadHex), [new Uint8Array(65537)]);
    expect(oversized.events.sample).not.toHaveBeenCalled();
    expect(await oversized.adapter.getState()).toMatchObject({
      status: 'error',
      error: expect.stringContaining('size'),
    });
  });

  it.each(['X6', 'X12'])('retains rider power independently of higher-voltage input for %s', async model => {
    const payload = hex(protocol.telemetry[3]!.payloadHex);
    const view = new DataView(payload.buffer);
    view.setInt16(21, 720, false);
    view.setInt32(13, 250, false);
    view.setInt32(44, 159, false);
    const identity = Uint8Array.of(111, 5, 3, ...new TextEncoder().encode(`${model} 20250604 `), 0x80, 0xff, 0);
    const h = await connected(identity, [framePayload(payload)]);
    expect(h.events.sample.mock.lastCall![0]).toMatchObject({
      batteryVoltageV: 72,
      batteryCurrentA: 2.5,
      motorInputPowerW: 180,
      humanPowerW: 159,
    });
  });

  it.each(protocol.telemetry.filter(row => row.mask === 0x03c0fb8f))(
    'preserves every decoded slot in $name',
    async row => {
      const h = await measured(hex(row.payloadHex));
      expect(h.events.sample.mock.lastCall![0]).toMatchObject(row.expected);
    },
  );

  it('retains signed wire extrema and unsigned categories without changing rider power', async () => {
    const h = await measured(hex(wireMetrics.payloadHex));
    const sample = h.events.sample.mock.lastCall![0];
    for (const [key, value] of Object.entries(wireMetrics.expected)) expect(sample[key], key).toBe(value);
    expect(sample.humanPowerW).toBe(32767);
    expect(sample.motorInputPowerW).toBe(-15.129);
  });

  it.each(protocol.telemetry.filter(row => row.mask !== 0x03c0fb8f))('rejects unsupported mask $name', async row => {
    const h = await measured(hex(row.payloadHex));
    expect((await h.adapter.getState()).status).toBe('error');
    expect(h.events.sample).not.toHaveBeenCalled();
  });

  it('rejects every mask-bit change, truncated telemetry and trailing bytes', async () => {
    const payload = hex(protocol.telemetry[3]!.payloadHex);
    const bad: Uint8Array[] = [];
    for (let bit = 0; bit < 32; bit += 1) {
      const changed = payload.slice();
      new DataView(changed.buffer).setUint32(1, (0x03c0fb8f ^ (1 << bit)) >>> 0, false);
      bad.push(changed);
    }
    for (let size = 1; size < payload.length; size += 1) bad.push(payload.slice(0, size));
    bad.push(Uint8Array.of(...payload, 0));
    for (const bytes of bad) {
      const h = await measured(bytes);
      expect((await h.adapter.getState()).status, toHex(bytes)).toBe('error');
      expect(h.events.sample).not.toHaveBeenCalled();
      await h.adapter.disconnect();
    }
  });

  it('ignores unknown packets, empty frames and full-telemetry commands', async () => {
    const notifications = framing.rejectedPayloadHex
      .filter(value => !value.startsWith('32') && !value.startsWith('6f'))
      .map(value => framePayload(hex(value)));
    for (const command of [4, 49, 51]) {
      const payload = hex(protocol.telemetry[3]!.payloadHex);
      payload[0] = command;
      notifications.push(framePayload(payload));
    }
    const h = await connected(hex(protocol.identity.payloadHex), notifications);
    expect(h.events.sample).not.toHaveBeenCalled();
    h.service.reader.telemetry();
    await flush();
    expect(h.events.sample).toHaveBeenCalledOnce();
  });
});
