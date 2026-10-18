import { presentationExternalControl } from '../../shared/js';
import { RideFailureError } from '../core/failures';
import type { ExternalRideControl, WorkoutState } from '../core/workouts';

export function externalRideDecision(state: WorkoutState, request: ExternalRideControl, confirmed: boolean) {
  if (!state.controlToken) throw new RideFailureError('rideChanged');
  return presentationExternalControl(
    state.id,
    state.controlToken,
    state.phase,
    state.pendingAction,
    request.rideId,
    request.controlToken,
    request.expectedPhase,
    request.action,
    confirmed,
  );
}

export async function finishExternalRide(
  state: WorkoutState,
  request: ExternalRideControl,
  confirmedRideId: string | null,
  discard: boolean,
  execute: (request: ExternalRideControl, discard: boolean) => Promise<unknown>,
) {
  const confirmed = confirmedRideId === request.rideId;
  const decision = externalRideDecision(state, request, confirmed);
  if (decision.outcome !== 'admitted') throw new RideFailureError(decision.error ?? 'finishConfirmationRequired');
  return execute(request, discard);
}
