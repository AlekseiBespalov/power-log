import { createContext, useCallback, useContext, useEffect, useMemo, useReducer, useState, useSyncExternalStore, type ReactNode } from 'react';
import type { Device, NativeState } from '../core/types';
import type { TelemetryDisplay } from '../core/telemetry-display';
import type { TelemetryAdapter } from './adapter';
import { deviceAdapter } from './device';
import { initialSessionErrors, sessionErrorMessage, sessionErrorReducer } from './session-errors';
import { workouts } from './workouts';
import { workoutInProgress } from '../core/workouts';
import { TelemetryMonitor } from '../core/monitor-data';
import { useMonitorPreferences } from './monitor-preferences';
import { appIsForeground, subscribeAppVisibility } from './app-visibility';
import { SessionPresentation } from './session-presentation';

interface SessionContextValue {
  adapter: TelemetryAdapter; state: NativeState; devices: Device[]; display: TelemetryDisplay; faultCode: number | null;
  error: string | null; busy: boolean; hz: 2 | 4 | 8;
  monitor: TelemetryMonitor;
  run: (action: () => Promise<unknown>) => Promise<void>;
  clearError: () => void; scan: () => Promise<void>;
}
const adapter = deviceAdapter;
const SessionContext = createContext<SessionContextValue | null>(null);
export function SessionProvider({ children }: { children: ReactNode }) {
  const [monitor] = useState(() => new TelemetryMonitor(`foreground:${adapter.kind}`, true));
  const [presentation] = useState(() => new SessionPresentation());
  const { state, display, faultCode } = useSyncExternalStore(presentation.subscribe, presentation.getSnapshot, presentation.getSnapshot);
  const [devices, setDevices] = useState<Device[]>([]);
  const settings = useMonitorPreferences();
  const hz = settings.preferences.sampleHz;
  const [busy, setBusy] = useState(false);
  const [errors, dispatchError] = useReducer(sessionErrorReducer, initialSessionErrors);
  const error = sessionErrorMessage(errors, {
    deferNative: state.recoverableConnectionError === true && display === 'held',
  });
  useEffect(() => {
    let rideActive = false, rideEvents = 0;
    const receiveRide = (value: Awaited<ReturnType<typeof workouts.getState>>) => {
      const next = workoutInProgress(value.phase);
      if (rideActive && !next) monitor.beginSession();
      rideActive = next;
    };
    const unsubscribeRide = workouts.subscribe(value => { rideEvents++; receiveRide(value); });
    void workouts.getState().then(value => { if (!rideEvents) receiveRide(value); }).catch(() => {});
    let disposed = false; let stateEvents = 0; let previousStatus: NativeState['status'] = 'idle';
    const receiveState = (next: NativeState) => {
      if (disposed) return;
      if (adapter.kind !== 'native' && next.status === 'connected' && previousStatus !== 'connected' && previousStatus !== 'reconnecting') monitor.beginSession();
      previousStatus = next.status;
      presentation.receiveState(next);
      dispatchError({ type: 'native', error: next.error });
    };
    const unsubscribe = adapter.subscribe({
      state: next => { stateEvents++; receiveState(next); },
      device: device => { if (!disposed) setDevices(previous => [...previous.filter(item => item.id !== device.id), device]); },
      sample: sample => {
        if (disposed) return;
        if (adapter.kind !== 'native' && !rideActive) {
          monitor.append(sample);
        }
        presentation.receiveSample(sample);
      },
    });
    let refreshGeneration = 0;
    const visibility = (active: boolean) => {
      presentation.setActive(active);
      const generation = ++refreshGeneration, events = stateEvents;
      if (active) void adapter.getState().then(next => { if (!disposed && generation === refreshGeneration && events === stateEvents) receiveState(next); })
        .catch(cause => { if (!disposed && generation === refreshGeneration && events === stateEvents) dispatchError({ type: 'native', error: cause instanceof Error ? cause.message : String(cause) }); });
    };
    visibility(appIsForeground());
    const unsubscribeVisibility = subscribeAppVisibility(visibility);
    return () => { disposed = true; presentation.setActive(false); unsubscribeVisibility(); unsubscribe(); unsubscribeRide(); };
  }, [monitor, presentation]);
  const run = useCallback(async (action: () => Promise<unknown>) => {
    setBusy(true); dispatchError({ type: 'operation', error: null });
    try { await action(); } catch (cause) { dispatchError({ type: 'operation', error: cause instanceof Error ? cause.message : String(cause) }); }
    finally { setBusy(false); }
  }, []);
  const value = useMemo<SessionContextValue>(() => ({
    adapter, state, devices, display, faultCode, error, busy, hz, monitor, run,
    clearError: () => dispatchError({ type: 'dismiss' }),
    scan: async () => { setDevices([]); await adapter.startScan(); },
  }), [state, devices, display, faultCode, error, busy, hz, monitor, run]);
  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>;
}
export function useSession() { const value = useContext(SessionContext); if (!value) throw new Error('Missing SessionProvider'); return value; }
