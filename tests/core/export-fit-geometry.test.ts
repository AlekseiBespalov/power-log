import { describe, expect, it } from 'vitest';
import {
  FitGeometry,
  GpsDistanceAccumulator,
  emptyFix,
  gpsMeters,
  validAltitude,
  validFix,
  validSpeed,
  type GpsFix,
} from '../../src/core/export/fit/geometry';

const METERS_PER_DEGREE = (Math.PI / 180) * 6_371_008.8;

function fix(time: number, values: Partial<GpsFix> = {}): GpsFix {
  return {
    ...emptyFix(),
    time,
    latitude: 0,
    longitude: 0,
    horizontalAccuracy: 5,
    interval: 1,
    ...values,
  };
}

describe('GPS fix validity (WorkoutGPSFix.valid)', () => {
  it.each([
    ['a barrier', { barrier: true }, false],
    ['no activity interval', { interval: 0 }, false],
    ['negative time', { time: -0.001 }, false],
    ['non-finite time', { time: NaN }, false],
    ['accuracy 0', { horizontalAccuracy: 0 }, true],
    ['accuracy 50', { horizontalAccuracy: 50 }, true],
    ['accuracy above 50', { horizontalAccuracy: 50.0001 }, false],
    ['negative accuracy', { horizontalAccuracy: -0.5 }, false],
    ['missing accuracy', { horizontalAccuracy: NaN }, false],
    ['latitude 90', { latitude: 90 }, true],
    ['latitude above 90', { latitude: 90.0001 }, false],
    ['longitude -180', { longitude: -180 }, true],
    ['longitude above 180', { longitude: 180.0001 }, false],
    ['missing longitude', { longitude: NaN }, false],
  ] as [string, Partial<GpsFix>, boolean][])('%s', (_, values, valid) => {
    expect(validFix(fix(3, values))).toBe(valid);
  });

  it('validates speed with its accuracy (WorkoutDistancePolicy.validSpeed)', () => {
    expect(validSpeed(0, NaN)).toBe(0);
    expect(validSpeed(40, 0)).toBe(40);
    expect(validSpeed(40.01, 0)).toBeNaN();
    expect(validSpeed(-0.01, NaN)).toBeNaN();
    expect(validSpeed(5, -1)).toBeNaN();
    expect(validSpeed(5, Infinity)).toBeNaN();
    expect(validSpeed(NaN, 1)).toBeNaN();
  });

  it('treats Apple altitude as valid only with vertical accuracy in (0, 20]', () => {
    expect(validAltitude(100, 0)).toBe(false);
    expect(validAltitude(100, -1)).toBe(false);
    expect(validAltitude(100, 0.01)).toBe(true);
    expect(validAltitude(100, 20)).toBe(true);
    expect(validAltitude(100, 20.01)).toBe(false);
    expect(validAltitude(-500, 3)).toBe(true);
    expect(validAltitude(-500.01, 3)).toBe(false);
    expect(validAltitude(20000, 3)).toBe(true);
    expect(validAltitude(20000.01, 3)).toBe(false);
    expect(validAltitude(NaN, 3)).toBe(false);
  });

  it('measures great-circle distance like the Swift accumulator', () => {
    expect(gpsMeters(fix(0), fix(1, { longitude: 0.000045 }))).toBeCloseTo(0.000045 * METERS_PER_DEGREE, 9);
    expect(gpsMeters(fix(0, { latitude: 45 }), fix(1, { latitude: 45 }))).toBe(0);
  });
});

