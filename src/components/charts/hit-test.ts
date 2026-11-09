import type { MetricId } from '../../../modules/power-log';
import type { ChartSeries } from '../../features/charts/chart-feed-model';
import { CHART_INSET, type ChartViewport } from './chart-viewport';

export type HitPoint = { elapsedSeconds: number; value: number; observationId: string; startsSegment?: boolean };
export type ChartSnap = { key: string; sourceId: string; revision: string; metric: string; point: HitPoint };
export type HitScene = {
  key: string;
  sourceId: string;
  revision: string;
  height: number;
  min: number;
  max: number;
  series: { metric: string; scale: number; offset?: number; points: HitPoint[] }[];
};

/** Original vertices only. The native ready event admits this table alongside its bitmap. */
export function buildHitScene(input: {
  key: string;
  sourceId: string;
  revision: string;
  height: number;
  scale: { min: number; max: number };
  metrics: readonly { id: MetricId; factor: number; offset: number }[];
  series: Readonly<Partial<Record<MetricId, ChartSeries>>>;
}): HitScene {
  return {
    key: input.key,
    sourceId: input.sourceId,
    revision: input.revision,
    height: input.height,
    min: input.scale.min,
    max: input.scale.max,
    series: input.metrics.map(metric => {
      const drawn = input.series[metric.id];
      const points: HitPoint[] = [];
      if (drawn)
        for (let index = 0; index < drawn.atS.length; index++) {
          const elapsedSeconds = drawn.atS[index]!,
            value = drawn.value[index]!;
          if (!Number.isFinite(value)) continue;
          points.push({
            elapsedSeconds,
            value,
            observationId: String(drawn.atUs[index]),
            ...(drawn.starts[index] === 1 ? { startsSegment: true } : {}),
          });
        }
      return { metric: metric.id, scale: metric.factor, offset: metric.offset, points };
    }),
  };
}

/** Every lane follows the UI cursor, including lanes that did not receive the touch. */
export function hitTargets(scene: HitScene | null, seconds: number | null, snap: ChartSnap | null) {
  'worklet';
  const targets: { key: string; id: string; atS: number; value: number }[] = [];
  if (!scene || seconds === null || !Number.isFinite(seconds)) return targets;
  for (const series of scene.series) {
    const points = series.points;
    let low = 0,
      high = points.length;
    while (low < high) {
      const mid = (low + high) >>> 1;
      if (points[mid]!.elapsedSeconds < seconds) low = mid + 1;
      else high = mid;
    }
    const next = points[low],
      previous = points[low - 1];
    let point: HitPoint | undefined;
    if (snap?.key === scene.key && snap.metric === series.metric && snap.point.elapsedSeconds === seconds) {
      point = snap.point;
    } else if (next?.elapsedSeconds === seconds) {
      point = next;
    } else if (previous && next && !next.startsSegment) {
      point = seconds - previous.elapsedSeconds <= next.elapsedSeconds - seconds ? previous : next;
    }
    if (point)
      targets.push({
        key: scene.key,
        id: series.metric,
        atS: point.elapsedSeconds,
        value: point.value * series.scale + (series.offset ?? 0),
      });
  }
  return targets;
}

/** Away from a line, inspect by time without extending a segment across a gap. */
function horizontalHit(scene: HitScene, view: ChartViewport, time: number, y: number): ChartSnap | null {
  'worklet';
  let best: ChartSnap | null = null,
    bestTime = Infinity,
    bestY = Infinity;
  for (const series of scene.series) {
    const points = series.points;
    let low = 0,
      high = points.length;
    while (low < high) {
      const mid = (low + high) >>> 1;
      if (points[mid]!.elapsedSeconds < time) low = mid + 1;
      else high = mid;
    }
    const next = points[low],
      previous = points[low - 1];
    if (next?.elapsedSeconds !== time && (!previous || !next || next.startsSegment)) continue;
    let start = next?.elapsedSeconds === time ? low : low - 1;
    while (start > 0 && points[start - 1]!.elapsedSeconds === points[start]!.elapsedSeconds) start--;
    for (let index = start; index < points.length; index++) {
      const point = points[index]!;
      if (point.elapsedSeconds > next!.elapsedSeconds) break;
      if (point.elapsedSeconds < view.start || point.elapsedSeconds > view.end) continue;
      const dt = Math.abs(point.elapsedSeconds - time);
      const py =
        8 +
        ((scene.max - (point.value * series.scale + (series.offset ?? 0))) / (scene.max - scene.min)) *
          (scene.height - 34);
      const dy = Math.abs(py - y);
      if (dt < bestTime || (dt === bestTime && dy < bestY)) {
        bestTime = dt;
        bestY = dy;
        best = { key: scene.key, sourceId: scene.sourceId, revision: scene.revision, metric: series.metric, point };
      }
    }
  }
  return best;
}

