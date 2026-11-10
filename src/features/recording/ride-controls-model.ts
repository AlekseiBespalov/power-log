import {
  kit,
  type ApiFailure,
  type Availability,
  type ConnectionView,
  type CueView,
  type FailureCode,
  type PermissionsView,
  type RecordingView,
  type Result,
  type RideAction,
  type RideOptions,
  type RidePhase,
} from '../../../modules/power-log';
import { timerText } from '../../components/ride-timer';
import {
  failureText,
  control,
  notice,
  NoticeSlot,
  run,
  Snapshots,
  StreamFailures,
  streamNotice,
  type Chip,
  type ControlState,
  type Notice,
  type ScreenModel,
  type UiEnv,
  shellFailure,
} from '../../components/screen-model';
import type { HapticKind } from '../../components/ui-platform';

export interface PermissionRow {
  label: string;
  detail: string | null;
  error: string | null;
  button: 'request' | 'refresh' | 'openAppSettings' | 'healthHelp' | null;
}

export interface SourceLine {
  title: string;
  text: string;
  tone: 'ready' | 'warning' | 'neutral';
}

export interface RideDetails {
  indoor: boolean;
  watch: boolean;
  distanceSource: string;
  health: { label: string; reason: string | null; components: readonly string[] } | null;
  sources: readonly SourceLine[];
}

export interface FinishSheetState {
  step: 'confirm' | 'discard';
  save: ControlState;
  discard: ControlState;
  keepRecording: ControlState;
  error: string | null;
}

export interface RideControlsState {
  ready: boolean;
  supported: boolean;
  phase: RidePhase | null;
  rideId: string | null;
  timer: string;
  chip: Chip | null;
  details: RideDetails | null;
  bar: { label: string; opensSetup: boolean } | null;
  start: ControlState;
  startReason: string | null;
  cancelStart: ControlState | null;
  pauseOrResume: ControlState | null;
  lap: ControlState | null;
  finish: ControlState | null;
  stop: ControlState | null;
  reviewRide: boolean;
  waiting: string | null;
  setupOpen: boolean;
  bikeShown: boolean;
  ownerForeign: string | null;
  permission: PermissionRow | null;
  notice: Notice | null;
  finishSheet: FinishSheetState | null;
}

type Command = 'start' | 'cancelStart' | 'pause' | 'resume' | 'lap' | 'finish' | 'discard' | 'permissions';

const waitingTexts: Partial<Record<RideAction, string>> = {
  finish: 'Saving…',
  discard: 'Discarding…',
  pause: 'Waiting for the ride to respond…',
  resume: 'Waiting for the ride to respond…',
  lap: 'Waiting for the ride to respond…',
};

const beepTexts = { resume: 'Resuming on the beep…', finish: 'Finishing on the beep…' } as const;

const sourceTitles = { controller: 'Bike', heartRate: 'Heart rate', gps: 'GPS' } as const;

const initialControl = control('Start ride', false, null);

const initial: RideControlsState = {
  ready: false,
  supported: true,
  phase: null,
  rideId: null,
  timer: '—',
  chip: null,
  details: null,
  bar: null,
  start: initialControl,
  startReason: null,
  cancelStart: null,
  pauseOrResume: null,
  lap: null,
  finish: null,
  stop: null,
  reviewRide: false,
  waiting: null,
  setupOpen: false,
  bikeShown: false,
  ownerForeign: null,
  permission: null,
  notice: null,
  finishSheet: null,
};

export class RideControlsModel implements ScreenModel<RideControlsState> {
  private readonly snapshots = new Snapshots<RideControlsState>(initial);
  private readonly inFlight = new Set<string>();
  private recording: RecordingView | null = null;
  private connection: ConnectionView | null = null;
  private permissions: PermissionsView | null = null;
  private permissionFailure: Notice | null = null;
  private readonly lost = new StreamFailures();
  private readonly rideFailure = new NoticeSlot();
  private setupOpen = false;
  private finishTarget: { rideId: string; identity: string; requestId: string | null } | null = null;
  private discardStep = false;
  private stops: (() => void)[] = [];
  private stopPermissions: (() => void) | null = null;
  private stopDisplay: (() => void) | null = null;
  private tick: ReturnType<typeof setInterval> | undefined;
  private tickMs: number | null = null;

  constructor(private readonly env: UiEnv) {}

  getSnapshot = (): RideControlsState => this.snapshots.get();

  subscribe = (onChange: () => void): (() => void) => this.snapshots.subscribe(onChange);

