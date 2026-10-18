import { createElement } from 'react';
import { View } from 'react-native';
export {
  useFocusEffect,
  useRouter,
  usePathname,
  useGlobalSearchParams,
  useIsFocused,
  useSafeAreaInsets,
  useSession,
  deviceAdapter,
  useMonitorPreferences,
  RoutePreview,
  CaptureRideDetails,
  importRecording,
  deleteCapture,
  exportCapture,
  workoutMonitorSource,
} from './history-deletion-platform';

export function MonitorPanel() {
  return createElement(View, { testID: 'selected-ride-chart', style: { height: 160, backgroundColor: '#15202a' } });
}