describe('GPS distance accumulator (WorkoutGPSDistanceAccumulator.append)', () => {
  const step = 0.00001;
  it('connects consecutive fixes within 10 s, 40 m/s, one interval and one clock epoch', () => {
    const accumulator = new GpsDistanceAccumulator();
    expect(accumulator.append(fix(0))).toBeNaN();
    expect(accumulator.append(fix(1, { longitude: step }))).toBeCloseTo(step * METERS_PER_DEGREE, 9);
    expect(accumulator.append(fix(11, { longitude: 2 * step }))).toBeCloseTo(step * METERS_PER_DEGREE, 9);
    expect(accumulator.append(fix(21.0001, { longitude: 3 * step }))).toBeNaN();
    expect(accumulator.append(fix(22, { longitude: 3 * step }))).toBe(0);
  });

  it('rejects implied speeds above 40 m/s and keeps the new fix as the next start', () => {
    const accumulator = new GpsDistanceAccumulator();
    const fast = 40.0001 / METERS_PER_DEGREE;
    accumulator.append(fix(0));
    expect(accumulator.append(fix(1, { longitude: fast }))).toBeNaN();
    expect(accumulator.append(fix(2, { longitude: fast + step }))).toBeCloseTo(step * METERS_PER_DEGREE, 9);
    const below = new GpsDistanceAccumulator();
    below.append(fix(0));
    expect(below.append(fix(1, { longitude: 39.9999 / METERS_PER_DEGREE }))).toBeCloseTo(39.9999, 6);
    const chord = gpsMeters(fix(0), fix(1, { longitude: 0.0004 }));
    const exact = new GpsDistanceAccumulator();
    exact.append(fix(0));
    expect(chord / (chord / 40)).toBe(40);
    expect(exact.append(fix(chord / 40, { longitude: 0.0004 }))).toBe(chord);
  });

  it('never connects across activity intervals or clock epochs', () => {
    const accumulator = new GpsDistanceAccumulator();
    accumulator.append(fix(0, { clockEpoch: 'a' }));
    expect(accumulator.append(fix(1, { clockEpoch: 'b', longitude: step }))).toBeNaN();
    expect(accumulator.append(fix(2, { clockEpoch: 'b', interval: 2, longitude: 2 * step }))).toBeNaN();
    expect(accumulator.append(fix(3, { clockEpoch: 'b', interval: 2, longitude: 3 * step }))).toBeGreaterThan(0);
  });

  it('counts a stationary interval as zero only when both valid speeds are below 0.5 m/s', () => {
    const accumulator = new GpsDistanceAccumulator();
    accumulator.append(fix(0, { speed: 0.4 }));
    expect(accumulator.append(fix(1, { speed: 0.4, longitude: step }))).toBe(0);
    expect(accumulator.append(fix(2, { speed: 0.4, speedAccuracy: -1, longitude: 2 * step }))).toBeGreaterThan(0);
    expect(accumulator.append(fix(3, { longitude: 3 * step }))).toBeGreaterThan(0);
    expect(accumulator.append(fix(4, { speed: 3, longitude: 4 * step }))).toBeGreaterThan(0);
    expect(accumulator.append(fix(5, { speed: 0.3, longitude: 5 * step }))).toBeGreaterThan(0);
    expect(accumulator.append(fix(6, { speed: 0.3, longitude: 6 * step }))).toBe(0);
  });

  it('clears its previous fix on dt ≤ 0, re-seeding only at equal time with a changed interval or epoch', () => {
    const same = new GpsDistanceAccumulator();
    same.append(fix(1, { clockEpoch: 'a' }));
    expect(same.append(fix(1, { clockEpoch: 'a' }))).toBeNaN();
    expect(same.append(fix(2, { clockEpoch: 'a', longitude: step }))).toBeNaN();
    const changed = new GpsDistanceAccumulator();
    changed.append(fix(1, { clockEpoch: 'a' }));
    expect(changed.append(fix(1, { clockEpoch: 'b' }))).toBeNaN();
    expect(changed.append(fix(2, { clockEpoch: 'b', longitude: step }))).toBeGreaterThan(0);
    const backwards = new GpsDistanceAccumulator();
    backwards.append(fix(2));
    expect(backwards.append(fix(1))).toBeNaN();
    expect(backwards.append(fix(3, { longitude: step }))).toBeNaN();
  });

  it('resets on a rejected fix', () => {
    const accumulator = new GpsDistanceAccumulator();
    accumulator.append(fix(0));
    expect(accumulator.append(fix(1, { horizontalAccuracy: 51 }))).toBeNaN();
    expect(accumulator.append(fix(2, { longitude: step }))).toBeNaN();
    expect(accumulator.append(fix(3, { longitude: 2 * step }))).toBeGreaterThan(0);
  });
});

