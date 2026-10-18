import { defaultMonitorPreferences } from '../../src/core/monitor';
import type { ReactNode } from 'react';
export const AppShell = ({ children }: { children: ReactNode }) => <>{children}</>;
export const useReducedMotion = () => false;
export { useSession, setPlatform, ModalDialog, Link, Icon, haptics } from './web-ui-parity-platform';
export {
  useSafeAreaInsets,
  useFocusEffect,
  useIsFocused,
  useRouter,
  usePathname,
  useGlobalSearchParams,
  workoutMonitorSource,
  MonitorPanel,
  RoutePreview,
  importRecording,
} from './history-deletion-platform';
let settingsFailure: string | null = null;
export function failSettingsLoad(message: string) {
  settingsFailure = message;
}
export const monitorPreferencesStore = {
  load: async () => {
    if (settingsFailure) throw new Error(settingsFailure);
    return { preferences: defaultMonitorPreferences() };
  },
  save: async () => {},
};
export const exportRide = async () => {};
export const exportRecording = async () => {};
