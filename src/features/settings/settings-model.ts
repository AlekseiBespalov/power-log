import {
  kit,
  type ApiFailure,
  type BatteryMethod,
  type DistanceSelection,
  type FailureCode,
  type FitPowerSource,
  type Result,
  type SampleRate,
  type SettingsChange,
  type SettingsView,
  type TemperatureUnit,
  type UnitSystem,
} from '../../../modules/power-log';
import {
  failureText,
  control,
  notice,
  NoticeSlot,
  run,
  Snapshots,
  streamNotice,
  type ControlState,
  type Notice,
  type ScreenModel,
  type UiEnv,
} from '../../components/screen-model';

export type RideType = 'outdoor' | 'indoor';

export interface ChoiceValues {
  units: UnitSystem;
  temperature: TemperatureUnit;
  distance: DistanceSelection;
  battery: BatteryMethod;
  fitPower: FitPowerSource;
  rideType: RideType;
  sampleRate: SampleRate;
}

export type ChoiceField = keyof ChoiceValues;

export interface SettingChoice<V extends string> {
  value: V;
  label: string;
}

export interface SettingRow<V extends string> {
  title: string;
  value: V;
  label: string;
  choices: readonly SettingChoice<V>[];
  busy: boolean;
}

export type ToggleField = 'useWatch' | 'recordGps' | 'saveToHealth';

export interface ToggleRow {
  label: string;
  accessibilityLabel: string;
  value: boolean;
  busy: boolean;
}

export interface SettingsDialog {
  kind: 'unreadable' | 'saveFailed';
  text: string;
  resetOffered: boolean;
}

export interface SettingsState {
  ready: boolean;
  refused: boolean;
  rows: { [F in ChoiceField]: SettingRow<ChoiceValues[F]> };
  toggles: { [F in ToggleField]: ToggleRow | null };
  healthNote: string | null;
  storage: string | null;
  examples: { add: ControlState; result: string | null } | null;
  dialog: SettingsDialog | null;
  notice: Notice | null;
  version: string;
}

const choiceValues: { [F in ChoiceField]: readonly ChoiceValues[F][] } = {
  units: ['metric', 'imperial'],
  temperature: ['celsius', 'fahrenheit'],
  distance: ['auto', 'gps:phone', 'controller'],
  battery: ['controller', 'calculated'],
  fitPower: ['rider', 'battery'],
  rideType: ['outdoor', 'indoor'],
  sampleRate: ['2', '4', '8'],
};

const choiceOrder = Object.keys(choiceValues) as ChoiceField[];

const settingField = (field: ChoiceField) => (field === 'rideType' ? 'indoor' : field);

export class SettingsModel implements ScreenModel<SettingsState> {
  private readonly snapshots: Snapshots<SettingsState>;
  private readonly inFlight = new Set<string>();
  private view: SettingsView | null = null;
  private dismissedUnreadable = false;
  private examplesResult: string | null = null;
  private lost: Notice | null = null;
  private readonly saveFailed = new NoticeSlot();
  private stop: (() => void) | null = null;

  constructor(private readonly env: UiEnv) {
    this.snapshots = new Snapshots<SettingsState>(this.state(null));
  }

  getSnapshot = (): SettingsState => this.snapshots.get();

  subscribe = (onChange: () => void): (() => void) => this.snapshots.subscribe(onChange);

