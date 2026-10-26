import type { ReactNode } from 'react';
import { defaultMonitorPreferences, type MonitorPreferences } from '../../src/core/monitor';

let preferences: MonitorPreferences = defaultMonitorPreferences();
export function setPanelPreferences(next: MonitorPreferences) {
  preferences = next;
}
export function useMonitorPreferences() {
  return {
    preferences,
    updateView: () => {},
    selectView: () => {},
    resetView: () => {},
    setHistoryRange: () => {},
    error: null,
  };
}
export const MonitorEditor = () => null;
export const Icon = () => null;
export const ModalDialog = ({ visible, children }: { visible: boolean; children: ReactNode }) =>
  visible ? children : null;
export const useFocusedModal = (visible: boolean) => visible;
