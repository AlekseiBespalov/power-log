import { beforeEach, describe, expect, it, vi } from 'vitest';
import { workouts } from '../../src/services/workouts.native';
import { unavailableWorkoutState, type WorkoutState } from '../../src/core/workouts';

const native = vi.hoisted(() => ({ getWorkoutState: vi.fn(), addListener: vi.fn(), startWorkout: vi.fn() }));
vi.mock('../../modules/cyc-bridge', () => ({ default: native }));
beforeEach(() => {
  vi.clearAllMocks();
});
const payload = () => ({
  ...unavailableWorkoutState,
  streams: {
    cyc: { status: 'future' },
    heartRate: { status: 'unavailable' },
    gps: { status: 'weak', source: 'phone', accuracyMeters: -Infinity },
  },
});
const expected = {
  cyc: { status: 'waiting' },
  heartRate: { status: 'unavailable' },
  gps: { status: 'weak', source: 'phone', accuracyMeters: null },
};

describe('native workout stream boundary', () => {
  it('normalizes state reads', async () => {
    native.getWorkoutState.mockResolvedValue(payload());
    expect((await workouts.getState()).streams).toEqual(expected);
  });
  it('normalizes every subscription payload and releases the native listener', () => {
    const remove = vi.fn(),
      listener = vi.fn<(state: WorkoutState) => void>();
    native.addListener.mockReturnValue({ remove });
    const unsubscribe = workouts.subscribe(listener);
    expect(native.addListener.mock.calls[0]![0]).toBe('onWorkoutState');
    native.addListener.mock.calls[0]![1](payload());
    expect(listener.mock.calls[0]![0].streams).toEqual(expected);
    unsubscribe();
    expect(remove).toHaveBeenCalledOnce();
  });
  it('normalizes command results as well as notifications', async () => {
    native.startWorkout.mockResolvedValue(payload());
    expect((await workouts.start({ indoor: false, useWatch: false })).streams).toEqual(expected);
  });
});
