import fixture from '../fixtures/protocol.json';
import { decodeSelectiveValues, toTelemetrySample, type TelemetrySample } from '../../src/core';

export function hex(value: string): Uint8Array {
  if (!/^(?:[0-9a-f]{2})*$/i.test(value)) throw new Error('Invalid test hex');
  return Uint8Array.from(value.match(/../g) ?? [], part => Number.parseInt(part, 16));
}
export function toHex(value: Uint8Array): string { return Array.from(value, byte => byte.toString(16).padStart(2, '0')).join('') }

/** Timing and any overrides are synthetic; only the baseline scalar packet is live. */
export function sample(elapsedSeconds = 0, overrides: Partial<TelemetrySample> = {}): TelemetrySample {
  return {
    ...toTelemetrySample(decodeSelectiveValues(hex(fixture.telemetry[2]!.payloadHex)), {
      timestamp: new Date(Date.UTC(2026, 0, 1) + elapsedSeconds * 1000).toISOString(),
      elapsedSeconds, sequence: Math.round(elapsedSeconds * 2),
    }),
    ...overrides,
  };
}
