import {
  InspectionPlotQuery,
  InspectionPlotCache,
  InspectionPlotSlotSummary,
  InspectionMetricQuery,
  InspectionMetricPolicy,
  InspectionTargetInput,
  InspectionOriginalInput,
  inspectionHeldTail,
  inspectionPlotBuckets,
  inspectionPlotPointLimit,
  inspectionPlotSlotUs,
} from '../../shared/js';
import { inspectMonitorItems, inspectionPoint } from '../core/monitor-inspection';
import { isChartCapacity } from '../core/failures';
import { int64, secondsUs } from './browser-storage-keys';
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
  type MonitorPlotRequest,
  type MonitorStatsRequest,
  type MonitorStatistics,
} from '../core/monitor';
import { isDistanceMetric, type DistanceSource } from '../core/distance';
import { isConsumptionMetric } from '../core/consumption';
import { withEnergyPerDistance } from './energy-per-distance-monitor';
const isDerivedMetric = (id: string) => isDistanceMetric(id) || isConsumptionMetric(id);
import {
  browserRideStore,
  BROWSER_PAGE,
  PLOT_SUMMARY_POINTS,
  TILE_SECONDS,
  rowPoint,
  tileLevelWithin,
  type BrowserRideStore,
  type RideRow,
  type BrowserRide,
  type RideLocationRow,
  type OriginalCursor,
  type OriginalIdentity,
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
  browserEnergyPerDistance,
} from './browser-distance-store';

const yieldPage = () => new Promise<void>(resolve => setTimeout(resolve, 0));
const revision = (record: { revision: string }) => String(record.revision);
const INT64_MIN = '-9223372036854775808',
  INT64_MAX = '9223372036854775807';
