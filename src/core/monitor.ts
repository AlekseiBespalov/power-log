import type { ChartViewport } from './chart-viewport';
import { DISTANCE_SOURCE_LABELS, type DistanceSource, type MetricSourceInfo } from './distance';
export type { MetricSourceInfo } from './distance';

/** Revisions and original identities cross the bridge losslessly as strings. */
export type MonitorRevision = string;
export interface MonitorPoint { elapsedSeconds: number; timestamp: string; value: number; exactValue?: string; observationId?: string; startsSegment?: boolean; derived?: boolean }
export interface MonitorStatistics {
  min?: MonitorPoint; max?: MonitorPoint; count?: number;
  /** Observation mean is distinct from duration-weighted integration. */
  sampleMean?: number; coveredSeconds?: number; integral?: number;
  distance?: number; unresolvedBoundary?: boolean; partial?: boolean;
}
/** Rendering model assembled from independent snapshot reads. */
export interface MonitorData {
  metricSources?: Record<string, MetricSourceInfo>;
  sourceId: string; revision?: MonitorRevision; startedAt: string; domain: ChartViewport; nowSeconds?: number;
  series: Record<string, MonitorPoint[]>; statistics: Record<string, MonitorStatistics>;
  /** Coordinates used to build the retained drawing; independent of a gesture preview. */
  plotViewport?: ChartViewport;
  /** Identity of the admitted geometry query, including its reduction budget. */
  plotGeneration?: number;
  latest?: Record<string, MonitorPoint | null>; selection?: Record<string, MonitorPoint | null>; reference?: Record<string, MonitorPoint | null>;
  selectionSeconds?: number; referenceSeconds?: number;
  selectionPending?: boolean;
  statisticsRevision?: MonitorRevision; statisticsViewport?: ChartViewport; statisticsPending?: boolean; latestRevision?: MonitorRevision; selectionRevision?: MonitorRevision; referenceRevision?: MonitorRevision;
}
export type NativeMonitorTarget = { source: 'live' | 'workout'; id?: string; distanceSource?: DistanceSource };
export type MonitorDescribeRequest = { generation: number };
export type MonitorLatestRequest = MonitorDescribeRequest & { metrics: string[] };
export type MonitorSnapshotRequest = MonitorDescribeRequest & { expectedRevision: MonitorRevision };
export type MonitorPlotRequest = MonitorSnapshotRequest & { metrics: string[]; startSeconds?: number; endSeconds?: number; buckets?: number; pixelWidth?: number };
export type MonitorObservationAnchor = { metric: string; observationId: string };
export type MonitorInspectRequest = MonitorSnapshotRequest & { metrics: string[]; seconds: number; anchor?: MonitorObservationAnchor };
export type MonitorStatsRequest = MonitorSnapshotRequest & { metrics: string[]; startSeconds: number; endSeconds: number; includeEndpoints?: boolean; startAnchor?: MonitorObservationAnchor; endAnchor?: MonitorObservationAnchor };
export type MonitorChangesRequest = MonitorDescribeRequest & { sinceRevision: MonitorRevision };
export type MonitorEnvelope = { generation: number; sourceId: string; revision: MonitorRevision; metricSources?: Record<string, MetricSourceInfo> };
export type MonitorRetry = MonitorEnvelope & { status: 'retry' };
export type MonitorResult<T> = (MonitorEnvelope & { status: 'ok' } & T) | MonitorRetry;
export type MonitorDescription = {
  metricSources?: Record<string, MetricSourceInfo>;
  startedAt: string; domain: ChartViewport; nowSeconds?: number; availableMetrics: string[];
  outcome: 'available' | 'pending' | 'partial' | 'unavailable'; warnings: string[];
};
export type MonitorDescribeResult = MonitorEnvelope & { status: 'ok' } & MonitorDescription;
export type MonitorLatestResult = MonitorResult<{ points: Record<string, MonitorPoint | null> }>;
export type MonitorPlotResult = MonitorResult<{ series: Record<string, MonitorPoint[]>; latest: Record<string, MonitorPoint | null>; resolution?: 'original' | 'reduced' }>;
export type MonitorInspectResult = MonitorResult<{ seconds: number; points: Record<string, MonitorPoint | null>; gaps: Record<string, boolean> }>;
export type MonitorStatsResult = MonitorResult<{ statistics: Record<string, MonitorStatistics>; endpoints?: { start: Record<string, MonitorPoint | null>; end: Record<string, MonitorPoint | null> } }>;
export type MonitorChange = { startSeconds: number; endSeconds: number; metrics?: string[]; kind: 'append' | 'correction' | 'semantics' };
export type MonitorChangesResult = MonitorEnvelope & { status: 'ok'; resetRequired: boolean; changes: MonitorChange[] };
export interface MonitorSource {
  /** Stable across ordinary data/seal revisions; changes for a different ride/session. */
  key: string; live: boolean;
  /** Optional catalog revision hint causes a refresh without replacing source identity. */
  revisionHint?: string;
  /** Analytical choices invalidate pending reads without replacing the ride. */
  semanticKey?: string;
  describeSource(request: MonitorDescribeRequest): Promise<MonitorDescribeResult>;
  readLatest(request: MonitorLatestRequest): Promise<MonitorLatestResult>;
  readPlot(request: MonitorPlotRequest): Promise<MonitorPlotResult>;
  inspectAt(request: MonitorInspectRequest): Promise<MonitorInspectResult>;
  rangeStats(request: MonitorStatsRequest): Promise<MonitorStatsResult>;
  changesSince(request: MonitorChangesRequest): Promise<MonitorChangesResult>;
}
export type MonitorMetric = {
  id: string; label: string; shortLabel: string; unit: string; group: 'Rider' | 'Battery' | 'Motor' | 'Route' | 'Status';
  color: string; decimals: number; gapSeconds: number; scale?: number; kind?: 'step'; zero?: boolean;
  semantics?: 'cumulative' | 'counter' | 'distance' | 'category' | 'circular';
};

