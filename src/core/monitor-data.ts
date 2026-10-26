import {
  AnalyticsItem,
  InspectionMetricQuery,
  InspectionOriginalInput,
  InspectionMetricPolicy,
  InspectionTargetInput,
  analyticsTimeUs,
  AnalyticsEnergyPerDistance,
  analyticsEnergyContextSeconds,
  inspectionHeldTail,
} from '../../shared/js';
import { inspectionPoint, inspectionStatistics, monitorInspectionItem, plotMonitorItems } from './monitor-inspection';
import { isDistanceMetric } from './distance';
import { isConsumptionMetric } from './consumption';
import type { EnergyPerDistanceResult } from './energy-per-distance';
import {
  metricById,
  MONITOR_METRICS,
  type MonitorChange,
  type MonitorChangesRequest,
  type MonitorChangesResult,
  type MonitorDescribeRequest,
  type MonitorDescribeResult,
  type MonitorInspectRequest,
  type MonitorInspectResult,
  type MonitorPlotRequest,
  type MonitorPlotResult,
  type MonitorPoint,
  type MonitorResult,
  type MonitorSource,
  type MonitorStatistics,
  type MonitorStatsRequest,
  type MonitorStatsResult,
} from './monitor';
import {
  sampleAcquisition,
  samplePresentationTime,
  type MonotonicMapping,
  type SampleDelivery,
  type TelemetrySample,
} from './types';

type MonitorSample = TelemetrySample & {
  activeInterval?: number | null;
  clockEpoch?: string;
  observationId?: string;
  producer?: string;
  inspection?: { kind?: string; declared?: string[]; selected?: boolean; accuracy?: number; exactValue?: string };
};
type PreparedOriginal = { item: AnalyticsItem; input: InspectionOriginalInput; sample: MonitorSample; time: bigint };
type PreparedMetric = {
  through: number;
  originals: PreparedOriginal[];
  valid: PreparedOriginal[];
  identities: Map<string, PreparedOriginal>;
  interruptions: Map<string, Set<number>>;
};

