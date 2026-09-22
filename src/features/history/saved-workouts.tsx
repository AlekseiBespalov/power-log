import { useEffect, useMemo, useState } from 'react';
import { Linking, Pressable, ScrollView, Text, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { Body, Button, Card, Chip, colors, formatDuration, Heading, Metric, styles } from '../../components/ui';
import { ModalDialog } from '../../components/modal-dialog';
import { DistanceSourceCaption } from '../../components/distance-source-caption';
import { RoutePreview } from '../../components/route-preview';
import { workoutCanDelete, workoutExportReady, workoutRecoveryAction, type WorkoutMetadata } from '../../core/workouts';
import { useWorkout } from '../../services/workout-context';
import { workouts } from '../../services/workouts';
import { exportWorkoutFile, importText, exportText } from '../../services/files';
import { workoutMonitorSource } from '../../services/workout-monitor-source';
import { CsvRideDetails, type CsvRide } from './csv-ride-details';
import { parseCsv } from '../../core/recordings';
import { MonitorPanel } from '../monitor/monitor-panel';
import { useReadResource } from '../../services/use-read-resource';
import { useMonitorPreferences } from '../../services/monitor-preferences';
import { metricById, formatMetric } from '../../core/monitor';
import { useForegroundActivity } from '../../services/use-foreground-activity';

const number = (value: number | null | undefined, decimals = 0) => value == null || !Number.isFinite(value) ? '—' : value.toFixed(decimals);
const dateLabel = (date: string) => new Date(date).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' });
const phases: Record<string, string> = { preparing: 'Preparing', running: 'Recording', paused: 'Paused', recoverable: 'Needs attention', finishing: 'Ended', failed: 'Failed' };
const phaseLabel = (phase: string) => phases[phase] ?? phase;
function elapsedLabel(record: WorkoutMetadata) {
  if (!record.endedAt) return null;
  const seconds = (Date.parse(record.endedAt) - Date.parse(record.startedAt)) / 1000;
  return Number.isFinite(seconds) && seconds >= 0 ? `${formatDuration(seconds)} elapsed` : null;
}

export function SavedWorkouts() {
  const workout = useWorkout();
  const active = useForegroundActivity(), setHistoryActive = workout.setHistoryActive;
  useEffect(() => { setHistoryActive(active); return () => setHistoryActive(false); }, [active, setHistoryActive]);
  const { preferences } = useMonitorPreferences();
  const { distanceSource, speedUnit } = preferences;
  const speedMetric = metricById('speedMps', speedUnit)!;
  const [csvRide, setCsvRide] = useState<CsvRide | null>(null);
  const insets = useSafeAreaInsets();
  const [removalTarget, setRemovalTarget] = useState<WorkoutMetadata | null>(null);
  const [removedIds, setRemovedIds] = useState<ReadonlySet<string>>(() => new Set());
  const [observedDeletion, setObservedDeletion] = useState<string | null>();
  const records = useMemo(() => workout.records.filter(record => !removedIds.has(record.id) && record.id !== workout.state.lastDeletedWorkoutId), [workout.records, workout.state.lastDeletedWorkoutId, removedIds]);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [noticeId, setNoticeId] = useState<string | null>(null);
  const showNotices = selectedId !== null && noticeId === selectedId;
  const [refreshVersion, setRefreshVersion] = useState(0);
  const [editing, setEditing] = useState(false);
  const [repairingId, setRepairingId] = useState<string | null>(null);
  const latest = records.find(record => record.id === selectedId);
  const revision = selectedId ? JSON.stringify([selectedId, latest?.collectionRevision, latest?.sealRevision, latest?.verifiedSealRevision, latest?.finalizationState, latest?.eventCount, latest?.phase, latest?.watchSyncState, latest?.endedAt, workout.state.historyRevision, refreshVersion, distanceSource]) : null;
  const baseMonitorSource = useMemo(() => workoutMonitorSource(selectedId ?? '', false, distanceSource), [selectedId, distanceSource]);
  const monitorSource = useMemo(() => ({ ...baseMonitorSource, revisionHint: revision ?? undefined }), [baseMonitorSource, revision]);
  const resource = useReadResource(selectedId, revision, () => workouts.read(selectedId!, distanceSource));
  const { data: detail, loading, error } = resource;
  const deleted = selectedId !== null && (workout.state.lastDeletedWorkoutId === selectedId || resource.errorCode === 'ERR_RIDE_DELETED');
  const refreshHistory = workout.refresh;
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
    setSelectedId(null); setNoticeId(null); setRemovalTarget(null);
  }
  const action = (operation: () => Promise<unknown>) => () => { void workout.run(operation); };
  const record = deleted ? null : latest ?? (detail?.metadata.id === selectedId ? detail.metadata : null);
  const summary = detail?.metadata.id === selectedId ? detail.summary : null;
  const ready = record ? workoutExportReady(record) : false;
  const recoveryAction = record ? workoutRecoveryAction(record, workout.state.id) : null;
  const warnings = [...new Set([...(record?.warnings ?? []), ...(summary?.warnings ?? [])])];
  const visibleError = (deleted ? null : error) ?? workout.error ?? workout.catalogError;
  const askToRemove = (target: WorkoutMetadata) => { workout.clearError(); setRemovalTarget({ ...target }); };
  const confirmRemoval = () => {
    if (!removalTarget) return;
    const target = removalTarget;
    void workout.run(async () => {
      const current = records.find(record => record.id === target.id) ?? target;
      if (!workoutCanDelete(current, workout.state)) throw new Error('Finish or resolve this ride before deleting it.');
      await workouts.remove(target.id);
      setRemovedIds(ids => new Set([...ids, target.id]));
      if (records.length <= 1) setEditing(false);
      setSelectedId(id => id === target.id ? null : id);
      setNoticeId(id => id === target.id ? null : id);
      resource.clearError(); setRemovalTarget(null);
    });
  };

  return <View style={{ gap: 12 }}>
    <View testID="history-toolbar" style={{ flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', justifyContent: 'space-between', gap: 12 }}>
      {selectedId || csvRide ? <Pressable accessibilityRole="button" onPress={() => { setSelectedId(null); setCsvRide(null); resource.clearError(); }} style={{ minHeight: 44, justifyContent: 'center' }}><Text style={{ color: colors.accent }}>‹ Rides</Text></Pressable> : <Body muted>{records.length} {records.length === 1 ? 'ride' : 'rides'}</Body>}
      <View style={[styles.row, { maxWidth: '100%' }]}>{!selectedId && !csvRide && records.length > 0 && <Button secondary onPress={() => setEditing(value => !value)}>{editing ? 'Done' : 'Edit'}</Button>}<Button secondary disabled={workout.busy} onPress={action(async () => {
        resource.clearError();
        await workout.refresh();
        if (selectedId) setRefreshVersion(value => value + 1);
      })}>Refresh</Button>
      <Button secondary disabled={workout.busy} onPress={action(async () => {
        const file = await importText(); if (!file) return;
        const parsed = parseCsv(file.text, { source: 'device' });
        setSelectedId(null); setEditing(false); setCsvRide({ title: file.name, csv: file.text, samples: parsed.samples });
      })}>Open CSV</Button></View>
    </View>
    {visibleError && <Card style={{ padding: 14, gap: 10 }}><Body>{visibleError}</Body><View style={styles.row}>
      {workout.catalogError && <Button secondary disabled={workout.catalogLoading} onPress={() => { workout.clearCatalogError(); void workout.refresh().catch(() => {}); }}>Retry</Button>}
      <Button secondary onPress={() => { resource.clearError(); workout.clearError(); workout.clearCatalogError(); }}>Dismiss</Button>
    </View></Card>}
    {csvRide ? <><CsvRideDetails ride={csvRide} /><Button secondary onPress={action(() => exportText(csvRide.title, csvRide.csv))}>Export CSV</Button></> : selectedId ? <>
      {!record && !error && !deleted && <Body muted>Loading ride details…</Body>}
      {record && <View style={{ gap: 14 }}>
        <View style={{ gap: 4 }}><Heading>{dateLabel(record.startedAt)}</Heading>{record.example && <Chip>Example</Chip>}<Body muted>{record.indoor ? 'Indoor' : 'Outdoor'} · E-bike{record.phase !== 'completed' ? ` · ${phaseLabel(record.phase)}` : ''}{record.interrupted ? ' · Recovered' : ''}</Body></View>
        {summary ? <View testID="ride-summary" style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 18 }}>
          <Metric label="Ride time" value={formatDuration(summary.timerSeconds)} unit="" />
          {(summary.distance || summary.distanceMeters != null) && <Metric label="Ride distance" value={number(summary.distanceMeters == null ? null : summary.distanceMeters / 1000, 2)} unit="km" />}
          {summary.healthDistanceMeters != null && <Metric label={summary.healthDistanceProvisional ? 'Health distance · Provisional' : 'Health reported distance'} value={number(summary.healthDistanceMeters / 1000, 2)} unit="km" />}
          {(summary.distance || summary.averageSpeedMps != null) && <Metric label={summary.distance ? 'Recorded avg speed' : 'Avg speed'} value={formatMetric(speedMetric, summary.averageSpeedMps)} unit={speedMetric.unit} />}
          <Metric label="Avg power" value={number(summary.averageRiderPowerW)} unit="W" color={colors.accent} />
          <Metric label="Avg cadence" value={number(summary.averageCadenceRpm)} unit="rpm" color={colors.cadence} />
          {summary.averageHeartRateBpm != null && <Metric label="Avg heart rate" value={number(summary.averageHeartRateBpm)} unit="bpm" color={colors.red} />}
          {summary.ascentMeters != null && <Metric label="Ascent" value={number(summary.ascentMeters)} unit="m" />}
          {summary.activeEnergyKcal != null && <Metric label="Active energy" value={number(summary.activeEnergyKcal)} unit="kcal" />}
        </View> : <Body muted>{loading ? 'Preparing summary… Charts are available below.' : 'Ride summary unavailable.'}</Body>}
        {summary?.distance && <View testID="ride-distance-source" style={{ gap: 6 }}>
          <DistanceSourceCaption testID="distance-source-caption" info={summary.distance.selected ?? undefined} unavailable="Distance unavailable" />

        </View>}
        {(record.recordGPS ?? !record.indoor) && summary && <RoutePreview points={summary.routePreview} />}
        <MonitorPanel source={monitorSource} embedded />
        <Body muted>{record.storage === 'browser' ? 'Browser' : record.watchEnabled ? 'Watch + iPhone' : 'iPhone'}{record.storage !== 'browser' ? ` · ${record.healthKitState === 'notRequested' ? 'Saved only in Power Log' : `Health ${record.healthKitState === 'notSaved' ? 'not saved' : record.healthKitState}`}` : ''}{summary && summary.lapCount > 0 ? ` · ${summary.lapCount} laps` : ''}</Body>
        {record.finalizationState === 'pending' && <Body>Ride ended. Syncing remaining data…</Body>}
        {record.finalizationState === 'partial' && <Body>Incomplete ride. Some sources are unavailable; see notices.</Body>}
        {!ready && <Body muted>{record.watchEnabled ? 'Exports are available when syncing finishes.' : 'Exports are available when saving finishes.'}</Body>}
        {recoveryAction && <Button secondary disabled={workout.busy} onPress={action(async () => {
          setRepairingId(record.id);
          try { await workouts.recover(record.id); setRefreshVersion(value => value + 1); }
          finally { setRepairingId(null); }
          })}>{repairingId === record.id ? 'Checking ride…' : recoveryAction === 'repair' ? 'Finish saving' : 'Retry'}</Button>}
        {warnings.length > 0 && <View style={{ gap: 6 }}><Button secondary onPress={() => setNoticeId(showNotices ? null : selectedId)}>{showNotices ? 'Hide notices' : `Recording notices · ${warnings.length}`}</Button>{showNotices && warnings.map(warning => <Body key={warning}>{warning}</Body>)}</View>}
        <View style={styles.row}>
          <Button disabled={workout.busy || !ready} onPress={action(async () => {
            const uri = await workouts.export(record.id, distanceSource);
            await exportWorkoutFile(uri, `power-log-${record.id}.${record.storage === 'browser' ? 'csv' : 'fit'}`);
          })}>{record.storage === 'browser' ? 'Export CSV' : 'Export FIT'}</Button>
          {record.storage !== 'browser' && <Button secondary disabled={workout.busy || !ready} onPress={action(async () => {
            const uri = await workouts.exportOriginal(record.id);
            await exportWorkoutFile(uri, `power-log-original-${record.id}.zip`);
          })}>Export ZIP</Button>}
        </View>
        {record.storage !== 'browser' && !record.example && <View style={{ gap: 8 }}>
          <Body muted>Export FIT, then select the file on Strava.</Body>
          <Button secondary disabled={workout.busy || !ready} onPress={action(() => Linking.openURL('https://www.strava.com/upload/select'))}>Open Strava upload</Button>
        </View>}
        {workoutCanDelete(record, workout.state) && <Button secondary disabled={workout.busy} onPress={() => askToRemove(record)}>Delete ride</Button>}
      </View>}
    </> : records.length ? <View style={{ borderTopWidth: 1, borderTopColor: colors.border }}>
      {records.map(record => {
        const secondary = [record.example ? 'Example' : null, record.indoor ? 'Indoor' : 'Outdoor', elapsedLabel(record), record.interrupted ? 'Recovered' : null].filter(Boolean).join(' · ');
        const unavailable = workout.busy || ['preparing', 'running', 'paused'].includes(record.phase);
        return <View key={record.id} style={{ borderBottomWidth: 1, borderBottomColor: colors.border, flexDirection: 'row', alignItems: 'center', gap: 8 }}>
          <Pressable accessibilityRole="button" accessibilityLabel={`Open ride, ${dateLabel(record.startedAt)}, ${secondary}`} accessibilityState={{ disabled: unavailable }} disabled={unavailable} onPress={() => { setCsvRide(null); setSelectedId(record.id); setEditing(false); }} style={({ pressed }) => ({ flex: 1, minHeight: 76, paddingVertical: 14, paddingHorizontal: 2, flexDirection: 'row', alignItems: 'center', gap: 12, opacity: unavailable ? 0.55 : pressed ? 0.7 : 1 })}>
          <View style={{ flex: 1, gap: 5 }}><Text style={{ color: colors.text, fontSize: 15, fontWeight: '500' }}>{dateLabel(record.startedAt)}</Text><Text style={{ color: colors.muted, fontSize: 13 }}>{secondary}</Text></View>
          {record.phase !== 'completed' && <Text style={{ color: record.phase === 'failed' ? colors.red : colors.muted, fontSize: 12 }}>{phaseLabel(record.phase)}</Text>}
          <Text style={{ color: colors.muted, fontSize: 22 }}>›</Text>
          </Pressable>
          {editing && workoutCanDelete(record, workout.state) && <Pressable accessibilityRole="button" accessibilityLabel={`Delete ride, ${dateLabel(record.startedAt)}`} disabled={workout.busy} accessibilityState={{ disabled: workout.busy }} onPress={() => askToRemove(record)} style={{ minHeight: 44, minWidth: 44, justifyContent: 'center', padding: 8, opacity: workout.busy ? 0.4 : 1 }}><Text style={{ color: colors.red, fontSize: 13 }}>Delete</Text></Pressable>}
        </View>;
      })}
      {workout.catalogHasMore && <Button secondary disabled={workout.catalogLoading} onPress={() => { void workout.loadMoreRecords().catch(() => {}); }}>{workout.catalogLoading ? 'Loading more rides…' : 'Load more rides'}</Button>}
    </View> : <Card style={{ padding: 20 }}><Body muted>{workout.catalogLoading ? 'Loading saved rides…' : workout.catalogError ? 'Saved ride catalog unavailable.' : 'No saved rides yet.'}</Body></Card>}
    <ModalDialog visible={removalTarget !== null} onClose={() => { if (!workout.busy) setRemovalTarget(null); }} closeLabel="Cancel ride deletion" testID="delete-ride-sheet" style={{ width: '100%', maxWidth: 600, alignSelf: 'center', backgroundColor: colors.surface, borderWidth: 1, borderColor: colors.border, borderTopLeftRadius: 16, borderTopRightRadius: 16 }}>
      <ScrollView testID="delete-ride-scroll" style={{ flexShrink: 1 }} contentContainerStyle={{ padding: 20, paddingBottom: Math.max(20, insets.bottom), gap: 12 }}>
      <Heading>Delete ride?</Heading>
      {removalTarget && <Body muted>{dateLabel(removalTarget.startedAt)}</Body>}
      <Body>This permanently removes the recording and charts from Power Log.{removalTarget?.watchEnabled ? ' The Watch copy will be removed when it connects.' : ''} Workouts already saved in Apple Health stay there.</Body>
      {workout.error && <Body>{workout.error}</Body>}
      <Button danger disabled={workout.busy || !removalTarget || !workoutCanDelete(records.find(record => record.id === removalTarget.id) ?? removalTarget, workout.state)} onPress={confirmRemoval}>{workout.busy ? 'Deleting…' : 'Delete ride'}</Button>
      <Button secondary disabled={workout.busy} onPress={() => setRemovalTarget(null)}>Keep ride</Button>
      </ScrollView>
    </ModalDialog>
  </View>;
}
