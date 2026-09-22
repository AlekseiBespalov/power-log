import { describe, expect, it } from 'vitest';
import { unavailableWorkoutState, workoutCanDelete, type WorkoutMetadata, type WorkoutPhase } from '../../src/core/workouts';

const record: WorkoutMetadata = { schemaVersion: 1, id: 'saved-ride', startedAt: '2026-09-10T00:00:00Z', phase: 'completed', indoor: true, watchEnabled: true, sport: 'cycling', subSport: 'eBike', eventCount: 12, interrupted: false, healthKitState: 'saved', warnings: [], watchSyncState: 'received' };

describe('ride deletion eligibility', () => {
  it('allows finished rides and resolved failed attempts even before sync verification', () => {
    for (const phase of ['completed', 'failed']) {
      expect(workoutCanDelete({ ...record, phase, watchSyncState: 'pending', finalizationState: 'partial' }, unavailableWorkoutState)).toBe(true);
    }
  });
  it('never offers deletion in place of finishing an active or unresolved ride', () => {
    for (const phase of ['preparing', 'running', 'paused', 'finishing', 'recoverable']) {
      expect(workoutCanDelete({ ...record, phase }, unavailableWorkoutState)).toBe(false);
    }
  });
  it('uses current owner state to reject an outdated terminal catalog row', () => {
    for (const phase of ['preparing', 'running', 'paused', 'finishing', 'recoverable'] as WorkoutPhase[]) {
      expect(workoutCanDelete(record, { ...unavailableWorkoutState, id: record.id, phase })).toBe(false);
    }
    expect(workoutCanDelete(record, { ...unavailableWorkoutState, id: record.id, phase: 'completed', pendingAction: 'stop' })).toBe(false);
    expect(workoutCanDelete(record, { ...unavailableWorkoutState, id: record.id, phase: 'failed', recoveryState: 'checking' })).toBe(false);
  });
  it('does not confuse a separate newer ride with the selected historical record', () => {
    expect(workoutCanDelete(record, { ...unavailableWorkoutState, id: 'new-ride', phase: 'running' })).toBe(true);
    expect(workoutCanDelete(record, { ...unavailableWorkoutState, id: record.id, phase: 'completed' })).toBe(true);
  });
});
