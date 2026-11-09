export interface ChartViewport {
  start: number;
  end: number;
}
/** Axis numbers sit inside the plot, so both insets stay small and equal. */
export const CHART_INSET = { left: 8, right: 8 } as const;
const clamp = (value: number, low: number, high: number) => {
  'worklet';
  return Math.min(high, Math.max(low, value));
};

export function clampChartViewport(view: ChartViewport, domain: ChartViewport): ChartViewport {
  'worklet';
  if (![view.start, view.end].every(Number.isFinite) || view.end <= view.start) return { ...domain };
  const span = clamp(view.end - view.start, Math.min(1, domain.end - domain.start), domain.end - domain.start);
  const start = clamp(view.start, domain.start, domain.end - span);
  return { start, end: start + span };
}

/** Keep the original time under the pinch focal point, including centroid movement. */
export function zoomChartViewport(
  view: ChartViewport,
  domain: ChartViewport,
  scale: number,
  initialFraction = 0.5,
  currentFraction = initialFraction,
): ChartViewport {
  'worklet';
  if (!Number.isFinite(scale) || scale <= 0) return clampChartViewport(view, domain);
  const span = clamp(
    (view.end - view.start) / scale,
    Math.min(1, domain.end - domain.start),
    domain.end - domain.start,
  );
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

export function viewportTransform(geometry: ChartViewport, view: ChartViewport, left: number, width: number) {
  const duration = Math.max(1e-9, view.end - view.start);
  const scale = Math.max(1e-9, geometry.end - geometry.start) / duration;
  return { scale, offset: left * (1 - scale) + ((geometry.start - view.start) / duration) * width };
}
