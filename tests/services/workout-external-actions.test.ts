import { beforeEach, expect, it, vi } from 'vitest';
import actions from '../fixtures/contract/presentation/actions.json';
import {
  unavailableWorkoutState,
  normalizeWorkoutState,
  externalRideControl,
  type ExternalRideControl,
} from '../../src/core/workouts';
import { externalRideDecision, finishExternalRide } from '../../src/services/workout-external-actions';
import { workouts } from '../../src/services/workouts.native';

const native = vi.hoisted(() => ({ finishExternalWorkoutAction: vi.fn(), cancelExternalWorkoutAction: vi.fn() }));
vi.mock('../../modules/power-log-bridge', () => ({ default: native }));
const state = { ...unavailableWorkoutState, id: actions.rideA, controlToken: actions.token, phase: 'running' as const };
const request: ExternalRideControl = {
  rideId: actions.rideA,
  controlToken: actions.token,
  expectedPhase: 'running',
  action: 'finish',
};
beforeEach(() => {
  vi.clearAllMocks();
  native.finishExternalWorkoutAction.mockResolvedValue(state);
  native.cancelExternalWorkoutAction.mockResolvedValue(state);
});

it.each(actions.external.filter(row => row.action === 'finish'))(
  'requires confirmation through the shared action flow: $confirmed',
  async row => {
    const execute = vi.fn(async () => {});
    expect(externalRideDecision(state, request, row.confirmed).outcome).toBe(row.outcome);
    const operation = finishExternalRide(state, request, row.confirmed ? actions.rideA : null, false, execute);
    if (row.confirmed) {
      await operation;
      expect(execute).toHaveBeenCalledExactlyOnceWith(request, false);
    } else {
      await expect(operation).rejects.toMatchObject({ code: 'finishConfirmationRequired' });
      expect(execute).not.toHaveBeenCalled();
    }
  },
);
it.each([
  { controlToken: actions.oldToken },
  { phase: 'paused' as const },
  { id: actions.rideB },
  { pendingAction: 'lap' as const },
])('rejects a changed external confirmation before any IO: %j', async patch => {
  const execute = vi.fn(async () => {});
  await expect(
    finishExternalRide({ ...state, ...patch }, request, actions.rideA, false, execute),
  ).rejects.toMatchObject({ code: 'rideChanged' });
  expect(execute).not.toHaveBeenCalled();
});
it('rejects confirmation of another ride and preserves the shared Discard choice', async () => {
  const execute = vi.fn(async () => {});
  await expect(finishExternalRide(state, request, actions.rideB, false, execute)).rejects.toMatchObject({
    code: 'finishConfirmationRequired',
  });
  expect(execute).not.toHaveBeenCalled();
  await finishExternalRide(state, request, actions.rideA, true, execute);
  expect(execute).toHaveBeenCalledExactlyOnceWith(request, true);
});
it('passes the original request through the bridge for native revalidation and cancellation', async () => {
  await workouts.finishExternal!(request, false);
  expect(native.finishExternalWorkoutAction).toHaveBeenCalledExactlyOnceWith(request, false);
  await workouts.cancelExternal!(request);
  expect(native.cancelExternalWorkoutAction).toHaveBeenCalledExactlyOnceWith(request);
  expect(normalizeWorkoutState({ ...state, externalFinish: request }).externalFinish).toEqual(request);
});
it.each([
  null,
  { ...request, rideId: '../ride' },
  { ...request, controlToken: 'invalid' },
  { ...request, action: 'stop' },
  { ...request, expectedPhase: 1 },
  { ...request, extra: true },
])('rejects malformed requests at the production bridge boundary: %j', async value => {
  expect(() => externalRideControl(value)).toThrow();
  await expect(workouts.finishExternal!(value as ExternalRideControl, false)).rejects.toThrow();
  expect(native.finishExternalWorkoutAction).not.toHaveBeenCalled();
});
