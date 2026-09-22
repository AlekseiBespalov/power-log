import { useCallback, useLayoutEffect, useRef, type ReactNode } from 'react';
import { useFocusEffect, useIsFocused } from 'expo-router';
import { KeyboardAvoidingView, Modal, Platform, Pressable, StyleSheet, View, useWindowDimensions, type StyleProp, type ViewStyle } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

type Props = {
  visible: boolean;
  onClose: () => void;
  closeLabel: string;
  testID: string;
  style: StyleProp<ViewStyle>;
  placement?: 'center' | 'adaptive';
  children: ReactNode;
};

/** A native/web modal is a portal, so hiding the route alone cannot dismiss it. */
export function useFocusedModal(visible: boolean, onClose: () => void) {
  const focused = useIsFocused();
  const latest = useRef({ visible, onClose });
  useLayoutEffect(() => { latest.current = { visible, onClose }; }, [visible, onClose]);
  useFocusEffect(useCallback(() => () => {
    if (latest.current.visible) latest.current.onClose();
  }, []));
  return focused && visible;
}

export function ModalDialog({ visible, onClose, closeLabel, testID, style, placement = 'adaptive', children }: Props) {
  const { width } = useWindowDimensions();
  const insets = useSafeAreaInsets();
  const presented = useFocusedModal(visible, onClose);
  const centered = placement === 'center' || (Platform.OS === 'web' && width >= 760);
  return <Modal visible={presented} transparent animationType="fade" onRequestClose={onClose}>
    <View style={{ flex: 1 }}>
      <Pressable testID={`${testID}-backdrop`} accessibilityRole="button" accessibilityLabel={closeLabel} onPress={onClose} style={[StyleSheet.absoluteFill, { backgroundColor: '#0009' }]} />
      <KeyboardAvoidingView pointerEvents="box-none" behavior={Platform.OS === 'ios' ? 'padding' : undefined} style={{ flex: 1, justifyContent: centered ? 'center' : 'flex-end', paddingHorizontal: centered ? 24 : 0, paddingTop: centered ? Math.max(24, insets.top) : insets.top, paddingBottom: centered ? Math.max(24, insets.bottom) : 0 }}>
        <View testID={testID} accessibilityViewIsModal style={[{ maxHeight: '100%', flexShrink: 1 }, style, centered && { borderRadius: 16 }]}>
          {children}
        </View>
      </KeyboardAvoidingView>
    </View>
  </Modal>;
}
