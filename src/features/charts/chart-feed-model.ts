import type {
  Anchor,
  ApiFailure,
  CallInput,
  CallName,
  CallOutput,
  ChartHeadView,
  ChartRange,
  ChartSource,
  FailureCode,
  InspectedValue,
  LatestView,
  MetricId,
  MetricRangeStatistics,
  PlotSeries,
  PlotView,
  StatisticsView,
  TimeRange,
} from '../../../modules/power-log';
import { kit } from '../../../modules/power-log';
import {
  failureText,
  shellFailure,
  Snapshots,
  streamNotice,
  type Notice,
  type ScreenModel,
  type UiEnv,
} from '../../components/screen-model';

export interface Viewport {
  startS: number;
  endS: number;
}

export interface ChartSeries {
  atUs: Float64Array;
  atS: Float64Array;
  value: Float64Array;
  starts: Uint8Array;
}

export interface LatestCell {
  value: number | null;
  elapsedUs: number | null;
  rideAverage: number | null;
  freshUntilMs: number | null;
  tailUntilMs: number | null;
  stale: boolean;
}

export interface ChartFeedState {
  sourceKey: string;
  head: { revision: number; live: boolean; metrics: readonly MetricId[] } | null;
  following: boolean;
  inspecting: boolean;
  domain: Viewport;
  viewport: Viewport;
  nowS: number | null;
  series: Readonly<Partial<Record<MetricId, ChartSeries>>>;
  drawings: Readonly<Partial<Record<MetricId, { revision: number; current: boolean }>>>;
  latest: Readonly<Partial<Record<MetricId, LatestCell>>>;
  statistics: {
    range: Viewport;
    values: Readonly<Partial<Record<MetricId, MetricRangeStatistics>>>;
    pending: boolean;
  } | null;
  cursor: { atS: number; values: Readonly<Partial<Record<MetricId, InspectedValue>>>; pending: boolean } | null;
  reference: { atS: number; values: Readonly<Partial<Record<MetricId, InspectedValue>>> } | null;
  comparison: { loading: boolean; values: Readonly<Partial<Record<MetricId, MetricRangeStatistics>>> } | null;
  tails: Readonly<Partial<Record<MetricId, { startS: number; endS: number; value: number }>>>;
  catchingUp: boolean;
  error: string | null;
  resetKey: number;
}

export interface ChartFeedInput {
  source: ChartSource;
  scope: 'live' | 'history';
  metrics: readonly MetricId[];
  range: ChartRange;
  smoothingS?: number;
  tiles?: readonly MetricId[];
  readouts?: boolean;
  bikeAvailable?: boolean;
  whPerKmCharted?: boolean;
}

type Kind = 'plot' | 'latest' | 'statistics' | 'cursor' | 'reference' | 'comparison';
type Query = 'charts.plot' | 'charts.latest' | 'charts.statistics' | 'charts.inspect';

interface Slot {
  query: AbortController | null;
  error: string | null;
  busy: boolean;
  retry: ReturnType<typeof setTimeout> | undefined;
}

interface Point {
  atS: number;
  anchor: Anchor | null;
}

const voltageMetrics: readonly MetricId[] = ['batteryVoltageV', 'throttleVoltageV'];

const rangeUs: Record<ChartRange, number | null> = {
  seconds30: 30_000_000,
  seconds120: 120_000_000,
  seconds600: 600_000_000,
  all: null,
};
const liveDomainS = 10;
const busyRetryMs = 500;
const previewRetryMs = 1000;
const liveTickMs = 250;
const gesturePlotMs = 120;
const settledPlotMs = 200;
const onScreenPlotMs = 100;
const comparisonMs = 160;
const kinds: readonly Kind[] = ['plot', 'latest', 'statistics', 'cursor', 'reference', 'comparison'];
let instances = 0;

