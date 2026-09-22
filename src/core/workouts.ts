import type { CatalogRequest } from './catalog';
import type { DistanceSource, WorkoutDistanceInfo } from './distance';
/** Portable ride contracts; each platform adapter owns durable capture. */
export type WorkoutPhase = 'idle' | 'preparing' | 'running' | 'paused' | 'recoverable' | 'finishing' | 'completed' | 'failed';
export type WorkoutStream = { status: string; lastSampleAgeSeconds: number | null };
export type WorkoutState = {
  distance?: WorkoutDistanceInfo;
  supported: boolean;
  capabilities: { phoneWorkout: boolean; watchWorkout: boolean; healthKit: boolean; gps?: boolean; foregroundOnly?: boolean };
  id: string | null;
  phase: WorkoutPhase;
  historyRevision?: string | null;
  lastDeletedWorkoutId?: string | null;
  collectionRevision?: number; sealRevision?: number; verifiedSealRevision?: number; ownerRevision?: number;
  finalizationState?: 'pending' | 'complete' | 'partial';
  startedAt: string | null;
  indoor: boolean;
  useWatch: boolean;
  saveToHealth?: boolean; recordGPS?: boolean;
  elapsedSeconds: number;
  timerSeconds: number;
  pendingAction: string | null;
  recoveryState?: 'idle' | 'checking' | 'unresolved' | 'resolved'; recoveryMessage?: string | null;
  healthKitState: string;
  healthKitUUID: string | null;
  watch: { supported: boolean; paired: boolean; installed: boolean; reachable: boolean; activated?: boolean; pendingMessages: number; error?: string };
  streams: {
    cyc: WorkoutStream;
    heartRate: WorkoutStream;
    gps: WorkoutStream & { source: 'phone' | 'watch'; accuracyMeters: number | null };
  };
  metrics: {
    riderPowerW: number | null; cadenceRpm: number | null; heartRateBpm: number | null;
    activeEnergyKcal: number | null; basalEnergyKcal: number | null; distanceMeters: number | null; speedMps: number | null;
  };
  warnings: string[];
  error: string | null;
};
export type WorkoutMetadata = {
  schemaVersion: number; id: string; startedAt: string; endedAt?: string; phase: string;
  saveToHealth?: boolean; recordGPS?: boolean; storage?: 'native' | 'browser'; example?: boolean;
  indoor: boolean; watchEnabled: boolean; sport: string; subSport: string;
  eventCount: number; interrupted: boolean; healthKitState: string; healthKitUUID?: string; warnings: string[];
  watchSyncState: 'pending' | 'received' | 'notRequired';
  collectionRevision?: number; sealRevision?: number; verifiedSealRevision?: number;
  finalizationState?: 'pending' | 'complete' | 'partial';
};
export type RoutePoint = { latitude: number; longitude: number; [key: string]: number };
export type WorkoutSummary = {
  distance?: WorkoutDistanceInfo;
  schemaVersion: number; id: string; startedAt: string; endedAt: string;
  elapsedSeconds: number; timerSeconds: number;
  distanceMeters?: number; gpsDistanceMeters?: number; healthDistanceMeters?: number;
  healthDistanceProvisional?: boolean; healthDistanceSource?: string; healthDistanceReportedAt?: string;
  averageSpeedMps?: number; maximumSpeedMps?: number; ascentMeters?: number; descentMeters?: number;
  averageHeartRateBpm?: number; maximumHeartRateBpm?: number;
  averageRiderPowerW?: number; maximumRiderPowerW?: number;
  averageCadenceRpm?: number; maximumCadenceRpm?: number;
  activeEnergyKcal?: number; basalEnergyKcal?: number; riderWorkJoules?: number;
  telemetryCoveredSeconds: number; heartRateCoveredSeconds: number;
  eventCount: number; telemetryCount: number; locationCount: number; healthCount: number; lapCount: number;
  routePreview: RoutePoint[]; warnings: string[]; provenance: Record<string, string>;
  completeness?: Record<string, string>;
};
export type WorkoutDetail = { metadata: WorkoutMetadata; summary: WorkoutSummary };
export type WorkoutOptions = { indoor: boolean; useWatch: boolean; saveToHealth?: boolean; recordGPS?: boolean; sampleHz?: 2 | 4 | 8 };
export type WorkoutPermissions = {
  health: { available: boolean; readAuthorization: 'notObservable'; writeAuthorization: Record<string, string> };
  location: string;
};
export type WorkoutPermissionStatus = WorkoutPermissions & {
  health: WorkoutPermissions['health'] & { requestStatus: 'shouldRequest' | 'unnecessary' | 'unknown' };
  locationServicesEnabled: boolean;
  locationAccuracyAuthorization: 'full' | 'reduced' | 'unknown';
};
export interface WorkoutAdapter {
  getState(): Promise<WorkoutState>;
  subscribe(listener: (state: WorkoutState) => void): () => void;
  getPermissions(): Promise<WorkoutPermissionStatus>;
  requestPermissions(options?: WorkoutOptions): Promise<WorkoutPermissions>;
  start(options: WorkoutOptions): Promise<WorkoutState>;
  pause(id?: string): Promise<WorkoutState>;
  resume(id?: string): Promise<WorkoutState>;
  lap(id?: string): Promise<WorkoutState>;
  stop(id?: string): Promise<WorkoutState>;
  recover(id: string): Promise<WorkoutState>;
  remove(id: string): Promise<WorkoutState>;
  discard(id: string): Promise<WorkoutState>;
  list(options?: CatalogRequest): Promise<WorkoutMetadata[]>;
  /** Development builds only: writes the fictional example rides that are not stored yet and returns how many were added. */
  addExampleRides?(): Promise<number>;
  read(id: string, distanceSource?: DistanceSource): Promise<WorkoutDetail>;
  export(id: string, distanceSource?: DistanceSource): Promise<string>;
  exportOriginal(id: string): Promise<string>;
}
export const workoutInProgress = (phase: WorkoutPhase) => ['preparing', 'running', 'paused', 'recoverable', 'finishing'].includes(phase);
export const unavailableWorkoutState: WorkoutState = {
  supported: false, capabilities: { phoneWorkout: false, watchWorkout: false, healthKit: false },
  id: null, phase: 'idle', startedAt: null, indoor: false, useWatch: true,
  elapsedSeconds: 0, timerSeconds: 0, pendingAction: null, healthKitState: 'notSaved', healthKitUUID: null,
  watch: { supported: false, paired: false, installed: false, reachable: false, pendingMessages: 0 },
  streams: { cyc: { status: 'missing', lastSampleAgeSeconds: null }, heartRate: { status: 'missing', lastSampleAgeSeconds: null }, gps: { status: 'missing', source: 'phone', lastSampleAgeSeconds: null, accuracyMeters: null } },
  metrics: { riderPowerW: null, cadenceRpm: null, heartRateBpm: null, activeEnergyKcal: null, basalEnergyKcal: null, distanceMeters: null, speedMps: null },
  warnings: [], error: null,
};

