import { describe, expect, it } from 'vitest';
import { locationPermissionLabel, workoutSourcePresentation } from '../../src/core/workout-presentation';
import {
  normalizeWorkoutState,
  parseStreamStatus,
  unavailableWorkoutState,
  type StreamStatus,
  type WorkoutState,
} from '../../src/core/workouts';

function state(): WorkoutState {
  return structuredClone({
    ...unavailableWorkoutState,
    saveToHealth: true,
    recordGPS: true,
    capabilities: {
      phoneWorkout: true,
      watchWorkout: true,
      phoneHealth: true,
      watchHealth: true,
      healthProvider: 'appleHealth',
      gps: true,
      foregroundOnly: false,
    },
  });
}

describe('workout source presentation', () => {
  it('shows bike link readiness and selected Watch sources before any workout samples exist', () => {
    const value = state();
    value.streams.gps.status = 'waiting';
    const sources = workoutSourcePresentation(value, { useWatch: true, indoor: false }, true);
    expect(sources.bike.label).toBe('Bike connected');
    expect(sources.bike.detail).toContain('start when you start');
    expect(sources.heartRate).toMatchObject({ label: 'Starts with workout', detail: 'Apple Watch', tone: 'neutral' });
    expect(sources.gps).toMatchObject({ label: 'Starts with workout', detail: 'Watch', tone: 'neutral' });
    expect(JSON.stringify(sources)).not.toContain('Receiving');
  });

  it('uses new setup selections instead of a completed ride’s measurements or source', () => {
    const value = state();
    value.phase = 'completed';
    value.useWatch = true;
    value.streams.cyc.status = 'receiving';
    value.streams.heartRate.status = 'receiving';
    value.streams.gps = { status: 'receiving', source: 'watch', accuracyMeters: 3 };
    const sources = workoutSourcePresentation(value, { useWatch: false, indoor: false }, false);
    expect(sources.bike.label).toBe('Not connected');
    expect(sources.heartRate.label).toBe('Not selected');
    expect(sources.gps).toMatchObject({ label: 'Starts with workout', detail: 'Phone' });
  });

  it('turns off the route presentation for an indoor setup without inventing a GPS failure', () => {
    const sources = workoutSourcePresentation(state(), { useWatch: true, indoor: true }, true);
    expect(sources.gps).toEqual({ label: 'Off for indoor ride', detail: 'No route recording', tone: 'neutral' });
    expect(sources.heartRate.detail).toBe('Apple Watch');
  });

  it('uses native active-workout settings and real stream states rather than bike link readiness', () => {
    const value = state();
    value.phase = 'running';
    value.useWatch = false;
    value.streams.cyc.status = 'waiting';
    value.streams.heartRate.status = 'stale';
    value.streams.gps.status = 'waiting';
    const sources = workoutSourcePresentation(value, { useWatch: true, indoor: true }, true);
    expect(sources.bike).toMatchObject({ label: 'Waiting for workout samples', tone: 'neutral' });
    expect(sources.heartRate).toMatchObject({ label: 'No recent samples', tone: 'warning' });
    expect(sources.gps).toMatchObject({ label: 'Waiting for GPS', detail: 'Phone', tone: 'neutral' });
  });

  it('preserves receiving samples, stale gaps and native indoor mode during a workout', () => {
    const value = state();
    value.phase = 'running';
    value.useWatch = true;
    value.indoor = true;
    value.recordGPS = false;
    value.streams.cyc.status = 'receiving';
    value.streams.heartRate.status = 'stale';
    const sources = workoutSourcePresentation(value, { useWatch: false, indoor: false }, false);
    expect(sources.bike).toMatchObject({ label: 'Receiving samples', tone: 'ready' });
    expect(sources.heartRate).toMatchObject({ label: 'No recent samples', detail: 'Apple Watch', tone: 'warning' });
    expect(sources.gps.label).toBe('Off for indoor ride');
  });

  it('renders active GPS permission and availability statuses in plain language', () => {
    const labels: Record<StreamStatus, string> = {
      receiving: 'Receiving GPS',
      waiting: 'Waiting for GPS',
      notDetermined: 'Location permission needed',
      denied: 'Location permission denied',
      restricted: 'Location access restricted',
      weak: 'Weak GPS signal',
      unavailable: 'GPS unavailable',
      stale: 'No recent GPS',
      paused: 'GPS paused',
      off: 'GPS off',
    };
    for (const [status, label] of Object.entries(labels)) {
      const value = state();
      value.phase = 'running';
      value.streams.gps.status = parseStreamStatus(status);
      expect(workoutSourcePresentation(value, { useWatch: false, indoor: false }, true).gps.label).toBe(label);
    }
  });

  it('shows GPS accuracy only for receiving fixes, never stale or invalid accuracy', () => {
    const value = state();
    value.phase = 'running';
    value.useWatch = true;
    value.streams.gps = { status: 'receiving', source: 'watch', accuracyMeters: 4.6 };
    expect(workoutSourcePresentation(value, { useWatch: false, indoor: false }, true).gps.detail).toBe('Watch · ±5 m');
    value.streams.gps.status = 'stale';
    expect(workoutSourcePresentation(value, { useWatch: false, indoor: false }, true).gps.detail).toBe('Watch');
    value.streams.gps.status = 'receiving';
    value.streams.gps.accuracyMeters = NaN;
    expect(workoutSourcePresentation(value, { useWatch: false, indoor: false }, true).gps.detail).toBe('Watch');
  });

  it('explains a paused GPS and permission results without exposing enum names', () => {
    const value = state();
    value.phase = 'paused';
    value.streams.gps.status = 'paused';
    expect(workoutSourcePresentation(value, { useWatch: false, indoor: false }, true).gps.label).toBe('GPS paused');
    expect(locationPermissionLabel('authorizedWhenInUse')).toBe('Location allowed while using the app');
    expect(locationPermissionLabel('unexpected')).toBe('Location permission status unavailable');
  });
});

