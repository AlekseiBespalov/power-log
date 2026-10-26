import {
  analyticsTimeUs,
  inspectionHeldTail,
  inspectionRenderedSegment,
  inspectionRenderedOriginal,
  InspectionOriginalInput,
} from '../../shared/js';
import { monitorInspectionItem } from './monitor-inspection';
import type { ChartViewport } from './chart-viewport';
import {
  metricById,
  metricValue,
  type MonitorData,
  type MonitorDrawing,
  type MonitorMetric,
  type MonitorPoint,
  type SpeedUnit,
} from './monitor';

export interface MonitorPlotGroup {
  id: string;
  label: string;
  unit: string;
  metrics: MonitorMetric[];
}
export interface MonitorScale {
  min: number;
  max: number;
  decimals: number;
}
const timeRoundoffSeconds = 0.000001;

export function monitorViewportTransform(geometry: ChartViewport, view: ChartViewport, left: number, width: number) {
  const duration = Math.max(1e-9, view.end - view.start);
  const scale = Math.max(1e-9, geometry.end - geometry.start) / duration;
  return { scale, offset: left * (1 - scale) + ((geometry.start - view.start) / duration) * width };
}

// Matching units alone are insufficient: crank and motor RPM, for example, have unrelated scales.
const overlays: Record<string, readonly [string, string]> = {
  batteryWhPerKm: ['energy-per-distance', 'Consumption'],
  riderWhPerKm: ['energy-per-distance', 'Consumption'],
  humanPowerW: ['power', 'Power'],
  motorInputPowerW: ['power', 'Power'],
  motorTempC: ['temperature', 'Temperature'],
  controllerTempC: ['temperature', 'Temperature'],
  batteryCurrentA: ['current', 'Current'],
  motorCurrentA: ['current', 'Current'],
  speedMps: ['speed', 'Speed'],
  gpsDistanceMeters: ['distance-comparison', 'Distance'],
  controllerDistanceMeters: ['distance-comparison', 'Distance'],
  controllerEnergyWh: ['battery-energy', 'Energy used'],
  calculatedEnergyWh: ['battery-energy', 'Energy used'],
  controllerChargeAh: ['battery-charge', 'Charge used'],
  calculatedChargeAh: ['battery-charge', 'Charge used'],
  controllerSpeedMps: ['speed', 'Speed'],
  activeEnergyKcal: ['energy', 'Energy'],
  basalEnergyKcal: ['energy', 'Energy'],
  horizontalAccuracyM: ['accuracy', 'GPS accuracy'],
  verticalAccuracyM: ['accuracy', 'GPS accuracy'],
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
  const start = groups.findIndex(group => group.id === from),
    end = groups.findIndex(group => group.id === to);
  if (start < 0 || end < 0 || start === end) return [...ids];
  groups.splice(end, 0, groups.splice(start, 1)[0]!);
  return groups.flatMap(group => group.metrics.map(metric => metric.id));
}

export const isVoltageMetric = (id: string) => id === 'batteryVoltageV' || id === 'throttleVoltageV';

export function validMonitorPoint(point: MonitorPoint | null | undefined): point is MonitorPoint {
  return !!point && Number.isFinite(point.elapsedSeconds) && Number.isFinite(point.value);
}

export function reuseShallow<T extends object>(previous: T | undefined, next: T): T {
  return previous &&
    Object.keys(next).every(key => previous[key as keyof T] === next[key as keyof T]) &&
    Object.keys(previous).length === Object.keys(next).length
    ? previous
    : next;
}

/**
 * One chart's inputs. It draws every metric confirmed by the same plot snapshot as its current (else newest) drawing,
 * so a chart never mixes revisions, and takes its scene identity from the newest of those reads; every part that
 * equals `previous` keeps its identity.
 */
