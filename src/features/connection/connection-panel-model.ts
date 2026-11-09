import {
  kit,
  type ApiFailure,
  type Availability,
  type ConnectionDisplay,
  type ConnectionView,
  type FailureCode,
  type Result,
} from '../../../modules/power-log';
import {
  failureText,
  control,
  notice,
  run,
  StreamFailures,
  streamNotice,
  Snapshots,
  NoticeSlot,
  type ControlState,
  type Notice,
  type ScreenModel,
  type UiEnv,
  shellFailure,
} from '../../components/screen-model';

export interface ConnectionPanelState {
  ready: boolean;
  heading: string;
  status: string;
  accent: boolean;
  shownDisplay: ConnectionDisplay;
  bikeName: string | null;
  speedNote: string | null;
  fault: string | null;
  findBike: ControlState | null;
  stopSearch: ControlState | null;
  bikes: readonly { bikeId: string; control: ControlState }[];
  disconnect: ControlState | null;
  cancelConnection: ControlState | null;
  notice: Notice | null;
}

type Command = 'startSearch' | 'stopSearch' | `connect:${string}` | 'disconnect' | 'cancel';

const initial: ConnectionPanelState = {
  ready: false,
  heading: 'Your bike',
  status: kit.textsConnection('idle', 'unavailable'),
  accent: false,
  shownDisplay: 'unavailable',
  bikeName: null,
  speedNote: null,
  fault: null,
  findBike: null,
  stopSearch: null,
  bikes: [],
  disconnect: null,
  cancelConnection: null,
  notice: null,
};

export class ConnectionPanelModel implements ScreenModel<ConnectionPanelState> {
  private readonly snapshots = new Snapshots<ConnectionPanelState>(initial);
  private readonly inFlight = new Set<string>();
  private view: ConnectionView | null = null;
  private rideOpen = false;
  private failed: Notice | null = null;
  private readonly lost = new StreamFailures();
  private readonly streamFailure = new NoticeSlot();
  private stop: (() => void) | null = null;

  constructor(private readonly env: UiEnv) {}

  getSnapshot = (): ConnectionPanelState => this.snapshots.get();

  subscribe = (onChange: () => void): (() => void) => this.snapshots.subscribe(onChange);

  activate(): void {
    if (this.stop) return;
    const stopStream = this.env.client.subscribe(
      'connection.state',
      {},
      ({ value }) => {
        this.view = value;
        this.lost.answered('connection.state');
        this.publish();
      },
      failure => this.streamFailed('connection.state', failure),
    );
    const stopRecording = this.env.client.subscribe(
      'recording.state',
      {},
      ({ value }) => {
        this.rideOpen = value.ride !== null;
        this.lost.answered('recording.state');
        this.publish();
      },
      failure => this.streamFailed('recording.state', failure),
    );
    const stopShell = this.env.shell.subscribe(() => this.publish());
    this.stop = () => {
      stopStream();
      stopRecording();
      stopShell();
    };
  }

  deactivate(): void {
    this.stop?.();
    this.stop = null;
  }

  dispose(): void {
    this.deactivate();
  }

  findBike(): void {
    this.command('startSearch', () => this.env.client.call('connection.startSearch', {}));
  }

  stopSearch(): void {
    this.command('stopSearch', () => this.env.client.call('connection.stopSearch', {}));
  }

  connect(bikeId: string): void {
    this.command(`connect:${bikeId}`, () => this.env.client.call('connection.connect', { bikeId }));
  }

  disconnect(): void {
    this.command('disconnect', () => this.env.client.call('connection.disconnect', {}), true);
  }

  cancelConnection(): void {
    this.command('cancel', () => this.env.client.call('connection.disconnect', {}), true);
  }

  dismissNotice(): void {
    const shown = this.snapshots.get().notice;
    if (!shown) return;
    this.env.shell.dismiss(shown.id);
    if (this.failed?.id === shown.id) this.failed = null;
    this.lost.dismiss(shown.id);
    this.publish();
  }

  retry(): void {
    this.lost.clear();
    this.deactivate();
    this.activate();
    this.publish();
  }

