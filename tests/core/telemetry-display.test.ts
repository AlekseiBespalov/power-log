import { describe, expect, it } from 'vitest';
import { bikeConnectionLabel, displaySampleTime, telemetryDisplay } from '../../src/core/telemetry-display';
import { isFreshSample, type ConnectionStatus } from '../../src/core/types';

describe('bike readings during a connection gap', () => {
  it('does not restart the hold for an old native event delivered after UI suspension', () => {
    const wallTime = Date.parse('2026-09-09T00:00:10Z');
    const receivedAt = displaySampleTime('2026-09-09T00:00:06Z', 20, wallTime);
    expect(receivedAt).toBe(16);
    expect(telemetryDisplay('reconnecting', receivedAt, 20)).toBe('held');
    expect(telemetryDisplay('reconnecting', receivedAt, 21)).toBe('held');
    expect(telemetryDisplay('reconnecting', receivedAt, 22)).toBe('unavailable');
    expect(telemetryDisplay('connected', displaySampleTime('2026-09-09T00:00:01Z', 20, wallTime), 20)).toBe('unavailable');
  });

  it('rejects invalid event clocks and bounds tolerated timestamp skew', () => {
    const wallTime = Date.parse('2026-09-09T00:00:10Z');
    expect(displaySampleTime('bad', 20, wallTime)).toBeNull();
    expect(displaySampleTime('2026-09-09T00:00:10Z', NaN, wallTime)).toBeNull();
    expect(displaySampleTime('2026-09-09T00:00:10Z', 20, NaN)).toBeNull();
    expect(displaySampleTime('2026-09-09T00:00:12Z', 20, wallTime)).toBe(20);
    expect(displaySampleTime('2026-09-09T00:00:12.001Z', 20, wallTime)).toBeNull();
  });

  it('keeps the normal connection label while internally distinguishing held readings', () => {
    expect(telemetryDisplay('connected', 10, 10.5)).toBe('live');
    expect(telemetryDisplay('reconnecting', 10, 10.5)).toBe('held');
    expect(bikeConnectionLabel('reconnecting', 'held')).toBe('Connected');
  });

  it('expires from the original sample time, not when reconnect starts or finishes', () => {
    expect(telemetryDisplay('reconnecting', 10, 15.999)).toBe('held');
    expect(telemetryDisplay('reconnecting', 10, 16)).toBe('unavailable');
    expect(telemetryDisplay('connected', 10, 16)).toBe('unavailable');
    expect(bikeConnectionLabel('connected', 'unavailable')).toBe('Waiting for data');
    expect(telemetryDisplay('connected', 16.1, 16.1)).toBe('live');
    expect(bikeConnectionLabel('reconnecting', 'unavailable')).toBe('Reconnecting…');
  });

  it('also tolerates a short stalled stream without changing measurement freshness', () => {
    expect(telemetryDisplay('connected', 10, 12.5)).toBe('live');
    expect(telemetryDisplay('connected', 10, 12.501)).toBe('held');
    expect(isFreshSample(10, 12.501)).toBe(false);
    expect(telemetryDisplay('connected', 10, 16)).toBe('unavailable');
    expect(bikeConnectionLabel('connected', 'held')).toBe('Connected');
  });

  it.each<ConnectionStatus>(['idle', 'scanning', 'connecting', 'error'])('does not hold values after leaving the session: %s', status => {
    expect(telemetryDisplay(status, 10, 10.1)).toBe('unavailable');
  });

  it('never revives a missing, invalid or future sample', () => {
    for (const receivedAt of [null, NaN, Infinity, -Infinity, 11]) {
      expect(telemetryDisplay('connected', receivedAt, 10)).toBe('unavailable');
      expect(telemetryDisplay('reconnecting', receivedAt, 10)).toBe('unavailable');
    }
    expect(telemetryDisplay('connected', 10, NaN)).toBe('unavailable');
    expect(telemetryDisplay('reconnecting', 10, Infinity)).toBe('unavailable');
  });
});
