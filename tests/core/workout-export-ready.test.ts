import { describe, expect, it } from 'vitest';
import { workoutExportReady, type WorkoutMetadata } from '../../src/core/workouts';
const ride: WorkoutMetadata = { schemaVersion: 2, id: 'test-ride', startedAt: '2026-01-01T00:00:00Z', endedAt: '2026-01-01T01:00:00Z', phase: 'completed', indoor: true, watchEnabled: true, sport: 'cycling', subSport: 'indoorCycling', eventCount: 2, interrupted: false, healthKitState: 'saved', healthKitUUID: 'test-health', warnings: [], watchSyncState: 'received' };
describe('current snapshot export gate', () => {
  it('does not equate ended, Health saved, or Watch transport received with a verified seal', () => {
    expect(workoutExportReady(ride)).toBe(false);
    expect(workoutExportReady({ ...ride, sealRevision: 2, verifiedSealRevision: 1, finalizationState: 'pending' })).toBe(false);
  });
  it('permits the verified complete or warned partial current snapshot', () => {
    for (const finalizationState of ['complete', 'partial'] as const) expect(workoutExportReady({ ...ride, sealRevision: 2, verifiedSealRevision: 2, finalizationState })).toBe(true);
  });
  it('invalidates readiness when a higher seal arrives or an outcome is still pending', () => {
    expect(workoutExportReady({ ...ride, sealRevision: 3, verifiedSealRevision: 2, finalizationState: 'complete' })).toBe(false);
    expect(workoutExportReady({ ...ride, sealRevision: 2, verifiedSealRevision: 2, finalizationState: 'pending' })).toBe(false);
  });
});
