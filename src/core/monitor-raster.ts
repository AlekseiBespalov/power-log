import { metricValue, type MonitorData, type MonitorPoint } from './monitor';
import { monitorSelection, monitorTailSeconds, validMonitorPoint, type MonitorPlotGroup, type MonitorScale } from './monitor-chart';

export function monitorRasterScene(data: MonitorData, group: MonitorPlotGroup, scale: MonitorScale, laneCount: number) {
  const view = data.plotViewport ?? data.domain;
  const series = group.metrics.map(metric => {
    let previous: MonitorPoint | null = null;
    const points: [number, number, boolean][] = [];
    for (const point of data.series[metric.id] ?? []) {
      const value = metricValue(metric, point.value);
      if (!validMonitorPoint(point) || !Number.isFinite(value)) { previous = null; continue; }
      const starts = !previous || point.elapsedSeconds <= previous.elapsedSeconds || (point.startsSegment ?? point.elapsedSeconds - previous.elapsedSeconds >= metric.gapSeconds);
      points.push([point.elapsedSeconds, value, starts]); previous = point;
    }
    return { id: metric.id, color: metric.color, step: metric.kind === 'step', points };
  });
  return { key: JSON.stringify([data.sourceId, data.revision, data.plotGeneration, view.start, view.end, group.id, scale.min, scale.max, scale.decimals, laneCount, group.metrics.map(metric => [metric.id, metric.kind, metric.color, metric.scale ?? 1, metric.gapSeconds])]),
    sourceId: data.sourceId, start: view.start, end: view.end, ...scale, laneCount, series };
}

export function monitorRasterSelection(data: MonitorData, group: MonitorPlotGroup, cursor: number | null, reference: number | null, tailTime?: number) {
  const points: { id: string; seconds: number; value: number }[] = [], references: typeof points = [];
  const tails: { id: string; start: number; end: number; value: number }[] = [];
  for (const metric of group.metrics) {
    const selected = monitorSelection(data, metric, cursor);
    if (selected) points.push({ id: metric.id, seconds: selected.elapsedSeconds, value: metricValue(metric, selected.value) });
    if (metric.id === 'batteryVoltageV' || metric.id === 'throttleVoltageV') {
      const a = monitorSelection(data, metric, reference, 'reference');
      if (a) references.push({ id: metric.id, seconds: a.elapsedSeconds, value: metricValue(metric, a.value) });
    }
    const series = data.series[metric.id] ?? [], last = series[series.length - 1];
    const end = monitorTailSeconds(last, tailTime, metric.gapSeconds);
    if (end !== null && validMonitorPoint(last)) tails.push({ id: metric.id, start: last.elapsedSeconds, end, value: metricValue(metric, last.value) });
  }
  return { sourceId: data.sourceId, cursorPending: data.selectionPending ?? false, cursorSeconds: cursor, referenceSeconds: reference, points, references, tails };
}
