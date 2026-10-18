import type { FitPowerSource } from './export/types';
import {
  coreMetricDeclarations,
  presentationMetric,
  presentationFormatMetric,
  presentationFormatPoint,
  presentationFormatDifference,
  presentationMetricAllowsMean,
  presentationDefaultPreferences,
  presentationParsePreferences,
  presentationPresetLabel,
  presentationReadingAvailable,
  type PresentationPreferences,
} from '../../shared/js';
import type { ChartViewport } from './chart-viewport';
import type { DistanceSource, MetricSourceInfo } from './distance';
import type { BatteryConsumptionSource } from './consumption';
export type { MetricSourceInfo } from './distance';

export type MonitorRevision = string;
export interface MonitorPoint {
  elapsedSeconds: number;
  timestamp: string;
  value: number;
  exactValue?: string;
  observationId?: string;
  startsSegment?: boolean;
  derived?: boolean;
}
export interface MonitorStatistics {
  min?: MonitorPoint;
  max?: MonitorPoint;
  count?: number;
  /** Observation mean is distinct from duration-weighted integration. */
  sampleMean?: number;
  coveredSeconds?: number;
  integral?: number;
  distance?: number;
  amount?: number;
  unresolvedBoundary?: boolean;
  partial?: boolean;
}
export interface MonitorData {
  metricSources?: Record<string, MetricSourceInfo>;
  sourceId: string;
  revision?: MonitorRevision;
  startedAt: string;
  domain: ChartViewport;
  nowSeconds?: number;
  monotonicAt?: number;
  liveAcquiredAt?: Record<string, number | null>;
  mapMonotonicSeconds?: (seconds: number) => number | null;
  series: Record<string, MonitorPoint[]>;
  statistics: Record<string, MonitorStatistics>;
  plotViewport?: ChartViewport;
  plotGeneration?: number;
  latest?: Record<string, MonitorPoint | null>;
  selection?: Record<string, MonitorPoint | null>;
  reference?: Record<string, MonitorPoint | null>;
  selectionSeconds?: number;
  referenceSeconds?: number;
  selectionPending?: boolean;
  statisticsRevision?: MonitorRevision;
  statisticsViewport?: ChartViewport;
  statisticsPending?: boolean;
  latestRevision?: MonitorRevision;
  selectionRevision?: MonitorRevision;
  referenceRevision?: MonitorRevision;
}
export type NativeMonitorTarget = {
  source: 'live' | 'workout';
  id?: string;
  distanceSource?: DistanceSource;
  cancellationId?: string;
};
export type MonitorDescribeRequest = { generation: number; signal?: AbortSignal };
export type MonitorLatestRequest = MonitorDescribeRequest & { metrics: string[] };
export type MonitorSnapshotRequest = MonitorDescribeRequest & { expectedRevision: MonitorRevision };
export type MonitorPlotRequest = MonitorSnapshotRequest & {
  metrics: string[];
  startSeconds?: number;
  endSeconds?: number;
  buckets?: number;
  pixelWidth?: number;
};
export type MonitorObservationAnchor = { metric: string; observationId: string };
export type MonitorInspectRequest = MonitorSnapshotRequest & {
  metrics: string[];
  seconds: number;
  anchor?: MonitorObservationAnchor;
};
export type MonitorStatsRequest = MonitorSnapshotRequest & {
  metrics: string[];
  startSeconds: number;
  endSeconds: number;
  includeEndpoints?: boolean;
  startAnchor?: MonitorObservationAnchor;
  endAnchor?: MonitorObservationAnchor;
};
export type MonitorChangesRequest = MonitorDescribeRequest & { sinceRevision: MonitorRevision };
export type MonitorEnvelope = {
  generation: number;
  sourceId: string;
  revision: MonitorRevision;
  metricSources?: Record<string, MetricSourceInfo>;
};
export type MonitorRetry = MonitorEnvelope & { status: 'retry' };
export type MonitorResult<T> = (MonitorEnvelope & { status: 'ok' } & T) | MonitorRetry;
export type MonitorDescription = {
  metricSources?: Record<string, MetricSourceInfo>;
  startedAt: string;
  domain: ChartViewport;
  nowSeconds?: number;
  monotonicAt?: number;
  availableMetrics: string[];
  outcome: 'available' | 'pending' | 'partial' | 'unavailable';
};
export type MonitorDescribeResult = MonitorEnvelope & { status: 'ok' } & MonitorDescription;
export type MonitorLatestResult = MonitorResult<{
  points: Record<string, MonitorPoint | null>;
  nowSeconds?: number;
  monotonicAt?: number;
  liveAcquiredAt?: Record<string, number | null>;
}>;
export type MonitorPlotResult = MonitorResult<{
  series: Record<string, MonitorPoint[]>;
  resolution?: 'original' | 'reduced';
}>;
export type MonitorInspectResult = MonitorResult<{
  seconds: number;
  points: Record<string, MonitorPoint | null>;
  gaps: Record<string, boolean>;
}>;
export type MonitorStatsResult = MonitorResult<{
  statistics: Record<string, MonitorStatistics>;
  endpoints?: { start: Record<string, MonitorPoint | null>; end: Record<string, MonitorPoint | null> };
}>;
export type MonitorChange = {
  startSeconds: number;
  endSeconds: number;
  metrics?: string[];
  kind: 'append' | 'correction' | 'semantics';
};
export type MonitorChangesResult = MonitorEnvelope & { status: 'ok'; resetRequired: boolean; changes: MonitorChange[] };
export interface MonitorSource {
  key: string;
  live: boolean;
  mapMonotonicSeconds?(seconds: number): number | null;
  revisionHint?: string;
  semanticKey?: string;
  describeSource(request: MonitorDescribeRequest): Promise<MonitorDescribeResult>;
  readLatest(request: MonitorLatestRequest): Promise<MonitorLatestResult>;
  readPlot(request: MonitorPlotRequest): Promise<MonitorPlotResult>;
  inspectAt(request: MonitorInspectRequest): Promise<MonitorInspectResult>;
  rangeStats(request: MonitorStatsRequest): Promise<MonitorStatsResult>;
  changesSince(request: MonitorChangesRequest): Promise<MonitorChangesResult>;
}
export type MonitorMetric = {
  id: string;
  label: string;
  shortLabel: string;
  unit: string;
  group: 'Rider' | 'Battery' | 'Motor' | 'Route' | 'Status';
  color: string;
  decimals: number;
  gapSeconds: number;
  scale?: number;
  kind?: 'step';
  zero?: boolean;
  semantics?: 'cumulative' | 'counter' | 'distance' | 'category' | 'circular';
  description?: string;
};

