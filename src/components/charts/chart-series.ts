import type { MetricId, MetricRangeStatistics, Original } from '../../../modules/power-log';
import type { ChartSeries } from '../../features/charts/chart-feed-model';
import type { ChartMetric } from './chart-groups';
import type { ChartViewport } from './chart-viewport';

export type SeriesMap = Readonly<Partial<Record<MetricId, ChartSeries>>>;
export type StatisticsMap = Readonly<Partial<Record<MetricId, MetricRangeStatistics>>>;

export interface ChartPoint {
  atS: number;
  value: number;
  utcUs: number | null;
}

export interface ChartScale {
  min: number;
  max: number;
  decimals: number;
}

export function originalPoint(original: Original | null | undefined): ChartPoint | null {
  return original ? { atS: original.elapsedUs / 1e6, value: original.value, utcUs: original.utcUs } : null;
}

export function hasSamples(metrics: readonly ChartMetric[], series: SeriesMap): boolean {
  return metrics.some(metric => {
    const drawn = series[metric.id];
    return !!drawn && drawn.value.some(value => Number.isFinite(metric.display(value)));
  });
}

export function chartScale(
  metrics: readonly ChartMetric[],
  series: SeriesMap,
  statistics: StatisticsMap | undefined,
): ChartScale {
  let min = Infinity;
  let max = -Infinity;
  let decimals = 0;
  const include = (metric: ChartMetric, value: number) => {
    const shown = metric.display(value);
    if (!Number.isFinite(shown)) return;
    min = Math.min(min, shown);
    max = Math.max(max, shown);
  };
  for (const metric of metrics) {
    decimals = Math.max(decimals, metric.decimals);
    // Statistics may predate this drawing; neither may hide the other's extrema while its query finishes.
    for (const value of series[metric.id]?.value ?? []) include(metric, value);
    const extrema = statistics?.[metric.id];
    for (const original of [extrema?.min, extrema?.max]) if (original) include(metric, original.value);
    if (metric.zero) {
      min = Math.min(min, 0);
      max = Math.max(max, 0);
    }
  }
  if (!Number.isFinite(min) || !Number.isFinite(max)) return { min: 0, max: 1, decimals };
  const resolution = 10 ** -decimals;
  const padding = Math.max(resolution, (max - min) * 0.08);
  const anchored = metrics.some(metric => metric.zero);
  return {
    min: anchored && min === 0 ? 0 : min - padding,
    max: anchored && max === 0 && min < 0 ? 0 : max + padding,
    decimals,
  };
}

export function lineGeometry(
  series: ChartSeries | undefined,
  metric: ChartMetric,
  x: (seconds: number) => number,
  y: (displayValue: number) => number,
): { path: string; isolated: ChartPoint[] } {
  let path = '';
  let open = false;
  let runStart: ChartPoint | null = null;
  let runLength = 0;
  const isolated: ChartPoint[] = [];
  const endRun = () => {
    if (runStart && runLength === 1) isolated.push(runStart);
    runStart = null;
    runLength = 0;
  };
  const count = series?.atS.length ?? 0;
  for (let index = 0; index < count; index++) {
    const atS = series!.atS[index]!,
      value = series!.value[index]!;
    const px = x(atS),
      py = y(metric.display(value));
    if (!Number.isFinite(px) || !Number.isFinite(py)) {
      endRun();
      open = false;
      continue;
    }
    const starts = !open || series!.starts[index] === 1;
    if (starts) {
      endRun();
      runStart = { atS, value, utcUs: null };
    }
    path += starts
      ? ` M${px.toFixed(2)},${py.toFixed(2)}`
      : metric.step
        ? ` H${px.toFixed(2)} V${py.toFixed(2)}`
        : ` L${px.toFixed(2)},${py.toFixed(2)}`;
    runLength++;
    open = true;
  }
  endRun();
  return { path: path.trim(), isolated };
}

export function seriesFrame(metrics: readonly ChartMetric[], series: SeriesMap): ChartViewport {
  let start = Infinity,
    end = -Infinity;
  for (const metric of metrics) {
    const drawn = series[metric.id];
    if (!drawn?.atS.length) continue;
    start = Math.min(start, drawn.atS[0]!);
    end = Math.max(end, drawn.atS[drawn.atS.length - 1]!);
  }
  if (!Number.isFinite(start)) return { start: 0, end: 1 };
  return { start, end: Math.max(end, start + 1e-6) };
}

export function latestPoint(series: ChartSeries | undefined, view?: ChartViewport): ChartPoint | null {
  for (let index = (series?.atS.length ?? 0) - 1; index >= 0; index--) {
    const atS = series!.atS[index]!,
      value = series!.value[index]!;
    if (Number.isFinite(value) && (!view || (atS >= view.start && atS <= view.end))) return { atS, value, utcUs: null };
  }
  return null;
}

export function stepCursor(
  metrics: readonly ChartMetric[],
  series: SeriesMap,
  domain: ChartViewport,
  cursor: number | null,
  direction: -1 | 1,
  view: ChartViewport,
): number | null {
  let result: number | null = null;
  const origin = cursor ?? (direction === 1 ? view.start : view.end);
  for (const metric of metrics) {
    const drawn = series[metric.id];
    for (let index = 0; index < (drawn?.atS.length ?? 0); index++) {
      const atS = drawn!.atS[index]!;
      if (!Number.isFinite(drawn!.value[index]!) || atS < domain.start || atS > domain.end) continue;
      const eligible =
        cursor === null
          ? direction === 1
            ? atS >= origin
            : atS <= origin
          : direction === 1
            ? atS > origin
            : atS < origin;
      if (eligible && (result === null || (direction === 1 ? atS < result : atS > result))) result = atS;
    }
  }
  return result ?? cursor;
}
