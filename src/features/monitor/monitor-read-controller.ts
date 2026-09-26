import type { MonitorSnap } from '../../core/monitor-hit-test';
import { clampChartViewport, type ChartViewport } from '../../core/chart-viewport';
import type { MonitorData, MonitorObservationAnchor, MonitorDescribeResult, MonitorEnvelope, MonitorInspectResult, MonitorLatestResult, MonitorPoint, MonitorPlotResult, MonitorRange, MonitorSource, MonitorStatistics, MonitorStatsResult } from '../../core/monitor';
import { ReadCancelled, ReadDeferred, readConsumer, readPressure, reads, type ReadExecutionLane } from '../../services/read-scheduler';

export function rangeViewport(domain: ChartViewport, range: MonitorRange): ChartViewport {
  return { start: range === 'all' ? domain.start : Math.max(domain.start, domain.end - range), end: domain.end };
}

const EMPTY_STATISTICS: Record<string, MonitorStatistics> = {};
const EMPTY_POINTS = {};
function reuseShallow<T extends object>(previous: T | undefined, next: T): T {
  return previous && Object.keys(next).every(key => previous[key as keyof T] === next[key as keyof T])
    && Object.keys(previous).length === Object.keys(next).length ? previous : next;
}
type ReadKind = 'source' | 'latest' | 'changes' | 'plot' | 'cursor' | 'reference' | 'statistics' | 'comparison';
const executionLane = (kind: ReadKind): ReadExecutionLane => kind === 'plot' ? 'plot' : kind === 'statistics' || kind === 'comparison' ? 'statistics' : 'fast';
/** Generations govern publication; the shared scheduler governs actual execution. */
class ReadLane {
  private generation = 0;
  constructor(private readonly consumer: string, private readonly kind: ReadKind) {}
  invalidate() { this.generation++; reads.cancel(this.consumer, this.kind); }
  run<T>(operation: (generation: number) => Promise<T>, accept: (value: T) => void, reject: (error: unknown) => void) {
    const generation = ++this.generation;
    void reads.schedule(executionLane(this.kind), this.consumer, this.kind, () => operation(generation))
      .then(value => { if (generation === this.generation) accept(value); }, error => { if (generation === this.generation) reject(error); });
  }
}

type Successful<T> = Extract<T, { status: 'ok' }>;
type SemanticSnapshot<T> = T & { semanticKey?: string };
type ViewStatistics = SemanticSnapshot<Successful<MonitorStatsResult>> & { viewport: ChartViewport; metrics: string };
export type MonitorReadState = {
  interactionVersion: number;
  description?: MonitorDescribeResult; data?: MonitorData;
  viewport: ChartViewport; nowSeconds: number; following: boolean;
  cursorSeconds: number | null; referenceSeconds: number | null;
  intervalStatistics: Record<string, MonitorStatistics>;
  latest?: Record<string, MonitorPoint | null>; latestRevision?: string;
  latestMetricSources?: MonitorEnvelope['metricSources'];
  deferred: Partial<Record<ReadKind, boolean>>;
  loading: Partial<Record<ReadKind, boolean>>; errors: Partial<Record<ReadKind, string>>;
};

