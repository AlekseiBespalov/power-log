import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { unavailableWorkoutState, type WorkoutMetadata, type WorkoutPermissionStatus, type WorkoutState } from '../core/workouts';
import { workouts, setWorkoutTelemetrySource } from './workouts';
import { rideHistorySources } from './ride-history';
import { deviceAdapter } from './device';
import { appIsForeground, subscribeAppVisibility } from './app-visibility';
import { useHistoryCatalog } from './use-history-catalog';
import { WorkoutStateDelivery, workoutHistoryRevision } from './workout-state-delivery';
import { useMonitorPreferences } from './monitor-preferences';
import { WorkoutActions, type WorkoutActionKind } from './workout-actions';

function useWorkoutController() {
  useEffect(() => { setWorkoutTelemetrySource(deviceAdapter); }, []);
  const [state, setState] = useState<WorkoutState>(unavailableWorkoutState);
  const [ready, setReady] = useState(false);
  const [stateDelivery] = useState(() => new WorkoutStateDelivery(setState));
  const catalog = useHistoryCatalog<WorkoutMetadata>(rideHistorySources);
  const records = catalog.records, catalogLoading = catalog.loading || (!catalog.initialized && catalog.error === null), catalogError = catalog.error;
  const refreshRecords = catalog.refresh, loadMoreRecords = catalog.loadMore, catalogHasMore = catalog.hasMore, clearCatalogError = catalog.clearError;
  const setHistoryActive = catalog.setActive;
  const [actionState, setActionState] = useState({ busy: false, recoveryAvailable: false, error: null as string | null });
  const [actions] = useState(() => new WorkoutActions(setActionState));
  const { busy, recoveryAvailable, error } = actionState;
  const settings = useMonitorPreferences();
  const options = settings.preferences.workoutOptions, setOptions = settings.setWorkoutOptions, optionsReady = settings.ready;
  const [permissions, setPermissions] = useState<WorkoutPermissionStatus | null>(null);
  const [permissionsError, setPermissionsError] = useState<string | null>(null);
  const permissionRequest = useRef(0);
  const refreshPermissions = useCallback(async () => {
    const request = ++permissionRequest.current;
    try {
      const next = await workouts.getPermissions();
      if (request === permissionRequest.current) { setPermissions(next); setPermissionsError(null); }
    } catch (error) {
      if (request === permissionRequest.current) { setPermissions(null); setPermissionsError(error instanceof Error ? error.message : String(error)); }
    }
  }, []);
  const refresh = useCallback(async () => {
    const request = stateDelivery.beginRefresh();
    // Lifecycle recovery must not wait for catalog or authorization queries.
    void refreshRecords().catch(() => {});
    const state = await workouts.getState(); stateDelivery.finishRefresh(request, state); setReady(true);
    if (state.supported && appIsForeground()) void refreshPermissions();
    else { setPermissions(null); setPermissionsError(null); }
  }, [refreshPermissions, refreshRecords, stateDelivery]);
  useEffect(() => {
    let mounted = true; let catalogRevision: string | undefined;
    const receive = (next: WorkoutState) => {
      if (!mounted) return;
      stateDelivery.receive(next);
      setReady(true);
      const revision = workoutHistoryRevision(next);
      if (revision !== catalogRevision) {
        catalogRevision = revision; void refreshRecords().catch(error => { if (mounted) actions.reportError(error); });
      }
    };
    const unsubscribe = workouts.subscribe(receive);
    const initial = () => { void refresh().catch(error => { if (mounted) actions.reportError(error); }); };
    const visibility = (active: boolean) => { stateDelivery.setActive(active); if (active) initial(); else setHistoryActive(false); };
    visibility(appIsForeground());
    const unsubscribeVisibility = subscribeAppVisibility(visibility);
    return () => { mounted = false; stateDelivery.setActive(false); stateDelivery.beginRefresh(); unsubscribe(); unsubscribeVisibility(); };
  }, [actions, refresh, refreshRecords, setHistoryActive, stateDelivery]);
  const run = useCallback((operation: () => Promise<unknown>, kind?: WorkoutActionKind) => actions.run(operation, refresh, kind), [actions, refresh]);
  return useMemo(() => ({ state, ready, records, catalogLoading, catalogError, clearCatalogError, catalogHasMore, loadMoreRecords, setHistoryActive, busy, recoveryAvailable, options, optionsReady, setOptions, error, run, refresh, permissions, permissionsError, refreshPermissions, clearError: actions.clearError }),
    [actions, state, ready, records, catalogLoading, catalogError, clearCatalogError, catalogHasMore, loadMoreRecords, setHistoryActive, busy, recoveryAvailable, options, optionsReady, setOptions, error, run, refresh, permissions, permissionsError, refreshPermissions]);
}
const Context = createContext<ReturnType<typeof useWorkoutController> | null>(null);
const IdentityContext = createContext<Pick<WorkoutState, 'id' | 'phase'>>(unavailableWorkoutState);
export function WorkoutProvider({ children }: { children: ReactNode }) {
  const value = useWorkoutController();
  const { id, phase } = value.state;
  const identity = useMemo(() => ({ id, phase }), [id, phase]);
  return <IdentityContext.Provider value={identity}><Context.Provider value={value}>{children}</Context.Provider></IdentityContext.Provider>;
}
export function useWorkoutIdentity() { return useContext(IdentityContext); }
export function useWorkout() {
  const context = useContext(Context);
  if (!context) throw new Error('WorkoutProvider is missing.');
  return context;
}
