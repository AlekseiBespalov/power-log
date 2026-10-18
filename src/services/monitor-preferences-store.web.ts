import { defaultMonitorPreferences, monitorPreferencesJson, parseMonitorPreferences } from '../core/monitor';
import { failurePresentation } from '../core/failures';
import { browserFailureCode } from './browser-failures';
import type { MonitorPreferencesStore } from './monitor-preferences-store';

export const MONITOR_PREFERENCES_KEY = 'power-log.monitor-preferences.v1';
function storedContents(): string | null {
  try {
    return localStorage.getItem(MONITOR_PREFERENCES_KEY);
  } catch {
    throw new Error('Saved monitor settings could not be read.');
  }
}
export const monitorPreferencesStore: MonitorPreferencesStore = {
  async load() {
    const contents = storedContents();
    return {
      preferences: contents === null ? defaultMonitorPreferences() : parseMonitorPreferences(contents),
    };
  },
  async save(preferences) {
    const contents = monitorPreferencesJson(preferences);
    try {
      localStorage.setItem(MONITOR_PREFERENCES_KEY, contents);
    } catch (error) {
      throw new Error(failurePresentation(browserFailureCode(error, 'storage')).message);
    }
  },
};
