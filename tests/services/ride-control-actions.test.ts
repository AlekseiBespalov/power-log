import { afterEach, expect, it, vi } from 'vitest';
import actions from '../fixtures/contract/presentation/actions.json';
import { presentationFailure } from '../../shared/js';
import { RideControlActions, type PresentedRideAction } from '../../src/services/ride-control-actions';
import { unavailableWorkoutState, type WorkoutState } from '../../src/core/workouts';

const state = (phase: string): WorkoutState => ({
  ...unavailableWorkoutState,
  id: phase === 'idle' ? null : actions.rideA,
  phase: phase as WorkoutState['phase'],
});
afterEach(() => vi.useRealTimers());

it.each(actions.outcomes)('delivers the $action fixture haptic only after a matching operation receipt', async row => {
  const haptic = vi.fn();
  const control = new RideControlActions(haptic);
  let finish!: () => void;
  const run = control.run(
    row.action as PresentedRideAction,
    state(row.phase),
    () =>
      new Promise<void>(resolve => {
        finish = resolve;
      }),
    actions.rideA,
  );
  await Promise.resolve();
  expect(haptic).not.toHaveBeenCalled();
  await expect(
    control.run(row.action as PresentedRideAction, state(row.phase), async () => {}, actions.rideA),
  ).rejects.toMatchObject({ code: 'actionPending', message: presentationFailure('actionPending').text });
  finish();
  await run;
  expect(haptic.mock.calls).toEqual([[row.haptic]]);
});

it('warns on a failed receipt and admits the next action', async () => {
  const haptic = vi.fn();
  const control = new RideControlActions(haptic);
  await expect(
    control.run('pause', state('running'), async () => {
      throw new Error('Owner rejected');
    }),
  ).rejects.toThrow('Owner rejected');
  await control.run('pause', state('running'), async () => {});
  expect(haptic.mock.calls).toEqual([['warning'], ['impact']]);
});

it.each(actions.start)('uses the rider Start admission fixture in $phase', async row => {
  const operation = vi.fn(async () => {});
  const haptic = vi.fn();
  const control = new RideControlActions(haptic);
  const run = control.run('start', state(row.phase), operation);
  if (row.rider) {
    await run;
    expect(operation).toHaveBeenCalledOnce();
    expect(haptic.mock.calls).toEqual([['success']]);
  } else {
    await expect(run).rejects.toThrow();
    expect(operation).not.toHaveBeenCalled();
    expect(haptic).not.toHaveBeenCalled();
  }
});

it.each(actions.unconfirmedStop)('uses the unconfirmed Stop admission fixture in $phase', async row => {
  const operation = vi.fn(async () => {});
  const haptic = vi.fn();
  const control = new RideControlActions(haptic);
  const run = control.run('stopUnconfirmed', state(row.phase), operation);
  if (row.admitted) {
    await run;
    expect(operation).toHaveBeenCalledOnce();
    expect(haptic.mock.calls).toEqual([['none']]);
  } else {
    await expect(run).rejects.toThrow();
    expect(operation).not.toHaveBeenCalled();
    expect(haptic).not.toHaveBeenCalled();
  }
});

it.each(actions.recovery)('uses the recovery surface fixture $name through the production adapter', async row => {
  const current = {
    ...state(row.phase),
    pendingAction: row.pending as WorkoutState['pendingAction'],
    foreignOwner: row.foreign ? ('anotherBrowserTab' as const) : null,
  };
  const operation = vi.fn(async () => {});
  const control = new RideControlActions(vi.fn());
  const availability = control.availability(row.action as PresentedRideAction, current, null, row.busy);
  expect(availability.admitted).toBe(row.admitted);
  const run = control.run(row.action as PresentedRideAction, current, operation, null, current.id, row.busy);
  if (row.admitted) {
    await run;
    expect(operation).toHaveBeenCalledOnce();
  } else {
    await expect(run).rejects.toMatchObject({ code: availability.error });
    expect(operation).not.toHaveBeenCalled();
  }
});

