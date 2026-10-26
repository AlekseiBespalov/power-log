import type { MonitorPlotGroup } from './monitor-chart';

export type MonitorChartBounds = { top: number; bottom: number };
export type MonitorChartVisibility = { onScreen: string[]; read: string[] };

/**
 * Bounds are screen coordinates; a chart without bounds is placed below the previous one at the estimated height.
 * Charts within half a screen above or below the screen are read as well as the charts on it.
 */
export function monitorChartVisibility(
  ids: readonly string[],
  bounds: ReadonlyMap<string, MonitorChartBounds>,
  screenHeight: number,
  estimatedHeight: number,
  top = 0,
): MonitorChartVisibility {
  const onScreen: string[] = [],
    read: string[] = [];
  let next = top;
  for (const id of ids) {
    const bound = bounds.get(id) ?? { top: next, bottom: next + estimatedHeight };
    next = bound.bottom;
    if (bound.bottom > 0 && bound.top < screenHeight) onScreen.push(id);
    if (bound.bottom > -screenHeight / 2 && bound.top < screenHeight * 1.5) read.push(id);
  }
  return { onScreen, read };
}

export function monitorGroupMetrics(groups: readonly MonitorPlotGroup[], ids: readonly string[]): string[] {
  return groups.filter(group => ids.includes(group.id)).flatMap(group => group.metrics.map(metric => metric.id));
}

// Card padding, title row and one readout row around the plot.
export const monitorChartEstimate = (plotHeight: number) => plotHeight + 120;