export const MONITOR_METRICS: readonly MonitorMetric[] = [
  { id: 'humanPowerW', label: 'Rider power', shortLabel: 'Rider', unit: 'W', group: 'Rider', color: '#ff8735', decimals: 0, gapSeconds: 6, zero: true },
  { id: 'cadenceRpm', label: 'Cadence', shortLabel: 'Cadence', unit: 'rpm', group: 'Rider', color: '#ad98e4', decimals: 0, gapSeconds: 6, zero: true },
  { id: 'heartRateBpm', label: 'Heart rate', shortLabel: 'Heart rate', unit: 'bpm', group: 'Rider', color: '#fb7185', decimals: 0, gapSeconds: 15 },
  { id: 'pedalTorqueNm', label: 'Pedal torque', shortLabel: 'Torque', unit: 'Nm', group: 'Rider', color: '#e9b65a', decimals: 1, gapSeconds: 6, zero: true },
  { id: 'activeEnergyKcal', label: 'Active energy', shortLabel: 'Active energy', unit: 'kcal', group: 'Rider', color: '#f6b26b', decimals: 0, gapSeconds: 120, zero: true },
  { id: 'basalEnergyKcal', label: 'Resting energy', shortLabel: 'Resting energy', unit: 'kcal', group: 'Rider', color: '#b8a184', decimals: 0, gapSeconds: 120, zero: true },
  { id: 'batteryVoltageV', label: 'Battery voltage', shortLabel: 'Voltage', unit: 'V', group: 'Battery', color: '#f4cf5b', decimals: 2, gapSeconds: 6 },
  { id: 'batteryCurrentA', label: 'Battery current', shortLabel: 'Battery current', unit: 'A', group: 'Battery', color: '#57c6f5', decimals: 1, gapSeconds: 6, zero: true },
  { id: 'motorInputPowerW', label: 'Motor input power', shortLabel: 'Motor input', unit: 'W', group: 'Battery', color: '#3d8bfd', decimals: 0, gapSeconds: 6, zero: true },
  { id: 'consumedAh', label: 'Battery charge used', shortLabel: 'Charge used', unit: 'Ah', group: 'Battery', color: '#cbb48f', decimals: 2, gapSeconds: 6, zero: true, semantics: 'counter' },
  { id: 'consumedWh', label: 'Battery energy used', shortLabel: 'Energy used', unit: 'Wh', group: 'Battery', color: '#e2ae71', decimals: 1, gapSeconds: 6, zero: true, semantics: 'counter' },
  { id: 'motorCurrentA', label: 'Motor current', shortLabel: 'Motor current', unit: 'A', group: 'Motor', color: '#e693c1', decimals: 1, gapSeconds: 6, zero: true },
  { id: 'motorTempC', label: 'Motor temperature', shortLabel: 'Motor', unit: '°C', group: 'Motor', color: '#ff9875', decimals: 1, gapSeconds: 6 },
  { id: 'controllerTempC', label: 'Controller temperature', shortLabel: 'Controller', unit: '°C', group: 'Motor', color: '#6bd4c5', decimals: 1, gapSeconds: 6 },
  { id: 'motorRpm', label: 'Motor speed', shortLabel: 'Motor speed', unit: 'rpm', group: 'Motor', color: '#84b1f5', decimals: 0, gapSeconds: 6, zero: true },
  { id: 'throttleVoltageV', label: 'Throttle voltage', shortLabel: 'Throttle', unit: 'V', group: 'Motor', color: '#d3a8e6', decimals: 2, gapSeconds: 6 },
  { id: 'speedMps', label: 'GPS speed', shortLabel: 'GPS', unit: 'km/h', group: 'Route', color: '#91c77a', decimals: 1, gapSeconds: 10, scale: 3.6, zero: true },
  { id: 'healthSpeedMps', label: 'Health cycling speed', shortLabel: 'Health', unit: 'km/h', group: 'Route', color: '#81c4b2', decimals: 1, gapSeconds: 10, scale: 3.6, zero: true },
  { id: 'controllerSpeedMps', label: 'Controller speed', shortLabel: 'Controller', unit: 'km/h', group: 'Route', color: '#8799ac', decimals: 1, gapSeconds: 6, scale: 3.6, zero: true },
  { id: 'distanceMeters', label: 'Ride distance', shortLabel: 'Distance', unit: 'km', group: 'Route', color: '#aed195', decimals: 2, gapSeconds: 120, scale: 0.001, zero: true, semantics: 'distance' },
  { id: 'altitudeMeters', label: 'Altitude', shortLabel: 'Altitude', unit: 'm', group: 'Route', color: '#b6bcd0', decimals: 0, gapSeconds: 10 },
  { id: 'horizontalAccuracyM', label: 'GPS accuracy', shortLabel: 'GPS accuracy', unit: 'm', group: 'Route', color: '#94a3b8', decimals: 1, gapSeconds: 10, zero: true },
  { id: 'verticalAccuracyM', label: 'Altitude accuracy', shortLabel: 'Altitude accuracy', unit: 'm', group: 'Route', color: '#acb4ce', decimals: 1, gapSeconds: 10, zero: true },
  { id: 'courseDegrees', label: 'Course', shortLabel: 'Course', unit: '°', group: 'Route', color: '#73aaa7', decimals: 0, gapSeconds: 10, semantics: 'circular' },
  { id: 'assistLevel', label: 'Assist level', shortLabel: 'Assist', unit: '', group: 'Status', color: '#dcad77', decimals: 0, gapSeconds: 6, kind: 'step', zero: true },
  { id: 'raceMode', label: 'Race mode', shortLabel: 'Race mode', unit: '', group: 'Status', color: '#ed9c94', decimals: 0, gapSeconds: 6, kind: 'step', zero: true },
  { id: 'faultCode', label: 'Controller fault code', shortLabel: 'Fault code', unit: '', group: 'Status', color: '#d7dde5', decimals: 0, gapSeconds: 6, kind: 'step', zero: true },
  { id: 'speedRaw', label: 'Controller speed (unit unknown)', shortLabel: 'Unit unknown', unit: 'raw', group: 'Route', color: '#8799ac', decimals: 2, gapSeconds: 6 },
];
export type SpeedUnit = 'km/h' | 'mph' | 'm/s';
const imperialSpeedMetrics = new Map(MONITOR_METRICS.filter(metric => ['speedMps', 'controllerSpeedMps', 'healthSpeedMps'].includes(metric.id))
  .map(metric => [metric.id, { ...metric, unit: 'mph', scale: 1 / 0.44704 }]));
