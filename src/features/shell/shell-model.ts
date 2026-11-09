import type {
  ApiFailure,
  ConnectionView,
  DeviceCapabilities,
  DeviceInfoView,
  FitPowerSource,
  PersistenceState,
  Platform,
  PowerLogClient,
  RecordingView,
  SettingsView,
  StreamName,
  StreamParams,
  StreamValue,
  TemperatureUnit,
  UnitSystem,
} from '../../../modules/power-log';
import { timerText } from '../../components/ride-timer';
import {
  failureText,
  notice,
  StreamFailures,
  NoticeSlot,
  Snapshots,
  type Notice,
  type Observable,
  type ScreenModel,
} from '../../components/screen-model';
import type { UiPlatform } from '../../components/ui-platform';

export interface DisplayContext {
  platform: Platform;
  units: UnitSystem;
  temperature: TemperatureUnit;
  fitPower: FitPowerSource;
  uses24h: boolean;
  locale: string;
  appVersion: string;
  developerTools: boolean;
  sdkLevel: number | null;
  capabilities: DeviceCapabilities;
  storage: PersistenceState | null;
}

export interface ActiveRideLink {
  text: string;
  paused: boolean;
}

export interface ShellState {
  mode: 'starting' | 'ready' | 'refused' | 'mismatch';
  message: string | null;
  notice: Notice | null;
  bikeNotice: Notice | null;
  activeRide: ActiveRideLink | null;
}

export interface ShellModel extends ScreenModel<ShellState> {
  readonly display: Observable<DisplayContext>;
  report(failure: ApiFailure): void;
  dismiss(id: string): void;
  dismissed(id: string): boolean;
  retry(): void;
}

const startingDisplay = (platform: Platform): DisplayContext => ({
  platform,
  units: 'metric',
  temperature: 'celsius',
  fitPower: 'rider',
  uses24h: false,
  locale: 'en',
  appVersion: '',
  developerTools: false,
  sdkLevel: null,
  capabilities: { recording: false, bluetooth: false, gps: false, health: null, watch: false, diagnosticLog: false },
  storage: null,
});

export class AppShellModel implements ShellModel {
  private readonly snapshots = new Snapshots<ShellState>({
    mode: 'starting',
    message: null,
    notice: null,
    bikeNotice: null,
    activeRide: null,
  });
  private readonly displays: Snapshots<DisplayContext>;
  private readonly hidden = new Set<string>();
  private readonly dismissals = new Snapshots<number>(0);
  private readonly bikeFailure = new NoticeSlot();
  private mode: ShellState['mode'] = 'starting';
  private message: string | null = null;
  private readonly failures = new StreamFailures();
  private device: DeviceInfoView | null = null;
  private settings: SettingsView | null = null;
  private recording: RecordingView | null = null;
  private connection: ConnectionView | null = null;
  private navigatedFor: string | null = null;
  private stops: (() => void)[] = [];
  private tick: ReturnType<typeof setInterval> | undefined;
  private stopCompatibility: (() => void) | null = null;

  readonly display: Observable<DisplayContext> = {
    current: () => this.displays.get(),
    subscribe: listener => this.displays.subscribe(() => listener(this.displays.get())),
  };

  constructor(
    private readonly client: PowerLogClient,
    private readonly ui: UiPlatform,
    platform: Platform,
  ) {
    this.displays = new Snapshots<DisplayContext>(startingDisplay(platform));
  }

  getSnapshot = (): ShellState => this.snapshots.get();

  subscribe = (onChange: () => void): (() => void) => {
    const stopState = this.snapshots.subscribe(onChange);
    const stopDismissals = this.dismissals.subscribe(onChange);
    return () => {
      stopState();
      stopDismissals();
    };
  };

  activate(): void {
    if (this.stops.length) return;
    this.stopCompatibility ??= this.client.onCompatibility(state => {
      if (state === 'incompatible') this.mismatch();
    });
    if (this.client.compatibility === 'incompatible') this.mismatch();
    this.stops = [
      this.follow('device.info', {}, value => {
        this.device = value;
        if (this.mode === 'starting') this.mode = 'ready';
      }),
      this.follow('settings.state', {}, value => {
        this.settings = value;
      }),
      this.follow('recording.state', {}, value => {
        this.recording = value;
        this.followFinishRequest(value);
      }),
      this.follow('connection.state', {}, value => {
        this.connection = value;
      }),
    ];
  }

