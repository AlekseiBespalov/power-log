import {
  kit,
  type ApiFailure,
  type Availability,
  type FailureCode,
  type LatLon,
  type MetricId,
  type Result,
  type RideDetailView,
  type Shared,
  type SummaryState,
  type SummaryView,
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
  shellFailure,
} from '../../components/screen-model';
import type { ExportKind, HistoryListModel } from './history-list-model';

export interface SummaryItem {
  label: string;
  value: string;
  unit: string;
  tone: 'accent' | 'cadence' | 'red' | null;
}

export interface RideHealthState {
  label: string;
  onWatch: boolean;
  reason: string | null;
  components: readonly string[];
}

export interface RideDetailState {
  ready: boolean;
  title: string;
  subtitle: string;
  summaryState: SummaryState;
  summary: readonly SummaryItem[];
  health: RideHealthState | null;
  route: LatLon[][] | null;
  exports: { fit: ControlState; csv: ControlState; zip: ControlState };
  strava: ControlState | null;
  healthRepair: ControlState | null;
  finish: ControlState | null;
  discard: ControlState | null;
  remove: ControlState | null;
  notice: Notice | null;
}

const stravaUrl = 'https://www.strava.com/upload/select';

export class RideDetailModel implements ScreenModel<RideDetailState> {
  private readonly snapshots: Snapshots<RideDetailState>;
  private readonly inFlight = new Set<string>();
  private view: RideDetailView | null = null;
  private failed: Notice | null = null;
  private streamFailure: Notice | null = null;
  private stop: (() => void) | null = null;

  constructor(
    private readonly env: UiEnv,
    private readonly rideId: string,
    private readonly list: HistoryListModel,
  ) {
    this.snapshots = new Snapshots<RideDetailState>(this.state());
  }

  getSnapshot = (): RideDetailState => this.snapshots.get();

  subscribe = (onChange: () => void): (() => void) => this.snapshots.subscribe(onChange);

  activate(): void {
    if (this.stop) return;
    this.list.rideSelected();
    const stopStream = this.env.client.subscribe(
      'history.ride',
      { rideId: this.rideId },
      ({ value }) => {
        this.view = value;
        this.streamFailure = null;
        this.publish();
      },
      failure => this.rideFailed(failure),
    );
    const stopExports = this.list.onExports(() => this.publish());
    const stopShell = this.env.shell.subscribe(() => this.publish());
    this.stop = () => {
      stopStream();
      stopExports();
      stopShell();
    };
    this.publish();
  }

  deactivate(): void {
    this.stop?.();
    this.stop = null;
  }

  dispose(): void {
    this.deactivate();
  }

  exportRide(kind: ExportKind): void {
    if (!this.view?.allowed.export.enabled || this.busy()) return;
    this.list.exportRide(this.rideId, kind, answer => this.answered(answer));
    this.publish();
  }

  openStrava(): void {
    this.env.ui.openUrl(stravaUrl);
  }

  repairHealth(): void {
    const allowed = this.view?.allowed;
    if (!allowed || this.busy()) return;
    const op = allowed.healthRetry.enabled ? 'health.retrySave' : 'health.refresh';
    this.command('health', () => this.env.client.call(op, { rideId: this.rideId }));
  }

  finishSavedPortion(): void {
    if (!this.view?.allowed.finish.enabled || this.busy()) return;
    this.command('finish', () => this.env.client.call('recording.control', { rideId: this.rideId, action: 'finish' }));
  }

  askToDelete(): void {
    const interrupted = this.view?.ride.phase === 'interrupted';
    this.list.askToDelete(this.rideId, interrupted, () => this.env.ui.dismissTo('/sessions'));
  }

  dismissNotice(): void {
    const shown = this.snapshots.get().notice;
    if (!shown) return;
    this.env.shell.dismiss(shown.id);
    if (this.failed?.id === shown.id) this.failed = null;
    if (this.streamFailure?.id === shown.id) this.streamFailure = null;
    this.publish();
  }

  retry(): void {
    this.streamFailure = null;
    this.deactivate();
    this.activate();
  }

  private busy(): boolean {
    return this.inFlight.size > 0 || this.list.exporting(this.rideId);
  }

  private command(key: string, call: () => Promise<Result<unknown>>): void {
    run(this.inFlight, key, call, answer => this.answered(answer));
    this.publish();
  }

  private answered(answer: Result<Shared> | Result<unknown>): void {
    if ('cancelled' in answer) return;
    if (!answer.ok) {
      this.report(answer.failure);
      if (!shellFailure(answer.failure.code))
        this.failed = notice(answer.failure, this.text(answer.failure.code), ['dismiss']);
    }
    this.publish();
  }

  private rideFailed(failure: ApiFailure): void {
    if (failure.code === 'rideDeleted') {
      this.deactivate();
      this.env.ui.dismissTo('/sessions');
      return;
    }
    this.report(failure);
    if (!shellFailure(failure.code)) this.streamFailure = streamNotice(failure, this.text(failure.code));
    this.publish();
  }

  private report(failure: ApiFailure): void {
    if (shellFailure(failure.code)) this.env.shell.report(failure);
  }

  private text(code: FailureCode): string {
    return failureText(this.env.shell.display.current(), code);
  }

  private reason(availability: Availability, key: string): string | null {
    if (this.list.exporting(this.rideId)) return this.text('exportInProgress');
    if ([...this.inFlight].some(other => other !== key)) return this.text('actionPending');
    return availability.enabled ? null : this.text(availability.reason ?? 'actionUnavailable');
  }