export function monitorChartData(
  data: MonitorData,
  metrics: readonly MonitorMetric[],
  previous?: MonitorData,
): MonitorData {
  const ids = metrics.map(metric => metric.id);
  let snapshot: MonitorDrawing | undefined;
  for (const id of ids) {
    const candidate = data.drawings?.[id];
    if (
      candidate &&
      (!snapshot ||
        (candidate.current && !snapshot.current) ||
        (candidate.current === snapshot.current && candidate.generation > snapshot.generation))
    )
      snapshot = candidate;
  }
  const member = (drawing: MonitorDrawing | undefined): drawing is MonitorDrawing =>
    !!drawing &&
    !!snapshot &&
    drawing.confirmed === snapshot.confirmed &&
    drawing.viewport.start === snapshot.viewport.start &&
    drawing.viewport.end === snapshot.viewport.end;
  const drawn = data.drawings ? ids.filter(id => member(data.drawings![id])) : ids;
  let drawing = snapshot;
  for (const id of drawn) {
    const candidate = data.drawings?.[id];
    if (candidate && drawing && candidate.generation > drawing.generation) drawing = candidate;
  }
  const pick = <T>(record: Record<string, T> | undefined, keys: readonly string[] = ids) => {
    if (!record) return undefined;
    const picked: Record<string, T> = {};
    for (const key of keys) if (Object.prototype.hasOwnProperty.call(record, key)) picked[key] = record[key]!;
    return picked;
  };
  const keep = <T extends object>(value: T | undefined, before: T | undefined) => value && reuseShallow(before, value);
  const states = ids.flatMap(id => data.statisticsStates?.[id] ?? []);
  return reuseShallow(previous, {
    sourceId: data.sourceId,
    startedAt: data.startedAt,
    domain: data.domain,
    revision: data.drawings ? drawing?.revision : data.revision,
    plotViewport: data.drawings ? drawing?.viewport : data.plotViewport,
    plotGeneration: data.drawings ? drawing?.generation : data.plotGeneration,
    mapMonotonicSeconds: data.mapMonotonicSeconds,
    liveAcquiredAt: keep(pick(data.liveAcquiredAt), previous?.liveAcquiredAt),
    series: keep(pick(data.series, drawn), previous?.series)!,
    drawings: keep(pick(data.drawings, drawn), previous?.drawings),
    statistics: keep(pick(data.statistics), previous?.statistics)!,
    statisticsViewport: data.statisticsStates ? states[0]?.viewport : data.statisticsViewport,
    statisticsPending: data.statisticsStates ? states.some(state => state.pending) : data.statisticsPending,
    latest: keep(pick(data.latest), previous?.latest),
    selection: keep(pick(data.selection), previous?.selection),
    reference: keep(pick(data.reference), previous?.reference),
    selectionSeconds: data.selectionSeconds,
    referenceSeconds: data.referenceSeconds,
    selectionPending: data.selectionPending,
  });
}

export function monitorScale(
  metrics: readonly MonitorMetric[],
  data: Pick<MonitorData, 'series' | 'statistics'>,
): MonitorScale {
  let min = Infinity;
  let max = -Infinity;
  let decimals = 0;
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
      min = Math.min(min, value);
      max = Math.max(max, value);
    }
    if (metric.zero) {
      min = Math.min(min, 0);
      max = Math.max(max, 0);
    }
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
function beginsSegment(point: MonitorPoint, previous: MonitorPoint | null, metric: string): boolean {
  return (
    !previous ||
    inspectionRenderedSegment(
      metric,
      previous.elapsedSeconds,
      previous.value,
      point.elapsedSeconds,
      point.value,
      point.startsSegment ?? null,
    )
  );
}

export function monitorLineGeometry(
  points: readonly MonitorPoint[],
  metric: MonitorMetric,
  x: (seconds: number) => number,
  y: (displayValue: number) => number,
): { path: string; isolated: MonitorPoint[] } {
  let path = '';
  let previous: MonitorPoint | null = null;
  let runStart: MonitorPoint | null = null;
  let runLength = 0;
  const isolated: MonitorPoint[] = [];
  const endRun = () => {
    if (runStart && runLength === 1) isolated.push(runStart);
    runStart = null;
    runLength = 0;
  };
  for (const point of points) {
    const px = validMonitorPoint(point) ? x(point.elapsedSeconds) : NaN;
    const py = validMonitorPoint(point) ? y(metricValue(metric, point.value)) : NaN;
    if (!Number.isFinite(px) || !Number.isFinite(py)) {
      endRun();
      previous = null;
      continue;
    }
    const starts = beginsSegment(point, previous, metric.id);
    if (starts) {
      endRun();
      runStart = point;
    }
    path += starts
      ? ` M${px.toFixed(2)},${py.toFixed(2)}`
      : metric.kind === 'step'
        ? ` H${px.toFixed(2)} V${py.toFixed(2)}`
        : ` L${px.toFixed(2)},${py.toFixed(2)}`;
    runLength++;
    previous = point;
  }
  endRun();
  return { path: path.trim(), isolated };
}

