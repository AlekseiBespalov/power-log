import type { MonitorPreferences } from '../core/monitor';

export interface MonitorPreferencesStore {
  load(): Promise<{ preferences: MonitorPreferences; warning?: string }>;
  save(preferences: MonitorPreferences): Promise<void>;
}

export { monitorPreferencesStore } from './monitor-preferences-store.native';
