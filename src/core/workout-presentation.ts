import {
  effectiveWorkoutOptions,
  workoutInProgress,
  type StreamStatus,
  type WorkoutOptions,
  type WorkoutState,
} from './workouts';

export type WorkoutSourcePresentation = {
  label: string;
  detail: string;
  tone: 'ready' | 'neutral' | 'warning';
};

export function workoutHealthPresentation(
  options: WorkoutOptions,
  capabilities: WorkoutState['capabilities'],
): { label: string; detail: string | null } {
  const effective = effectiveWorkoutOptions(options, capabilities);
  const label =
    capabilities.healthProvider === 'appleHealth'
      ? 'Apple Health'
      : capabilities.healthProvider === 'healthConnect'
        ? 'Health Connect'
        : 'Health';
  const unavailable =
    options.saveToHealth !== false &&
    !effective.saveToHealth &&
    (capabilities.phoneWorkout || capabilities.watchWorkout);
  return {
    label,
    detail: unavailable
      ? `${effective.useWatch ? 'Watch' : 'Phone'}-recorded rides cannot be saved to ${label} on this device. This ride will stay in Power Log.`
      : null,
  };
}

/** Health save and durable Watch receipt are separate steps. */
export function workoutFinishPresentation(state: WorkoutState): { label: string; detail: string } {
  if (state.pendingAction === 'discard') return { label: 'Discarding…', detail: 'Stopping and discarding ride…' };
  if (!state.useWatch) return { label: 'Saving…', detail: 'Saving ride…' };
  if (state.healthKitState === 'notRequested')
    return { label: 'Saving…', detail: 'Saving ride and syncing Watch data…' };
  if (state.healthKitState === 'saved')
    return {
      label: 'Syncing…',
      detail: 'Saved to Apple Health. Syncing Watch data…',
    };
  return {
    label: 'Finishing…',
    detail: 'Waiting for Watch to finish and sync…',
  };
}

export function locationPermissionLabel(status: string): string {
  switch (status) {
    case 'authorizedAlways':
      return 'Location allowed';
    case 'authorizedWhenInUse':
      return 'Location allowed while using the app';
    case 'denied':
      return 'Location permission denied';
    case 'restricted':
      return 'Location access restricted';
    case 'notDetermined':
      return 'Location permission needed';
    default:
      return 'Location permission status unavailable';
  }
}

/** Setup readiness is separate from measurements received by an active workout. */
export function workoutSourcePresentation(
  state: WorkoutState,
  options: WorkoutOptions,
  bikeReady: boolean,
): {
  bike: WorkoutSourcePresentation;
  heartRate: WorkoutSourcePresentation;
  gps: WorkoutSourcePresentation;
} {
  const active = workoutInProgress(state.phase);
  const selected = active ? state : effectiveWorkoutOptions(options, state.capabilities);
  const gpsEnabled = selected.recordGPS;
  const source = (active ? state.streams.gps.source === 'watch' : selected.useWatch) ? 'Watch' : 'Phone';
  const heartSource = selected.useWatch ? 'Apple Watch' : 'Compatible heart-rate source on phone';
  const gpsOff: WorkoutSourcePresentation = {
    label: selected.indoor ? 'Off for indoor ride' : 'Off',
    detail: 'No route recording',
    tone: 'neutral',
  };
  if (!active) {
    return {
      bike: bikeReady
        ? { label: 'Bike connected', detail: 'Workout samples start when you start the ride.', tone: 'ready' }
        : { label: 'Not connected', detail: 'Bike measurements are optional.', tone: 'neutral' },
      heartRate: selected.useWatch
        ? { label: 'Starts with workout', detail: heartSource, tone: 'neutral' }
        : { label: 'Not selected', detail: 'Heart rate is optional.', tone: 'neutral' },
      gps: !gpsEnabled ? gpsOff : { label: 'Starts with workout', detail: source, tone: 'neutral' },
    };
  }
  const samples = (
    status: StreamStatus,
    waiting: string,
    detail: string,
    unavailable: string,
  ): WorkoutSourcePresentation => {
    switch (status) {
      case 'receiving':
        return { label: 'Receiving samples', detail, tone: 'ready' };
      case 'stale':
        return { label: 'No recent samples', detail, tone: 'warning' };
      case 'off':
        return { label: 'Not selected', detail, tone: 'neutral' };
      case 'unavailable':
        return { label: unavailable, detail, tone: 'neutral' };
      case 'paused':
        return { label: 'Paused', detail, tone: 'neutral' };
      case 'weak':
        return { label: 'Weak signal', detail, tone: 'warning' };
      case 'denied':
      case 'restricted':
      case 'notDetermined':
      case 'waiting':
        return { label: waiting, detail, tone: 'neutral' };
    }
  };
  const bike = samples(
    state.streams.cyc.status,
    'Waiting for workout samples',
    'Rider power, cadence and motor readings',
    'Bike unavailable',
  );
  const heartRate =
    state.streams.heartRate.status !== 'unavailable' && !selected.useWatch && selected.saveToHealth === false
      ? { label: 'Not selected', detail: 'Heart rate is optional.', tone: 'neutral' as const }
      : samples(state.streams.heartRate.status, 'Waiting for heart rate', heartSource, 'Not available on this device');
  if (!gpsEnabled) return { bike, heartRate, gps: gpsOff };

  const status = state.streams.gps.status;
  let gps: WorkoutSourcePresentation;
  switch (status) {
    case 'receiving':
      gps = { label: 'Receiving GPS', detail: source, tone: 'ready' };
      break;
    case 'weak':
      gps = { label: 'Weak GPS signal', detail: source, tone: 'warning' };
      break;
    case 'stale':
      gps = { label: 'No recent GPS', detail: source, tone: 'warning' };
      break;
    case 'paused':
      gps = { label: 'GPS paused', detail: source, tone: 'neutral' };
      break;
    case 'off':
      gps = { label: 'GPS off', detail: source, tone: 'neutral' };
      break;
    case 'denied':
    case 'restricted':
    case 'notDetermined':
      gps = { label: locationPermissionLabel(status), detail: source, tone: 'warning' };
      break;
    case 'unavailable':
      gps = { label: 'GPS unavailable', detail: source, tone: 'warning' };
      break;
    case 'waiting':
      gps = { label: 'Waiting for GPS', detail: source, tone: 'neutral' };
      break;
  }
  const accuracy = state.streams.gps.accuracyMeters;
  if (status === 'receiving' && accuracy != null && Number.isFinite(accuracy) && accuracy >= 0) {
    gps.detail += ` · ±${Math.round(accuracy)} m`;
  }
  return { bike, heartRate, gps };
}
