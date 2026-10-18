import {
  AnalyticsItem,
  analyticsTimeUs,
  InspectionMetricPolicy,
  InspectionMetricQuery,
  InspectionPlotQuery,
  InspectionTargetInput,
  type AnalyticsOriginal,
  type AnalyticsStatistics,
} from '../../shared/js';
import type { MonitorPoint, MonitorStatistics } from './monitor';
import { isDistanceMetric } from './distance';
import { isConsumptionMetric } from './consumption';

export const inspectionPoint = (original: AnalyticsOriginal): MonitorPoint => ({
  observationId: original.observationId,
  timestamp: original.timestamp,
  elapsedSeconds: original.elapsedSeconds,
  value: original.value,
  ...(original.exactValue == null ? {} : { exactValue: original.exactValue }),
});
export function inspectionStatistics(value: AnalyticsStatistics): MonitorStatistics {
  return {
    count: Number(value.count),
    ...(value.min ? { min: inspectionPoint(value.min) } : {}),
    ...(value.max ? { max: inspectionPoint(value.max) } : {}),
    ...(value.sampleMean == null ? {} : { sampleMean: value.sampleMean }),
    ...(value.integral == null ? {} : { integral: value.integral }),
    ...(value.coveredSeconds == null ? {} : { coveredSeconds: value.coveredSeconds }),
  };
}
export function monitorInspectionItem(
  metric: string,
  point: MonitorPoint,
  continuity: {
    clockEpoch?: string;
    connectionEpoch?: string;
    interruptionIndex?: number;
    sequence?: number;
    producer?: string;
    accuracy?: number;
    kind?: string;
    declared?: string[];
  } = {},
): AnalyticsItem {
  const value = Number.isFinite(point.value) ? point.value : undefined;
  let kind = continuity.kind;
  if (kind === undefined) {
    // Rendered totals already carry their values; they cannot use a raw-measurement query policy.
    if (isDistanceMetric(metric)) kind = 'distance';
    else if (isConsumptionMetric(metric)) kind = 'consumption';
    else kind = new InspectionMetricPolicy(metric).channel;
  }
  return new AnalyticsItem(
    analyticsTimeUs(point.elapsedSeconds),
    point.elapsedSeconds,
    point.observationId ?? point.timestamp,
    point.observationId ?? point.timestamp,
    continuity.producer ?? 'controller',
    String(continuity.sequence ?? 0),
    kind,
    'controller',
    point.timestamp,
    [
      metric,
      ...(continuity.accuracy === undefined
        ? []
        : [metric === 'courseDegrees' ? 'courseAccuracyDegrees' : 'speedAccuracyMps']),
    ],
    [point.exactValue === undefined ? 'real64' : 'integer64', ...(continuity.accuracy === undefined ? [] : ['real64'])],
    [
      value === undefined ? 'NaN' : (point.exactValue ?? (Object.is(value, -0) ? '-0.0' : String(value))),
      ...(continuity.accuracy === undefined ? [] : [String(continuity.accuracy)]),
    ],
    null,
    null,
    continuity.clockEpoch ?? null,
    continuity.connectionEpoch ?? null,
    continuity.interruptionIndex ?? 0,
    null,
    false,
    false,
    null,
    true,
    null,
    continuity.declared ?? [metric],
  );
}
export function inspectMonitorItems(
  metric: string,
  items: AnalyticsItem[],
  seconds: number,
  end: number,
  lifecycle: AnalyticsItem[] = [],
  anchor?: string,
  trailing = false,
): MonitorPoint | null {
  const target = new InspectionTargetInput(
    analyticsTimeUs(seconds),
    anchor ?? null,
    anchor ? analyticsTimeUs(seconds) : null,
  );
  const query = new InspectionMetricQuery(
    metric,
    '0',
    analyticsTimeUs(Math.max(0, end)),
    analyticsTimeUs(Math.max(0, end)),
    lifecycle,
    target,
    null,
    trailing,
  );
  items.forEach(item => query.appendInspection(item, true));
  const result = query.result().selection;
  return result ? inspectionPoint(result) : null;
}
export function plotMonitorItems(
  metric: string,
  items: AnalyticsItem[],
  start: number,
  end: number,
  buckets: number,
): MonitorPoint[] {
  const query = new InspectionPlotQuery(
    metric,
    analyticsTimeUs(start),
    analyticsTimeUs(end),
    Math.min(512, Math.max(1, Math.floor(buckets))),
    16384,
  );
  const boundaries = monitorInterruptionBoundaries(items);
  items.forEach(item => query.append(item, true, boundaries.has(item.collectionUs)));
  let previous: number | undefined;
  return query.result().map(point => {
    const startsSegment = point.segment !== previous;
    previous = point.segment;
    return { ...inspectionPoint(point.original), startsSegment };
  });
}

export function monitorInterruptionBoundaries(items: AnalyticsItem[]): Set<string> {
  return new Set(
    items
      .filter(
        (item, index) =>
          index > 0 &&
          item.collectionUs === items[index - 1]!.collectionUs &&
          item.interruptionIndex !== items[index - 1]!.interruptionIndex,
      )
      .map(item => item.collectionUs),
  );
}
