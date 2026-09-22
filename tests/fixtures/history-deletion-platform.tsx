import { createElement, useEffect, useSyncExternalStore, type ReactNode } from 'react';
import { MonitorPreferencesController } from '../../src/services/monitor-preferences-controller';
import type { DistanceSource } from '../../src/core/distance';
import { defaultMonitorPreferences, type MonitorSource } from '../../src/core/monitor';

type Props = { children?: ReactNode; testID?: string; accessibilityRole?: string; accessibilityLabel?: string;
  disabled?: boolean; onPress?: () => void; visible?: boolean; style?: unknown };
function element(tag: string, props: Props) {
  return createElement(tag, { 'data-testid': props.testID, role: props.accessibilityRole, 'aria-label': props.accessibilityLabel,
    disabled: props.disabled, onClick: props.onPress }, props.children);
}
export const View = (props: Props) => element('div', props);
export const ScrollView = View;
export const Text = (props: Props) => element('span', props);
export const Pressable = (props: Props) => element('button', props);
export const KeyboardAvoidingView = View;
export const Modal = (props: Props) => props.visible ? element('div', props) : null;
export const StyleSheet = { create: <T,>(value: T) => value, absoluteFill: {} };
export const Platform = { OS: 'web' };
export const openedURLs: string[] = [];
export const Linking = { openURL: async (url: string) => { openedURLs.push(url); } };
export const AppState = { currentState: 'active', addEventListener: (_name: string, _listener: (state: string) => void) => ({ remove() {} }) };
export const useWindowDimensions = () => ({ width: 390, height: 900, fontScale: 1 });
export const useSafeAreaInsets = () => ({ top: 0, bottom: 0, left: 0, right: 0 });
export function useFocusEffect(effect: () => void | (() => void)) { useEffect(effect, [effect]); }
export const useIsFocused = () => true;
const session = { adapter: {}, state: { recordingId: null } };
export const deviceAdapter = session.adapter;
export const useSession = () => session;
const preferences = new MonitorPreferencesController({ load: async () => ({ preferences: defaultMonitorPreferences() }), save: async () => {} });
void preferences.hydrate();
export function setGlobalDistanceSource(source: DistanceSource) { preferences.setDistanceSource(source); }
export function useMonitorPreferences() {
  const snapshot = useSyncExternalStore(preferences.subscribe, preferences.snapshot, preferences.snapshot);
  return { ...snapshot, setWorkoutOptions: preferences.setWorkoutOptions, setDistanceSource: preferences.setDistanceSource };
}
export const RoutePreview = () => null;
export const CaptureRideDetails = () => null;
export const CsvRideDetails = () => null;
export const sharedFiles: { uri: string; name: string }[] = [];
export const exportWorkoutFile = async (uri: string, name: string) => { sharedFiles.push({ uri, name }); };
export const importText = async () => null;
export const exportText = async () => {};
export const deleteCapture = async () => {};
export const exportCapture = async () => {};
export const workoutMonitorSource = (id: string) => ({ key: `workout:${id}`, id });
export const monitorMounts: string[] = [], monitorUnmounts: string[] = [];
/** Visual stand-in proves production SavedWorkouts detaches its chart subtree. */
export function MonitorPanel({ source }: { source: MonitorSource }) {
  useEffect(() => { monitorMounts.push(source.key); return () => { monitorUnmounts.push(source.key); }; }, [source.key]);
  return createElement('div', { 'data-testid': 'selected-ride-chart', 'data-source': source.key }, 'Chart for ' + source.key);
}