describe('native stream payloads', () => {
  const payloads = [
    {
      name: 'Android',
      useWatch: false,
      capabilities: {
        phoneWorkout: true,
        watchWorkout: false,
        phoneHealth: true,
        watchHealth: false,
        healthProvider: 'healthConnect' as const,
        gps: true,
        foregroundOnly: false,
      },
      streams: {
        cyc: { status: 'receiving' },
        heartRate: { status: 'unavailable' },
        gps: { status: 'receiving', source: 'phone' as const, accuracyMeters: 4.6 },
      },
      gps: { label: 'Receiving GPS', detail: 'Phone · ±5 m', tone: 'ready' },
      heart: 'Not available on this device',
    },
    {
      name: 'iPhone',
      useWatch: false,
      capabilities: {
        phoneWorkout: true,
        watchWorkout: true,
        phoneHealth: true,
        watchHealth: true,
        healthProvider: 'appleHealth' as const,
        gps: true,
        foregroundOnly: false,
      },
      streams: {
        cyc: { status: 'receiving' },
        heartRate: { status: 'waiting' },
        gps: { status: 'weak', source: 'phone' as const, accuracyMeters: 70 },
      },
      gps: { label: 'Weak GPS signal', detail: 'Phone', tone: 'warning' },
      heart: 'Waiting for heart rate',
    },
    {
      name: 'Watch owner',
      useWatch: true,
      capabilities: {
        phoneWorkout: true,
        watchWorkout: true,
        phoneHealth: true,
        watchHealth: true,
        healthProvider: 'appleHealth' as const,
        gps: true,
        foregroundOnly: false,
      },
      streams: {
        cyc: { status: 'receiving' },
        heartRate: { status: 'receiving' },
        gps: { status: 'receiving', source: 'watch' as const, accuracyMeters: 8 },
      },
      gps: { label: 'Receiving GPS', detail: 'Watch · ±8 m', tone: 'ready' },
      heart: 'Receiving samples',
    },
  ];
  for (const fixture of payloads)
    it(`presents the ${fixture.name} wire payload`, () => {
      const value = normalizeWorkoutState({
        ...state(),
        supported: true,
        id: 'fixture-ride',
        phase: 'running',
        recordGPS: true,
        saveToHealth: fixture.name !== 'Android',
        useWatch: fixture.useWatch,
        capabilities: fixture.capabilities,
        streams: fixture.streams,
      });
      const sources = workoutSourcePresentation(value, { indoor: true, useWatch: false }, false);
      expect(sources.bike.label).toBe('Receiving samples');
      expect(sources.gps).toEqual(fixture.gps);
      expect(sources.heartRate.label).toBe(fixture.heart);
      expect(JSON.stringify(sources)).not.toContain('iPhone');
    });

  it.each([
    'futureNativeStatus',
    'GPS ±5 m',
    'GPS denied',
    'Allow location',
    'GPS unavailable',
    'GPS paused',
    'GPS saved',
    'Weak GPS',
    'GPS off',
    'inactive',
    'live',
    null,
    12,
  ])('normalizes unknown status %s to waiting before presentation', status => {
    const raw = {
      ...state(),
      phase: 'running' as const,
      streams: {
        cyc: { status },
        heartRate: { status },
        gps: { status, source: 'watch' as const, accuracyMeters: 5 },
      },
    };
    const value = normalizeWorkoutState(raw);
    expect(value.streams.cyc.status).toBe('waiting');
    expect(value.streams.heartRate.status).toBe('waiting');
    expect(value.streams.gps.status).toBe('waiting');
    expect(workoutSourcePresentation(value, { useWatch: false, indoor: false }, false).gps.label).toBe(
      'Waiting for GPS',
    );
    expect(raw.streams.gps.status).toBe(status);
  });

  it.each([NaN, Infinity, -Infinity, -1, '3', undefined, null])('clears invalid accuracy %s', value => {
    const raw = {
      ...state(),
      streams: {
        cyc: { status: 'receiving' },
        heartRate: { status: 'stale' },
        gps: { status: 'receiving', source: 'phone' as const, accuracyMeters: value },
      },
    };
    const normalized = normalizeWorkoutState(raw);
    expect(normalized.streams.gps).toEqual({
      status: 'receiving',
      source: 'phone',
      accuracyMeters: null,
    });
  });

  it('preserves valid zero accuracy', () => {
    const raw = state();
    raw.streams.gps = { status: 'receiving', source: 'phone', accuracyMeters: 0 };
    expect(normalizeWorkoutState(raw).streams.gps).toEqual(raw.streams.gps);
  });

  it('uses the active GPS producer independently of setup selections', () => {
    const value = state();
    value.phase = 'running';
    value.useWatch = true;
    value.streams.gps = { status: 'receiving', source: 'phone', accuracyMeters: 5 };
    expect(workoutSourcePresentation(value, { useWatch: true, indoor: false }, false).gps.detail).toBe('Phone · ±5 m');
  });
});

it.each(['off', 'unavailable'] as const)(
  'presents %s bike and heart-rate streams without implying that samples are pending',
  status => {
    const value = state();
    value.phase = 'running';
    value.useWatch = true;
    value.streams.cyc.status = status;
    value.streams.heartRate.status = status;
    const sources = workoutSourcePresentation(value, { indoor: false, useWatch: false }, true);
    expect(sources.bike.label).toBe(status === 'off' ? 'Not selected' : 'Bike unavailable');
    expect(sources.heartRate.label).toBe(status === 'off' ? 'Not selected' : 'Not available on this device');
  },
);
