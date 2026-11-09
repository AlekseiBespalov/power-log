import { useLocalSearchParams } from 'expo-router';
import { useEffect, useState } from 'react';
import { Platform, RefreshControl, Text, View } from 'react-native';
import { AppShell } from '../../components/app-shell';
import { Button, colors, Heading, MIN_TOUCH_TARGET, Pressable, styles, type } from '../../components/ui';
import { useUiEnv } from '../../components/use-screen-model';
import { ChartsPanel } from '../charts/charts-panel';
import { ImportedRide } from '../imports/imported-ride';
import { HistoryList } from './history-list';
import type { HistoryListModel, HistoryListState } from './history-list-model';
import { HistoryRefresh } from './history-refresh';
import { useHistoryList } from './history-stack';
import { RideDetail } from './ride-detail';

export function HistoryScreen() {
  const [rides, list] = useHistoryList();
  const [menuOpen, setMenuOpen] = useState(false);
  const refresh = () => list.refresh();
  const imported = rides.imported;
  const opened = imported !== null;
  return (
    <HistoryRefresh enabled={!opened} refreshing={rides.refreshing} onRefresh={refresh}>
      <AppShell
        navigationRoute="sessions"
        refreshControl={
          Platform.OS !== 'web' && !opened ? (
            <RefreshControl
              refreshing={rides.refreshing}
              onRefresh={refresh}
              tintColor={colors.accent}
              colors={[colors.accent]}
            />
          ) : undefined
        }
      >
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
            {opened ? (
              <Pressable
                accessibilityRole="button"
                onPress={() => list.closeImport()}
                style={{ minHeight: 44, justifyContent: 'center' }}
              >
                <Text style={{ color: colors.accent }}>‹ History</Text>
              </Pressable>
            ) : (
              <Heading>History</Heading>
            )}
            <View style={[styles.row, { maxWidth: '100%' }]}>
              {!opened && rides.rows.length > 0 && (
                <Button secondary onPress={() => list.toggleEdit()}>
                  {rides.editing ? 'Done' : 'Edit'}
                </Button>
              )}
              <HistoryActionsButton open={menuOpen} onToggle={() => setMenuOpen(value => !value)} />
            </View>
          </View>
          {menuOpen && <HistoryActionsMenu state={rides} list={list} />}
          {imported ? (
            <ImportedRide key={imported.importId} imported={imported} list={list} />
          ) : (
            <HistoryList state={rides} model={list} />
          )}
        </View>
      </AppShell>
    </HistoryRefresh>
  );
}
export function HistoryRideScreen() {
  const params = useLocalSearchParams<{ id?: string | string[] }>();
  const rideId = typeof params.id === 'string' ? params.id : '';
  const [rides, list] = useHistoryList();
  const { ui } = useUiEnv();
  const [menuOpen, setMenuOpen] = useState(false);
  const importId = rides.imported?.importId ?? null;
  const [shownImport] = useState(importId);
  useEffect(() => {
    if (importId !== null && importId !== shownImport) ui.dismissTo('/sessions');
  }, [importId, shownImport, ui]);
  return (
    <AppShell navigationRoute="sessions">
      <RideDetail
        key={rideId}
        rideId={rideId}
        actions={<HistoryActionsButton open={menuOpen} onToggle={() => setMenuOpen(value => !value)} />}
        menu={menuOpen && <HistoryActionsMenu state={rides} list={list} />}
        chart={<ChartsPanel input={{ source: { kind: 'ride', rideId }, scope: 'history' }} embedded />}
      />
    </AppShell>
  );
}

function HistoryActionsButton({ open, onToggle }: { open: boolean; onToggle: () => void }) {
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel="History actions"
      accessibilityState={{ expanded: open }}
      onPress={onToggle}
      style={{
        minHeight: MIN_TOUCH_TARGET,
        minWidth: MIN_TOUCH_TARGET,
        alignItems: 'center',
        justifyContent: 'center',
      }}
    >
      <Text style={{ color: colors.text, fontSize: type.title }}>•••</Text>
    </Pressable>
  );
}

function HistoryActionsMenu({ state, list }: { state: HistoryListState; list: HistoryListModel }) {
  return (
    <View testID="history-actions-menu" style={styles.row}>
      <Button secondary disabled={state.refreshing} onPress={() => list.refresh()}>
        Refresh
      </Button>
      <Button secondary disabled={!state.openCsv.enabled || state.openCsv.busy} onPress={() => list.openCsv()}>
        Open CSV
      </Button>
    </View>
  );
}
