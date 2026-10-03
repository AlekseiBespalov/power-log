import { describe, expect, it } from 'vitest';
import { HealthDiscovery, heartRateKind, healthKind } from '../../src/core/export/fit/health';
import type { ProjectionPage } from '../../src/core/export/pages';
import { ExportError, type Producer } from '../../src/core/export/types';

interface Row {
  t: number;
  producer?: Producer;
  representation?: string | null;
  sampleCount?: number;
  hr?: number;
  energy?: number;
  active?: boolean;
}

function discover(owner: Producer, end: number, rows: Row[]) {
  const page = {
    rows: rows.length,
    connection: null,
    columns: {
      elapsedSeconds: Float64Array.from(rows.map(row => row.t)),
      producer: rows.map(row => row.producer ?? owner),
      representation: rows.map(row => row.representation ?? null),
      sampleCount: Float64Array.from(rows.map(row => row.sampleCount ?? NaN)),
      heartRateBpm: Float64Array.from(rows.map(row => row.hr ?? NaN)),
      activeEnergyKcal: Float64Array.from(rows.map(row => row.energy ?? NaN)),
    },
  } as ProjectionPage<'healthFit'>;
  const discovery = new HealthDiscovery(owner, end);
  discovery.add(page, Int32Array.from(rows.map(row => (row.active === false ? 0 : 1))));
  return discovery.plan();
}

describe('Health kinds', () => {
  it.each([
    ['rawSeries', NaN, 'sample'],
    ['rawQuantity', NaN, 'sample'],
    ['rawQuantity', 1, 'sample'],
    ['rawQuantity', 2, 'aggregate'],
    ['builderMostRecent', NaN, 'latest'],
    ['cumulativeWorkoutTotal', NaN, 'cumulative'],
    ['finalWorkoutTotal', NaN, 'final'],
    ['workoutAssociation', NaN, null],
    ['healthTombstone', NaN, null],
    ['workoutMetadata', NaN, null],
  ] as [string, number, string | null][])('%s with %d values is %s', (representation, count, kind) => {
    expect(heartRateKind(representation, count)).toBe(kind);
  });

  it('gives rows without a representation the metric’s fallback kind', () => {
    expect(heartRateKind(null, NaN)).toBe('latest');
    expect(healthKind(null, NaN, 'cumulative')).toBe('cumulative');
  });

  it('fails on a representation the catalog does not list', () => {
    expect(() => heartRateKind('aggregate', NaN)).toThrow(ExportError);
    expect(() => discover('watch', 10, [{ t: 1, representation: 'aggregate', energy: 3 }])).toThrow(
      /unknown kind aggregate/,
    );
  });
});

describe('Health source qualification', () => {
  it('chooses the owner when its heart rate qualifies', () => {
    expect(
      discover('watch', 10, [
        { t: 1, producer: 'phone', hr: 100 },
        { t: 2, producer: 'watch', hr: 120, representation: 'builderMostRecent' },
      ]).heartRate,
    ).toEqual({ source: 'watch', kind: 'latest' });
  });

  it('falls back when the owner has only aggregate heart rate', () => {
    expect(
      discover('watch', 10, [
        { t: 1, producer: 'watch', hr: 120, representation: 'rawQuantity', sampleCount: 5 },
        { t: 2, producer: 'phone', hr: 100 },
      ]).heartRate,
    ).toEqual({ source: 'phone', kind: 'latest' });
  });

  it('does not qualify a source with only invalid heart-rate values', () => {
    expect(
      discover('phone', 10, [
        { t: 1, producer: 'phone', hr: 0 },
        { t: 2, producer: 'phone', hr: 255 },
        { t: 3, producer: 'watch', hr: 254 },
      ]).heartRate,
    ).toEqual({ source: 'watch', kind: 'latest' });
    expect(discover('phone', 10, [{ t: 1, producer: 'phone', hr: 0.5 }]).heartRate).toBeNull();
  });

  it('prefers raw samples only when the chosen source has one inside an activity interval', () => {
    const outside = [
      { t: 1, hr: 100, representation: 'rawSeries', active: false },
      { t: 2, hr: 110, representation: 'builderMostRecent' },
    ];
    expect(discover('watch', 10, outside).heartRate).toEqual({ source: 'watch', kind: 'latest' });
    expect(
      discover('watch', 10, [...outside, { t: 3, hr: 120, representation: 'rawQuantity', sampleCount: 1 }]).heartRate,
    ).toEqual({
      source: 'watch',
      kind: 'sample',
    });
  });
});

describe('Active energy', () => {
  it('takes the maximum cumulative value up to E, ignoring later snapshots', () => {
    expect(
      discover('watch', 10, [
        { t: 1, energy: 10, representation: 'cumulativeWorkoutTotal' },
        { t: 5, energy: 25, representation: 'cumulativeWorkoutTotal' },
        { t: 9, energy: 20, representation: 'cumulativeWorkoutTotal' },
        { t: 10, energy: 24 },
        { t: 11, energy: 90, representation: 'cumulativeWorkoutTotal' },
      ]).calories,
    ).toBe(25);
  });

  it('replaces it with the latest final total, even a lower one after Stop', () => {
    expect(
      discover('watch', 10, [
        { t: 5, energy: 25, representation: 'cumulativeWorkoutTotal' },
        { t: 12, energy: 21, representation: 'finalWorkoutTotal' },
        { t: 11, energy: 99, representation: 'cumulativeWorkoutTotal' },
        { t: 12, energy: 22, representation: 'finalWorkoutTotal' },
        { t: 13, energy: 30, representation: 'rawSeries' },
      ]).calories,
    ).toBe(22);
  });

  it('never sums sources and falls back only when the owner does not qualify', () => {
    expect(
      discover('watch', 10, [
        { t: 1, producer: 'watch', energy: 5, representation: 'rawQuantity', sampleCount: 1 },
        { t: 2, producer: 'phone', energy: 7 },
        { t: 3, producer: 'phone', energy: 9 },
      ]).calories,
    ).toBe(9);
    expect(
      discover('phone', 10, [{ t: 1, producer: 'watch', energy: 5, representation: 'rawSeries' }]).calories,
    ).toBeNaN();
  });
});
