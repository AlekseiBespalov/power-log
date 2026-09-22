import bridge from '../../modules/cyc-bridge';
import type { WorkoutAdapter } from '../core/workouts';
import { unavailableWorkoutState } from '../core/workouts';

function native() {
  if (!bridge?.getWorkoutState) throw new Error('Install the current Power Log iPhone build to record workouts.');
  return bridge;
}
export const workouts: WorkoutAdapter = {
  getState: () => bridge?.getWorkoutState ? bridge.getWorkoutState() : Promise.resolve(unavailableWorkoutState),
  subscribe: listener => {
    if (!bridge?.getWorkoutState) return () => {};
    const subscription = bridge.addListener('onWorkoutState', listener);
    return () => subscription.remove();
  },
  addExampleRides: async () => {
    const add = native().addExampleRides;
    if (!add) throw new Error('Example rides need a development build.');
    return (await add()).added;
  },
  getPermissions: () => {
    if (!native().getWorkoutPermissions) throw new Error('Install the latest iPhone build to check current permissions.');
    return native().getWorkoutPermissions();
  },
  requestPermissions: options => options && native().requestWorkoutPermissionsForOptions
    ? native().requestWorkoutPermissionsForOptions(options)
    : native().requestWorkoutPermissions(),
  start: options => native().startWorkout(options),
  pause: id => native().pauseWorkout(id),
  resume: id => native().resumeWorkout(id),
  lap: id => native().markWorkoutLap(id),
  stop: id => native().stopWorkout(id),
  discard: id => {
    if (!native().discardWorkout) throw new Error('Install the latest Power Log build to discard rides.');
    return native().discardWorkout(id);
  },
  recover: id => native().recoverWorkout(id),
  remove: id => {
    if (!native().deleteWorkout) throw new Error('Install the latest Power Log build to delete saved rides.');
    return native().deleteWorkout(id);
  },
  list: options => bridge?.listWorkouts ? bridge.listWorkouts(options ?? {}) : Promise.resolve([]),
  read: (id, distanceSource = 'auto') => native().readWorkout(id, distanceSource),
  export: (id, distanceSource = 'auto') => native().exportWorkout(id, distanceSource),
  exportOriginal: id => {
    if (!native().exportWorkoutArchive) throw new Error('Install the latest native build to export original workout data.');
    return native().exportWorkoutArchive(id);
  },
};

export function setWorkoutTelemetrySource(_adapter: import('./adapter').TelemetryAdapter): void {}
