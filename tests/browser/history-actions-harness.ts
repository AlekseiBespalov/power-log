import { createElement } from 'react';
import { createRoot } from 'react-dom/client';
import { WorkoutProvider, useWorkout } from '../../src/services/workout-context';
import { SavedRides } from '../../src/features/history/saved-rides';
import { emit, workouts } from '../fixtures/history-deletion-backend';
import * as platform from '../fixtures/history-deletion-platform';
export * from '../fixtures/history-deletion-harness';

type Held = { promise: Promise<void>; resolve(): void; reject(error: Error): void };
const held = new Map<string, Held>();
const exportRide = workouts.export,
  removeRide = workouts.remove,
  listRides = workouts.list,
  chooseCSV = platform.importRecording;
let catalogFailure: string | undefined;
export function failCatalog(message: string) {
  catalogFailure = message;
}
workouts.list = async request => {
  if (catalogFailure) throw new Error(catalogFailure);
  return listRides(request);
};
Object.assign(platform, {
  importRecording: async () => {
    await held.get('import:csv')?.promise;
    return chooseCSV();
  },
});
workouts.export = async (id, source) => {
  await held.get(`export:${id}`)?.promise;
  return exportRide(id, source);
};
workouts.remove = async id => {
  await held.get(`remove:${id}`)?.promise;
  return removeRide(id);
};
export function holdArchive(kind: 'export' | 'remove' | 'import', id: string) {
  let resolve!: () => void, reject!: (error: Error) => void;
  const promise = new Promise<void>((yes, no) => {
    resolve = yes;
    reject = no;
  });
  held.set(`${kind}:${id}`, { promise, resolve, reject });
}
export function releaseArchive(kind: 'export' | 'remove' | 'import', id: string, error?: string) {
  const key = `${kind}:${id}`,
    pending = held.get(key);
  if (!pending) throw new Error('Archive operation was not held');
  held.delete(key);
  if (error) pending.reject(new Error(error));
  else pending.resolve();
}
export let pauseCalls = 0;
function Controls() {
  const workout = useWorkout();
  return createElement(
    'div',
    null,
    createElement(
      'output',
      { 'data-testid': 'ride-controls-state' },
      JSON.stringify({ busy: workout.busy, phase: workout.state.phase, error: workout.error }),
    ),
    createElement(
      'button',
      {
        disabled: workout.busy,
        onClick: () => {
          void workout.run(async () => {
            pauseCalls++;
            emit({ phase: 'paused' });
          });
        },
      },
      'Pause active ride',
    ),
  );
}
export function mountWithControls() {
  const container = document.createElement('div');
  document.body.append(container);
  const root = createRoot(container);
  root.render(createElement(WorkoutProvider, null, createElement(Controls), createElement(SavedRides)));
  return () => root.unmount();
}
