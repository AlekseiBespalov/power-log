import { createElement, useEffect, useLayoutEffect } from 'react';
import { createRoot } from 'react-dom/client';
import { SessionProvider, useSession } from '../../src/services/session-context';
import { WorkoutProvider, useWorkout, useWorkoutIdentity } from '../../src/services/workout-context';
import { workouts } from '../../src/services/workouts.web';
import { browserRideStore } from '../../src/services/browser-ride-store';
import { deviceAdapter, visibility } from './presentation-platform';
export { visibility, deviceAdapter, workouts };
export const counts = { session: 0, identity: 0, workout: 0 };
export let catalogLoads = 0;
const list = workouts.list.bind(workouts);
workouts.list = async request => { catalogLoads++; return list(request); };
let historyVisible = false;
let historyVisibility: ((active: boolean) => void) | undefined;
export function setHistoryVisible(active: boolean) { historyVisible = active; historyVisibility?.(active); }
function SessionConsumer() { useSession(); useLayoutEffect(() => { counts.session++; }); return null; }
function IdentityConsumer() { useWorkoutIdentity(); useLayoutEffect(() => { counts.identity++; }); return null; }
function WorkoutConsumer() {
  const workout = useWorkout(); useLayoutEffect(() => { counts.workout++; });
  useEffect(() => { historyVisibility = workout.setHistoryActive; historyVisibility(historyVisible); return () => { historyVisibility = undefined; }; }, [workout.setHistoryActive]);
  return createElement('span', { id: 'phase' }, workout.state.phase);
}
export async function mount() {
  await deviceAdapter.connect();
  const container = document.createElement('div'); document.body.append(container);
  const root = createRoot(container);
  root.render(createElement(SessionProvider, null, createElement(WorkoutProvider, null,
    createElement(SessionConsumer), createElement(IdentityConsumer), createElement(WorkoutConsumer))));
}
export async function emitFrames(from: number, count: number) {
  for (let i = from; i < from + count; i++) { deviceAdapter.sample(i); await new Promise(resolve => setTimeout(resolve, 125)); }
}
export const recorded = (id: string) => browserRideStore.page(id, 0, 100);
