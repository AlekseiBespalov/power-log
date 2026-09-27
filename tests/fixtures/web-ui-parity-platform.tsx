import { useEffect, useSyncExternalStore, type ReactNode } from 'react';
import { Platform } from 'react-native';
import type { NativeState } from '../../src/core/types';
import type { TelemetryDisplay } from '../../src/core/telemetry-display';

export const useSafeAreaInsets = () => ({ top: 0, bottom: 0, left: 0, right: 0 });
export const ModalDialog = ({ children }: { children: ReactNode }) => <>{children}</>;
export const SortableMetrics = ({ children, header }: { children: ReactNode; header?: ReactNode }) => (
  <>
    {header}
    {children}
  </>
);
export const Icon = () => null;
export const ConnectionHealth = () => null;
export function useFocusEffect(effect: () => void | (() => void)) {
  useEffect(effect, [effect]);
}
export const useWorkoutIdentity = () => ({ phase: 'idle' });
const listeners = new Set<() => void>();
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
  for (const listener of listeners) listener();
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
