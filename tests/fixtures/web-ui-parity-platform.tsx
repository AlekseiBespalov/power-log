import { useEffect, type ReactNode } from 'react';
import { Platform, View } from 'react-native';

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
export const haptics = { selection: () => {}, success: () => {}, warning: () => {}, impact: () => {} };
export function setPlatform(os: 'web' | 'android' | 'ios') {
  Object.defineProperty(Platform, 'OS', { configurable: true, value: os });
}
