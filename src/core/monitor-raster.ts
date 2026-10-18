import { inspectionRenderedSegment } from '../../shared/js';
import { metricValue, type MonitorData, type MonitorPoint } from './monitor';
import { CHART_INSET } from './chart-viewport';
import {
  monitorSelection,
  monitorTailSeconds,
  validMonitorPoint,
  type MonitorPlotGroup,
  type MonitorScale,
} from './monitor-chart';

export const MONITOR_AXIS = {
  textHeight: 14,
  inset: 4,
  horizontalPadding: 4,
  verticalPadding: 2,
  gridOffset: 2,
} as const;

export function monitorAxisFrame(axis: 'x' | 'y', position: number, textWidth: number, width: number, height: number) {
  const plotWidth = width - CHART_INSET.left - CHART_INSET.right;
  const labelWidth = Math.min(
    Math.ceil(textWidth) + MONITOR_AXIS.horizontalPadding * 2,
    Math.max(0, plotWidth - MONITOR_AXIS.inset * 2),
  );
  const labelHeight = MONITOR_AXIS.textHeight + MONITOR_AXIS.verticalPadding * 2;
  const left = CHART_INSET.left + MONITOR_AXIS.inset;
  const top = 8 + MONITOR_AXIS.inset;
  const right = width - CHART_INSET.right - MONITOR_AXIS.inset - labelWidth;
  const bottom = Math.max(top, height - 24 - MONITOR_AXIS.inset - labelHeight);
  return {
    x: axis === 'y' ? left : Math.max(left, Math.min(right, CHART_INSET.left + plotWidth * position - labelWidth / 2)),
    y:
      axis === 'y'
        ? Math.min(bottom, Math.max(top, position + MONITOR_AXIS.gridOffset))
        : height - MONITOR_AXIS.inset - labelHeight,
    width: labelWidth,
    height: labelHeight,
  };
}

export function monitorRasterScene(data: MonitorData, group: MonitorPlotGroup, scale: MonitorScale, laneCount: number) {
  const series = group.metrics.map(metric => {
    let previous: MonitorPoint | null = null;
    const points: [number, number, boolean][] = [];
    for (const point of data.series[metric.id] ?? []) {
      const value = metricValue(metric, point.value);
      if (!validMonitorPoint(point) || !Number.isFinite(value)) {
        previous = null;
        continue;
      }
      const starts =
        !previous ||
        inspectionRenderedSegment(
          metric.id,
          previous.elapsedSeconds,
          previous.value,
          point.elapsedSeconds,
          point.value,
          point.startsSegment ?? null,
        );
      points.push([point.elapsedSeconds, value, starts]);
      previous = point;
    }
    return { id: metric.id, color: metric.color, step: metric.kind === 'step', points };
  });
  return {
    ...monitorRasterMetadata(data, group, scale, laneCount),
    series,
  };
}

export function monitorRasterMetadata(
  data: MonitorData,
  group: MonitorPlotGroup,
  scale: MonitorScale,
  laneCount: number,
) {
  const view = data.plotViewport ?? data.domain;
  return {
    key: JSON.stringify([
      data.sourceId,
      data.revision,
      data.plotGeneration,
      view.start,
      view.end,
      group.id,
      scale.min,
      scale.max,
      scale.decimals,
      laneCount,
      group.metrics.map(metric => [metric.id, metric.kind, metric.color, metric.scale ?? 1, metric.gapSeconds]),
    ]),
    sourceId: data.sourceId,
    revision: data.revision,
    start: view.start,
    end: view.end,
    ...scale,
    laneCount,
  };
}

export function monitorRasterSelectionPoints(
  data: MonitorData,
  group: MonitorPlotGroup,
  cursor: number | null,
  reference: number | null,
) {
  const points: { id: string; seconds: number; value: number }[] = [],
    references: typeof points = [];
  for (const metric of group.metrics) {
    const selected = monitorSelection(data, metric, cursor);
    if (selected)
      points.push({ id: metric.id, seconds: selected.elapsedSeconds, value: metricValue(metric, selected.value) });
    if (metric.id === 'batteryVoltageV' || metric.id === 'throttleVoltageV') {
      const a = monitorSelection(data, metric, reference, 'reference');
      if (a) references.push({ id: metric.id, seconds: a.elapsedSeconds, value: metricValue(metric, a.value) });
    }
  }
  return {
    sourceId: data.sourceId,
    cursorPending: data.selectionPending ?? false,
    cursorSeconds: cursor,
    referenceSeconds: reference,
    points,
    references,
  };
}

export function monitorRasterSelection(
  data: MonitorData,
  group: MonitorPlotGroup,
  cursor: number | null,
  reference: number | null,
  tailTime?: number,
) {
  const tails: { id: string; start: number; end: number; value: number }[] = [];
  for (const metric of group.metrics) {
    const series = data.series[metric.id] ?? [],
      last = series[series.length - 1];
    const end = monitorTailSeconds(
      last,
      tailTime,
      metric,
      data.liveAcquiredAt?.[metric.id],
      data.monotonicAt,
      data.latest?.[metric.id],
      data.mapMonotonicSeconds,
    );
    if (end !== null && validMonitorPoint(last))
      tails.push({ id: metric.id, start: last.elapsedSeconds, end, value: metricValue(metric, last.value) });
  }
  return { ...monitorRasterSelectionPoints(data, group, cursor, reference), revision: data.revision, tails };
}
