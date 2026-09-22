import { defaultMonitorPreferences, validateMonitorPreferences } from '../core/monitor';
import type { MonitorPreferencesStore } from './monitor-preferences-store';

export const MONITOR_PREFERENCES_KEY = 'power-log.monitor-preferences.v1';
export const monitorPreferencesStore: MonitorPreferencesStore = {
  async load() {
    const contents = localStorage.getItem(MONITOR_PREFERENCES_KEY);
    return { preferences: contents === null ? defaultMonitorPreferences() : validateMonitorPreferences(JSON.parse(contents)) };
  },
  async save(preferences) {
    // setItem replaces one complete value atomically, or throws without changing it.
    localStorage.setItem(MONITOR_PREFERENCES_KEY, JSON.stringify(validateMonitorPreferences(preferences)));
  },
};
