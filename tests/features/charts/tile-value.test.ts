import { describe, expect, it } from 'vitest';
import { chartMetrics } from '../../../src/components/charts/chart-groups';
import type { LatestCell } from '../../../src/features/charts/chart-feed-model';
import { tileValue } from '../../../src/features/charts/tile-value';

const metric = chartMetrics('metric', 'celsius');
const cell = (value: number, stale = false): LatestCell => ({
  value,
  elapsedUs: 1_000_000,
  rideAverage: 12.5,
  freshUntilMs: null,
  tailUntilMs: null,
  stale,
});
const live = { live: true, headLive: true, bikeAvailable: true };

describe('chart tiles', () => {
  it('shows "—" for a live tile past its freshness, or a bike metric once the bike is unavailable', () => {
    expect(tileValue(metric('humanPowerW')!, cell(200), live)).toBe(200);
    expect(tileValue(metric('humanPowerW')!, cell(200, true), live)).toBeNull();
    expect(tileValue(metric('humanPowerW')!, cell(200), { ...live, bikeAvailable: false })).toBeNull();
    expect(tileValue(metric('heartRateBpm')!, cell(140), { ...live, bikeAvailable: false })).toBe(140);
    expect(tileValue(metric('distanceMeters')!, cell(1000, true), { ...live, bikeAvailable: false })).toBe(1000);
    expect(tileValue(metric('humanPowerW')!, undefined, live)).toBeNull();
  });

  it('shows "—" in every tile of an interrupted ride but distance, consumption and Wh/km', () => {
    const interrupted = { ...live, headLive: false };
    for (const id of ['humanPowerW', 'heartRateBpm', 'speedMps', 'altitudeMeters'])
      expect(tileValue(metric(id)!, cell(10), interrupted), id).toBeNull();
    expect(tileValue(metric('distanceMeters')!, cell(1000), interrupted)).toBe(1000);
    expect(tileValue(metric('consumedWh')!, cell(3), interrupted)).toBe(3);
    expect(tileValue(metric('controllerEnergyWh')!, cell(3), interrupted)).toBe(3);
    expect(tileValue(metric('batteryWhPerKm')!, cell(20), interrupted)).toBe(12.5);
  });

  it('keeps a saved ride’s values whatever their freshness', () => {
    const saved = { live: false, headLive: false, bikeAvailable: false };
    expect(tileValue(metric('humanPowerW')!, cell(200, true), saved)).toBe(200);
  });
});