/** Small physical hit targets make subpixel original extrema selectable without inventing values. */
export function hitChart(
  scene: HitScene | null,
  view: ChartViewport,
  width: number,
  x: number,
  y: number,
  previous: ChartSnap | null,
): ChartSnap | null {
  'worklet';
  if (
    !scene ||
    width <= 0 ||
    view.end <= view.start ||
    scene.max <= scene.min ||
    !Number.isFinite(x) ||
    !Number.isFinite(y)
  )
    return null;
  const radius = 24,
    releaseRadius = 32,
    duration = view.end - view.start;
  const time = view.start + ((x - CHART_INSET.left) / width) * duration;
  const distance = (point: HitPoint, scale: number, offset: number) => {
    'worklet';
    const px = CHART_INSET.left + ((point.elapsedSeconds - view.start) / duration) * width;
    const py = 8 + ((scene.max - (point.value * scale + offset)) / (scene.max - scene.min)) * (scene.height - 34);
    return Math.hypot(px - x, py - y);
  };
  let best: ChartSnap | null = null,
    bestDistance = radius;
  for (const series of scene.series) {
    const from = time - (radius / width) * duration,
      to = time + (radius / width) * duration;
    let low = 0,
      high = series.points.length;
    while (low < high) {
      const mid = (low + high) >>> 1;
      if (series.points[mid]!.elapsedSeconds < from) low = mid + 1;
      else high = mid;
    }
    for (let index = low; index < series.points.length; index++) {
      const point = series.points[index]!;
      if (point.elapsedSeconds > to) break;
      if (point.elapsedSeconds < view.start || point.elapsedSeconds > view.end) continue;
      const d = distance(point, series.scale, series.offset ?? 0);
      if (
        d < bestDistance ||
        (d === bestDistance && best && (point.observationId ?? '') < (best.point.observationId ?? ''))
      ) {
        bestDistance = d;
        best = { key: scene.key, sourceId: scene.sourceId, revision: scene.revision, metric: series.metric, point };
      }
    }
  }
  if (previous?.key === scene.key) {
    const series = scene.series.find(series => series.metric === previous.metric);
    if (series && previous.point.elapsedSeconds >= view.start && previous.point.elapsedSeconds <= view.end) {
      const heldDistance = distance(previous.point, series.scale, series.offset ?? 0);
      if (heldDistance <= releaseRadius && (!best || bestDistance + 6 >= heldDistance)) return previous;
    }
  }
  return best ?? horizontalHit(scene, view, time, y);
}

export type RasterReady = {
  key: string;
  sourceId?: string;
  acceptanceId?: string;
  viewId?: string;
  acceptanceGeneration?: number;
  width?: number;
  height?: number;
  displayScale?: number;
};
/** Logical content and actual layout must both match the native accepted bitmap. */
export function admitHitScene(
  expected: { key: string; width: number; height: number; displayScale: number; hits: HitScene },
  ready: RasterReady,
): HitScene | null {
  // Android view bounds are rounded to physical pixels; Yoga reports logical fractions.
  const layoutTolerance = 0.5 / expected.displayScale + 1e-3;
  if (
    ready.key !== expected.key ||
    ready.sourceId !== expected.hits.sourceId ||
    !ready.acceptanceId ||
    ready.width === undefined ||
    !Number.isFinite(ready.width) ||
    Math.abs(ready.width - expected.width) > layoutTolerance ||
    ready.height === undefined ||
    !Number.isFinite(ready.height) ||
    Math.abs(ready.height - expected.height) > layoutTolerance ||
    ready.displayScale !== expected.displayScale
  )
    return null;
  return { ...expected.hits, key: ready.acceptanceId };
}

export type HitPacket = {
  key: string;
  width: number;
  height: number;
  displayScale: number;
  hits: HitScene;
};
/** Match actual native acceptances, which can trail the latest requested packet. */
export class HitAdmission {
  private packets = new Map<string, HitPacket>();
  private binding = '';
  private viewId?: string;
  private generation = -1;
  request(packet: HitPacket, binding: string) {
    if (binding !== this.binding) {
      this.binding = binding;
      this.packets.clear();
    }
    this.packets.delete(packet.key);
    this.packets.set(packet.key, packet);
    while (this.packets.size > 2) this.packets.delete(this.packets.keys().next().value!);
  }
  accept(ready: RasterReady): { generation: number; scene: HitScene | null } | null {
    if (
      !ready.viewId ||
      (this.viewId && ready.viewId !== this.viewId) ||
      !Number.isSafeInteger(ready.acceptanceGeneration) ||
      ready.acceptanceGeneration! <= this.generation
    )
      return null;
    this.viewId = ready.viewId;
    this.generation = ready.acceptanceGeneration!;
    const packet = this.packets.get(ready.key);
    // An unbound accepted bitmap must disable snapping, never retain an older table.
    return { generation: this.generation, scene: packet ? admitHitScene(packet, ready) : null };
  }
}
