import { describe, expect, it } from 'vitest';
import { bikeDisplayName, controllerSpeedNote } from '../../src/core/bikes';

describe('bike identification', () => {
  it('distinguishes identical advertisements before pairing and keeps the tag after identification', () => {
    const first = { id: 'SYNTHETIC-A', name: 'CYCMOTOR', rssi: -60 };
    const second = { ...first, id: 'SYNTHETIC-B' };
    expect(bikeDisplayName(first)).not.toBe(bikeDisplayName(second));
    expect(bikeDisplayName(first)).toBe(bikeDisplayName({ ...first, id: first.id.toLowerCase() }));
    expect(bikeDisplayName({ ...first, controllerModel: 'X12' })).toBe(
      bikeDisplayName(first).replace('CYC bike', 'CYC X12'),
    );
  });
  it('preserves plain names', () => {
    expect(bikeDisplayName({ name: 'Garage bike' })).toBe('Garage bike');
  });
  it('explains missing controller speed only for an identity without a known speed unit', () => {
    const note = 'Controller speed needs CYC X6 or X12 firmware with protocol 5.3.';
    expect(
      controllerSpeedNote({ controllerModel: 'X6', firmwareLabel: '20250604', controllerProtocol: '5.3' }),
    ).toBeNull();
    expect(controllerSpeedNote({ controllerModel: 'X12', firmwareLabel: '20240110', controllerProtocol: '5.2' })).toBe(
      note,
    );
    expect(controllerSpeedNote({ controllerModel: 'X6', firmwareLabel: '20250604' })).toBeNull();
    expect(controllerSpeedNote({})).toBeNull();
  });
});
