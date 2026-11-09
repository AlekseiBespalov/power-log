import {
  kit,
  type ApiFailure,
  type ChartRange,
  type ChartSource,
  type FailureCode,
  type MetricId,
  type SettingsChange,
  type SettingsView,
  type ViewId,
  type ViewPreset,
} from '../../../modules/power-log';
import {
  failureText,
  Snapshots,
  streamNotice,
  type Notice,
  type ScreenModel,
  type UiEnv,
} from '../../components/screen-model';
import { ChartFeedModel } from './chart-feed-model';

export type MetricKind = 'numbers' | 'charts';
export type Smoothing = 0 | 5 | 30 | 60;

export interface ChartsPanelInput {
  source: ChartSource;
  scope: 'live' | 'history';
  bikeAvailable?: boolean;
}

export interface ChartsPanelState {
  ready: boolean;
  view: ViewId;
  views: readonly { id: ViewId; name: string; selected: boolean }[];
  range: ChartRange;
  ranges: readonly { value: ChartRange; label: string; selected: boolean }[];
  numbers: readonly MetricId[];
  charts: readonly MetricId[];
  selected: { numbers: readonly MetricId[]; charts: readonly MetricId[] };
  columns: number;
  smoothingS: Smoothing;
  status: { label: string; detail: string | null } | null;
  resetOffered: boolean;
  editor: { tab: MetricKind; query: string } | null;
  menu: 'view' | 'smoothing' | null;
  notice: Notice | null;
}

const viewIds: readonly ViewId[] = ['ride', 'battery', 'temperature'];
const rangeIds: readonly ChartRange[] = ['seconds30', 'seconds120', 'seconds600', 'all'];

export class ChartsPanelModel implements ScreenModel<ChartsPanelState> {
  readonly feed: ChartFeedModel;
  private readonly snapshots: Snapshots<ChartsPanelState>;
  private readonly saving = new Map<string, SettingsChange>();
  private readonly queued = new Map<string, SettingsChange>();
  private view: SettingsView | null = null;
  private saveFailure: ApiFailure | null = null;
  private lost: Notice | null = null;
  private smoothingS: Smoothing = 0;
  private editor: ChartsPanelState['editor'] = null;
  private menu: ChartsPanelState['menu'] = null;
  private stop: (() => void) | null = null;
  private bikeAvailable: boolean;

  constructor(
    private readonly env: UiEnv,
    private readonly input: ChartsPanelInput,
  ) {
    this.bikeAvailable = input.bikeAvailable ?? true;
    this.feed = new ChartFeedModel(env, {
      ...input,
      metrics: [],
      range: input.scope === 'live' ? 'seconds120' : 'all',
    });
    this.snapshots = new Snapshots<ChartsPanelState>(this.state());
  }

  getSnapshot = (): ChartsPanelState => this.snapshots.get();

  subscribe = (onChange: () => void): (() => void) => this.snapshots.subscribe(onChange);

  activate(): void {
    if (this.stop) return;
    const stopStream = this.env.client.subscribe(
      'settings.state',
      {},
      ({ value }) => {
        this.view = [...this.saving.values(), ...this.queued.values()].reduce(applied, value);
        this.lost = null;
        this.publish();
      },
      failure => {
        this.report(failure);
        if (failure.code === 'storageRefused' || failure.code === 'apiMismatch') return;
        this.lost = streamNotice(failure, this.text(failure.code));
        this.publish();
      },
    );
    const stopFeed = this.feed.subscribe(() => this.publish());
    this.feed.activate();
    this.stop = () => {
      stopStream();
      stopFeed();
      this.feed.deactivate();
    };
    this.publish();
  }

  deactivate(): void {
    this.stop?.();
    this.stop = null;
  }

  dispose(): void {
    this.deactivate();
    this.feed.dispose();
  }

  retry(): void {
    this.lost = null;
    this.deactivate();
    this.activate();
  }

  dismissNotice(): void {
    const shown = this.snapshots.get().notice;
    if (shown) this.env.shell.dismiss(shown.id);
    if (this.lost?.id === shown?.id) this.lost = null;
    this.publish();
  }

