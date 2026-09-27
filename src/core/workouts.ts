import type { CatalogPage, CatalogRequest } from './catalog';
import type { DistanceSource, WorkoutDistanceInfo } from './distance';
/** Portable ride contracts; each platform adapter owns durable capture. */
export type WorkoutPhase =
  'idle' | 'preparing' | 'running' | 'paused' | 'recoverable' | 'finishing' | 'completed' | 'failed';
export type StreamStatus =
  | 'off'
  | 'waiting'
  | 'receiving'
  | 'weak'
  | 'stale'
  | 'paused'
  | 'denied'
  | 'restricted'
  | 'notDetermined'
  | 'unavailable';
export type WorkoutStream = { status: StreamStatus };
export type WorkoutState = {
  supported: boolean;
  capabilities: {
    phoneWorkout: boolean;
    watchWorkout: boolean;
    phoneHealth: boolean;
    watchHealth: boolean;
    healthProvider: 'appleHealth' | 'healthConnect' | null;
    gps: boolean;
    foregroundOnly: boolean;
  };
  id: string | null;
  phase: WorkoutPhase;
  historyRevision: string;
  lastDeletedWorkoutId: string | null;
  collectionRevision: number | null;
  sealRevision: number | null;
  verifiedSealRevision: number | null;
  finalizationState: 'pending' | 'complete' | 'partial' | null;
  indoor: boolean;
  useWatch: boolean;
  saveToHealth: boolean;
  recordGPS: boolean;
  timerSeconds: number;
  pendingAction: string | null;
  recoveryState: 'idle' | 'checking' | 'unresolved' | 'resolved';
  recoveryMessage: string | null;
  healthKitState: string;
  watch: { installed: boolean };
  streams: {
    cyc: WorkoutStream;
    heartRate: WorkoutStream;
    gps: WorkoutStream & { source: 'phone' | 'watch'; accuracyMeters: number | null };
  };
  warnings: string[];
  error: string | null;
};
type UncheckedStream = { status: unknown };
type UncheckedWorkoutState = Omit<WorkoutState, 'streams' | 'capabilities'> & {
  capabilities: { [K in keyof WorkoutState['capabilities']]: unknown };
  streams: {
    cyc: UncheckedStream;
    heartRate: UncheckedStream;
    gps: UncheckedStream & { source: 'phone' | 'watch'; accuracyMeters: unknown };
  };
};

export function parseStreamStatus(status: unknown): StreamStatus {
  switch (status) {
    case 'off':
    case 'waiting':
    case 'receiving':
    case 'weak':
    case 'stale':
    case 'paused':
    case 'denied':
    case 'restricted':
    case 'notDetermined':
    case 'unavailable':
      return status;
    default:
      return 'waiting';
  }
}

