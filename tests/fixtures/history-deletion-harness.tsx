import { createElement } from 'react';
import { createRoot } from 'react-dom/client';
import { WorkoutProvider, useWorkout } from '../../src/services/workout-context';
import { SavedWorkouts } from '../../src/features/history/saved-workouts';
export * from './history-deletion-backend';
export { monitorMounts, monitorUnmounts, setGlobalDistanceSource, sharedFiles, openedURLs } from './history-deletion-platform';

function CatalogEvidence() {
  const value = useWorkout();
  return createElement('output', { 'data-testid': 'catalog-state' }, JSON.stringify({ ids: value.records.map(row => row.id), loading: value.catalogLoading, phase: value.state.phase, current: value.state.id }));
}
export function mount() {
  const container = document.createElement('div'); document.body.append(container);
  const root = createRoot(container);
  root.render(createElement(WorkoutProvider, null, createElement(CatalogEvidence), createElement(SavedWorkouts)));
  return () => root.unmount();
}