  private streamFailed(stream: string, failure: ApiFailure): void {
    this.report(failure);
    if (shellFailure(failure.code)) return;
    this.lost.failed(stream, streamNotice(failure, this.text(failure.code)));
    this.publish();
  }

  openSettings(): void {
    this.env.ui.openAppSettings();
  }

  private command(key: Command, call: () => Promise<Result<unknown>>, showsFailure = false): void {
    run(this.inFlight, key, call, answer => {
      if ('ok' in answer && !answer.ok) {
        this.report(answer.failure);
        if (showsFailure && !shellFailure(answer.failure.code)) this.failed = this.notice(answer.failure);
      }
      this.publish();
    });
    this.publish();
  }

  private report(failure: ApiFailure): void {
    if (shellFailure(failure.code)) this.env.shell.report(failure);
  }

  private notice(failure: ApiFailure): Notice {
    const native = this.env.ui.kind === 'native';
    return notice(
      failure,
      this.text(failure.code),
      failure.code === 'bluetoothDenied' && native ? ['openAppSettings', 'dismiss'] : ['dismiss'],
    );
  }

  private text(code: FailureCode): string {
    return failureText(this.env.shell.display.current(), code);
  }

  private reason(availability: Availability, key: Command): string | null {
    if ([...this.inFlight].some(other => other !== key && other !== 'cancel')) return this.text('actionPending');
    return availability.enabled ? null : this.text(availability.reason ?? 'actionUnavailable');
  }

  private publish(): void {
    const view = this.view;
    if (!view) {
      const lost = this.lost.shown(id => this.env.shell.dismissed(id));
      this.snapshots.set({ ...this.snapshots.get(), notice: lost });
      return;
    }
    const { state, allowed, bike } = view;
    const shownDisplay: ConnectionDisplay = this.rideOpen
      ? view.display
      : state === 'connected'
        ? 'live'
        : 'unavailable';
    const name = bike ? kit.formatBikeName(bike.identity?.model ?? null, bike.advertisedName, bike.bikeId) : null;
    const streamFailure = this.streamFailure.take(view.failure, failure => this.notice(failure));
    const shown =
      [this.lost.shown(id => this.env.shell.dismissed(id)), streamFailure, this.failed].find(
        item => item && !this.env.shell.dismissed(item.id),
      ) ?? null;
    const busy = (key: Command) => this.inFlight.has(key);
    this.snapshots.set({
      ready: true,
      heading: name ?? 'Your bike',
      status: kit.textsConnection(state, shownDisplay),
      accent: shownDisplay !== 'unavailable',
      shownDisplay,
      bikeName: shownDisplay !== 'unavailable' ? name : null,
      speedNote: state === 'connected' ? (kit.textsControllerSpeedNote(bike?.controllerSpeed ?? null) ?? null) : null,
      fault: view.faultCode && view.display !== 'unavailable' ? kit.textsControllerFault(view.faultCode) : null,
      findBike:
        state === 'idle' || state === 'unavailable'
          ? control('Find bike', busy('startSearch'), this.reason(allowed.startSearch, 'startSearch'))
          : null,
      stopSearch:
        state === 'searching'
          ? control('Stop search', busy('stopSearch'), this.reason(allowed.stopSearch, 'stopSearch'))
          : null,
      bikes:
        state === 'idle' || state === 'searching'
          ? view.found.map(found => ({
              bikeId: found.bikeId,
              control: control(
                `Connect ${kit.formatBikeName(found.known?.model ?? null, found.advertisedName, found.bikeId)}`,
                busy(`connect:${found.bikeId}`),
                this.reason(allowed.connect, `connect:${found.bikeId}`),
              ),
            }))
          : [],
      disconnect:
        state === 'connected'
          ? control('Disconnect', busy('disconnect'), this.reason(allowed.disconnect, 'disconnect'))
          : null,
      cancelConnection:
        state === 'connecting' || state === 'reconnecting' || [...this.inFlight].some(key => key.startsWith('connect:'))
          ? control('Cancel connection', busy('cancel'), busy('cancel') ? this.text('actionPending') : null)
          : null,
      notice: shown,
    });
  }
}
