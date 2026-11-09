import { Stack } from 'expo-router';
import { colors } from '../../components/ui';
import { HistoryStack } from '../../features/history/history-stack';

export const unstable_settings = { initialRouteName: 'index' };

export default function HistoryLayout() {
  return (
    <HistoryStack>
      <Stack
        screenOptions={{ headerShown: false, gestureEnabled: true, contentStyle: { backgroundColor: colors.bg } }}
      />
    </HistoryStack>
  );
}
