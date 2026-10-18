import { BrowserWorkoutRecorder } from '../../src/services/browser-workout-recorder';
import { browserRideStore as store } from '../../src/services/browser-ride-store';
import { TestTelemetryAdapter } from '../support/telemetry-adapter';
import { syntheticSample } from './synthetic-sample';
export { store };
export { exportFit } from '../../src/core/export/fit-export';
export { exportZip } from '../../src/core/export/zip-export';
export { browserExportSource } from '../../src/services/browser-export-source';
export { openBrowserExportSink } from '../../src/services/browser-export-sink';

let time = 0;
const source = new TestTelemetryAdapter(() => time);
export const recorder = new BrowserWorkoutRecorder(
  store,
  () => time,
  () => new Date(Date.UTC(2026, 0, 1) + time * 1000).toISOString(),
);
recorder.setTelemetrySource(source);
export let reached = false;
export async function start() {
  await source.connect();
  return recorder.start({ indoor: true, sampleHz: 8, recordGPS: false });
}
export function sample(index: number) {
  time = index / 8;
  source.emitSample({
    ...syntheticSample(time, index, new Date(Date.UTC(2026, 0, 1) + time * 1000).toISOString()),
    connectionEpoch: source.connectionEpoch,
  });
}
export function arm(afterCommit: boolean) {
  const append = store.append.bind(store);
  store.append = async (...args) => {
    if (afterCommit) await append(...args);
    reached = true;
    return new Promise(() => {});
  };
}
export async function finishSaved(id: string) {
  time = 1000;
  const before = await recorder.getState();
  const saved = await recorder.stop(id);
  return { before, saved, record: await store.get(id), rows: await store.page(id, 0, Infinity) };
}
