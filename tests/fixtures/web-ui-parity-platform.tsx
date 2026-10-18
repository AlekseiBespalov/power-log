import { defaultMonitorPreferences } from '../../src/core/monitor';
import { useEffect, useSyncExternalStore, type ReactNode } from 'react';
import { Platform, View } from 'react-native';
import { unavailableWorkoutState, type WorkoutState, type WorkoutPhase } from '../../src/core/workouts';
import type { NativeState } from '../../src/core/types';
import type { TelemetryDisplay } from '../../src/core/telemetry-display';
import { WorkoutActions, type WorkoutActionKind } from '../../src/services/workout-actions';

export const useSafeAreaInsets = () => ({ top: 0, bottom: 0, left: 0, right: 0 });
export const ModalDialog = ({
  children,
  visible,
  testID,
}: {
  children: ReactNode;
  visible: boolean;
  testID: string;
}) => (visible ? <View testID={testID}>{children}</View> : null);
export const Link = ({ children }: { children: ReactNode }) => <>{children}</>;
export const SortableMetrics = ({ children, header }: { children: ReactNode; header?: ReactNode }) => (
  <>
    {header}
    {children}
  </>
);
export const Icon = () => null;
export function useFocusEffect(effect: () => void | (() => void)) {
  useEffect(effect, [effect]);
}
const listeners = new Set<() => void>();
let workout = {
  state: { ...unavailableWorkoutState, supported: true },
  options: { indoor: false, useWatch: false, saveToHealth: false, recordGPS: false },
  optionsReady: true,
  busy: false,
  error: null as string | null,
  permissions: null,
  permissionsError: null,
  recoveryAvailable: false,
  run: (operation: () => Promise<unknown>, kind?: WorkoutActionKind) => actions.run(operation, async () => {}, kind),
  clearError: () => actions.clearError(),
};
function notify() {
  for (const listener of listeners) listener();
}
const actions = new WorkoutActions(next => {
  workout = { ...workout, ...next };
  notify();
});
export function setRidePhase(phase: WorkoutPhase) {
  workout = { ...workout, state: { ...workout.state, phase, id: phase === 'idle' ? null : 'synthetic-ride' } };
  notify();
}
export const recoveries: string[] = [];
export const stops: string[] = [];
export const workouts = {
  start: async () => setRidePhase('running'),
  pause: async () => setRidePhase('paused'),
  resume: async () => setRidePhase('running'),
  lap: async () => {},
  recover: async (id: string) => {
    recoveries.push(id);
  },
  stop: async (id: string) => {
    stops.push(id);
  },
};
export const haptics = { selection: () => {}, success: () => {}, warning: () => {}, impact: () => {} };
export function useWorkout() {
  return useSyncExternalStore(
    listener => {
      listeners.add(listener);
      return () => {
        listeners.delete(listener);
      };
    },
    () => workout,
  );
}
export const useWorkoutIdentity = () => ({ phase: useWorkout().state.phase });
export const disconnects: string[] = [];
let session = {
  state: { status: 'connecting', deviceId: 'synthetic-bike', deviceName: 'Synthetic bike' } as NativeState,
  display: 'unavailable' as TelemetryDisplay,
  adapter: {
    kind: 'web',
    disconnect: async () => {
      disconnects.push('disconnect');
    },
    startScan: async () => {},
    stopScan: async () => {},
  },
  busy: false,
  devices: [],
  error: null,
  hz: 2,
  scan: async () => {},
  clearError: () => {},
  run: async (operation: () => Promise<unknown>) => {
    await operation();
  },
};
export function setPlatform(os: 'web' | 'android' | 'ios') {
  Object.defineProperty(Platform, 'OS', { configurable: true, value: os });
  session = { ...session, adapter: { ...session.adapter, kind: os === 'web' ? 'web' : 'native' } };
  workout = {
    ...workout,
    state: {
      ...workout.state,
      capabilities: {
        ...unavailableWorkoutState.capabilities,
        phoneWorkout: true,
        gps: os !== 'web',
        foregroundOnly: os === 'web',
        healthProvider: os === 'ios' ? 'appleHealth' : os === 'android' ? 'healthConnect' : null,
      },
    },
  };
  notify();
}
export function setConnection(status: NativeState['status']) {
  session = { ...session, state: { ...session.state, status } };
  for (const listener of listeners) listener();
}
export const useSession = () =>
  useSyncExternalStore(
    listener => {
      listeners.add(listener);
      return () => {
        listeners.delete(listener);
      };
    },
    () => session,
  );

export function setWorkoutHealth(patch: Partial<WorkoutState>) {
  workout = { ...workout, state: { ...workout.state, ...patch } };
  notify();
}

export const useMonitorPreferences = () => ({
  preferences: defaultMonitorPreferences(),
  prepareRecording: async () => defaultMonitorPreferences(),
});
