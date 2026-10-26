import { useState } from 'react';
import { Linking, ScrollView, Text, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import {
  Body,
  Button,
  Card,
  colors,
  formatDuration,
  Heading,
  Metric,
  styles,
  type,
  Pressable,
  MIN_TOUCH_TARGET,
} from '../../components/ui';
import { ModalDialog } from '../../components/modal-dialog';
import { RoutePreview } from '../../components/route-preview';
import {
  healthProviderLabel,
  workoutHealthResultPresentation,
  workoutHistoryStatusPresentation,
  workoutPhysiologyLabel,
} from '../../core/workout-presentation';
import { workoutCanDelete, type WorkoutMetadata } from '../../core/workouts';
import { workouts } from '../../services/workouts';
import { exportRecording, exportRide } from '../../services/ride-export';
import { CsvRideDetails } from './csv-ride-details';
import { MonitorPanel } from '../monitor/monitor-panel';
import { metricById, formatMetric } from '../../core/monitor';

import { recordKey, useHistory } from './history-controller';
const number = (value: number | null | undefined, decimals = 0) =>
  value == null || !Number.isFinite(value) ? '—' : value.toFixed(decimals);
const dateLabel = (date: string) => {
  const value = new Date(date);
  return `${value.toLocaleDateString(undefined, { weekday: 'short', month: 'short', day: 'numeric' })} · ${value.toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit', hour12: false })}`;
};
function recordingDevice(record: WorkoutMetadata) {
  if (record.storage === 'browser') return 'Browser';
  if (record.watchEnabled) return 'Watch + iPhone';
  return record.healthProvider === 'healthConnect' ? 'Android' : 'iPhone';
}
const phases: Record<string, string> = {
  preparing: 'Preparing',
  running: 'Recording',
  paused: 'Paused',
  interrupted: 'Needs attention',
  finishing: 'Ended',
  failed: 'Failed',
};
const phaseLabel = (phase: string) => phases[phase] ?? phase;
function elapsedLabel(record: WorkoutMetadata) {
  return formatDuration(record.elapsedSeconds);
}
function HealthResult({ record }: { record: WorkoutMetadata }) {
  const health = workoutHealthResultPresentation(record);
  return (
    <View testID="ride-health-sync" style={{ gap: 6 }}>
      {record.storage !== 'browser' && (
        <>
          <Body muted>{health.label}</Body>
          {record.watchEnabled &&
            record.saveToHealth !== false &&
            (record.healthProvider ?? 'appleHealth') === 'appleHealth' && (
              <Body muted>Detailed Health results are on the Watch.</Body>
            )}
          {health.details.map((detail, index) => (
            <Body key={index} muted>
              {detail}
            </Body>
          ))}
        </>
      )}
    </View>
  );
}

export function SavedRides({ detail }: { detail: boolean }) {
  const {
    workout,
    archiveState,
    archiveActions,
    pending,
    preferences,
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
    askToRemove,
    navigate,
    refreshHistory,
    refresh,
    openCsv,
  } = useHistory();
  const history = useHistory();
  const selectedId = detail ? history.selectedId : null;
  const csv = detail ? null : history.csv;
  const selection = detail ? history.selection : csv;
  const speedMetric = metricById('speedMps', preferences.speedUnit)!;
  const distanceSource = record?.distanceSource ?? 'auto';
  const [menuOpen, setMenuOpen] = useState(false);

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
        {detail || selection !== null ? (
          <Pressable
            accessibilityRole="button"
            onPress={() => {
              navigate(null);
              resource.clearError();
            }}
            style={{ minHeight: 44, justifyContent: 'center' }}
          >
            <Text style={{ color: colors.accent }}>‹ History</Text>
          </Pressable>
        ) : (
          <Heading>History</Heading>
        )}
        <View style={[styles.row, { maxWidth: '100%' }]}>
          {!detail && selection === null && records.length > 0 && (
            <Button secondary onPress={() => setEditing(value => !value)}>
              {editing ? 'Done' : 'Edit'}
            </Button>
          )}
          <Pressable
            accessibilityRole="button"
            accessibilityLabel="History actions"
            accessibilityState={{ expanded: menuOpen }}
            onPress={() => setMenuOpen(value => !value)}
            style={{
              minHeight: MIN_TOUCH_TARGET,
              minWidth: MIN_TOUCH_TARGET,
              alignItems: 'center',
              justifyContent: 'center',
            }}
          >
            <Text style={{ color: colors.text, fontSize: type.title }}>•••</Text>
          </Pressable>
        </View>
      </View>
      {menuOpen && (
        <View testID="history-actions-menu" style={styles.row}>
          <Button secondary disabled={pending('catalog')} onPress={refresh}>
            Refresh
          </Button>
          <Button secondary disabled={pending('csv')} onPress={openCsv}>
            Open CSV
          </Button>
        </View>
      )}
      {!detail && selection === null && (
        <Body muted>
          {pending('catalog') ? 'Refreshing…' : 'Pull to refresh'} · {records.length}{' '}
          {records.length === 1 ? 'ride' : 'rides'}
        </Body>
      )}
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
      ) : detail && !selectedId ? (
        <Body muted>Choose a ride from History.</Body>
      ) : selectedId ? (
        <>
          {!record && !error && !deleted && <Body muted>Loading ride details…</Body>}
          {record && (
            <View style={{ gap: 14 }}>
              <View style={{ gap: 4 }}>
                <Heading>{dateLabel(record.startedAt)}</Heading>
                <Body muted>
                  {record.example ? 'Example' : 'Recorded'} · {record.indoor ? 'Indoor' : 'Outdoor'} ·{' '}
                  {recordingDevice(record)}
                  {summary && summary.lapCount > 0
                    ? ` · ${summary.lapCount} ${summary.lapCount === 1 ? 'lap' : 'laps'}`
                    : ''}
                  {record.phase !== 'completed' ? ` · ${phaseLabel(record.phase)}` : ''}
                  {record.interrupted ? ' · Interrupted' : ''}
                </Body>
              </View>
              <View style={styles.row}>
                <Button
                  disabled={pending(recordKey(record.id)) || !ready}
                  onPress={action(recordKey(record.id), () =>
                    exportRide('fit', record.id, distanceSource, preferences.fitPowerSource),
                  )}
                >
                  Export FIT
                </Button>
                <Button
                  secondary
                  disabled={pending(recordKey(record.id)) || !ready}
                  onPress={action(recordKey(record.id), () => exportRide('csv', record.id, distanceSource))}
                >
                  Export CSV
                </Button>
                <Button
                  secondary
                  disabled={pending(recordKey(record.id)) || !ready}
                  onPress={action(recordKey(record.id), () => exportRide('zip', record.id))}
                >
                  Export ZIP
                </Button>
              </View>
              <Heading level={2}>Ride summary</Heading>
              {summary ? (
                <View testID="ride-summary" style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 18 }}>
                  <Metric label="Ride time" value={formatDuration(summary.timerSeconds)} unit="" />
                  {(summary.distance || summary.distanceMeters != null) && (
                    <Metric
                      label="Distance"
                      value={number(summary.distanceMeters == null ? null : summary.distanceMeters / 1000, 2)}
                      unit="km"
                    />
                  )}
                  {(summary.distance || summary.averageSpeedMps != null) && (
                    <Metric
                      label="Avg speed"
                      value={formatMetric(speedMetric, summary.averageSpeedMps)}
                      unit={speedMetric.unit}
                    />
                  )}
                  {summary.batteryConsumption && (
                    <>
                      <Metric
                        label={`Energy used (${summary.batteryConsumption.source === 'controller' ? 'controller' : 'calc'})`}
                        value={number(summary.batteryConsumption.energyWh, 2)}
                        unit="Wh"
                      />
                      <Metric
                        label={`Charge used (${summary.batteryConsumption.source === 'controller' ? 'controller' : 'calc'})`}
                        value={number(summary.batteryConsumption.chargeAh, 2)}
                        unit="Ah"
                      />
                    </>
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
                      label={workoutPhysiologyLabel(record, 'Avg heart rate')}
                      value={number(summary.averageHeartRateBpm)}
                      unit="bpm"
                      color={colors.red}
                    />
                  )}
                  {summary.ascentMeters != null && (
                    <Metric label="Ascent" value={number(summary.ascentMeters)} unit="m" />
                  )}
                  {summary.activeEnergyKcal != null && (
                    <Metric
                      label={workoutPhysiologyLabel(record, 'Active energy')}
                      value={number(summary.activeEnergyKcal)}
                      unit="kcal"
                    />
                  )}
                </View>
              ) : (
                <Body muted>
                  {loading ? 'Preparing summary… Charts are available below.' : 'Ride summary unavailable.'}
                </Body>
              )}
              {(record.recordGPS ?? !record.indoor) && summary && <RoutePreview points={summary.routePreview} />}
              <Heading level={2}>Chart inspection</Heading>
              <MonitorPanel source={monitorSource} embedded />
              <HealthResult record={record} />
              {!ready && (
                <Body muted>
                  {record.phase === 'failed'
                    ? 'This ride did not complete. Use Delete ride to remove it from History.'
                    : 'Finish the local ride to export it.'}
                </Body>
              )}
              {record.phase === 'interrupted' && record.id === workout.state.id && (
                <View style={styles.row}>
                  <Button
                    disabled={workout.busy}
                    onPress={action(recordKey(record.id), async () => {
                      await workouts.stop(record.id);
                      await workout.refresh();
                      setRefreshVersion(value => value + 1);
                    })}
                  >
                    Finish saved portion
                  </Button>
                  <Button danger disabled={workout.busy} onPress={() => askToRemove(record)}>
                    Discard ride
                  </Button>
                </View>
              )}
              {record.phase === 'completed' && record.storage !== 'browser' && workouts.refreshHealth && (
                <Button
                  secondary
                  disabled={workout.busy || pending(recordKey(record.id)) || repairingId === record.id}
                  onPress={() =>
                    void workout.run(async ({ current, signal }) => {
                      const clear = () => setRepairingId(value => (value === record.id ? null : value));
                      setRepairingId(record.id);
                      signal.addEventListener('abort', clear, { once: true });
                      try {
                        if (record.healthRetryAvailable && workouts.retryHealthSave)
                          await workouts.retryHealthSave(record.id);
                        else await workouts.refreshHealth!(record.id);
                        if (current()) setRefreshVersion(value => value + 1);
                      } finally {
                        signal.removeEventListener('abort', clear);
                        if (current()) clear();
                      }
                    })
                  }
                >
                  {repairingId === record.id
                    ? 'Refreshing Health…'
                    : record.healthRetryAvailable && workouts.retryHealthSave
                      ? 'Retry Health save'
                      : 'Refresh Health data'}
                </Button>
              )}
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
                  danger
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
            const status = workoutHistoryStatusPresentation(record);
            const secondary = [
              record.example ? 'Example' : 'Recorded',
              record.indoor ? 'Indoor' : 'Outdoor',
              elapsedLabel(record),
              record.interrupted ? 'Interrupted' : null,
            ]
              .filter(Boolean)
              .join(' · ');
            const unavailable = ['preparing', 'running', 'paused'].includes(record.phase);
            return (
              <View key={record.id} style={{ borderBottomWidth: 1, borderBottomColor: colors.border }}>
                <View style={{ flexDirection: 'row', alignItems: 'center', gap: 8 }}>
                  <Pressable
                    accessibilityRole="button"
                    accessibilityLabel={[`Open ride, ${dateLabel(record.startedAt)}, ${secondary}`, status]
                      .filter(Boolean)
                      .join(', ')}
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
                      <Text style={{ color: colors.text, fontSize: type.control, fontWeight: '500' }}>
                        {dateLabel(record.startedAt)}
                      </Text>
                      <Text style={{ color: colors.muted, fontSize: type.caption }}>{secondary}</Text>
                      {status && (
                        <Text
                          testID="ride-health-sync-summary"
                          accessible={false}
                          numberOfLines={1}
                          style={{ color: colors.muted, fontSize: type.caption }}
                        >
                          {status}
                        </Text>
                      )}
                    </View>
                    {record.phase !== 'completed' && (
                      <Text
                        style={{ color: record.phase === 'failed' ? colors.red : colors.muted, fontSize: type.caption }}
                      >
                        {phaseLabel(record.phase)}
                      </Text>
                    )}
                    <Text style={{ color: colors.muted, fontSize: type.title }}>›</Text>
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
                      <Text style={{ color: colors.red, fontSize: type.caption }}>Delete</Text>
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
    </View>
  );
}

export function HistoryDeletionDialog() {
  const { workout, archiveState, records, removalTarget, setRemovalTarget, removalKey, removing, confirmRemoval } =
    useHistory();
  const insets = useSafeAreaInsets();
  return (
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
          {removalTarget?.storage !== 'browser'
            ? ` Workouts already saved in ${healthProviderLabel(removalTarget?.healthProvider ?? 'appleHealth')} stay there.`
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
            (!(removalTarget.phase === 'interrupted' && removalTarget.id === workout.state.id) &&
              !workoutCanDelete(records.find(record => record.id === removalTarget.id) ?? removalTarget, workout.state))
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
  );
}
