import { expect, it } from 'vitest';
import formatting from '../fixtures/contract/presentation/formatting.json';
import options from '../fixtures/contract/presentation/options.json';
import preferences from '../fixtures/contract/presentation/preferences.json';
import permissions from '../fixtures/contract/presentation/permissions.json';
import {
  formatMetric,
  formatMetricPoint,
  formatMetricDifference,
  metricById,
  defaultMonitorPreferences,
  monitorPreferencesJson,
  parseMonitorPreferences,
  type SpeedUnit,
} from '../../src/core/monitor';
import { formatDuration } from '../../src/core/formatting';
import type { DistanceSource } from '../../src/core/distance';
import { effectiveWorkoutOptions, unavailableWorkoutState, type WorkoutState } from '../../src/core/workouts';
import {
  workoutDistanceSelectionLabel,
  healthProviderLabel,
  workoutStartReadiness,
  workoutSourcePresentation,
} from '../../src/core/workout-presentation';
import { workoutPermissionAction } from '../../src/core/workout-permissions';

const capabilities: WorkoutState['capabilities'] = {
  phoneWorkout: true,
  watchWorkout: true,
  phoneHealth: true,
  watchHealth: true,
  gps: true,
  healthProvider: 'appleHealth',
  foregroundOnly: false,
};
const point = (value: number | null, exactValue: string | null) =>
  value === null
    ? null
    : {
        elapsedSeconds: 1,
        timestamp: '2026-01-01T00:00:00Z',
        value,
        ...(exactValue === null ? {} : { exactValue }),
      };
