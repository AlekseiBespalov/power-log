import { decimateChart, findChartSample } from './chart';
import { chartVisibleRange } from './chart-viewport';
import { metricById, metricAllowsMean, MONITOR_METRICS, type MonitorChange, type MonitorChangesRequest, type MonitorChangesResult, type MonitorDescribeRequest, type MonitorDescribeResult, type MonitorInspectRequest, type MonitorInspectResult, type MonitorPlotRequest, type MonitorPlotResult, type MonitorPoint, type MonitorSource, type MonitorStatistics, type MonitorStatsRequest, type MonitorStatsResult } from './monitor';
import type { TelemetrySample } from './types';

const CACHE_BYTES = 4 * 1024 * 1024;
const JOURNAL_ENTRIES = 256;
const pointOrder = (a: MonitorPoint, b: MonitorPoint) => a.elapsedSeconds - b.elapsedSeconds || (a.observationId ?? '').localeCompare(b.observationId ?? '');
const originalRange = (points: readonly MonitorPoint[], view: { start: number; end: number }) => {
  const range = chartVisibleRange(points, view);
  while (range.start > 0 && points[range.start - 1]!.elapsedSeconds === points[range.start]!.elapsedSeconds) range.start--;
  while (range.end < points.length && points[range.end]!.elapsedSeconds <= view.end) range.end++;
  return range;
};