/** The UI owns selection; a committed source revision never owns or resets it. */
export class MonitorReadController {
  private source: MonitorSource;
  private metrics: string[] = [];
  private range: MonitorRange = 'all';
  private pixelWidth = 360;
  private manualView: ChartViewport | null = null;
  private plot?: SemanticSnapshot<Successful<MonitorPlotResult>>;
  private retainedSemanticData?: { data: MonitorData; intervalStatistics: Record<string, MonitorStatistics> };
  private statistics?: ViewStatistics;
  private latest?: SemanticSnapshot<Successful<MonitorLatestResult>>;
  private comparison?: SemanticSnapshot<Successful<MonitorStatsResult>>;
  private comparisonTimer?: ReturnType<typeof setTimeout>;
  private catchupTimer?: ReturnType<typeof setTimeout>;
  private statisticsTimer?: ReturnType<typeof setTimeout>;
  private nextStatisticsAt = 0;
  private nextCatchupAt = 0;
  private unavailablePoints: Record<string, null> = {};
  private selection?: SemanticSnapshot<Successful<MonitorInspectResult>>;
  private reference?: SemanticSnapshot<Successful<MonitorInspectResult>>;
  private cursorAnchor?: MonitorObservationAnchor;
  private cursorResolved = false;
  private referenceAnchor?: MonitorObservationAnchor;
  private receivedAt = 0;
  private disposed = false;
  private active = true;
  private retryScheduled = false;
  private plotView?: ChartViewport;
  private interacting = false;
  private detailTimer?: ReturnType<typeof setTimeout>;
  private interactionEndTimer?: ReturnType<typeof setTimeout>;
  private readonly listeners = new Set<(state: MonitorReadState) => void>();
  private readonly consumer = readConsumer('monitor');
  private readonly lanes = Object.fromEntries((['source', 'latest', 'changes', 'plot', 'cursor', 'reference', 'statistics', 'comparison'] as const).map(kind => [kind, new ReadLane(this.consumer, kind)])) as Record<ReadKind, ReadLane>;
  private state: MonitorReadState = { interactionVersion: 0, viewport: { start: 0, end: 30 }, nowSeconds: 0, following: true, cursorSeconds: null, referenceSeconds: null, intervalStatistics: {}, loading: {}, errors: {}, deferred: {} };
  constructor(source: MonitorSource) { this.source = source; }
  getSnapshot() { return this.state; }
  subscribe(listener: (state: MonitorReadState) => void) { this.listeners.add(listener); return () => { this.listeners.delete(listener); }; }
  activate() { this.disposed = false; this.active = true; this.state = { ...this.state, loading: {} }; }
  deactivate() { this.state = { ...this.state, interactionVersion: this.state.interactionVersion + 1 }; this.active = false; this.clearInteraction(); if (this.catchupTimer) clearTimeout(this.catchupTimer); if (this.statisticsTimer) clearTimeout(this.statisticsTimer); this.statisticsTimer = undefined; this.catchupTimer = undefined; for (const lane of Object.values(this.lanes)) lane.invalidate(); this.state = { ...this.state, loading: {} }; }
  dispose() { this.deactivate(); this.disposed = true; this.listeners.clear(); }
  updateSource(source: MonitorSource) {
    const changed = this.source.semanticKey !== source.semanticKey;
    this.source = source;
    if (!changed) return;
    if (this.state.data) this.retainedSemanticData ??= { data: this.state.data, intervalStatistics: this.state.intervalStatistics };
    for (const lane of Object.values(this.lanes)) lane.invalidate();
    this.clearInteraction();
    this.cursorAnchor = undefined; this.referenceAnchor = undefined;
    this.emit({ interactionVersion: this.state.interactionVersion + 1, loading: {}, errors: {}, deferred: {} });
  }
  configure(metrics: readonly string[], range: MonitorRange, pixelWidth = 360) {
    const changed = metrics.join(',') !== this.metrics.join(',') || range !== this.range || pixelWidth !== this.pixelWidth;
    if (metrics.join(',') !== this.metrics.join(',')) this.unavailablePoints = Object.fromEntries(metrics.map(id => [id, null]));
    this.metrics = [...metrics];
    if (this.cursorAnchor && !metrics.includes(this.cursorAnchor.metric)) this.cursorAnchor = undefined;
    if (this.referenceAnchor && !metrics.includes(this.referenceAnchor.metric)) this.referenceAnchor = undefined;
    this.range = range; this.pixelWidth = pixelWidth;
    if (changed && this.state.description && this.active) { this.state = { ...this.state, interactionVersion: this.state.interactionVersion + 1 }; this.clearInteraction(); this.readLatest(true); this.readGeometry(true); this.readSelections(true); }
  }
  private emit(patch: Partial<MonitorReadState> = {}) {
    if (this.disposed) return;
    this.state = { ...this.state, ...patch }; this.compose();
    for (const listener of this.listeners) listener(this.state);
  }
  private compose() {
    const description = this.state.description, revision = this.plot?.revision;
    this.state = { ...this.state, latest: this.latest?.points, latestRevision: this.latest?.revision, latestMetricSources: this.latest?.metricSources };
    if (!description || !this.plot) { this.state = { ...this.state, data: undefined }; return; }
    // A preference change preserves the complete old drawing/readout snapshot until
    // replacement geometry arrives. Other lanes may complete in either order.
    if (this.retainedSemanticData && this.plot.semanticKey !== this.source.semanticKey) {
      this.state = { ...this.state, data: this.retainedSemanticData.data, intervalStatistics: this.retainedSemanticData.intervalStatistics }; return;
    }
    this.retainedSemanticData = undefined;
    const sameSemantics = (result: { semanticKey?: string } | undefined) => !!result && result.semanticKey === this.plot!.semanticKey;
    // Each exact operation keeps its admitted revision. A/B comparisons require one
    // common original snapshot; geometry can remain visible while a newer lookup finishes.
    const stats = sameSemantics(this.statistics) && this.statistics?.metrics === this.metrics.join(',') ? this.statistics : undefined;
    const statistics = stats?.statistics ?? EMPTY_STATISTICS;
    const unavailable = this.unavailablePoints;
    const selectionPending = this.state.cursorSeconds !== null && (!this.cursorResolved || !sameSemantics(this.selection));
    const selectionResult = sameSemantics(this.selection) && (this.selection?.seconds === this.state.cursorSeconds || selectionPending) ? this.selection : undefined;
    const referenceResult = sameSemantics(this.reference) && this.reference?.seconds === this.state.referenceSeconds ? this.reference : undefined;
    const endpointsMatch = this.state.cursorSeconds === null || this.state.referenceSeconds === null || selectionResult?.revision === referenceResult?.revision;
    const selection = selectionResult?.points ?? (this.state.cursorSeconds === null ? EMPTY_POINTS : unavailable);
    const reference = endpointsMatch ? referenceResult?.points ?? (this.state.referenceSeconds === null ? EMPTY_POINTS : unavailable) : unavailable;
    const intervalStatistics = !selectionPending && selectionResult && referenceResult && selectionResult.revision === referenceResult.revision && sameSemantics(this.comparison) && this.comparison?.revision === selectionResult.revision ? this.comparison.statistics : EMPTY_STATISTICS;
    const domain = this.sameView(this.state.data?.domain, description.domain) ? this.state.data!.domain : description.domain;
    const data = reuseShallow(this.state.data, { sourceId: this.plot.sourceId, revision, startedAt: description.startedAt, domain, nowSeconds: description.nowSeconds,
      metricSources: this.plot.metricSources ?? (this.plot.revision === description.revision ? description.metricSources : undefined),
      series: this.plot.series, plotViewport: this.plotView, plotGeneration: this.plot.generation, latest: sameSemantics(this.latest) ? this.latest?.points : undefined, latestRevision: sameSemantics(this.latest) ? this.latest?.revision : undefined, statistics, statisticsRevision: stats?.revision,
      statisticsViewport: stats?.viewport, statisticsPending: !!stats && (stats.revision !== description.revision || !this.sameView(stats.viewport, this.state.viewport) || !!this.state.loading.statistics),
      selection, reference, selectionRevision: selectionResult?.revision, referenceRevision: endpointsMatch ? referenceResult?.revision : undefined,
      selectionPending, selectionSeconds: selectionResult?.seconds ?? this.state.cursorSeconds ?? undefined, referenceSeconds: this.state.referenceSeconds ?? undefined });
    this.state = { ...this.state, intervalStatistics, data };
  }

