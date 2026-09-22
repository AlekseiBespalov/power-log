import { createElement } from 'react';
import { createRoot } from 'react-dom/client';
import { WorkoutProvider } from '../../src/services/workout-context';
import { SavedWorkouts } from '../../src/features/history/saved-workouts';
import { workouts, seed, metadata, setDistanceFixture, snapshot } from './history-deletion-backend';
export { setGlobalDistanceSource } from './history-deletion-platform';
export { snapshot };

const read = workouts.read;
let releaseRead: (() => void) | undefined;
let readWait: Promise<void> | undefined;
let rejectRead: string | undefined;
export const phases: string[] = [];
workouts.read = async (id, source = 'auto') => {
  const value = await read(id, source), failure = rejectRead;
  // A zero recorded interval has no defined average; other sources do.
  if (value.summary.distance?.selected?.coveredSeconds) value.summary.averageSpeedMps = value.summary.distance.selected.distanceMeters / value.summary.distance.selected.coveredSeconds;
  if (readWait) { phases.push('read:' + source); await readWait; }
  if (failure) throw new Error(failure);
  return value;
};
export function delayNext(failure?: string) {
  phases.length = 0; rejectRead = failure;
  readWait = new Promise(resolve => { releaseRead = resolve; });
}
export function finishRead() { const release = releaseRead; releaseRead = undefined; readWait = undefined; rejectRead = undefined; release?.(); }
export function mount() {
  seed([metadata('source-layout', 1), metadata('other-ride', 0)]);
  const gps = { source: 'gps:watch' as const, label: 'GPS · Watch', distanceMeters: 0, coveredSeconds: 0, uncoveredSeconds: 48, partial: true };
  const controller = { source: 'controller' as const, label: 'Controller estimate', distanceMeters: 900, coveredSeconds: 48, uncoveredSeconds: 0, estimated: true };
  setDistanceFixture('source-layout', { selection: 'auto', selected: gps, available: [gps, controller] });
  document.body.style.cssText = 'margin:0;background:#0b0d10;';
  const container = document.createElement('div'); container.style.padding = '12px'; document.body.append(container);
  const root = createRoot(container); root.render(createElement(WorkoutProvider, null, createElement(SavedWorkouts)));
  return () => root.unmount();
}
