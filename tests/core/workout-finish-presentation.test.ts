import { describe, expect, it } from 'vitest';
import { workoutFinishPresentation } from '../../src/core/workout-presentation';
import { unavailableWorkoutState } from '../../src/core/workouts';

describe('ride finish presentation', () => {
  const state = { ...unavailableWorkoutState, phase: 'finishing' as const, useWatch: true };
  it('distinguishes Health save from pending Watch sync', () => {
    expect(workoutFinishPresentation({ ...state, healthKitState: 'saved' })).toEqual({
      label: 'Syncing…', detail: 'Saved to Apple Health. Syncing Watch data…',
    });
  });
  it('keeps syncing copy stable across transport queue updates', () => {
    for (const pendingMessages of [0, 1, 2, 100]) {
      expect(workoutFinishPresentation({ ...state, healthKitState: 'saved', watch: { ...state.watch, pendingMessages } }).detail)
        .toBe('Saved to Apple Health. Syncing Watch data…');
    }
  });
  it('does not claim a Health save without its confirmation', () => {
    for (const healthKitState of ['pending', 'failed', 'unknown']) {
      expect(workoutFinishPresentation({ ...state, healthKitState }).detail)
        .toBe('Waiting for Watch to finish and sync…');
    }
    expect(workoutFinishPresentation({ ...state, useWatch: false }).detail).toBe('Saving ride…');
  });
  it('does not imply a Health save when Health was not selected', () => {
    expect(workoutFinishPresentation({ ...state, healthKitState: 'notRequested', saveToHealth: false })).toEqual({
      label: 'Saving…', detail: 'Saving ride and syncing Watch data…',
    });
  });
});

it('shows discard rather than saving or archive progress', () => {
  for (const useWatch of [true, false]) {
    expect(workoutFinishPresentation({ ...unavailableWorkoutState, useWatch, phase: 'finishing', pendingAction: 'discard' }))
      .toEqual({ label: 'Discarding…', detail: 'Stopping and discarding ride…' });
  }
});