export function normalizeWorkoutState(state: UncheckedWorkoutState): WorkoutState {
  const nonnegative = (value: unknown) =>
    typeof value === 'number' && Number.isFinite(value) && value >= 0 ? value : null;
  const stream = (value: UncheckedStream): WorkoutStream => ({
    status: parseStreamStatus(value.status),
  });
  return {
    ...state,
    capabilities: {
      phoneWorkout: state.capabilities.phoneWorkout === true,
      watchWorkout: state.capabilities.watchWorkout === true,
      phoneHealth: state.capabilities.phoneHealth === true,
      watchHealth: state.capabilities.watchHealth === true,
      healthProvider:
        state.capabilities.healthProvider === 'appleHealth' || state.capabilities.healthProvider === 'healthConnect'
          ? state.capabilities.healthProvider
          : null,
      gps: state.capabilities.gps === true,
      foregroundOnly: state.capabilities.foregroundOnly === true,
    },
    streams: {
      cyc: stream(state.streams.cyc),
      heartRate: stream(state.streams.heartRate),
      gps: {
        ...stream(state.streams.gps),
        source: state.streams.gps.source,
        accuracyMeters: nonnegative(state.streams.gps.accuracyMeters),
      },
    },
  };
}
export type WorkoutMetadata = {
  schemaVersion: number;
  id: string;
  startedAt: string;
  elapsedSeconds: number;
  endedAt?: string;
  phase: string;
  healthProvider?: 'healthConnect';
  saveToHealth?: boolean;
  recordGPS?: boolean;
  storage?: 'native' | 'browser';
  example?: boolean;
  indoor: boolean;
  watchEnabled: boolean;
  sport: string;
  subSport: string;
  eventCount: number;
  interrupted: boolean;
  healthKitState: string;
  healthKitUUID?: string;
  warnings: string[];
  watchSyncState: 'pending' | 'received' | 'notRequired';
  collectionRevision?: number;
  sealRevision?: number;
  verifiedSealRevision?: number;
  finalizationState?: 'pending' | 'complete' | 'partial';
};
export type RoutePoint = { latitude: number; longitude: number; [key: string]: number };
export type WorkoutSummary = {
  distance?: WorkoutDistanceInfo;
  schemaVersion: number;
  id: string;
  startedAt: string;
  endedAt: string;
  elapsedSeconds: number;
  timerSeconds: number;
  distanceMeters?: number;
  gpsDistanceMeters?: number;
  healthDistanceMeters?: number;
  healthDistanceProvisional?: boolean;
  healthDistanceSource?: string;
  healthDistanceReportedAt?: string;
  averageSpeedMps?: number;
  maximumSpeedMps?: number;
  ascentMeters?: number;
  descentMeters?: number;
  averageHeartRateBpm?: number;
  maximumHeartRateBpm?: number;
  averageRiderPowerW?: number;
  maximumRiderPowerW?: number;
  averageCadenceRpm?: number;
  maximumCadenceRpm?: number;
  activeEnergyKcal?: number;
  basalEnergyKcal?: number;
  riderWorkJoules?: number;
  telemetryCoveredSeconds: number;
  heartRateCoveredSeconds: number;
  eventCount: number;
  telemetryCount: number;
  locationCount: number;
  healthCount: number;
  lapCount: number;
  routePreview: RoutePoint[];
  warnings: string[];
  provenance: Record<string, string>;
  completeness?: Record<string, string>;
};
export type WorkoutDetail = { metadata: WorkoutMetadata; summary: WorkoutSummary };
export type StoragePersistence = 'persisted' | 'not persisted' | 'unavailable';
export type WorkoutOptions = {
  indoor: boolean;
  useWatch?: boolean | null;
  saveToHealth?: boolean | null;
  recordGPS?: boolean | null;
  sampleHz?: 2 | 4 | 8 | null;
};
export type WorkoutPermissions = {
  health: {
    provider?: 'healthConnect';
    requiredWrites?: string[];
    available: boolean;
    writeAuthorization: Record<string, string>;
  };
  location: string;
};
export type WorkoutPermissionStatus = WorkoutPermissions & {
  locationServicesEnabled: boolean;
  locationAccuracyAuthorization: 'full' | 'reduced' | 'unknown';
};
export interface WorkoutAdapter {
  getState(): Promise<WorkoutState>;
  subscribe(listener: (state: WorkoutState) => void): () => void;
  getPermissions(): Promise<WorkoutPermissionStatus>;
  requestPermissions(options: WorkoutOptions): Promise<WorkoutPermissions>;
  start(options: WorkoutOptions): Promise<WorkoutState>;
  pause(id?: string): Promise<WorkoutState>;
  resume(id?: string): Promise<WorkoutState>;
  lap(id?: string): Promise<WorkoutState>;
  stop(id?: string): Promise<WorkoutState>;
  recover(id: string): Promise<WorkoutState>;
  remove(id: string): Promise<WorkoutState>;
  discard(id: string): Promise<WorkoutState>;
  list(options?: CatalogRequest): Promise<CatalogPage<WorkoutMetadata>>;
  /** Development builds only: writes the fictional example rides that are not stored yet and returns how many were added. */
  addExampleRides?(): Promise<number>;
  /** Browser only: whether saved rides are protected from storage eviction. */
  storagePersistence?(): Promise<StoragePersistence>;
  read(id: string, distanceSource?: DistanceSource): Promise<WorkoutDetail>;
  export(id: string, distanceSource?: DistanceSource): Promise<string>;
  exportOriginal(id: string): Promise<string>;
}
export const workoutInProgress = (phase: WorkoutPhase) =>
  ['preparing', 'running', 'paused', 'recoverable', 'finishing'].includes(phase);
