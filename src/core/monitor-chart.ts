import type { ChartViewport } from './chart-viewport';
import { metricById, metricValue, type MonitorData, type MonitorMetric, type MonitorPoint, type SpeedUnit } from './monitor';

export interface MonitorPlotGroup { id: string; label: string; unit: string; metrics: MonitorMetric[] }
export interface MonitorScale { min: number; max: number; decimals: number }
const timeRoundoffSeconds = 0.000001;

/** Move a retained path into the current viewport without rebuilding its vertices. */
export function monitorViewportTransform(geometry: ChartViewport, view: ChartViewport, left: number, width: number) {
  const duration = Math.max(1e-9, view.end - view.start);
  const scale = Math.max(1e-9, geometry.end - geometry.start) / duration;
  return { scale, offset: left * (1 - scale) + (geometry.start - view.start) / duration * width };
}

// Matching units alone are insufficient: crank and motor RPM, for example, have unrelated scales.
const overlays: Record<string, readonly [string, string]> = {
  humanPowerW: ['power', 'Power'], motorInputPowerW: ['power', 'Power'],
  motorTempC: ['temperature', 'Temperature'], controllerTempC: ['temperature', 'Temperature'],
  batteryCurrentA: ['current', 'Current'], motorCurrentA: ['current', 'Current'],
  speedMps: ['speed', 'Speed'], controllerSpeedMps: ['speed', 'Speed'], healthSpeedMps: ['speed', 'Speed'],
  activeEnergyKcal: ['energy', 'Energy'], basalEnergyKcal: ['energy', 'Energy'],
  horizontalAccuracyM: ['accuracy', 'GPS accuracy'], verticalAccuracyM: ['accuracy', 'GPS accuracy'],
};

export function groupMonitorMetrics(ids: readonly string[], speedUnit: SpeedUnit = 'km/h'): MonitorPlotGroup[] {
  const groups = new Map<string, MonitorPlotGroup>();
  for (const id of new Set(ids)) {
    const metric = metricById(id, speedUnit);
    if (!metric) continue;
    const [key, label] = overlays[id] ?? [id, metric.label];
    const group = groups.get(key);
    if (group) group.metrics.push(metric);
    else groups.set(key, { id: key, label, unit: metric.unit, metrics: [metric] });
  }
  return [...groups.values()];
}

export function reorderMonitorCharts(ids: readonly string[], from: string, to: string): string[] {
  const groups = groupMonitorMetrics(ids);
  const start = groups.findIndex(group => group.id === from), end = groups.findIndex(group => group.id === to);
  if (start < 0 || end < 0 || start === end) return [...ids];
  groups.splice(end, 0, groups.splice(start, 1)[0]!);
  return groups.flatMap(group => group.metrics.map(metric => metric.id));
}

export function validMonitorPoint(point: MonitorPoint | null | undefined): point is MonitorPoint {
  return !!point && Number.isFinite(point.elapsedSeconds) && Number.isFinite(point.value);
}

export function monitorScale(metrics: readonly MonitorMetric[], data: Pick<MonitorData, 'series' | 'statistics'>): MonitorScale {
  let min = Infinity; let max = -Infinity; let decimals = 0;
  for (const metric of metrics) {
    decimals = Math.max(decimals, metric.decimals);
    const statistics = data.statistics[metric.id];
    // Retained statistics may predate this geometry. Neither source may hide
    // the other's extrema while its independent query finishes.
    const points = data.series[metric.id] ?? [];
    const extrema = statistics && (statistics.count ?? 0) > 0 ? [statistics.min, statistics.max] : [];
    for (const point of [...points, ...extrema]) {
      if (!validMonitorPoint(point)) continue;
      const value = metricValue(metric, point.value);
      if (!Number.isFinite(value)) continue;
      min = Math.min(min, value); max = Math.max(max, value);
    }
    if (metric.zero) { min = Math.min(min, 0); max = Math.max(max, 0); }
  }
  if (!Number.isFinite(min) || !Number.isFinite(max)) return { min: 0, max: 1, decimals };
  const resolution = 10 ** -decimals;
  const span = max - min;
  const padding = Math.max(resolution, span * 0.08);
  const anchored = metrics.some(metric => metric.zero);
  return {
    min: anchored && min === 0 ? 0 : min - padding,
    max: anchored && max === 0 && min < 0 ? 0 : max + padding,
    decimals,
  };
}

/** Explicit native segment markers describe original samples, not coarse vertex spacing. */
function beginsSegment(point: MonitorPoint, previous: MonitorPoint | null, gapSeconds: number): boolean {
  return !previous || point.elapsedSeconds <= previous.elapsedSeconds ||
    (point.startsSegment ?? point.elapsedSeconds - previous.elapsedSeconds >= gapSeconds);
}

