import {
  kit,
  type ApiFailure,
  type FailureCode,
  type HistoryPage,
  type HistoryRow,
  type ImportedRideView,
  type Result,
  type Shared,
} from '../../../modules/power-log';
import {
  failureText,
  control,
  notice,
  run,
  Snapshots,
  streamNotice,
  type ControlState,
  type Notice,
  type ScreenModel,
  type UiEnv,
} from '../../components/screen-model';

export interface HistoryRowState {
  rideId: string;
  title: string;
  detail: string;
  health: string | null;
  phase: string | null;
  failed: boolean;
  open: ControlState;
  remove: ControlState;
}

export interface DeleteSheetState {
  rideId: string;
  mode: 'delete' | 'discard';
  title: string;
  date: string;
  body: string;
  confirm: ControlState;
  keep: ControlState;
  error: string | null;
}

export interface HistoryListState {
  loading: boolean;
  refreshing: boolean;
  rows: readonly HistoryRowState[];
  count: string;
  more: ControlState | null;
  editing: boolean;
  deleteSheet: DeleteSheetState | null;
  openCsv: ControlState;
  imported: ImportedRideView | null;
  notice: Notice | null;
}

export type ExportKind = 'fit' | 'csv' | 'zip';

const pageKey = 'history:list:page';
const moreKey = 'history:list:more';
const csvKey = 'history:list:csv';

export class HistoryListModel implements ScreenModel<HistoryListState> {
  private readonly snapshots = new Snapshots<HistoryListState>({
    loading: true,
    refreshing: false,
    rows: [],
    count: '',
    more: null,
    editing: false,
    deleteSheet: null,
    openCsv: control('Open CSV', false, null),
    imported: null,
    notice: null,
  });
  private readonly inFlight = new Set<string>();
  private readonly exports = new Map<string, AbortController>();
  private readonly exportListeners = new Set<() => void>();
  private rows: HistoryRow[] | null = null;
  private next: string | null = null;
  private failed: Notice | null = null;
  private lost: Notice | null = null;
  private revision: number | null = null;
  private query: AbortController | null = null;
  private more: AbortController | null = null;
  private moreWanted = false;
  private editing = false;
  private deleteTarget: {
    rideId: string;
    mode: 'delete' | 'discard';
    error: string | null;
    onDone: (() => void) | null;
  } | null = null;
  private imported: ImportedRideView | null = null;
  private selection = 0;
  private disposed = false;
  private stop: (() => void) | null = null;
  private stopBackground: (() => void) | null = null;

  constructor(private readonly env: UiEnv) {}

  getSnapshot = (): HistoryListState => this.snapshots.get();

  subscribe = (onChange: () => void): (() => void) => this.snapshots.subscribe(onChange);

  activate(): void {
    if (this.stop) return;
    const stopStream = this.env.client.subscribe(
      'history.list',
      {},
      ({ value }) => {
        const changed = this.revision !== null && value.revision !== this.revision;
        this.revision = value.revision;
        if (this.lost) {
          this.lost = null;
          this.publish();
        }
        if (changed) this.reload();
      },
      failure => {
        this.report(failure);
        if (shellFailure(failure.code)) return;
        this.lost = streamNotice(failure, this.text(failure.code));
        this.publish();
      },
    );
    const stopShell = this.env.shell.subscribe(() => this.publish());
    this.stop = () => {
      stopStream();
      stopShell();
    };
    if (this.rows === null) this.loadFirst();
    else this.reload();
  }

  deactivate(): void {
    this.stop?.();
    this.stop = null;
    this.query?.abort();
    this.query = null;
    this.more?.abort();
    this.more = null;
    this.moreWanted = false;
  }

  dispose(): void {
    this.deactivate();
    this.disposed = true;
    this.abortExports();
    this.followBackground();
    if (this.imported) this.closeImport();
  }

  retry(): void {
    const resubscribe = this.lost !== null;
    this.dismissNotice();
    if (resubscribe) {
      this.lost = null;
      this.deactivate();
      this.activate();
    } else this.reload();
  }

  refresh(): void {
    this.reload();
  }

  dismissNotice(): void {
    const shown = this.snapshots.get().notice;
    if (shown) this.env.shell.dismiss(shown.id);
    if (this.failed?.id === shown?.id) this.failed = null;
    if (this.lost?.id === shown?.id) this.lost = null;
    this.publish();
  }

  openRide(rideId: string): void {
    const row = this.rows?.find(candidate => candidate.rideId === rideId);
    if (!row?.open.enabled) return;
    this.selection++;
    this.env.ui.navigate({ pathname: '/sessions/ride', params: { id: rideId } });
  }

  rideSelected(): void {
    this.selection++;
  }

