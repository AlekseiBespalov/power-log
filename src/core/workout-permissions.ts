import type { WorkoutOptions, WorkoutPermissionStatus } from './workouts';

export type WorkoutPermissionAction = {
  action: 'none' | 'request' | 'settings' | 'check' | 'unavailable';
  label: string;
  detail: string;
  settingsTarget?: 'app' | 'health';
};
const writeTypes = {
  common: ['HKWorkoutTypeIdentifier', 'HKQuantityTypeIdentifierCyclingPower', 'HKQuantityTypeIdentifierCyclingCadence',
    'HKQuantityTypeIdentifierActiveEnergyBurned', 'HKQuantityTypeIdentifierBasalEnergyBurned', 'HKQuantityTypeIdentifierHeartRate'],
  outdoor: ['HKWorkoutRouteTypeIdentifier', 'HKQuantityTypeIdentifierCyclingSpeed', 'HKQuantityTypeIdentifierDistanceCycling'],
};

/** Decides whether the phone has a concrete setup action. It never asserts HealthKit read grants. */
export function workoutPermissionAction(status: WorkoutPermissionStatus | null, options: WorkoutOptions): WorkoutPermissionAction {
  const healthRequired = !options.useWatch && options.saveToHealth !== false;
  const phoneGPS = (options.recordGPS ?? !options.indoor) && !options.useWatch;
  if (!healthRequired && !phoneGPS) return { action: 'none', label: '', detail: '' };
  if (!status) return { action: 'check', label: 'Check permissions', detail: 'Current permission status is not available yet.' };
  if (healthRequired && !status.health.available) return { action: 'unavailable', label: 'Health unavailable', detail: 'Health saving is unavailable on this device.' };
  // Watch is the HealthKit writer in Watch mode; its own permission flow is confirmed on the Watch.
  const requiredWrites = healthRequired ? status.health.requiredWrites ?? [...writeTypes.common, ...(phoneGPS ? writeTypes.outdoor : [])] : [];
  const writes = requiredWrites.map(type => status.health.writeAuthorization[type] ?? 'notDetermined');
  // The aggregate request status also includes optional types this ride did not select.
  if (writes.includes('notDetermined')) {
    return { action: 'request', label: 'Set up permissions', detail: 'Review the health permissions requested by Power Log.' };
  }
  if (phoneGPS && status.location === 'notDetermined') {
    return { action: 'request', label: 'Allow location', detail: 'Your phone needs location access to record GPS measurements.' };
  }
  if (writes.includes('denied')) {
    return { action: 'settings', label: 'Review Health access', settingsTarget: 'health', detail: 'Some write access is denied. Review Power Log’s permissions in the Health app.' };
  }
  if (phoneGPS && (!status.locationServicesEnabled || ['denied', 'restricted'].includes(status.location))) {
    return { action: 'settings', label: 'Location settings', settingsTarget: 'app', detail: !status.locationServicesEnabled
      ? 'Turn on Location Services to record the phone route.'
      : status.location === 'restricted' ? 'Location access is restricted by device settings.' : 'Allow location access for Power Log in Settings.' };
  }
  if (phoneGPS && status.locationAccuracyAuthorization === 'reduced') {
    return { action: 'settings', label: 'Precise location', settingsTarget: 'app', detail: 'Enable Precise Location for a useful cycling route.' };
  }
  if (writes.some(value => value !== 'authorized')
    || (phoneGPS && (!['authorizedAlways', 'authorizedWhenInUse'].includes(status.location) || status.locationAccuracyAuthorization === 'unknown'))) {
    return { action: 'check', label: 'Check permissions', detail: 'Current permission readiness could not be determined.' };
  }
  // "unnecessary" means no new authorization prompt, not that every HealthKit read was allowed.
  return { action: 'none', label: '', detail: '' };
}