export function chartSeries(plot: PlotSeries): ChartSeries {
  const points: { us: number; value: number; segment: number }[] = [];
  plot.segment.forEach((segment, index) => {
    const bucket = [
      { us: plot.firstUs[index]!, value: plot.first[index]! },
      { us: plot.minUs[index]!, value: plot.min[index]! },
      { us: plot.maxUs[index]!, value: plot.max[index]! },
      { us: plot.lastUs[index]!, value: plot.last[index]! },
    ].sort((a, b) => a.us - b.us);
    for (const point of bucket) if (points.at(-1)?.us !== point.us) points.push({ ...point, segment });
  });
  const series: ChartSeries = {
    atUs: new Float64Array(points.length),
    atS: new Float64Array(points.length),
    value: new Float64Array(points.length),
    starts: new Uint8Array(points.length),
  };
  points.forEach((point, index) => {
    series.atUs[index] = point.us;
    series.atS[index] = point.us / 1e6;
    series.value[index] = point.value;
    series.starts[index] = index === 0 || points[index - 1]!.segment !== point.segment ? 1 : 0;
  });
  return series;
}

export class ChartFeedModel implements ScreenModel<ChartFeedState> {
  private readonly snapshots: Snapshots<ChartFeedState>;
  private readonly prefix = `chart-feed:${++instances}`;
  private readonly slots = new Map<Kind, Slot>(
    kinds.map(kind => [kind, { query: null, error: null, busy: false, retry: undefined }]),
  );
  private head: ChartHeadView | null = null;
  private plot: PlotView | null = null;
  private drawn: PlotView | null = null;
  private drawnRevision: number | null = null;
  private latestView: LatestView | null = null;
  private statisticsView: { range: Viewport; view: StatisticsView } | null = null;
  private filling = false;
  private statisticsNextMs: number | null = null;
  private cursorView: { atS: number; values: InspectedValue[] } | null = null;
  private referenceView: { atS: number; values: InspectedValue[] } | null = null;
  private comparisonView: MetricRangeStatistics[] | null = null;
  private manual: Viewport | null = null;
  private cursor: Point | null = null;
  private reference: Point | null = null;
  private onScreen: readonly MetricId[] | null = null;
  private gesturing = false;
  private lastGesturePlot = -Infinity;
  private widthPoints = 320;
  private timers = new Map<string, ReturnType<typeof setTimeout>>();
  private tick: ReturnType<typeof setInterval> | undefined;
  private resetKey = 0;
  private lost: Notice | null = null;
  private readonly drawnRevisions = new Map<MetricId, number>();
  private stopped = false;
  private awaitingPreview = false;
  private stop: (() => void) | null = null;

  constructor(
    private readonly env: UiEnv,
    private input: ChartFeedInput,
  ) {
    this.snapshots = new Snapshots<ChartFeedState>({
      sourceKey: JSON.stringify(input.source),
      head: null,
      following: input.scope === 'live',
      inspecting: false,
      domain: { startS: 0, endS: liveDomainS },
      viewport: { startS: 0, endS: liveDomainS },
      nowS: null,
      series: {},
      drawings: {},
      latest: {},
      statistics: null,
      cursor: null,
      reference: null,
      comparison: null,
      tails: {},
      catchingUp: false,
      error: null,
      resetKey: 0,
    });
  }

  getSnapshot = (): ChartFeedState => this.snapshots.get();

  subscribe = (onChange: () => void): (() => void) => this.snapshots.subscribe(onChange);

  activate(): void {
    if (this.stop || this.stopped) return;
    this.awaitingPreview = false;
    this.lost = null;
    this.stop = this.env.client.subscribe(
      'charts.head',
      { source: this.input.source },
      ({ value }) => {
        const revised = value.revision !== this.head?.revision;
        this.head = value;
        this.lost = null;
        this.followClock();
        this.headAnswered(revised);
        this.publish();
      },
      failure => {
        if (failure.code !== 'rideDeleted' && !shellFailure(failure.code))
          this.lost = streamNotice(failure, this.text(failure.code));
        this.failed('plot', failure);
      },
    );
    this.requestLatest();
  }

