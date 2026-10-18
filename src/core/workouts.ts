import type { BatteryConsumptionSource, BatteryConsumptionSummary } from './consumption';
import {
  completionCanDelete,
  completionExportReady,
  presentationStreamStatus,
  presentationResolveOptions,
  presentationSampleRates,
} from '../../shared/js';
import { presentationCapabilities, presentationOptions } from './presentation-inputs';

import { failureCode, rideFailure, type RideFailureState } from './failures';
import {
  retainWorkoutCompletion,
  workoutCompletion,
  validDecimal,
  validateWorkoutPayload,
  watchWorkoutId,
} from './workout-payload';
import type { CatalogPage, CatalogRequest } from './catalog';
import type { DistanceSource, WorkoutDistanceInfo } from './distance';
export const sampleRates: readonly number[] = Array.from(presentationSampleRates());
export type WorkoutPhase =
  'idle' | 'preparing' | 'running' | 'paused' | 'interrupted' | 'finishing' | 'completed' | 'failed';
export type StreamStatus =
  | 'off'
  | 'waiting'
  | 'receiving'
  | 'backfilled'
  | 'weak'
  | 'stale'
  | 'paused'
  | 'denied'
  | 'restricted'
  | 'notDetermined'
  | 'unavailable';
export type WorkoutStream = { status: StreamStatus };
export type LifecycleAction = 'start' | 'pause' | 'resume' | 'lap' | 'stop';
export type RideActionRequest = {
  actionId: string;
  rideId: string;
  action: RideAction;
  requestedAt: string;
  options: {
    indoor: boolean;
    recordingOwner: 'phone';
    saveToHealth: boolean;
    recordGPS: boolean;
    sampleHz: 2 | 4 | 8;
  };
  elapsedSeconds: number;
  timerSeconds: number;
};
export type RideAction = 'start' | 'pause' | 'resume' | 'lap' | 'stop' | 'discard';

export type HealthProvider = 'appleHealth' | 'healthConnect';
export type OwnerHealth =
  'pending' | 'saved' | 'failed' | 'notSaved' | 'unavailable' | 'unknown' | 'discarded' | 'notRequested';
export type HealthComponent =
  | 'workout'
  | 'route'
  | 'laps'
  | 'rider-power'
  | 'cycling-cadence'
  | 'cycling-speed'
  | 'cycling-distance'
  | 'association'
  | 'readback';
export type HealthReason =
  | 'permission'
  | 'locked'
  | 'quota'
  | 'invalid'
  | 'fixedSize'
  | 'integrityConflict'
  | 'ambiguous'
  | 'outcomeAbsent'
  | 'providerDeleted'
  | 'localHistory'
  | 'discard'
  | 'closedSource'
  | 'builderLost'
  | 'readbackUnavailable'
  | 'nativeOwnerLost'
  | 'numericOverflow'
  | 'noValidReading'
  | 'noCoverage'
  | 'inactive'
  | 'transientFailure'
  | 'missingSource'
  | 'clock';
export type ComponentResult = {
  component: HealthComponent;
  sourceRevision: string;
  required: boolean;
  providerIds: string[];
} & (
  | { state: 'notRequested' | 'pending' | 'saved'; reason?: HealthReason }
  | { state: 'unavailable' | 'failed' | 'fenced'; reason: HealthReason }
);
export type WorkoutHealthResult = {
  componentResults?: ComponentResult[];
  recordingOwner?: 'phone';
  healthPublisher?: 'phone' | 'watch';
  healthRetryAvailable?: boolean;
  exportEligible?: boolean;
};

export type ExternalRideControl = {
  rideId: string;
  controlToken: string;
  expectedPhase: string;
  action: 'finish';
};
export function externalRideControl(value: unknown): ExternalRideControl {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('Invalid external ride control');
  const record = value as Record<string, unknown>;
  if (
    Object.keys(record).some(key => !['rideId', 'controlToken', 'expectedPhase', 'action'].includes(key)) ||
    !['running', 'paused'].includes(record.expectedPhase as string) ||
    record.action !== 'finish'
  )
    throw new Error('Invalid external ride control');
  return {
    rideId: watchWorkoutId(record.rideId),
    controlToken: watchWorkoutId(record.controlToken),
    expectedPhase: record.expectedPhase as string,
    action: 'finish',
  };
}

