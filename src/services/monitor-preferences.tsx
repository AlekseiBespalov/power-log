import { createContext, useContext, useEffect, useMemo, useState, useSyncExternalStore, type ReactNode } from 'react';
import type { MonitorPreferences, MonitorViewId } from '../core/monitor';
import { MonitorPreferencesController, type MonitorViewChanges } from './monitor-preferences-controller';
import { monitorPreferencesStore } from './monitor-preferences-store';

interface MonitorPreferencesValue {
  preferences: MonitorPreferences;
  ready: boolean;
  error: string | null;
  resetOffered: boolean;
  resetSettings: MonitorPreferencesController['resetSettings'];
  selectView: MonitorPreferencesController['selectView'];
  updateView(id: MonitorViewId, changes: MonitorViewChanges): void;
  resetView(id: MonitorViewId): void;
  setWorkoutOptions: MonitorPreferencesController['setWorkoutOptions'];
  setSpeedUnit: MonitorPreferencesController['setSpeedUnit'];
  setDistanceSource: MonitorPreferencesController['setDistanceSource'];
  setBatteryConsumptionSource: MonitorPreferencesController['setBatteryConsumptionSource'];
  setHistoryRange: MonitorPreferencesController['setHistoryRange'];
  setSampleHz: MonitorPreferencesController['setSampleHz'];
  setFitPowerSource: MonitorPreferencesController['setFitPowerSource'];
  prepareRecording: MonitorPreferencesController['prepareRecording'];
}
const Context = createContext<MonitorPreferencesValue | null>(null);
export function MonitorPreferencesProvider({ children }: { children: ReactNode }) {
  const [controller] = useState(() => new MonitorPreferencesController(monitorPreferencesStore));
  const snapshot = useSyncExternalStore(controller.subscribe, controller.snapshot, controller.snapshot);
  useEffect(() => {
    void controller.hydrate();
  }, [controller]);
  const value = useMemo(
    () => ({
      ...snapshot,
      selectView: controller.selectView,
      updateView: controller.updateView,
      resetView: controller.resetView,
      resetSettings: controller.resetSettings,
      setWorkoutOptions: controller.setWorkoutOptions,
      setSpeedUnit: controller.setSpeedUnit,
      setDistanceSource: controller.setDistanceSource,
      setBatteryConsumptionSource: controller.setBatteryConsumptionSource,
      setSampleHz: controller.setSampleHz,
      setFitPowerSource: controller.setFitPowerSource,
      prepareRecording: controller.prepareRecording,
      setHistoryRange: controller.setHistoryRange,
    }),
    [snapshot, controller],
  );
  return <Context.Provider value={value}>{children}</Context.Provider>;
}
export function useMonitorPreferences() {
  const value = useContext(Context);
  if (!value) throw new Error('MonitorPreferencesProvider is missing.');
  return value;
}