const siSpeedMetrics = new Map(MONITOR_METRICS.filter(metric => ['speedMps', 'controllerSpeedMps', 'healthSpeedMps'].includes(metric.id))
  .map(metric => [metric.id, { ...metric, unit: 'm/s', scale: 1 }]));
export const metricById = (id: string, speedUnit: SpeedUnit = 'km/h') => (speedUnit === 'mph' ? imperialSpeedMetrics.get(id) : speedUnit === 'm/s' ? siSpeedMetrics.get(id) : undefined) ?? MONITOR_METRICS.find(metric => metric.id === id);
export function metricAllowsMean(metric: MonitorMetric): boolean { return !metric.semantics && metric.kind !== 'step' && !['activeEnergyKcal', 'basalEnergyKcal'].includes(metric.id); }
export const controllerSpeedPreferenceId = (id: string) => id === 'speedRaw' ? 'controllerSpeedMps' : id;
export function monitorQueryMetrics(ids: readonly string[]): string[] {
  return [...new Set(ids.flatMap(id => id === 'controllerSpeedMps' ? [id, 'speedRaw'] : [id]))];
}
export function resolveMonitorMetrics(ids: readonly string[], available: readonly string[] = []): string[] {
  const unknownUnit = !available.includes('controllerSpeedMps') && available.includes('speedRaw');
  return ids.map(id => id === 'controllerSpeedMps' && unknownUnit ? 'speedRaw' : id);
}
export const metricValue = (metric: MonitorMetric, value: number) => value * (metric.scale ?? 1);
export const formatMetric = (metric: MonitorMetric, value: number | null | undefined) => value == null || !Number.isFinite(value) ? '—' : metricValue(metric, value).toFixed(metric.decimals);
/** Exact integer originals never pass through Number on their way to a text readout. */
export function formatExactMetricInteger(metric: MonitorMetric, original: string): string | null {
  if (!/^-?(?:0|[1-9][0-9]*)$/.test(original)) return null;
  const scale = String(metric.scale ?? 1), match = /^([0-9]+)(?:\.([0-9]+))?$/.exec(scale);
  if (!match) return null;
  const decimal = match[2] ?? '', denominator = 10n ** BigInt(decimal.length);
  const numerator = BigInt(original) * BigInt(match[1]! + decimal) * 10n ** BigInt(metric.decimals);
  const magnitude = numerator < 0n ? -numerator : numerator;
  const rounded = magnitude / denominator + (magnitude % denominator * 2n >= denominator ? 1n : 0n);
  const digits = rounded.toString().padStart(metric.decimals + 1, '0');
  const text = metric.decimals ? `${digits.slice(0, -metric.decimals)}.${digits.slice(-metric.decimals)}` : digits;
  return `${numerator < 0n ? '-' : ''}${text}`;
}
export function formatMetricPoint(metric: MonitorMetric, point: MonitorPoint | null | undefined): string {
  if (!point) return '—';
  return (point.exactValue === undefined ? null : formatExactMetricInteger(metric, point.exactValue)) ?? formatMetric(metric, point.value);
}
export function formatMetricDifference(metric: MonitorMetric, a: MonitorPoint | null, b: MonitorPoint | null): string {
  if (!a || !b || !metricAllowsMean(metric)) return '—';
  if (a.exactValue !== undefined && b.exactValue !== undefined && /^-?[0-9]+$/.test(a.exactValue) && /^-?[0-9]+$/.test(b.exactValue)) {
    const difference = BigInt(b.exactValue) - BigInt(a.exactValue), text = formatExactMetricInteger(metric, difference.toString());
    if (text !== null) return `${difference > 0n ? '+' : ''}${text}`;
  }
  const delta = metricValue(metric, b.value) - metricValue(metric, a.value);
  return Number.isFinite(delta) ? `${delta > 0 ? '+' : ''}${delta.toFixed(metric.decimals)}` : '—';
}