  notice(): Notice | null {
    return this.lost;
  }

  retry(): void {
    this.deactivate();
    this.activate();
    this.publish();
  }

  deactivate(): void {
    this.awaitingPreview = false;
    this.stop?.();
    this.stop = null;
    for (const slot of this.slots.values()) {
      slot.query?.abort();
      slot.query = null;
      clearTimeout(slot.retry);
      slot.retry = undefined;
    }
    for (const timer of this.timers.values()) clearTimeout(timer);
    this.timers.clear();
    clearInterval(this.tick);
    this.tick = undefined;
  }

  dispose(): void {
    this.deactivate();
  }

  configure(next: Partial<Omit<ChartFeedInput, 'source' | 'scope'>> & Pick<ChartFeedInput, 'metrics' | 'range'>): void {
    const previous = this.input;
    const merged: ChartFeedInput = { ...previous, ...next, smoothingS: next.smoothingS ?? previous.smoothingS ?? 0 };
    const plotChanged =
      merged.range !== previous.range ||
      merged.smoothingS !== (previous.smoothingS ?? 0) ||
      !same(merged.metrics, previous.metrics);
    const latestChanged = !same(merged.tiles ?? [], previous.tiles ?? []) || merged.readouts !== previous.readouts;
    if (!plotChanged && !latestChanged && merged.bikeAvailable === previous.bikeAvailable) return;
    this.input = merged;
    if (this.awaitingPreview && merged.bikeAvailable && !previous.bikeAvailable) this.activate();
    if (merged.range !== previous.range) this.resume();
    if (!same(merged.metrics, previous.metrics)) this.clearInteraction();
    if (plotChanged) {
      this.drawnRevision = null;
      if (this.head) this.requestPlot();
      this.requestStatistics();
    }
    if (latestChanged || plotChanged) this.requestLatest();
    this.publish();
  }

  setPlotWidth(points: number): void {
    const width = Math.max(1, Math.round(points));
    if (width === this.widthPoints) return;
    this.widthPoints = width;
    if (this.head) this.requestPlot();
  }

  setOnScreen(metrics: readonly MetricId[]): void {
    if (this.onScreen && same(metrics, this.onScreen)) return;
    this.onScreen = metrics;
    this.later('onScreen', onScreenPlotMs, () => {
      if (this.head) this.requestPlot();
      this.requestStatistics(true);
    });
  }

  setViewport(viewport: Viewport, gesturing: boolean): void {
    const domain = this.domain();
    const span = Math.max(1e-6, viewport.endS - viewport.startS);
    const startS = Math.min(Math.max(viewport.startS, domain.startS), Math.max(domain.startS, domain.endS - span));
    this.manual = { startS, endS: startS + span };
    this.gesturing = gesturing;
    const now = performance.now();
    if (gesturing) {
      if (now - this.lastGesturePlot >= gesturePlotMs) {
        this.lastGesturePlot = now;
        this.requestPlot();
      }
      this.later('settled', settledPlotMs, () => this.endGesture());
    } else this.endGesture();
    this.publish();
  }

  endGesture(): void {
    this.gesturing = false;
    this.cancelLater('settled');
    this.requestPlot();
    this.requestStatistics();
  }

  setCursor(atS: number, anchor: Anchor | null, dragging = false): void {
    this.cursor = { atS, anchor };
    if (this.input.scope === 'live' && !this.manual) this.manual = this.viewport();
    this.requestInspect('cursor');
    if (this.reference) {
      if (dragging) this.later('comparison', comparisonMs, () => this.requestComparison());
      else this.requestComparison();
    }
    this.publish();
  }

  clearCursor(): void {
    this.cursor = null;
    this.cursorView = null;
    this.abort('cursor');
    this.publish();
  }

  setReference(atS: number, anchor: Anchor | null): void {
    this.reference = { atS, anchor };
    this.requestInspect('reference');
    this.requestComparison();
    this.publish();
  }