it('consumes metric, exact, difference, duration, provider and picker fixtures through production formatting', () => {
  for (const row of formatting.metrics)
    expect(
      formatMetric(metricById(row.id, row.unit as SpeedUnit)!, row.value === null ? null : Number(row.value)),
    ).toBe(row.text);
  for (const row of formatting.exact)
    expect(formatMetricPoint(metricById(row.id)!, point(Number(row.original), row.original))).toBe(row.text);
  for (const row of formatting.differences)
    expect(formatMetricDifference(metricById(row.id)!, point(row.a, row.aExact), point(row.b, row.bExact))).toBe(
      row.text,
    );
  for (const row of formatting.duration) expect(formatDuration(row.seconds)).toBe(row.text);
  for (const row of formatting.providers)
    expect(healthProviderLabel(row.provider as 'appleHealth' | null)).toBe(row.label);
  for (const row of formatting.distanceOptions)
    expect(workoutDistanceSelectionLabel(row.source as DistanceSource, row.foreground)).toBe(row.label);
});
it('resolves the option fixtures and requires a bike for web Start with GPS enabled or disabled', () => {
  for (const row of options.options)
    expect(
      effectiveWorkoutOptions(
        {
          indoor: row.indoor,
          useWatch: row.watch,
          saveToHealth: row.health,
          recordGPS: row.recordGPS,
          sampleHz: row.sampleHz as 2 | 4 | 8 | null,
        },
        capabilities,
      ),
    ).toEqual({
      indoor: row.indoor,
      useWatch: row.expectedWatch,
      saveToHealth: row.expectedHealth,
      recordGPS: row.expectedGPS,
      sampleHz: row.expectedRate,
      distanceSource: row.expectedGPS && !row.indoor ? 'gps:phone' : 'controller',
      batteryConsumptionSource: 'controller',
    });
  const web = {
    ...unavailableWorkoutState,
    supported: true,
    capabilities: { ...unavailableWorkoutState.capabilities, gps: true, foregroundOnly: true },
  };
  for (const recordGPS of [false, true]) {
    const request = { indoor: false, saveToHealth: false, recordGPS };
    expect(workoutStartReadiness(web, request, false)).toMatchObject({ available: false, error: 'bikeRequired' });
    expect(workoutStartReadiness(web, request, true).available).toBe(true);
  }
  expect(
    workoutStartReadiness({ ...web, recoveryState: 'unresolved' }, { indoor: false, recordGPS: true }, false).available,
  ).toBe(false);
  expect(workoutStartReadiness(web, { indoor: false, recordGPS: true }, false, false, 'Input unavailable').error).toBe(
    'Input unavailable',
  );
});
it('enforces the admitted rate fixture through production option resolution', () => {
  for (const sampleHz of options.validRates)
    expect(effectiveWorkoutOptions({ indoor: false, sampleHz: sampleHz as 2 | 4 | 8 }, capabilities).sampleHz).toBe(
      sampleHz,
    );
  for (const sampleHz of options.invalidRates.filter(rate => rate !== null))
    expect(() =>
      effectiveWorkoutOptions({ indoor: false, sampleHz: Number(sampleHz) as 2 | 4 | 8 }, capabilities),
    ).toThrow();
});
it('projects active frozen source choices independently of the next-ride preference rate', () => {
  const state = {
    ...unavailableWorkoutState,
    capabilities,
    phase: 'running' as const,
    useWatch: true,
    recordGPS: true,
    saveToHealth: false,
  };
  const sources = workoutSourcePresentation(
    state,
    { indoor: true, useWatch: false, recordGPS: false, sampleHz: 8 },
    true,
    'held',
  );
  expect(sources.bike.label).toBe('Receiving samples');
  expect(sources.gps.label).toBe('GPS unavailable');
  expect(workoutSourcePresentation(state, { indoor: true, sampleHz: 2 }, true, 'held')).toEqual(sources);
});
it('round-trips the preset-keyed fixture and strictly rejects malformed preferences', () => {
  expect(JSON.parse(monitorPreferencesJson(defaultMonitorPreferences()))).toEqual(preferences.defaults);
  const text = JSON.stringify(preferences.defaults);
  expect(JSON.parse(monitorPreferencesJson(parseMonitorPreferences(text)))).toEqual(preferences.defaults);
  for (const row of preferences.invalid)
    expect(() => parseMonitorPreferences(JSON.stringify({ ...preferences.defaults, [row.field]: row.value }))).toThrow(
      'Invalid saved monitor settings',
    );
  expect(() => parseMonitorPreferences(text.replace('"version":1', '"version":1,"version":1'))).toThrow(
    'Invalid saved monitor settings',
  );
});
it('skips stored choices this build no longer offers instead of rejecting the settings', () => {
  for (const distanceSource of preferences.unavailable.distanceSources)
    expect(parseMonitorPreferences(JSON.stringify({ ...preferences.defaults, distanceSource })).distanceSource).toBe(
      'auto',
    );
  const [first, ...rest] = preferences.unavailable.metrics,
    ride = preferences.defaults.views.ride,
    stored = {
      ...preferences.defaults,
      views: {
        ...preferences.defaults.views,
        ride: { ...ride, numbers: [first, ...ride.numbers, ...rest], charts: [first, ...ride.charts, ...rest] },
      },
    };
  expect(parseMonitorPreferences(JSON.stringify(stored)).views.ride).toMatchObject({
    numbers: ride.numbers,
    charts: ride.charts,
  });
});
it.each(permissions.cases)('projects the permission fixture $name through the production consumer', row => {
  const status = row.status;
  const result = workoutPermissionAction(
    status === null
      ? null
      : {
          health: {
            available: status.available,
            provider: status.provider === 'healthConnect' ? 'healthConnect' : undefined,
            requiredWrites: Object.keys(status.writes).length ? ['workout'] : undefined,
            writeAuthorization: status.writes as Record<string, string>,
          },
          location: status.location,
          locationServicesEnabled: status.services,
          locationAccuracyAuthorization: status.accuracy as 'full' | 'reduced' | 'unknown',
        },
    { indoor: false, useWatch: row.watch, saveToHealth: row.health, recordGPS: row.gps },
  );
  expect(result.action).toBe(row.action);
  expect(result.label).toBe(row.label);
});