  activate(): void {
    if (this.stops.length) return;
    const { client } = this.env;
    this.stops = [
      client.subscribe(
        'recording.state',
        {},
        ({ value }) => {
          this.recording = value;
          this.lost.answered('recording.state');
          this.publish();
        },
        failure => this.streamFailed('recording.state', failure),
      ),
      client.subscribe(
        'connection.state',
        {},
        ({ value }) => {
          this.connection = value;
          this.lost.answered('connection.state');
          this.publish();
        },
        failure => this.streamFailed('connection.state', failure),
      ),
    ];
    this.stopDisplay = this.env.shell.display.subscribe(() => this.publish());
    this.publish();
  }

  deactivate(): void {
    for (const stop of this.stops.splice(0)) stop();
    this.stopPermissions?.();
    this.stopPermissions = null;
    this.stopDisplay?.();
    this.stopDisplay = null;
    clearInterval(this.tick);
    this.tick = undefined;
  }

  dispose(): void {
    this.deactivate();
  }

  openSetup(): void {
    this.setupOpen = true;
    this.publish();
  }

  closeSetup(): void {
    this.setupOpen = false;
    this.publish();
  }

  openRideSettings(): void {
    if (this.recording?.ride) return;
    this.setupOpen = false;
    this.publish();
    this.env.ui.navigate('/settings');
  }

  start(): void {
    this.command(
      'start',
      () => this.env.client.call('recording.start', {}),
      answer => {
        if ('cancelled' in answer) return;
        if (answer.ok) {
          this.setupOpen = false;
          this.haptic('success');
        } else if (answer.failure.code !== 'startCancelled') this.haptic('warning');
      },
    );
  }

  cancelStart(): void {
    this.command(
      'cancelStart',
      () => this.env.client.call('recording.cancelStart', {}),
      () => {},
    );
  }

  pause(): void {
    this.control('pause', 'impact');
  }

  resume(): void {
    this.control('resume', 'impact');
  }

  lap(): void {
    this.control('lap', 'lightImpact');
  }

  stop(): void {
    this.control('discard', null);
  }

  askToFinish(): void {
    const ride = this.recording?.ride;
    if (!ride || !this.recording?.allowed.finish.enabled) return;
    this.finishTarget = { rideId: ride.rideId, identity: identity(this.recording.phase), requestId: null };
    this.discardStep = false;
    this.publish();
  }

  saveRide(): void {
    const target = this.finishTarget;
    if (!target) return;
    this.control('finish', 'success', target.rideId, () => this.closeFinish());
  }

  discardRide(): void {
    if (!this.finishTarget) return;
    this.discardStep = true;
    this.publish();
  }

  backFromDiscard(): void {
    this.discardStep = false;
    this.publish();
  }

  confirmDiscard(): void {
    const target = this.finishTarget;
    if (!target) return;
    this.control('discard', 'warning', target.rideId, () => this.closeFinish());
  }

  keepRecording(): void {
    if (this.inFlight.has('finish') || this.inFlight.has('discard')) return;
    const requestId = this.finishTarget?.requestId;
    if (requestId) this.env.shell.dismiss(requestId);
    this.closeFinish();
  }

  dismissNotice(): void {
    const shown = this.snapshots.get().notice;
    if (!shown) return;
    this.env.shell.dismiss(shown.id);
    if (this.permissionFailure?.id === shown.id) this.permissionFailure = null;
    this.lost.dismiss(shown.id);
    this.publish();
  }

  retry(): void {
    this.lost.clear();
    this.deactivate();
    this.activate();
  }

  private streamFailed(stream: string, failure: ApiFailure): void {
    this.report(failure);
    if (shellFailure(failure.code)) return;
    this.lost.failed(stream, streamNotice(failure, this.text(failure.code)));
    this.publish();
  }

  permissionAction(): void {
    const row = this.snapshots.get().permission;
    if (!row?.button) return;
    const { client, ui } = this.env;
    if (row.button === 'openAppSettings') ui.openAppSettings();
    else if (row.button === 'healthHelp')
      ui.alert('Health access', 'Open Health → your profile → Apps → Power Log to review access.');
    else
      this.command(
        'permissions',
        () =>
          row.button === 'request'
            ? client.call('recording.requestPermissions', {})
            : client.call('recording.refreshPermissions', {}),
        answer => {
          if ('ok' in answer && !answer.ok && !shellFailure(answer.failure.code))
            this.permissionFailure = notice(answer.failure, this.text(answer.failure.code), ['dismiss']);
        },
      );
  }

  private control(
    action: RideAction,
    haptic: HapticKind | null,
    rideId = this.recording?.ride?.rideId,
    succeeded?: () => void,
  ): void {
    if (!rideId) return;
    this.command(
      action,
      () => this.env.client.call('recording.control', { rideId, action }),
      answer => {
        if ('cancelled' in answer) return;
        if (answer.ok) {
          succeeded?.();
          if (haptic) this.haptic(haptic);
        } else this.haptic('warning');
      },
    );
  }