export type WorkoutState = WorkoutHealthResult & {
  healthReason?: string | null;
  healthBusy?: boolean | null;
  sessionBusy?: boolean | null;
  endingOwner?: boolean | null;
  startBusy?: boolean | null;
  finalizationBusy?: boolean | null;
  readWorkoutIds?: string[];
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
  controlToken?: string | null;
  externalFinish?: ExternalRideControl | null;
  phase: WorkoutPhase;
  historyRevision: string;
  lastDeletedWorkoutId: string | null;
  collectionRevision: string | null;
  indoor: boolean;
  useWatch: boolean;
  saveToHealth: boolean;
  recordGPS: boolean;
  distanceSource?: DistanceSource;
  batteryConsumptionSource?: BatteryConsumptionSource;
  timerSeconds: number;
  pendingAction: RideAction | null;
  foreignOwner: 'anotherBrowserTab' | null;
  recoveryState: 'idle' | 'checking' | 'unresolved' | 'storage' | 'checkingStorage' | 'absent' | 'resolved';
  healthKitState: OwnerHealth;
  watch: { installed: boolean };
  streams: {
    controller: WorkoutStream;
    heartRate: WorkoutStream;
    gps: WorkoutStream & { source: 'phone' | 'watch'; accuracyMeters: number | null };
  };
  failure: RideFailureState | null;
};
type UncheckedStream = { status: unknown };
type UncheckedWorkoutState = Omit<
  WorkoutState,
  'streams' | 'capabilities' | 'pendingAction' | 'foreignOwner' | 'failure'
> & {
  pendingAction: unknown;
  foreignOwner?: unknown;
  failure?: unknown;
  capabilities: { [K in keyof WorkoutState['capabilities']]: unknown };
  streams: {
    controller: UncheckedStream;
    heartRate: UncheckedStream;
    gps: UncheckedStream & { source: 'phone' | 'watch'; accuracyMeters: unknown };
  };
};

export function parseStreamStatus(status: unknown): StreamStatus {
  return presentationStreamStatus(typeof status === 'string' ? status : null) as StreamStatus;
}

