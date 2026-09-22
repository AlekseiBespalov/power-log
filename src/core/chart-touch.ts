import { zoomChartViewport, type ChartViewport } from './chart-viewport';

export type ChartTouch = { id: number; x: number; y: number };
export type ChartTransform = {
  ids: readonly [number, number];
  distance: number;
  fraction: number;
  view: ChartViewport;
  domain: ChartViewport;
  left: number;
  width: number;
};

export function beginChartTransform(touches: readonly ChartTouch[], view: ChartViewport, domain: ChartViewport, left: number, width: number): ChartTransform | null {
  'worklet';
  if (touches.length !== 2 || !Number.isFinite(left) || !Number.isFinite(width) || width <= 0) return null;
  const [a, b] = touches as readonly [ChartTouch, ChartTouch];
  const distance = Math.hypot(a.x - b.x, a.y - b.y);
  const fraction = ((a.x + b.x) / 2 - left) / width;
  if (a.id === b.id || !Number.isFinite(distance) || !Number.isFinite(fraction) || distance < 1) return null;
  return { ids: [a.id, b.id], distance, fraction, view: { ...view }, domain: { ...domain }, left, width };
}

export function moveChartTransform(start: ChartTransform, touches: readonly ChartTouch[]): ChartViewport | null {
  'worklet';
  if (touches.length !== 2) return null;
  const a = touches.find(touch => touch.id === start.ids[0]);
  const b = touches.find(touch => touch.id === start.ids[1]);
  if (!a || !b) return null;
  const distance = Math.hypot(a.x - b.x, a.y - b.y);
  const fraction = ((a.x + b.x) / 2 - start.left) / start.width;
  if (!Number.isFinite(distance) || !Number.isFinite(fraction) || distance < 1) return null;
  return zoomChartViewport(start.view, start.domain, distance / start.distance, start.fraction, fraction);
}
