export interface ChartViewport { start: number; end: number }
/** Axis numbers sit inside the plot, so both insets stay small and equal. */
export const CHART_INSET = { left: 8, right: 8 } as const;
type Timed = { elapsedSeconds: number };
const clamp = (value: number, low: number, high: number) => { 'worklet'; return Math.min(high, Math.max(low, value)); };

export function clampChartViewport(view: ChartViewport, domain: ChartViewport): ChartViewport {
  'worklet';
  if (![view.start, view.end].every(Number.isFinite) || view.end <= view.start) return { ...domain };
  const span = clamp(view.end - view.start, Math.min(1, domain.end - domain.start), domain.end - domain.start);
  const start = clamp(view.start, domain.start, domain.end - span);
  return { start, end: start + span };
}

/** Keep the original time under the pinch focal point, including centroid movement. */
export function zoomChartViewport(view: ChartViewport, domain: ChartViewport, scale: number, initialFraction = 0.5, currentFraction = initialFraction): ChartViewport {
  'worklet';
  if (!Number.isFinite(scale) || scale <= 0) return clampChartViewport(view, domain);
  const span = clamp((view.end - view.start) / scale, Math.min(1, domain.end - domain.start), domain.end - domain.start);
  const anchor = view.start + clamp(initialFraction, 0, 1) * (view.end - view.start);
  const start = anchor - clamp(currentFraction, 0, 1) * span;
  return clampChartViewport({ start, end: start + span }, domain);
}

export function panChartViewport(view: ChartViewport, domain: ChartViewport, fraction: number): ChartViewport {
  'worklet';
  if (!Number.isFinite(fraction)) return clampChartViewport(view, domain);
  const shift = fraction * (view.end - view.start);
  return clampChartViewport({ start: view.start + shift, end: view.end + shift }, domain);
}

export function isChartZoomed(view: ChartViewport, domain: ChartViewport): boolean {
  return view.end - view.start < domain.end - domain.start - 0.000001;
}

/** Binary search retains one neighbour on either side for correctly clipped line segments. */
export function chartVisibleRange(samples: readonly Timed[], view: ChartViewport): { start: number; end: number } {
  const lower = (time: number) => {
    let low = 0; let high = samples.length;
    while (low < high) { const middle = (low + high) >>> 1; if (samples[middle]!.elapsedSeconds < time) low = middle + 1; else high = middle; }
    return low;
  };
  return { start: Math.max(0, lower(view.start) - 1), end: Math.min(samples.length, lower(view.end) + 1) };
}