  clearReference(): void {
    this.reference = null;
    this.referenceView = null;
    this.comparisonView = null;
    this.abort('reference');
    this.abort('comparison');
    this.publish();
  }

  follow(): void {
    this.resume();
    this.requestPlot();
    this.requestStatistics();
    this.publish();
  }

  resetView(): void {
    this.clearInteraction();
    this.requestPlot();
    this.requestStatistics();
    this.publish();
  }

  private clearInteraction(): void {
    this.resume();
    this.reference = null;
    this.referenceView = null;
    this.comparisonView = null;
    this.cancelLater('comparison');
    this.abort('reference');
    this.abort('comparison');
    this.resetKey++;
  }

  private resume(): void {
    this.manual = null;
    this.cursor = null;
    this.cursorView = null;
    this.abort('cursor');
  }

  private headAnswered(revised: boolean): void {
    const plot = this.slot('plot');
    if (this.head!.revision !== this.drawnRevision || plot.busy) this.requestPlot();
    this.requestLatest();
    const statistics = this.slot('statistics');
    if (
      statistics.busy ||
      (this.statisticsNextMs !== null && performance.now() >= this.statisticsNextMs) ||
      (revised && this.statisticsView === null)
    )
      this.requestStatistics();
    if (this.cursor) this.requestInspect('cursor');
    if (this.reference && revised) {
      this.requestInspect('reference');
      this.requestComparison();
    }
  }

  private charted(): MetricId[] {
    const metrics = [...this.input.metrics];
    return this.onScreen ? metrics.filter(metric => this.onScreen!.includes(metric)) : metrics;
  }

  private smoothingUs(): number {
    return (this.input.smoothingS ?? 0) * 1_000_000;
  }

  private requestPlot(): void {
    const head = this.head;
    if (!head || this.stopped) return;
    const metrics = this.charted();
    this.issue(
      'plot',
      'charts.plot',
      {
        source: this.input.source,
        metrics,
        rangeUs: this.plotRange(head),
        buckets: Math.min(512, Math.max(1, this.widthPoints)),
        smoothingUs: this.smoothingUs(),
      },
      value => {
        this.plot = value;
        this.drawnRevision = value.revision;
      },
    );
  }

  private requestLatest(): void {
    if (this.stopped || !this.stop) return;
    const readouts = this.input.readouts && this.input.scope === 'live' ? this.charted() : [];
    const metrics = [...new Set([...(this.input.tiles ?? []), ...readouts])];
    if (!metrics.length) return;
    this.issue(
      'latest',
      'charts.latest',
      { source: this.input.source, metrics, smoothingUs: this.smoothingUs() },
      value => {
        this.latestView = value;
      },
    );
  }

  private requestStatistics(fill = false): void {
    if (this.stopped || !this.head || !this.input.readouts || this.gesturing) return;
    const following = this.input.scope === 'live' && !this.manual;
    const wanted = this.charted().filter(metric => !following || voltageMetrics.includes(metric));
    const viewport = this.viewport();
    const shown = this.statisticsView;
    const current =
      fill && !!shown && shown.range.startS === viewport.startS && shown.range.endS === viewport.endS ? shown : null;
    const metrics = current
      ? wanted.filter(metric => !current.view.statistics.some(row => row.metric === metric))
      : wanted;
    if (!metrics.length) return;
    this.filling = current !== null;
    const range = { startUs: Math.max(0, Math.round(viewport.startS * 1e6)), endUs: Math.round(viewport.endS * 1e6) };
    this.issue(
      'statistics',
      'charts.statistics',
      {
        source: this.input.source,
        metrics,
        rangeUs: range,
        smoothingUs: this.smoothingUs(),
        whPerKmCharted: this.input.whPerKmCharted ?? false,
      },
      value => {
        this.statisticsView =
          current && this.statisticsView === current
            ? {
                range: viewport,
                view: {
                  ...value,
                  statistics: [
                    ...current.view.statistics.filter(row => !metrics.includes(row.metric)),
                    ...value.statistics,
                  ],
                },
              }
            : { range: viewport, view: value };
        this.statisticsNextMs = value.nextUs === null ? null : performance.now() + value.nextUs / 1000;
      },
    );
  }