export function normalizeWorkoutState(state: UncheckedWorkoutState): WorkoutState {
  if (!validDecimal(state.historyRevision, 'revision')) throw new Error('Invalid workout catalog revision');
  if (state.controlToken != null) watchWorkoutId(state.controlToken);
  if (state.externalFinish != null && !state.controlToken) throw new Error('Invalid external ride control');
  const externalFinish =
    state.externalFinish == null ? state.externalFinish : externalRideControl(state.externalFinish);
  const completion = validateWorkoutPayload(state);
  const pendingAction = state.pendingAction;
  if (
    pendingAction !== null &&
    pendingAction !== 'start' &&
    pendingAction !== 'pause' &&
    pendingAction !== 'resume' &&
    pendingAction !== 'lap' &&
    pendingAction !== 'stop' &&
    pendingAction !== 'discard'
  )
    throw new Error('Invalid pending ride action');
  if (state.foreignOwner != null && state.foreignOwner !== 'anotherBrowserTab')
    throw new Error('Invalid foreign ride owner');
  const nonnegative = (value: unknown) =>
    typeof value === 'number' && Number.isFinite(value) && value >= 0 ? value : null;
  const stream = (value: UncheckedStream): WorkoutStream => ({
    status: parseStreamStatus(value.status),
  });
  return retainWorkoutCompletion(
    {
      ...state,
      ...(state.externalFinish != null ? { externalFinish } : {}),
      collectionRevision: completion.collectionRevision ?? null,
      pendingAction,
      foreignOwner: state.foreignOwner ?? null,
      failure: state.failure == null ? null : rideFailure(failureCode(state.failure)),
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
        controller: stream(state.streams.controller),
        heartRate: stream(state.streams.heartRate),
        gps: {
          ...stream(state.streams.gps),
          source: state.streams.gps.source,
          accuracyMeters: nonnegative(state.streams.gps.accuracyMeters),
        },
      },
    },
    completion,
  );
}
export type WorkoutMetadata = WorkoutHealthResult & {
  schemaVersion: number;
  id: string;
  startedAt: string;
  elapsedSeconds: number;
  endedAt?: string;
  phase: string;
  healthProvider?: HealthProvider;
  saveToHealth?: boolean;
  recordGPS?: boolean;
  distanceSource?: DistanceSource;
  batteryConsumptionSource?: BatteryConsumptionSource;
  storage?: 'native' | 'browser';
  sampleHz?: 2 | 4 | 8;
  example?: boolean;
  indoor: boolean;
  watchEnabled: boolean;
  eventCount: string;
  interrupted: boolean;
  healthKitState: OwnerHealth;
  healthKitUUID?: string;
  healthReason?: string | null;
  collectionRevision?: string;
};
export type RoutePoint = { latitude: number; longitude: number; [key: string]: number };
export type WorkoutSummary = {
  batteryConsumption?: BatteryConsumptionSummary;
  distance?: WorkoutDistanceInfo;
  schemaVersion: number;
  id: string;
  startedAt: string;
  endedAt: string;
  elapsedSeconds: number;
  timerSeconds: number;
  distanceMeters?: number;
  gpsDistanceMeters?: number;
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
  eventCount: string;
  telemetryCount: string;
  locationCount: string;
  healthCount: string;
  lifecycleCount: string;
  lapCount: number;
  routePreview: RoutePoint[];
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
  distanceSource?: DistanceSource | null;
  batteryConsumptionSource?: BatteryConsumptionSource | null;
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
  refreshHealth?(id: string): Promise<WorkoutState>;
  retryHealthSave?(id: string): Promise<WorkoutState>;
  finishExternal?(request: ExternalRideControl, discard: boolean): Promise<WorkoutState>;
  cancelExternal?(request: ExternalRideControl): Promise<WorkoutState>;
  remove(id: string): Promise<WorkoutState>;
  discard(id: string): Promise<WorkoutState>;
  list(options?: CatalogRequest): Promise<CatalogPage<WorkoutMetadata>>;
  addExampleRides?(): Promise<number>;
  storagePersistence?(): Promise<StoragePersistence>;
  read(id: string, distanceSource?: DistanceSource, signal?: AbortSignal): Promise<WorkoutDetail>;
}
export const workoutInProgress = (phase: WorkoutPhase) =>
  ['preparing', 'running', 'paused', 'interrupted', 'finishing'].includes(phase);
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
  historyRevision: '0',
  lastDeletedWorkoutId: null,
  collectionRevision: null,
  indoor: false,
  useWatch: false,
  saveToHealth: false,
  recordGPS: false,
  timerSeconds: 0,
  pendingAction: null,
  foreignOwner: null,
  recoveryState: 'idle',
  healthKitState: 'notSaved',
  watch: { installed: false },
  streams: {
    controller: { status: 'unavailable' },
    heartRate: { status: 'unavailable' },
    gps: { status: 'unavailable', source: 'phone', accuracyMeters: null },
  },
  failure: null,
  sessionBusy: false,
};

export function workoutExportReady(record: WorkoutMetadata): boolean {
  try {
    return completionExportReady(workoutCompletion(record));
  } catch {
    return false;
  }
}

export type WorkoutDeletionFacts = {
  healthBusy?: boolean | null;
  startBusy?: boolean | null;
  finalizationBusy?: boolean | null;
  targetPending?: boolean | null;
  targetRecovering?: boolean | null;
  targetBusy?: boolean | null;
};
export function workoutCanDelete(
  record: WorkoutMetadata,
  current: WorkoutState,
  facts?: WorkoutDeletionFacts,
): boolean {
  return completionCanDelete(record.id, record.phase, current, {
    healthBusy: null,
    startBusy: null,
    finalizationBusy: null,
    targetPending: null,
    targetRecovering: null,
    targetBusy: null,
    ...facts,
  });
}

export function effectiveWorkoutOptions(
  options: { [K in keyof WorkoutOptions]: unknown },
  capabilities: WorkoutState['capabilities'],
): { [K in keyof WorkoutOptions]-?: NonNullable<WorkoutOptions[K]> } {
  const resolved = presentationResolveOptions(presentationOptions(options), presentationCapabilities(capabilities));
  return {
    indoor: resolved.indoor,
    useWatch: resolved.useWatch,
    saveToHealth: resolved.saveToHealth,
    recordGPS: resolved.recordGPS,
    sampleHz: resolved.sampleHz as 2 | 4 | 8,
    distanceSource: resolved.distanceSource as DistanceSource,
    batteryConsumptionSource: resolved.batteryConsumptionSource as BatteryConsumptionSource,
  };
}