  deactivate(): void {
    for (const stop of this.stops.splice(0)) stop();
    this.followTimer(false);
  }

  dispose(): void {
    this.deactivate();
    this.stopCompatibility?.();
    this.stopCompatibility = null;
  }

  retry(): void {
    this.failures.clear();
    this.deactivate();
    this.activate();
    this.publish();
  }

  report(failure: ApiFailure): void {
    if (failure.code === 'apiMismatch') this.mismatch();
    else if (failure.code === 'storageRefused' && this.mode !== 'mismatch') {
      this.mode = 'refused';
      this.message = failureText(this.displays.get(), 'storageRefused', failure.detail);
      this.publish();
    }
  }

  dismiss(id: string): void {
    if (this.hidden.has(id)) return;
    this.hidden.add(id);
    this.publish(true);
    this.dismissals.set(this.dismissals.get() + 1);
  }

  dismissed(id: string): boolean {
    return this.hidden.has(id);
  }

  private follow<S extends StreamName>(stream: S, params: StreamParams<S>, take: (value: StreamValue<S>) => void) {
    return this.client.subscribe(
      stream,
      params,
      ({ value }) => {
        take(value);
        this.failures.answered(stream);
        this.publish();
      },
      failure => this.streamFailed(stream, failure),
    );
  }

  private streamFailed(stream: string, failure: ApiFailure): void {
    if (failure.code === 'storageRefused' || failure.code === 'apiMismatch') {
      this.report(failure);
      return;
    }
    if (this.failures.has(stream, failure.code)) return;
    this.failures.failed(stream, notice(failure, failureText(this.displays.get(), failure.code), ['retry']));
    this.publish();
  }

  private followFinishRequest(view: RecordingView): void {
    const request = view.finishRequest;
    if (!request || request.requestId === this.navigatedFor || this.hidden.has(request.requestId)) return;
    this.navigatedFor = request.requestId;
    this.ui.navigate('/');
  }

  private followTimer(running: boolean): void {
    if (running && this.stops.length && this.tick === undefined) this.tick = setInterval(() => this.publish(), 1000);
    if (!running && this.tick !== undefined) {
      clearInterval(this.tick);
      this.tick = undefined;
    }
  }

  private mismatch(): void {
    this.mode = 'mismatch';
    this.message = failureText(this.displays.get(), 'apiMismatch');
    this.publish();
  }

  private activeRide(): ActiveRideLink | null {
    const view = this.recording;
    if (!view?.ride) return null;
    const paused = view.phase === 'paused';
    return {
      paused,
      text: `● ${paused ? 'Ride paused' : 'Ride in progress'} · ${timerText(view.timer, this.client)} →`,
    };
  }

  private publish(dismissed = false): void {
    const device = this.device;
    const app = this.settings?.app;
    const base = this.displays.get();
    if (!dismissed)
      this.displays.set({
        ...base,
        ...(device
          ? {
              platform: device.platform,
              uses24h: device.uses24h,
              locale: device.locale,
              appVersion: device.appVersion,
              developerTools: device.developerTools,
              sdkLevel: device.sdkLevel,
              capabilities: device.capabilities,
              storage: device.storage,
            }
          : {}),
        ...(app ? { units: app.units, temperature: app.temperature, fitPower: app.fitPower } : {}),
      });
    const display = this.displays.get();
    const bike = this.bikeFailure.take(this.connection?.failure ?? null, failure =>
      notice(failure, failureText(display, failure.code), ['dismiss']),
    );
    this.followTimer(this.recording?.ride != null && this.recording.timer.state === 'running');
    this.snapshots.set({
      mode: this.mode,
      message: this.message,
      notice: this.failures.shown(id => this.hidden.has(id)),
      bikeNotice: bike && !this.hidden.has(bike.id) ? bike : null,
      activeRide: this.activeRide(),
    });
  }
}
