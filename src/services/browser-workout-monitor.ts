import { metricById, MONITOR_METRICS, type MetricSourceInfo, type MonitorSource, type MonitorPoint, type MonitorInspectRequest, type MonitorStatsRequest, type MonitorStatistics } from '../core/monitor';
import { DISTANCE_POLICY_VERSION, type DistanceSource } from '../core/distance';
import { decimateChart, findChartSample } from '../core/chart';
import { browserRideStore, BROWSER_PAGE, TILE_SECONDS, mergeAggregate, rowAggregate, rowPoint, statisticsOf, type Aggregate, type BrowserRideStore, type RideRow } from './browser-ride-store';
import { browserDistanceRevision, browserDistanceCurrent, browserDistanceLatest, browserDistanceStats, ensureBrowserDistance, inspectBrowserDistance, peekBrowserDistance, plotBrowserDistance } from './browser-distance-store';

const yieldPage = () => new Promise<void>(resolve => setTimeout(resolve, 0));
const revision = (record: { revision?: number; samples: number }) => String(record.revision ?? record.samples);
type AnalysisCache = { statistics: Map<string, Record<string, MonitorStatistics>>; plots: Map<string, Record<string, MonitorPoint[]>> };
const sourceCaches = new WeakMap<BrowserRideStore, Map<string, AnalysisCache>>();
function cacheFor(store: BrowserRideStore, id: string): AnalysisCache {
  let rides = sourceCaches.get(store); if (!rides) { rides = new Map(); sourceCaches.set(store, rides); }
  let cache = rides.get(id); if (!cache) cache = { statistics: new Map(), plots: new Map() };
  rides.delete(id); rides.set(id, cache);
  if (rides.size > 4) rides.delete(rides.keys().next().value!);
  return cache;
}
const metricsFor = (metrics: string[]) => [...new Set(metrics)].filter(id => Boolean(metricById(id))).slice(0, 32);
function levelFor(span: number, maximum: number): number {
  const level = TILE_SECONDS.findIndex(size => Math.ceil(span / size) + 1 <= maximum);
  return level < 0 ? TILE_SECONDS.length - 1 : level;
}
export function browserWorkoutMonitorSource(id: string, live = false, store: BrowserRideStore = browserRideStore, distanceSource: DistanceSource = 'auto'): MonitorSource {
  const key = `browser-workout:${id}`;
  // Request generations belong to independent UI consumers. The shared scheduler
  // cancels publication; this provider only fences data revisions and bounds reads.
  const { statistics: statsCache, plots: plotCache } = cacheFor(store, id);
  async function context(generation: number) {
    const record = await store.get(id);
    return { record, envelope: { generation, sourceId: key, revision: browserDistanceRevision(record, distanceSource) } };
  }
  async function current(expected: string) { return browserDistanceRevision(await store.get(id), distanceSource) === expected; }
  async function scan(start: number, end: number, onRow: (row: RideRow) => void) {
    let after: [number, number] | undefined;
    while (true) {
      const rows = await store.page(id, Math.max(0, start), end, after);
      rows.forEach(onRow);
      if (rows.length < BROWSER_PAGE) return;
      const tail = rows[rows.length - 1]!; after = [tail.elapsedSeconds, tail.sequence]; await yieldPage();
    }
  }
  async function aggregateRange(start: number, end: number, metrics: string[], level = levelFor(end - start, 128), exclusiveEnd = false): Promise<Record<string, Aggregate>> {
    const size = TILE_SECONDS[level]!, aggregates: Record<string, Aggregate> = {};
    const merge = (values: Record<string, Aggregate>) => { for (const metric of metrics) { const value = values[metric]; if (value) aggregates[metric] = mergeAggregate(aggregates[metric], value, metric); } };
    for (const tile of await store.tiles(id, level, Math.floor(start / size), exclusiveEnd ? Math.ceil(end / size) - 1 : Math.floor(end / size))) {
      const left = tile.bucket * size, right = (tile.bucket + 1) * size;
      if (left >= start && right <= end) merge(tile.stats);
      else if (level > 0) merge(await aggregateRange(Math.max(start, left), Math.min(end, right), metrics, level - 1, right <= end || exclusiveEnd));
      else await scan(Math.max(start, left), Math.min(end, right), row => {
        if (Math.floor(row.elapsedSeconds / size) !== tile.bucket || exclusiveEnd && row.elapsedSeconds === end) return;
        for (const metric of metrics) { const value = rowAggregate(row, metric); if (value) aggregates[metric] = mergeAggregate(aggregates[metric], value, metric); }
      });
    }
    return aggregates;
  }
  async function inspection(request: MonitorInspectRequest) {
    const { envelope } = await context(request.generation);
    if (envelope.revision !== request.expectedRevision) return { ...envelope, status: 'retry' as const };
    const [before, after] = request.metrics.some(metric => metric !== 'distanceMeters') ? await Promise.all([store.neighbor(id, request.seconds, 'prev'), store.neighbor(id, request.seconds, 'next')]) : [undefined, undefined];
    let anchored: RideRow | undefined;
    if (request.anchor && request.anchor.metric !== 'distanceMeters') {
      const prefix = `${id}:`, suffix = request.anchor.observationId.startsWith(prefix) ? request.anchor.observationId.slice(prefix.length) : '';
      if (/^\d+$/.test(suffix)) anchored = await store.bySequence(id, Number(suffix));
    }
    const points: Record<string, MonitorPoint | null> = {}, gaps: Record<string, boolean> = {};
    for (const metric of metricsFor(request.metrics)) {
      if (metric === 'distanceMeters') {
        const handle = await ensureBrowserDistance(id, distanceSource);
        points[metric] = await inspectBrowserDistance(handle, request.seconds, request.anchor?.metric === metric ? request.anchor.observationId : undefined); gaps[metric] = !points[metric];
        if (!await browserDistanceCurrent(handle)) return { ...envelope, status: 'retry' as const };
        continue;
      }
      let point: MonitorPoint | undefined;
      if (request.anchor?.metric === metric) {
        if (anchored && Math.abs(anchored.elapsedSeconds - request.seconds) <= 1e-6) point = rowPoint(anchored, metric);
      } else {
        const rows = [before, after].filter((row): row is RideRow => Boolean(row)).filter((row, index, all) => index === 0 || all[0]!.sequence !== row.sequence).sort((a, b) => a.elapsedSeconds - b.elapsedSeconds || a.sequence - b.sequence);
        const candidates = rows.map(row => rowPoint(row, metric)).filter((value): value is MonitorPoint => Boolean(value));
        const near = candidates.find(value => Math.abs(value.elapsedSeconds - request.seconds) <= 1e-6);
        point = near ?? findChartSample(candidates, request.seconds, metricById(metric)!.gapSeconds).sample ?? undefined;
        if (!point && live && before && !after && request.seconds - before.elapsedSeconds < metricById(metric)!.gapSeconds) point = rowPoint(before, metric);
      }
      points[metric] = point ?? null; gaps[metric] = !point;
    }
    if (!await current(envelope.revision)) return { ...envelope, status: 'retry' as const };
    return { ...envelope, status: 'ok' as const, seconds: request.seconds, points, gaps };
  }
  async function stats(request: MonitorStatsRequest) {
    const { record, envelope } = await context(request.generation);
    if (envelope.revision !== request.expectedRevision) return { ...envelope, status: 'retry' as const };
    const start = Math.max(0, Math.min(request.startSeconds, request.endSeconds)), end = Math.max(0, request.startSeconds, request.endSeconds);
    const metrics = metricsFor(request.metrics), rawMetrics = metrics.filter(metric => metric !== 'distanceMeters'), cacheKey = JSON.stringify([revision(record), start, end, rawMetrics]);
    let statistics = statsCache.get(cacheKey);
    if (!statistics) {
      if (rawMetrics.length) await store.ensureProjection(id);
      const aggregates = rawMetrics.length ? await aggregateRange(start, end, rawMetrics) : {};
      // Edge integration uses adjacent originals, clipped to the requested range.
      // Their values do not enter the in-range count, extrema or observation mean.
      const [before, after] = rawMetrics.length ? await Promise.all([store.neighbor(id, start, 'prev'), store.neighbor(id, end, 'next')]) : [undefined, undefined];
      for (const metric of metrics) {
        const value = aggregates[metric]; if (!value) continue;
        for (const [left, right] of [[before && rowAggregate(before, metric), value], [value, after && rowAggregate(after, metric)]]) {
          if (!left || !right || left.last.elapsedSeconds >= right.first.elapsedSeconds) continue;
          const a = left.last, b = right.first, dt = b.elapsedSeconds - a.elapsedSeconds;
          const limit = ['humanPowerW', 'cadenceRpm'].includes(metric) ? 2.5 : metricById(metric)!.gapSeconds;
          if (dt > limit || !left.lastActive || !right.firstActive || left.lastInterval !== right.firstInterval) continue;
          const lo = Math.max(start, a.elapsedSeconds), hi = Math.min(end, b.elapsedSeconds); if (hi <= lo) continue;
          const interpolate = (time: number) => a.value + (b.value - a.value) * (time - a.elapsedSeconds) / dt;
          value.covered += hi - lo; value.integral += (metricById(metric)!.kind === 'step' ? a.value : (interpolate(lo) + interpolate(hi)) / 2) * (hi - lo);
        }
      }
      statistics = Object.fromEntries(Object.entries(aggregates).map(([metric, value]) => [metric, statisticsOf(value, metric)]));
      if (!await current(envelope.revision)) return { ...envelope, status: 'retry' as const };
      if (statsCache.size >= 4) statsCache.delete(statsCache.keys().next().value!);
      statsCache.set(cacheKey, statistics);
    }
    statistics = { ...statistics };
    if (metrics.includes('distanceMeters')) {
      const handle = await ensureBrowserDistance(id, distanceSource), distance = await browserDistanceStats(handle, start, end);
      if (distance) statistics.distanceMeters = distance;
      if (!await browserDistanceCurrent(handle)) return { ...envelope, status: 'retry' as const };
    }
    if (!await current(envelope.revision)) return { ...envelope, status: 'retry' as const };
    if (!request.includeEndpoints) return { ...envelope, status: 'ok' as const, statistics };
    const [a, b] = await Promise.all([inspection({ ...request, seconds: request.startSeconds, anchor: request.startAnchor }), inspection({ ...request, seconds: request.endSeconds, anchor: request.endAnchor })]);
    if (a.status !== 'ok' || b.status !== 'ok') return { ...envelope, status: 'retry' as const };
    return { ...envelope, status: 'ok' as const, statistics, endpoints: { start: a.points, end: b.points } };
  }
  return { key, live, semanticKey: `distance:v${DISTANCE_POLICY_VERSION}:${distanceSource}`,
    async describeSource({ generation }) {
      const { record, envelope } = await context(generation), tail = await store.neighbor(id, Infinity, 'prev');
      const distance = await peekBrowserDistance(id, distanceSource);
      if (!distance) void ensureBrowserDistance(id, distanceSource).catch(() => {});
      const selected = distance?.info.selected;
      return { ...envelope, status: 'ok', startedAt: record.startedAt, domain: { start: 0, end: Math.max(10, record.elapsedSeconds ?? tail?.elapsedSeconds ?? 0) },
        nowSeconds: live && !record.endedAt ? Math.max(record.elapsedSeconds ?? 0, (Date.now() - Date.parse(record.startedAt)) / 1000) : record.elapsedSeconds ?? tail?.elapsedSeconds ?? 0,
        availableMetrics: [...(record.availableMetrics ?? MONITOR_METRICS.filter(m => tail && rowPoint(tail, m.id)).map(m => m.id)), ...(selected ? ['distanceMeters'] : [])],
        metricSources: selected ? { distanceMeters: selected } : undefined,
        outcome: !distance ? 'pending' : record.samples ? record.interrupted ? 'partial' : 'available' : live ? 'pending' : 'unavailable', warnings: record.warnings ?? [] };
    },
    async readLatest({ generation, metrics }) {
      const { envelope } = await context(generation), tail = metrics.some(metric => metric !== 'distanceMeters') ? await store.neighbor(id, Infinity, 'prev') : undefined;
      const points = Object.fromEntries(metricsFor(metrics).map(metric => [metric, tail ? rowPoint(tail, metric) ?? null : null]));
      let metricSources: Record<string, MetricSourceInfo> | undefined;
      if (metrics.includes('distanceMeters')) {
        const handle = await ensureBrowserDistance(id, distanceSource); points.distanceMeters = await browserDistanceLatest(handle);
        if (handle.info.selected) metricSources = { distanceMeters: handle.info.selected };
        if (!await browserDistanceCurrent(handle)) return { ...envelope, status: 'retry' };
      }
      if (!await current(envelope.revision)) return { ...envelope, status: 'retry' };
      return { ...envelope, status: 'ok', points, metricSources };
    },
    async readPlot(request) {
      const { record, envelope } = await context(request.generation);
      if (envelope.revision !== request.expectedRevision) return { ...envelope, status: 'retry' };
      const tail = request.metrics.some(metric => metric !== 'distanceMeters') || record.elapsedSeconds === undefined ? await store.neighbor(id, Infinity, 'prev') : undefined;
      const start = Math.max(0, request.startSeconds ?? 0), end = Math.max(start, request.endSeconds ?? record.elapsedSeconds ?? tail?.elapsedSeconds ?? 0);
      const metrics = metricsFor(request.metrics), budget = Math.max(4, Math.min(256, Math.floor(900 / Math.max(1, metrics.length)), Math.floor(request.buckets ?? request.pixelWidth ?? 240)));
      const rawMetrics = metrics.filter(metric => metric !== 'distanceMeters');
      const cacheKey = JSON.stringify([revision(record), start, end, rawMetrics, budget]);
      let series = plotCache.get(cacheKey);
      if (!series && metrics.every(metric => metric === 'distanceMeters')) series = {};
      if (!series) {
        // Close views retain original detail. This path has a fixed raw-row bound.
        if (end - start <= 128) {
          const rows: RideRow[] = []; let after: [number, number] | undefined, complete = false;
          while (rows.length < 2048) {
            const page = await store.page(id, start, end, after); rows.push(...page);
            if (page.length < BROWSER_PAGE) { complete = true; break; }
            const last = page[page.length - 1]!; after = [last.elapsedSeconds, last.sequence]; await yieldPage();
          }
          if (complete) {
            series = {};
            for (const metric of rawMetrics) {
              const points = rows.map(row => rowPoint(row, metric)).filter((point): point is MonitorPoint => Boolean(point));
              if (points.length <= budget * 4) series[metric] = points.map((point, index) => ({ ...point, startsSegment: !index || point.elapsedSeconds - points[index - 1]!.elapsedSeconds >= metricById(metric)!.gapSeconds }));
              else {
                let count = budget, geometry = decimateChart(points, point => point.value, count, metricById(metric)!.gapSeconds);
                while (geometry.length > Math.floor(4096 / metrics.length) && count > 1) { count = Math.max(1, Math.floor(count / 2)); geometry = decimateChart(points, point => point.value, count, metricById(metric)!.gapSeconds); }
                series[metric] = geometry.map(point => ({ ...points[point.sampleIndex]!, startsSegment: point.startsSegment }));
              }
            }
          }
        }
      }
      if (!series) {
        await store.ensureProjection(id);
        const level = levelFor(end - start, budget), size = TILE_SECONDS[level]!;
        const tiles = await store.tiles(id, level, Math.floor(start / size), Math.floor(end / size));
        const bins = new Map<number, Record<string, Aggregate>>();
        for (const tile of tiles) {
          if (tile.bucket * size >= start && (tile.bucket + 1) * size <= end) bins.set(tile.bucket, tile.stats);
          else {
            const aggregates = await aggregateRange(Math.max(start, tile.bucket * size), Math.min(end, (tile.bucket + 1) * size), rawMetrics, level, (tile.bucket + 1) * size <= end);
            bins.set(tile.bucket, aggregates);
          }
        }
        series = {};
        for (const metric of rawMetrics) {
          const points: MonitorPoint[] = []; let previous: MonitorPoint | undefined;
          for (const bin of bins.values()) {
            const value = bin[metric]; if (!value) continue;
            const ordered = [...new Map([value.first, value.min, value.max, value.last].map(p => [p.observationId, p])).values()].sort((a, b) => a.elapsedSeconds - b.elapsedSeconds || a.observationId!.localeCompare(b.observationId!));
            for (const [index, point] of ordered.entries()) points.push({ ...point, startsSegment: value.gap || index === 0 && (!previous || value.first.elapsedSeconds - previous.elapsedSeconds >= metricById(metric)!.gapSeconds) });
            previous = value.last;
          }
          series[metric] = points;
        }
        if (!await current(envelope.revision)) return { ...envelope, status: 'retry' };
        if (plotCache.size >= 2) plotCache.delete(plotCache.keys().next().value!); plotCache.set(cacheKey, series);
      }
      if (!await current(envelope.revision)) return { ...envelope, status: 'retry' };
      if (!plotCache.has(cacheKey)) { if (plotCache.size >= 2) plotCache.delete(plotCache.keys().next().value!); plotCache.set(cacheKey, series); }
      const latest = Object.fromEntries(metrics.map(metric => [metric, tail ? rowPoint(tail, metric) ?? null : null]));
      let metricSources: Record<string, MetricSourceInfo> | undefined;
      if (metrics.includes('distanceMeters')) {
        const handle = await ensureBrowserDistance(id, distanceSource);
        if (handle.info.selected) metricSources = { distanceMeters: handle.info.selected };
        series = { ...series, distanceMeters: await plotBrowserDistance(handle, start, end, budget) };
        latest.distanceMeters = await browserDistanceLatest(handle);
        if (!await browserDistanceCurrent(handle)) return { ...envelope, status: 'retry' };
      }
      if (!await current(envelope.revision)) return { ...envelope, status: 'retry' };
      return { ...envelope, status: 'ok', series, latest, metricSources, resolution: 'reduced' };
    },
    inspectAt: inspection, rangeStats: stats,
    async changesSince(request) { const { record, envelope } = await context(request.generation); return { ...envelope, status: 'ok', resetRequired: request.sinceRevision !== envelope.revision,
      changes: request.sinceRevision === envelope.revision ? [] : [{ startSeconds: 0, endSeconds: record.elapsedSeconds ?? 0, kind: 'semantics' }] }; },
  };
}
