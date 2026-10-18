import { createElement } from 'react';
import { createRoot } from 'react-dom/client';
import { WorkoutProvider, useWorkout } from '../../src/services/workout-context';
import { HistoryDeletionDialog } from '../../src/features/history/saved-rides';
import { HistoryFixtureRoute } from '../fixtures/history-deletion-harness';
import { HistoryProvider } from '../../src/features/history/history-controller';
import { emit, exportHooks, workouts } from '../fixtures/history-deletion-backend';
import * as platform from '../fixtures/history-deletion-platform';
export * from '../fixtures/history-deletion-harness';

type Held = { promise: Promise<void>; resolve(): void; reject(error: Error): void };
const held = new Map<string, Held>();
const removeRide = workouts.remove,
  listRides = workouts.list;
let catalogFailure: string | undefined;
export function failCatalog(message: string) {
  catalogFailure = message;
}
workouts.list = async request => {
  if (catalogFailure)
    throw Object.assign(
      new Error(`BridgeException: ${catalogFailure} (at PowerLogBridge/PowerLogBridgeModule.swift:11)`),
      { code: catalogFailure },
    );
  return listRides(request);
};
platform.importHooks.before = async () => {
  await held.get('import:csv')?.promise;
};
exportHooks.before = async id => {
  await held.get(`export:${id}`)?.promise;
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
  if (error)
    pending.reject(
      Object.assign(new Error(`BridgeException: ${error} (at PowerLogBridge/PowerLogBridgeModule.swift:11)`), {
        code: error,
      }),
    );
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
  root.render(
    createElement(
      WorkoutProvider,
      null,
      createElement(Controls),
      createElement(HistoryProvider, null, createElement(HistoryFixtureRoute), createElement(HistoryDeletionDialog)),
    ),
  );
  return () => root.unmount();
}
