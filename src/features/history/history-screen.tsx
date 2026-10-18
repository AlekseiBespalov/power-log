import { Platform, RefreshControl } from 'react-native';
import { colors } from '../../components/ui';
import { HistoryRefresh } from './history-refresh';
import { useHistory } from './history-controller';
import { useIsFocused } from 'expo-router';
import { AppShell } from '../../components/app-shell';
import { SavedRides } from './saved-rides';

export function HistoryScreen() {
  const { pending, refresh, csv } = useHistory();
  const refreshing = pending('catalog');
  return (
    <HistoryRefresh enabled={!csv} refreshing={refreshing} onRefresh={refresh}>
      <AppShell
        navigationRoute="sessions"
        refreshControl={
          Platform.OS !== 'web' && !csv ? (
            <RefreshControl
              refreshing={refreshing}
              onRefresh={refresh}
              tintColor={colors.accent}
              colors={[colors.accent]}
            />
          ) : undefined
        }
      >
        <SavedRides detail={false} />
      </AppShell>
    </HistoryRefresh>
  );
}
export function HistoryRideScreen() {
  const focused = useIsFocused();
  return <AppShell navigationRoute="sessions">{focused && <SavedRides detail />}</AppShell>;
}
