import type { ChartGroup } from './chart-groups';

export type ChartBounds = { top: number; bottom: number };
export type ChartVisibility = { onScreen: string[]; read: string[] };

/**
 * Bounds are screen coordinates; a chart without bounds is placed below the previous one at the estimated height.
 * Charts within half a screen above or below the screen are read as well as the charts on it.
 */
export function chartVisibility(
  ids: readonly string[],
  bounds: ReadonlyMap<string, ChartBounds>,
  screenHeight: number,
  estimatedHeight: number,
  top = 0,
): ChartVisibility {
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

export function groupMetrics(groups: readonly ChartGroup[], ids: readonly string[]): string[] {
  return groups.filter(group => ids.includes(group.id)).flatMap(group => group.metrics.map(metric => metric.id));
}

// Card padding, title row and one readout row around the plot.
export const chartEstimate = (plotHeight: number) => plotHeight + 120;