  loadMore(): void {
    if (this.query) {
      this.moreWanted = true;
      return;
    }
    if (this.next === null || this.more) return;
    const query = new AbortController();
    this.more = query;
    const after = this.next;
    void this.env.client.call('history.page', { after, key: moreKey }, { signal: query.signal }).then(answer => {
      if (this.more !== query || 'cancelled' in answer) return;
      this.more = null;
      if (answer.ok) {
        this.rows = [...(this.rows ?? []), ...answer.value.rows];
        this.next = answer.value.next;
      } else this.pageFailed(answer.failure);
      this.publish();
    });
    this.publish();
  }

  toggleEdit(): void {
    this.editing = !this.editing && (this.rows?.length ?? 0) > 0;
    this.publish();
  }

  askToDelete(rideId: string, interrupted?: boolean, onDone?: () => void): void {
    const row = this.rows?.find(candidate => candidate.rideId === rideId);
    const discard = interrupted ?? row?.phase === 'interrupted';
    this.deleteTarget = { rideId, mode: discard ? 'discard' : 'delete', error: null, onDone: onDone ?? null };
    this.publish();
  }

  keepRide(): void {
    if (this.inFlight.has('delete')) return;
    this.deleteTarget = null;
    this.publish();
  }

  confirmDelete(): void {
    const target = this.deleteTarget;
    if (!target) return;
    run(
      this.inFlight,
      'delete',
      () =>
        target.mode === 'discard'
          ? this.env.client.call('recording.control', { rideId: target.rideId, action: 'discard' })
          : this.env.client.call('history.delete', { rideId: target.rideId }),
      answer => {
        if ('cancelled' in answer) return;
        if (answer.ok) {
          this.rows = this.rows?.filter(row => row.rideId !== target.rideId) ?? null;
          if (!this.rows?.length) this.editing = false;
          if (this.deleteTarget?.rideId === target.rideId) this.deleteTarget = null;
          target.onDone?.();
        } else {
          this.report(answer.failure);
          if (this.deleteTarget?.rideId === target.rideId)
            this.deleteTarget = { ...this.deleteTarget, error: this.text(answer.failure.code) };
        }
        this.publish();
      },
    );
    this.publish();
  }

  openCsv(): void {
    const token = ++this.selection;
    run(
      this.inFlight,
      'csv',
      () => this.env.client.call('imports.open', { key: csvKey }),
      answer => {
        if ('cancelled' in answer) return;
        if (answer.ok) {
          if (token !== this.selection || this.disposed) {
            void this.env.client.call('imports.close', { importId: answer.value.importId });
          } else {
            if (this.imported) void this.env.client.call('imports.close', { importId: this.imported.importId });
            this.imported = answer.value;
          }
        } else {
          this.report(answer.failure);
          if (!shellFailure(answer.failure.code))
            this.failed = notice(answer.failure, this.text(answer.failure.code), ['dismiss']);
        }
        this.publish();
      },
    );
    this.publish();
  }

  closeImport(): void {
    const imported = this.imported;
    if (!imported) return;
    this.imported = null;
    this.selection++;
    void this.env.client.call('imports.close', { importId: imported.importId });
    this.publish();
  }

  exporting(rideId: string): boolean {
    return this.exports.has(rideId);
  }

  onExports(listener: () => void): () => void {
    this.exportListeners.add(listener);
    return () => {
      this.exportListeners.delete(listener);
    };
  }

  exportRide(rideId: string, kind: ExportKind, done: (answer: Result<Shared>) => void): void {
    this.export(
      rideId,
      signal => this.env.exports.shareRide(rideId, kind, this.env.shell.display.current().fitPower, signal),
      done,
    );
  }

  exportImported(importId: string, name: string, done: (answer: Result<Shared>) => void): void {
    this.export(`import:${importId}`, signal => this.env.exports.shareImported(importId, name, signal), done);
  }

  private export(
    key: string,
    share: (signal: AbortSignal) => Promise<Result<Shared>>,
    done: (answer: Result<Shared>) => void,
  ): void {
    if (this.exports.has(key)) return;
    const signal = new AbortController();
    this.exports.set(key, signal);
    this.followBackground();
    this.exportsChanged();
    void share(signal.signal).then(answer => {
      if (this.exports.get(key) === signal) this.exports.delete(key);
      this.followBackground();
      this.exportsChanged();
      done(answer);
    });
  }

  private followBackground(): void {
    const exporting = this.exports.size > 0 && !this.disposed;
    if (exporting && !this.stopBackground)
      this.stopBackground = this.env.ui.background.subscribe(background => {
        if (background) this.abortExports();
      });
    if (!exporting && this.stopBackground) {
      this.stopBackground();
      this.stopBackground = null;
    }
  }

  private exportsChanged(): void {
    for (const listener of [...this.exportListeners]) listener();
  }

  private abortExports(): void {
    for (const signal of this.exports.values()) signal.abort();
  }

