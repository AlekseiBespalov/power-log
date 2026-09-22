import { describe, expect, it } from 'vitest';
import { workoutRecoveryAction, type WorkoutMetadata } from '../../src/core/workouts';
const record: WorkoutMetadata = { schemaVersion: 1, id: 'old-phone', startedAt: '2026-09-10T00:00:00Z', phase: 'completed', indoor: false, watchEnabled: false, sport: 'cycling', subSport: 'eBike', eventCount: 10, interrupted: false, healthKitState: 'saved', warnings: [], watchSyncState: 'notRequired', collectionRevision: 12, sealRevision: 3, verifiedSealRevision: 3, finalizationState: 'partial' };
describe('saved ride recovery action', () => {
  it('exposes repair for the selected historical saved phone ride without rebinding current owner', () => {
    expect(workoutRecoveryAction(record, 'current-watch')).toBe('repair');
    expect(workoutRecoveryAction({ ...record, finalizationState: 'complete' }, 'current-watch')).toBeNull();
  });
  it('does not expose unsupported historical Watch or unsaved phone repair', () => {
    expect(workoutRecoveryAction({ ...record, watchEnabled: true }, 'current-watch')).toBeNull();
    expect(workoutRecoveryAction({ ...record, healthKitState: 'pending' }, 'current-watch')).toBeNull();
  });
  it('allows current-owner recovery even while a preparing action is pending', () => {
    expect(workoutRecoveryAction({ ...record, phase: 'preparing', watchEnabled: true, healthKitState: 'pending' }, record.id)).toBe('recover');
  });
  it('repairs a historical local-only phone archive without changing the selected owner', () => {
    const local = { ...record, saveToHealth: false, healthKitState: 'notRequested', endedAt: '2026-09-10T01:00:00Z', finalizationState: 'pending' as const };
    expect(workoutRecoveryAction(local, 'another-active-ride')).toBe('repair');
    expect(workoutRecoveryAction({ ...local, finalizationState: 'complete' }, 'another-active-ride')).toBeNull();
    expect(workoutRecoveryAction({ ...local, watchEnabled: true }, 'another-active-ride')).toBeNull();
    expect(workoutRecoveryAction({ ...local, endedAt: undefined }, 'another-active-ride')).toBeNull();
  });
});
