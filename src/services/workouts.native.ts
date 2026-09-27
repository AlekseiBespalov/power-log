import bridge from '../../modules/cyc-bridge';
import type { WorkoutAdapter } from '../core/workouts';
import { normalizeWorkoutState, unavailableWorkoutState } from '../core/workouts';

function native() {
  if (!bridge) throw new Error('Install the current Power Log build to record workouts.');
  return bridge;
}
export const workouts: WorkoutAdapter = {
  getState: async () => (bridge ? normalizeWorkoutState(await bridge.getWorkoutState()) : unavailableWorkoutState),
  subscribe: listener => {
    if (!bridge) return () => {};
    const subscription = bridge.addListener('onWorkoutState', state => listener(normalizeWorkoutState(state)));
    return () => subscription.remove();
  },
  addExampleRides: async () => {
    const add = native().addExampleRides;
    if (!add) throw new Error('Example rides need a development build.');
    return (await add()).added;
  },
  getPermissions: () => native().getWorkoutPermissions(),
  requestPermissions: options => native().requestWorkoutPermissions(options),
  start: options => native().startWorkout(options).then(normalizeWorkoutState),
  pause: id => native().pauseWorkout(id).then(normalizeWorkoutState),
  resume: id => native().resumeWorkout(id).then(normalizeWorkoutState),
  lap: id => native().markWorkoutLap(id).then(normalizeWorkoutState),
  stop: id => native().stopWorkout(id).then(normalizeWorkoutState),
  discard: id => native().discardWorkout(id).then(normalizeWorkoutState),
  recover: id => native().recoverWorkout(id).then(normalizeWorkoutState),
  remove: id => native().deleteWorkout(id).then(normalizeWorkoutState),
  list: async options => ({
    records: bridge ? await bridge.listWorkouts(options ?? {}) : [],
    unreadableCount: 0,
    unindexedCount: 0,
  }),
  read: (id, distanceSource = 'auto') => native().readWorkout(id, distanceSource),
  export: (id, distanceSource = 'auto') => native().exportWorkout(id, distanceSource),
  exportOriginal: id => native().exportWorkoutArchive(id),
};