describe('FIT geometry (WorkoutAnalysis.Geometry.accept)', () => {
  const altitude = (time: number, value: number, accuracy = 2, values: Partial<GpsFix> = {}) =>
    fix(time, { altitude: value, verticalAccuracy: accuracy, longitude: time * 0.00001, ...values });

  it('adds a change at exactly the threshold and moves the anchor', () => {
    const geometry = new FitGeometry();
    expect(geometry.accept(altitude(0, 100))).toBe(true);
    geometry.accept(altitude(1, 102.999));
    expect(geometry.ascent).toBe(0);
    geometry.accept(altitude(2, 103));
    expect(geometry.ascent).toBe(3);
    geometry.accept(altitude(3, 100.5));
    expect(geometry.descent).toBe(0);
    geometry.accept(altitude(4, 99.9, 3.5));
    expect(geometry.descent).toBe(0);
    geometry.accept(altitude(5, 99.5, 3.5));
    expect(geometry.descent).toBeCloseTo(3.5, 12);
    expect(geometry.altitudeSegments).toBe(5);
  });

  it('uses the larger of 3 m and both fixes’ vertical accuracies as the threshold', () => {
    const geometry = new FitGeometry();
    geometry.accept(altitude(0, 100, 6));
    geometry.accept(altitude(1, 105.9, 2));
    expect(geometry.ascent).toBe(0);
    geometry.accept(altitude(2, 106, 2));
    expect(geometry.ascent).toBe(6);
  });

  it('resets the anchor on an invalid altitude and re-seeds it from the next valid pair', () => {
    const geometry = new FitGeometry();
    geometry.accept(altitude(0, 100));
    geometry.accept(altitude(1, 150, 0));
    geometry.accept(altitude(2, 200, -1));
    geometry.accept(altitude(3, 300, 2));
    expect(geometry.altitudeSegments).toBe(0);
    geometry.accept(altitude(4, 310, 2));
    expect(geometry.ascent).toBe(0);
    geometry.accept(altitude(5, 314, 2));
    expect(geometry.ascent).toBe(4);
  });

  it('resets on a rejected fix and on a geometry discontinuity, seeding the anchor at the segment start', () => {
    const geometry = new FitGeometry();
    geometry.accept(altitude(0, 100));
    expect(geometry.accept(altitude(1, 200, 2, { barrier: true }))).toBe(false);
    geometry.accept(altitude(2, 110));
    geometry.accept(altitude(13, 120));
    geometry.accept(altitude(14, 125));
    expect(geometry.ascent).toBe(5);
    geometry.accept(altitude(15, 130, 2, { interruption: 1 }));
    geometry.accept(altitude(16, 134, 2, { interruption: 1 }));
    expect(geometry.ascent).toBe(9);
  });

  it('accepts a fix at the same or an earlier time, starts a segment and seeds the anchor', () => {
    const geometry = new FitGeometry();
    geometry.accept(altitude(1, 100));
    expect(geometry.accept(altitude(1, 150))).toBe(true);
    geometry.accept(altitude(2, 154));
    expect(geometry.ascent).toBe(0);
    geometry.accept(altitude(3, 158));
    expect(geometry.ascent).toBe(4);
    expect(geometry.accept(altitude(2.5, 200))).toBe(true);
    geometry.accept(altitude(3.5, 210));
    expect(geometry.ascent).toBe(4);
  });

  it('links equal-time fixes across a changed clock epoch but not within one epoch', () => {
    const changed = new FitGeometry();
    changed.accept(altitude(1, 100, 2, { clockEpoch: 'a' }));
    changed.accept(altitude(1, 100, 2, { clockEpoch: 'b' }));
    changed.accept(altitude(2, 110, 2, { clockEpoch: 'b' }));
    expect(changed.ascent).toBe(10);
    const same = new FitGeometry();
    same.accept(altitude(1, 100, 2, { clockEpoch: 'a' }));
    same.accept(altitude(1, 100, 2, { clockEpoch: 'a' }));
    same.accept(altitude(2, 110, 2, { clockEpoch: 'a' }));
    expect(same.ascent).toBe(0);
    same.accept(altitude(3, 114, 2, { clockEpoch: 'a' }));
    expect(same.ascent).toBe(4);
  });
});
