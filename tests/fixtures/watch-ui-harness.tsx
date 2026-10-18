import { useEffect } from 'react';
import { createRoot } from 'react-dom/client';
import { usePathname } from 'expo-router';
import { MonitorPreferencesProvider, useMonitorPreferences } from '../../src/services/monitor-preferences';
import { WorkoutProvider, useWorkout } from '../../src/services/workout-context';
import { RideActionBar, RideControlsProvider, RideStatus } from '../../src/features/workout/workout-screen';
import { SavedRides } from '../../src/features/history/saved-rides';
import { HistoryProvider } from '../../src/features/history/history-controller';
import { SettingsScreen } from '../../src/features/settings/settings-screen';
export { calls, rideId, seed, patchState, patchDetail, patchRecord } from './watch-ui-native';
export { setPlatform, failSettingsLoad } from './watch-ui-platform';
export let setDistance: ReturnType<typeof useMonitorPreferences>['setDistanceSource'];
export let failRide: (code: string) => Promise<void>;
function Evidence() {
  const settings = useMonitorPreferences();
  const workout = useWorkout();
  const run = workout.run;
  useEffect(() => {
    setDistance = settings.setDistanceSource;
    failRide = code =>
      run(async () => {
        throw Object.assign(new Error(`BridgeException: ${code} (at PowerLogBridge/PowerLogBridgeModule.swift:11)`), {
          code,
        });
      });
  }, [settings.setDistanceSource, run]);
  return (
    <output data-testid="watch-state">
      {JSON.stringify({
        phase: workout.state.phase,
        controlToken: workout.state.controlToken,
        error: workout.error,
        distanceSelectionError: null,
        distance: settings.preferences.distanceSource,
        settingsError: settings.error,
      })}
    </output>
  );
}
function History() {
  return <SavedRides detail={usePathname() === '/sessions/ride'} />;
}
export function mount(surface: 'ride' | 'history' | 'settings') {
  const container = document.createElement('div');
  document.body.append(container);
  createRoot(container).render(
    <MonitorPreferencesProvider>
      <WorkoutProvider>
        <Evidence />
        {surface === 'ride' ? (
          <RideControlsProvider>
            <RideActionBar />
            <RideStatus connection={null} />
          </RideControlsProvider>
        ) : surface === 'settings' ? (
          <SettingsScreen />
        ) : (
          <HistoryProvider>
            <History />
          </HistoryProvider>
        )}
      </WorkoutProvider>
    </MonitorPreferencesProvider>,
  );
}
