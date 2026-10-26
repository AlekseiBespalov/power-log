import { energyAverageInterval, isEnergyPerDistanceMetric } from '../../core/energy-per-distance';
import { InspectionRequestGate, InspectionRequestContext, inspectionContextsMatch } from '../../../shared/js';
import type { MonitorSnap } from '../../core/monitor-hit-test';
import { isVoltageMetric, reuseShallow } from '../../core/monitor-chart';
import { presentFailure } from '../../core/failures';
import { clampChartViewport, type ChartViewport } from '../../core/chart-viewport';
import {
  monitorQueryMetrics,
  type MonitorData,
  type MonitorDrawing,
  type MonitorObservationAnchor,
  type MonitorDescribeResult,
  type MonitorEnvelope,
  type MonitorInspectResult,
  type MonitorLatestResult,
  type MonitorPoint,
  type MonitorPlotResult,
  type MonitorRange,
  type MonitorSource,
  type MonitorStatistics,
  type MonitorStatisticsState,
  type MonitorStatsResult,
} from '../../core/monitor';
import {
  ReadCancelled,
  ReadDeferred,
  readConsumer,
  readPressure,
  reads,
  type ReadExecutionLane,
} from '../../services/read-scheduler';

export function rangeViewport(domain: ChartViewport, range: MonitorRange): ChartViewport {
  return { start: range === 'all' ? domain.start : Math.max(domain.start, domain.end - range), end: domain.end };
}

const EMPTY_STATISTICS: Record<string, MonitorStatistics> = {};
const EMPTY_POINTS = {};
const samePoint = (a: MonitorPoint | null | undefined, b: MonitorPoint | null | undefined) =>
  a === b ||
  (!!a &&
    !!b &&
    Object.is(a.elapsedSeconds, b.elapsedSeconds) &&
    Object.is(a.value, b.value) &&
    a.timestamp === b.timestamp &&
    a.exactValue === b.exactValue &&
    a.observationId === b.observationId &&
    a.startsSegment === b.startsSegment &&
    a.derived === b.derived);
const sameSeries = (a: readonly MonitorPoint[], b: readonly MonitorPoint[]) =>
  a === b || (a.length === b.length && a.every((point, index) => samePoint(point, b[index])));
const sameStatistics = (a: MonitorStatistics, b: MonitorStatistics) =>
  Object.keys(a).length === Object.keys(b).length &&
  (Object.keys(a) as (keyof MonitorStatistics)[]).every(key =>
    key === 'min' || key === 'max' ? samePoint(a[key], b[key]) : Object.is(a[key], b[key]),
  );
function sharePoints(
  previous: Record<string, MonitorPoint | null> | undefined,
  next: Record<string, MonitorPoint | null>,
): Record<string, MonitorPoint | null> {
  if (!previous) return next;
  const shared = Object.fromEntries(
    Object.entries(next).map(([metric, point]) => [
      metric,
      samePoint(previous[metric], point) ? previous[metric]! : point,
    ]),
  );
  return reuseShallow(previous, shared);
}
type ReadKind = 'source' | 'latest' | 'changes' | 'plot' | 'cursor' | 'reference' | 'statistics' | 'comparison';
const executionLane = (kind: ReadKind): ReadExecutionLane =>
  kind === 'plot' ? 'plot' : kind === 'statistics' || kind === 'comparison' ? 'statistics' : 'fast';
/** Generations govern publication; the shared scheduler governs actual execution. */
class ReadLane {
  private gate = new InspectionRequestGate();
  constructor(
    private readonly consumer: string,
    private readonly kind: ReadKind,
  ) {}
  invalidate() {
    this.gate.cancel();
    reads.cancel(this.consumer, this.kind);
  }
  run<T extends MonitorEnvelope>(
    context: InspectionRequestContext,
    operation: (generation: number, signal: AbortSignal) => Promise<T>,
    accept: (value: T) => void,
    reject: (error: unknown) => void,
  ) {
    const ticket = this.gate.begin(context);
    const generation = Number(ticket.generation);
    void reads
      .schedule(executionLane(this.kind), this.consumer, this.kind, signal => operation(generation, signal))
      .then(
        value => {
          if (this.gate.acceptsGeneration(ticket, String(value.generation))) accept(value);
        },
        error => {
          if (this.gate.accepts(ticket)) reject(error);
        },
      );
  }
}

type Successful<T> = Extract<T, { status: 'ok' }>;
type SemanticSnapshot<T> = T & { semanticKey?: string };
type ViewStatistics = SemanticSnapshot<Successful<MonitorStatsResult>> & { viewport: ChartViewport; metrics: string };
type PlotSnapshot = SemanticSnapshot<Successful<MonitorPlotResult>> & { metrics: string[] };
/** `confirmed` is the newest plot revision at which these points are known to be unchanged. */
type Drawing = {
  points: MonitorPoint[];
  revision: string;
  generation: number;
  viewport: ChartViewport;
  confirmed: string;
  semanticKey?: string;
  sourceId: string;
  presented?: MonitorDrawing;
};
type StatisticsEntry = {
  value: MonitorStatistics;
  revision: string;
  viewport: ChartViewport;
  semanticKey?: string;
  sourceId: string;
  presented?: MonitorStatisticsState;
};
export type MonitorReadState = {
  interactionVersion: number;
  description?: MonitorDescribeResult;
  data?: MonitorData;
  viewport: ChartViewport;
  nowSeconds: number;
  monotonicAt: number;
  liveAcquiredAt: Record<string, number | null>;
  mapMonotonicSeconds?: MonitorSource['mapMonotonicSeconds'];
  following: boolean;
  cursorSeconds: number | null;
  referenceSeconds: number | null;
  intervalStatistics: Record<string, MonitorStatistics>;
  latest?: Record<string, MonitorPoint | null>;
  averages?: Record<string, MonitorPoint | null>;
  latestRevision?: string;
  latestMetricSources?: MonitorEnvelope['metricSources'];
  deferred: Partial<Record<ReadKind, boolean>>;
  loading: Partial<Record<ReadKind, boolean>>;
  errors: Partial<Record<ReadKind, string>>;
};

