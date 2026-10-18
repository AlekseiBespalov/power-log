import {
  InspectionPlotQuery,
  InspectionPlotCache,
  InspectionMetricQuery,
  InspectionMetricPolicy,
  InspectionTargetInput,
  InspectionOriginalInput,
  inspectionHeldTail,
} from '../../shared/js';
import { inspectMonitorItems, inspectionPoint } from '../core/monitor-inspection';
import { isChartCapacity } from '../core/failures';
import { secondsUs } from './browser-storage-keys';
import {
  browserStatistics,
  browserPageReader,
  browserLocationMetrics,
  browserInspectionItem,
  scanBrowserAnalysis,
  scanBrowserLocations,
} from './browser-analytics';
import {
  metricById,
  type MetricSourceInfo,
  type MonitorSource,
  type MonitorPoint,
  type MonitorInspectRequest,
  type MonitorStatsRequest,
  type MonitorStatistics,
} from '../core/monitor';
import { isDistanceMetric, type DistanceSource } from '../core/distance';
import { isConsumptionMetric } from '../core/consumption';
const isDerivedMetric = (id: string) => isDistanceMetric(id) || isConsumptionMetric(id);
import {
  browserRideStore,
  BROWSER_PAGE,
  TILE_SECONDS,
  rowPoint,
  type BrowserRideStore,
  type RideRow,
  type BrowserRide,
  type RideLocationRow,
} from './browser-ride-store';
import {
  browserDerivedHandle,
  browserDerivedSources,
  browserDistanceRevision,
  browserDistanceCurrent,
  browserDistanceLatest,
  browserDistanceStats,
  retainBrowserDistance,
  type BrowserDistanceHandle,
  ensureBrowserDistance,
  inspectBrowserDistance,
  peekBrowserDistance,
  plotBrowserDistance,
} from './browser-distance-store';

const yieldPage = () => new Promise<void>(resolve => setTimeout(resolve, 0));
const revision = (record: { revision: string }) => String(record.revision);
type AnalysisCache = {
  statistics: Map<string, Record<string, MonitorStatistics>>;
  plots: Map<string, Record<string, MonitorPoint[]>>;
  lifecycle?: { revision: string; items: import('../../shared/js').AnalyticsItem[] };
  originals: Map<string, InspectionOriginalInput>;
};
const sourceCaches = new WeakMap<BrowserRideStore, Map<string, AnalysisCache>>();
type LiveTiming = { nowSeconds: number; monotonicAt: number };
const liveClocks = new WeakMap<
  BrowserRideStore,
  { id: string; origin: number; firstSequence: number; firstGPSSequence: string; timing: () => LiveTiming | undefined }
