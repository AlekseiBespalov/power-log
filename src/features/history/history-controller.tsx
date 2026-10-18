import {
  createContext,
  useContext,
  useEffect,
  useLayoutEffect,
  useMemo,
  useCallback,
  useState,
  type ReactNode,
} from 'react';
import { useGlobalSearchParams, usePathname, useRouter } from 'expo-router';
import { workoutCanDelete, workoutExportReady, type WorkoutMetadata } from '../../core/workouts';
import { RideFailureError } from '../../core/failures';
import { ArchiveActions } from '../../services/workout-actions';
import { useWorkout } from '../../services/workout-context';
import { workouts } from '../../services/workouts';
import { importRecording } from '../../services/files';
import { workoutMonitorSource } from '../../services/workout-monitor-source';
import { useReadResource } from '../../services/use-read-resource';
import { useMonitorPreferences } from '../../services/monitor-preferences';
import { useForegroundActivity } from '../../services/use-foreground-activity';
import type { CsvRide } from './csv-ride-details';
import { HistorySelection } from './history-selection';

export const recordKey = (id: string) => `record:${id}`;
type HistorySelectionTarget = null | { kind: 'saved'; id: string } | { kind: 'csv'; csv: CsvRide };

function useController() {
  const workout = useWorkout();
  const [archiveState, setArchiveState] = useState<ReturnType<ArchiveActions['snapshot']>>(() => new Map());
  const [archiveActions] = useState(() => new ArchiveActions(setArchiveState));
  const pending = (key: string) => archiveState.get(key)?.pending === true;
  const active = useForegroundActivity(),
    setHistoryActive = workout.setHistoryActive;
  useEffect(() => {
    setHistoryActive(active);
    return () => setHistoryActive(false);
  }, [active, setHistoryActive]);
  const { preferences } = useMonitorPreferences();
  const router = useRouter();
  const pathname = usePathname();
  const params = useGlobalSearchParams<{ id?: string | string[] }>();
  const id = typeof params.id === 'string' && params.id.length > 0 ? params.id : null;
  const selectedId = pathname === '/sessions/ride' ? id : null;
  const [csv, setCsv] = useState<CsvRide | null>(null);
  const routeKey = `${pathname}?${selectedId ?? ''}`;
  const [navigation] = useState(() => new HistorySelection());
  const [observedRoute, setObservedRoute] = useState(routeKey);
  useLayoutEffect(() => navigation.observeRoute(routeKey, selectedId), [navigation, routeKey, selectedId]);
  const selection = selectedId
    ? { kind: 'saved' as const, id: selectedId }
    : csv
      ? { kind: 'csv' as const, csv }
      : null;
  const navigate = (next: HistorySelectionTarget) => {
    navigation.choose(next?.kind === 'saved' ? next.id : null);
    setCsv(next?.kind === 'csv' ? next.csv : null);
    setRemovalTarget(null);
    if (next?.kind === 'saved') router.push({ pathname: '/sessions/ride', params: { id: next.id } });
    else if (pathname === '/sessions/ride') router.dismissTo('/sessions');
  };
  const clearSaved = useCallback(
    (id: string) => {
      if (navigation.selects(id)) router.dismissTo('/sessions');
    },
    [navigation, router],
  );
  const [removalTarget, setRemovalTarget] = useState<WorkoutMetadata | null>(null);
  if (observedRoute !== routeKey) {
    setObservedRoute(routeKey);
    setRemovalTarget(null);
    if (selectedId && csv) setCsv(null);
  }
  const [removedIds, setRemovedIds] = useState<ReadonlySet<string>>(() => new Set());
  const records = useMemo(
    () =>
      workout.records.filter(record => !removedIds.has(record.id) && record.id !== workout.state.lastDeletedWorkoutId),
    [workout.records, workout.state.lastDeletedWorkoutId, removedIds],
  );
  const [refreshVersion, setRefreshVersion] = useState(0);
  const [editing, setEditing] = useState(false);
  const [repairingId, setRepairingId] = useState<string | null>(null);
  const latest = records.find(record => record.id === selectedId);
  const distanceSource = latest?.distanceSource ?? 'auto';
  const revision = selectedId
    ? JSON.stringify([
        selectedId,
        latest?.collectionRevision,
        latest?.phase,
        latest?.healthKitState,
        latest?.healthReason,
        latest?.componentResults,
        latest?.exportEligible,
        latest?.endedAt,
        workout.state.historyRevision,
        refreshVersion,
        distanceSource,
      ])
    : null;
  const baseMonitorSource = useMemo(
    () => workoutMonitorSource(selectedId ?? '', false, distanceSource),
    [selectedId, distanceSource],
  );
  const monitorSource = useMemo(
    () => ({ ...baseMonitorSource, revisionHint: revision ?? undefined }),
    [baseMonitorSource, revision],
  );
  const resource = useReadResource(selectedId, revision, (_consumer, _current, signal) =>
    workouts.read(selectedId!, distanceSource, signal),
  );
  const { data: detail, loading, error } = resource;
  const deleted =
    selectedId !== null && (workout.state.lastDeletedWorkoutId === selectedId || resource.errorCode === 'rideDeleted');
  const refreshHistory = workout.refreshRecords;
  useEffect(() => {
    if (removedIds.size) void refreshHistory().catch(() => {});
  }, [removedIds, refreshHistory]);
  const [observedDeletion, setObservedDeletion] = useState<string | null>();
  if (observedDeletion !== workout.state.lastDeletedWorkoutId) {
    const id = workout.state.lastDeletedWorkoutId;
    setObservedDeletion(id);
    if (id && !removedIds.has(id)) setRemovedIds(ids => new Set([...ids, id]));
    if (id && removalTarget?.id === id) setRemovalTarget(null);
  }
  if (deleted && selectedId && !removedIds.has(selectedId)) {
    setRemovedIds(ids => new Set([...ids, selectedId]));
    if (removalTarget?.id === selectedId) setRemovalTarget(null);
  }
  useEffect(() => {
    if (deleted && selectedId) clearSaved(selectedId);
  }, [deleted, selectedId, clearSaved]);
  const action = (key: string, operation: () => Promise<unknown>) => () => {
    void archiveActions.run(key, operation);
  };
  const selectedKey = selectedId ? recordKey(selectedId) : 'csv';
  const removalKey = removalTarget ? recordKey(removalTarget.id) : null;
  const removing = removalKey !== null && pending(removalKey);
  const record = deleted ? null : (latest ?? (detail?.metadata.id === selectedId ? detail.metadata : null));
  const summary = detail?.metadata.id === selectedId ? detail.summary : null;
  const ready = record ? workoutExportReady(record) : false;
  const visibleError =
    (deleted ? null : error) ??
    archiveState.get(selectedKey)?.error ??
    archiveState.get('catalog')?.error ??
    archiveState.get('csv')?.error ??
    workout.error ??
    workout.catalogError;
  const askToRemove = (target: WorkoutMetadata) => {
    archiveActions.clearError(recordKey(target.id));
    setRemovalTarget({ ...target });
  };
  const confirmRemoval = () => {
    if (!removalTarget) return;
    const target = removalTarget;
    void archiveActions.run(recordKey(target.id), async () => {
      const current = records.find(record => record.id === target.id) ?? target;
      if (current.phase === 'interrupted' && workout.state.id === current.id) await workouts.discard(target.id);
      else {
        if (!workoutCanDelete(current, workout.state)) throw new RideFailureError('actionUnavailable');
        await workouts.remove(target.id);
      }
      await workout.refresh();
      setRemovedIds(ids => new Set([...ids, target.id]));
      if (records.length <= 1) setEditing(false);
      clearSaved(target.id);
      if (navigation.selects(target.id)) resource.clearError();
      setRemovalTarget(value => (value?.id === target.id ? null : value));
    });
  };

  const refresh = action('catalog', async () => {
    resource.clearError();
    await refreshHistory();
    setRefreshVersion(value => value + 1);
  });
  const openCsv = () => {
    void archiveActions.run(
      'csv',
      async () => {
        const started = navigation.token();
        const file = await importRecording();
        if (!file || !navigation.accepts(started)) return;
        navigate({ kind: 'csv', csv: { title: file.name, samples: file.recording.samples } });
        setEditing(false);
      },
      "Power Log couldn't open this CSV. Choose telemetry.csv from a Power Log ride-data ZIP.",
    );
  };
  return {
    workout,
    archiveState,
    archiveActions,
    pending,
    preferences,
    selection,
    selectedId,
    csv,
    removalTarget,
    setRemovalTarget,
    records,
    editing,
    setEditing,
    repairingId,
    setRepairingId,
    setRefreshVersion,
    monitorSource,
    resource,
    loading,
    error,
    deleted,
    record,
    summary,
    ready,
    visibleError,
    action,
    selectedKey,
    removalKey,
    removing,
    askToRemove,
    confirmRemoval,
    navigate,
    refreshHistory,
    refresh,
    openCsv,
  };
}
const HistoryContext = createContext<ReturnType<typeof useController> | null>(null);
export function HistoryProvider({ children }: { children: ReactNode }) {
  const controller = useController();
  return <HistoryContext.Provider value={controller}>{children}</HistoryContext.Provider>;
}
export function useHistory() {
  const controller = useContext(HistoryContext);
  if (!controller) throw new Error('HistoryProvider is missing');
  return controller;
}
