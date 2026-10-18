import { RideActionPresentation } from '../../shared/js';
import { waitForRideAction } from './ride-action-deadline';
import { RideFailureError, failureCode } from '../core/failures';
import { workoutStartContext } from '../core/workout-presentation';
import type { WorkoutState } from '../core/workouts';

export type PresentedRideAction = 'start' | 'pause' | 'resume' | 'lap' | 'stop' | 'discard' | 'stopUnconfirmed';
export type RideHaptic = 'none' | 'success' | 'warning' | 'impact' | 'lightImpact';
const surface = (action: PresentedRideAction) => (action === 'stopUnconfirmed' ? 'recovery' : 'rider');
export const browserDiscardAvailable = (state: WorkoutState) =>
  state.capabilities.foregroundOnly &&
  (state.phase === 'interrupted' || state.recoveryState === 'unresolved') &&
  Boolean(state.failure);

export class RideControlActions {
  private presentation = new RideActionPresentation();
  private command = 0;
  constructor(
    private readonly haptic: (outcome: RideHaptic) => void,
    private readonly now = () => performance.now() / 1000,
  ) {}
  availability(
    action: PresentedRideAction,
    state: WorkoutState,
    confirmedRideId: string | null = null,
    adapterBusy = false,
  ) {
    return this.presentation.availability(
      action === 'start' ? null : state.id,
      action,
      state.phase,
      state.id,
      state.foreignOwner !== null,
      confirmedRideId,
      state.pendingAction,
      workoutStartContext(state),
      surface(action),
      adapterBusy,
    );
  }
  async run(
    action: PresentedRideAction,
    state: WorkoutState,
    operation: () => Promise<unknown>,
    confirmedRideId: string | null = null,
    selectedRideId: string | null = state.id,
    adapterBusy = false,
    signal?: AbortSignal,
  ) {
    const commandId = String(++this.command);
    const rideId = action === 'start' ? null : selectedRideId;
    const admitted = this.presentation.admit(
      commandId,
      rideId,
      action,
      state.phase,
      state.id,
      state.foreignOwner !== null,
      confirmedRideId,
      state.pendingAction,
      workoutStartContext(state),
      surface(action),
      adapterBusy,
    );
    if (!admitted.admitted) throw new RideFailureError(admitted.error ?? 'actionUnavailable');
    try {
      await waitForRideAction(operation, action === 'start' ? 'start' : 'recovery', this.now, signal);
    } catch (error) {
      const receipt = this.presentation.complete(commandId, rideId, failureCode(error));
      this.haptic(receipt.haptic as RideHaptic);
      throw error;
    }
    const receipt = this.presentation.complete(commandId, rideId, null);
    this.haptic(receipt.haptic as RideHaptic);
  }
}