  private summary(summary: SummaryView | null): SummaryItem[] {
    if (!summary) return [];
    const { units } = this.env.shell.display.current();
    const metric = (id: MetricId, value: number | null) => kit.formatMetric(id, value, units, 'celsius');
    const symbol = (id: MetricId) => kit.unitsDisplay(id, units, 'celsius')?.symbol ?? '';
    const source = summary.batteryMethod === 'controller' ? 'controller' : 'calc';
    const backfilled = summary.physiologyBackfilled ? ' (backfilled)' : '';
    const items: SummaryItem[] = [
      { label: 'Ride time', value: kit.formatDuration(summary.timerUs), unit: '', tone: null },
      {
        label: 'Distance',
        value: kit.formatDistance(summary.distanceM, units),
        unit: symbol('distanceMeters'),
        tone: null,
      },
      { label: 'Avg speed', value: metric('speedMps', summary.averageSpeedMps), unit: symbol('speedMps'), tone: null },
      { label: `Energy used (${source})`, value: kit.formatDecimal(summary.energyWh, 2), unit: 'Wh', tone: null },
      { label: `Charge used (${source})`, value: kit.formatDecimal(summary.chargeAh, 2), unit: 'Ah', tone: null },
      { label: 'Avg power', value: kit.formatDecimal(summary.averagePowerW, 0), unit: 'W', tone: 'accent' },
      { label: 'Avg cadence', value: kit.formatDecimal(summary.averageCadenceRpm, 0), unit: 'rpm', tone: 'cadence' },
    ];
    if (summary.averageHeartRateBpm !== null)
      items.push({
        label: `Avg heart rate${backfilled}`,
        value: kit.formatDecimal(summary.averageHeartRateBpm, 0),
        unit: 'bpm',
        tone: 'red',
      });
    if (summary.activeEnergyKcal !== null)
      items.push({
        label: `Active energy${backfilled}`,
        value: kit.formatDecimal(summary.activeEnergyKcal, 0),
        unit: 'kcal',
        tone: null,
      });
    if (summary.ascentM !== null)
      items.push({
        label: 'Ascent',
        value: metric('altitudeMeters', summary.ascentM),
        unit: symbol('altitudeMeters'),
        tone: null,
      });
    return items;
  }

  private health(view: RideDetailView): RideHealthState | null {
    const { health, ride } = view;
    if (!health || this.env.shell.display.current().platform === 'web') return null;
    const { options } = ride;
    return {
      label: kit.textsHealth(health),
      onWatch:
        options.useWatch && options.saveToHealth && (health.provider === null || health.provider === 'appleHealth'),
      reason: kit.textsHealthReason(health) ?? null,
      components: kit.textsHealthComponents(health.components, health.provider),
    };
  }

  private state(): RideDetailState {
    const view = this.view;
    const display = this.env.shell.display.current();
    const exportControl = (label: string): ControlState =>
      view
        ? control(label, this.list.exporting(this.rideId), this.reason(view.allowed.export, 'export'))
        : control(label, true, null);
    const shown = [this.failed, this.streamFailure].find(item => item && !this.env.shell.dismissed(item.id)) ?? null;
    if (!view)
      return {
        ready: false,
        title: '',
        subtitle: '',
        summaryState: 'pending',
        summary: [],
        health: null,
        route: null,
        exports: {
          fit: exportControl('Export FIT'),
          csv: exportControl('Export CSV'),
          zip: exportControl('Export ZIP'),
        },
        strava: null,
        healthRepair: null,
        finish: null,
        discard: null,
        remove: null,
        notice: shown,
      };
    const { ride, allowed } = view;
    const interrupted = ride.phase === 'interrupted';
    const repair = allowed.healthRetry.enabled ? allowed.healthRetry : allowed.healthRefresh;
    return {
      ready: true,
      title: kit.rideDate(ride.startedUtcUs, Date.now() * 1000, display.uses24h, display.locale),
      subtitle: [
        ride.example ? 'Example' : 'Recorded',
        ride.options.indoor ? 'Indoor' : 'Outdoor',
        kit.textsRecordedOn(ride.recordedBy, ride.options.useWatch),
        view.laps > 0 ? kit.textsLapCount(view.laps) : null,
        kit.textsHistoryPhase(ride.phase, ride.interrupted),
      ]
        .filter(Boolean)
        .join(' · '),
      summaryState: view.summaryState,
      summary: this.summary(view.summary),
      health: this.health(view),
      route: ride.options.recordGps && view.summaryState === 'ready' ? (view.route ?? []) : null,
      exports: { fit: exportControl('Export FIT'), csv: exportControl('Export CSV'), zip: exportControl('Export ZIP') },
      strava: ride.example ? null : control('Open Strava upload', false, null),
      healthRepair:
        allowed.healthRetry.enabled || allowed.healthRefresh.enabled
          ? control(
              allowed.healthRetry.enabled ? 'Retry Health save' : 'Refresh Health data',
              this.inFlight.has('health'),
              this.reason(repair, 'health'),
            )
          : null,
      finish: interrupted
        ? control('Finish saved portion', this.inFlight.has('finish'), this.reason(allowed.finish, 'finish'))
        : null,
      discard: interrupted ? control('Discard ride', false, this.reason(allowed.discard, 'discard')) : null,
      remove: interrupted ? null : control('Delete ride', false, this.reason(allowed.delete, 'delete')),
      notice: shown,
    };
  }

  private publish(): void {
    this.snapshots.set(this.state());
  }
}