  chooseView(view: ViewId): void {
    this.menu = null;
    this.change(
      'view',
      this.input.scope === 'live' ? { kind: 'activeView', value: view } : { kind: 'historyView', value: view },
    );
    this.feed.resetView();
  }

  chooseRange(range: ChartRange): void {
    this.menu = null;
    this.change(
      'range',
      this.input.scope === 'live' ? preset(this.activeView(), { range }) : { kind: 'historyRange', value: range },
    );
    this.feed.follow();
  }

  openMenu(menu: 'view' | 'smoothing'): void {
    this.menu = menu;
    this.publish();
  }

  closeMenu(): void {
    this.menu = null;
    this.publish();
  }

  openEditor(tab: MetricKind = 'numbers'): void {
    this.editor = { tab, query: '' };
    this.publish();
  }

  closeEditor(): void {
    this.editor = null;
    this.publish();
  }

  setEditorTab(tab: MetricKind): void {
    if (!this.editor) return;
    this.editor = { tab, query: this.editor.query };
    this.publish();
  }

  setEditorQuery(query: string): void {
    if (!this.editor) return;
    this.editor = { tab: this.editor.tab, query };
    this.publish();
  }

  addMetric(kind: MetricKind, metric: MetricId): void {
    const current = this.presetOf(this.activeView())[kind];
    if (current.includes(metric)) return;
    this.setMetrics(kind, [...current, metric]);
  }

  removeMetric(kind: MetricKind, metric: MetricId): void {
    this.setMetrics(
      kind,
      this.presetOf(this.activeView())[kind].filter(id => id !== metric),
    );
  }

  moveMetric(kind: MetricKind, from: number, to: number): void {
    const next = [...this.presetOf(this.activeView())[kind]];
    if (from < 0 || from >= next.length || to < 0 || to >= next.length || from === to) return;
    const [moved] = next.splice(from, 1);
    next.splice(to, 0, moved!);
    this.setMetrics(kind, next);
  }

  reorderCharts(shown: readonly MetricId[]): void {
    const order = shown.map(id => kit.metricCatalogStored(id) as MetricId);
    const stored = this.presetOf(this.activeView()).charts;
    const moved = stored.filter(id => order.includes(id));
    let next = 0;
    this.setMetrics(
      'charts',
      stored.map(id => (moved.includes(id) ? order[next++]! : id)),
    );
  }

  resetLayout(): void {
    this.change('layout', { kind: 'viewReset', view: this.activeView() });
  }

  setColumns(webColumns: number): void {
    this.change('columns', preset(this.activeView(), { webColumns }));
  }

  resetSettings(): void {
    this.menu = null;
    this.change('reset', { kind: 'reset' });
  }

  setBikeAvailable(available: boolean): void {
    if (available === this.bikeAvailable) return;
    this.bikeAvailable = available;
    this.publish();
  }

  setSmoothing(smoothingS: Smoothing): void {
    this.smoothingS = smoothingS;
    this.menu = null;
    this.publish();
  }

  setMetrics(kind: MetricKind, metrics: MetricId[]): void {
    this.change(kind, preset(this.activeView(), kind === 'numbers' ? { numbers: metrics } : { charts: metrics }));
  }

  private change(key: string, change: SettingsChange): void {
    if (this.view) this.view = applied(this.view, change);
    if (this.saving.has(key)) this.queued.set(key, change);
    else this.save(key, change);
    this.publish();
  }

  private save(key: string, change: SettingsChange): void {
    this.saving.set(key, change);
    void this.env.client.call('settings.change', change).then(answer => {
      this.saving.delete(key);
      if (!('cancelled' in answer)) {
        if (answer.ok) this.saveFailure = null;
        else {
          this.report(answer.failure);
          if (answer.failure.code === 'settingsSaveFailed') this.saveFailure = answer.failure;
        }
      }
      const next = this.queued.get(key);
      this.queued.delete(key);
      if (next) this.save(key, next);
      this.publish();
    });
  }

  private report(failure: ApiFailure): void {
    if (failure.code === 'storageRefused' || failure.code === 'apiMismatch') this.env.shell.report(failure);
  }

  private text(code: FailureCode): string {
    return failureText(this.env.shell.display.current(), code);
  }

  private activeView(): ViewId {
    const app = this.view?.app;
    if (!app) return 'ride';
    return this.input.scope === 'live' ? app.activeView : (app.historyView ?? app.activeView);
  }