export const MONITOR_METRICS: readonly MonitorMetric[] = coreMetricDeclarations().map(metric => ({
  id: metric.id,
  label: metric.label,
  shortLabel: metric.shortLabel,
  unit: metric.unit,
  group: metric.group as MonitorMetric['group'],
  color: metric.color,
  decimals: metric.decimals,
  gapSeconds: metric.gapSeconds,
  ...(metric.scale === 1 ? {} : { scale: metric.scale }),
  ...(metric.kind == null ? {} : { kind: metric.kind as MonitorMetric['kind'] }),
  ...(metric.zero == null ? {} : { zero: metric.zero }),
  ...(metric.semantics == null ? {} : { semantics: metric.semantics as MonitorMetric['semantics'] }),
  ...(metric.description == null ? {} : { description: metric.description }),
}));
export type SpeedUnit = 'km/h' | 'mph' | 'm/s';
const metricCache = new Map<string, MonitorMetric>(MONITOR_METRICS.map(metric => [`km/h:${metric.id}`, metric]));
export function metricById(id: string, speedUnit: SpeedUnit = 'km/h'): MonitorMetric | undefined {
  if (!metricCache.has(`km/h:${id}`)) return undefined;
  const key = `${speedUnit}:${id}`;
  let metric = metricCache.get(key);
  if (!metric) {
    const declaration = presentationMetric(id, speedUnit);
    metric = { ...metricCache.get(`km/h:${id}`)!, unit: declaration.unit, scale: declaration.scale };
    metricCache.set(key, metric);
  }
  return metric;
}
export function metricAllowsMean(metric: MonitorMetric): boolean {
  return presentationMetricAllowsMean(metric.id);
}
export const controllerSpeedPreferenceId = (id: string) => (id === 'speedRaw' ? 'controllerSpeedMps' : id);
export function monitorQueryMetrics(ids: readonly string[]): string[] {
  return [...new Set(ids.flatMap(id => (id === 'controllerSpeedMps' ? [id, 'speedRaw'] : [id])))];
}
export function resolveMonitorMetrics(ids: readonly string[], available: readonly string[] = []): string[] {
  const unknownUnit = !available.includes('controllerSpeedMps') && available.includes('speedRaw');
  return ids.map(id => (id === 'controllerSpeedMps' && unknownUnit ? 'speedRaw' : id));
}
export const metricValue = (metric: MonitorMetric, value: number) => value * (metric.scale ?? 1);
const metricSpeedUnit = (metric: MonitorMetric): SpeedUnit =>
  metric.unit === 'mph' || metric.unit === 'm/s' ? metric.unit : 'km/h';
export function formatMetric(metric: MonitorMetric, value: number | null | undefined): string {
  return presentationFormatMetric(metric.id, value ?? null, metricSpeedUnit(metric));
}
export function formatMetricPoint(metric: MonitorMetric, point: MonitorPoint | null | undefined): string {
  return presentationFormatPoint(metric.id, point?.value ?? null, point?.exactValue ?? null, metricSpeedUnit(metric));
}
export function formatMetricDifference(metric: MonitorMetric, a: MonitorPoint | null, b: MonitorPoint | null): string {
  return presentationFormatDifference(
    metric.id,
    a?.value ?? null,
    b?.value ?? null,
    a?.exactValue ?? null,
    b?.exactValue ?? null,
    metricSpeedUnit(metric),
  );
}

