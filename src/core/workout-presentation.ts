import { workoutInProgress, type WorkoutOptions, type WorkoutState } from './workouts';

export type WorkoutSourcePresentation = {
  label: string;
  detail: string;
  tone: 'ready' | 'neutral' | 'warning';
};

/** Health save and durable Watch receipt are separate steps. */
export function workoutFinishPresentation(state: WorkoutState): { label: string; detail: string } {
  if (state.pendingAction === 'discard') return { label: 'Discarding…', detail: 'Stopping and discarding ride…' };
  if (!state.useWatch) return { label: 'Saving…', detail: 'Saving ride…' };
  if (state.healthKitState === 'notRequested') return { label: 'Saving…', detail: 'Saving ride and syncing Watch data…' };
  if (state.healthKitState === 'saved') return {
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
    case 'authorizedAlways': return 'Location allowed';
    case 'authorizedWhenInUse': return 'Location allowed while using the app';
    case 'denied': return 'Location permission denied';
    case 'restricted': return 'Location access restricted';
    case 'notDetermined': return 'Location permission needed';
    default: return 'Location permission status unavailable';
  }
}

/** Setup readiness is separate from measurements received by an active workout. */
export function workoutSourcePresentation(state: WorkoutState, options: WorkoutOptions, bikeReady: boolean): {
  bike: WorkoutSourcePresentation;
  heartRate: WorkoutSourcePresentation;
  gps: WorkoutSourcePresentation;
} {
  const active = workoutInProgress(state.phase);
  const selected = active ? state : options;
  const gpsEnabled = selected.recordGPS ?? !selected.indoor;
  const source = selected.useWatch ? 'Apple Watch' : 'iPhone';
  const heartSource = selected.useWatch ? 'Apple Watch' : 'Compatible heart-rate source on iPhone';
  const gpsOff: WorkoutSourcePresentation = { label: selected.indoor ? 'Off for indoor ride' : 'Off', detail: 'No route recording', tone: 'neutral' };
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
  const samples = (status: string, waiting: string, detail: string): WorkoutSourcePresentation => {
    if (status === 'receiving') return { label: 'Receiving samples', detail, tone: 'ready' };
    if (status === 'stale') return { label: 'No recent samples', detail, tone: 'warning' };
    return { label: waiting, detail, tone: 'neutral' };
  };
  const bike = samples(state.streams.cyc.status, 'Waiting for workout samples', 'Rider power, cadence and motor readings');
  const heartRate = !selected.useWatch && selected.saveToHealth === false
    ? { label: 'Not selected', detail: 'Heart rate is optional.', tone: 'neutral' as const }
    : samples(state.streams.heartRate.status, 'Waiting for heart rate', heartSource);
  if (!gpsEnabled) return { bike, heartRate, gps: gpsOff };

  let status = state.streams.gps.status;
  // Watch status messages also carry display labels. An old accuracy label alone
  // is not evidence of a current location fix.
  if (/^GPS ±\d+ m$/.test(status)) {
    const age = state.streams.gps.lastSampleAgeSeconds;
    status = age != null && Number.isFinite(age) && age >= 0 ? (age <= 10 ? 'receiving' : 'stale') : 'waiting';
  }
  let gps: WorkoutSourcePresentation;
  switch (status) {
    case 'receiving': gps = { label: 'Receiving GPS', detail: source, tone: 'ready' }; break;
    case 'stale': gps = { label: 'No recent GPS', detail: source, tone: 'warning' }; break;
    case 'denied':
    case 'GPS denied': gps = { label: locationPermissionLabel('denied'), detail: source, tone: 'warning' }; break;
    case 'restricted':
    case 'notDetermined': gps = { label: locationPermissionLabel(status), detail: source, tone: 'warning' }; break;
    case 'Allow location': gps = { label: locationPermissionLabel('notDetermined'), detail: source, tone: 'warning' }; break;
    case 'unavailable':
    case 'GPS unavailable':
    case 'unknown': gps = { label: 'GPS unavailable', detail: source, tone: 'warning' }; break;
    case 'GPS paused': gps = { label: 'GPS paused', detail: source, tone: 'neutral' }; break;
    case 'GPS saved': gps = { label: 'Route recorded', detail: source, tone: 'neutral' }; break;
    case 'Weak GPS': gps = { label: 'Weak GPS signal', detail: source, tone: 'warning' }; break;
    case 'GPS off':
    case 'inactive': gps = { label: state.phase === 'paused' ? 'GPS paused' : 'GPS inactive', detail: source, tone: 'neutral' }; break;
    default: gps = { label: 'Waiting for GPS', detail: source, tone: 'neutral' }; break;
  }
  const accuracy = state.streams.gps.accuracyMeters;
  if (status === 'receiving' && accuracy != null && Number.isFinite(accuracy) && accuracy >= 0) {
    gps.detail += ` · ±${Math.round(accuracy)} m`;
  }
  return { bike, heartRate, gps };
}