  private request<T extends MonitorEnvelope & { status: string }>(kind: ReadKind, operation: (generation: number) => Promise<T>, accept: (result: T, semanticKey?: string) => void) {
    if (!this.active || this.disposed) return;
    const semanticKey = this.source.semanticKey;
    this.emit({ loading: { ...this.state.loading, [kind]: true } });
    this.lanes[kind].run(async generation => {
      const result = await operation(generation);
      if (result.generation !== generation) throw new Error('Source returned an invalid request generation.');
      if ((kind === 'source' || kind === 'changes') && result.status !== 'ok') throw new ReadDeferred();
      return result;
    }, result => {
      if (this.disposed) return;
      this.state = { ...this.state, loading: { ...this.state.loading, [kind]: false }, errors: { ...this.state.errors, [kind]: undefined }, deferred: { ...this.state.deferred, [kind]: false } };
      accept(result, semanticKey); this.emit(); this.scheduleCatchup();
    }, error => {
      if (error instanceof ReadCancelled) return;
      this.emit({ loading: { ...this.state.loading, [kind]: false }, deferred: { ...this.state.deferred, [kind]: readPressure(error) },
        errors: readPressure(error) ? this.state.errors : { ...this.state.errors, [kind]: error instanceof Error ? error.message : String(error) } });
    });
  }
  private current(result: MonitorEnvelope & { status: 'ok' | 'retry' }, admitted = this.state.description) {
    const latest = this.state.description;
    if (result.status === 'retry' || result.sourceId !== latest?.sourceId || result.sourceId !== admitted?.sourceId || result.revision !== admitted.revision) {
      if (!this.retryScheduled) { this.retryScheduled = true; this.refresh(false); }
      return false;
    }
    return true;
  }
  private geometryBusy() { return this.state.loading.plot; }
  private selectionsBusy() { return this.state.loading.cursor || this.state.loading.reference || this.state.loading.comparison; }
  private selectionsBehind() {
    const revision = this.state.description?.revision;
    return (this.state.cursorSeconds !== null && this.selection?.revision !== revision) ||
      (this.state.referenceSeconds !== null && this.reference?.revision !== revision) ||
      (this.state.cursorSeconds !== null && this.state.referenceSeconds !== null && this.comparison?.revision !== revision);
  }
  private scheduleCatchup() {
    if (this.disposed || !this.active || this.interacting || this.catchupTimer || !this.state.description) return;
    const geometryBehind = !this.geometryBusy() && !this.state.loading.changes && !this.state.errors.plot && this.plot?.revision !== this.state.description.revision;
    const exactBehind = !this.selectionsBusy() && !this.state.errors.cursor && !this.state.errors.reference && !this.state.errors.comparison && this.selectionsBehind();
    if (!geometryBehind && !exactBehind) return;
    this.catchupTimer = setTimeout(() => {
      this.catchupTimer = undefined;
      this.nextCatchupAt = performance.now() + 500;
      this.refresh(false);
    }, Math.max(500, this.nextCatchupAt - performance.now()));
  }
  refresh(external = true) {
    if (this.disposed || !this.active) return;
    this.readLatest();
    if (this.state.loading.source) return;
    if (external) this.retryScheduled = false;
    let requestedAt = 0;
    this.request('source', generation => { requestedAt = performance.now() / 1000; return this.source.describeSource({ generation }); }, description => {
      const previous = this.state.description;
      const replaced = previous !== undefined && previous.sourceId !== description.sourceId;
      if (replaced) {
        this.clearInteraction();
        for (const [kind, lane] of Object.entries(this.lanes)) if (kind !== 'source') lane.invalidate();
        this.retainedSemanticData = undefined;
        this.manualView = null; this.plot = undefined; this.statistics = undefined; this.latest = undefined;
        this.selection = undefined; this.reference = undefined; this.comparison = undefined; this.plotView = undefined; this.cursorAnchor = undefined; this.referenceAnchor = undefined;
        this.state = { ...this.state, interactionVersion: this.state.interactionVersion + 1, cursorSeconds: null, referenceSeconds: null, intervalStatistics: {}, loading: {}, errors: {}, deferred: {} };
      }
      this.receivedAt = requestedAt;
      this.state = { ...this.state, description };
      this.updateClock();
      if (!this.latest) this.readLatest();
      if (this.interacting) return;
      // Capture progress never supersedes admitted scans. Each operation can advance
      // independently; a completed statistics snapshot keeps its explicit interval.
      if (!this.plot) this.readGeometry(false);
      else if (this.plot.revision !== description.revision && !this.geometryBusy() && !this.state.loading.changes) {
        const previousPlot = this.plot;
        this.request('changes', generation => this.source.changesSince({ generation, sinceRevision: previousPlot.revision }), changes => {
          if (changes.sourceId !== description.sourceId || changes.sourceId !== this.state.description?.sourceId || this.plot !== previousPlot) return;
          const view = this.state.viewport;
          const intersects = changes.resetRequired || changes.changes.some(change => change.kind === 'semantics' || ((!change.metrics || change.metrics.some(id => this.metrics.includes(id))) && change.endSeconds >= view.start && change.startSeconds <= view.end));
          // A delayed journal still completes its request. If newer capture arrived,
          // query the current snapshot; its older proof cannot promote geometry to R.
          if (changes.revision !== this.state.description.revision || intersects || !this.sameView(this.plotView, view)) this.readGeometry(false);
          else {
            // The journal proves geometry in this fixed window unchanged through R.
            this.plot = { ...previousPlot, revision: changes.revision };
            this.readStatistics(false);
          }
        });
      } else if (!this.sameView(this.plotView, this.state.viewport) || this.state.errors.plot || this.state.deferred.plot) this.readGeometry(false);
      if (!this.state.loading.statistics && (this.statistics?.revision !== description.revision || !this.sameView(this.statistics?.viewport, this.state.viewport) || this.state.errors.statistics || this.state.deferred.statistics)) this.readStatistics(false);
      if (this.selectionsBehind() || this.state.errors.cursor || this.state.errors.reference || this.state.errors.comparison || this.state.deferred.cursor || this.state.deferred.reference || this.state.deferred.comparison) this.readSelections(false);
    });
  }
  private sameView(a: ChartViewport | undefined, b: ChartViewport | undefined) { return !!a && !!b && a.start === b.start && a.end === b.end; }
  tick() { if (this.active && !this.disposed) { this.updateClock(); this.emit(); } }
  private readLatest(force = false) {
    const description = this.state.description;
    if (!description || (!force && this.state.loading.latest)) return;
    const metrics = [...this.metrics];
    this.request('latest', generation => this.source.readLatest({ generation, metrics }), (result, semanticKey) => {
      if (result.status !== 'ok' || result.sourceId !== description.sourceId || result.sourceId !== this.state.description?.sourceId) return;
      if (this.latest && /^\d+$/.test(result.revision) && /^\d+$/.test(this.latest.revision) && BigInt(result.revision) < BigInt(this.latest.revision)) return;
      this.latest = { ...result, semanticKey };
    });
  }
  private updateClock() {
    const description = this.state.description;
    if (!description) return;
    const nowSeconds = (description.nowSeconds ?? description.domain.end) + (this.source.live ? Math.max(0, performance.now() / 1000 - this.receivedAt) : 0);
    const domain = { start: description.domain.start, end: Math.max(description.domain.end, this.source.live ? nowSeconds : description.domain.end) };
    const nextView = this.manualView ? clampChartViewport(this.manualView, domain) : rangeViewport(domain, this.range);
    const viewport = this.sameView(this.state.viewport, nextView) ? this.state.viewport : nextView;
    this.state = { ...this.state, nowSeconds, following: this.manualView === null, viewport };
  }
  private readGeometry(force: boolean, withStatistics = true) {
    const description = this.state.description; if (!description || (!force && this.geometryBusy())) return;
    if (force) {
      this.lanes.changes.invalidate();
      this.state = { ...this.state, loading: { ...this.state.loading, changes: false } };
    }
    this.updateClock();
    const view = this.state.viewport, metrics = [...this.metrics]; let admitted = description;
    this.request('plot', generation => { admitted = this.state.description ?? description; return this.source.readPlot({ generation, expectedRevision: admitted.revision, metrics, startSeconds: view.start, endSeconds: view.end, pixelWidth: this.pixelWidth, buckets: Math.max(1, Math.min(512, Math.round(this.pixelWidth))) }); }, (result, semanticKey) => {
      if (!this.current(result, admitted) || result.status !== 'ok') return;
      this.plot = { ...result, semanticKey }; this.plotView = view;
    });
    if (withStatistics) this.readStatistics(force, description, view);
  }
  private readStatistics(force: boolean, description = this.state.description, view = this.state.viewport) {
    if (this.interacting) return;
    if (!description || (!force && this.state.loading.statistics)) return;
    if (!force && this.statistics && performance.now() < this.nextStatisticsAt) {
      if (!this.statisticsTimer && this.active) this.statisticsTimer = setTimeout(() => { this.statisticsTimer = undefined; this.readStatistics(false); }, this.nextStatisticsAt - performance.now());
      return;
    }
    if (this.statisticsTimer) clearTimeout(this.statisticsTimer); this.statisticsTimer = undefined;
    const metrics = [...this.metrics]; let admitted = description;
    this.request('statistics', generation => { admitted = this.state.description ?? description; return this.source.rangeStats({ generation, expectedRevision: admitted.revision, metrics, startSeconds: view.start, endSeconds: view.end }); }, (result, semanticKey) => {
      if (!this.current(result, admitted) || result.status !== 'ok') return;
      this.statistics = { ...result, semanticKey, viewport: view, metrics: metrics.join(',') };
      this.nextStatisticsAt = performance.now() + 2000;
      if (this.state.description?.revision !== result.revision || !this.sameView(this.state.viewport, view)) this.readStatistics(false);
    });
  }
  private readSelection(kind: 'cursor' | 'reference', description: MonitorDescribeResult) {
    const seconds = kind === 'cursor' ? this.state.cursorSeconds : this.state.referenceSeconds;
    this.lanes[kind].invalidate();
    if (kind === 'cursor') this.cursorResolved = false;
    if (seconds === null) { this.emit({ loading: { ...this.state.loading, [kind]: false } }); return; }
    const metrics = [...this.metrics]; let admitted = description;
    const anchor = kind === 'cursor' ? this.cursorAnchor : this.referenceAnchor;
    this.request(kind, generation => { admitted = this.state.description ?? description; return this.source.inspectAt({ generation, expectedRevision: admitted.revision, metrics, seconds, anchor: anchor && metrics.includes(anchor.metric) ? anchor : undefined }); }, (result, semanticKey) => {
      if (!this.current(result, admitted) || result.status !== 'ok') return;
      if (kind === 'cursor') { this.selection = { ...result, semanticKey }; this.cursorResolved = true; } else this.reference = { ...result, semanticKey };
    });
  }
  private readComparison(description: MonitorDescribeResult) {
    const startSeconds = this.state.referenceSeconds, endSeconds = this.state.cursorSeconds;
    this.lanes.comparison.invalidate();
    if (startSeconds === null || endSeconds === null) { this.comparison = undefined; this.emit({ intervalStatistics: {}, loading: { ...this.state.loading, comparison: false } }); return; }
    const metrics = [...this.metrics]; let admitted = description;
    const forward = startSeconds <= endSeconds;
    const startAnchor = forward ? this.referenceAnchor : this.cursorAnchor, endAnchor = forward ? this.cursorAnchor : this.referenceAnchor;
    this.request('comparison', generation => { admitted = this.state.description ?? description; return this.source.rangeStats({ generation, expectedRevision: admitted.revision, metrics, startSeconds: Math.min(startSeconds, endSeconds), endSeconds: Math.max(startSeconds, endSeconds), includeEndpoints: true, startAnchor, endAnchor }); }, (result, semanticKey) => {
      if (!this.current(result, admitted) || result.status !== 'ok') return;
      this.comparison = { ...result, semanticKey };
      if (result.endpoints) {
        const forward = startSeconds <= endSeconds;
        this.reference = { ...result, semanticKey, seconds: startSeconds, points: forward ? result.endpoints.start : result.endpoints.end, gaps: {} };
        this.cursorResolved = true;
        this.selection = { ...result, semanticKey, seconds: endSeconds, points: forward ? result.endpoints.end : result.endpoints.start, gaps: {} };
      }
    });
  }
  private readSelections(force: boolean) {
    const description = this.state.description;
    if (!description || (!force && this.selectionsBusy())) return;
    this.readSelection('cursor', description); this.readSelection('reference', description); this.readComparison(description);
  }
  inspect(seconds: number | null, interacting = false, snap?: MonitorSnap | null) {
    if (!this.active || this.disposed) return;
    if (seconds !== null) this.manualView ??= this.state.viewport;
    // Retained geometry remains interactive by time while its replacement loads,
    // but its source-specific value and anchor cannot enter the new interpretation.
    const validSnap = seconds !== null && snap && this.plot?.semanticKey === this.source.semanticKey && snap.revision === this.plot?.revision && snap.sourceId === this.state.description?.sourceId && snap.point.elapsedSeconds === seconds && this.metrics.includes(snap.metric) && snap.point.observationId ? snap : null;
    this.cursorResolved = false;
    this.cursorAnchor = validSnap ? { metric: validSnap.metric, observationId: validSnap.point.observationId! } : undefined;
    if (seconds === null) this.selection = undefined;
    // A plotted candidate is itself an original. Preserve all original metadata
    // while native lookup verifies its identity against the admitted snapshot.
    if (validSnap && validSnap.revision === this.state.description?.revision) {
      this.selection = { semanticKey: this.source.semanticKey, status: 'ok', generation: 0, sourceId: validSnap.sourceId, revision: validSnap.revision, seconds: validSnap.point.elapsedSeconds,
        points: { ...(this.selection?.revision === validSnap.revision ? this.selection.points : {}), [validSnap.metric]: validSnap.point }, gaps: {} };
    }
    this.comparison = undefined;
    this.cancelComparison();
    this.emit({ cursorSeconds: seconds, following: this.manualView === null, intervalStatistics: {}, loading: { ...this.state.loading, cursor: seconds !== null && !!this.state.description } });
    const description = this.state.description;
    if (!description) return;
    this.readSelection('cursor', description);
    // A is stationary while B moves. Keep its admitted read instead of
    // cancelling and issuing it again for every pointer position.
    if (this.state.referenceSeconds !== null && !this.state.loading.reference && this.reference?.revision !== description.revision) this.readSelection('reference', description);
    if (seconds === null || this.state.referenceSeconds === null) return;
    if (interacting) {
      this.emit({ loading: { ...this.state.loading, comparison: true } });
      this.comparisonTimer = setTimeout(() => {
        this.comparisonTimer = undefined;
        if (this.active && this.state.description) this.readComparison(this.state.description);
      }, 160);
    } else this.readComparison(description);
  }
  setReference(seconds: number | null) { this.referenceAnchor = seconds !== null && seconds === this.state.cursorSeconds ? this.cursorAnchor : undefined; this.cancelComparison(); this.reference = undefined; this.comparison = undefined; this.emit({ referenceSeconds: seconds, intervalStatistics: {} }); this.readSelections(true); }
  private cancelComparison() {
    if (this.comparisonTimer) clearTimeout(this.comparisonTimer);
    this.comparisonTimer = undefined;
    this.lanes.comparison.invalidate();
    this.state = { ...this.state, loading: { ...this.state.loading, comparison: false } };
  }
  private clearInteraction() {
    this.cancelComparison();
    this.interacting = false;
    if (this.detailTimer) clearTimeout(this.detailTimer);
    if (this.interactionEndTimer) clearTimeout(this.interactionEndTimer);
    this.detailTimer = undefined; this.interactionEndTimer = undefined;
  }
  changeViewport(view: ChartViewport, interacting = false) {
    if (!this.active || this.disposed) return;
    const description = this.state.description; if (!description) return;
    this.manualView = clampChartViewport(view, { start: description.domain.start, end: Math.max(description.domain.end, this.source.live ? this.state.nowSeconds : description.domain.end) });
    this.updateClock();
    if (interacting) {
      if (!this.interacting) {
        if (this.statisticsTimer) clearTimeout(this.statisticsTimer);
        this.statisticsTimer = undefined;
        this.lanes.statistics.invalidate();
        this.state = { ...this.state, loading: { ...this.state.loading, statistics: false } };
      }
      this.interacting = true;
      // Move the retained drawing immediately; never issue one database cohort per touch.
      if (!this.detailTimer) this.detailTimer = setTimeout(() => {
        this.detailTimer = undefined; this.readGeometry(true, false);
      }, 120);
      if (this.interactionEndTimer) clearTimeout(this.interactionEndTimer);
      this.interactionEndTimer = setTimeout(() => this.changeViewport(this.state.viewport), 200);
    } else { this.clearInteraction(); this.readGeometry(true); }
    this.emit();
  }
  resume() { this.state = { ...this.state, interactionVersion: this.state.interactionVersion + 1 }; this.clearInteraction(); this.manualView = null; this.selection = undefined; this.cursorAnchor = undefined; this.lanes.cursor.invalidate(); this.lanes.comparison.invalidate(); this.emit({ cursorSeconds: null, following: true, intervalStatistics: {}, loading: { ...this.state.loading, cursor: false, comparison: false } }); this.readGeometry(true); }
}
