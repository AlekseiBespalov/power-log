import type { CatalogRequest } from '../../src/core/catalog';
import { requireOptionalNativeModule, NativeModule } from 'expo-modules-core';
import type {
  ConnectionDiagnostics,
  ConnectionOptions,
  Device,
  NativeState,
  TelemetrySample,
} from '../../src/core/types';
import type {
  WorkoutState,
  WorkoutOptions,
  WorkoutPermissions,
  WorkoutPermissionStatus,
  WorkoutMetadata,
  WorkoutDetail,
} from '../../src/core/workouts';
import type { DistanceSource } from '../../src/core/distance';
import type {
  MonitorDescribeRequest,
  MonitorDescribeResult,
  MonitorPlotRequest,
  MonitorPlotResult,
  MonitorInspectRequest,
  MonitorInspectResult,
  MonitorStatsRequest,
  MonitorStatsResult,
  MonitorChangesRequest,
  MonitorChangesResult,
  NativeMonitorTarget,
} from '../../src/core/monitor';
import type {
  ExportCommitResult,
  ExportContext,
  ExportDeflateResult,
  ExportFileKind,
  ExportOpenRequest,
  ExportOpenResult,
  ExportPageRequest,
  NativeExportPage,
  ProjectionName,
} from '../../src/core/export/types';

type Events = {
  onDevice: (device: Device) => void;
  onState: (state: NativeState) => void;
  onSample: (sample: TelemetrySample & { acquiredAtMonotonic: number }) => void;
  onWorkoutState: (state: WorkoutState) => void;
};
export declare class CycBridge extends NativeModule<Events> {
  getMonotonicSeconds(): Promise<number>;
  shareFile?(uri: string): Promise<void>;
  readMonitorLatest(
    options: NativeMonitorTarget & import('../../src/core/monitor').MonitorLatestRequest,
  ): Promise<import('../../src/core/monitor').MonitorLatestResult>;
  describeMonitorSource(options: NativeMonitorTarget & MonitorDescribeRequest): Promise<MonitorDescribeResult>;
  readMonitorPlot(options: NativeMonitorTarget & MonitorPlotRequest): Promise<MonitorPlotResult>;
  inspectMonitorAt(options: NativeMonitorTarget & MonitorInspectRequest): Promise<MonitorInspectResult>;
  readMonitorRangeStats(options: NativeMonitorTarget & MonitorStatsRequest): Promise<MonitorStatsResult>;
  monitorChangesSince(options: NativeMonitorTarget & MonitorChangesRequest): Promise<MonitorChangesResult>;
  getWorkoutState(): Promise<WorkoutState>;
  getWorkoutPermissions(): Promise<WorkoutPermissionStatus>;
  requestWorkoutPermissions(options: WorkoutOptions): Promise<WorkoutPermissions>;
  startWorkout(options: WorkoutOptions): Promise<WorkoutState>;
  pauseWorkout(id?: string): Promise<WorkoutState>;
  resumeWorkout(id?: string): Promise<WorkoutState>;
  markWorkoutLap(id?: string): Promise<WorkoutState>;
  stopWorkout(id?: string): Promise<WorkoutState>;
  recoverWorkout(id: string): Promise<WorkoutState>;
  deleteWorkout(id: string): Promise<WorkoutState>;
  discardWorkout(id: string): Promise<WorkoutState>;
  listWorkouts(options?: CatalogRequest): Promise<WorkoutMetadata[]>;
  /** Development builds only. */
  addExampleRides?(): Promise<{ added: number }>;
  readWorkout(id: string, distanceSource?: DistanceSource): Promise<WorkoutDetail>;
  getState(): Promise<NativeState>;
  getDiagnostics(): Promise<ConnectionDiagnostics>;
  startScan(): Promise<void>;
  stopScan(): Promise<void>;
  connect(options: ConnectionOptions): Promise<void>;
  disconnect(): Promise<void>;
  exportOpen(request: ExportOpenRequest): Promise<ExportOpenResult>;
  exportPage<P extends ProjectionName>(request: ExportPageRequest<P>): Promise<NativeExportPage<P>>;
  exportClose(session: string): Promise<void>;
  sinkOpen(kind: ExportFileKind, context: ExportContext): Promise<string>;
  sinkWrite(id: string, bytes: Uint8Array): Promise<void>;
  sinkWriteAt(id: string, offset: number, bytes: Uint8Array): Promise<void>;
  sinkBeginDeflate(id: string): Promise<void>;
  sinkEndDeflate(id: string): Promise<ExportDeflateResult>;
  sinkCommit(id: string, name: string): Promise<ExportCommitResult>;
  sinkAbort(id: string): Promise<void>;
}
export default requireOptionalNativeModule<CycBridge>('CycBridge');
