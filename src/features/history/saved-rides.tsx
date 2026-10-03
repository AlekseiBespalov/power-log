import { useEffect, useMemo, useState } from 'react';
import { Platform, Linking, Pressable, ScrollView, Text, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { Body, Button, Card, Chip, colors, formatDuration, Heading, Metric, styles } from '../../components/ui';
import { ModalDialog } from '../../components/modal-dialog';
import { DistanceSourceCaption } from '../../components/distance-source-caption';
import { RoutePreview } from '../../components/route-preview';
import { workoutCanDelete, workoutExportReady, workoutRecoveryAction, type WorkoutMetadata } from '../../core/workouts';
import { ArchiveActions } from '../../services/workout-actions';
import { useWorkout } from '../../services/workout-context';
import { workouts } from '../../services/workouts';
import { importRecording } from '../../services/files';
import { exportRecording, exportRide } from '../../services/ride-export';
import { workoutMonitorSource } from '../../services/workout-monitor-source';
import { CsvRideDetails, type CsvRide } from './csv-ride-details';
import { MonitorPanel } from '../monitor/monitor-panel';
import { useReadResource } from '../../services/use-read-resource';
import { useMonitorPreferences } from '../../services/monitor-preferences';
import { metricById, formatMetric } from '../../core/monitor';
import { useForegroundActivity } from '../../services/use-foreground-activity';

const recordKey = (id: string) => `record:${id}`;
const number = (value: number | null | undefined, decimals = 0) =>
  value == null || !Number.isFinite(value) ? '—' : value.toFixed(decimals);
const dateLabel = (date: string) =>
  new Date(date).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' });
const phases: Record<string, string> = {
  preparing: 'Preparing',
  running: 'Recording',
  paused: 'Paused',
  recoverable: 'Needs attention',
  finishing: 'Ended',
  failed: 'Failed',
};
const phaseLabel = (phase: string) => phases[phase] ?? phase;
type HistorySelection = null | { kind: 'saved'; id: string } | { kind: 'csv'; csv: CsvRide };
function elapsedLabel(record: WorkoutMetadata) {
  return `${formatDuration(record.elapsedSeconds)} elapsed`;
}
function healthLabel(record: WorkoutMetadata) {
  if (record.healthReason && record.healthKitState !== 'saved') return `Health not saved: ${record.healthReason}`;
  if (record.healthKitState === 'notRequested') return 'Saved only in Power Log';
  return `Health ${record.healthKitState === 'notSaved' ? 'not saved' : record.healthKitState}`;
}

export function SavedRides() {
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
  const { distanceSource, speedUnit } = preferences;
  const speedMetric = metricById('speedMps', speedUnit)!;
  const [navigation, setNavigation] = useState<{ selection: HistorySelection; version: number }>({
    selection: null,
    version: 0,
  });
  const selection = navigation.selection;
  const navigate = (next: HistorySelection) =>
    setNavigation(value => ({ selection: next, version: value.version + 1 }));
  const clearSaved = (id: string) =>
    setNavigation(value =>
      value.selection?.kind === 'saved' && value.selection.id === id
        ? { selection: null, version: value.version + 1 }
        : value,
    );
  const selectedId = selection?.kind === 'saved' ? selection.id : null;
  const csv = selection?.kind === 'csv' ? selection.csv : null;
  const insets = useSafeAreaInsets();
  const [removalTarget, setRemovalTarget] = useState<WorkoutMetadata | null>(null);
  const [removedIds, setRemovedIds] = useState<ReadonlySet<string>>(() => new Set());
  const [observedDeletion, setObservedDeletion] = useState<string | null>();
  const records = useMemo(
    () =>
      workout.records.filter(record => !removedIds.has(record.id) && record.id !== workout.state.lastDeletedWorkoutId),
    [workout.records, workout.state.lastDeletedWorkoutId, removedIds],
  );
  const [refreshVersion, setRefreshVersion] = useState(0);
  const [editing, setEditing] = useState(false);
  const [repairingId, setRepairingId] = useState<string | null>(null);
  const latest = records.find(record => record.id === selectedId);
  const revision = selectedId
    ? JSON.stringify([
        selectedId,
        latest?.collectionRevision,
        latest?.sealRevision,
        latest?.verifiedSealRevision,
        latest?.finalizationState,
        latest?.phase,
        latest?.watchSyncState,
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
  const resource = useReadResource(selectedId, revision, () => workouts.read(selectedId!, distanceSource));
  const { data: detail, loading, error } = resource;
  const deleted =
    selectedId !== null &&
    (workout.state.lastDeletedWorkoutId === selectedId || resource.errorCode === 'ERR_RIDE_DELETED');
  const refreshHistory = workout.refreshRecords;
  useEffect(() => {
    if (removedIds.size) void refreshHistory().catch(() => {});
  }, [removedIds, refreshHistory]);
  if (observedDeletion !== workout.state.lastDeletedWorkoutId) {
    setObservedDeletion(workout.state.lastDeletedWorkoutId);
    const id = workout.state.lastDeletedWorkoutId;
    if (id && !removedIds.has(id)) setRemovedIds(ids => new Set([...ids, id]));
    if (id && removalTarget?.id === id) setRemovalTarget(null);
  }
  if (deleted && selectedId) {
    setRemovedIds(ids => new Set([...ids, selectedId]));
    clearSaved(selectedId);
    setRemovalTarget(null);
  }
  const action = (key: string, operation: () => Promise<unknown>) => () => {
    void archiveActions.run(key, operation);
  };
  const selectedKey = selectedId ? recordKey(selectedId) : 'csv';
  const removalKey = removalTarget ? recordKey(removalTarget.id) : null;
  const removing = removalKey !== null && pending(removalKey);
  const record = deleted ? null : (latest ?? (detail?.metadata.id === selectedId ? detail.metadata : null));
  const summary = detail?.metadata.id === selectedId ? detail.summary : null;
  const ready = record ? workoutExportReady(record) : false;
  const recoveryAction = record ? workoutRecoveryAction(record, workout.state.id) : null;
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
      if (!workoutCanDelete(current, workout.state)) throw new Error('Finish or resolve this ride before deleting it.');
      await workouts.remove(target.id);
      setRemovedIds(ids => new Set([...ids, target.id]));
      if (records.length <= 1) setEditing(false);
      clearSaved(target.id);
      resource.clearError();
      setRemovalTarget(value => (value?.id === target.id ? null : value));
    });
  };

  return (
    <View style={{ gap: 12 }}>
      <View
        testID="history-toolbar"
        style={{
          flexDirection: 'row',
          flexWrap: 'wrap',
          alignItems: 'center',
          justifyContent: 'space-between',
          gap: 12,
        }}
      >
        {selection !== null ? (
          <Pressable
            accessibilityRole="button"
            onPress={() => {
              navigate(null);
              resource.clearError();
            }}
            style={{ minHeight: 44, justifyContent: 'center' }}
          >
            <Text style={{ color: colors.accent }}>‹ Rides</Text>
          </Pressable>
        ) : (
          <Body muted>
            {records.length} {records.length === 1 ? 'ride' : 'rides'}
          </Body>
        )}
        <View style={[styles.row, { maxWidth: '100%' }]}>
          {selection === null && records.length > 0 && (
            <Button secondary onPress={() => setEditing(value => !value)}>
              {editing ? 'Done' : 'Edit'}
            </Button>
          )}
          <Button
            secondary
            disabled={pending('catalog')}
            onPress={action('catalog', async () => {
              resource.clearError();
              await refreshHistory();
              if (selectedId) setRefreshVersion(value => value + 1);
            })}
          >
            Refresh
          </Button>
          <Button
            secondary
            disabled={pending('csv')}
            onPress={action('csv', async () => {
              const started = navigation.version;
              const file = await importRecording();
              if (!file) return;
              setNavigation(value =>
                value.version === started
                  ? {
                      selection: { kind: 'csv', csv: { title: file.name, samples: file.recording.samples } },
                      version: value.version + 1,
                    }
                  : value,
              );
              setEditing(false);
            })}
          >
            Open CSV
          </Button>
        </View>
      </View>
      {(workout.catalogUnreadableCount > 0 || workout.catalogUnindexedCount > 0) && (
        <Card style={{ padding: 14, gap: 6 }}>
          {workout.catalogUnreadableCount > 0 && (
            <Text accessibilityRole="alert" style={{ color: colors.muted }}>
              {workout.catalogUnreadableCount} unreadable{' '}
              {workout.catalogUnreadableCount === 1 ? 'ride was' : 'rides were'} skipped while loading this page.
            </Text>
          )}
          {workout.catalogUnindexedCount > 0 && (
            <Text accessibilityRole="alert" style={{ color: colors.muted }}>
              {workout.catalogUnindexedCount} {workout.catalogUnindexedCount === 1 ? 'ride is' : 'rides are'} missing
              from the History index.
            </Text>
          )}
        </Card>
      )}
      {visibleError && (
        <Card style={{ padding: 14, gap: 10 }}>
          <Text accessibilityRole="alert" style={{ color: colors.red }}>
            {visibleError}
          </Text>
          <View style={styles.row}>
            {workout.catalogError && (
              <Button
                secondary
                disabled={workout.catalogLoading}
                onPress={action('catalog', async () => {
                  workout.clearCatalogError();
                  await refreshHistory();
                })}
              >
                Retry
              </Button>
            )}
            <Button
              secondary
              onPress={() => {
                resource.clearError();
                archiveActions.clearError(selectedKey);
                archiveActions.clearError('catalog');
                archiveActions.clearError('csv');
                workout.clearError();
                workout.clearCatalogError();
              }}
            >
              Dismiss
            </Button>
          </View>
        </Card>
      )}
      {csv ? (
        <>
          <CsvRideDetails ride={csv} />
          <Button
            secondary
            disabled={pending('csv')}
            onPress={action('csv', () => exportRecording(csv.title, csv.samples))}
          >
            Export CSV
          </Button>
        </>
      ) : selectedId ? (
        <>
          {!record && !error && !deleted && <Body muted>Loading ride details…</Body>}
          {record && (
            <View style={{ gap: 14 }}>
              <View style={{ gap: 4 }}>
                <Heading>{dateLabel(record.startedAt)}</Heading>
                {record.example && <Chip>Example</Chip>}
                <Body muted>
                  {record.indoor ? 'Indoor' : 'Outdoor'} · E-bike
                  {record.phase !== 'completed' ? ` · ${phaseLabel(record.phase)}` : ''}
                  {record.interrupted ? ' · Recovered' : ''}
                </Body>
              </View>
              {summary ? (
                <View testID="ride-summary" style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 18 }}>
                  <Metric label="Ride time" value={formatDuration(summary.timerSeconds)} unit="" />
                  {(summary.distance || summary.distanceMeters != null) && (
                    <Metric
                      label="Ride distance"
                      value={number(summary.distanceMeters == null ? null : summary.distanceMeters / 1000, 2)}
                      unit="km"
                    />
                  )}
                  {summary.healthDistanceMeters != null && (
                    <Metric
                      label={
                        summary.healthDistanceProvisional ? 'Health distance · Provisional' : 'Health reported distance'
                      }
                      value={number(summary.healthDistanceMeters / 1000, 2)}
                      unit="km"
                    />
                  )}
                  {(summary.distance || summary.averageSpeedMps != null) && (
                    <Metric
                      label={summary.distance ? 'Recorded avg speed' : 'Avg speed'}
                      value={formatMetric(speedMetric, summary.averageSpeedMps)}
                      unit={speedMetric.unit}
                    />
                  )}
                  <Metric label="Avg power" value={number(summary.averageRiderPowerW)} unit="W" color={colors.accent} />
                  <Metric
                    label="Avg cadence"
                    value={number(summary.averageCadenceRpm)}
                    unit="rpm"
                    color={colors.cadence}
                  />
                  {summary.averageHeartRateBpm != null && (
                    <Metric
                      label="Avg heart rate"
                      value={number(summary.averageHeartRateBpm)}
                      unit="bpm"
                      color={colors.red}
                    />
                  )}
                  {summary.ascentMeters != null && (
                    <Metric label="Ascent" value={number(summary.ascentMeters)} unit="m" />
                  )}
                  {summary.activeEnergyKcal != null && (
                    <Metric label="Active energy" value={number(summary.activeEnergyKcal)} unit="kcal" />
                  )}
                </View>
              ) : (
                <Body muted>
                  {loading ? 'Preparing summary… Charts are available below.' : 'Ride summary unavailable.'}
                </Body>
              )}
              {summary?.distance && (
                <View testID="ride-distance-source" style={{ gap: 6 }}>
                  <DistanceSourceCaption
                    testID="distance-source-caption"
                    info={summary.distance.selected ?? undefined}
                    unavailable="Distance unavailable"
                  />
                </View>
              )}
              {(record.recordGPS ?? !record.indoor) && summary && <RoutePreview points={summary.routePreview} />}
              <MonitorPanel source={monitorSource} embedded />
              <Body muted>
                {record.storage === 'browser'
                  ? 'Browser'
                  : record.watchEnabled
                    ? 'Watch + iPhone'
                    : Platform.OS === 'android'
                      ? 'Android'
                      : 'iPhone'}
                {record.storage !== 'browser' ? ` · ${healthLabel(record)}` : ''}
                {summary && summary.lapCount > 0 ? ` · ${summary.lapCount} laps` : ''}
              </Body>
              {record.finalizationState === 'pending' && (
                <Body>
                  {record.syncReason
                    ? `Watch sync failed: ${record.syncReason}`
                    : 'Ride ended. Syncing remaining data…'}
                </Body>
              )}
              {record.finalizationState === 'partial' && <Body>Incomplete ride. Some sources are unavailable.</Body>}
              {!ready && (
                <Body muted>
                  {record.watchEnabled
                    ? 'Exports are available when syncing finishes.'
                    : 'Exports are available when saving finishes.'}
                </Body>
              )}
              {recoveryAction && (
                <Button
                  secondary
                  disabled={workout.busy || pending(recordKey(record.id))}
                  onPress={() => {
                    void workout.run(async () => {
                      setRepairingId(record.id);
                      try {
                        await workouts.recover(record.id);
                        setRefreshVersion(value => value + 1);
                      } finally {
                        setRepairingId(null);
                      }
                    });
                  }}
                >
                  {repairingId === record.id
                    ? 'Checking ride…'
                    : recoveryAction === 'repair'
                      ? record.healthProvider === 'healthConnect'
                        ? 'Retry Health Connect'
                        : 'Finish saving'
                      : 'Retry'}
                </Button>
              )}
              <View style={styles.row}>
                <Button
                  disabled={pending(recordKey(record.id)) || !ready}
                  onPress={action(recordKey(record.id), () => exportRide('fit', record.id, distanceSource))}
                >
                  Export FIT
                </Button>
                <Button
                  secondary
                  disabled={pending(recordKey(record.id)) || !ready}
                  onPress={action(recordKey(record.id), () => exportRide('zip', record.id))}
                >
                  Export ZIP
                </Button>
              </View>
              {!record.example && (
                <View style={{ gap: 8 }}>
                  <Body muted>Export FIT, then select the file on Strava.</Body>
                  <Button
                    secondary
                    disabled={pending(recordKey(record.id)) || !ready}
                    onPress={action(recordKey(record.id), () =>
                      Linking.openURL('https://www.strava.com/upload/select'),
                    )}
                  >
                    Open Strava upload
                  </Button>
                </View>
              )}
              {workoutCanDelete(record, workout.state) && (
                <Button
                  secondary
                  disabled={pending(recordKey(record.id)) || repairingId === record.id}
                  onPress={() => askToRemove(record)}
                >
                  Delete ride
                </Button>
              )}
            </View>
          )}
        </>
      ) : records.length ? (
        <View style={{ borderTopWidth: 1, borderTopColor: colors.border }}>
          {records.map(record => {
            const secondary = [
              record.example ? 'Example' : null,
              record.indoor ? 'Indoor' : 'Outdoor',
              elapsedLabel(record),
              record.interrupted ? 'Recovered' : null,
            ]
              .filter(Boolean)
              .join(' · ');
            const unavailable = ['preparing', 'running', 'paused'].includes(record.phase);
            return (
              <View key={record.id} style={{ borderBottomWidth: 1, borderBottomColor: colors.border }}>
                <View style={{ flexDirection: 'row', alignItems: 'center', gap: 8 }}>
                  <Pressable
                    accessibilityRole="button"
                    accessibilityLabel={`Open ride, ${dateLabel(record.startedAt)}, ${secondary}`}
                    accessibilityState={{ disabled: unavailable }}
                    disabled={unavailable}
                    onPress={() => {
                      navigate({ kind: 'saved', id: record.id });
                      setEditing(false);
                    }}
                    style={({ pressed }) => ({
                      flex: 1,
                      minHeight: 76,
                      paddingVertical: 14,
                      paddingHorizontal: 2,
                      flexDirection: 'row',
                      alignItems: 'center',
                      gap: 12,
                      opacity: unavailable ? 0.55 : pressed ? 0.7 : 1,
                    })}
                  >
                    <View style={{ flex: 1, gap: 5 }}>
                      <Text style={{ color: colors.text, fontSize: 15, fontWeight: '500' }}>
                        {dateLabel(record.startedAt)}
                      </Text>
                      <Text style={{ color: colors.muted, fontSize: 13 }}>{secondary}</Text>
                    </View>
                    {record.phase !== 'completed' && (
                      <Text style={{ color: record.phase === 'failed' ? colors.red : colors.muted, fontSize: 12 }}>
                        {phaseLabel(record.phase)}
                      </Text>
                    )}
                    <Text style={{ color: colors.muted, fontSize: 22 }}>›</Text>
                  </Pressable>
                  {editing && workoutCanDelete(record, workout.state) && (
                    <Pressable
                      accessibilityRole="button"
                      accessibilityLabel={`Delete ride, ${dateLabel(record.startedAt)}`}
                      disabled={pending(recordKey(record.id))}
                      accessibilityState={{ disabled: pending(recordKey(record.id)) }}
                      onPress={() => askToRemove(record)}
                      style={{
                        minHeight: 44,
                        minWidth: 44,
                        justifyContent: 'center',
                        padding: 8,
                        opacity: pending(recordKey(record.id)) ? 0.4 : 1,
                      }}
                    >
                      <Text style={{ color: colors.red, fontSize: 13 }}>Delete</Text>
                    </Pressable>
                  )}
                </View>
                {archiveState.get(recordKey(record.id))?.error && (
                  <View style={{ gap: 6, paddingBottom: 12 }}>
                    <Text accessibilityRole="alert" style={{ color: colors.red }}>
                      {archiveState.get(recordKey(record.id))!.error}
                    </Text>
                    <Button secondary onPress={() => archiveActions.clearError(recordKey(record.id))}>
                      Dismiss
                    </Button>
                  </View>
                )}
              </View>
            );
          })}
          {workout.catalogHasMore && (
            <Button
              secondary
              disabled={workout.catalogLoading}
              onPress={() => {
                void workout.loadMoreRecords().catch(() => {});
              }}
            >
              {workout.catalogLoading ? 'Loading more rides…' : 'Load more rides'}
            </Button>
          )}
        </View>
      ) : (
        <Card style={{ padding: 20 }}>
          <Body muted>
            {workout.catalogLoading
              ? 'Loading saved rides…'
              : workout.catalogError
                ? 'Saved ride catalog unavailable.'
                : 'No saved rides yet.'}
          </Body>
        </Card>
      )}
      <ModalDialog
        visible={removalTarget !== null}
        onClose={() => {
          if (!removing) setRemovalTarget(null);
        }}
        closeLabel="Cancel ride deletion"
        testID="delete-ride-sheet"
        style={{
          width: '100%',
          maxWidth: 600,
          alignSelf: 'center',
          backgroundColor: colors.surface,
          borderWidth: 1,
          borderColor: colors.border,
          borderTopLeftRadius: 16,
          borderTopRightRadius: 16,
        }}
      >
        <ScrollView
          testID="delete-ride-scroll"
          style={{ flexShrink: 1 }}
          contentContainerStyle={{ padding: 20, paddingBottom: Math.max(20, insets.bottom), gap: 12 }}
        >
          <Heading>Delete ride?</Heading>
          {removalTarget && <Body muted>{dateLabel(removalTarget.startedAt)}</Body>}
          <Body>
            This permanently removes the recording and charts from Power Log.
            {removalTarget?.watchEnabled ? ' The Watch copy will be removed when it connects.' : ''}
            {removalTarget?.storage !== 'browser'
              ? ` Workouts already saved in ${Platform.OS === 'android' ? 'Health Connect' : 'Apple Health'} stay there.`
              : ''}
          </Body>
          {removalKey && archiveState.get(removalKey)?.error && (
            <Text accessibilityRole="alert" style={{ color: colors.red }}>
              {archiveState.get(removalKey)!.error}
            </Text>
          )}
          <Button
            danger
            disabled={
              removing ||
              !removalTarget ||
              !workoutCanDelete(records.find(record => record.id === removalTarget.id) ?? removalTarget, workout.state)
            }
            onPress={confirmRemoval}
          >
            {removing ? 'Deleting…' : 'Delete ride'}
          </Button>
          <Button secondary disabled={removing} onPress={() => setRemovalTarget(null)}>
            Keep ride
          </Button>
        </ScrollView>
      </ModalDialog>
    </View>
  );
}