  private loadFirst(): void {
    this.page(null, pageKey, page => {
      this.rows = page.rows;
      this.next = page.next;
    });
  }

  private reload(): void {
    const target = Math.max(this.rows?.length ?? 0, 1);
    const rows: HistoryRow[] = [];
    const step = (after: string | null) =>
      this.page(after, pageKey, page => {
        rows.push(...page.rows);
        if (rows.length < target && page.next !== null) {
          step(page.next);
          return false;
        }
        this.rows = rows;
        this.next = page.next;
        if (!rows.length) this.editing = false;
        return true;
      });
    step(null);
  }

  private page(after: string | null, key: string, take: (page: HistoryPage) => boolean | void): void {
    if (this.more) {
      this.more.abort();
      this.more = null;
      this.moreWanted = true;
    }
    this.query?.abort();
    const query = new AbortController();
    this.query = query;
    if (this.rows) this.publish();
    void this.env.client.call('history.page', { after, key }, { signal: query.signal }).then(answer => {
      if (this.query !== query || 'cancelled' in answer) return;
      if (answer.ok) {
        if (take(answer.value) === false) return;
        this.query = null;
        this.failed = null;
        if (this.moreWanted) {
          this.moreWanted = false;
          this.loadMore();
        }
      } else {
        this.query = null;
        this.pageFailed(answer.failure);
      }
      this.publish();
    });
  }

  private pageFailed(failure: ApiFailure): void {
    this.report(failure);
    if (!shellFailure(failure.code)) this.failed = notice(failure, this.text(failure.code), ['retry', 'dismiss']);
  }

  private report(failure: ApiFailure): void {
    if (shellFailure(failure.code)) this.env.shell.report(failure);
  }

  private text(code: FailureCode): string {
    return failureText(this.env.shell.display.current(), code);
  }

  private title(row: HistoryRow | undefined): string {
    if (!row) return '';
    const display = this.env.shell.display.current();
    return kit.rideDate(row.startedUtcUs, Date.now() * 1000, display.uses24h, display.locale);
  }

  private deletionText(row: HistoryRow | undefined): string {
    const provider = row?.health?.provider;
    return `This permanently removes the recording and charts from Power Log.${
      provider ? ` Workouts already saved in ${kit.textsProvider(provider)} stay there.` : ''
    }`;
  }

  private publish(): void {
    const rows = this.rows ?? [];
    const deleting = this.inFlight.has('delete');
    const target = this.deleteTarget;
    this.snapshots.set({
      loading: this.rows === null && this.query !== null,
      refreshing: this.rows !== null && this.query !== null,
      rows: rows.map(row => ({
        rideId: row.rideId,
        title: this.title(row),
        detail: [
          row.example ? 'Example' : 'Recorded',
          row.indoor ? 'Indoor' : 'Outdoor',
          kit.formatDuration(row.elapsedUs),
        ].join(' · '),
        health: row.health ? (kit.textsHistoryHealthLine(row.health, row.health.components) ?? null) : null,
        phase: kit.textsHistoryPhase(row.phase, row.interrupted) || null,
        failed: row.phase === 'failed',
        open: control('Open ride', false, row.open.enabled ? null : this.text(row.open.reason ?? 'actionUnavailable')),
        remove: control(
          row.phase === 'interrupted' ? 'Discard ride' : 'Delete ride',
          deleting && target?.rideId === row.rideId,
          deleting && target?.rideId !== row.rideId
            ? this.text('actionPending')
            : row.delete.enabled
              ? null
              : this.text(row.delete.reason ?? 'actionUnavailable'),
        ),
      })),
      count: kit.textsRideCount(rows.length),
      more: this.next === null ? null : control('Load more rides', this.more !== null || this.moreWanted, null),
      editing: this.editing,
      deleteSheet: target
        ? {
            rideId: target.rideId,
            mode: target.mode,
            title: target.mode === 'discard' ? 'Discard ride?' : 'Delete ride?',
            date: this.title(rows.find(row => row.rideId === target.rideId)),
            body:
              target.mode === 'discard'
                ? 'Discarding permanently removes this ride from Power Log and ends recording.'
                : this.deletionText(rows.find(row => row.rideId === target.rideId)),
            confirm: control(
              deleting
                ? target.mode === 'discard'
                  ? 'Discarding…'
                  : 'Deleting…'
                : target.mode === 'discard'
                  ? 'Discard ride'
                  : 'Delete ride',
              deleting,
              null,
            ),
            keep: control('Keep ride', false, deleting ? this.text('actionPending') : null),
            error: target.error,
          }
        : null,
      openCsv: control('Open CSV', this.inFlight.has('csv'), null),
      imported: this.imported,
      notice: [this.lost, this.failed].find(item => item && !this.env.shell.dismissed(item.id)) ?? null,
    });
  }
}

const shellFailure = (code: FailureCode) => code === 'storageRefused' || code === 'apiMismatch';