export const unavailableWorkoutState: WorkoutState = {
  supported: false,
  capabilities: {
    phoneWorkout: false,
    watchWorkout: false,
    phoneHealth: false,
    watchHealth: false,
    healthProvider: null,
    gps: false,
    foregroundOnly: false,
  },
  id: null,
  phase: 'idle',
  historyRevision: '',
  lastDeletedWorkoutId: null,
  collectionRevision: null,
  sealRevision: null,
  verifiedSealRevision: null,
  finalizationState: null,
  indoor: false,
  useWatch: false,
  saveToHealth: false,
  recordGPS: false,
  timerSeconds: 0,
  pendingAction: null,
  recoveryState: 'idle',
  recoveryMessage: null,
  healthKitState: 'notSaved',
  watch: { installed: false },
  streams: {
    cyc: { status: 'unavailable' },
    heartRate: { status: 'unavailable' },
    gps: { status: 'unavailable', source: 'phone', accuracyMeters: null },
  },
  warnings: [],
  error: null,
};

/** Ended and Health saved are independent from verification of the current snapshot. */
export function workoutExportReady(record: WorkoutMetadata): boolean {
  if (record.storage === 'browser')
    return record.phase === 'completed' && Boolean(record.endedAt) && record.finalizationState === 'complete';
  return (
    record.phase === 'completed' &&
    (record.sealRevision ?? 0) > 0 &&
    record.verifiedSealRevision === record.sealRevision &&
    (record.finalizationState === 'complete' || record.finalizationState === 'partial')
  );
}

/** UI eligibility only; native storage verifies owner and in-flight work again before deletion. */
export function workoutCanDelete(record: WorkoutMetadata, current: WorkoutState): boolean {
  if (record.storage === 'browser' && record.phase === 'recoverable')
    return record.id !== current.id || (!workoutInProgress(current.phase) && !current.pendingAction);
  if (!['completed', 'failed'].includes(record.phase)) return false;
  return (
    record.id !== current.id ||
    (!workoutInProgress(current.phase) && !current.pendingAction && current.recoveryState !== 'checking')
  );
}

/** Historical phone repair never selects that ride as the current owner. */
export function workoutRecoveryAction(record: WorkoutMetadata, currentID: string | null): 'repair' | 'recover' | null {
  if (record.storage === 'browser') return null;
  if (
    record.healthProvider === 'healthConnect' &&
    record.phase === 'completed' &&
    record.saveToHealth &&
    record.healthKitState === 'notSaved'
  )
    return 'repair';
  if (workoutExportReady(record) && record.finalizationState !== 'partial') return null;
  if (
    record.phase === 'completed' &&
    !record.watchEnabled &&
    (record.healthKitState === 'saved' ||
      (record.saveToHealth === false && record.healthKitState === 'notRequested' && Boolean(record.endedAt)))
  )
    return 'repair';
  return record.id === currentID ? 'recover' : null;
}

export function effectiveWorkoutOptions(
  options: { [K in keyof WorkoutOptions]: unknown },
  capabilities: WorkoutState['capabilities'],
): { [K in keyof WorkoutOptions]-?: NonNullable<WorkoutOptions[K]> } {
  if (typeof options.indoor !== 'boolean') throw new Error('indoor must be a boolean');
  const boolean = (field: 'useWatch' | 'saveToHealth' | 'recordGPS', fallback: boolean): boolean => {
    const value = options[field];
    if (value == null) return fallback;
    if (typeof value !== 'boolean') throw new Error(`${field} must be a boolean`);
    return value;
  };
  const requestedWatch = boolean('useWatch', false);
  const saveToHealth = boolean('saveToHealth', true);
  const recordGPS = boolean('recordGPS', !options.indoor);
  const sampleHz = options.sampleHz ?? 2;
  if (sampleHz !== 2 && sampleHz !== 4 && sampleHz !== 8) throw new Error('Sample rate must be 2, 4, or 8 Hz');
  const useWatch = capabilities.watchWorkout && requestedWatch;
  return {
    sampleHz,
    indoor: options.indoor,
    useWatch,
    saveToHealth: (useWatch ? capabilities.watchHealth : capabilities.phoneHealth) && saveToHealth,
    recordGPS: capabilities.gps && recordGPS,
  };
}
