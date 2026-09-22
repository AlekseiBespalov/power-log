import { describe, expect, it } from 'vitest';
import { locationPermissionLabel, workoutSourcePresentation } from '../../src/core/workout-presentation';
import { unavailableWorkoutState, type WorkoutState } from '../../src/core/workouts';

function state(): WorkoutState { return structuredClone(unavailableWorkoutState); }

describe('workout source presentation', () => {
  it('shows bike link readiness and selected Watch sources before any workout samples exist', () => {
    const value = state();
    value.streams.gps.status = 'authorizedWhenInUse';
    const sources = workoutSourcePresentation(value, { useWatch: true, indoor: false }, true);
    expect(sources.bike.label).toBe('Bike connected');
    expect(sources.bike.detail).toContain('start when you start');
    expect(sources.heartRate).toMatchObject({ label: 'Starts with workout', detail: 'Apple Watch', tone: 'neutral' });
    expect(sources.gps).toMatchObject({ label: 'Starts with workout', detail: 'Apple Watch', tone: 'neutral' });
    expect(JSON.stringify(sources)).not.toContain('Receiving');
  });

  it('uses new setup selections instead of a completed ride’s measurements or source', () => {
    const value = state();
    value.phase = 'completed'; value.useWatch = true;
    value.streams.cyc.status = 'receiving'; value.streams.heartRate.status = 'receiving';
    value.streams.gps = { status: 'receiving', source: 'watch', accuracyMeters: 3, lastSampleAgeSeconds: 1 };
    const sources = workoutSourcePresentation(value, { useWatch: false, indoor: false }, false);
    expect(sources.bike.label).toBe('Not connected');
    expect(sources.heartRate.label).toBe('Not selected');
    expect(sources.gps).toMatchObject({ label: 'Starts with workout', detail: 'iPhone' });
  });

  it('turns off the route presentation for an indoor setup without inventing a GPS failure', () => {
    const sources = workoutSourcePresentation(state(), { useWatch: true, indoor: true }, true);
    expect(sources.gps).toEqual({ label: 'Off for indoor ride', detail: 'No route recording', tone: 'neutral' });
    expect(sources.heartRate.detail).toBe('Apple Watch');
  });

  it('uses native active-workout settings and real stream states rather than bike link readiness', () => {
    const value = state();
    value.phase = 'running'; value.useWatch = false;
    value.streams.cyc.status = 'missing'; value.streams.heartRate.status = 'stale';
    value.streams.gps.status = 'authorizedWhenInUse';
    const sources = workoutSourcePresentation(value, { useWatch: true, indoor: true }, true);
    expect(sources.bike).toMatchObject({ label: 'Waiting for workout samples', tone: 'neutral' });
    expect(sources.heartRate).toMatchObject({ label: 'No recent samples', tone: 'warning' });
    expect(sources.gps).toMatchObject({ label: 'Waiting for GPS', detail: 'iPhone', tone: 'neutral' });
  });

  it('preserves receiving samples, stale gaps and native indoor mode during a workout', () => {
    const value = state();
    value.phase = 'running'; value.useWatch = true; value.indoor = true;
    value.streams.cyc.status = 'receiving'; value.streams.heartRate.status = 'stale';
    const sources = workoutSourcePresentation(value, { useWatch: false, indoor: false }, false);
    expect(sources.bike).toMatchObject({ label: 'Receiving samples', tone: 'ready' });
    expect(sources.heartRate).toMatchObject({ label: 'No recent samples', detail: 'Apple Watch', tone: 'warning' });
    expect(sources.gps.label).toBe('Off for indoor ride');
  });

  it('renders active GPS permission and availability statuses in plain language', () => {
    const labels = {
      authorizedAlways: 'Waiting for GPS', notDetermined: 'Location permission needed',
      denied: 'Location permission denied', restricted: 'Location access restricted',
      unavailable: 'GPS unavailable', stale: 'No recent GPS', futureNativeStatus: 'Waiting for GPS',
      'GPS denied': 'Location permission denied', 'Allow location': 'Location permission needed', 'Weak GPS': 'Weak GPS signal',
    };
    for (const [status, label] of Object.entries(labels)) {
      const value = state(); value.phase = 'running'; value.streams.gps.status = status;
      expect(workoutSourcePresentation(value, { useWatch: false, indoor: false }, true).gps.label).toBe(label);
    }
  });

  it('shows GPS accuracy only for receiving fixes, never stale or invalid accuracy', () => {
    const value = state(); value.phase = 'running'; value.useWatch = true;
    value.streams.gps = { status: 'receiving', source: 'watch', accuracyMeters: 4.6, lastSampleAgeSeconds: 1 };
    expect(workoutSourcePresentation(value, { useWatch: false, indoor: false }, true).gps.detail).toBe('Apple Watch · ±5 m');
    value.streams.gps.status = 'GPS ±5 m';
    expect(workoutSourcePresentation(value, { useWatch: false, indoor: false }, true).gps.label).toBe('Receiving GPS');
    value.streams.gps.lastSampleAgeSeconds = null;
    expect(workoutSourcePresentation(value, { useWatch: false, indoor: false }, true).gps.label).toBe('Waiting for GPS');
    value.streams.gps.status = 'stale';
    expect(workoutSourcePresentation(value, { useWatch: false, indoor: false }, true).gps.detail).toBe('Apple Watch');
    value.streams.gps.status = 'receiving'; value.streams.gps.accuracyMeters = NaN;
    expect(workoutSourcePresentation(value, { useWatch: false, indoor: false }, true).gps.detail).toBe('Apple Watch');
  });

  it('explains a paused GPS and permission results without exposing enum names', () => {
    const value = state(); value.phase = 'paused'; value.streams.gps.status = 'inactive';
    expect(workoutSourcePresentation(value, { useWatch: false, indoor: false }, true).gps.label).toBe('GPS paused');
    expect(locationPermissionLabel('authorizedWhenInUse')).toBe('Location allowed while using the app');
    expect(locationPermissionLabel('unexpected')).toBe('Location permission status unavailable');
  });
});
