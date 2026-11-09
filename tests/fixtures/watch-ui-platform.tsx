import type { ReactNode } from 'react';
export const AppShell = ({ children }: { children: ReactNode }) => <>{children}</>;
export const useReducedMotion = () => false;
export { ModalDialog, Link, Icon, haptics } from './web-ui-parity-platform';
export {
  useSafeAreaInsets,
  useFocusEffect,
  useIsFocused,
  useRouter,
  usePathname,
  useGlobalSearchParams,
} from './history-deletion-platform';
