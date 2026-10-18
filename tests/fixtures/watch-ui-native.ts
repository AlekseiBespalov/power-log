import { unavailableWorkoutState, type WorkoutMetadata, type WorkoutState } from '../../src/core/workouts';
export const rideId = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa';
let state: WorkoutState = unavailableWorkoutState;
let record: WorkoutMetadata;
const listeners = new Set<(state: WorkoutState) => void>();
export const calls = {
  external: [] as { request: import('../../src/core/workouts').ExternalRideControl; discard: boolean }[],
  cancelExternal: [] as string[],
  finish: [] as string[],
  stop: [] as (string | undefined)[],
  discard: [] as string[],
  refresh: [] as string[],
};
let detailPatch: Partial<WorkoutMetadata> = {};
export function patchRecord(patch: Partial<WorkoutMetadata>) {
  record = { ...record, ...patch };
}
export function patchDetail(patch: Partial<WorkoutMetadata>) {
  detailPatch = patch;
}
export function patchState(patch: Partial<WorkoutState>) {
  state = { ...state, ...patch };
  for (const listener of listeners) listener(state);
}
export function seed(os: 'ios' | 'android' | 'web', phase: WorkoutState['phase'] = 'interrupted') {
  calls.finish.length = 0;
  calls.stop.length = 0;
  calls.discard.length = 0;
  calls.refresh.length = 0;
  calls.external.length = 0;
  calls.cancelExternal.length = 0;
  detailPatch = {};
  record = {
    schemaVersion: 1,
    id: rideId,
    startedAt: '2026-09-09T00:00:00.000Z',
    elapsedSeconds: 2,
    phase,
    indoor: true,
    watchEnabled: true,
    eventCount: '2',
    interrupted: true,
    healthKitState: 'saved',
    ...(phase === 'completed' ? { endedAt: '2026-09-09T00:00:02.000Z' } : {}),
    distanceSource: 'controller',
    collectionRevision: '3',
  };
  state = {
    ...unavailableWorkoutState,
    supported: true,
    id: rideId,
    phase,
    useWatch: true,
    capabilities: {
      ...unavailableWorkoutState.capabilities,
      phoneWorkout: true,
      watchWorkout: os === 'ios',
      healthProvider: os === 'ios' ? 'appleHealth' : os === 'android' ? 'healthConnect' : null,
    },
    collectionRevision: '3',
  };
}
const bridge = {
  refreshWorkoutHealth: async (id: string) => {
    calls.refresh.push(id);
    return state;
  },
  stopWorkout: async (id?: string) => {
    calls.stop.push(id);
    patchState({ phase: 'completed', pendingAction: null });
    return state;
  },
  discardWorkout: async (id: string) => {
    calls.discard.push(id);
    patchState({ phase: 'idle', pendingAction: null });
    return state;
  },
  finishExternalWorkoutAction: async (
    request: import('../../src/core/workouts').ExternalRideControl,
    discard: boolean,
  ) => {
    calls.external.push({ request, discard });
    patchState({ phase: discard ? 'idle' : 'completed', externalFinish: null });
    return state;
  },
  cancelExternalWorkoutAction: async (request: import('../../src/core/workouts').ExternalRideControl) => {
    calls.cancelExternal.push(request.controlToken);
    patchState({ externalFinish: null });
    return state;
  },
  getWorkoutState: async () => state,
  getWorkoutPermissions: async () => ({
    health: { available: false, writeAuthorization: {} },
    location: 'notDetermined',
    locationServicesEnabled: false,
    locationAccuracyAuthorization: 'unknown',
  }),
  addListener: (_event: string, listener: (state: WorkoutState) => void) => {
    listeners.add(listener);
    return { remove: () => listeners.delete(listener) };
  },
  listWorkouts: async () => [record],
  readWorkout: async () => ({
    metadata: { ...record, ...detailPatch },
    summary: {
      schemaVersion: 1,
      id: rideId,
      startedAt: record.startedAt,
      endedAt: record.endedAt ?? record.startedAt,
      elapsedSeconds: 2,
      timerSeconds: 2,
      eventCount: '2',
      telemetryCount: '1',
      locationCount: '0',
      healthCount: '0',
      lifecycleCount: '1',
      lapCount: 1,
      routePreview: [],
      provenance: {},
      telemetryCoveredSeconds: 0,
      heartRateCoveredSeconds: 0,
    },
  }),
};
export default bridge;