  private requestInspect(kind: 'cursor' | 'reference'): void {
    const point = kind === 'cursor' ? this.cursor : this.reference;
    if (!point || this.stopped || !this.stop) return;
    const metrics = [...this.input.metrics];
    if (!metrics.length) return;
    this.issue(
      kind,
      'charts.inspect',
      {
        source: this.input.source,
        metrics,
        atUs: this.instantUs(point),
        anchor: point.anchor,
        smoothingUs: this.smoothingUs(),
      },
      value => {
        const shown = { atS: point.atS, values: value.values };
        if (kind === 'cursor') this.cursorView = shown;
        else this.referenceView = shown;
      },
    );
  }

  private instantUs(point: Point): number {
    if (point.anchor) return point.anchor.elapsedUs;
    const atUs = Math.round(point.atS * 1e6);
    return this.head && !this.head.live ? Math.min(atUs, this.head.rangeUs.endUs - 1) : atUs;
  }

  private requestComparison(): void {
    const a = this.reference;
    const b = this.cursor;
    this.cancelLater('comparison');
    if (!a || !b || this.stopped || !this.stop) return;
    const aUs = this.instantUs(a);
    const bUs = this.instantUs(b);
    const metrics = [...this.input.metrics];
    if (!metrics.length) return;
    this.issue(
      'comparison',
      'charts.statistics',
      {
        source: this.input.source,
        metrics,
        rangeUs: { startUs: Math.min(aUs, bUs), endUs: Math.max(aUs, bUs) + 1 },
        smoothingUs: this.smoothingUs(),
        whPerKmCharted: this.input.whPerKmCharted ?? false,
      },
      value => {
        this.comparisonView = value.statistics;
      },
    );
  }

  private issue<O extends Query & CallName>(
    kind: Kind,
    op: O,
    input: Omit<CallInput<O>, 'key'>,
    done: (value: CallOutput<O>) => void,
  ): void {
    const slot = this.slot(kind);
    clearTimeout(slot.retry);
    slot.retry = undefined;
    slot.query?.abort();
    const query = new AbortController();
    slot.query = query;
    void this.env.client
      .call(op, { ...input, key: `${this.prefix}:${kind}` } as CallInput<O>, { signal: query.signal } as never)
      .then(answer => {
        if (slot.query !== query || 'cancelled' in answer) return;
        slot.query = null;
        if (answer.ok) {
          slot.error = null;
          slot.busy = false;
          done(answer.value as CallOutput<O>);
        } else this.failed(kind, answer.failure);
        this.publish();
      });
  }

  private failed(kind: Kind, failure: ApiFailure): void {
    const { code } = failure;
    const slot = this.slot(kind);
    if (code === 'storageRefused' || code === 'apiMismatch') this.env.shell.report(failure);
    else if (code === 'rideDeleted' && this.input.source.kind === 'preview') this.awaitPreview();
    else if (code === 'rideDeleted') {
      this.stopped = true;
      this.deactivate();
    } else if (code === 'storageBusy') {
      slot.busy = true;
      clearTimeout(slot.retry);
      slot.retry = setTimeout(() => this.reissue(kind), busyRetryMs);
    } else slot.error = this.text(code);
    this.publish();
  }

  private awaitPreview(): void {
    this.deactivate();
    this.head = null;
    this.awaitingPreview = true;
    if (this.input.bikeAvailable)
      this.later('preview', previewRetryMs, () => {
        if (this.awaitingPreview) this.activate();
      });
  }

