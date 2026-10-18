import { expect, it, vi } from 'vitest';
import { completionOpenExport } from '../../shared/js';
import {
  normalizeWorkoutState,
  unavailableWorkoutState,
  workoutCanDelete,
  workoutExportReady,
  type WorkoutMetadata,
  type WorkoutState,
} from '../../src/core/workouts';
import { normalizeWorkoutMetadata } from '../../src/core/workout-payload';
import { WorkoutStateDelivery } from '../../src/services/workout-state-delivery';
const record: WorkoutMetadata = {
  schemaVersion: 1,
  id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
  startedAt: '2026-01-01T00:00:00Z',
  endedAt: '2026-01-01T00:01:00Z',
  elapsedSeconds: 60,
  phase: 'completed',
  indoor: false,
  watchEnabled: true,
  eventCount: '17',
  interrupted: false,
  healthKitState: 'pending',
  collectionRevision: '4',
};
it.each(['pending', 'saved', 'failed', 'unknown', 'notRequested'] as const)(
  'opens local completed data while Health is %s',
  healthKitState => {
    const value = normalizeWorkoutMetadata({ ...record, healthKitState });
    expect(workoutExportReady(value)).toBe(true);
    expect(completionOpenExport(JSON.stringify(value), value.id, '4')).toBe(true);
    expect(completionOpenExport(JSON.stringify(value), value.id, '3')).toBe(false);
  },
);
it.each(['running', 'paused', 'interrupted', 'finishing'])('requires local Finish before exporting %s', phase => {
  expect(workoutExportReady({ ...record, phase, endedAt: undefined })).toBe(false);
});
it('requires an ended revision and honors native read admission', () => {
  for (const patch of [
    { endedAt: undefined },
    { collectionRevision: undefined },
    { collectionRevision: '0' },
    { exportEligible: false },
  ])
    expect(workoutExportReady({ ...record, ...patch })).toBe(false);
});
it('keeps History deletion separate from active Discard', () => {
  expect(workoutCanDelete(record, unavailableWorkoutState)).toBe(true);
  expect(
    workoutCanDelete(
      { ...record, phase: 'interrupted' },
      { ...unavailableWorkoutState, id: record.id, phase: 'interrupted' },
    ),
  ).toBe(false);
});
it('does not publish lower revisions or let a delayed refresh change selection', () => {
  vi.useFakeTimers();
  const delivered: WorkoutState[] = [];
  const delivery = new WorkoutStateDelivery(value => delivered.push(value));
  const current = normalizeWorkoutState({ ...unavailableWorkoutState, ...record, phase: 'completed' });
  try {
    delivery.receive(current);
    delivery.receive({ ...current, collectionRevision: '3' });
    expect(delivered).toHaveLength(1);
    const token = delivery.beginRefresh();
    delivery.receive({ ...current, id: 'new-ride', phase: 'running' });
    delivery.finishRefresh(token, current);
    expect(delivered.at(-1)?.id).toBe('new-ride');
  } finally {
    delivery.setActive(false);
    vi.useRealTimers();
  }
});