export type MonitorViewId = 'ride' | 'battery' | 'temperature';
export type MonitorRange = 30 | 120 | 600 | 'all';
export type MonitorColumns = 1 | 2 | 3;
export interface MonitorView {
  id: MonitorViewId;
  name: string;
  numbers: string[];
  charts: string[];
  range: MonitorRange;
  webColumns: MonitorColumns;
}
export class MonitorPreferencesError extends Error {}

export interface MonitorPreferences {
  version: 1;
  activeView: MonitorViewId;
  historyView?: MonitorViewId;
  historyRange: MonitorRange;
  speedUnit: SpeedUnit;
  distanceSource: DistanceSource;
  batteryConsumptionSource: BatteryConsumptionSource;
  fitPowerSource: FitPowerSource;
  sampleHz: 2 | 4 | 8;
  views: Record<MonitorViewId, MonitorView>;
  workoutOptions: import('./workouts').WorkoutOptions;
}
export const MONITOR_VIEW_IDS = ['ride', 'battery', 'temperature'] as const;

const retainedPreferences = new WeakMap<MonitorPreferences, PresentationPreferences>();
export function monitorPreferencesFromCore(core: PresentationPreferences, retain = false): MonitorPreferences {
  const views = {} as MonitorPreferences['views'];
  for (const id of MONITOR_VIEW_IDS) {
    const view = core.view(id);
    views[id] = {
      id,
      name: presentationPresetLabel(id),
      numbers: view.numbers,
      charts: view.charts,
      range: (view.range === 'all' ? 'all' : Number(view.range)) as MonitorRange,
      webColumns: view.webColumns as MonitorColumns,
    };
  }
  const options = core.options;
  const result: MonitorPreferences = {
    version: 1,
    activeView: core.activeView as MonitorViewId,
    ...(core.historyView === null ? {} : { historyView: core.historyView as MonitorViewId }),
    historyRange: (core.historyRange === 'all' ? 'all' : Number(core.historyRange)) as MonitorRange,
    speedUnit: core.speedUnit as SpeedUnit,
    distanceSource: core.distanceSource as DistanceSource,
    batteryConsumptionSource: core.batteryConsumptionSource as BatteryConsumptionSource,
    fitPowerSource: core.fitPowerSource as FitPowerSource,
    sampleHz: core.sampleHz as 2 | 4 | 8,
    views,
    workoutOptions: {
      indoor: options.indoor,
      useWatch: options.useWatch!,
      saveToHealth: options.saveToHealth!,
      ...(options.recordGPS === null ? {} : { recordGPS: options.recordGPS }),
    },
  };
  if (retain) {
    for (const view of Object.values(views)) {
      Object.freeze(view.numbers);
      Object.freeze(view.charts);
      Object.freeze(view);
    }
    Object.freeze(views);
    Object.freeze(result.workoutOptions);
    Object.freeze(result);
    retainedPreferences.set(result, core);
  }
  return result;
}
export function defaultMonitorPreferences(): MonitorPreferences {
  return monitorPreferencesFromCore(presentationDefaultPreferences());
}
function preferenceRecord(input: unknown): unknown {
  if (!input || typeof input !== 'object' || Array.isArray(input)) return input;
  const value = input as MonitorPreferences;
  if (!value.views || typeof value.views !== 'object') return input;
  const views = Object.fromEntries(
    Object.entries(value.views).map(([id, view]) => {
      if (!view || typeof view !== 'object') return [id, view];
      const { id: _id, name: _name, ...fields } = view;
      return [id, fields];
    }),
  );
  const { sampleHz: _sampleHz, ...workoutOptions } = value.workoutOptions ?? {};
  return { ...value, workoutOptions, views };
}
export function monitorPreferencesCore(input: MonitorPreferences): PresentationPreferences {
  const retained = retainedPreferences.get(input);
  if (retained) return retained;
  const contents = JSON.stringify(preferenceRecord(input));
  if (contents === undefined) throw new Error('Invalid saved monitor settings.');
  return presentationParsePreferences(contents);
}
export function parseMonitorPreferences(contents: string): MonitorPreferences {
  return monitorPreferencesFromCore(presentationParsePreferences(contents), true);
}
export function monitorPreferencesJson(preferences: MonitorPreferences): string {
  return monitorPreferencesCore(preferences).storedJson;
}
export function currentMonitorPoint(
  point: MonitorPoint | null | undefined,
  live: boolean,
  monotonicAt: number | undefined,
  gapSeconds: number,
  acquiredAt: number | null | undefined,
  mapMonotonicSeconds?: (seconds: number) => number | null,
): MonitorPoint | null {
  if (!point || !live) return point ?? null;
  if (acquiredAt != null && mapMonotonicSeconds) acquiredAt = mapMonotonicSeconds(acquiredAt);
  return presentationReadingAvailable(acquiredAt ?? null, monotonicAt ?? null, gapSeconds) ? point : null;
}
