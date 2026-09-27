import type { TelemetrySample } from '../../src/core/types';
import { BrowserWorkoutRecorder } from '../../src/services/browser-workout-recorder';
import {
  browserRideStore,
  browserDatabase,
  browserTransaction,
  idbRequest,
  BROWSER_BATCH,
} from '../../src/services/browser-ride-store';
import { browserWorkoutMonitorSource } from '../../src/services/browser-workout-monitor';
import { TestTelemetryAdapter } from '../support/telemetry-adapter';
import { syntheticSample } from './synthetic-sample';
export { browserRow, browserRide } from '../support/browser-ride';
export { HistoryCatalog } from '../../src/services/history-catalog';
export { browserStoragePersistence } from '../../src/services/browser-ride-store';
export { BROWSER_RECORDING_LOCK } from '../../src/services/browser-workout-recorder';
export {
  browserRideStore as store,
  browserDatabase,
  browserTransaction,
  idbRequest,
  BROWSER_BATCH,
  browserWorkoutMonitorSource as monitor,
  syntheticSample,
};
export {
  ensureBrowserDistance,
  peekBrowserDistance,
  browserDistanceCurrent,
  browserDistanceStats,
  browserDistanceLatest,
  inspectBrowserDistance,
  plotBrowserDistance,
} from '../../src/services/browser-distance-store';

export function createRecorder() {
  let time = 0;
  const utc = () => new Date(Date.UTC(2026, 0, 1) + time * 1000).toISOString();
  class TestSource extends TestTelemetryAdapter {
    sample(sequence: number, power = 100, extras: Partial<TelemetrySample> = {}) {
      this.emitSample({
        ...syntheticSample(time, sequence, utc()),
        connectionEpoch: this.connectionEpoch,
        humanPowerW: power,
        ...extras,
      });
    }
    changeIdentity() {
      this.emitState({ deviceId: 'another-bike' });
    }
  }
  const source = new TestSource(() => time);
  const recorder = new BrowserWorkoutRecorder(browserRideStore, () => time, utc);
  recorder.setTelemetrySource(source);
  return {
    source,
    recorder,
    setTime: (seconds: number) => {
      time = seconds;
    },
  };
}
