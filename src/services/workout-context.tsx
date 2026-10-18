import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import type { CatalogRequest } from '../core/catalog';
import { RideFailureError, presentFailure } from '../core/failures';
import {
  unavailableWorkoutState,
  type WorkoutMetadata,
  type WorkoutPermissionStatus,
  type WorkoutState,
} from '../core/workouts';
import { workouts } from './workouts';
import { appIsForeground, subscribeAppVisibility } from './app-visibility';
import { useHistoryCatalog } from './use-history-catalog';
import { WorkoutStateDelivery, workoutHistoryRevision } from './workout-state-delivery';
import { useMonitorPreferences } from './monitor-preferences';
import { WorkoutActions, type WorkoutActionKind, type WorkoutActionFlight } from './workout-actions';
import { finishExternalRide } from './workout-external-actions';

const loadHistory = (request: CatalogRequest) => workouts.list(request);

function useWorkoutController() {
  const [state, setState] = useState<WorkoutState>(unavailableWorkoutState);
  const [ready, setReady] = useState(false);
  const [stateDelivery] = useState(() => new WorkoutStateDelivery(setState));
  const catalog = useHistoryCatalog<WorkoutMetadata>(loadHistory);
  const records = catalog.records,
    catalogLoading = catalog.loading || (!catalog.initialized && catalog.error === null),
    catalogError = catalog.error;
  const refreshRecords = catalog.refresh,
    loadMoreRecords = catalog.loadMore,
    catalogHasMore = catalog.hasMore,
    clearCatalogError = catalog.clearError;
  const catalogUnreadableCount = catalog.unreadableCount,
    catalogUnindexedCount = catalog.unindexedCount;
  const setHistoryActive = catalog.setActive;
  const [actionState, setActionState] = useState({
    busy: false,
    recoveryAvailable: false,
    error: null as string | null,
  });
  const [actions] = useState(() => new WorkoutActions(setActionState));
  const { busy, recoveryAvailable, error } = actionState;
  const settings = useMonitorPreferences();
  const options = useMemo(
      () => ({
        ...settings.preferences.workoutOptions,
        distanceSource: settings.preferences.distanceSource,
        batteryConsumptionSource: settings.preferences.batteryConsumptionSource,
      }),
      [settings.preferences.workoutOptions, settings.preferences.distanceSource],
    ),
    setOptions = settings.setWorkoutOptions,
    optionsReady = settings.ready;
  const [permissions, setPermissions] = useState<WorkoutPermissionStatus | null>(null);
  const [permissionsError, setPermissionsError] = useState<string | null>(null);
  const permissionRequest = useRef(0);
  const refreshPermissions = useCallback(async (current: () => boolean = () => true) => {
    const request = ++permissionRequest.current;
    try {
      const next = await workouts.getPermissions();
      if (current() && request === permissionRequest.current) {
        setPermissions(next);
        setPermissionsError(null);
      }
    } catch (error) {
      if (current() && request === permissionRequest.current) {
        setPermissions(null);
        setPermissionsError(presentFailure(error).text);
      }
    }
  }, []);
  const refresh = useCallback(
    async (current: () => boolean = () => true) => {
      const request = stateDelivery.beginRefresh();
      void refreshRecords().catch(() => {});
      const state = await workouts.getState();
      if (!current()) return;
      stateDelivery.finishRefresh(request, state);
      setReady(true);
      if (state.supported && appIsForeground()) await refreshPermissions(current);
      else {
        setPermissions(null);
        setPermissionsError(null);
      }
    },
    [refreshPermissions, refreshRecords, stateDelivery],
  );
  useEffect(() => {
    let mounted = true;
    let catalogRevision: string | undefined;
    const receive = (next: WorkoutState) => {
      if (!mounted) return;
      stateDelivery.receive(next);
      setReady(true);
      const revision = workoutHistoryRevision(next);
      if (revision !== catalogRevision) {
        catalogRevision = revision;
        void refreshRecords().catch(() => {});
      }
    };
    const unsubscribe = workouts.subscribe(receive);
    const initial = () => {
      void refresh().catch(error => {
        if (mounted) actions.reportError(error);
      });
    };
    const visibility = (active: boolean) => {
      stateDelivery.setActive(active);
      if (active) initial();
      else setHistoryActive(false);
    };
    visibility(appIsForeground());
    const unsubscribeVisibility = subscribeAppVisibility(visibility);
    return () => {
      mounted = false;
      stateDelivery.setActive(false);
      stateDelivery.beginRefresh();
      unsubscribe();
      unsubscribeVisibility();
    };
  }, [actions, refresh, refreshRecords, setHistoryActive, stateDelivery]);
  const run = useCallback(
    (operation: (flight: WorkoutActionFlight) => Promise<unknown>, kind?: WorkoutActionKind) =>
      actions.run(operation, refresh, kind),
    [actions, refresh],
  );
  const finishRide = useCallback(
    async (id: string, discard: boolean) => {
      const request = state.externalFinish;
      if (request) {
        if (!workouts.finishExternal) throw new RideFailureError('actionUnavailable');
        await finishExternalRide(state, request, id, discard, workouts.finishExternal);
      } else throw new RideFailureError('rideChanged');
    },
    [state],
  );
  const cancelFinish = useCallback(() => {
    const request = state.externalFinish;
    if (request && workouts.cancelExternal) void run(() => workouts.cancelExternal!(request)).catch(() => {});
  }, [run, state.externalFinish]);
  return useMemo(
    () => ({
      state,
      ready,
      records,
      catalogLoading,
      catalogUnreadableCount,
      catalogUnindexedCount,
      catalogError,
      clearCatalogError,
      catalogHasMore,
      loadMoreRecords,
      refreshRecords,
      setHistoryActive,
      busy,
      recoveryAvailable,
      options,
      optionsReady,
      setOptions,
      error,
      run,
      refresh,
      permissions,
      permissionsError,
      refreshPermissions,
      finishRide,
      cancelFinish,
      clearError: actions.clearError,
    }),
    [
      actions,
      finishRide,
      cancelFinish,
      state,
      ready,
      records,
      catalogLoading,
      catalogUnreadableCount,
      catalogUnindexedCount,
      catalogError,
      clearCatalogError,
      catalogHasMore,
      loadMoreRecords,
      refreshRecords,
      setHistoryActive,
      busy,
      recoveryAvailable,
      options,
      optionsReady,
      setOptions,
      error,
      run,
      refresh,
      permissions,
      permissionsError,
      refreshPermissions,
    ],
  );
}
const Context = createContext<ReturnType<typeof useWorkoutController> | null>(null);
const IdentityContext = createContext<Pick<WorkoutState, 'id' | 'phase'>>(unavailableWorkoutState);
export function WorkoutProvider({ children }: { children: ReactNode }) {
  const value = useWorkoutController();
  const { id, phase } = value.state;
  const identity = useMemo(() => ({ id, phase }), [id, phase]);
  return (
    <IdentityContext.Provider value={identity}>
      <Context.Provider value={value}>{children}</Context.Provider>
    </IdentityContext.Provider>
  );
}
export function useWorkoutIdentity() {
  return useContext(IdentityContext);
}
export function useWorkout() {
  const context = useContext(Context);
  if (!context) throw new Error('WorkoutProvider is missing.');
  return context;
}