  private command(key: Command, call: () => Promise<Result<unknown>>, done: (answer: Result<unknown>) => void) {
    run(this.inFlight, key, call, answer => {
      if ('ok' in answer && !answer.ok) this.report(answer.failure);
      done(answer);
      this.publish();
    });
    this.publish();
  }

  private closeFinish(): void {
    this.finishTarget = null;
    this.discardStep = false;
    this.publish();
  }

  private haptic(kind: HapticKind): void {
    this.env.ui.haptic(kind);
  }

  private report(failure: ApiFailure): void {
    if (shellFailure(failure.code)) this.env.shell.report(failure);
  }

  private text(code: FailureCode): string {
    return failureText(this.env.shell.display.current(), code);
  }

  private reason(availability: Availability): string | null {
    return availability.enabled ? null : this.text(availability.reason ?? 'actionUnavailable');
  }

  private followPermissions(open: boolean): void {
    if (open || this.stops.length === 0) {
      this.stopPermissions?.();
      this.stopPermissions = null;
      this.permissions = null;
      return;
    }
    this.stopPermissions ??= this.env.client.subscribe(
      'recording.permissions',
      {},
      ({ value }) => {
        this.permissions = value;
        this.lost.answered('recording.permissions');
        this.publish();
      },
      failure => this.streamFailed('recording.permissions', failure),
    );
  }

  private secondsUntil(atMonoUs: number): number | null {
    const at = this.env.client.localTime(atMonoUs);
    return at === null ? null : Math.max(0, Math.ceil((at - performance.now()) / 1000));
  }

  private followTimer(running: boolean, counting: boolean): void {
    const everyMs = counting ? 250 : running ? 1000 : null;
    if (this.tick !== undefined && everyMs !== this.tickMs) {
      clearInterval(this.tick);
      this.tick = undefined;
    }
    this.tickMs = everyMs;
    if (everyMs !== null && this.stops.length && this.tick === undefined)
      this.tick = setInterval(() => this.publish(), everyMs);
  }

  private permissionRow(): PermissionRow | null {
    const permissions = this.permissions;
    if (!permissions) return null;
    if (permissions.failure)
      return {
        label: 'Check permissions',
        detail: null,
        error: this.text(permissions.failure.code),
        button: 'refresh',
      };
    const { action, actionFor } = permissions;
    if (!action || !actionFor) return null;
    const { capabilities, platform } = this.env.shell.display.current();
    const text = kit.textsPermission(actionFor, action, capabilities.health, platform);
    if (!text) return null;
    const { label } = text;
    const detail = action === 'request' || action === 'unavailable' ? null : (text.detail ?? null);
    const button =
      action === 'request'
        ? 'request'
        : action === 'check'
          ? 'refresh'
          : action === 'openAppSettings'
            ? this.env.ui.kind === 'web'
              ? 'refresh'
              : 'openAppSettings'
            : action === 'healthHelp'
              ? 'healthHelp'
              : null;
    return { label, detail, error: null, button };
  }

  private details(view: RecordingView, options: RideOptions): RideDetails {
    const { capabilities, platform } = this.env.shell.display.current();
    const { health } = view;
    return {
      indoor: options.indoor,
      watch: options.useWatch,
      distanceSource: kit.textsDistanceSource(options.distanceSource, platform === 'web'),
      health: health
        ? {
            label: kit.textsHealth(health),
            reason: kit.textsHealthReason(health) ?? null,
            components: kit.textsHealthComponents(health.components, health.provider),
          }
        : null,
      sources: (['controller', 'heartRate', 'gps'] as const).flatMap(kind => {
        const text = kit.textsSource(kind, view.sources[kind], options, capabilities.health);
        return text ? [{ title: sourceTitles[kind], text: text.label, tone: sourceTone(text.tone) }] : [];
      }),
    };
  }