  private reissue(kind: Kind): void {
    if (kind === 'plot') this.requestPlot();
    else if (kind === 'latest') this.requestLatest();
    else if (kind === 'statistics') this.requestStatistics();
    else if (kind === 'comparison') this.requestComparison();
    else this.requestInspect(kind);
  }

  private slot(kind: Kind): Slot {
    return this.slots.get(kind)!;
  }

  private abort(kind: Kind): void {
    const slot = this.slot(kind);
    slot.query?.abort();
    slot.query = null;
    clearTimeout(slot.retry);
    slot.retry = undefined;
  }

  private later(name: string, ms: number, run: () => void): void {
    this.cancelLater(name);
    this.timers.set(
      name,
      setTimeout(() => {
        this.timers.delete(name);
        run();
        this.publish();
      }, ms),
    );
  }

  private cancelLater(name: string): void {
    clearTimeout(this.timers.get(name));
    this.timers.delete(name);
  }

  private plotRange(head: ChartHeadView): TimeRange | null {
    if (this.manual) {
      const end = head.rangeUs.endUs;
      const startUs = Math.min(Math.max(0, Math.round(this.manual.startS * 1e6)), end);
      return { startUs, endUs: Math.round(this.manual.endS * 1e6) };
    }
    const span = rangeUs[this.input.range];
    if (span === null) return null;
    const end = head.rangeUs.endUs;
    return { startUs: Math.max(0, end - span), endUs: end };
  }

  private followClock(): void {
    const live = this.head?.live === true && this.head.originMonoUs !== null;
    if (live && this.tick === undefined) this.tick = setInterval(() => this.publish(), liveTickMs);
    if (!live && this.tick !== undefined) {
      clearInterval(this.tick);
      this.tick = undefined;
    }
  }

  private text(code: FailureCode): string {
    return failureText(this.env.shell.display.current(), code);
  }

  private nowS(): number | null {
    const head = this.head;
    const origin = head?.live && head.originMonoUs !== null ? this.env.client.localTime(head.originMonoUs) : null;
    return origin === null ? null : Math.max(0, (performance.now() - origin) / 1000);
  }

  private domain(): Viewport {
    const head = this.head;
    const nowS = this.nowS();
    if (head === null) return this.snapshots.get().domain;
    return nowS !== null
      ? { startS: 0, endS: Math.max(liveDomainS, nowS) }
      : { startS: head.rangeUs.startUs / 1e6, endS: head.rangeUs.endUs / 1e6 };
  }

  private viewport(): Viewport {
    if (this.manual) return this.manual;
    const domain = this.domain();
    const span = rangeUs[this.input.range];
    return span === null ? domain : { startS: Math.max(domain.startS, domain.endS - span / 1e6), endS: domain.endS };
  }

  private latestCells(): Partial<Record<MetricId, LatestCell>> {
    const cells: Partial<Record<MetricId, LatestCell>> = {};
    const now = performance.now();
    for (const reading of this.latestView?.readings ?? []) {
      const freshUntilMs =
        reading.freshUntilMonoUs === null ? null : this.env.client.localTime(reading.freshUntilMonoUs);
      cells[reading.metric] = {
        value: reading.value,
        elapsedUs: reading.elapsedUs,
        rideAverage: reading.rideAverage,
        freshUntilMs,
        tailUntilMs: reading.tailUntilMonoUs === null ? null : this.env.client.localTime(reading.tailUntilMonoUs),
        stale: freshUntilMs !== null && now > freshUntilMs,
      };
    }
    return cells;
  }

