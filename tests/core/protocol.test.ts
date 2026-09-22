import { describe, expect, it } from 'vitest';
import fixture from '../fixtures/protocol.json';
import { crc16Xmodem, identifyController, decodeIdentity, decodeSelectiveValues, FrameDecoder, requestFrame, SELECTED_MASK, toTelemetrySample } from '../../src/core';
import { hex, toHex } from './helpers';

describe('CYC read protocol parity with verified Python fixtures', () => {
  it('matches the XMODEM reference and exact allowlisted request bytes', () => {
    expect(crc16Xmodem(Uint8Array.from(fixture.crc.ascii, value => value.charCodeAt(0)))).toBe(fixture.crc.expected);
    expect(toHex(requestFrame('identity'))).toBe(fixture.requests.identity);
    expect(toHex(requestFrame('selective'))).toBe(fixture.requests.selective);
    // @ts-expect-error Deliberately test the runtime boundary from untyped callers.
    expect(() => requestFrame('values')).toThrow('Unsupported controller request');
    // @ts-expect-error Arbitrary controller writes are not part of the app API.
    expect(() => requestFrame(Uint8Array.of(6))).toThrow();
  });

  it('reassembles every small fragmentation size and coalesced packets', () => {
    const wire = hex(fixture.telemetry[3]!.frameHex!);
    for (let size = 1; size <= wire.length; size += 1) {
      const decoder = new FrameDecoder(); const packets: Uint8Array[] = [];
      for (let offset = 0; offset < wire.length; offset += size) packets.push(...decoder.feed(wire.slice(offset, offset + size)));
      expect(packets.map(toHex)).toEqual([fixture.telemetry[3]!.payloadHex]);
      expect(decoder.bufferedBytes).toBe(0);
      expect(decoder.discardedBytes).toBe(0);
    }
    const decoder = new FrameDecoder();
    expect(decoder.feed(hex(fixture.framing.single + fixture.framing.single)).map(toHex)).toEqual(['04', '04']);
  });

  it('resynchronizes after garbage and corrupt CRC without emitting corrupt data', () => {
    const decoder = new FrameDecoder();
    expect(decoder.feed(hex('aabbcc' + fixture.framing.corrupted + fixture.framing.single)).map(toHex)).toEqual(['04']);
    expect(decoder.discardedBytes).toBe(9);
  });

  it('accepts split canonical long frames and forgets partial data on reconnect', () => {
    const decoder = new FrameDecoder(); const wire = hex(fixture.framing.longFrameHex);
    expect(decoder.feed(wire.slice(0, 70))).toEqual([]);
    expect(decoder.feed(wire.slice(70)).map(toHex)).toEqual([fixture.framing.longPayloadHex]);
    decoder.feed(wire.slice(0, 10)); decoder.reset();
    expect(decoder.discardedBytes).toBe(10);
    expect(decoder.bufferedBytes).toBe(0);
    expect(decoder.feed(hex(fixture.framing.single)).map(toHex)).toEqual(['04']);
  });

  it('rejects noncanonical lengths and keeps bounded retained state on noise', () => {
    const decoder = new FrameDecoder();
    expect(decoder.feed(hex('03000104408403'))).toEqual([]);
    decoder.reset();
    decoder.feed(new Uint8Array(60_000).fill(2));
    expect(decoder.bufferedBytes).toBeLessThanOrEqual(1029);
    expect(() => decoder.feed(new Uint8Array(65537))).toThrow('size');
  });

  it('decodes only supported X6 identities and excludes serial bytes', () => {
    const identity = decodeIdentity(hex(fixture.identity.payloadHex));
    expect(identity.controllerModel).toBe(fixture.identity.controllerModel);
    expect(identity.firmwareLabel).toBe(fixture.identity.firmwareLabel);
    expect(Object.keys(identity)).not.toContain('remainingHex');
    const legacy = hex(fixture.identity.payloadHex); legacy[0] = 0;
    expect(decodeIdentity(legacy).command).toBe(0);
    expect(() => decodeIdentity(hex('6f0503583600'))).toThrow('Unsupported controller');
    expect(() => decodeIdentity(hex('6f05035836203230323530373235'))).toThrow('Unsupported controller');
    const wrong = hex(fixture.identity.payloadHex); wrong[4] = 55;
    expect(() => decodeIdentity(wrong)).toThrow('Unsupported controller');
  });

  it.each([0, 111])('accepts a strict X6 prefix with an opaque suffix for command %i without exposing it', command => {
    // Synthetic bytes only: reproduces the live response's binary-before-NUL shape.
    const prefix = Array.from('X6        20250604 ', char => char.charCodeAt(0));
    const payload = Uint8Array.of(command, 5, 3, ...prefix, 0x80, 0xff, 0x1f, 0x7f, 65, 0, 0xfe, 0xdc);
    expect(decodeIdentity(payload)).toEqual({ command, major: 5, minor: 3, controllerModel: 'X6', firmwareLabel: '20250604', productString: 'X6 20250604' });
    expect(decodeIdentity(Uint8Array.of(command, 5, 3, ...Array.from('X6_Pro 260101A', char => char.charCodeAt(0)), 0)).firmwareLabel).toBe('260101A');
  });

  it('rejects unknown models, malformed date boundaries and non-ASCII prefix bytes even with an opaque suffix', () => {
    for (const label of ['X1 20250604 ', 'X60 20250604 ', 'X120 20250604 ', 'X12 202506041 ', ' X6 20250604 ', 'X6-Other 20250604 ', 'X6 20250 ', 'X6 202506041 ', 'X6 20250604a ', 'X6 20250604/extra ', 'X6 20250604\n', 'X6\t20250604 ', `X6${'A'.repeat(31)} 20250604 `, `X6 20250604${'A'.repeat(9)} `]) {
      const payload = Uint8Array.of(111, 5, 3, ...Array.from(label, char => char.charCodeAt(0)), 0x80, 0xff, 0);
      expect(() => decodeIdentity(payload), label).toThrow('Unsupported controller. Connect a CYC X6 or X12.');
    }
    for (const label of [[88, 0x80, 54, 32, ...Array.from('20250604', char => char.charCodeAt(0))], [...Array.from('X6 20250604', char => char.charCodeAt(0)), 0xff]]) {
      expect(() => decodeIdentity(Uint8Array.of(111, 5, 3, ...label, 0))).toThrow('Unsupported controller');
    }
  });

  it.each(['X6', 'X12'] as const)('selects the %s adapter and decodes higher-voltage telemetry without conflating rider watts', model => {
    const payload = Uint8Array.of(111, 5, 3, ...Array.from(`${model} 20250604 `, char => char.charCodeAt(0)), 0x80, 0xff, 0);
    const { identity, adapter } = identifyController(payload);
    expect(identity).toEqual({ command: 111, major: 5, minor: 3, controllerModel: model, firmwareLabel: '20250604', productString: `${model} 20250604` });
    expect(adapter.family).toBe(model);
    const telemetry = hex(fixture.telemetry[2]!.payloadHex);
    new DataView(telemetry.buffer).setInt16(21, 720, false);
    const values = adapter.decodeTelemetry(telemetry);
    expect(values.batteryVoltageV).toBe(72);
    expect(values.motorInputPowerW).toBeCloseTo(72 * values.batteryCurrentA!);
    expect(values.humanPowerW).toBe(decodeSelectiveValues(telemetry).humanPowerW);
    expect(() => adapter.decodeTelemetry(telemetry.slice(0, -1))).toThrow();
  });

  it('retains identity command, NUL and length bounds when suffix bytes are opaque', () => {
    const prefix = Array.from('X6 20250604 ', char => char.charCodeAt(0));
    const accepted = Uint8Array.of(111, 5, 3, ...prefix, ...new Array<number>(128 - prefix.length).fill(0xff), 0);
    expect(decodeIdentity(accepted).controllerModel).toBe('X6');
    const oversizedLabel = Uint8Array.of(...accepted.slice(0, -1), 0xff, 0);
    const oversizedFrame = new Uint8Array(1025); oversizedFrame.set(accepted);
    const wrongCommand = accepted.slice(); wrongCommand[0] = 50;
    for (const bad of [oversizedLabel, oversizedFrame, wrongCommand, accepted.slice(0, -1)]) expect(() => decodeIdentity(bad)).toThrow('Unsupported controller');
  });

  it.each(fixture.telemetry)('decodes $name with exact CYC slots and scaling', data => {
    expect(decodeSelectiveValues(hex(data.payloadHex), data.mask)).toMatchObject(data.expected);
  });

  it('preserves rider watts separately from electrical battery watts and speed raw units', () => {
    const values = decodeSelectiveValues(hex(fixture.telemetry[0]!.payloadHex), fixture.telemetry[0]!.mask);
    expect(values.humanPowerW).toBe(159); expect(values.motorInputPowerW).toBe(130.25);
    const nonzero = decodeSelectiveValues(hex(fixture.telemetry[3]!.payloadHex));
    expect(nonzero.speedRaw).toBe(3.77); expect(nonzero.humanPowerW).toBe(1); expect(nonzero.motorInputPowerW).toBe(0);
  });

  it('refuses unknown masks, unexpected masks, truncation, trailing bytes and full telemetry commands', () => {
    const wire = hex(fixture.telemetry[2]!.payloadHex);
    expect(() => decodeSelectiveValues(wire.slice(0, -1))).toThrow('Truncated');
    expect(() => decodeSelectiveValues(Uint8Array.of(...wire, 0))).toThrow('trailing');
    expect(() => decodeSelectiveValues(hex('3280000000'))).toThrow('Unknown');
    expect(() => decodeSelectiveValues(wire, 1)).toThrow('Unexpected');
    expect(() => decodeSelectiveValues(hex('04'))).toThrow('selective');
    expect(() => decodeSelectiveValues(wire, -1)).toThrow('expected mask');
    expect(() => toTelemetrySample({ responseCommand: 50, fieldMask: SELECTED_MASK }, { timestamp: '2026-01-01T00:00:00Z', elapsedSeconds: 0, sequence: 0 })).toThrow('numeric');
  });
});
