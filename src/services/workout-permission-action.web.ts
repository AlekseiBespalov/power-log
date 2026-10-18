import { workoutPermissionAction as sharedAction, type WorkoutPermissionAction } from '../core/workout-permissions';
import type { WorkoutOptions, WorkoutPermissionStatus } from '../core/workouts';

export function workoutPermissionAction(
  status: WorkoutPermissionStatus | null,
  options: WorkoutOptions,
): WorkoutPermissionAction {
  const action = sharedAction(status, options);
  const gps = (options.recordGPS ?? !options.indoor) && !options.useWatch;
  if (!gps || !status) return action;
  if (['authorizedWhenInUse', 'authorizedAlways'].includes(status.location))
    return { action: 'none', label: '', detail: '' };
  if (action.action === 'settings')
    return {
      ...action,
      label: 'Check permissions',
      detail: "Allow location in this site's browser settings, then check permissions.",
    };
  return action;
}