  private presetOf(view: ViewId): ViewPreset {
    return this.view?.app.views[view] ?? { numbers: [], charts: [], range: 'seconds120', webColumns: 2 };
  }

  private range(): ChartRange {
    if (this.input.scope === 'history') return this.view?.app.historyRange ?? 'all';
    return this.presetOf(this.activeView()).range;
  }

  private status(): ChartsPanelState['status'] {
    const feed = this.feed.getSnapshot();
    if (feed.error) return chartStatus('chartError', feed.error);
    if (this.saveFailure) return chartStatus('settingsError', this.text('settingsSaveFailed'));
    if (this.view && this.view.state !== 'ok')
      return chartStatus(
        'settingsError',
        this.text(this.view.state === 'unreadable' ? 'settingsUnreadable' : 'settingsSaveFailed'),
      );
    const metrics = feed.head?.metrics ?? [];
    if (this.input.scope === 'live') return metrics.length ? null : chartStatus('awaitingData');
    if (feed.catchingUp) return chartStatus('syncing');
    return metrics.length ? null : chartStatus('noMeasurements');
  }

  private state(): ChartsPanelState {
    const view = this.activeView();
    const current = this.presetOf(view);
    const range = this.range();
    const head = this.feed.getSnapshot().head;
    const shown = (ids: readonly MetricId[]): MetricId[] =>
      kit.metricCatalogShown([...ids], [...(head?.metrics ?? [])]) as MetricId[];
    return {
      ready: this.view !== null,
      view,
      views: viewIds.map(id => ({ id, name: kit.textsViewName(id), selected: id === view })),
      range,
      ranges: rangeIds.map(value => ({ value, label: kit.textsRange(value), selected: value === range })),
      numbers: shown(current.numbers),
      charts: shown(current.charts),
      selected: { numbers: current.numbers, charts: current.charts },
      columns: current.webColumns,
      smoothingS: this.smoothingS,
      status: this.status(),
      resetOffered: this.view?.state === 'unreadable',
      editor: this.editor,
      menu: this.menu,
      notice: [this.lost, this.feed.notice()].find(item => item && !this.env.shell.dismissed(item.id)) ?? null,
    };
  }

  private publish(): void {
    const next = this.state();
    this.feed.configure({
      metrics: next.charts,
      range: next.range,
      smoothingS: next.smoothingS,
      tiles: next.numbers,
      readouts: true,
      whPerKmCharted: next.charts.some(metric => metric === 'batteryWhPerKm' || metric === 'riderWhPerKm'),
      bikeAvailable: this.bikeAvailable,
    });
    this.snapshots.set(next);
  }
}

function preset(
  view: ViewId,
  change: { numbers?: MetricId[]; charts?: MetricId[]; range?: ChartRange; webColumns?: number },
): SettingsChange {
  return {
    kind: 'preset',
    view,
    numbers: change.numbers ?? null,
    charts: change.charts ?? null,
    range: change.range ?? null,
    webColumns: change.webColumns ?? null,
  };
}

function applied(view: SettingsView, change: SettingsChange): SettingsView {
  const app = view.app;
  switch (change.kind) {
    case 'activeView':
      return { ...view, app: { ...app, activeView: change.value } };
    case 'historyView':
      return { ...view, app: { ...app, historyView: change.value } };
    case 'historyRange':
      return { ...view, app: { ...app, historyRange: change.value } };
    case 'preset': {
      const previous = app.views[change.view];
      const next: ViewPreset = {
        numbers: change.numbers ?? previous.numbers,
        charts: change.charts ?? previous.charts,
        range: change.range ?? previous.range,
        webColumns: change.webColumns ?? previous.webColumns,
      };
      return { ...view, app: { ...app, views: { ...app.views, [change.view]: next } } };
    }
    default:
      return view;
  }
}

type ChartState = 'chartError' | 'settingsError' | 'awaitingData' | 'syncing' | 'noMeasurements';

function chartStatus(state: ChartState, detail?: string): { label: string; detail: string | null } {
  const text = kit.textsChartStatus(state);
  return { label: text?.label ?? '', detail: detail ?? (text?.detail || null) };
}