it.each([
  {
    name: 'paused Lap',
    phase: 'paused',
    action: 'lap',
    pending: null,
    currentRide: actions.rideA,
    selectedRide: actions.rideA,
    confirmation: null,
    error: 'actionUnavailable',
  },
  {
    name: 'native pending action',
    phase: 'running',
    action: 'pause',
    pending: 'lap',
    currentRide: actions.rideA,
    selectedRide: actions.rideA,
    confirmation: null,
    error: 'actionPending',
  },
  {
    name: 'changed Finish selection',
    phase: 'running',
    action: 'stop',
    pending: null,
    currentRide: actions.rideB,
    selectedRide: actions.rideA,
    confirmation: actions.rideA,
    error: 'rideChanged',
  },
  {
    name: 'stale Pause control',
    phase: 'running',
    action: 'pause',
    pending: null,
    currentRide: actions.rideB,
    selectedRide: actions.rideA,
    confirmation: null,
    error: 'rideChanged',
  },
])('rejects guarded $name before IO', async row => {
  const current = {
    ...state(row.phase),
    id: row.currentRide,
    pendingAction: row.pending as WorkoutState['pendingAction'],
  };
  const operation = vi.fn(async () => {});
  const haptic = vi.fn();
  const control = new RideControlActions(haptic);
  await expect(
    control.run(row.action as PresentedRideAction, current, operation, row.confirmation, row.selectedRide),
  ).rejects.toMatchObject({ code: row.error, message: presentationFailure(row.error).text });
  expect(operation).not.toHaveBeenCalled();
  expect(haptic).not.toHaveBeenCalled();
});

it('fences a late timed-out Start receipt while a replacement Start is pending', async () => {
  vi.useFakeTimers();
  let now = 0;
  const haptic = vi.fn();
  const control = new RideControlActions(haptic, () => now);
  let finishOld!: () => void;
  let finishNew!: () => void;
  const old = control
    .run(
      'start',
      state('idle'),
      () =>
        new Promise<void>(resolve => {
          finishOld = resolve;
        }),
    )
    .catch(() => {});
  await Promise.resolve();
  now = 45;
  await vi.advanceTimersByTimeAsync(45000);
  await old;
  const next = control.run(
    'start',
    state('idle'),
    () =>
      new Promise<void>(resolve => {
        finishNew = resolve;
      }),
  );
  await Promise.resolve();
  finishOld();
  await Promise.resolve();
  await expect(control.run('start', state('idle'), async () => {})).rejects.toMatchObject({ code: 'actionPending' });
  expect(haptic.mock.calls).toEqual([['warning']]);
  finishNew();
  await next;
  expect(haptic.mock.calls).toEqual([['warning'], ['success']]);
});

it('admits confirmed Finish and Discard for an interrupted ride without native recovery', async () => {
  const control = new RideControlActions(vi.fn());
  const retained: WorkoutState = { ...state('interrupted'), recoveryState: 'absent' };
  for (const action of ['stop', 'discard'] as const) {
    expect(control.availability(action, retained).admitted).toBe(false);
    expect(control.availability(action, retained, retained.id).admitted).toBe(true);
    await control.run(action, retained, async () => {}, retained.id);
  }
  expect(control.availability('start', retained).admitted).toBe(false);
  expect(control.availability('discard', state('interrupted'), retained.id).admitted).toBe(true);
  expect(control.availability('discard', { ...retained, recoveryState: 'unresolved' }, retained.id).admitted).toBe(
    true,
  );
});

it('admits confirmed quota discard while retaining foreign-owner and confirmation checks', async () => {
  const current = {
    ...state('interrupted'),
    capabilities: { ...unavailableWorkoutState.capabilities, foregroundOnly: true },
    pendingAction: null,
    failure: { code: 'storageFull', message: 'Full' },
  };
  const controls = new RideControlActions(vi.fn());
  expect(controls.availability('discard', current).admitted).toBe(false);
  expect(controls.availability('discard', { ...current, foreignOwner: 'anotherBrowserTab' }, current.id).admitted).toBe(
    false,
  );
  expect(controls.availability('discard', current, current.id).admitted).toBe(true);
  const discard = vi.fn(async () => {});
  await controls.run('discard', current, discard, current.id);
  expect(discard).toHaveBeenCalledOnce();
});