/** The UI owns selection; a committed source revision never owns or resets it. */
export class MonitorReadController {
  private source: MonitorSource;
  private configured: string[] = [];
  private metrics: string[] = [];
  private range: MonitorRange = 'all';
  private pixelWidth = 360;
  private manualView: ChartViewport | null = null;
  private visible: string[] | null = null;
  private plot?: PlotSnapshot;
  private drawings: Record<string, Drawing> = {};
  private retainedSemanticData?: { data: MonitorData; intervalStatistics: Record<string, MonitorStatistics> };
  private statistics?: ViewStatistics;
  private statisticsEntries: Record<string, StatisticsEntry> = {};
  private latest?: SemanticSnapshot<Successful<MonitorLatestResult>>;
  private comparison?: SemanticSnapshot<Successful<MonitorStatsResult>>;
  private comparisonTimer?: ReturnType<typeof setTimeout>;
  private catchupTimer?: ReturnType<typeof setTimeout>;
  private statisticsTimer?: ReturnType<typeof setTimeout>;
  private averagesTimer?: ReturnType<typeof setTimeout>;
  private visibleTimer?: ReturnType<typeof setTimeout>;
  private nextStatisticsAt = 0;
  private nextCatchupAt = 0;
  private unavailablePoints: Record<string, null> = {};
  private selection?: SemanticSnapshot<Successful<MonitorInspectResult>>;
  private reference?: SemanticSnapshot<Successful<MonitorInspectResult>>;
  private cursorAnchor?: MonitorObservationAnchor;
  private cursorResolved = false;
  private referenceAnchor?: MonitorObservationAnchor;
  private liveClock?: { seconds: number; monotonicAt: number };
  private liveObservations: Record<string, number> = {};
  private disposed = false;
  private active = true;
  private retryScheduled = false;
  private plotView?: ChartViewport;
  private interacting = false;
  private detailTimer?: ReturnType<typeof setTimeout>;
  private interactionEndTimer?: ReturnType<typeof setTimeout>;
  private readonly listeners = new Set<(state: MonitorReadState) => void>();
  private readonly consumer = readConsumer('monitor');
  private readonly lanes = Object.fromEntries(
    (['source', 'latest', 'changes', 'plot', 'cursor', 'reference', 'statistics', 'comparison'] as const).map(kind => [
      kind,
      new ReadLane(this.consumer, kind),
    ]),
  ) as Record<ReadKind, ReadLane>;
  private state: MonitorReadState = {
    interactionVersion: 0,
    viewport: { start: 0, end: 30 },
    nowSeconds: 0,
    monotonicAt: 0,
    liveAcquiredAt: {},
    following: true,
    cursorSeconds: null,
    referenceSeconds: null,
    intervalStatistics: {},
    loading: {},
    errors: {},
    deferred: {},
  };
  constructor(source: MonitorSource) {
    this.source = source;
  }
  getSnapshot() {
    return this.state;
  }
  subscribe(listener: (state: MonitorReadState) => void) {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  }
  activate() {
    this.disposed = false;
    this.active = true;
    this.state = { ...this.state, loading: {} };
    this.updateClock();
    this.emit();
  }
  deactivate() {
    this.state = { ...this.state, interactionVersion: this.state.interactionVersion + 1 };
    this.active = false;
    this.clearInteraction();
    if (this.catchupTimer) clearTimeout(this.catchupTimer);
    if (this.statisticsTimer) clearTimeout(this.statisticsTimer);
    if (this.averagesTimer) clearTimeout(this.averagesTimer);
    if (this.visibleTimer) clearTimeout(this.visibleTimer);
    this.statisticsTimer = undefined;
    this.catchupTimer = undefined;
    this.averagesTimer = undefined;
    this.visibleTimer = undefined;
    for (const lane of Object.values(this.lanes)) lane.invalidate();
    this.state = { ...this.state, loading: {} };
  }
  dispose() {
    this.deactivate();
    this.disposed = true;
    this.listeners.clear();
  }
  updateSource(source: MonitorSource) {
    const replaced = this.source.key !== source.key;
    const changed = this.source.semanticKey !== source.semanticKey;
    this.source = source;
    if (replaced) {
      this.resetSource();
      this.emit();
      return;
    }
    if (!changed) return;
    if (this.state.data)
      this.retainedSemanticData ??= { data: this.state.data, intervalStatistics: this.state.intervalStatistics };
    for (const lane of Object.values(this.lanes)) lane.invalidate();
    this.clearInteraction();
    this.cursorAnchor = undefined;
    this.referenceAnchor = undefined;
    this.emit({ interactionVersion: this.state.interactionVersion + 1, loading: {}, errors: {}, deferred: {} });
  }
  private resetSource(keepDescription = false) {
    for (const [kind, lane] of Object.entries(this.lanes)) if (!keepDescription || kind !== 'source') lane.invalidate();
    this.clearInteraction();
    if (this.catchupTimer) clearTimeout(this.catchupTimer);
    if (this.statisticsTimer) clearTimeout(this.statisticsTimer);
    if (this.averagesTimer) clearTimeout(this.averagesTimer);
    if (this.visibleTimer) clearTimeout(this.visibleTimer);
    this.catchupTimer = undefined;
    this.statisticsTimer = undefined;
    this.averagesTimer = undefined;
    this.visibleTimer = undefined;
    this.nextCatchupAt = 0;
    this.nextStatisticsAt = 0;
    this.retryScheduled = false;
    this.liveClock = undefined;
    this.liveObservations = {};
    this.retainedSemanticData = undefined;
    this.manualView = null;
    this.plot = undefined;
    this.drawings = {};
    this.statistics = undefined;
    this.statisticsEntries = {};
    this.latest = undefined;
    this.selection = undefined;
    this.reference = undefined;
    this.comparison = undefined;
    this.plotView = undefined;
    this.cursorAnchor = undefined;
    this.cursorResolved = false;
    this.referenceAnchor = undefined;
    this.state = {
      interactionVersion: this.state.interactionVersion + 1,
      viewport: { start: 0, end: 30 },
      nowSeconds: 0,
      monotonicAt: 0,
      liveAcquiredAt: {},
      following: true,
      cursorSeconds: null,
      referenceSeconds: null,
      intervalStatistics: {},
      loading: {},
      errors: {},
      deferred: {},
    };
  }
  configure(metrics: readonly string[], range: MonitorRange, pixelWidth = 360) {
    const changed =
      metrics.join(',') !== this.configured.join(',') || range !== this.range || pixelWidth !== this.pixelWidth;
    this.configured = [...metrics];
    this.resolveMetrics(this.state.description);
    this.range = range;
    this.pixelWidth = pixelWidth;
    if (changed && this.state.description && this.active) {
      this.state = { ...this.state, interactionVersion: this.state.interactionVersion + 1 };
      this.clearInteraction();
      this.readLatest(true);
      this.readGeometry(true);
      this.readSelections(true);
    }
  }
  private resolveMetrics(description: MonitorDescribeResult | undefined) {
    const metrics = monitorQueryMetrics(this.configured, description?.availableMetrics);
    if (metrics.join(',') === this.metrics.join(',')) return false;
    this.unavailablePoints = Object.fromEntries(metrics.map(id => [id, null]));
    this.metrics = metrics;
    if (this.cursorAnchor && !metrics.includes(this.cursorAnchor.metric)) this.cursorAnchor = undefined;
    if (this.referenceAnchor && !metrics.includes(this.referenceAnchor.metric)) this.referenceAnchor = undefined;
    return true;
  }
  /** Plots and range statistics cover these metrics; `null` reads every selected metric. */
  setVisibleMetrics(metrics: readonly string[] | null) {
    const next = metrics && [...new Set(metrics)];
    if ((next?.join(',') ?? null) === (this.visible?.join(',') ?? null)) return;
    this.visible = next;
    if (this.visibleTimer || !this.state.description || !this.active || this.disposed) return;
    this.visibleTimer = setTimeout(() => {
      this.visibleTimer = undefined;
      this.readVisible();
    }, 100);
  }
  private plotMetrics() {
    return this.visible ? this.metrics.filter(id => this.visible!.includes(id)) : [...this.metrics];
  }
  private statisticsMetrics(metrics = this.plotMetrics()) {
    return this.source.live && this.manualView === null ? metrics.filter(isVoltageMetric) : metrics;
  }
  private isCurrent(drawing: Drawing | undefined) {
    return (
      !!drawing &&
      !!this.plot &&
      drawing.confirmed === this.plot.revision &&
      drawing.sourceId === this.plot.sourceId &&
      drawing.semanticKey === this.source.semanticKey &&
      this.sameView(drawing.viewport, this.plotView)
    );
  }
  private covers() {
    return this.plotMetrics().every(metric => this.isCurrent(this.drawings[metric]));
  }
  private statisticsCovered() {
    const viewport = this.statistics?.viewport;
    return this.statisticsMetrics().every(metric => {
      const entry = this.statisticsEntries[metric];
      return (
        !!entry &&
        entry.semanticKey === this.source.semanticKey &&
        entry.revision === this.statistics?.revision &&
        this.sameView(entry.viewport, viewport)
      );
    });
  }
  private readVisible() {
    if (!this.active || this.disposed || this.interacting || !this.state.description) return;
    if (this.plotMetrics().length && !this.covers()) this.readGeometry(false, false);
    if (!this.statisticsCovered()) this.readStatistics(true);
    this.emit();
  }
  private draw(snapshot: PlotSnapshot, view: ChartViewport) {
    for (const metric of snapshot.metrics) {
      const points = snapshot.series[metric] ?? [];
      const previous = this.drawings[metric];
      if (
        previous &&
        previous.sourceId === snapshot.sourceId &&
        previous.semanticKey === snapshot.semanticKey &&
        this.sameView(previous.viewport, view) &&
        sameSeries(previous.points, points)
      )
        previous.confirmed = snapshot.revision;
      else
        this.drawings[metric] = {
          points,
          revision: snapshot.revision,
          generation: snapshot.generation,
          viewport: view,
          confirmed: snapshot.revision,
          semanticKey: snapshot.semanticKey,
          sourceId: snapshot.sourceId,
        };
    }
  }
  private presentDrawing(drawing: Drawing) {
    const current = this.isCurrent(drawing);
    if (drawing.presented?.current !== current || drawing.presented.confirmed !== drawing.confirmed)
      drawing.presented = {
        revision: drawing.revision,
        generation: drawing.generation,
        viewport: drawing.viewport,
        confirmed: drawing.confirmed,
        current,
      };
    return drawing.presented;
  }
  private emit(patch: Partial<MonitorReadState> = {}) {
    if (this.disposed) return;
    this.state = { ...this.state, ...patch };
    this.compose();
    for (const listener of this.listeners) listener(this.state);
  }
  private compose() {
    const description = this.state.description;
    this.state = {
      ...this.state,
      latest: this.latest?.points,
      averages: this.latest?.averages,
      latestRevision: this.latest?.revision,
      latestMetricSources: this.latest?.metricSources,
    };
    const plotted = this.plotMetrics().length > 0;
    if (!description || (plotted && !this.plot)) {
      this.state = { ...this.state, data: undefined };
      return;
    }
    // A preference change preserves the complete old drawing/readout snapshot until
    // replacement geometry arrives. Other lanes may complete in either order.
    if (this.retainedSemanticData && plotted && this.plot!.semanticKey !== this.source.semanticKey) {
      this.state = {
        ...this.state,
        data: this.retainedSemanticData.data,
        intervalStatistics: this.retainedSemanticData.intervalStatistics,
      };
      return;
    }
    this.retainedSemanticData = undefined;
    const semanticKey = this.plot ? this.plot.semanticKey : this.source.semanticKey;
    const sameSemantics = (result: { semanticKey?: string } | undefined) =>
      !!result && result.semanticKey === semanticKey;
    // Each exact operation keeps its admitted revision. A/B comparisons require one
    // common original snapshot; geometry can remain visible while a newer lookup finishes.
    const stats =
      sameSemantics(this.statistics) && this.statistics?.metrics === this.metrics.join(',')
        ? this.statistics
        : undefined;
    const series: Record<string, MonitorPoint[]> = {},
      drawings: Record<string, MonitorDrawing> = {},
      statistics: Record<string, MonitorStatistics> = {},
      statisticsStates: Record<string, MonitorStatisticsState> = {};
    for (const metric of this.metrics) {
      const drawing = this.drawings[metric];
      if (drawing && drawing.sourceId === description.sourceId) {
        series[metric] = drawing.points;
        drawings[metric] = this.presentDrawing(drawing);
      }
      const entry = this.statisticsEntries[metric];
      if (entry && entry.sourceId === description.sourceId && sameSemantics(entry)) {
        statistics[metric] = entry.value;
        const pending = entry.revision !== description.revision || !this.sameView(entry.viewport, this.state.viewport);
        if (entry.presented?.pending !== pending)
          entry.presented = { revision: entry.revision, viewport: entry.viewport, pending };
        statisticsStates[metric] = entry.presented;
      }
    }
    const unavailable = this.unavailablePoints;
    const selectionPending =
      this.state.cursorSeconds !== null && (!this.cursorResolved || !sameSemantics(this.selection));
    const selectionResult =
      sameSemantics(this.selection) && (this.selection?.seconds === this.state.cursorSeconds || selectionPending)
        ? this.selection
        : undefined;
    const referenceResult =
      sameSemantics(this.reference) && this.reference?.seconds === this.state.referenceSeconds
        ? this.reference
        : undefined;
    const endpointsMatch =
      this.state.cursorSeconds === null ||
      this.state.referenceSeconds === null ||
      selectionResult?.revision === referenceResult?.revision;
    const selection = selectionResult?.points ?? (this.state.cursorSeconds === null ? EMPTY_POINTS : unavailable);
    const reference = endpointsMatch
      ? (referenceResult?.points ?? (this.state.referenceSeconds === null ? EMPTY_POINTS : unavailable))
      : unavailable;
    const intervalStatistics =
      !selectionPending &&
      selectionResult &&
      referenceResult &&
      selectionResult.revision === referenceResult.revision &&
      sameSemantics(this.comparison) &&
      this.comparison?.revision === selectionResult.revision
        ? this.comparison.statistics
        : EMPTY_STATISTICS;
    const domain = this.sameView(this.state.data?.domain, description.domain)
      ? this.state.data!.domain
      : description.domain;
    const previous = this.state.data;
    const data = reuseShallow(previous, {
      sourceId: this.plot?.sourceId ?? description.sourceId,
      revision: this.plot?.revision,
      startedAt: description.startedAt,
      domain,
      nowSeconds: this.state.nowSeconds,
      monotonicAt: this.state.monotonicAt,
      liveAcquiredAt: this.state.liveAcquiredAt,
      mapMonotonicSeconds: this.state.mapMonotonicSeconds,
      metricSources:
        this.plot?.metricSources ??
        (!this.plot || this.plot.revision === description.revision ? description.metricSources : undefined),
      series: reuseShallow(previous?.series, series),
      drawings: reuseShallow(previous?.drawings, drawings),
      plotViewport: this.plotView,
      plotGeneration: this.plot?.generation,
      latest: sameSemantics(this.latest) ? this.latest?.points : undefined,
      latestRevision: sameSemantics(this.latest) ? this.latest?.revision : undefined,
      statistics: reuseShallow(previous?.statistics, statistics),
      statisticsStates: reuseShallow(previous?.statisticsStates, statisticsStates),
      statisticsRevision: stats?.revision,
      statisticsViewport: stats?.viewport,
      statisticsPending:
        !!stats &&
        (stats.revision !== description.revision ||
          !this.sameView(stats.viewport, this.state.viewport) ||
          !!this.state.loading.statistics),
      selection,
      reference,
      selectionRevision: selectionResult?.revision,
      referenceRevision: endpointsMatch ? referenceResult?.revision : undefined,
      selectionPending,
      selectionSeconds: selectionResult?.seconds,
      referenceSeconds: this.state.referenceSeconds ?? undefined,
    });
    this.state = { ...this.state, intervalStatistics, data };
  }