const CACHE_BYTES = 4 * 1024 * 1024;
const JOURNAL_ENTRIES = 256;
export class TelemetryMonitor implements MonitorSource {
  private samples: MonitorSample[] = [];
  private cache = new Map<string, PreparedMetric>();
  private identities = new Set<string>();
  private renderCache = new Map<string, { series: MonitorPoint[]; bytes: number }>();
  private cacheBytes = 0;
  private available = new Set<string>();
  private journal: { revision: number; change: MonitorChange }[] = [];
  private session = 0;
  private start = 0;
  private end = 0;
  private liveAnchor: number | null = null;
  private clock?: MonotonicMapping;
  private liveAcquiredAt: Record<string, number | null> = {};
  private startedAt = new Date().toISOString();
  constructor(
    readonly key: string,
    readonly live: boolean,
    samples: readonly MonitorSample[] = [],
    private readonly now = () => performance.now() / 1000,
    private readonly evidence: { lifecycle?: AnalyticsItem[]; rideEndSeconds?: number } = {},
  ) {
    for (const sample of samples) this.append(sample);
  }
  beginSession() {
    this.session++;
    this.samples = [];
    this.cache.clear();
    this.identities.clear();
    this.renderCache.clear();
    this.cacheBytes = 0;
    this.available.clear();
    this.journal = [];
    this.start = 0;
    this.end = 0;
    this.liveAnchor = this.live ? this.now() : null;
    this.startedAt = new Date().toISOString();
    this.clock = undefined;
    this.liveAcquiredAt = {};
  }
  private get sourceId() {
    return this.session ? `${this.key}:session:${this.session}` : this.key;
  }
  append(sample: MonitorSample, delivery?: SampleDelivery) {
    const identity =
      sample.observationId ??
      `${sample.producer ?? 'controller'}:${sample.connectionEpoch}:${sample.sequence}:${sample.timestamp}:${sample.elapsedSeconds}`;
    if (this.live && this.identities.has(identity)) return;
    this.identities.add(identity);
    this.samples.push(sample);
    const time = sample.elapsedSeconds,
      previousEnd = this.end,
      previousStart = this.start;
    const receivedAt = delivery ? samplePresentationTime(delivery) : null;
    const acquiredAt = delivery ? sampleAcquisition(delivery) : null;
    const validAcquisition = acquiredAt !== null && (receivedAt === null || receivedAt <= this.now());
    if (this.live && validAcquisition) {
      this.clock = delivery && 'clock' in delivery ? delivery.clock : undefined;
      this.liveAnchor ??= acquiredAt - time;
    }
    if (this.samples.length === 1) this.start = this.end = time;
    else {
      this.start = Math.min(this.start, time);
      this.end = Math.max(this.end, time);
    }
    const metrics = MONITOR_METRICS.filter(({ id }) => Number.isFinite(sample[id as keyof TelemetrySample])).map(
      ({ id }) => id,
    );
    for (const id of metrics) {
      this.available.add(id);
      if (this.live) this.liveAcquiredAt[id] = validAcquisition ? acquiredAt : null;
    }
    this.journal.push({
      revision: this.samples.length,
      change: {
        startSeconds:
          this.samples.length === 1 ? time : time < previousEnd ? Math.min(previousStart, time) : previousEnd,
        endSeconds: Math.max(time, previousEnd),
        metrics,
        kind: time < previousEnd ? 'correction' : 'append',
      },
    });
    if (this.journal.length > JOURNAL_ENTRIES) this.journal.shift();
  }
  mapMonotonicSeconds = (seconds: number): number | null => (this.clock ? this.clock.toJS(seconds) : seconds);
  private timing() {
    const now = this.now();
    const anchor = this.liveAnchor === null ? null : this.mapMonotonicSeconds(this.liveAnchor);
    const elapsed = anchor === null ? 0 : Math.max(0, now - anchor);
    return this.live
      ? {
          nowSeconds: elapsed,
          monotonicAt: this.liveAnchor === null ? now : this.liveAnchor + elapsed,
        }
      : {};
  }
  private envelope(generation: number) {
    return { generation, sourceId: this.sourceId, revision: String(this.samples.length) };
  }
  private point(index: number, id: string): MonitorPoint {
    const sample = this.samples[index]!;
    const value = sample[id as keyof TelemetrySample];
    return {
      elapsedSeconds: sample.elapsedSeconds,
      timestamp: sample.timestamp,
      value: typeof value === 'number' ? value : NaN,
      ...(sample.inspection?.exactValue === undefined ? {} : { exactValue: sample.inspection.exactValue }),
      observationId: sample.observationId ?? `${this.sourceId}:${String(index).padStart(16, '0')}`,
    };
  }
  private lowerBound(items: PreparedOriginal[], time: bigint, after = false): number {
    let lo = 0,
      hi = items.length;
    while (lo < hi) {
      const mid = (lo + hi) >>> 1;
      if (items[mid]!.time < time || (after && items[mid]!.time === time)) lo = mid + 1;
      else hi = mid;
    }
    return lo;
  }
  private prepare(id: string): PreparedMetric {
    let cached = this.cache.get(id);
    if (!cached) {
      cached = { through: 0, originals: [], valid: [], identities: new Map(), interruptions: new Map() };
      this.cache.set(id, cached);
    }
    for (let index = cached.through; index < this.samples.length; index++) {
      const sample = this.samples[index]!;
      if (!Object.hasOwn(sample, id)) continue;
      const item = monitorInspectionItem(id, this.point(index, id), {
        ...sample.inspection,
        clockEpoch: sample.clockEpoch,
        connectionEpoch: sample.connectionEpoch,
        interruptionIndex: sample.interruptionIndex,
        sequence: index + 1,
        producer: sample.producer,
      });
      const input = new InspectionOriginalInput(item);
      if (!input.isReading(id) || sample.inspection?.selected === false) continue;
      const original = { item, input, sample, time: BigInt(item.collectionUs) };
      const insert = (items: PreparedOriginal[]) => {
        if (!items.length || items[items.length - 1]!.time <= original.time) items.push(original);
        else items.splice(this.lowerBound(items, original.time, true), 0, original);
      };
      insert(cached.originals);
      cached.identities.set(item.observationId, original);
      const interruptions = cached.interruptions.get(item.collectionUs) ?? new Set<number>();
      interruptions.add(item.interruptionIndex);
      cached.interruptions.set(item.collectionUs, interruptions);
      if (input.plotValid(id)) insert(cached.valid);
    }
    cached.through = this.samples.length;
    return cached;
  }
  private items(id: string): AnalyticsItem[] {
    return this.prepare(id).originals.map(original => original.item);
  }
  private candidates(cached: PreparedMetric, time: bigint, anchor?: string): PreparedOriginal[] {
    const selected = new Set<PreparedOriginal>();
    const addGroup = (item: PreparedOriginal | undefined) => {
      if (!item) return;
      for (
        let i = this.lowerBound(cached.originals, item.time);
        i < cached.originals.length && cached.originals[i]!.time === item.time;
        i++
      )
        selected.add(cached.originals[i]!);
    };
    addGroup(cached.valid[this.lowerBound(cached.valid, time, true) - 1]);
    addGroup(cached.valid[this.lowerBound(cached.valid, time)]);
    if (anchor) addGroup(cached.identities.get(anchor));
    return [...selected].sort((a, b) =>
      a.time < b.time ? -1 : a.time > b.time ? 1 : Number(a.item.sequence) - Number(b.item.sequence),
    );
  }
  async describeSource({ generation }: MonitorDescribeRequest): Promise<MonitorDescribeResult> {
    const first = this.samples[0];
    const startedAt =
      !this.live && first
        ? new Date(Date.parse(first.timestamp) - first.elapsedSeconds * 1000).toISOString()
        : this.startedAt;
    return {
      ...this.envelope(generation),
      status: 'ok',
      startedAt,
      domain: { start: this.start, end: Math.max(this.start + 10, this.end) },
      ...this.timing(),
      availableMetrics: [...this.available],
      outcome: this.samples.length ? 'available' : this.live ? 'pending' : 'unavailable',
    };
  }
  /** Foreground preview has no ride totals or GPS capture; use the speed in each controller frame. */
  async readEnergyPerDistance(request: MonitorPlotRequest): Promise<MonitorResult<EnergyPerDistanceResult>> {
    const envelope = this.envelope(request.generation);
    if (request.expectedRevision !== envelope.revision) return { ...envelope, status: 'retry' };
    const end = request.endSeconds ?? this.end;
    const start = request.energyCurrent ? end : (request.startSeconds ?? end);
    const query = new AnalyticsEnergyPerDistance(
      analyticsTimeUs(start),
      analyticsTimeUs(end),
      Math.max(1, Math.min(2048, request.buckets ?? request.pixelWidth ?? 240)),
      analyticsTimeUs(end - start),
      true,
      request.energySmoothingSeconds ?? 0,
      false,
    );
    for (const sample of this.samples) {
      if (
        sample.elapsedSeconds < start - analyticsEnergyContextSeconds(request.energySmoothingSeconds ?? 0) ||
        sample.elapsedSeconds > end
      )
        continue;
      query.telemetry(
        analyticsTimeUs(sample.elapsedSeconds),
        sample.humanPowerW ?? null,
        sample.motorInputPowerW ?? null,
        sample.controllerSpeedMps ?? null,
        sample.activeInterval === undefined ? 0 : sample.activeInterval,
        `${sample.clockEpoch ?? ''}|${sample.connectionEpoch ?? ''}|${sample.interruptionIndex ?? 0}`,
        sample.timestamp,
      );
    }
    return { ...envelope, status: 'ok', ...(JSON.parse(query.resultJson()) as EnergyPerDistanceResult) };
  }
  async readPlot(request: MonitorPlotRequest): Promise<MonitorPlotResult> {
    const envelope = this.envelope(request.generation);
    if (request.expectedRevision !== envelope.revision) return { ...envelope, status: 'retry' };
    const view = { start: request.startSeconds ?? this.start, end: request.endSeconds ?? this.end };
    const series: Record<string, MonitorPoint[]> = {};
    const buckets = Math.max(
      1,
      Math.min(request.buckets ?? request.pixelWidth ?? 240, Math.floor(4096 / Math.max(1, request.metrics.length))),
    );
    for (const id of request.metrics) {
      if (isDistanceMetric(id) || isConsumptionMetric(id)) {
        series[id] = [];
        continue;
      }
      const metric = metricById(id) ?? new InspectionMetricPolicy(id);
      if (!metric) continue;
      const key = `${id}:${envelope.revision}:${view.start}:${view.end}:${buckets}`;
      let rendered = this.renderCache.get(key);
      if (!rendered) {
        const geometry = plotMonitorItems(id, this.items(id), view.start, view.end, buckets);
        const bytes = JSON.stringify(geometry).length * 2;
        rendered = { series: geometry, bytes };
        while (this.renderCache.size && this.cacheBytes + bytes > CACHE_BYTES) {
          const firstKey = this.renderCache.keys().next().value!;
          this.cacheBytes -= this.renderCache.get(firstKey)!.bytes;
          this.renderCache.delete(firstKey);
        }
        if (bytes <= CACHE_BYTES) {
          this.renderCache.set(key, rendered);
          this.cacheBytes += bytes;
        }
      }
      series[id] = rendered.series;
    }
    return { ...envelope, status: 'ok', series, resolution: 'reduced' };
  }
  async readLatest(
    request: import('./monitor').MonitorLatestRequest,
  ): Promise<import('./monitor').MonitorLatestResult> {
    const points = Object.fromEntries(
      request.metrics.map(id => {
        if (!metricById(id)) return [id, null];
        const point = this.samples.length ? this.point(this.samples.length - 1, id) : null;
        return [id, point && Number.isFinite(point.value) ? point : null];
      }),
    );
    return {
      ...this.envelope(request.generation),
      status: 'ok',
      points,
      ...this.timing(),
      ...(this.live
        ? { liveAcquiredAt: Object.fromEntries(request.metrics.map(id => [id, this.liveAcquiredAt[id] ?? null])) }
        : {}),
    };
  }
  async inspectAt(request: MonitorInspectRequest): Promise<MonitorInspectResult> {
    return this.inspection(request);
  }
  private inspection(request: MonitorInspectRequest): MonitorInspectResult {
    const envelope = this.envelope(request.generation);
    if (request.expectedRevision !== envelope.revision) return { ...envelope, status: 'retry' };
    const points: Record<string, MonitorPoint | null> = {},
      gaps: Record<string, boolean> = {};
    for (const id of request.metrics) {
      if (isDistanceMetric(id) || isConsumptionMetric(id)) {
        points[id] = null;
        gaps[id] = true;
        continue;
      }
      const metric = metricById(id) ?? new InspectionMetricPolicy(id);
      if (!metric) continue;
      const cached = this.prepare(id);
      const anchor = request.anchor?.metric === id ? request.anchor.observationId : undefined;
      const timeUs = analyticsTimeUs(request.seconds);
      const originals = this.candidates(cached, BigInt(timeUs), anchor);
      const tail = cached.valid[cached.valid.length - 1];
      const acquired = this.liveAcquiredAt[id];
      const mapped = acquired == null ? null : this.mapMonotonicSeconds(acquired);
      const trailing =
        this.live &&
        tail !== undefined &&
        inspectionHeldTail(
          id,
          tail.item.observationId,
          tail.item.collectionElapsedSeconds,
          this.point(Number(tail.item.sequence) - 1, id).value,
          tail.item.observationId,
          mapped,
          this.now(),
          request.seconds,
        ) !== null;
      const query = new InspectionMetricQuery(
        id,
        '0',
        analyticsTimeUs(this.evidence.rideEndSeconds ?? this.end),
        analyticsTimeUs(this.evidence.rideEndSeconds ?? this.end),
        this.evidence.lifecycle ?? [],
        new InspectionTargetInput(timeUs, anchor ?? null, anchor ? timeUs : null).withNearbyOriginal(
          id,
          originals.map(original => original.input),
        ),
        null,
        trailing,
      );
      for (const original of originals)
        query.appendPreparedInspection(
          original.input,
          true,
          !this.evidence.lifecycle,
          (cached.interruptions.get(original.item.collectionUs)?.size ?? 0) > 1,
        );
      const selected = query.result().selection;
      const point = selected ? inspectionPoint(selected) : null;
      points[id] = point;
      gaps[id] = point === null;
    }
    return { ...envelope, status: 'ok', seconds: request.seconds, points, gaps };
  }
  async rangeStats(request: MonitorStatsRequest): Promise<MonitorStatsResult> {
    const envelope = this.envelope(request.generation);
    if (request.expectedRevision !== envelope.revision) return { ...envelope, status: 'retry' };
    const statistics: Record<string, MonitorStatistics> = {};
    const start = Math.min(request.startSeconds, request.endSeconds),
      end = Math.max(request.startSeconds, request.endSeconds);
    for (const id of request.metrics) {
      if (isDistanceMetric(id) || isConsumptionMetric(id)) continue;
      const metric = metricById(id) ?? new InspectionMetricPolicy(id);
      if (!metric) continue;
      const reducer = new InspectionMetricQuery(
        id,
        analyticsTimeUs(start),
        analyticsTimeUs(end),
        analyticsTimeUs(this.evidence.rideEndSeconds ?? this.end),
        this.evidence.lifecycle ?? [],
        null,
        null,
        false,
      );
      for (const { item, sample } of this.prepare(id).originals) {
        if (this.evidence.lifecycle) reducer.append(item, true);
        else reducer.appendRetained(item, sample.activeInterval === null ? null : (sample.activeInterval ?? 0));
      }
      const result = reducer.result();
      if (result.outcome.startsWith('numericOverflow')) throw new Error(result.outcome);
      if (result.statistics) statistics[id] = inspectionStatistics(result.statistics);
    }
    const firstEndpoint = request.includeEndpoints
      ? this.inspection({ ...request, seconds: request.startSeconds, anchor: request.startAnchor })
      : null;
    const lastEndpoint = request.includeEndpoints
      ? this.inspection({ ...request, seconds: request.endSeconds, anchor: request.endAnchor })
      : null;
    if ((firstEndpoint && firstEndpoint.status !== 'ok') || (lastEndpoint && lastEndpoint.status !== 'ok'))
      return { ...this.envelope(request.generation), status: 'retry' };
    return {
      ...envelope,
      status: 'ok',
      statistics,
      ...(firstEndpoint?.status === 'ok' && lastEndpoint?.status === 'ok'
        ? { endpoints: { start: firstEndpoint.points, end: lastEndpoint.points } }
        : {}),
    };
  }
  async changesSince(request: MonitorChangesRequest): Promise<MonitorChangesResult> {
    const since = Number(request.sinceRevision),
      revision = this.samples.length;
    const resetRequired =
      !Number.isSafeInteger(since) ||
      since < 0 ||
      since > revision ||
      since < (this.journal[0]?.revision ?? revision + 1) - 1;
    return {
      ...this.envelope(request.generation),
      status: 'ok',
      resetRequired,
      changes: resetRequired ? [] : this.journal.filter(entry => entry.revision > since).map(entry => entry.change),
    };
  }
}
