import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { unavailableWorkoutState, type WorkoutState } from '../../src/core/workouts';
import { RideControlActions } from '../../src/services/ride-control-actions';
import { WorkoutActions } from '../../src/services/workout-actions';
import { workouts } from '../../src/services/workouts.native';

const native = vi.hoisted(() => ({
  markWorkoutLap: vi.fn(),
  pauseWorkout: vi.fn(),
  resumeWorkout: vi.fn(),
  stopWorkout: vi.fn(),
  discardWorkout: vi.fn(),
  recoverWorkout: vi.fn(),
  finishWorkoutWithoutWatch: vi.fn(),
  finishExternalWorkoutAction: vi.fn(),
  refreshWorkoutHealth: vi.fn(),
  retryWorkoutHealthSave: vi.fn(),
}));
vi.mock('../../modules/power-log-bridge', () => ({ default: native }));
const rideId = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa';
const state: WorkoutState = { ...unavailableWorkoutState, id: rideId, phase: 'running' };
const settle = async () => {
  for (let i = 0; i < 30; i++) await Promise.resolve();
};
const deferred = () => {
  let resolve!: () => void;
  let reject!: (error: Error) => void;
  const promise = new Promise<void>((yes, no) => {
    resolve = yes;
    reject = no;
  });
  return { promise, resolve, reject };
};
beforeEach(() => {
  vi.useFakeTimers();
  vi.resetAllMocks();
});
afterEach(() => vi.useRealTimers());

it('keeps explicit Health save retry separate from a read refresh at the native boundary', async () => {
  native.refreshWorkoutHealth.mockResolvedValue(state);
  native.retryWorkoutHealthSave.mockResolvedValue(state);
  await workouts.refreshHealth!(rideId);
  expect(native.refreshWorkoutHealth).toHaveBeenCalledExactlyOnceWith(rideId);
  expect(native.retryWorkoutHealthSave).not.toHaveBeenCalled();
  await workouts.retryHealthSave!(rideId);
  expect(native.retryWorkoutHealthSave).toHaveBeenCalledExactlyOnceWith(rideId);
  expect(native.refreshWorkoutHealth).toHaveBeenCalledTimes(1);
});

it.each(['lap', 'pause'] as const)('reuses a timed-out native %s while Stop remains available', async action => {
  const pending = deferred();
  const bridgeCall = action === 'lap' ? native.markWorkoutLap : native.pauseWorkout;
  let effects = 0;
  bridgeCall.mockImplementation(async () => {
    await pending.promise;
    effects++;
    return state;
  });
  native.stopWorkout.mockResolvedValue({ ...state, phase: 'completed' });
  const haptic = vi.fn();
  const controls = new RideControlActions(haptic);
  const actions = new WorkoutActions(() => {});
  const refresh = vi.fn(async () => {});
  const run = () =>
    actions.run(
      flight => controls.run(action, state, () => workouts[action](rideId), null, rideId, false, flight.signal),
      refresh,
    );
  const first = run();
  await vi.advanceTimersByTimeAsync(15000);
  await first;
  expect(actions.snapshot().busy).toBe(false);
  expect(controls.availability('stop', state, rideId).admitted).toBe(true);
  expect(effects).toBe(0);
  const retried = run();
  await settle();
  pending.resolve();
  await retried;
  expect(bridgeCall).toHaveBeenCalledExactlyOnceWith(rideId);
  expect(effects).toBe(1);
  expect(refresh).toHaveBeenCalledTimes(1);
  expect(haptic.mock.calls).toEqual([['warning'], [action === 'lap' ? 'lightImpact' : 'impact']]);
  expect(actions.snapshot()).toMatchObject({ busy: false, error: null });
});

it.each(['lap', 'pause'] as const)(
  'fences an expired %s retry when its native result arrives during Stop',
  async action => {
    const pending = deferred();
    const stopping = deferred();
    const bridgeCall = action === 'lap' ? native.markWorkoutLap : native.pauseWorkout;
    let effects = 0;
    bridgeCall.mockImplementation(async () => {
      await pending.promise;
      effects++;
      return state;
    });
    native.stopWorkout.mockImplementation(async () => {
      await stopping.promise;
      return { ...state, phase: 'completed' };
    });
    const haptic = vi.fn();
    const controls = new RideControlActions(haptic);
    const actions = new WorkoutActions(() => {});
    const refresh = vi.fn(async () => {});
    const run = (action: 'lap' | 'pause' | 'stop') =>
      actions.run(
        flight => controls.run(action, state, () => workouts[action](rideId), rideId, rideId, false, flight.signal),
        refresh,
      );
    const first = run(action);
    await vi.advanceTimersByTimeAsync(15000);
    await first;
    const retry = run(action);
    await vi.advanceTimersByTimeAsync(15000);
    await retry;
    const stop = run('stop');
    await settle();
    expect(native.stopWorkout).toHaveBeenCalledExactlyOnceWith(rideId);
    const hapticsBeforeReceipt = haptic.mock.calls.slice();
    pending.resolve();
    await settle();
    expect(bridgeCall).toHaveBeenCalledTimes(1);
    expect(effects).toBe(1);
    expect(actions.snapshot()).toMatchObject({ busy: true, error: null });
    expect(haptic.mock.calls).toEqual(hapticsBeforeReceipt);
    expect(refresh).not.toHaveBeenCalled();
    stopping.resolve();
    await stop;
    expect(actions.snapshot().busy).toBe(false);
    expect(haptic.mock.calls.at(-1)).toEqual(['success']);
  },
);

it('releases a rejected invocation and keeps different rides independent', async () => {
  const pending = deferred();
  const otherId = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb';
  native.markWorkoutLap.mockImplementation(async id => {
    if (id === rideId) await pending.promise;
    return { ...state, id };
  });
  const first = workouts.lap(rideId).catch(error => error);
  const retry = workouts.lap(rideId).catch(error => error);
  await workouts.lap(otherId);
  pending.reject(new Error('Native rejection'));
  expect(await first).toBe(await retry);
  expect(native.markWorkoutLap).toHaveBeenCalledTimes(2);
  native.markWorkoutLap.mockResolvedValue(state);
  await workouts.lap(rideId);
  expect(native.markWorkoutLap).toHaveBeenCalledTimes(3);
});
