import { normalizeWorkoutDetail, normalizeWorkoutMetadata } from '../core/workout-payload';
import bridge from '../../modules/power-log-bridge';
import { nativeRead } from './native-read';
import type { WorkoutAdapter, WorkoutState } from '../core/workouts';
import { externalRideControl, normalizeWorkoutState, unavailableWorkoutState } from '../core/workouts';
import { catalogLimit, catalogOrder, type CatalogEntry } from '../core/catalog';
import { isRecord, validateTimestamp } from '../core/validation';

function native() {
  if (!bridge) throw new Error('Install the current Power Log build to record workouts.');
  return bridge;
}
const pendingActions = new Map<string, Promise<WorkoutState>>();
function nativeAction(action: string, id: string | undefined, execute: () => Promise<WorkoutState>) {
  const key = JSON.stringify([id ?? null, action]);
  const pending = pendingActions.get(key);
  if (pending) return pending;
  const invocation = Promise.resolve()
    .then(execute)
    .then(normalizeWorkoutState)
    .finally(() => {
      if (pendingActions.get(key) === invocation) pendingActions.delete(key);
    });
  pendingActions.set(key, invocation);
  return invocation;
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
  pause: id => nativeAction('pause', id, () => native().pauseWorkout(id)),
  resume: id => nativeAction('resume', id, () => native().resumeWorkout(id)),
  lap: id => nativeAction('lap', id, () => native().markWorkoutLap(id)),
  stop: id => nativeAction('stop', id, () => native().stopWorkout(id)),
  discard: id => nativeAction('discard', id, () => native().discardWorkout(id)),
  refreshHealth: id => nativeAction('refreshHealth', id, () => native().refreshWorkoutHealth(id)),
  retryHealthSave: bridge?.retryWorkoutHealthSave
    ? id => nativeAction('retryHealthSave', id, () => native().retryWorkoutHealthSave!(id))
    : undefined,
  finishExternal: bridge?.finishExternalWorkoutAction
    ? async (request, discard) => {
        const control = externalRideControl(request);
        return nativeAction(discard ? 'discard' : 'stop', control.rideId, () =>
          native().finishExternalWorkoutAction!(control, discard),
        );
      }
    : undefined,
  cancelExternal: bridge?.cancelExternalWorkoutAction
    ? async request => {
        const control = externalRideControl(request);
        return nativeAction('cancelExternal', control.rideId, () => native().cancelExternalWorkoutAction!(control));
      }
    : undefined,
  remove: id => native().deleteWorkout(id).then(normalizeWorkoutState),
  list: async options => {
    const result = {
      records: [] as ReturnType<typeof normalizeWorkoutMetadata>[],
      unreadableCount: 0,
      unindexedCount: 0,
    };
    if (!bridge) return result;
    const limit = catalogLimit(options);
    let cursor =
      options?.beforeID && options.beforeStartedAt
        ? { id: options.beforeID, startedAt: options.beforeStartedAt }
        : undefined;
    while (result.records.length < limit) {
      const count = limit - result.records.length;
      const rows = await bridge.listWorkouts({
        limit: count,
        ...(cursor ? { beforeID: cursor.id, beforeStartedAt: cursor.startedAt } : {}),
      });
      for (const row of rows) {
        if (!isRecord(row) || typeof row.id !== 'string' || !row.id) throw new Error('Invalid catalog cursor');
        const key: CatalogEntry = { id: row.id, startedAt: validateTimestamp(row.startedAt) };
        if (cursor && catalogOrder(key, cursor) <= 0) throw new Error('Catalog cursor did not advance');
        cursor = key;
        try {
          result.records.push(normalizeWorkoutMetadata(row));
        } catch {
          result.unreadableCount++;
        }
      }
      if (rows.length < count) break;
    }
    return result;
  },
  read: (id, distanceSource = 'auto', signal) => {
    const module = native();
    return nativeRead(
      signal,
      cancellationId =>
        cancellationId
          ? module.readWorkout(id, distanceSource, cancellationId)
          : module.readWorkout(id, distanceSource),
      module.cancelWorkoutRead?.bind(module),
    ).then(normalizeWorkoutDetail);
  },
};