type RenderedVertex = { input: InspectionOriginalInput; segment: number };
type RenderedGroup = { time: bigint; points: MonitorPoint[]; prepared?: RenderedVertex[]; eligible?: boolean };
type RenderedIndex = { groups: RenderedGroup[]; originals: Map<string, MonitorPoint>; length: number };
const renderedIndexes = new WeakMap<readonly MonitorPoint[], Map<string, RenderedIndex>>();
function renderedIndex(points: readonly MonitorPoint[], metric: string): RenderedIndex {
  let metrics = renderedIndexes.get(points);
  if (!metrics) {
    metrics = new Map();
    renderedIndexes.set(points, metrics);
  }
  const cached = metrics.get(metric);
  if (cached?.length === points.length) return cached;
  const groupsByTime = new Map<bigint, RenderedGroup>();
  const originals = new Map<string, MonitorPoint>();
  for (const point of points) {
    if (!Number.isFinite(point.elapsedSeconds)) continue;
    const time = BigInt(analyticsTimeUs(point.elapsedSeconds));
    let group = groupsByTime.get(time);
    if (!group) {
      group = { time, points: [] };
      groupsByTime.set(time, group);
    }
    group.points.push(point);
    const identity = point.observationId ?? point.timestamp;
    if (!originals.has(identity)) originals.set(identity, point);
  }
  const groups = [...groupsByTime.values()].sort((a, b) => (a.time < b.time ? -1 : a.time > b.time ? 1 : 0));
  const index = { groups, originals, length: points.length };
  metrics.set(metric, index);
  return index;
}
function preparedRenderedGroup(group: RenderedGroup, metric: string): RenderedVertex[] {
  if (!group.prepared) {
    group.eligible = false;
    group.prepared = group.points.map(point => {
      const input = new InspectionOriginalInput(monitorInspectionItem(metric, point));
      if (input.plotValid(metric)) group.eligible = true;
      return { input, segment: point.startsSegment == null ? -1 : Number(point.startsSegment) };
    });
  }
  return group.prepared;
}

export function nearestMonitorPoint(
  points: readonly MonitorPoint[],
  seconds: number,
  metric: MonitorMetric,
): MonitorPoint | null {
  if (!Number.isFinite(seconds) || !points.length) return null;
  const index = renderedIndex(points, metric.id);
  const timeUs = analyticsTimeUs(seconds),
    time = BigInt(timeUs);
  let low = 0,
    high = index.groups.length;
  while (low < high) {
    const middle = (low + high) >>> 1;
    if (index.groups[middle]!.time < time) low = middle + 1;
    else high = middle;
  }
  const candidates: RenderedVertex[] = [];
  for (const direction of [-1, 1]) {
    for (
      let position = direction === -1 ? low - 1 : low;
      position >= 0 && position < index.groups.length;
      position += direction
    ) {
      const group = index.groups[position]!;
      const prepared = preparedRenderedGroup(group, metric.id);
      if (group.eligible) {
        candidates.push(...prepared);
        break;
      }
    }
  }
  const result = inspectionRenderedOriginal(
    metric.id,
    candidates.map(vertex => vertex.input),
    Int32Array.from(candidates.map(vertex => vertex.segment)),
    timeUs,
  );
  return result ? (index.originals.get(result.observationId) ?? null) : null;
}

