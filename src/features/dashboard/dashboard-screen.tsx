import { useMemo } from 'react';
import { Platform, Text, useWindowDimensions } from 'react-native';
import { workoutInProgress } from '../../core/workouts';
import { useSession } from '../../services/session-context';
import { useWorkoutIdentity } from '../../services/workout-context';
import { useMonitorPreferences } from '../../services/monitor-preferences';
import { AppShell } from '../../components/app-shell';
import { colors } from '../../components/ui';
import { MonitorPanel } from '../monitor/monitor-panel';
import { nativeMonitorSource } from '../../services/monitor-source';
import { workoutMonitorSource } from '../../services/workout-monitor-source';
import { RideActionBar, RideControlsProvider, RideStatus } from '../workout/workout-screen';
import { ConnectionSettings } from './connection-settings';

export function DashboardScreen() {
  const session = useSession(), workout = useWorkoutIdentity();
  const { adapter } = session;
  const { preferences } = useMonitorPreferences();
  const distanceSource = preferences.distanceSource;
  const available = session.display !== 'unavailable';
  const workoutId = workoutInProgress(workout.phase) ? workout.id : undefined;
  const monitorSource = useMemo(() => workoutId ? workoutMonitorSource(workoutId, true, distanceSource) : adapter.kind === 'native' ? nativeMonitorSource('live', undefined, true, distanceSource) : session.monitor, [adapter.kind, workoutId, session.monitor, distanceSource]);
  const { width } = useWindowDimensions();
  const bottomBar = !(Platform.OS === 'web' && width >= 1100);
  return <RideControlsProvider>
    <AppShell footer={bottomBar ? <RideActionBar /> : undefined}>
      <RideStatus connection={<ConnectionSettings />} />
      {!bottomBar && <RideActionBar inline />}
      <MonitorPanel source={monitorSource} bikeAvailable={available} />
      {available && session.faultCode !== null && session.faultCode !== 0 && <Text accessibilityRole="alert" style={{ color: colors.red }}>Controller fault: {session.faultCode}</Text>}
    </AppShell>
  </RideControlsProvider>;
}