/** Browser and imported CSV analysis. Originals and their identities survive every read. */
export class TelemetryMonitor implements MonitorSource {
  private samples: TelemetrySample[] = [];
  private cache = new Map<string, { processed: number; points: MonitorPoint[] }>();
  private renderCache = new Map<string, { series: MonitorPoint[]; bytes: number }>();
  private cacheBytes = 0;
  private available = new Set<string>();
  private journal: { revision: number; change: MonitorChange }[] = [];
  private session = 0;
  private start = 0;
  private end = 0;
  private readonly emptyStartedAt = new Date().toISOString();
  constructor(readonly key: string, readonly live: boolean, samples: readonly TelemetrySample[] = []) { for (const sample of samples) this.append(sample); }
  beginSession() {
    this.session++; this.samples = []; this.cache.clear(); this.renderCache.clear(); this.cacheBytes = 0;
    this.available.clear(); this.journal = []; this.start = 0; this.end = 0;
  }
  private get sourceId() { return this.session ? `${this.key}:session:${this.session}` : this.key; }
  append(sample: TelemetrySample) {
    this.samples.push(sample);
    const time = this.time(sample), previousEnd = this.end, previousStart = this.start;
    if (this.samples.length === 1) this.start = this.end = time;
    else { this.start = Math.min(this.start, time); this.end = Math.max(this.end, time); }
    const metrics = MONITOR_METRICS.filter(({ id }) => typeof sample[id as keyof TelemetrySample] === 'number').map(({ id }) => id);
    for (const id of metrics) this.available.add(id);
    this.journal.push({ revision: this.samples.length, change: { startSeconds: this.samples.length === 1 ? time : time < previousEnd ? Math.min(previousStart, time) : previousEnd, endSeconds: Math.max(time, previousEnd), metrics, kind: time < previousEnd ? 'correction' : 'append' } });
    if (this.journal.length > JOURNAL_ENTRIES) this.journal.shift();
  }
  private time(sample: TelemetrySample) { return this.live ? (Date.parse(sample.timestamp) - Date.parse(this.samples[0]!.timestamp)) / 1000 : sample.elapsedSeconds; }
  private envelope(generation: number) { return { generation, sourceId: this.sourceId, revision: String(this.samples.length) }; }
  private points(id: string) {
    const cached = this.cache.get(id) ?? { processed: 0, points: [] };
    let needsSort = false;
    for (let i = cached.processed; i < this.samples.length; i++) {
      const sample = this.samples[i]!, value = sample[id as keyof TelemetrySample], elapsedSeconds = this.time(sample);
      if (typeof value !== 'number' || !Number.isFinite(value) || !Number.isFinite(elapsedSeconds)) continue;
      if (cached.points.length && elapsedSeconds <= cached.points[cached.points.length - 1]!.elapsedSeconds) needsSort = true;
      cached.points.push({ elapsedSeconds, timestamp: sample.timestamp, value, observationId: `${this.sourceId}:${String(i).padStart(16, '0')}` });
    }
    if (needsSort) cached.points.sort(pointOrder);
    cached.processed = this.samples.length; this.cache.set(id, cached); return cached.points;
  }
  async describeSource({ generation }: MonitorDescribeRequest): Promise<MonitorDescribeResult> {
    const first = this.samples[0];
    const startedAt = first ? new Date(Date.parse(first.timestamp) - (this.live ? 0 : first.elapsedSeconds) * 1000).toISOString() : this.emptyStartedAt;
    return { ...this.envelope(generation), status: 'ok', startedAt, domain: { start: this.start, end: Math.max(this.start + 10, this.end) }, nowSeconds: this.live ? Math.max(this.end, (Date.now() - Date.parse(startedAt)) / 1000) : this.end, availableMetrics: [...this.available], outcome: this.samples.length ? 'available' : this.live ? 'pending' : 'unavailable', warnings: [] };
  }
  async readPlot(request: MonitorPlotRequest): Promise<MonitorPlotResult> {
    const envelope = this.envelope(request.generation);
    if (request.expectedRevision !== envelope.revision) return { ...envelope, status: 'retry' };
    const view = { start: request.startSeconds ?? this.start, end: request.endSeconds ?? this.end };
    const series: Record<string, MonitorPoint[]> = {}, latest: Record<string, MonitorPoint | null> = {};
    // Geometry is byte bounded; originals remain in the separate local source store.
    const buckets = Math.max(1, Math.min(request.buckets ?? request.pixelWidth ?? 240, Math.floor(4096 / Math.max(1, request.metrics.length))));
    for (const id of request.metrics) {
      const metric = metricById(id); if (!metric) continue;
      const points = this.points(id), key = `${id}:${envelope.revision}:${view.start}:${view.end}:${buckets}`;
      let rendered = this.renderCache.get(key);
      if (!rendered) {
        const range = originalRange(points, view), visible = points.slice(range.start, range.end);
        const geometry = decimateChart(visible, point => point.value, buckets, metric.gapSeconds).map(point => ({ ...visible[point.sampleIndex]!, startsSegment: point.startsSegment }));
        const bytes = JSON.stringify(geometry).length * 2;
        rendered = { series: geometry, bytes };
        while (this.renderCache.size && this.cacheBytes + bytes > CACHE_BYTES) {
          const firstKey = this.renderCache.keys().next().value!;
          this.cacheBytes -= this.renderCache.get(firstKey)!.bytes; this.renderCache.delete(firstKey);
        }
        if (bytes <= CACHE_BYTES) { this.renderCache.set(key, rendered); this.cacheBytes += bytes; }
      }
      series[id] = rendered.series; latest[id] = points[points.length - 1] ?? null;
    }
    return { ...envelope, status: 'ok', series, latest, resolution: 'reduced' };
  }
  async readLatest(request: import('./monitor').MonitorLatestRequest): Promise<import('./monitor').MonitorLatestResult> {
    const points = Object.fromEntries(request.metrics.map(id => { const originals = this.points(id); return [id, originals[originals.length - 1] ?? null]; }));
    return { ...this.envelope(request.generation), status: 'ok', points };
  }
  async inspectAt(request: MonitorInspectRequest): Promise<MonitorInspectResult> {
    return this.inspection(request);
  }
  private inspection(request: MonitorInspectRequest): MonitorInspectResult {
    const envelope = this.envelope(request.generation);
    if (request.expectedRevision !== envelope.revision) return { ...envelope, status: 'retry' };
    const points: Record<string, MonitorPoint | null> = {}, gaps: Record<string, boolean> = {};
    for (const id of request.metrics) {
      const metric = metricById(id); if (!metric) continue;
      if (request.anchor?.metric === id) {
        // Source IDs encode the original append index: anchored lookup stays O(1)
        // even when a reduced chart represents hundreds of thousands of rows.
        const prefix = `${this.sourceId}:`, identity = request.anchor.observationId;
        const suffix = identity.startsWith(prefix) ? identity.slice(prefix.length) : '';
        const index = /^\d{16}$/.test(suffix) ? Number(suffix) : -1;
        const sample = Number.isSafeInteger(index) ? this.samples[index] : undefined;
        const value = sample?.[id as keyof TelemetrySample], elapsedSeconds = sample ? this.time(sample) : NaN;
        const point: MonitorPoint | null = sample && typeof value === 'number' && Number.isFinite(value) && Math.abs(elapsedSeconds - request.seconds) <= 1e-6
          ? { elapsedSeconds, timestamp: sample.timestamp, value, observationId: identity } : null;
        points[id] = point; gaps[id] = point === null; continue;
      }
      const originals = this.points(id);
      // Pointer-to-time arithmetic can land a fraction of a microsecond beyond a gap endpoint.
      // Snap only that numerical tolerance to an original, without rounding its stored time.
      let low = 0, high = originals.length;
      while (low < high) { const middle = (low + high) >>> 1; if (originals[middle]!.elapsedSeconds < request.seconds) low = middle + 1; else high = middle; }
      const endpoint = [originals[low - 1], originals[low]].filter((point): point is MonitorPoint => !!point && Math.abs(point.elapsedSeconds - request.seconds) <= 1e-6)
        .sort((a, b) => Math.abs(a.elapsedSeconds - request.seconds) - Math.abs(b.elapsedSeconds - request.seconds) || pointOrder(a, b))[0];
      const selected = findChartSample(originals, endpoint?.elapsedSeconds ?? request.seconds, metric.gapSeconds);
      const tail = originals[originals.length - 1];
      let point = selected.sample ?? (this.live && tail && request.seconds > tail.elapsedSeconds && request.seconds - tail.elapsedSeconds < metric.gapSeconds ? tail : null);
      // The neighboring earlier point can be the last of several equal-time originals.
      if (point) { const first = findChartSample(originals, point.elapsedSeconds, metric.gapSeconds); point = first.sample; }
      points[id] = point; gaps[id] = point === null;
    }
    return { ...envelope, status: 'ok', seconds: request.seconds, points, gaps };
  }
  async rangeStats(request: MonitorStatsRequest): Promise<MonitorStatsResult> {
    const envelope = this.envelope(request.generation);
    if (request.expectedRevision !== envelope.revision) return { ...envelope, status: 'retry' };
    const statistics: Record<string, MonitorStatistics> = {};
    const start = Math.min(request.startSeconds, request.endSeconds), end = Math.max(request.startSeconds, request.endSeconds);
    for (const id of request.metrics) {
      const metric = metricById(id); if (!metric) continue;
      const points = this.points(id), range = originalRange(points, { start, end }), visible = points.slice(range.start, range.end);
      const selected = visible.filter(point => point.elapsedSeconds >= start && point.elapsedSeconds <= end);
      if (!selected.length) continue;
      let coveredSeconds = 0, integral = 0;
      const timeline = visible.filter((point, i) => i === 0 || point.elapsedSeconds !== visible[i - 1]!.elapsedSeconds);
      for (let i = 1; i < timeline.length; i++) {
        const a = timeline[i - 1]!, b = timeline[i]!, dt = b.elapsedSeconds - a.elapsedSeconds;
        const integrationGap = id === 'humanPowerW' || id === 'cadenceRpm' ? 2.5 : id === 'heartRateBpm' ? 10 : metric.gapSeconds;
        if (dt <= 0 || dt > integrationGap) continue;
        const left = Math.max(start, a.elapsedSeconds), right = Math.min(end, b.elapsedSeconds);
        if (right <= left) continue;
        const valueAt = (time: number) => a.value + (b.value - a.value) * (time - a.elapsedSeconds) / dt;
        coveredSeconds += right - left;
        integral += metric.kind === 'step' ? a.value * (right - left) : (valueAt(left) + valueAt(right)) * (right - left) / 2;
      }
      statistics[id] = { min: selected.reduce((a, b) => b.value < a.value ? b : a), max: selected.reduce((a, b) => b.value > a.value ? b : a), count: selected.length, coveredSeconds, ...(metricAllowsMean(metric) ? { sampleMean: selected.reduce((sum, point) => sum + point.value, 0) / selected.length, integral } : {}) };
    }
    const firstEndpoint = request.includeEndpoints ? this.inspection({ ...request, seconds: request.startSeconds, anchor: request.startAnchor }) : null;
    const lastEndpoint = request.includeEndpoints ? this.inspection({ ...request, seconds: request.endSeconds, anchor: request.endAnchor }) : null;
    if ((firstEndpoint && firstEndpoint.status !== 'ok') || (lastEndpoint && lastEndpoint.status !== 'ok')) return { ...this.envelope(request.generation), status: 'retry' };
    return { ...envelope, status: 'ok', statistics, ...(firstEndpoint?.status === 'ok' && lastEndpoint?.status === 'ok' ? { endpoints: { start: firstEndpoint.points, end: lastEndpoint.points } } : {}) };
  }
  async changesSince(request: MonitorChangesRequest): Promise<MonitorChangesResult> {
    const since = Number(request.sinceRevision), revision = this.samples.length;
    const resetRequired = !Number.isSafeInteger(since) || since < 0 || since > revision || since < (this.journal[0]?.revision ?? revision + 1) - 1;
    return { ...this.envelope(request.generation), status: 'ok', resetRequired, changes: resetRequired ? [] : this.journal.filter(entry => entry.revision > since).map(entry => entry.change) };
  }
}
