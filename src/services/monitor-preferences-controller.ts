import type { FitPowerSource } from '../core/export/types';
import {
  presentationDefaultPreferences,
  presentationLoadPreferences,
  PresentationPreset,
  type PresentationPreferences,
  type PresentationPreferenceLoad,
} from '../../shared/js';
import { presentationOptions } from '../core/presentation-inputs';
import {
  MonitorPreferencesError,
  type MonitorRange,
  monitorPreferencesCore,
  monitorPreferencesFromCore,
  type MonitorPreferences,
  type MonitorView,
  type MonitorViewId,
  type SpeedUnit,
} from '../core/monitor';
import type { MonitorPreferencesStore } from './monitor-preferences-store';
import type { WorkoutOptions } from '../core/workouts';
import type { DistanceSource } from '../core/distance';

export type MonitorViewChanges = Partial<Pick<MonitorView, 'numbers' | 'charts' | 'range' | 'webColumns'>>;
type Snapshot = { preferences: MonitorPreferences; ready: boolean; error: string | null };
type Change = (preferences: PresentationPreferences) => PresentationPreferences;
const message = (error: unknown) => (error instanceof Error ? error.message : String(error));

export class MonitorPreferencesController {
  private defaults = presentationDefaultPreferences();
  private load: PresentationPreferenceLoad = presentationLoadPreferences(this.defaults, null, null);
  private state: Snapshot = {
    preferences: monitorPreferencesFromCore(this.load.preferences, true),
    ready: false,
    error: null,
  };
  private listeners = new Set<() => void>();
  private hydration: Promise<void> | null = null;
  private beforeLoad: Change[] = [];
  private pendingSave: Promise<void> = Promise.resolve();
  private saveError: string | null = null;
  constructor(private store: MonitorPreferencesStore) {}
  snapshot = () => this.state;
  subscribe = (listener: () => void) => {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  };
  private publish(state: Snapshot) {
    this.state = state;
    for (const listener of this.listeners) listener();
  }

  hydrate = (): Promise<void> =>
    (this.hydration ??= this.store
      .load()
      .then(result => {
        const changes = this.beforeLoad;
        this.beforeLoad = [];
        const loaded = monitorPreferencesCore(result.preferences);
        this.load = this.load.change(loaded);
        for (const change of changes) this.load = this.load.change(change(this.load.preferences));
        const preferences = monitorPreferencesFromCore(this.load.preferences, true);
        this.publish({ preferences, ready: true, error: result.warning ?? null });
        if (changes.length) this.persist(preferences);
      })
      .catch(error => {
        this.beforeLoad = [];
        this.load = presentationLoadPreferences(this.load.preferences, null, message(error));
        this.publish({ ...this.state, ready: true, error: this.load.error ?? null });
      }));

  private persist(preferences: MonitorPreferences) {
    const pending = this.store.save(preferences).then(
      () => {
        if (this.pendingSave === pending) {
          this.saveError = null;
          this.publish({ ...this.state, error: null });
        }
      },
      error => {
        if (this.pendingSave === pending) {
          this.saveError = `Could not save monitor settings. ${message(error)}`;
          this.publish({ ...this.state, error: this.saveError });
        }
      },
    );
    this.pendingSave = pending;
  }

  flush = () => this.pendingSave;
  prepareRecording = async (): Promise<MonitorPreferences> => {
    await this.hydrate();
    if (!this.load.writable) throw new MonitorPreferencesError(this.load.error ?? 'Monitor settings are unreadable.');
    let pending: Promise<void>;
    do {
      pending = this.pendingSave;
      await pending;
    } while (pending !== this.pendingSave);
    if (this.saveError) throw new MonitorPreferencesError(this.saveError);
    return this.state.preferences;
  };
  private change(change: Change) {
    this.load = this.load.change(change(this.load.preferences));
    const preferences = monitorPreferencesFromCore(this.load.preferences, true);
    this.publish({ ...this.state, preferences });
    if (!this.state.ready) this.beforeLoad.push(change);
    else if (this.load.shouldSave) this.persist(preferences);
  }
  selectView = (id: MonitorViewId, scope: 'live' | 'history' = 'live') =>
    this.change(previous =>
      previous.withSelections(
        scope === 'live' ? id : previous.activeView,
        scope === 'history' ? id : previous.historyView,
        previous.historyRange,
      ),
    );
  setHistoryRange = (range: MonitorRange) =>
    this.change(previous => previous.withSelections(previous.activeView, previous.historyView, String(range)));
  setSpeedUnit = (unit: SpeedUnit) => this.change(previous => previous.withDisplay(unit, previous.distanceSource));
  setDistanceSource = (source: DistanceSource) =>
    this.change(previous => previous.withDisplay(previous.speedUnit, source));
  setBatteryConsumptionSource = (source: import('../core/consumption').BatteryConsumptionSource) =>
    this.change(previous => previous.withBatteryConsumptionSource(source));
  setFitPowerSource = (source: FitPowerSource) => this.change(previous => previous.withFitPowerSource(source));
  setSampleHz = (sampleHz: 2 | 4 | 8) => this.change(previous => previous.withOptions(previous.options, sampleHz));
  setWorkoutOptions = (update: WorkoutOptions | ((previous: WorkoutOptions) => WorkoutOptions)) =>
    this.change(previous => {
      const options =
        typeof update === 'function' ? update(monitorPreferencesFromCore(previous).workoutOptions) : update;
      const defaults = this.defaults.options;
      return previous.withOptions(
        presentationOptions({
          ...options,
          useWatch: options.useWatch ?? defaults.useWatch,
          saveToHealth: options.saveToHealth ?? defaults.saveToHealth,
        }),
        previous.sampleHz,
      );
    });
  updateView = (id: MonitorViewId, changes: MonitorViewChanges) => {
    const patch = {
      ...changes,
      ...(changes.numbers === undefined ? {} : { numbers: [...changes.numbers] }),
      ...(changes.charts === undefined ? {} : { charts: [...changes.charts] }),
    };
    this.change(previous => {
      const view = previous.view(id);
      return previous.withPreset(
        id,
        new PresentationPreset(
          patch.numbers ?? view.numbers,
          patch.charts ?? view.charts,
          String(patch.range ?? view.range),
          patch.webColumns ?? view.webColumns,
        ),
      );
    });
  };
  resetView = (id: MonitorViewId) => this.change(previous => previous.withPreset(id, this.defaults.view(id)));
}
