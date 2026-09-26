import type { CatalogRequest } from '../../src/core/catalog';
import { requireOptionalNativeModule, NativeModule } from 'expo-modules-core';
import type { ConnectionDiagnostics, ConnectionOptions, Device, NativeState, TelemetrySample } from '../../src/core/types';
import type { WorkoutState, WorkoutOptions, WorkoutPermissions, WorkoutPermissionStatus, WorkoutMetadata, WorkoutDetail } from '../../src/core/workouts';
import type { DistanceSource } from '../../src/core/distance';
import type { MonitorDescribeRequest, MonitorDescribeResult, MonitorPlotRequest, MonitorPlotResult, MonitorInspectRequest, MonitorInspectResult, MonitorStatsRequest, MonitorStatsResult, MonitorChangesRequest, MonitorChangesResult, NativeMonitorTarget } from '../../src/core/monitor';

type Events = {
  onDevice: (device: Device) => void;
  onState: (state: NativeState) => void;
  onSample: (sample: TelemetrySample) => void;
  onWorkoutState: (state: WorkoutState) => void;
};
export declare class CycBridge extends NativeModule<Events> {
  shareFile?(uri: string): Promise<void>;
  readMonitorLatest(options: NativeMonitorTarget & import('../../src/core/monitor').MonitorLatestRequest): Promise<import('../../src/core/monitor').MonitorLatestResult>;
  describeMonitorSource(options: NativeMonitorTarget & MonitorDescribeRequest): Promise<MonitorDescribeResult>;
  readMonitorPlot(options: NativeMonitorTarget & MonitorPlotRequest): Promise<MonitorPlotResult>;
  inspectMonitorAt(options: NativeMonitorTarget & MonitorInspectRequest): Promise<MonitorInspectResult>;
  readMonitorRangeStats(options: NativeMonitorTarget & MonitorStatsRequest): Promise<MonitorStatsResult>;
  monitorChangesSince(options: NativeMonitorTarget & MonitorChangesRequest): Promise<MonitorChangesResult>;
  getWorkoutState(): Promise<WorkoutState>;
  getWorkoutPermissions(): Promise<WorkoutPermissionStatus>;
  requestWorkoutPermissions(): Promise<WorkoutPermissions>;
  requestWorkoutPermissionsForOptions(options: WorkoutOptions): Promise<WorkoutPermissions>;
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
  exportWorkout(id: string, distanceSource?: DistanceSource): Promise<string>;
  exportWorkoutArchive(id: string): Promise<string>;
  getState(): Promise<NativeState>;
  getDiagnostics(): Promise<ConnectionDiagnostics>;
  readDiagnostics(): Promise<string>;
  startScan(): Promise<void>;
  stopScan(): Promise<void>;
  connect(options: ConnectionOptions): Promise<void>;
  disconnect(): Promise<void>;
}
export default requireOptionalNativeModule<CycBridge>('CycBridge');