  private tails(
    series: Partial<Record<MetricId, ChartSeries>>,
    latest: Partial<Record<MetricId, LatestCell>>,
    nowS: number | null,
    following: boolean,
  ): Partial<Record<MetricId, { startS: number; endS: number; value: number }>> {
    const tails: Partial<Record<MetricId, { startS: number; endS: number; value: number }>> = {};
    if (!following || nowS === null || this.input.bikeAvailable === false) return tails;
    const now = performance.now();
    for (const metric of this.input.metrics) {
      const drawn = series[metric];
      const cell = latest[metric];
      if (!drawn || !drawn.atUs.length || !cell || cell.tailUntilMs === null || now >= cell.tailUntilMs) continue;
      const last = drawn.atUs.length - 1;
      if (drawn.atUs[last] !== cell.elapsedUs) continue;
      const atS = drawn.atS[last]!;
      if (nowS <= atS) continue;
      const tailS = (kit.metricAppearance(metric)?.tailUs ?? 0) / 1e6;
      tails[metric] = { startS: atS, endS: Math.min(nowS, atS + tailS), value: drawn.value[last]! };
    }
    return tails;
  }

  private publish(): void {
    const head = this.head;
    const previous = this.snapshots.get();
    const nowS = this.nowS();
    const domain = this.domain();
    const viewport = this.viewport();
    const following = this.input.scope === 'live' && this.manual === null && (head === null || head.live);
    const series: Partial<Record<MetricId, ChartSeries>> = { ...previous.series };
    const drawings: Partial<Record<MetricId, { revision: number; current: boolean }>> = {};
    if (this.plot && this.plot !== this.drawn) {
      for (const plotted of this.plot.series) {
        const built = chartSeries(plotted);
        const kept = series[plotted.metric];
        series[plotted.metric] = kept && sameSeries(kept, built) ? kept : built;
        this.drawnRevisions.set(plotted.metric, this.plot.revision);
      }
      this.drawn = this.plot;
    }
    for (const [metric, revision] of this.drawnRevisions)
      drawings[metric] = { revision, current: revision === head?.revision };
    const latest = this.latestCells();
    const byMetric = <T extends { metric: MetricId }>(rows: readonly T[]) =>
      Object.fromEntries(rows.map(row => [row.metric, row])) as Partial<Record<MetricId, T>>;
    const statistics = this.slot('statistics');
    const cursor = this.slot('cursor');
    const comparison = this.slot('comparison');
    const busy = kinds.some(kind => this.slot(kind).busy);
    this.snapshots.set({
      ...previous,
      head: head ? { revision: head.revision, live: head.live, metrics: head.metrics } : null,
      following,
      inspecting: this.manual !== null || this.cursor !== null,
      domain,
      viewport,
      nowS,
      series,
      drawings,
      latest,
      statistics: this.statisticsView
        ? {
            range: this.statisticsView.range,
            values: byMetric(this.statisticsView.view.statistics),
            pending: (statistics.query !== null && !this.filling) || this.gesturing,
          }
        : null,
      cursor: this.cursor
        ? {
            atS: this.cursor.atS,
            values: byMetric(this.cursorView?.values ?? []),
            pending: cursor.query !== null || this.cursorView?.atS !== this.cursor.atS,
          }
        : null,
      reference: this.reference
        ? { atS: this.reference.atS, values: byMetric(this.referenceView?.values ?? []) }
        : null,
      comparison:
        this.reference && this.cursor
          ? {
              loading: comparison.query !== null || this.timers.has('comparison'),
              values: byMetric(this.comparisonView ?? []),
            }
          : null,
      tails: this.tails(series, latest, nowS, following),
      catchingUp: busy || head?.catchingUp === true,
      error: kinds.map(kind => this.slot(kind).error).find(error => error !== null) ?? null,
      resetKey: this.resetKey,
    });
  }
}

function sameSeries(a: ChartSeries, b: ChartSeries): boolean {
  const equalArrays = (x: ArrayLike<number>, y: ArrayLike<number>) => {
    if (x.length !== y.length) return false;
    for (let index = 0; index < x.length; index++) if (!Object.is(x[index], y[index])) return false;
    return true;
  };
  return equalArrays(a.atUs, b.atUs) && equalArrays(a.value, b.value) && equalArrays(a.starts, b.starts);
}

function same(a: readonly MetricId[], b: readonly MetricId[]): boolean {
  return a.length === b.length && a.every((metric, index) => metric === b[index]);
}
