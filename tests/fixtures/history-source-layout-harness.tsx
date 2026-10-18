import { createElement } from 'react';
import { createRoot } from 'react-dom/client';
import { WorkoutProvider } from '../../src/services/workout-context';
import { HistoryDeletionDialog } from '../../src/features/history/saved-rides';
import { HistoryFixtureRoute } from './history-deletion-harness';
import { HistoryProvider } from '../../src/features/history/history-controller';
import { workouts, seed, metadata, setDistanceFixture, snapshot, updateRecord, emit } from './history-deletion-backend';
export { setGlobalDistanceSource } from './history-deletion-platform';
export { snapshot };

const read = workouts.read;
let releaseRead: (() => void) | undefined;
let readWait: Promise<void> | undefined;
let rejectRead: string | undefined;
export const phases: string[] = [];
workouts.read = async (id, source = 'auto') => {
  const value = await read(id, source),
    failure = rejectRead;
  if (value.summary.distance?.selected?.coveredSeconds)
    value.summary.averageSpeedMps =
      value.summary.distance.selected.distanceMeters / value.summary.distance.selected.coveredSeconds;
  if (readWait) {
    phases.push('read:' + source);
    await readWait;
  }
  if (failure)
    throw Object.assign(new Error(`BridgeException: ${failure} (at PowerLogBridge/PowerLogBridgeModule.swift:11)`), {
      code: failure,
    });
  if (value.metadata.healthProvider === 'healthConnect') {
    value.summary.averageHeartRateBpm = 120;
    value.summary.activeEnergyKcal = 10;
  }
  return value;
};
export function delayNext(failure?: string) {
  phases.length = 0;
  rejectRead = failure;
  readWait = new Promise(resolve => {
    releaseRead = resolve;
  });
}
export function finishRead() {
  const release = releaseRead;
  releaseRead = undefined;
  readWait = undefined;
  rejectRead = undefined;
  release?.();
}
export function mount() {
  seed([metadata('source-layout', 1), metadata('other-ride', 0)]);
  const gps = {
    source: 'gps:phone' as const,
    label: 'GPS · Phone',
    distanceMeters: 0,
    coveredSeconds: 0,
    uncoveredSeconds: 48,
    partial: true,
  };
  const controller = {
    source: 'controller' as const,
    label: 'Controller estimate',
    distanceMeters: 900,
    coveredSeconds: 48,
    uncoveredSeconds: 0,
    estimated: true,
  };
  setDistanceFixture('source-layout', { selection: 'auto', selected: gps, available: [gps, controller] });
  document.body.style.cssText = 'margin:0;background:#0b0d10;';
  const container = document.createElement('div');
  container.style.padding = '12px';
  document.body.append(container);
  const root = createRoot(container);
  root.render(
    createElement(
      WorkoutProvider,
      null,
      createElement(HistoryProvider, null, createElement(HistoryFixtureRoute), createElement(HistoryDeletionDialog)),
    ),
  );
  return () => root.unmount();
}

export function setHealthResult(provider: 'appleHealth' | 'healthConnect', received = false) {
  updateRecord('source-layout', {
    healthProvider: provider,
    healthKitState: 'saved',
    saveToHealth: true,
    componentResults: [
      {
        component: 'cycling-cadence',
        sourceRevision: '1',
        required: true,
        state: 'unavailable',
        reason: 'permission',
        providerIds: [],
      },
    ],
  });
  emit({ historyRevision: received ? '2' : '1' });
}

export function setWatchHealthResult() {
  setHealthResult('appleHealth');
  updateRecord('source-layout', { watchEnabled: true, saveToHealth: true });
  emit({ historyRevision: '3' });
}

let refreshRevision = 10;
export function refresh() {
  emit({ historyRevision: String(++refreshRevision) });
}
