import { CHART_INSET, type ChartViewport } from './chart-viewport';
import type { MonitorData, MonitorPoint } from './monitor';
import type { MonitorPlotGroup, MonitorScale } from './monitor-chart';

export type MonitorSnap = { key: string; sourceId: string; revision: string; metric: string; point: MonitorPoint };
export type MonitorHitScene = {
  key: string; sourceId: string; revision: string; height: number; min: number; max: number;
  series: { metric: string; scale: number; points: MonitorPoint[] }[];
};

/** Original vertices only. The native ready event admits this table alongside its bitmap. */
export function monitorHitScene(data: MonitorData, group: MonitorPlotGroup, scale: MonitorScale, height: number, key: string): MonitorHitScene {
  const coverage = data.plotViewport ?? data.domain;
  return { key, sourceId: data.sourceId, revision: data.revision ?? '', height, min: scale.min, max: scale.max,
    series: group.metrics.map(metric => ({ metric: metric.id, scale: metric.scale ?? 1,
      points: (data.series[metric.id] ?? []).filter(point => point.observationId && Number.isFinite(point.value) && Number.isFinite(point.elapsedSeconds) && point.elapsedSeconds >= Math.max(data.domain.start, coverage.start) && point.elapsedSeconds <= Math.min(data.domain.end, coverage.end)) })) };
}

/** Small physical hit targets make subpixel original extrema selectable without inventing values. */
export function hitMonitorScene(scene: MonitorHitScene | null, view: ChartViewport, width: number, x: number, y: number, previous: MonitorSnap | null): MonitorSnap | null {
  'worklet';
  if (!scene || width <= 0 || view.end <= view.start || scene.max <= scene.min || !Number.isFinite(x) || !Number.isFinite(y)) return null;
  const radius = 24, releaseRadius = 32, duration = view.end - view.start;
  const time = view.start + (x - CHART_INSET.left) / width * duration;
  const distance = (point: MonitorPoint, scale: number) => {
    'worklet';
    const px = CHART_INSET.left + (point.elapsedSeconds - view.start) / duration * width;
    const py = 8 + (scene.max - point.value * scale) / (scene.max - scene.min) * (scene.height - 34);
    return Math.hypot(px - x, py - y);
  };
  let best: MonitorSnap | null = null, bestDistance = radius;
  for (const series of scene.series) {
    const from = time - radius / width * duration, to = time + radius / width * duration;
    let low = 0, high = series.points.length;
    while (low < high) { const mid = (low + high) >>> 1; if (series.points[mid]!.elapsedSeconds < from) low = mid + 1; else high = mid; }
    for (let index = low; index < series.points.length; index++) {
      const point = series.points[index]!;
      if (point.elapsedSeconds > to) break;
      if (point.elapsedSeconds < view.start || point.elapsedSeconds > view.end) continue;
      const d = distance(point, series.scale);
      if (d < bestDistance || (d === bestDistance && best && (point.observationId ?? '') < (best.point.observationId ?? ''))) {
        bestDistance = d; best = { key: scene.key, sourceId: scene.sourceId, revision: scene.revision, metric: series.metric, point };
      }
    }
  }
  if (previous?.key === scene.key) {
    const series = scene.series.find(series => series.metric === previous.metric);
    if (series && previous.point.elapsedSeconds >= view.start && previous.point.elapsedSeconds <= view.end) {
      const heldDistance = distance(previous.point, series.scale);
      if (heldDistance <= releaseRadius && (!best || bestDistance + 6 >= heldDistance)) return previous;
    }
  }
  return best;
}

export type MonitorRasterReady = { key: string; sourceId?: string; acceptanceId?: string; viewId?: string; acceptanceGeneration?: number; width?: number; height?: number; displayScale?: number };
/** Logical content and actual layout must both match the native accepted bitmap. */
export function admitMonitorHitScene(expected: { key: string; width: number; height: number; displayScale: number; hits: MonitorHitScene }, ready: MonitorRasterReady): MonitorHitScene | null {
  // Android view bounds are rounded to physical pixels; Yoga reports logical fractions.
  const layoutTolerance = 0.5 / expected.displayScale + 1e-3;
  if (ready.key !== expected.key || ready.sourceId !== expected.hits.sourceId || !ready.acceptanceId ||
      ready.width === undefined || !Number.isFinite(ready.width) || Math.abs(ready.width - expected.width) > layoutTolerance ||
      ready.height === undefined || !Number.isFinite(ready.height) || Math.abs(ready.height - expected.height) > layoutTolerance || ready.displayScale !== expected.displayScale) return null;
  return { ...expected.hits, key: ready.acceptanceId };
}

export type MonitorHitPacket = { key: string; width: number; height: number; displayScale: number; hits: MonitorHitScene };
/** Match actual native acceptances, which can trail the latest requested packet. */
export class MonitorHitAdmission {
  private packets = new Map<string, MonitorHitPacket>();
  private binding = '';
  private viewId?: string;
  private generation = -1;
  request(packet: MonitorHitPacket, binding: string) {
    if (binding !== this.binding) { this.binding = binding; this.packets.clear(); }
    this.packets.delete(packet.key); this.packets.set(packet.key, packet);
    while (this.packets.size > 2) this.packets.delete(this.packets.keys().next().value!);
  }
  accept(ready: MonitorRasterReady): { generation: number; scene: MonitorHitScene | null } | null {
    if (!ready.viewId || (this.viewId && ready.viewId !== this.viewId) || !Number.isSafeInteger(ready.acceptanceGeneration) || ready.acceptanceGeneration! <= this.generation) return null;
    this.viewId = ready.viewId; this.generation = ready.acceptanceGeneration!;
    const packet = this.packets.get(ready.key);
    // An unbound accepted bitmap must disable snapping, never retain an older table.
    return { generation: this.generation, scene: packet ? admitMonitorHitScene(packet, ready) : null };
  }
}
