import { expect, it } from 'vitest';
import { normalizeWorkoutMetadata } from '../../src/core/workout-payload';
import { unavailableWorkoutState, type WorkoutMetadata } from '../../src/core/workouts';
import { workoutHealthResultPresentation, workoutSourcePresentation } from '../../src/core/workout-presentation';

const ride: WorkoutMetadata = {
  schemaVersion: 1,
  id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
  startedAt: '2026-01-01T00:00:00Z',
  endedAt: '2026-01-01T00:00:01Z',
  elapsedSeconds: 1,
  phase: 'completed',
  indoor: true,
  watchEnabled: false,
  saveToHealth: true,
  eventCount: '3',
  healthKitState: 'saved',
  interrupted: false,
  collectionRevision: '2',
};
const denied = {
  component: 'cycling-cadence' as const,
  sourceRevision: '2',
  required: true,
  state: 'unavailable' as const,
  reason: 'permission' as const,
  providerIds: [],
};

it.each(['unavailable', 'denied', 'restricted', 'paused', 'stale', 'off'] as const)(
  'does not call Health Connect heart-rate status %s received backfill',
  status => {
    const state = {
      ...unavailableWorkoutState,
      phase: 'running' as const,
      saveToHealth: true,
      capabilities: { ...unavailableWorkoutState.capabilities, healthProvider: 'healthConnect' as const },
      streams: { ...unavailableWorkoutState.streams, heartRate: { status } },
    };
    const result = workoutSourcePresentation(state, { indoor: true }, false).heartRate;
    expect(result.label).not.toMatch(/backfill/i);
    if (status === 'unavailable') expect(result.label).toBe('Not available on this device');
    if (status === 'denied') expect(result.label).toBe('Heart-rate access denied');
    if (status === 'restricted') expect(result.label).toBe('Heart-rate access restricted');
  },
);

it.each(['appleHealth', 'healthConnect'] as const)('uses shared readable reasons for %s', healthProvider => {
  const provider = healthProvider === 'appleHealth' ? 'Apple Health' : 'Health Connect';
  for (const reason of ['providerDeleted', 'outcomeAbsent', 'nativeOwnerLost', 'readbackUnavailable'] as const) {
    const result = workoutHealthResultPresentation({
      ...ride,
      healthProvider,
      componentResults: [{ ...denied, component: 'workout', reason }],
    });
    expect(result.details[0]).not.toContain(reason);
    expect(result.details[0]).toContain(provider);
  }
  expect(
    workoutHealthResultPresentation({
      ...ride,
      healthProvider,
      healthKitState: 'unavailable',
      healthReason: 'nativeOwnerLost',
    }).details,
  ).toEqual([`The original ${provider} recording owner is unavailable. Local originals are retained in Power Log.`]);
});

it('preserves the iPhone terminal clock wording and presents closed states as words', () => {
  const clock =
    'Health is unavailable because the retained finish UTC is not after the workout start UTC. Local originals are retained.';
  expect(
    workoutHealthResultPresentation({ ...ride, healthKitState: 'unavailable', healthReason: 'clock' }).details,
  ).toEqual([clock]);
  expect(
    workoutHealthResultPresentation({ ...ride, healthKitState: 'unavailable', healthReason: clock }).details,
  ).toEqual([]);
  expect(workoutHealthResultPresentation({ ...ride, healthKitState: 'notSaved' }).label).toBe('Apple Health not saved');
  expect(workoutHealthResultPresentation({ ...ride, healthKitState: 'unknown' }).label).toBe(
    'Apple Health save unconfirmed',
  );
});

it('describes an unconfirmed Health request without implying automatic replay', () => {
  const result = workoutHealthResultPresentation({
    ...ride,
    healthKitState: 'saved',
    componentResults: [
      {
        component: 'rider-power',
        sourceRevision: '1',
        required: true,
        state: 'failed',
        reason: 'transientFailure',
        providerIds: [],
      },
    ],
  });
  expect(result.details.join(' ')).toContain('Your local ride is available.');
  expect(result.details.join(' ')).not.toContain('retry action');
  expect(result.details.join(' ')).not.toContain('will retry');
});

it('presents a native metadata payload with the clock reason code', () => {
  const metadata = normalizeWorkoutMetadata({ ...ride, healthKitState: 'unavailable', healthReason: 'clock' });
  expect(workoutHealthResultPresentation(metadata).details).toEqual([
    'Health is unavailable because the retained finish UTC is not after the workout start UTC. Local originals are retained.',
  ]);
});