>();
export function registerBrowserWorkoutClock(
  id: string,
  origin: number,
  timing: () => LiveTiming | undefined,
  store: BrowserRideStore = browserRideStore,
  firstSequence = 0,
  firstGPSSequence = '0',
): () => void {
  const clock = { id, origin, timing, firstSequence, firstGPSSequence };
  liveClocks.set(store, clock);
  return () => {
    if (liveClocks.get(store) === clock) liveClocks.delete(store);
  };
}
function cacheFor(store: BrowserRideStore, id: string): AnalysisCache {
  let rides = sourceCaches.get(store);
  if (!rides) {
    rides = new Map();
    sourceCaches.set(store, rides);
  }
  let cache = rides.get(id);
  if (!cache) cache = { statistics: new Map(), plots: new Map(), originals: new Map() };
  rides.delete(id);
  rides.set(id, cache);
  if (rides.size > 4) rides.delete(rides.keys().next().value!);
  return cache;
}
const metricsFor = (metrics: string[]) => [...new Set(metrics)].filter(id => Boolean(metricById(id))).slice(0, 32);
function levelFor(span: number, maximum: number): number {
  const level = TILE_SECONDS.findIndex(size => Math.ceil(span / size) + 1 <= maximum);
  return level < 0 ? TILE_SECONDS.length - 1 : level;
}
export function browserWorkoutMonitorSource(
  id: string,
  live = false,
  store: BrowserRideStore = browserRideStore,
  distanceSource: DistanceSource = 'auto',
): MonitorSource {
  const key = `browser-workout:${id}`;
  const timing = (record: { endedAt?: string }): Partial<LiveTiming> => {
    const clock = liveClocks.get(store);
    if (!live || record.endedAt || clock?.id !== id) return {};
    return clock.timing() ?? {};
  };
  // Request generations belong to independent UI consumers. The shared scheduler
  // cancels publication; this provider only fences data revisions and bounds reads.
  const cache = cacheFor(store, id);
  const { statistics: statsCache, plots: plotCache } = cache;
  async function lifecycleFor(record: BrowserRide) {
    if (cache.lifecycle?.revision === record.revision) return cache.lifecycle.items;
    const items: import('../../shared/js').AnalyticsItem[] = [];
    await scanBrowserAnalysis(
      record,
      store,
      item => {
        items.push(item);
      },
      { samples: false, 'ride-locations': false },
    );
    cache.lifecycle = { revision: record.revision, items };
    return items;
  }
  function prepared(row: RideRow | RideLocationRow): InspectionOriginalInput {
    let item = cache.originals.get(row.eventId);
    if (!item) {
      item = new InspectionOriginalInput(browserInspectionItem(row));
      if (cache.originals.size >= 1024) cache.originals.delete(cache.originals.keys().next().value!);
      cache.originals.set(row.eventId, item);
    }
    return item;
  }
  let retainedRecord: BrowserRide | undefined;
  async function context(generation: number) {
    const record = await store.get(id);
    retainedRecord = record;
    return {
      record,
      envelope: { generation, sourceId: key, revision: browserDistanceRevision(record, distanceSource) },
    };
  }
  async function current(expected: string) {
    const record = await store.get(id);
    retainedRecord = record;
    return browserDistanceRevision(record, distanceSource) === expected;
  }
  async function scan(
    start: number,
    end: number,
    onRow: (row: RideRow) => void,
    revision?: string,
    readPage?: ReturnType<typeof browserPageReader>,
  ) {
    let after: [number, number] | undefined;
    while (true) {
      const rows = readPage
        ? await readPage(start, end, after)
        : await store.page(id, Math.max(0, start), end, after, BROWSER_PAGE, revision);
      rows.forEach(onRow);
      if (rows.length < BROWSER_PAGE) return;
      const tail = rows[rows.length - 1]!;
      after = [tail.elapsedSeconds, tail.sequence];
      await yieldPage();
    }
  }
  function inspectionPointFromRow(row: RideRow | RideLocationRow, metric: string): MonitorPoint | undefined {
    const value = (row as unknown as Record<string, unknown>)[metric];
    if (typeof value !== 'number') return undefined;
    return {
      observationId: 'sequence' in row ? `${id}:${row.sequence}` : `${id}:${row.eventId}`,
      timestamp: row.timestamp,
      elapsedSeconds: row.elapsedSeconds,
      value,
    };
  }
  function locationPoint(row: RideLocationRow, metric: string): MonitorPoint | undefined {
    return (
      inspectMonitorItems(metric, [browserInspectionItem(row)], row.elapsedSeconds, row.elapsedSeconds) ?? undefined
    );
  }
  type InspectionWindow = {
    revision: string;
    rows: RideRow[];
    groups: Map<string, RideRow[]>;
    identities: Map<string, RideRow>;
    metrics: Map<string, { rows: RideRow[]; times: bigint[] }>;
  };
  let inspectionWindow: InspectionWindow | undefined;
  function cachedTelemetryCandidates(
    record: BrowserRide,
    request: MonitorInspectRequest,
    metrics: string[],
  ): RideRow[] | undefined {
    const cached = inspectionWindow?.revision === record.revision ? inspectionWindow : undefined;
    const target = BigInt(secondsUs(request.seconds));
    const anchor = request.anchor && metrics.includes(request.anchor.metric) ? request.anchor.observationId : undefined;
    if (cached?.rows.length) {
      const first = BigInt(cached.rows[0]!.elapsedUs),
        last = BigInt(cached.rows.at(-1)!.elapsedUs);
      const anchored = anchor ? cached.identities.get(anchor) : undefined;
      if (
        target > first &&
        target < last &&
        (!anchor || (anchored && BigInt(anchored.elapsedUs) > first && BigInt(anchored.elapsedUs) < last))
      ) {
        const selected = new Map<string, RideRow>();
        let complete = true;
        for (const metric of metrics) {
          let index = cached.metrics.get(metric);
          if (!index) {
            const rows = cached.rows.filter(row => prepared(row).plotValid(metric));
            index = { rows, times: rows.map(row => BigInt(row.elapsedUs)) };
            cached.metrics.set(metric, index);
          }
          let low = 0,
            high = index.times.length;
          while (low < high) {
            const middle = (low + high) >>> 1;
            if (index.times[middle]! < target) low = middle + 1;
            else high = middle;
          }
          const before = index.times[low] === target ? index.rows[low] : index.rows[low - 1];
          const after = index.rows[low];
          if (!before || !after || BigInt(before.elapsedUs) <= first || BigInt(after.elapsedUs) >= last) {
            complete = false;
            break;
          }
          for (const time of new Set([before.elapsedUs, after.elapsedUs, ...(anchored ? [anchored.elapsedUs] : [])]))
            cached.groups.get(time)?.forEach(row => selected.set(row.eventId, row));
        }
        if (complete)
          return [...selected.values()].sort(
            (a, b) => Number(BigInt(a.elapsedUs) - BigInt(b.elapsedUs)) || a.sequence - b.sequence,
          );
      }
    }
    return undefined;
  }
  async function telemetryCandidates(record: BrowserRide, request: MonitorInspectRequest, metrics: string[]) {
    if (!metrics.length) return [];
    const cached = cachedTelemetryCandidates(record, request, metrics);
    if (cached) return cached;
    const anchor = request.anchor && metrics.includes(request.anchor.metric) ? request.anchor.observationId : undefined;
    const rows = await store.inspectionGroups(id, request.seconds, record.revision, false, metrics, anchor);
    const target = BigInt(secondsUs(request.seconds));
    const gap = metrics.reduce((largest, metric) => {
      const value = BigInt(new InspectionMetricPolicy(metric).plotGapUs);
      return value > largest ? value : largest;
    }, 0n);
    const before = gap < 1_000_000n ? gap : 1_000_000n;
    const window = await store.pageExact(
      id,
      (target > before ? target - before : 0n).toString(),
      (target + gap).toString(),
      null,
      BROWSER_PAGE,
      record.revision,
    );
    const groups = new Map<string, RideRow[]>();
    const identities = new Map<string, RideRow>();
    for (const row of window) {
      const group = groups.get(row.elapsedUs) ?? [];
      group.push(row);
      groups.set(row.elapsedUs, group);
      identities.set(`${id}:${row.sequence}`, row);
    }
    inspectionWindow = { revision: record.revision, rows: window, groups, identities, metrics: new Map() };
    return rows;
  }
  async function inspection(request: MonitorInspectRequest, pin?: BrowserRide, distancePin?: BrowserDistanceHandle) {
    const telemetryMetrics = request.metrics.filter(
      metric => !isDerivedMetric(metric) && !browserLocationMetrics.has(metric),
    );
    const locationMetrics = request.metrics.filter(metric => browserLocationMetrics.has(metric));
    const cachedRecord =
      !pin &&
      retainedRecord &&
      browserDistanceRevision(retainedRecord, distanceSource) === request.expectedRevision &&
      cache.lifecycle?.revision === retainedRecord.revision &&
      telemetryMetrics.length === request.metrics.length
        ? retainedRecord
        : undefined;
    const cachedOriginals = cachedRecord
      ? cachedTelemetryCandidates(cachedRecord, request, telemetryMetrics)
      : undefined;
    const retained = pin ?? (cachedOriginals ? cachedRecord : undefined);
    const { record, envelope } = retained
      ? {
          record: retained,
          envelope: {
            generation: request.generation,
            sourceId: key,
            revision: browserDistanceRevision(retained, distanceSource),
          },
        }
      : await context(request.generation);
    if (envelope.revision !== request.expectedRevision) return { ...envelope, status: 'retry' as const };
    const lifecycle = await lifecycleFor(record);
    const [originals, locations] = await Promise.all([
      cachedOriginals ??
        telemetryCandidates(
          record,
          request,
          telemetryMetrics.filter(metric => record.availableMetrics.includes(metric)),
        ),
      locationMetrics.some(metric => record.availableMetrics.includes(metric))
        ? store.inspectionGroups(
            id,
            request.seconds,
            record.revision,
            true,
            locationMetrics.filter(metric => record.availableMetrics.includes(metric)),
            request.anchor && locationMetrics.includes(request.anchor.metric)
              ? request.anchor.observationId
              : undefined,
          )
        : [],
    ]);
    const points: Record<string, MonitorPoint | null> = {},
      gaps: Record<string, boolean> = {};
    for (const metric of metricsFor(request.metrics)) {
      if (isDerivedMetric(metric)) {
        const handle = distancePin ?? (await ensureBrowserDistance(id, distanceSource, record));
        points[metric] = await inspectBrowserDistance(
          browserDerivedHandle(handle, metric),
          request.seconds,
          request.anchor?.metric === metric ? request.anchor.observationId : undefined,
        );
        gaps[metric] = !points[metric];
        if (!pin && !(await browserDistanceCurrent(handle))) return { ...envelope, status: 'retry' as const };
        continue;
      }
      const rows = browserLocationMetrics.has(metric) ? locations : originals;
      const inputs = rows.map(prepared);
      const clock = liveClocks.get(store),
        liveTiming = timing(record);
      const before = rows
        .filter(row => Number(row.elapsedUs) <= Number(secondsUs(request.seconds)) && prepared(row).plotValid(metric))
        .at(-1);
      const original = before ? inspectionPointFromRow(before, metric) : undefined;
      const acquired =
        before && 'acquiredAtSeconds' in before
          ? before.acquiredAtSeconds
          : before && 'sequence' in before && clock?.id === id && before.sequence >= clock.firstSequence
            ? clock.origin + before.elapsedSeconds
            : null;
      const held =
        original && liveTiming.monotonicAt !== undefined
          ? inspectionHeldTail(
              metric,
              original.observationId!,
              original.elapsedSeconds,
              original.value,
              original.observationId!,
              acquired,
              liveTiming.monotonicAt,
              request.seconds,
            )
          : null;
      const anchor = request.anchor?.metric === metric ? request.anchor.observationId : undefined;
      const query = new InspectionMetricQuery(
        metric,
        '0',
        secondsUs(record.elapsedSeconds),
        secondsUs(record.elapsedSeconds),
        lifecycle,
        new InspectionTargetInput(
          secondsUs(request.seconds),
          anchor ?? null,
          anchor ? secondsUs(request.seconds) : null,
        ).withNearbyOriginal(metric, inputs),
        null,
        held !== null,
      );
      inputs.forEach(input => query.appendPreparedInspection(input, true, false, false));
      const selected = query.result().selection;
      points[metric] = selected ? inspectionPoint(selected) : null;
      gaps[metric] = !points[metric];
    }
    if (!pin && !(await current(envelope.revision))) return { ...envelope, status: 'retry' as const };
    return { ...envelope, status: 'ok' as const, seconds: request.seconds, points, gaps };
  }
  async function stats(request: MonitorStatsRequest) {
    const { record, envelope } = await context(request.generation);
    if (envelope.revision !== request.expectedRevision) return { ...envelope, status: 'retry' as const };
    const distancePin = request.metrics.some(isDerivedMetric)
      ? await ensureBrowserDistance(id, distanceSource, record)
      : undefined;
    const release = distancePin ? await retainBrowserDistance(distancePin) : undefined;
    try {
      const start = Math.max(0, Math.min(request.startSeconds, request.endSeconds)),
        end = Math.max(0, request.startSeconds, request.endSeconds);
      const metrics = metricsFor(request.metrics),
        rawMetrics = metrics.filter(metric => !isDerivedMetric(metric));
      const cacheKey = JSON.stringify([revision(record), start, end, rawMetrics]);
      let statistics = statsCache.get(cacheKey);
      if (!statistics) {
        statistics = await browserStatistics(record, store, start, end, rawMetrics);
        if (statsCache.size >= 4) statsCache.delete(statsCache.keys().next().value!);
        statsCache.set(cacheKey, statistics);
      }
      statistics = { ...statistics };
      if (distancePin) {
        for (const metric of metrics.filter(isDerivedMetric)) {
          const result = await browserDistanceStats(browserDerivedHandle(distancePin, metric), start, end);
          if (result) statistics[metric] = result;
        }
      }
      if (!(await current(envelope.revision))) return { ...envelope, status: 'retry' as const };
      if (!request.includeEndpoints) return { ...envelope, status: 'ok' as const, statistics };
      const [a, b] = await Promise.all([
        inspection({ ...request, seconds: request.startSeconds, anchor: request.startAnchor }, record, distancePin),
        inspection({ ...request, seconds: request.endSeconds, anchor: request.endAnchor }, record, distancePin),
      ]);
      if (!(await current(envelope.revision)) || a.status !== 'ok' || b.status !== 'ok')
        return { ...envelope, status: 'retry' as const };
      return { ...envelope, status: 'ok' as const, statistics, endpoints: { start: a.points, end: b.points } };
    } finally {
      await release?.();
    }
  }

  return {
    key,
    live,
    semanticKey: 'distance:auto',
    async describeSource({ generation }) {
      const { record, envelope } = await context(generation);
      const distance = await peekBrowserDistance(id, distanceSource);
      const sources = distance ? browserDerivedSources(distance) : {};
      const outcome =
        record.telemetryCount || record.locationCount
          ? record.interrupted
            ? 'partial'
            : 'available'
          : live
            ? 'pending'
            : 'unavailable';
      return {
        ...envelope,
        status: 'ok',
        startedAt: record.startedAt,
        domain: { start: 0, end: Math.max(10, record.elapsedSeconds) },
        ...timing(record),
        availableMetrics: [...record.availableMetrics, ...Object.keys(sources)],
        metricSources: sources,
        outcome,
      };
    },
    async readLatest({ generation, metrics }) {
      const { record, envelope } = await context(generation),
        tail = metrics.some(metric => !isDerivedMetric(metric))
          ? await store.neighbor(id, Infinity, 'prev')
          : undefined;
      const points = Object.fromEntries(
        metricsFor(metrics).map(metric => [metric, tail ? (rowPoint(tail, metric) ?? null) : null]),
      );
      const location = metrics.some(metric => browserLocationMetrics.has(metric))
        ? await store.locationNeighbor(id, Infinity, 'prev')
        : undefined;
      for (const metric of metrics)
        if (browserLocationMetrics.has(metric))
          points[metric] = location ? (locationPoint(location, metric) ?? null) : null;
      let metricSources: Record<string, MetricSourceInfo> | undefined;
      if (metrics.some(isDerivedMetric)) {
        const handle = await ensureBrowserDistance(id, distanceSource);
        for (const metric of metrics.filter(isDerivedMetric))
          points[metric] = await browserDistanceLatest(browserDerivedHandle(handle, metric));
        metricSources = browserDerivedSources(handle);
        if (!(await browserDistanceCurrent(handle))) return { ...envelope, status: 'retry' };
      }
      if (!(await current(envelope.revision))) return { ...envelope, status: 'retry' };
      const clock = timing(record);
      const origin = liveClocks.get(store)?.origin;
      const liveAcquiredAt =
        clock.monotonicAt === undefined || origin === undefined
          ? undefined
          : Object.fromEntries(
              metrics.map(metric => {
                const point = points[metric];
                const sequence = point?.observationId?.match(/:(\d+)(?::(?:start|end))?$/)?.[1];
                return [
                  metric,
                  browserLocationMetrics.has(metric)
                    ? location && BigInt(location.producerSequence) >= BigInt(liveClocks.get(store)!.firstGPSSequence)
                      ? location.acquiredAtSeconds
                      : null
                    : point && sequence !== undefined && Number(sequence) >= liveClocks.get(store)!.firstSequence
                      ? origin + point.elapsedSeconds
                      : null,
                ];
              }),
            );
      return {
        ...envelope,
        status: 'ok',
        points,
        metricSources,
        ...clock,
        ...(liveAcquiredAt ? { liveAcquiredAt } : {}),
      };
    },
    async readPlot(request) {
      const { record, envelope } = await context(request.generation);
      if (envelope.revision !== request.expectedRevision) return { ...envelope, status: 'retry' };
      const start = Math.max(0, request.startSeconds ?? 0),
        end = Math.max(start, request.endSeconds ?? record.elapsedSeconds);
      const metrics = metricsFor(request.metrics),
        budget = Math.max(
          4,
          Math.min(
            256,
            Math.floor(900 / Math.max(1, metrics.length)),
            Math.floor(request.buckets ?? request.pixelWidth ?? 240),
          ),
        );
      const rawMetrics = metrics.filter(
        metric =>
          !isDerivedMetric(metric) && !browserLocationMetrics.has(metric) && record.availableMetrics.includes(metric),
      );
      const cacheKey = JSON.stringify([revision(record), start, end, rawMetrics, budget]);
      let series = plotCache.get(cacheKey);
      if (!series && metrics.every(metric => isDerivedMetric(metric))) series = {};
      if (!series) {
        series = {};
        const readPage = browserPageReader(store, id, record.revision);
        for (const metric of rawMetrics) {
          const level = levelFor(end - start, budget),
            size = TILE_SECONDS[level]!;
          const reducer = new InspectionPlotQuery(
            metric,
            secondsUs(start),
            secondsUs(end),
            budget,
            16384,
            end - start >= size ? secondsUs(size) : undefined,
          );
          const before = await store.neighbor(id, start, 'prev', record.revision);
          if (before && before.elapsedSeconds < start) reducer.append(browserInspectionItem(before), true, false);
          const descend = async (level: number, first: number, last: number): Promise<void> => {
            const size = TILE_SECONDS[level]!;
            const tiles = await store.tiles(id, level, first, last, record.revision);
            for (let bucket = first; bucket <= last; bucket++) {
              const tile = tiles.find(tile => tile.bucket === bucket);
              const payload = tile?.stats[metric];
              let merged = false;
              if (payload?.plot) {
                try {
                  const aggregate = new InspectionPlotCache(payload.plot);
                  if (reducer.canMerge(aggregate)) {
                    reducer.merge(aggregate);
                    merged = true;
                  }
                } catch (error) {
                  if (isChartCapacity(error)) throw error;
                }
              }
              if (merged) continue;
              const left = bucket * size,
                right = left + size;
              if (level > 0) {
                const childSize = TILE_SECONDS[level - 1]!;
                await descend(
                  level - 1,
                  Math.floor(Math.max(start, left) / childSize),
                  Math.min(
                    Math.ceil(right / childSize) - 1,
                    Math.floor(Math.min(end, record.elapsedSeconds) / childSize),
                  ),
                );
              } else
                await scan(
                  Math.max(start, left),
                  Math.min(end, right),
                  row => {
                    if (row.elapsedSeconds < right) reducer.append(browserInspectionItem(row), true, false);
                  },
                  record.revision,
                  readPage,
                );
            }
          };
          await descend(level, Math.floor(start / size), Math.floor(Math.min(end, record.elapsedSeconds) / size));
          const after = await store.neighbor(id, end, 'next', record.revision);
          if (after && after.elapsedSeconds > end) reducer.append(browserInspectionItem(after), true, false);
          let previous: number | undefined;
          series[metric] = reducer.result().map(value => {
            const startsSegment = value.segment !== previous;
            previous = value.segment;
            return { ...inspectionPoint(value.original), startsSegment };
          });
        }
        if (!(await current(envelope.revision))) return { ...envelope, status: 'retry' };
        if (plotCache.size >= 2) plotCache.delete(plotCache.keys().next().value!);
        plotCache.set(cacheKey, series);
      }
      const locationMetrics = metrics.filter(
        metric => browserLocationMetrics.has(metric) && record.availableMetrics.includes(metric),
      );
      if (locationMetrics.length) {
        const reducers = Object.fromEntries(
          locationMetrics.map(metric => [
            metric,
            new InspectionPlotQuery(metric, secondsUs(start), secondsUs(end), budget, 16384),
          ]),
        );
        await scanBrowserLocations(record, store, start, end, (_item, row) => {
          for (const reducer of Object.values(reducers)) reducer.append(browserInspectionItem(row), true, false);
        });
        series = { ...series };
        for (const metric of locationMetrics) {
          let previous: number | undefined;
          series[metric] = reducers[metric]!.result().map(value => {
            const startsSegment = value.segment !== previous;
            previous = value.segment;
            return { ...inspectionPoint(value.original), startsSegment };
          });
        }
      }
      let metricSources: Record<string, MetricSourceInfo> | undefined;
      if (metrics.some(isDerivedMetric)) {
        const handle = await ensureBrowserDistance(id, distanceSource);
        metricSources = browserDerivedSources(handle);
        series = { ...series };
        for (const metric of metrics.filter(isDerivedMetric))
          series[metric] = await plotBrowserDistance(browserDerivedHandle(handle, metric), start, end, budget);
        if (!(await browserDistanceCurrent(handle))) return { ...envelope, status: 'retry' };
      }
      if (!(await current(envelope.revision))) return { ...envelope, status: 'retry' };
      return {
        ...envelope,
        status: 'ok',
        series: Object.fromEntries(metrics.map(metric => [metric, series[metric] ?? []])),
        metricSources,
        resolution: 'reduced',
      };
    },
    inspectAt: inspection,
    rangeStats: stats,
    async changesSince(request) {
      const { record, envelope } = await context(request.generation);
      return {
        ...envelope,
        status: 'ok',
        resetRequired: request.sinceRevision !== envelope.revision,
        changes:
          request.sinceRevision === envelope.revision
            ? []
            : [{ startSeconds: 0, endSeconds: record.elapsedSeconds, kind: 'semantics' }],
      };
    },
  };
}
