import { useRef, useState } from 'react';
import { Modal, StyleSheet, Text, View, useWindowDimensions } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { useModalAccessibility } from './modal-accessibility';
import { useFocusedModal } from './modal-dialog';
import { colors, Pressable, type } from './ui';

export const ENERGY_SMOOTHING_SECONDS = [0, 5, 30, 60] as const;
export type EnergySmoothingSeconds = (typeof ENERGY_SMOOTHING_SECONDS)[number];

export function EnergySmoothingMenu({
  value,
  onChange,
  testID,
}: {
  value: EnergySmoothingSeconds;
  onChange: (value: EnergySmoothingSeconds) => void;
  testID: string;
}) {
  const trigger = useRef<View>(null);
  const [anchor, setAnchor] = useState<{ x: number; y: number; width: number; height: number } | null>(null);
  const close = () => setAnchor(null);
  const visible = useFocusedModal(anchor !== null, close);
  const modalRef = useModalAccessibility(visible);
  const size = useWindowDimensions();
  const insets = useSafeAreaInsets();
  const menuWidth = 136,
    menuHeight = 200;
  const left = Math.max(
    insets.left + 8,
    Math.min(size.width - insets.right - menuWidth - 8, (anchor?.x ?? 0) + (anchor?.width ?? 0) - menuWidth),
  );
  const below = (anchor?.y ?? 0) + (anchor?.height ?? 0);
  const top = Math.max(
    insets.top + 8,
    below + menuHeight <= size.height - insets.bottom - 8 ? below : (anchor?.y ?? 0) - menuHeight,
  );
  return (
    <>
      <View ref={trigger} collapsable={false}>
        <Pressable
          accessibilityRole="button"
          accessibilityLabel={`Consumption smoothing: ${value ? `${value} seconds` : 'off'}`}
          accessibilityState={{ expanded: visible }}
          aria-haspopup="menu"
          testID={testID}
          onPress={() => trigger.current?.measureInWindow((x, y, width, height) => setAnchor({ x, y, width, height }))}
          style={{ paddingHorizontal: 8, justifyContent: 'center' }}
        >
          <Text style={{ color: value ? colors.accent : colors.muted, fontSize: type.caption }}>
            {value ? `Smooth · ${value} s ▾` : 'Smooth ▾'}
          </Text>
        </Pressable>
      </View>
      <Modal visible={visible} transparent animationType="none" onRequestClose={close}>
        <View ref={modalRef} aria-hidden={!visible} style={{ flex: 1 }}>
          <Pressable
            testID={`${testID}-backdrop`}
            accessibilityRole="button"
            accessibilityLabel="Close smoothing menu"
            onPress={close}
            style={StyleSheet.absoluteFill}
          />
          <View
            accessibilityViewIsModal
            accessibilityRole="menu"
            testID={`${testID}-menu`}
            style={{
              position: 'absolute',
              left,
              top,
              width: menuWidth,
              paddingVertical: 3,
              backgroundColor: colors.surfaceRaised,
              borderColor: colors.border,
              borderWidth: 1,
              borderRadius: 10,
            }}
          >
            {ENERGY_SMOOTHING_SECONDS.map(seconds => (
              <Pressable
                key={seconds}
                accessibilityRole="menuitem"
                accessibilityState={{ selected: value === seconds }}
                testID={`${testID}-${seconds}`}
                onPress={() => {
                  onChange(seconds);
                  close();
                }}
                style={{
                  paddingHorizontal: 16,
                  flexDirection: 'row',
                  alignItems: 'center',
                  justifyContent: 'space-between',
                }}
              >
                <Text style={{ color: colors.text, fontSize: type.control }}>{seconds ? `${seconds} s` : 'Off'}</Text>
                {value === seconds && <Text style={{ color: colors.accent }}>✓</Text>}
              </Pressable>
            ))}
          </View>
        </View>
      </Modal>
    </>
  );
}