  private publish(): void {
    const view = this.recording;
    if (!view) {
      const lost = this.lost.shown(id => this.env.shell.dismissed(id));
      this.snapshots.set({ ...this.snapshots.get(), notice: lost });
      return;
    }
    const { phase, ride, allowed, pending, cue } = view;
    const open = ride !== null;
    this.followPermissions(open);
    const counting = cue?.action === 'start';
    const countdown = cue?.action === 'start' ? this.secondsUntil(cue.atMonoUs) : null;
    this.followTimer(view.timer.state === 'running', counting);
    const target = this.finishTarget;
    if (target && (!ride || ride.rideId !== target.rideId || identity(phase) !== target.identity)) {
      this.finishTarget = null;
      this.discardStep = false;
    }
    const request = view.finishRequest;
    if (request && ride && request.rideId === ride.rideId && !this.env.shell.dismissed(request.requestId))
      if (this.finishTarget?.requestId !== request.requestId && allowed.finish.enabled) {
        this.finishTarget = { rideId: ride.rideId, identity: identity(phase), requestId: request.requestId };
        this.discardStep = false;
      }
    const display = this.env.shell.display.current();
    const bikeShown = (this.connection?.display ?? 'unavailable') !== 'unavailable';
    const permission = open ? null : this.permissionRow();
    const starting = this.inFlight.has('start') || phase === 'preparing';
    const busy = (key: Command) => this.inFlight.has(key);
    const rideFailure = this.rideFailure.take(view.failure, failure =>
      notice(failure, this.text(failure.code), ['reviewRide']),
    );
    const shown =
      [this.lost.shown(id => this.env.shell.dismissed(id)), rideFailure, this.permissionFailure].find(
        item => item && !this.env.shell.dismissed(item.id),
      ) ?? null;
    const startReason = busy('start') ? this.text('actionPending') : this.reason(allowed.start);
    const startLabel = countdown !== null ? `Starting in ${countdown}` : starting ? 'Starting…' : 'Start ride';
    const finishSheet = this.finishTarget
      ? {
          step: this.discardStep ? ('discard' as const) : ('confirm' as const),
          save: control('Save ride', busy('finish'), this.reason(allowed.finish)),
          discard: control(
            this.discardStep ? 'Discard for good' : 'Discard ride',
            busy('discard'),
            this.reason(allowed.discard),
          ),
          keepRecording: control(
            'Keep recording',
            false,
            busy('finish') || busy('discard') ? this.text('actionPending') : null,
          ),
          error: rideFailure && !this.env.shell.dismissed(rideFailure.id) ? rideFailure.text : null,
        }
      : null;
    this.snapshots.set({
      ready: true,
      supported: display.capabilities.recording || this.env.shell.getSnapshot().mode === 'starting',
      phase,
      rideId: ride?.rideId ?? null,
      timer: timerText(view.timer, this.env.client),
      chip: phase
        ? {
            text: kit.textsPhaseChip(phase),
            tone: phase === 'running' ? 'accent' : phase === 'paused' ? 'warning' : 'neutral',
          }
        : null,
      details: ride ? this.details(view, ride.options) : null,
      bar: open ? null : bar(startLabel, starting || counting, allowed.start.enabled && !permission && bikeShown),
      start: control(startLabel, busy('start'), startReason),
      startReason,
      cancelStart: counting ? control('Cancel', busy('cancelStart'), null) : null,
      pauseOrResume:
        phase === 'running' || phase === 'paused' || phase === 'interrupted'
          ? phase === 'paused'
            ? control('Resume', busy('resume'), this.reason(allowed.resume))
            : control('Pause', busy('pause'), this.reason(allowed.pause))
          : null,
      lap:
        phase === 'running' || phase === 'paused' || phase === 'interrupted'
          ? control('Lap', busy('lap'), this.reason(allowed.lap))
          : null,
      finish:
        phase === 'running' || phase === 'paused' || phase === 'interrupted'
          ? control('Finish', false, this.reason(allowed.finish))
          : null,
      stop: phase === 'preparing' ? control('Stop', busy('discard'), this.reason(allowed.discard)) : null,
      reviewRide: phase === 'interrupted',
      waiting: waiting(phase, cue, pending),
      setupOpen: this.setupOpen,
      bikeShown,
      ownerForeign: view.otherTab ? this.text('ownerForeign') : null,
      permission,
      notice: shown,
      finishSheet,
    });
  }
}

const sourceTone = (tone: string): SourceLine['tone'] => (tone === 'ready' || tone === 'warning' ? tone : 'neutral');

function bar(startLabel: string, starting: boolean, ready: boolean): RideControlsState['bar'] {
  if (starting) return { label: startLabel, opensSetup: false };
  return ready ? { label: 'Start ride', opensSetup: false } : { label: 'Set up ride', opensSetup: true };
}

function waiting(phase: RidePhase | null, cue: CueView | null, pending: RideAction | null): string | null {
  if (phase === 'preparing') return 'Starting ride…';
  if (cue && cue.action !== 'start') return beepTexts[cue.action];
  return pending ? (waitingTexts[pending] ?? null) : null;
}

const identity = (phase: RidePhase | null) => (phase === 'interrupted' ? 'interrupted' : 'active');
