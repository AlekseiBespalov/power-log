import { describe, expect, it } from 'vitest';
import { workoutFinishPresentation } from '../../src/core/workout-presentation';
import { unavailableWorkoutState } from '../../src/core/workouts';

describe('local ride finish presentation', () => {
  it.each(['pending', 'saved', 'failed', 'unknown', 'notRequested'] as const)(
    'finishes the local ride independently of Health outcome %s',
    healthKitState => {
      for (const useWatch of [true, false]) {
        expect(
          workoutFinishPresentation({ ...unavailableWorkoutState, phase: 'finishing', useWatch, healthKitState }),
        ).toEqual({ label: 'Saving…', detail: 'Saving local ride…' });
      }
    },
  );
  it('presents local deletion without waiting for a native acknowledgement', () => {
    expect(
      workoutFinishPresentation({
        ...unavailableWorkoutState,
        useWatch: true,
        phase: 'finishing',
        pendingAction: 'discard',
      }),
    ).toEqual({ label: 'Discarding…', detail: 'Discarding local ride…' });
  });
});