export type MonitorViewId = 'ride' | 'battery' | 'temperature';
export type MonitorRange = 30 | 120 | 600 | 'all';
export type MonitorColumns = 1 | 2 | 3;
export interface MonitorView { id: MonitorViewId; name: string; numbers: string[]; charts: string[]; range: MonitorRange; webColumns: MonitorColumns }
export interface MonitorPreferences { version: 1; activeView: MonitorViewId; historyView?: MonitorViewId; historyRange: MonitorRange; speedUnit: SpeedUnit; distanceSource: DistanceSource; sampleHz: 2 | 4 | 8; views: Record<MonitorViewId, MonitorView>; workoutOptions: import('./workouts').WorkoutOptions }
export function defaultMonitorPreferences(): MonitorPreferences {
  return { version: 1, activeView: 'ride', historyRange: 'all', speedUnit: 'km/h', distanceSource: 'auto', sampleHz: 2, workoutOptions: { indoor: false, useWatch: true, saveToHealth: true }, views: {
    ride: { id: 'ride', name: 'Ride', numbers: ['humanPowerW', 'cadenceRpm', 'heartRateBpm', 'speedMps'], charts: ['humanPowerW', 'motorInputPowerW', 'cadenceRpm'], range: 120, webColumns: 2 },
    battery: { id: 'battery', name: 'Battery', numbers: ['batteryVoltageV', 'batteryCurrentA', 'motorInputPowerW'], charts: ['batteryVoltageV', 'batteryCurrentA', 'motorInputPowerW'], range: 120, webColumns: 2 },
    temperature: { id: 'temperature', name: 'Temperature', numbers: ['motorTempC', 'controllerTempC', 'motorInputPowerW'], charts: ['motorTempC', 'controllerTempC', 'motorInputPowerW'], range: 600, webColumns: 2 },
  } };
}
export function validateMonitorPreferences(input: unknown): MonitorPreferences {
  const result = defaultMonitorPreferences();
  if (!input || typeof input !== 'object' || Array.isArray(input)) return result;
  const data = input as Record<string, unknown>;
  if (data.version !== 1) return result;
  if (data.speedUnit === 'mph' || data.speedUnit === 'm/s') result.speedUnit = data.speedUnit;
  if (typeof data.distanceSource === 'string' && Object.hasOwn(DISTANCE_SOURCE_LABELS, data.distanceSource)) result.distanceSource = data.distanceSource as DistanceSource;
  if ([2, 4, 8].includes(data.sampleHz as number)) result.sampleHz = data.sampleHz as 2 | 4 | 8;
  if (data.workoutOptions && typeof data.workoutOptions === 'object') {
    const options = data.workoutOptions as Record<string, unknown>;
    if (typeof options.indoor === 'boolean') result.workoutOptions.indoor = options.indoor;
    if (typeof options.useWatch === 'boolean') result.workoutOptions.useWatch = options.useWatch;
    if (typeof options.saveToHealth === 'boolean') result.workoutOptions.saveToHealth = options.saveToHealth;
    if (typeof options.recordGPS === 'boolean') result.workoutOptions.recordGPS = options.recordGPS;
  }
  const ids = ['ride', 'battery', 'temperature'] as const;
  if (ids.includes(data.activeView as MonitorViewId)) result.activeView = data.activeView as MonitorViewId;
  if (ids.includes(data.historyView as MonitorViewId)) result.historyView = data.historyView as MonitorViewId;
  if ([30, 120, 600, 'all'].includes(data.historyRange as MonitorRange)) result.historyRange = data.historyRange as MonitorRange;
  const views = data.views && typeof data.views === 'object' ? data.views as Record<string, unknown> : {};
  for (const id of ids) {
    const view = views[id]; if (!view || typeof view !== 'object') continue;
    const candidate = view as Record<string, unknown>;
    for (const key of ['numbers', 'charts'] as const) if (Array.isArray(candidate[key])) {
      result.views[id][key] = [...new Set((candidate[key] as unknown[]).filter((item): item is string => typeof item === 'string' && !!metricById(item)).map(controllerSpeedPreferenceId))];
    }
    if ([30, 120, 600, 'all'].includes(candidate.range as MonitorRange)) result.views[id].range = candidate.range as MonitorRange;
    if ([1, 2, 3].includes(candidate.webColumns as number)) result.views[id].webColumns = candidate.webColumns as MonitorColumns;
  }
  return result;
}

/** A held display value keeps its original point and expires at the exact gap boundary. */
export function currentMonitorPoint(point: MonitorPoint | null | undefined, live: boolean, nowSeconds: number, gapSeconds: number): MonitorPoint | null {
  return !point || (live && nowSeconds - point.elapsedSeconds >= gapSeconds) ? null : point;
}