export function monitorLineGeometry(
  points: readonly MonitorPoint[], metric: MonitorMetric,
  x: (seconds: number) => number, y: (displayValue: number) => number,
): { path: string; isolated: MonitorPoint[] } {
  let path = ''; let previous: MonitorPoint | null = null;
  let runStart: MonitorPoint | null = null; let runLength = 0;
  const isolated: MonitorPoint[] = [];
  const endRun = () => { if (runStart && runLength === 1) isolated.push(runStart); runStart = null; runLength = 0; };
  for (const point of points) {
    const px = validMonitorPoint(point) ? x(point.elapsedSeconds) : NaN;
    const py = validMonitorPoint(point) ? y(metricValue(metric, point.value)) : NaN;
    if (!Number.isFinite(px) || !Number.isFinite(py)) { endRun(); previous = null; continue; }
    const starts = beginsSegment(point, previous, metric.gapSeconds) || metric.semantics === 'circular' && previous !== null && Math.abs(point.value - previous.value) > 180;
    if (starts) { endRun(); runStart = point; }
    path += starts ? ` M${px.toFixed(2)},${py.toFixed(2)}`
      : metric.kind === 'step' ? ` H${px.toFixed(2)} V${py.toFixed(2)}` : ` L${px.toFixed(2)},${py.toFixed(2)}`;
    runLength++; previous = point;
  }
  endRun();
  return { path: path.trim(), isolated };
}

function lowerBound(points: readonly MonitorPoint[], seconds: number): number {
  let lo = 0; let hi = points.length;
  while (lo < hi) { const mid = (lo + hi) >>> 1; if (points[mid]!.elapsedSeconds < seconds) lo = mid + 1; else hi = mid; }
  return lo;
}

/** Immediate fallback is a real vertex. It never interpolates, extends a tail, or crosses a break. */
export function nearestMonitorPoint(points: readonly MonitorPoint[], seconds: number, gapSeconds: number): MonitorPoint | null {
  if (!Number.isFinite(seconds) || !points.length) return null;
  const index = lowerBound(points, seconds);
  const after = points[index]; const before = points[index - 1];
  if (validMonitorPoint(after) && Math.abs(after.elapsedSeconds - seconds) <= timeRoundoffSeconds) return after;
  if (validMonitorPoint(before) && Math.abs(before.elapsedSeconds - seconds) <= timeRoundoffSeconds) return before;
  if (!validMonitorPoint(before) || !validMonitorPoint(after) || beginsSegment(after, before, gapSeconds)) return null;
  return seconds - before.elapsedSeconds <= after.elapsedSeconds - seconds ? before : after;
}

export function monitorSelection(data: MonitorData, metric: MonitorMetric, seconds: number | null, kind: 'selection' | 'reference' = 'selection'): MonitorPoint | null {
  if (seconds === null || !Number.isFinite(seconds) || seconds < data.domain.start - timeRoundoffSeconds || seconds > data.domain.end + timeRoundoffSeconds) return null;
  const inDomain = (point: MonitorPoint | null | undefined): point is MonitorPoint => validMonitorPoint(point) && point.elapsedSeconds >= data.domain.start && point.elapsedSeconds <= data.domain.end;
  const tag = kind === 'selection' ? data.selectionSeconds : data.referenceSeconds;
  const exact = data[kind];
  if ((tag === seconds || (kind === 'selection' && data.selectionPending)) && exact && Object.prototype.hasOwnProperty.call(exact, metric.id)) {
    const point = exact[metric.id];
    return inDomain(point) ? point : null;
  }
  const point = nearestMonitorPoint(data.series[metric.id] ?? [], seconds, metric.gapSeconds);
  return inDomain(point) ? point : null;
}

/** Display-only endpoint; never used for selection, summaries or capture. */
export function monitorTailSeconds(point: MonitorPoint | null | undefined, nowSeconds: number | undefined, gapSeconds: number): number | null {
  if (!validMonitorPoint(point) || nowSeconds === undefined || !Number.isFinite(nowSeconds)) return null;
  const age = nowSeconds - point.elapsedSeconds;
  return age > 0 && age < Math.min(6, gapSeconds) ? nowSeconds : null;
}

/** Live time may advance through a gap; this changes only the axis, not the sampled domain. */
export function monitorDisplayDomain(data: MonitorData, live = false, displayTimeSeconds?: number): ChartViewport {
  const now = displayTimeSeconds ?? data.nowSeconds;
  return live && now !== undefined && Number.isFinite(now)
    ? { start: data.domain.start, end: Math.max(data.domain.end, now) } : data.domain;
}

export function stepMonitorCursor(data: MonitorData, metrics: readonly MonitorMetric[], cursor: number | null, direction: -1 | 1, view: ChartViewport): number | null {
  let result: number | null = null;
  const origin = cursor ?? (direction === 1 ? view.start : view.end);
  for (const metric of metrics) for (const point of data.series[metric.id] ?? []) {
    if (!validMonitorPoint(point) || point.elapsedSeconds < data.domain.start || point.elapsedSeconds > data.domain.end) continue;
    const eligible = cursor === null
      ? direction === 1 ? point.elapsedSeconds >= origin : point.elapsedSeconds <= origin
      : direction === 1 ? point.elapsedSeconds > origin : point.elapsedSeconds < origin;
    if (eligible) {
      if (result === null || (direction === 1 ? point.elapsedSeconds < result : point.elapsedSeconds > result)) result = point.elapsedSeconds;
    }
  }
  return result ?? cursor;
}

export function monitorElapsed(seconds: number): string {
  if (!Number.isFinite(seconds)) return '—';
  const milliseconds = Math.round(Math.abs(seconds) * 1000);
  const whole = Math.floor(milliseconds / 1000); const hours = Math.floor(whole / 3600);
  return `${seconds < 0 ? '−' : ''}${hours ? `${hours}:` : ''}${String(Math.floor(whole / 60) % 60).padStart(2, '0')}:${String(whole % 60).padStart(2, '0')}.${String(milliseconds % 1000).padStart(3, '0')}`;
}
