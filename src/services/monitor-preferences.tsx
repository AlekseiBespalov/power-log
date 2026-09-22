import { createContext, useContext, useEffect, useMemo, useState, useSyncExternalStore, type ReactNode } from 'react';
import type { MonitorPreferences, MonitorViewId } from '../core/monitor';
import { MonitorPreferencesController, type MonitorViewChanges } from './monitor-preferences-controller';
import { monitorPreferencesStore } from './monitor-preferences-store';

interface MonitorPreferencesValue {
  preferences: MonitorPreferences; ready: boolean; error: string | null;
  selectView: MonitorPreferencesController['selectView'];
  updateView(id: MonitorViewId, changes: MonitorViewChanges): void;
  resetView(id: MonitorViewId): void;
  setWorkoutOptions: MonitorPreferencesController['setWorkoutOptions'];
  setSpeedUnit: MonitorPreferencesController['setSpeedUnit'];
  setDistanceSource: MonitorPreferencesController['setDistanceSource'];
  setHistoryRange: MonitorPreferencesController['setHistoryRange'];
  setSampleHz: MonitorPreferencesController['setSampleHz'];
}
const Context = createContext<MonitorPreferencesValue | null>(null);
export function MonitorPreferencesProvider({ children }: { children: ReactNode }) {
  const [controller] = useState(() => new MonitorPreferencesController(monitorPreferencesStore));
  const snapshot = useSyncExternalStore(controller.subscribe, controller.snapshot, controller.snapshot);
  useEffect(() => { void controller.hydrate(); }, [controller]);
  const value = useMemo(() => ({ ...snapshot, selectView: controller.selectView, updateView: controller.updateView, resetView: controller.resetView, setWorkoutOptions: controller.setWorkoutOptions, setSpeedUnit: controller.setSpeedUnit, setDistanceSource: controller.setDistanceSource, setSampleHz: controller.setSampleHz, setHistoryRange: controller.setHistoryRange }), [snapshot, controller]);
  return <Context.Provider value={value}>{children}</Context.Provider>;
}
export function useMonitorPreferences() {
  const value = useContext(Context);
  if (!value) throw new Error('MonitorPreferencesProvider is missing.');
  return value;
}
