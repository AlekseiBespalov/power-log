import { createElement } from 'react';
import { View } from 'react-native';
export { useFocusEffect, useIsFocused, useSafeAreaInsets, useSession, deviceAdapter, useMonitorPreferences, RoutePreview,
  CaptureRideDetails, CsvRideDetails, exportWorkoutFile, importText, exportText, deleteCapture, exportCapture, workoutMonitorSource } from './history-deletion-platform';

/** A fixed chart landmark; the History/Metric/Text layout above it uses real RN Web. */
export function MonitorPanel() {
  return createElement(View, { testID: 'selected-ride-chart', style: { height: 160, backgroundColor: '#15202a' } });
}