export function monitorSelection(
  data: MonitorData,
  metric: MonitorMetric,
  seconds: number | null,
  kind: 'selection' | 'reference' = 'selection',
): MonitorPoint | null {
  if (
    seconds === null ||
    !Number.isFinite(seconds) ||
    seconds < data.domain.start - timeRoundoffSeconds ||
    seconds > data.domain.end + timeRoundoffSeconds
  )
    return null;
  const inDomain = (point: MonitorPoint | null | undefined): point is MonitorPoint =>
    validMonitorPoint(point) && point.elapsedSeconds >= data.domain.start && point.elapsedSeconds <= data.domain.end;
  const tag = kind === 'selection' ? data.selectionSeconds : data.referenceSeconds;
  const exact = data[kind];
  if (tag === seconds && exact && Object.prototype.hasOwnProperty.call(exact, metric.id)) {
    const point = exact[metric.id];
    return inDomain(point) ? point : null;
  }
  const point = nearestMonitorPoint(data.series[metric.id] ?? [], seconds, metric);
  return inDomain(point) ? point : null;
}

/** Display-only endpoint; never used for selection, summaries or capture. */
export function monitorTailSeconds(
  point: MonitorPoint | null | undefined,
  nowSeconds: number | undefined,
  metric: MonitorMetric,
  acquiredAt: number | null | undefined,
  monotonicAt: number | undefined,
  evidencePoint: MonitorPoint | null | undefined,
  mapMonotonicSeconds?: (seconds: number) => number | null,
): number | null {
  if (!validMonitorPoint(point) || !point.observationId || nowSeconds === undefined || monotonicAt === undefined)
    return null;
  const mapped = acquiredAt == null ? null : mapMonotonicSeconds ? mapMonotonicSeconds(acquiredAt) : acquiredAt;
  return (
    inspectionHeldTail(
      metric.id,
      point.observationId,
      point.elapsedSeconds,
      point.value,
      evidencePoint?.observationId ?? '',
      mapped,
      monotonicAt,
      nowSeconds,
    ) ?? null
  );
}

/** Live time may advance through a gap; this changes only the axis, not the sampled domain. */
export function monitorDisplayDomain(data: MonitorData, live = false, displayTimeSeconds?: number): ChartViewport {
  if (!Object.values(data.series).some(points => points?.some(validMonitorPoint))) return data.domain;
  const now = displayTimeSeconds ?? data.nowSeconds;
  return live && now !== undefined && Number.isFinite(now)
    ? { start: data.domain.start, end: Math.max(data.domain.end, now) }
    : data.domain;
}

export function stepMonitorCursor(
  data: MonitorData,
  metrics: readonly MonitorMetric[],
  cursor: number | null,
  direction: -1 | 1,
  view: ChartViewport,
): number | null {
  let result: number | null = null;
  const origin = cursor ?? (direction === 1 ? view.start : view.end);
  for (const metric of metrics)
    for (const point of data.series[metric.id] ?? []) {
      if (
        !validMonitorPoint(point) ||
        point.elapsedSeconds < data.domain.start ||
        point.elapsedSeconds > data.domain.end
      )
        continue;
      const eligible =
        cursor === null
          ? direction === 1
            ? point.elapsedSeconds >= origin
            : point.elapsedSeconds <= origin
          : direction === 1
            ? point.elapsedSeconds > origin
            : point.elapsedSeconds < origin;
      if (eligible) {
        if (result === null || (direction === 1 ? point.elapsedSeconds < result : point.elapsedSeconds > result))
          result = point.elapsedSeconds;
      }
    }
  return result ?? cursor;
}

export function monitorElapsed(seconds: number): string {
  if (!Number.isFinite(seconds)) return '—';
  const milliseconds = Math.round(Math.abs(seconds) * 1000);
  const whole = Math.floor(milliseconds / 1000);
  const hours = Math.floor(whole / 3600);
  return `${seconds < 0 ? '−' : ''}${hours ? `${hours}:` : ''}${String(Math.floor(whole / 60) % 60).padStart(2, '0')}:${String(whole % 60).padStart(2, '0')}.${String(milliseconds % 1000).padStart(3, '0')}`;
}
