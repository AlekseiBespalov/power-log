import { PresentationPermissions, presentationPermissionAction } from '../../shared/js';
import { presentationOptions } from './presentation-inputs';
import type { WorkoutOptions, WorkoutPermissionStatus } from './workouts';

export type WorkoutPermissionAction = {
  action: 'none' | 'request' | 'settings' | 'check' | 'unavailable';
  label: string;
  detail: string;
  settingsTarget?: 'app' | 'health';
};
export function workoutPermissionAction(
  status: WorkoutPermissionStatus | null,
  options: WorkoutOptions,
): WorkoutPermissionAction {
  const result = presentationPermissionAction(
    status === null
      ? null
      : new PresentationPermissions(
          status.health.available,
          status.health.provider ?? 'appleHealth',
          status.health.requiredWrites ?? null,
          Object.keys(status.health.writeAuthorization),
          Object.values(status.health.writeAuthorization),
          status.location,
          status.locationServicesEnabled,
          status.locationAccuracyAuthorization,
        ),
    presentationOptions(options),
  );
  return {
    action: result.action as WorkoutPermissionAction['action'],
    label: result.label,
    detail: result.detail,
    ...(result.settingsTarget === null ? {} : { settingsTarget: result.settingsTarget as 'app' | 'health' }),
  };
}
