import type { BrowserRide } from '../../src/services/browser-ride-store';
import type { WorkoutState } from '../../src/core/workouts';

export function capturePrefixAcknowledged(
  record: Pick<BrowserRide, 'telemetryCount' | 'revision'> | undefined,
  owner: Pick<WorkoutState, 'collectionRevision'>,
  count: number,
): boolean {
  return record?.telemetryCount === count && owner.collectionRevision === record.revision;
}
