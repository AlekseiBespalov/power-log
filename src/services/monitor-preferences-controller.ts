import { type MonitorRange, defaultMonitorPreferences, validateMonitorPreferences, type MonitorPreferences, type MonitorView, type MonitorViewId, type SpeedUnit } from '../core/monitor';
import type { MonitorPreferencesStore } from './monitor-preferences-store';
import type { WorkoutOptions } from '../core/workouts';
import type { DistanceSource } from '../core/distance';

export type MonitorViewChanges = Partial<Pick<MonitorView, 'numbers' | 'charts' | 'range' | 'webColumns'>>;
type Snapshot = { preferences: MonitorPreferences; ready: boolean; error: string | null };
type Change = (preferences: MonitorPreferences) => MonitorPreferences;
const message = (error: unknown) => error instanceof Error ? error.message : String(error);

/** One hydration and an ordered write queue; React renders never write preferences. */
export class MonitorPreferencesController {
  private state: Snapshot = { preferences: defaultMonitorPreferences(), ready: false, error: null };
  private listeners = new Set<() => void>();
  private hydration: Promise<void> | null = null;
  private beforeLoad: Change[] = [];
  private writes: Promise<void> = Promise.resolve();
  constructor(private store: MonitorPreferencesStore) {}
  snapshot = () => this.state;
  subscribe = (listener: () => void) => { this.listeners.add(listener); return () => { this.listeners.delete(listener); }; };
  private publish(state: Snapshot) { this.state = state; for (const listener of this.listeners) listener(); }

  hydrate = (): Promise<void> => this.hydration ??= this.store.load().then(result => {
    const changes = this.beforeLoad; this.beforeLoad = [];
    const preferences = changes.reduce((value, change) => change(value), validateMonitorPreferences(result.preferences));
    this.publish({ preferences, ready: true, error: result.warning ?? null });
    if (changes.length) this.persist(preferences);
  }).catch(error => {
    this.beforeLoad = [];
    // Never overwrite unread settings with defaults after an unsuccessful load.
    this.publish({ ...this.state, ready: true, error: `Could not load monitor settings. ${message(error)}` });
  });

  private persist(preferences: MonitorPreferences) {
    const saved = validateMonitorPreferences(preferences);
    this.writes = this.writes.then(async () => {
      try { await this.store.save(saved); this.publish({ ...this.state, error: null }); }
      catch (error) { this.publish({ ...this.state, error: `Could not save monitor settings. ${message(error)}` }); }
    });
  }

  flush = () => this.writes;
  private change(change: Change) {
    const preferences = change(this.state.preferences);
    this.publish({ ...this.state, preferences });
    if (!this.state.ready) this.beforeLoad.push(change);
    else this.persist(preferences);
  }
  selectView = (id: MonitorViewId, scope: 'live' | 'history' = 'live') => this.change(previous => validateMonitorPreferences(scope === 'live' ? { ...previous, activeView: id } : { ...previous, historyView: id }));
  setHistoryRange = (historyRange: MonitorRange) => this.change(previous => validateMonitorPreferences({ ...previous, historyRange }));
  setSpeedUnit = (speedUnit: SpeedUnit) => this.change(previous => validateMonitorPreferences({ ...previous, speedUnit }));
  setDistanceSource = (distanceSource: DistanceSource) => this.change(previous => validateMonitorPreferences({ ...previous, distanceSource }));
  setSampleHz = (sampleHz: 2 | 4 | 8) => this.change(previous => validateMonitorPreferences({ ...previous, sampleHz }));
  setWorkoutOptions = (update: WorkoutOptions | ((previous: WorkoutOptions) => WorkoutOptions)) => this.change(previous => validateMonitorPreferences({ ...previous, workoutOptions: typeof update === 'function' ? update(previous.workoutOptions) : update }));
  updateView = (id: MonitorViewId, changes: MonitorViewChanges) => {
    const patch = {
      ...(changes.numbers !== undefined ? { numbers: [...changes.numbers] } : {}),
      ...(changes.charts !== undefined ? { charts: [...changes.charts] } : {}),
      ...(changes.range !== undefined ? { range: changes.range } : {}),
      ...(changes.webColumns !== undefined ? { webColumns: changes.webColumns } : {}),
    };
    this.change(previous => validateMonitorPreferences({ ...previous, views: { ...previous.views, [id]: { ...previous.views[id], ...patch } } }));
  };
  resetView = (id: MonitorViewId) => this.change(previous => validateMonitorPreferences({ ...previous,
    views: { ...previous.views, [id]: defaultMonitorPreferences().views[id] } }));
}
