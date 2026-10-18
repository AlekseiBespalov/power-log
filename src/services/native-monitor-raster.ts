import {
  monitorRasterMetadata,
  monitorRasterScene,
  monitorRasterSelection,
  monitorRasterSelectionPoints,
} from '../core/monitor-raster';
import { metricValue } from '../core/monitor';

export function nativeMonitorRasterScene(...args: Parameters<typeof monitorRasterScene>) {
  const [data, group, scale, laneCount] = args;
  return {
    ...monitorRasterMetadata(data, group, scale, laneCount),
    series: group.metrics.map(metric => ({
      id: metric.id,
      color: metric.color,
      step: metric.kind === 'step',
      points: (data.series[metric.id] ?? []).map<[number, number, boolean]>(point => [
        point.elapsedSeconds,
        metricValue(metric, point.value),
        point.startsSegment ?? true,
      ]),
    })),
  };
}

export function nativeMonitorRasterSelection(platform: string, ...args: Parameters<typeof monitorRasterSelection>) {
  const [data, group, cursor, reference] = args;
  if (platform !== 'ios') return monitorRasterSelection(...args);
  const tailTime = args[4];
  return {
    ...monitorRasterSelectionPoints(data, group, cursor, reference),
    tailCandidates: group.metrics.flatMap(metric => {
      const point = data.series[metric.id]?.at(-1);
      if (!point || !point.observationId || tailTime === undefined || data.monotonicAt === undefined) return [];
      const acquired = data.liveAcquiredAt?.[metric.id];
      return [
        {
          id: metric.id,
          observationId: point.observationId,
          evidence: data.latest?.[metric.id]?.observationId ?? '',
          start: point.elapsedSeconds,
          value: point.value * (metric.scale ?? 1),
          acquired: acquired == null ? null : data.mapMonotonicSeconds ? data.mapMonotonicSeconds(acquired) : acquired,
          now: data.monotonicAt,
          elapsed: tailTime,
        },
      ];
    }),
  };
}