type PlotStream = 'samples' | 'ride-locations';
type PlotSummaries = { revision: string; last?: OriginalIdentity; summaries: Map<string, InspectionPlotSlotSummary> };
type AnalysisCache = {
  statistics: Map<string, Record<string, MonitorStatistics>>;
  plots: Map<string, Record<string, MonitorPoint[]>>;
  lifecycle?: { revision: string; items: import('../../shared/js').AnalyticsItem[] };
  originals: Map<string, InspectionOriginalInput>;
  summaries: Partial<Record<PlotStream, PlotSummaries>>;
  exhausted: Set<string>;
  summaryWork: Promise<unknown>;
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
  if (!cache)
    cache = {
      statistics: new Map(),
      plots: new Map(),
      originals: new Map(),
      summaries: {},
      exhausted: new Set(),
      summaryWork: Promise.resolve(),
    };
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
export function browserPlotWindow(
  request: Pick<MonitorPlotRequest, 'startSeconds' | 'endSeconds' | 'buckets' | 'pixelWidth'>,
  elapsedSeconds: number,
  metricCount: number,
) {
  const start = Math.max(0, request.startSeconds ?? 0),
    domainEnd = Math.max(10, elapsedSeconds),
    whole = start === 0 && (request.endSeconds ?? domainEnd) >= domainEnd,
    end = whole ? domainEnd : Math.max(start, request.endSeconds ?? elapsedSeconds),
    buckets = inspectionPlotBuckets(
      Math.max(1, Math.min(512, Math.floor(request.buckets ?? request.pixelWidth ?? 240))),
      metricCount,
    );
  return {
    start,
    end,
    whole,
    buckets,
    pointLimit: inspectionPlotPointLimit(metricCount),
    slotUs: whole ? (inspectionPlotSlotUs('0', secondsUs(end), buckets) ?? null) : null,
  };
}
function compareOriginals(a: OriginalIdentity, b: OriginalIdentity): number {
  const time = int64(a.elapsedUs) - int64(b.elapsedUs);
  if (time !== 0n) return time < 0n ? -1 : 1;
  if (a.producer !== b.producer) return a.producer < b.producer ? -1 : 1;
  const sequence = int64(a.producerSequence) - int64(b.producerSequence);
  return sequence === 0n ? 0 : sequence < 0n ? -1 : 1;
}
function plotted(reducer: InspectionPlotQuery): MonitorPoint[] {
  let previous: number | undefined;
  return reducer.result().map(value => {
    const startsSegment = value.segment !== previous;
    previous = value.segment;
    return { ...inspectionPoint(value.original), startsSegment };
  });
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
  function feed(state: PlotSummaries, rows: (RideRow | RideLocationRow)[]) {
    for (const row of rows) {
      const input = new InspectionOriginalInput(browserInspectionItem(row));
      for (const [metric, summary] of state.summaries)
        try {
          summary.appendOriginal(input, true, false);
        } catch {
          state.summaries.delete(metric);
          cache.exhausted.add(metric);
        }
    }
    state.last = rows.at(-1) ?? state.last;
  }
  async function liveSummaries(stream: PlotStream, metrics: string[], record: BrowserRide) {
    let state = cache.summaries[stream];
    if (state && int64(state.revision) > int64(record.revision)) return undefined;
    if (state && state.revision !== record.revision) {
      const rows: (RideRow | RideLocationRow)[] = [];
      let after: OriginalCursor | null = null;
      while (true) {
        const page: ((RideRow | RideLocationRow) & { revision: string })[] = await store.originalsSince(
          stream,
          id,
          state.revision,
          record.revision,
          after,
        );
        rows.push(...page);
        if (page.length < BROWSER_PAGE) break;
        const tail = page.at(-1)!;
        after = [tail.revision, tail.producer, tail.producerSequence];
      }
      rows.sort(compareOriginals);
      if (rows[0] && state.last && compareOriginals(rows[0], state.last) <= 0) state = undefined;
      else {
        feed(state, rows);
        state.revision = record.revision;
      }
    }
    const missing = metrics.filter(metric => !cache.exhausted.has(metric) && !state?.summaries.has(metric));
    if (missing.length) {
      const built: PlotSummaries = {
        revision: record.revision,
        summaries: new Map(
          missing.map(metric => [metric, new InspectionPlotSlotSummary(metric, 512, PLOT_SUMMARY_POINTS)]),
        ),
      };
      let after: OriginalCursor | null = null;
      while (true) {
        const rows: (RideRow | RideLocationRow)[] =
          stream === 'samples'
            ? await store.pageExact(id, INT64_MIN, INT64_MAX, after, BROWSER_PAGE, record.revision)
            : await store.locationPage(id, INT64_MIN, INT64_MAX, after, record.revision);
        feed(built, rows);
        if (rows.length < BROWSER_PAGE) break;
        const tail = rows.at(-1)!;
        after = [tail.elapsedUs, tail.producer, tail.producerSequence];
        await yieldPage();
      }
      if (state) for (const [metric, summary] of built.summaries) state.summaries.set(metric, summary);
      else state = built;
    }
    cache.summaries[stream] = state;
    return state?.summaries;
  }
  function summaryPlots(
    stream: PlotStream,
    metrics: string[],
    record: BrowserRide,
    endUs: string,
    buckets: number,
    pointLimit: number,
    slotUs: string,
  ): Promise<Record<string, MonitorPoint[]>> {
    const work = cache.summaryWork.then(async () => {
      const summaries = await liveSummaries(stream, metrics, record);
      const series: Record<string, MonitorPoint[]> = {};
      for (const metric of metrics) {
        const summary = summaries?.get(metric);
        if (!summary) continue;
        const reducer = new InspectionPlotQuery(metric, '0', endUs, buckets, pointLimit, slotUs);
        if (!summary.canMergeInto(reducer)) continue;
        summary.mergeInto(reducer);
        series[metric] = plotted(reducer);
      }
      return series;
    });
    cache.summaryWork = work.catch(() => {});
    return work;
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

  return withEnergyPerDistance(
    {
      key,
      live,
      semanticKey: 'distance:auto',
      distanceSelection: 'auto',
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
        const metrics = metricsFor(request.metrics);
        const {
          start,
          end,
          whole,
          buckets: budget,
          pointLimit,
          slotUs: wholeSlot,
        } = browserPlotWindow(request, record.elapsedSeconds, metrics.length);
        const summarySlot = live && !record.endedAt ? wholeSlot : null;
        if (record.endedAt) cache.summaries = {};
        const rawMetrics = metrics.filter(
          metric =>
            !isDerivedMetric(metric) && !browserLocationMetrics.has(metric) && record.availableMetrics.includes(metric),
        );
        const cacheKey = JSON.stringify([revision(record), start, end, rawMetrics, budget, pointLimit]);
        let series = plotCache.get(cacheKey);
        if (!series && metrics.every(metric => isDerivedMetric(metric))) series = {};
        if (!series) {
          const built =
            summarySlot === null || !rawMetrics.length
              ? {}
              : await summaryPlots('samples', rawMetrics, record, secondsUs(end), budget, pointLimit, summarySlot);
          const pending = rawMetrics.filter(metric => !built[metric]);
          const level = whole ? (wholeSlot === null ? -1 : tileLevelWithin(wholeSlot)) : levelFor(end - start, budget);
          const slotUs = whole
            ? (wholeSlot ?? undefined)
            : end - start >= TILE_SECONDS[level]!
              ? secondsUs(TILE_SECONDS[level]!)
              : undefined;
          const readPage = browserPageReader(store, id, record.revision);
          const reducers = new Map(
            pending.map(metric => [
              metric,
              new InspectionPlotQuery(metric, secondsUs(start), secondsUs(end), budget, pointLimit, slotUs),
            ]),
          );
          const appendRow = (row: RideRow) => {
            const item = browserInspectionItem(row);
            for (const reducer of reducers.values()) reducer.append(item, true, false);
          };
          const descend = async (
            metric: string,
            reducer: InspectionPlotQuery,
            level: number,
            first: number,
            last: number,
          ): Promise<void> => {
            const size = TILE_SECONDS[level]!;
            const tiles = new Map(
              (await store.tiles(id, level, first, last, record.revision)).map(tile => [tile.bucket, tile]),
            );
            for (let bucket = first; bucket <= last; bucket++) {
              const payload = tiles.get(bucket)?.stats[metric];
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
                  metric,
                  reducer,
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
          if (pending.length) {
            const before = await store.neighbor(id, start, 'prev', record.revision);
            if (before && before.elapsedSeconds < start) appendRow(before);
            if (level < 0) await scan(start, end, appendRow, record.revision, readPage);
            else {
              const size = TILE_SECONDS[level]!;
              for (const [metric, reducer] of reducers)
                await descend(
                  metric,
                  reducer,
                  level,
                  Math.floor(start / size),
                  Math.floor(Math.min(end, record.elapsedSeconds) / size),
                );
            }
            const after = await store.neighbor(id, end, 'next', record.revision);
            if (after && after.elapsedSeconds > end) appendRow(after);
          }
          series = { ...built };
          for (const [metric, reducer] of reducers) series[metric] = plotted(reducer);
          if (!(await current(envelope.revision))) return { ...envelope, status: 'retry' };
          if (plotCache.size >= 2) plotCache.delete(plotCache.keys().next().value!);
          plotCache.set(cacheKey, series);
        }
        const locationMetrics = metrics.filter(
          metric => browserLocationMetrics.has(metric) && record.availableMetrics.includes(metric),
        );
        if (locationMetrics.length) {
          series = {
            ...series,
            ...(summarySlot === null
              ? {}
              : await summaryPlots(
                  'ride-locations',
                  locationMetrics,
                  record,
                  secondsUs(end),
                  budget,
                  pointLimit,
                  summarySlot,
                )),
          };
          const reducers = new Map(
            locationMetrics
              .filter(metric => !series![metric])
              .map(metric => [
                metric,
                new InspectionPlotQuery(
                  metric,
                  secondsUs(start),
                  secondsUs(end),
                  budget,
                  pointLimit,
                  wholeSlot ?? undefined,
                ),
              ]),
          );
          if (reducers.size)
            await scanBrowserLocations(record, store, start, end, (_item, row) => {
              const item = browserInspectionItem(row);
              for (const reducer of reducers.values()) reducer.append(item, true, false);
            });
          for (const [metric, reducer] of reducers) series[metric] = plotted(reducer);
        }
        let metricSources: Record<string, MetricSourceInfo> | undefined;
        if (metrics.some(isDerivedMetric)) {
          const handle = await ensureBrowserDistance(id, distanceSource);
          metricSources = browserDerivedSources(handle);
          series = { ...series };
          for (const metric of metrics.filter(isDerivedMetric))
            series[metric] = await plotBrowserDistance(
              browserDerivedHandle(handle, metric),
              start,
              end,
              budget,
              wholeSlot ?? undefined,
              summarySlot !== null,
            );
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
    },
    async request => {
      const { record, envelope } = await context(request.generation);
      if (envelope.revision !== request.expectedRevision) return { ...envelope, status: 'retry' };
      const end = request.endSeconds ?? record.elapsedSeconds;
      const start = request.energyCurrent ? end : (request.startSeconds ?? end);
      const handle = request.energyTotals === false ? null : await ensureBrowserDistance(id, distanceSource, record);
      const buckets = handle || request.energyCurrent ? null : (request.buckets ?? 128);
      const grid =
        buckets === null
          ? undefined
          : browserPlotWindow({ startSeconds: start, endSeconds: end, buckets }, record.elapsedSeconds, 1);
      const result = grid?.whole
        ? await browserEnergyPerDistance(
            record,
            handle,
            grid.start,
            grid.end,
            grid.buckets,
            request.energySmoothingSeconds,
            { slotUs: grid.slotUs, incremental: live && !record.endedAt },
          )
        : await browserEnergyPerDistance(record, handle, start, end, buckets, request.energySmoothingSeconds);
      if (!(await current(envelope.revision))) return { ...envelope, status: 'retry' };
      return { ...envelope, status: 'ok', ...result };
    },
  );
}
