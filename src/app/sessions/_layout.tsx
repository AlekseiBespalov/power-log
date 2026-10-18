import { Stack } from 'expo-router';
import { colors } from '../../components/ui';
import { HistoryDeletionDialog } from '../../features/history/saved-rides';
import { HistoryProvider } from '../../features/history/history-controller';

export const unstable_settings = { initialRouteName: 'index' };

export default function HistoryLayout() {
  return (
    <HistoryProvider>
      <Stack
        screenOptions={{ headerShown: false, gestureEnabled: true, contentStyle: { backgroundColor: colors.bg } }}
      />
      <HistoryDeletionDialog />
    </HistoryProvider>
  );
}
