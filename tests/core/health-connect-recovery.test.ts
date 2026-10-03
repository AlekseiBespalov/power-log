import { describe, expect, it } from 'vitest';
import { workoutExportReady, workoutRecoveryAction, type WorkoutMetadata } from '../../src/core/workouts';

const record: WorkoutMetadata = {
  schemaVersion: 1,
  id: 'android-clock-ride',
  elapsedSeconds: 3600,
  startedAt: '2026-09-10T10:00:00Z',
  endedAt: '2026-09-10T09:59:00Z',
  phase: 'completed',
  indoor: false,
  watchEnabled: false,
  saveToHealth: true,
  recordGPS: true,
  storage: 'native',
  healthProvider: 'healthConnect',
  sport: 'cycling',
  subSport: 'eBiking',
  eventCount: 10,
  interrupted: false,
  healthKitState: 'unavailable',
  healthReason: 'Health Connect is unavailable because the clock cutoff is not after the start.',
  watchSyncState: 'notRequired',
  sealRevision: 3,
  verifiedSealRevision: 3,
  finalizationState: 'complete',
};

describe('Health Connect result recovery', () => {
  it('offers repair only for retryable notSaved while preserving local export for terminal failure', () => {
    expect(workoutRecoveryAction({ ...record, healthKitState: 'notSaved' }, null)).toBe('repair');
    for (const healthKitState of ['unavailable', 'pending', 'saved', 'notRequested']) {
      const ended = { ...record, healthKitState };
      expect(workoutRecoveryAction(ended, null)).toBeNull();
      expect(workoutRecoveryAction(ended, record.id)).toBeNull();
      expect(workoutRecoveryAction(ended, 'another-active-ride')).toBeNull();
      expect(workoutExportReady(ended)).toBe(true);
    }
    expect(workoutRecoveryAction({ ...record, saveToHealth: false, healthKitState: 'notSaved' }, null)).toBeNull();
  });
});