/** Ended and Health saved are independent from verification of the current snapshot. */
export function workoutExportReady(record: WorkoutMetadata): boolean {
  if (record.storage === 'browser') return record.phase === 'completed' && Boolean(record.endedAt) && record.finalizationState === 'complete';
  return record.phase === 'completed' && (record.sealRevision ?? 0) > 0 && record.verifiedSealRevision === record.sealRevision && (record.finalizationState === 'complete' || record.finalizationState === 'partial');
}

/** UI eligibility only; native storage verifies owner and in-flight work again before deletion. */
export function workoutCanDelete(record: WorkoutMetadata, current: WorkoutState): boolean {
  if (record.storage === 'browser' && record.phase === 'recoverable') return record.id !== current.id || (!workoutInProgress(current.phase) && !current.pendingAction);
  if (!['completed', 'failed'].includes(record.phase)) return false;
  return record.id !== current.id || (!workoutInProgress(current.phase) && !current.pendingAction && current.recoveryState !== 'checking');
}

/** Historical phone repair never selects that ride as the current owner. */
export function workoutRecoveryAction(record: WorkoutMetadata, currentID: string | null): 'repair' | 'recover' | null {
  if (record.storage === 'browser') return null;
  if (workoutExportReady(record) && record.finalizationState !== 'partial') return null;
  if (record.phase === 'completed' && !record.watchEnabled && (record.healthKitState === 'saved'
    || record.saveToHealth === false && record.healthKitState === 'notRequested' && Boolean(record.endedAt))) return 'repair';
  return record.id === currentID ? 'recover' : null;
}

export function availableWorkoutOptions(options: WorkoutOptions, capabilities: WorkoutState['capabilities']): Required<WorkoutOptions> {
  return {
    sampleHz: options.sampleHz ?? 2,
    indoor: options.indoor,
    useWatch: capabilities.watchWorkout && options.useWatch,
    saveToHealth: capabilities.healthKit && options.saveToHealth !== false,
    recordGPS: (capabilities.gps ?? capabilities.phoneWorkout) && (options.recordGPS ?? !options.indoor),
  };
}
