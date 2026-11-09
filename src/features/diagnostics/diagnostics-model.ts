import { kit, type ApiFailure, type DiagnosticsView, type FailureCode, type Result } from '../../../modules/power-log';
import {
  failureText,
  control,
  notice,
  run,
  Snapshots,
  type ControlState,
  type Notice,
  type ScreenModel,
  type UiEnv,
} from '../../components/screen-model';

export interface DiagnosticsState {
  available: boolean;
  share: ControlState | null;
  capture: ControlState;
  stop: ControlState | null;
  exportLog: ControlState;
  clear: ControlState;
  status: string;
  notice: Notice | null;
}

const unknownText = 'Bluetooth diagnostics could not complete. Try again.';

export class DiagnosticsModel implements ScreenModel<DiagnosticsState> {
  private readonly snapshots: Snapshots<DiagnosticsState>;
  private readonly inFlight = new Set<string>();
  private view: DiagnosticsView | null = null;
  private failed: Notice | null = null;
  private stop: (() => void) | null = null;

  constructor(private readonly env: UiEnv) {
    this.snapshots = new Snapshots<DiagnosticsState>(this.state());
  }

  getSnapshot = (): DiagnosticsState => this.snapshots.get();

  subscribe = (onChange: () => void): (() => void) => this.snapshots.subscribe(onChange);

  activate(): void {
    if (this.stop) return;
    const stopStream = this.env.client.subscribe(
      'diagnostics.state',
      {},
      ({ value }) => {
        this.view = value;
        this.publish();
      },
      failure => {
        if (failure.code !== 'storageBusy') this.failure(failure);
      },
    );
    const stopShell = this.env.shell.subscribe(() => this.publish());
    const stopDisplay = this.env.shell.display.subscribe(() => this.publish());
    this.stop = () => {
      stopStream();
      stopShell();
      stopDisplay();
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

  share(): void {
    this.command('share', () => this.env.diagnostics.share());
  }

  startCapture(): void {
    this.command('capture', async () => {
      const purged = await this.env.diagnostics.purgeTrace();
      if (!('cancelled' in purged) && !purged.ok) return purged;
      return this.env.client.call('diagnostics.startTrace', {});
    });
  }

  stopCapture(): void {
    this.command('stop', () => this.env.client.call('diagnostics.stopTrace', {}));
  }

  exportLog(): void {
    this.command('export', () => this.env.diagnostics.shareTrace());
  }

  clear(): void {
    this.command('clear', async () => {
      const cleared = await this.env.client.call('diagnostics.clearTrace', {});
      if (!('cancelled' in cleared) && !cleared.ok) return cleared;
      return this.env.diagnostics.purgeTrace();
    });
  }

  refreshStatus(): void {
    if (this.failed) this.env.shell.dismiss(this.failed.id);
    this.failed = null;
    this.deactivate();
    this.activate();
  }

  private command(key: string, call: () => Promise<Result<unknown>>): void {
    if (this.inFlight.size) return;
    run(this.inFlight, key, call, answer => {
      if (!('cancelled' in answer) && !answer.ok) this.failure(answer.failure);
      this.publish();
    });
    this.publish();
  }

  private failure(failure: ApiFailure): void {
    if (failure.code === 'storageRefused' || failure.code === 'apiMismatch') {
      this.env.shell.report(failure);
      return;
    }
    this.failed = notice(failure, failure.code === 'unknown' ? unknownText : this.text(failure.code), [
      'refreshStatus',
    ]);
    this.publish();
  }

  private text(code: FailureCode): string {
    return failureText(this.env.shell.display.current(), code);
  }

  private state(): DiagnosticsState {
    const { capabilities } = this.env.shell.display.current();
    const trace = this.view?.trace ?? null;
    const pending = this.inFlight.size > 0 ? this.text('actionPending') : null;
    const own = (key: string) => this.inFlight.has(key);
    const reason = (key: string, unavailable: string | null) => (own(key) ? null : (pending ?? unavailable));
    const capturing = trace?.state === 'capturing';
    const events = trace?.events ?? 0;
    return {
      available: capabilities.bluetooth,
      share: capabilities.diagnosticLog ? control('Share diagnostics', own('share'), reason('share', null)) : null,
      capture: control(
        events > 0 ? 'New capture' : 'Start capture',
        own('capture'),
        reason('capture', capturing ? this.text('actionUnavailable') : null),
      ),
      stop: capturing ? control('Stop capture', own('stop'), reason('stop', null)) : null,
      exportLog: control(
        'Export log',
        own('export'),
        reason('export', events > 0 ? null : this.text('actionUnavailable')),
      ),
      clear: control('Clear', own('clear'), reason('clear', trace ? null : this.text('actionUnavailable'))),
      status: kit.textsTraceStatus(trace?.state ?? 'idle', events, trace?.stopReason ?? null),
      notice: this.failed && !this.env.shell.dismissed(this.failed.id) ? this.failed : null,
    };
  }

  private publish(): void {
    this.snapshots.set(this.state());
  }
}
