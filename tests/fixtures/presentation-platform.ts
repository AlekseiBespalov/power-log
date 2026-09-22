import { defaultMonitorPreferences } from '../../src/core/monitor';
import { ForegroundAdapter } from '../../src/services/foreground-adapter';
import { syntheticSample } from './synthetic-sample';

const listeners = new Set<(state: string) => void>();
export const Platform = { OS: 'web' };
export const AppState = { currentState: 'active', addEventListener(_name: string, listener: (state: string) => void) {
  listeners.add(listener); return { remove() { listeners.delete(listener); } };
} };
export function visibility(active: boolean) { AppState.currentState = active ? 'active' : 'background'; for (const listener of listeners) listener(AppState.currentState); }
const settings = { preferences: defaultMonitorPreferences(), ready: true, error: null, setWorkoutOptions() {} };
export const useMonitorPreferences = () => settings;
class Source extends ForegroundAdapter {
  readonly kind = 'web'; readonly description = 'synthetic presentation test';
  async startScan() {} async stopScan() {}
  async connect() { this.update({ status: 'connected', deviceId: 'synthetic-bike' }); }
  async disconnect() { this.update({ status: 'idle' }); }
  sample(sequence: number) { this.publish(syntheticSample(sequence / 8, sequence, new Date().toISOString())); }
}
export const deviceAdapter = new Source();