  private request<T extends MonitorEnvelope & { status: string }>(
    kind: ReadKind,
    operation: (generation: number, signal: AbortSignal) => Promise<T>,
    accept: (result: T, semanticKey?: string) => void,
  ) {
    if (!this.active || this.disposed) return;
    const semanticKey = this.source.semanticKey;
    this.emit({ loading: { ...this.state.loading, [kind]: true } });
    this.lanes[kind].run(
      new InspectionRequestContext(
        this.state.description?.sourceId ?? this.source.key,
        this.state.description?.revision ?? 'unresolved',
        this.source.distanceSelection ?? 'auto',
        String(this.state.interactionVersion),
        '0',
      ),
      async (generation, signal) => {
        const result = await operation(generation, signal);
        if ((kind === 'source' || kind === 'changes') && result.status !== 'ok') throw new ReadDeferred();
        return result;
      },
      result => {
        if (this.disposed) return;
        this.state = {
          ...this.state,
          loading: { ...this.state.loading, [kind]: false },
          errors: { ...this.state.errors, [kind]: undefined },
          deferred: { ...this.state.deferred, [kind]: false },
        };
        accept(result, semanticKey);
        this.emit();
        this.scheduleCatchup();
      },
      error => {
        if (error instanceof ReadCancelled) return;
        this.emit({
          loading: { ...this.state.loading, [kind]: false },
          deferred: { ...this.state.deferred, [kind]: readPressure(error) },
          errors: readPressure(error)
            ? this.state.errors
            : { ...this.state.errors, [kind]: presentFailure(error).text },
        });
      },
    );
  }
  private current(result: MonitorEnvelope & { status: 'ok' | 'retry' }, admitted = this.state.description) {
    const latest = this.state.description;
    const context = (sourceId: string, revision: string) =>
      new InspectionRequestContext(
        sourceId,
        revision,
        this.source.distanceSelection ?? 'auto',
        String(this.state.interactionVersion),
        '0',
      );
    const received = context(result.sourceId, result.revision);
    if (
      result.status === 'retry' ||
      !admitted ||
      !latest ||
      !inspectionContextsMatch(context(admitted.sourceId, admitted.revision), received) ||
      !inspectionContextsMatch(context(latest.sourceId, admitted.revision), received)
    ) {
      if (!this.retryScheduled) {
        this.retryScheduled = true;
        this.refresh(false);
      }
      return false;
    }
    return true;
  }
  private geometryBusy() {
    return this.state.loading.plot;
  }
  private selectionsBusy() {
    return this.state.loading.cursor || this.state.loading.reference || this.state.loading.comparison;
  }
  private selectionsBehind() {
    const revision = this.state.description?.revision;
    return (
      (this.state.cursorSeconds !== null && this.selection?.revision !== revision) ||
      (this.state.referenceSeconds !== null && this.reference?.revision !== revision) ||
      (this.state.cursorSeconds !== null &&
        this.state.referenceSeconds !== null &&
        this.comparison?.revision !== revision)
    );
  }
  private scheduleCatchup() {
    if (this.disposed || !this.active || this.interacting || this.catchupTimer || !this.state.description) return;
    const geometryBehind =
      this.plotMetrics().length > 0 &&
      !this.geometryBusy() &&
      !this.state.loading.changes &&
      !this.state.errors.plot &&
      this.plot?.revision !== this.state.description.revision;
    const exactBehind =
      !this.selectionsBusy() &&
      !this.state.errors.cursor &&
      !this.state.errors.reference &&
      !this.state.errors.comparison &&
      this.selectionsBehind();
    if (!geometryBehind && !exactBehind) return;
    this.catchupTimer = setTimeout(
      () => {
        this.catchupTimer = undefined;
        this.nextCatchupAt = performance.now() + 500;
        this.refresh(false);
      },
      Math.max(500, this.nextCatchupAt - performance.now()),
    );
  }
  refresh(external = true) {
    if (this.disposed || !this.active) return;
    this.readLatest();
    if (this.state.loading.source) return;
    if (external) this.retryScheduled = false;
    this.request(
      'source',
      (generation, signal) => this.source.describeSource({ generation, signal }),
      description => {
        const previous = this.state.description;
        const replaced = previous !== undefined && previous.sourceId !== description.sourceId;
        if (replaced) this.resetSource(true);
        this.state = { ...this.state, description };
        const resolved = this.resolveMetrics(description) && previous !== undefined && !replaced;
        this.receiveTiming(description);
        this.updateClock();
        if (!this.latest || resolved) this.readLatest(resolved);
        if (this.interacting) return;
        if (resolved) {
          this.readGeometry(true);
          this.readSelections(true);
          return;
        }
        // Capture progress never supersedes admitted scans. Each operation can advance
        // independently; a completed statistics snapshot keeps its explicit interval.
        if (!this.plot) this.readGeometry(false);
        else if (this.plot.revision !== description.revision && !this.geometryBusy() && !this.state.loading.changes) {
          const previousPlot = this.plot;
          this.request(
            'changes',
            (generation, signal) =>
              this.source.changesSince({ generation, signal, sinceRevision: previousPlot.revision }),
            changes => {
              if (
                changes.sourceId !== description.sourceId ||
                changes.sourceId !== this.state.description?.sourceId ||
                this.plot !== previousPlot
              )
                return;
              const view = this.state.viewport,
                visible = this.plotMetrics();
              const intersects =
                changes.resetRequired ||
                changes.changes.some(
                  change =>
                    change.kind === 'semantics' ||
                    ((!change.metrics || change.metrics.some(id => visible.includes(id))) &&
                      change.endSeconds >= view.start &&
                      change.startSeconds <= view.end),
                );
              // A delayed journal still completes its request. If newer capture arrived,
              // query the current snapshot; its older proof cannot promote geometry to R.
              if (
                changes.revision !== this.state.description.revision ||
                intersects ||
                !this.sameView(this.plotView, view) ||
                !this.covers()
              )
                this.readGeometry(false);
              else {
                for (const metric of visible) this.drawings[metric]!.confirmed = changes.revision;
                this.plot = { ...previousPlot, revision: changes.revision };
                this.readStatistics(false);
              }
            },
          );
        } else if (
          !this.sameView(this.plotView, this.state.viewport) ||
          this.state.errors.plot ||
          this.state.deferred.plot ||
          !this.covers()
        )
          this.readGeometry(false);
        if (
          !this.state.loading.statistics &&
          (this.statistics?.revision !== description.revision ||
            !this.sameView(this.statistics?.viewport, this.state.viewport) ||
            this.state.errors.statistics ||
            this.state.deferred.statistics)
        )
          this.readStatistics(false);
        if (
          this.selectionsBehind() ||
          this.state.errors.cursor ||
          this.state.errors.reference ||
          this.state.errors.comparison ||
          this.state.deferred.cursor ||
          this.state.deferred.reference ||
          this.state.deferred.comparison
        )
          this.readSelections(false);
      },
    );
  }
  private sameView(a: ChartViewport | undefined, b: ChartViewport | undefined) {
    return !!a && !!b && a.start === b.start && a.end === b.end;
  }
  tick() {
    if (this.active && !this.disposed) {
      this.updateClock();
      this.emit();
    }
  }
  private readLatest(force = false) {
    const description = this.state.description;
    if (!description || (!force && this.state.loading.latest)) return;
    const metrics = [...this.metrics];
    this.request(
      'latest',
      (generation, signal) => this.source.readLatest({ generation, signal, metrics }),
      (result, semanticKey) => {
        if (
          result.status !== 'ok' ||
          result.sourceId !== description.sourceId ||
          result.sourceId !== this.state.description?.sourceId
        )
          return;
        if (
          this.latest &&
          /^\d+$/.test(result.revision) &&
          /^\d+$/.test(this.latest.revision) &&
          BigInt(result.revision) < BigInt(this.latest.revision)
        )
          return;
        this.latest = { ...result, semanticKey, points: sharePoints(this.latest?.points, result.points) };
        this.receiveTiming(result);
        this.updateClock();
        if (result.averagesPending && !this.source.live && !this.averagesTimer)
          this.averagesTimer = setTimeout(() => {
            this.averagesTimer = undefined;
            this.readLatest();
          }, 500);
      },
    );
  }
  private receiveTiming(result: {
    nowSeconds?: number;
    monotonicAt?: number;
    liveAcquiredAt?: Record<string, number | null>;
  }) {
    const { nowSeconds, monotonicAt, liveAcquiredAt } = result;
    if (
      !this.source.live ||
      nowSeconds === undefined ||
      !Number.isFinite(nowSeconds) ||
      monotonicAt === undefined ||
      !Number.isFinite(monotonicAt)
    ) {
      this.liveClock = undefined;
      this.liveObservations = {};
      return;
    }
    if (!this.liveClock || monotonicAt > this.liveClock.monotonicAt)
      this.liveClock = { seconds: nowSeconds, monotonicAt };
    if (liveAcquiredAt) {
      for (const metric of this.metrics) {
        const acquiredAt = liveAcquiredAt[metric];
        if (acquiredAt == null || !Number.isFinite(acquiredAt) || acquiredAt > monotonicAt) {
          delete this.liveObservations[metric];
          continue;
        }
        this.liveObservations[metric] = acquiredAt;
      }
    }
  }
  private updateClock() {
    const description = this.state.description;
    if (!description) return;
    const now = performance.now() / 1000;
    const mappedAt = this.liveClock
      ? this.source.mapMonotonicSeconds
        ? this.source.mapMonotonicSeconds(this.liveClock.monotonicAt)
        : this.liveClock.monotonicAt
      : null;
    const nowSeconds =
      this.liveClock && mappedAt !== null && Number.isFinite(mappedAt)
        ? this.liveClock.seconds + Math.max(0, now - mappedAt)
        : (description.nowSeconds ?? 0);
    const liveAcquiredAt = reuseShallow(
      this.state.liveAcquiredAt,
      Object.fromEntries(this.metrics.map(metric => [metric, this.liveObservations[metric] ?? null])),
    );
    const domain = {
      start: description.domain.start,
      end: Math.max(description.domain.end, this.source.live ? nowSeconds : description.domain.end),
    };
    const nextView = this.manualView ? clampChartViewport(this.manualView, domain) : rangeViewport(domain, this.range);
    const viewport = this.sameView(this.state.viewport, nextView) ? this.state.viewport : nextView;
    this.state = {
      ...this.state,
      nowSeconds,
      monotonicAt: this.source.live ? now : 0,
      liveAcquiredAt,
      mapMonotonicSeconds: this.source.mapMonotonicSeconds,
      following: this.manualView === null,
      viewport,
    };
  }
  private readGeometry(force: boolean, withStatistics = true) {
    const description = this.state.description;
    if (!description || (!force && this.geometryBusy())) return;
    if (force) {
      this.lanes.changes.invalidate();
      this.state = { ...this.state, loading: { ...this.state.loading, changes: false } };
    }
    this.updateClock();
    const view = this.state.viewport,
      metrics = this.plotMetrics();
    let admitted = description;
    if (metrics.length)
      this.request(
        'plot',
        (generation, signal) => {
          admitted = this.state.description ?? description;
          return this.source.readPlot({
            generation,
            signal,
            expectedRevision: admitted.revision,
            metrics,
            startSeconds: view.start,
            endSeconds: view.end,
            pixelWidth: this.pixelWidth,
            buckets: Math.max(1, Math.min(512, Math.round(this.pixelWidth))),
          });
        },
        (result, semanticKey) => {
          if (!this.current(result, admitted) || result.status !== 'ok') return;
          this.plot = { ...result, semanticKey, metrics };
          this.plotView = view;
          this.draw(this.plot, view);
          if (!this.covers()) this.readGeometry(false);
        },
      );
    if (withStatistics) this.readStatistics(force, description, view);
  }
  private readStatistics(force: boolean, description = this.state.description, view = this.state.viewport) {
    if (this.interacting) return;
    if (!description || (!force && this.state.loading.statistics)) return;
    if (!force && this.statistics && performance.now() < this.nextStatisticsAt) {
      if (!this.statisticsTimer && this.active)
        this.statisticsTimer = setTimeout(() => {
          this.statisticsTimer = undefined;
          this.readStatistics(false);
        }, this.nextStatisticsAt - performance.now());
      return;
    }
    if (this.statisticsTimer) clearTimeout(this.statisticsTimer);
    this.statisticsTimer = undefined;
    const selected = this.metrics.join(','),
      metrics = this.plotMetrics(),
      requested = this.statisticsMetrics(metrics);
    const complete = (result: Successful<MonitorStatsResult>, semanticKey?: string) => {
      this.statistics = { ...result, semanticKey, viewport: view, metrics: selected };
      for (const metric of metrics) {
        const value = requested.includes(metric) ? result.statistics[metric] : undefined;
        const previous = this.statisticsEntries[metric];
        const same = !!previous && !!value && sameStatistics(previous.value, value);
        if (!value) delete this.statisticsEntries[metric];
        else if (
          !same ||
          previous!.revision !== result.revision ||
          previous!.sourceId !== result.sourceId ||
          previous!.semanticKey !== semanticKey ||
          !this.sameView(previous!.viewport, view)
        )
          this.statisticsEntries[metric] = {
            value: same ? previous!.value : value,
            revision: result.revision,
            viewport: view,
            semanticKey,
            sourceId: result.sourceId,
          };
      }
      const interval =
        this.source.live && this.metrics.some(isEnergyPerDistanceMetric)
          ? energyAverageInterval(view.end - view.start)
          : 2;
      this.nextStatisticsAt = performance.now() + interval * 1000;
      if (this.state.description?.revision !== result.revision || !this.sameView(this.state.viewport, view))
        this.readStatistics(false);
    };
    if (!requested.length) {
      this.lanes.statistics.invalidate();
      const current = this.state.description ?? description;
      this.state = {
        ...this.state,
        loading: { ...this.state.loading, statistics: false },
        errors: { ...this.state.errors, statistics: undefined },
        deferred: { ...this.state.deferred, statistics: false },
      };
      complete(
        { status: 'ok', generation: 0, sourceId: current.sourceId, revision: current.revision, statistics: {} },
        this.source.semanticKey,
      );
      this.emit();
      return;
    }
    let admitted = description;
    this.request(
      'statistics',
      (generation, signal) => {
        admitted = this.state.description ?? description;
        return this.source.rangeStats({
          generation,
          signal,
          expectedRevision: admitted.revision,
          metrics: requested,
          startSeconds: view.start,
          endSeconds: view.end,
        });
      },
      (result, semanticKey) => {
        if (!this.current(result, admitted) || result.status !== 'ok') return;
        complete(result, semanticKey);
      },
    );
  }
  private readSelection(kind: 'cursor' | 'reference', description: MonitorDescribeResult) {
    const seconds = kind === 'cursor' ? this.state.cursorSeconds : this.state.referenceSeconds;
    this.lanes[kind].invalidate();
    if (kind === 'cursor') this.cursorResolved = false;
    if (seconds === null) {
      this.emit({ loading: { ...this.state.loading, [kind]: false } });
      return;
    }
    const metrics = [...this.metrics];
    let admitted = description;
    const anchor = kind === 'cursor' ? this.cursorAnchor : this.referenceAnchor;
    this.request(
      kind,
      (generation, signal) => {
        admitted = this.state.description ?? description;
        return this.source.inspectAt({
          generation,
          signal,
          expectedRevision: admitted.revision,
          metrics,
          seconds,
          anchor: anchor && metrics.includes(anchor.metric) ? anchor : undefined,
        });
      },
      (result, semanticKey) => {
        if (!this.current(result, admitted) || result.status !== 'ok') return;
        if (kind === 'cursor') {
          this.selection = { ...result, semanticKey };
          this.cursorResolved = true;
        } else this.reference = { ...result, semanticKey };
      },
    );
  }
  private readComparison(description: MonitorDescribeResult) {
    const startSeconds = this.state.referenceSeconds,
      endSeconds = this.state.cursorSeconds;
    this.lanes.comparison.invalidate();
    if (startSeconds === null || endSeconds === null) {
      this.comparison = undefined;
      this.emit({ intervalStatistics: {}, loading: { ...this.state.loading, comparison: false } });
      return;
    }
    const metrics = [...this.metrics];
    let admitted = description;
    const forward = startSeconds <= endSeconds;
    const startAnchor = forward ? this.referenceAnchor : this.cursorAnchor,
      endAnchor = forward ? this.cursorAnchor : this.referenceAnchor;
    this.request(
      'comparison',
      (generation, signal) => {
        admitted = this.state.description ?? description;
        return this.source.rangeStats({
          generation,
          signal,
          expectedRevision: admitted.revision,
          metrics,
          startSeconds: Math.min(startSeconds, endSeconds),
          endSeconds: Math.max(startSeconds, endSeconds),
          includeEndpoints: true,
          startAnchor,
          endAnchor,
        });
      },
      (result, semanticKey) => {
        if (!this.current(result, admitted) || result.status !== 'ok') return;
        this.comparison = { ...result, semanticKey };
        if (result.endpoints) {
          const forward = startSeconds <= endSeconds;
          this.reference = {
            ...result,
            semanticKey,
            seconds: startSeconds,
            points: forward ? result.endpoints.start : result.endpoints.end,
            gaps: {},
          };
          this.cursorResolved = true;
          this.selection = {
            ...result,
            semanticKey,
            seconds: endSeconds,
            points: forward ? result.endpoints.end : result.endpoints.start,
            gaps: {},
          };
        }
      },
    );
  }
  private readSelections(force: boolean) {
    const description = this.state.description;
    if (!description || (!force && this.selectionsBusy())) return;
    this.readSelection('cursor', description);
    this.readSelection('reference', description);
    this.readComparison(description);
  }
  inspect(seconds: number | null, interacting = false, snap?: MonitorSnap | null) {
    if (!this.active || this.disposed) return;
    if (seconds !== null) this.manualView ??= this.state.viewport;
    // Retained geometry remains interactive by time while its replacement loads,
    // but its source-specific value and anchor cannot enter the new interpretation.
    const drawing = snap ? this.drawings[snap.metric] : undefined;
    const validSnap =
      seconds !== null &&
      snap &&
      this.isCurrent(drawing) &&
      drawing!.points.some(
        point =>
          point.observationId === snap.point.observationId &&
          Object.is(point.elapsedSeconds, snap.point.elapsedSeconds) &&
          Object.is(point.value, snap.point.value),
      ) &&
      snap.sourceId === this.state.description?.sourceId &&
      snap.point.elapsedSeconds === seconds &&
      this.metrics.includes(snap.metric) &&
      snap.point.observationId
        ? snap
        : null;
    this.cursorResolved = false;
    this.cursorAnchor = validSnap
      ? { metric: validSnap.metric, observationId: validSnap.point.observationId! }
      : undefined;
    if (seconds === null) this.selection = undefined;
    // A plotted candidate is itself an original. Preserve all original metadata
    // while native lookup verifies its identity against the admitted snapshot.
    if (validSnap && this.plot?.revision === this.state.description?.revision) {
      this.selection = {
        semanticKey: this.source.semanticKey,
        status: 'ok',
        generation: 0,
        sourceId: validSnap.sourceId,
        revision: this.plot!.revision,
        seconds: validSnap.point.elapsedSeconds,
        points: {
          [validSnap.metric]: validSnap.point,
        },
        gaps: {},
      };
    }
    this.comparison = undefined;
    this.cancelComparison();
    this.emit({
      cursorSeconds: seconds,
      following: this.manualView === null,
      intervalStatistics: {},
      loading: { ...this.state.loading, cursor: seconds !== null && !!this.state.description },
    });
    const description = this.state.description;
    if (!description) return;
    this.readSelection('cursor', description);
    // A is stationary while B moves. Keep its admitted read instead of
    // cancelling and issuing it again for every pointer position.
    if (
      this.state.referenceSeconds !== null &&
      !this.state.loading.reference &&
      this.reference?.revision !== description.revision
    )
      this.readSelection('reference', description);
    if (seconds === null || this.state.referenceSeconds === null) return;
    if (interacting) {
      this.emit({ loading: { ...this.state.loading, comparison: true } });
      this.comparisonTimer = setTimeout(() => {
        this.comparisonTimer = undefined;
        if (this.active && this.state.description) this.readComparison(this.state.description);
      }, 160);
    } else this.readComparison(description);
  }
  setReference(seconds: number | null) {
    this.referenceAnchor = seconds !== null && seconds === this.state.cursorSeconds ? this.cursorAnchor : undefined;
    this.cancelComparison();
    this.reference = undefined;
    this.comparison = undefined;
    this.emit({ referenceSeconds: seconds, intervalStatistics: {} });
    this.readSelections(true);
  }
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
    this.detailTimer = undefined;
    this.interactionEndTimer = undefined;
  }
  changeViewport(view: ChartViewport, interacting = false) {
    if (!this.active || this.disposed) return;
    const description = this.state.description;
    if (!description) return;
    this.manualView = clampChartViewport(view, {
      start: description.domain.start,
      end: Math.max(description.domain.end, this.source.live ? this.state.nowSeconds : description.domain.end),
    });
    this.updateClock();
    if (interacting) {
      if (!this.interacting) {
        if (this.statisticsTimer) clearTimeout(this.statisticsTimer);
        this.statisticsTimer = undefined;
        this.lanes.statistics.invalidate();
        this.state = { ...this.state, loading: { ...this.state.loading, statistics: false } };
      }
      this.interacting = true;
      if (!this.detailTimer)
        this.detailTimer = setTimeout(() => {
          this.detailTimer = undefined;
          this.readGeometry(true, false);
        }, 120);
      if (this.interactionEndTimer) clearTimeout(this.interactionEndTimer);
      this.interactionEndTimer = setTimeout(() => this.changeViewport(this.state.viewport), 200);
    } else {
      this.clearInteraction();
      this.readGeometry(true);
    }
    this.emit();
  }
  resume() {
    this.state = { ...this.state, interactionVersion: this.state.interactionVersion + 1 };
    this.clearInteraction();
    this.manualView = null;
    this.selection = undefined;
    this.cursorAnchor = undefined;
    this.lanes.cursor.invalidate();
    this.lanes.comparison.invalidate();
    this.emit({
      cursorSeconds: null,
      following: true,
      intervalStatistics: {},
      loading: { ...this.state.loading, cursor: false, comparison: false },
    });
    this.readGeometry(true);
  }
}