  activate(): void {
    if (this.stop) return;
    const stopStream = this.env.client.subscribe(
      'settings.state',
      {},
      ({ value }) => {
        if (value.state !== 'unreadable') this.dismissedUnreadable = false;
        this.view = value;
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

  choose<F extends ChoiceField>(field: F, value: ChoiceValues[F]): void {
    this.change(field, changeOf(field, value));
  }

  setToggle(field: ToggleField, on: boolean): void {
    this.change(field, field === 'recordGps' ? { kind: 'recordGps', value: on } : { kind: 'toggle', field, on });
  }

  resetSettings(): void {
    this.change('reset', { kind: 'reset' });
  }

  addExampleRides(): void {
    this.examplesResult = null;
    run(
      this.inFlight,
      'examples',
      () => this.env.client.call('history.addExampleRides', {}),
      answer => {
        if ('cancelled' in answer) return;
        if (answer.ok) this.examplesResult = examplesText(answer.value.added);
        else {
          this.report(answer.failure);
          this.examplesResult = this.text(answer.failure.code);
        }
        this.publish();
      },
    );
    this.publish();
  }

  retry(): void {
    this.lost = null;
    this.deactivate();
    this.activate();
  }

  dismissNotice(): void {
    if (this.lost) this.env.shell.dismiss(this.lost.id);
    this.lost = null;
    this.publish();
  }

  dismissDialog(): void {
    const dialog = this.snapshots.get().dialog;
    if (!dialog) return;
    if (dialog.kind === 'unreadable') this.dismissedUnreadable = true;
    else {
      const failed = this.saveFailed.take(this.view?.failure ?? null, failure => this.saveFailure(failure));
      if (failed) this.env.shell.dismiss(failed.id);
    }
    this.publish();
  }

  private change(key: string, change: SettingsChange): void {
    run(
      this.inFlight,
      key,
      () => this.env.client.call('settings.change', change),
      (answer: Result<unknown>) => {
        if ('ok' in answer && !answer.ok) this.report(answer.failure);
        this.publish();
      },
    );
    this.publish();
  }

  private report(failure: ApiFailure): void {
    if (failure.code === 'storageRefused' || failure.code === 'apiMismatch') this.env.shell.report(failure);
  }

  private text(code: FailureCode): string {
    return failureText(this.env.shell.display.current(), code);
  }

  private saveFailure(failure: ApiFailure): Notice {
    return notice(failure, this.text('settingsSaveFailed'), ['dismiss']);
  }

  private dialog(view: SettingsView): SettingsDialog | null {
    if (view.state === 'unreadable')
      return this.dismissedUnreadable
        ? null
        : { kind: 'unreadable', text: this.text('settingsUnreadable'), resetOffered: true };
    const failed = this.saveFailed.take(view.state === 'saveFailed' ? view.failure : null, failure =>
      this.saveFailure(failure),
    );
    if (failed && !this.env.shell.dismissed(failed.id))
      return { kind: 'saveFailed', text: failed.text, resetOffered: false };
    return null;
  }

  private state(view: SettingsView | null): SettingsState {
    const display = this.env.shell.display.current();
    const { capabilities, platform } = display;
    const label = (field: ChoiceField, value: string) =>
      kit.textsChoice(settingField(field), field === 'rideType' ? String(value === 'indoor') : value, platform);
    const ride = view?.ride;
    const values: ChoiceValues = {
      units: view?.app.units ?? 'metric',
      temperature: view?.app.temperature ?? 'celsius',
      distance: ride?.distance ?? 'auto',
      battery: ride?.battery ?? 'controller',
      fitPower: view?.app.fitPower ?? 'rider',
      rideType: ride?.indoor ? 'indoor' : 'outdoor',
      sampleRate: ride?.sampleRate ?? '2',
    };
    const rows = Object.fromEntries(
      choiceOrder.map(field => {
        const value = values[field];
        return [
          field,
          {
            title: kit.textsSetting(settingField(field)),
            value,
            label: label(field, value),
            choices: (choiceValues[field] as readonly string[]).map(option => ({
              value: option,
              label: label(field, option),
            })),
            busy: this.inFlight.has(field),
          },
        ];
      }),
    ) as unknown as SettingsState['rows'];
    const toggle = (label: string, accessibilityLabel: string, value: boolean, field: ToggleField): ToggleRow => ({
      label,
      accessibilityLabel,
      value,
      busy: this.inFlight.has(field),
    });
    const provider = capabilities.health === null ? null : kit.textsProvider(capabilities.health);
    return {
      ready: view !== null,
      refused: this.env.shell.getSnapshot().mode === 'refused',
      rows,
      toggles: {
        useWatch:
          ride && capabilities.watch
            ? toggle(
                kit.textsSetting('useWatch'),
                'Use Apple Watch',
                view.effective?.useWatch ?? ride.useWatch,
                'useWatch',
              )
            : null,
        recordGps:
          ride && capabilities.gps
            ? toggle(kit.textsSetting('recordGps'), 'Record GPS route', view.effective?.recordGps ?? false, 'recordGps')
            : null,
        saveToHealth:
          ride && provider !== null ? toggle(provider, `Save to ${provider}`, ride.saveToHealth, 'saveToHealth') : null,
      },
      healthNote:
        ride && platform !== 'web' ? (kit.textsHealthSetup(view.effective, ride, capabilities.health) ?? null) : null,
      storage: platform === 'web' && display.storage !== null ? kit.textsStoragePersistence(display.storage) : null,
      examples: display.developerTools
        ? {
            add: control('Add example rides', this.inFlight.has('examples'), null),
            result: this.examplesResult,
          }
        : null,
      dialog: view ? this.dialog(view) : null,
      notice: this.lost && !this.env.shell.dismissed(this.lost.id) ? this.lost : null,
      version: display.appVersion,
    };
  }

  private publish(): void {
    this.snapshots.set(this.state(this.view));
  }
}

const examplesText = (added: number) =>
  added
    ? `${added} example ${added === 1 ? 'ride' : 'rides'} added to History.`
    : 'The example rides are already in History.';

function changeOf<F extends ChoiceField>(field: F, value: ChoiceValues[F]): SettingsChange {
  switch (field) {
    case 'units':
      return { kind: 'units', value: value as ChoiceValues['units'] };
    case 'temperature':
      return { kind: 'temperature', value: value as ChoiceValues['temperature'] };
    case 'distance':
      return { kind: 'distance', value: value as ChoiceValues['distance'] };
    case 'battery':
      return { kind: 'battery', value: value as ChoiceValues['battery'] };
    case 'fitPower':
      return { kind: 'fitPower', value: value as ChoiceValues['fitPower'] };
    case 'rideType':
      return { kind: 'toggle', field: 'indoor', on: value === 'indoor' };
    default:
      return { kind: 'sampleRate', value: value as ChoiceValues['sampleRate'] };
  }
}
