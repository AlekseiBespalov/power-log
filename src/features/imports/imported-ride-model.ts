import {
  kit,
  type ApiFailure,
  type FailureCode,
  type ImportedRideView,
  type Result,
  type Shared,
} from '../../../modules/power-log';
import {
  failureText,
  control,
  notice,
  Snapshots,
  type ControlState,
  type Notice,
  type ScreenModel,
  type UiEnv,
} from '../../components/screen-model';
import type { HistoryListModel } from '../history/history-list-model';
import type { SummaryItem } from '../history/ride-detail-model';

export interface ImportedRideState {
  importId: string;
  name: string;
  summary: readonly SummaryItem[];
  samples: string;
  exportCsv: ControlState;
  notice: Notice | null;
}

export class ImportedRideModel implements ScreenModel<ImportedRideState> {
  private readonly snapshots: Snapshots<ImportedRideState>;
  private failed: Notice | null = null;
  private stop: (() => void) | null = null;

  constructor(
    private readonly env: UiEnv,
    private readonly imported: ImportedRideView,
    private readonly list: HistoryListModel,
  ) {
    this.snapshots = new Snapshots<ImportedRideState>(this.state());
  }

  getSnapshot = (): ImportedRideState => this.snapshots.get();

  subscribe = (onChange: () => void): (() => void) => this.snapshots.subscribe(onChange);

  activate(): void {
    if (this.stop) return;
    const stopExports = this.list.onExports(() => this.publish());
    const stopShell = this.env.shell.subscribe(() => this.publish());
    this.stop = () => {
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

  exportCsv(): void {
    if (this.exporting()) return;
    this.list.exportImported(this.imported.importId, this.imported.name, answer => this.answered(answer));
    this.publish();
  }

  backToHistory(): void {
    this.list.closeImport();
  }

  dismissNotice(): void {
    if (this.failed) this.env.shell.dismiss(this.failed.id);
    this.failed = null;
    this.publish();
  }

  private exporting(): boolean {
    return this.list.exporting(`import:${this.imported.importId}`);
  }

  private answered(answer: Result<Shared>): void {
    if ('cancelled' in answer) return;
    if (!answer.ok) {
      this.report(answer.failure);
      if (answer.failure.code !== 'storageRefused' && answer.failure.code !== 'apiMismatch')
        this.failed = notice(answer.failure, this.text(answer.failure.code), ['dismiss']);
    }
    this.publish();
  }

  private report(failure: ApiFailure): void {
    if (failure.code === 'storageRefused' || failure.code === 'apiMismatch') this.env.shell.report(failure);
  }

  private text(code: FailureCode): string {
    return failureText(this.env.shell.display.current(), code);
  }

  private state(): ImportedRideState {
    const { summary } = this.imported;
    const { locale } = this.env.shell.display.current();
    return {
      importId: this.imported.importId,
      name: this.imported.name,
      summary: [
        { label: 'Duration', value: kit.formatDuration(summary.durationUs), unit: '', tone: null },
        { label: 'Avg power', value: kit.formatDecimal(summary.averagePowerW, 0), unit: 'W', tone: 'accent' },
        { label: 'Peak power', value: kit.formatDecimal(summary.peakPowerW, 0), unit: 'W', tone: null },
        { label: 'Avg cadence', value: kit.formatDecimal(summary.averageCadenceRpm, 0), unit: 'rpm', tone: 'cadence' },
        { label: 'Rider work', value: kit.formatDecimal(summary.riderWorkWh, 1), unit: 'Wh', tone: null },
      ],
      samples: [
        `${kit.integer(summary.samples, locale)} original samples`,
        summary.gaps > 0 ? `${summary.gaps} gaps` : null,
        summary.clockAdjustments > 0 ? `${summary.clockAdjustments} clock adjustments` : null,
      ]
        .filter(Boolean)
        .join(' · '),
      exportCsv: control('Export CSV', this.exporting(), null),
      notice: this.failed && !this.env.shell.dismissed(this.failed.id) ? this.failed : null,
    };
  }

  private publish(): void {
    this.snapshots.set(this.state());
  }
}
