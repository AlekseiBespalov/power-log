import { createElement, useEffect } from 'react';
import { createRoot } from 'react-dom/client';
import { usePathname } from 'expo-router';
import { WorkoutProvider, useWorkout } from '../../src/services/workout-context';
import { SavedRides, HistoryDeletionDialog } from '../../src/features/history/saved-rides';
import { HistoryProvider } from '../../src/features/history/history-controller';
export * from './history-deletion-backend';
export {
  monitorMounts,
  monitorUnmounts,
  setGlobalDistanceSource,
  setCsvImport,
  openedURLs,
} from './history-deletion-platform';

export let refreshCatalog: () => Promise<void>;
function CatalogEvidence() {
  const value = useWorkout();
  useEffect(() => {
    refreshCatalog = value.refreshRecords;
  }, [value.refreshRecords]);
  return createElement(
    'output',
    { 'data-testid': 'catalog-state' },
    JSON.stringify({
      ids: value.records.map(row => row.id),
      loading: value.catalogLoading,
      phase: value.state.phase,
      current: value.state.id,
    }),
  );
}
export function HistoryFixtureRoute() {
  return createElement(SavedRides, { detail: usePathname() === '/sessions/ride' });
}
export function mount() {
  const container = document.createElement('div');
  document.body.append(container);
  const root = createRoot(container);
  root.render(
    createElement(
      WorkoutProvider,
      null,
      createElement(CatalogEvidence),
      createElement(HistoryProvider, null, createElement(HistoryFixtureRoute), createElement(HistoryDeletionDialog)),
    ),
  );
  return () => root.unmount();
}
