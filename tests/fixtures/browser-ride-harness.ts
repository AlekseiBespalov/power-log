import { BrowserWorkoutRecorder } from '../../src/services/browser-workout-recorder';
import { browserRideStore, browserDatabase, browserTransaction, idbRequest, BROWSER_BATCH } from '../../src/services/browser-ride-store';
import { browserWorkoutMonitorSource } from '../../src/services/browser-workout-monitor';
import { ForegroundAdapter } from '../../src/services/foreground-adapter';
import { syntheticSample } from './synthetic-sample';
import type { ConnectionOptions, TelemetrySample } from '../../src/core/types';
export { BROWSER_RECORDING_LOCK } from '../../src/services/browser-workout-recorder';
export { browserRideStore as store, browserDatabase, browserTransaction, idbRequest, BROWSER_BATCH, browserWorkoutMonitorSource as monitor, syntheticSample };
export { ensureBrowserDistance, peekBrowserDistance, browserDistanceCurrent, browserDistanceStats, browserDistanceLatest, inspectBrowserDistance, plotBrowserDistance } from '../../src/services/browser-distance-store';

export function createRecorder() {
  let time = 0;
  const utc = () => new Date(Date.UTC(2026, 0, 1) + time * 1000).toISOString();
  class TestSource extends ForegroundAdapter {
    readonly kind = 'web'; readonly description = 'test CYC';
    sampleRates: number[] = [];
    async setSampleRate(hz: number) { this.sampleRates.push(hz); }
    async startScan() {} async stopScan() {}
    async connect(options: ConnectionOptions = { deviceId: 'test-bike', hz: 8 }) { this.requireWorkoutDevice(options.deviceId); this.update({ status: 'connected', deviceId: options.deviceId }); }
    async disconnect() { this.update({ status: 'idle' }); }
    sample(sequence: number, power = 100, extras: Partial<TelemetrySample> = {}) { this.publish({ ...syntheticSample(time, sequence, utc()), humanPowerW: power, ...extras }); }
    changeIdentity() { this.update({ deviceId: 'another-bike' }); }
  }
  const source = new TestSource();
  const recorder = new BrowserWorkoutRecorder(browserRideStore, () => time, utc);
  recorder.setTelemetrySource(source);
  return { source, recorder, setTime: (seconds: number) => { time = seconds; } };
}
