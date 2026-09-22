import { describe, expect, it } from 'vitest';
import { bikeDetails, bikeDisplayName } from '../../src/core/bikes';

describe('bike identification', () => {
  it('distinguishes identical advertisements before pairing and keeps the tag after identification', () => {
    const first = { id: 'SYNTHETIC-A', name: 'CYCMOTOR', rssi: -60 };
    const second = { ...first, id: 'SYNTHETIC-B' };
    expect(bikeDisplayName(first)).not.toBe(bikeDisplayName(second));
    expect(bikeDisplayName(first)).toBe(bikeDisplayName({ ...first, id: first.id.toLowerCase() }));
    expect(bikeDisplayName({ ...first, controllerModel: 'X12' })).toBe(bikeDisplayName(first).replace('CYC bike', 'CYC X12'));
    expect(bikeDetails(first)).toBe('Model identified on connection · Strong signal');
    expect(bikeDetails({ ...second, controllerModel: 'X6', firmwareLabel: '20250604', rssi: -77 })).toBe('Firmware 20250604 · Fair signal');
  });
  it('does not invent a model or signal and preserves plain names', () => {
    expect(bikeDisplayName({ name: 'Garage bike' })).toBe('Garage bike');
    expect(bikeDetails({ id: 'test', name: 'CYCMOTOR', rssi: 0 })).toBe('Model identified on connection');
  });
});
