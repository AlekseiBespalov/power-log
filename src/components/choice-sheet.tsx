import { useRef, type KeyboardEvent } from 'react';
import { Platform, ScrollView, Text, View, useWindowDimensions } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { ModalDialog } from './modal-dialog';
import { Button, colors, Pressable, type } from './ui';

type Focusable = { focus?: () => void };

export interface Choice {
  value: string;
  label: string;
}

export function ChoiceSheet({
  visible,
  title,
  choices,
  selected,
  onChoose,
  onClose,
  testID,
}: {
  visible: boolean;
  title: string;
  choices: readonly Choice[];
  selected: string | null;
  onChoose: (value: string) => void;
  onClose: () => void;
  testID: string;
}) {
  const { fontScale } = useWindowDimensions();
  const insets = useSafeAreaInsets();
  const options = useRef<Record<string, Focusable | null>>({});
  const keyDown = (event: KeyboardEvent, index: number) => {
    if (event.key === ' ' || event.key === 'Enter') {
      event.preventDefault();
      onChoose(choices[index]!.value);
    } else if (['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) {
      event.preventDefault();
      const next =
        event.key === 'Home'
          ? 0
          : event.key === 'End'
            ? choices.length - 1
            : (index + (event.key === 'ArrowDown' ? 1 : choices.length - 1)) % choices.length;
      options.current[choices[next]!.value]?.focus?.();
    }
  };
  return (
    <ModalDialog
      visible={visible}
      onClose={onClose}
      closeLabel="Close setting options"
      testID={testID}
      style={{
        width: '100%',
        maxWidth: 480,
        alignSelf: 'center',
        backgroundColor: colors.surface,
        borderWidth: 1,
        borderColor: colors.border,
        borderTopLeftRadius: 16,
        borderTopRightRadius: 16,
      }}
    >
      <View style={{ padding: 16, flexDirection: 'row', alignItems: 'center', gap: 12 }}>
        <Text
          accessibilityRole="header"
          style={{ fontSize: type.title, fontWeight: '600', color: colors.text, flex: 1, minWidth: 0 }}
        >
          {title}
        </Text>
        <Button secondary onPress={onClose}>
          Done
        </Button>
      </View>
      <ScrollView
        showsHorizontalScrollIndicator={false}
        style={{ flexShrink: 1 }}
        contentContainerStyle={{ paddingHorizontal: 16, paddingBottom: Math.max(16, insets.bottom) }}
      >
        <View accessibilityRole="radiogroup" accessibilityLabel={title}>
          {choices.map((choice, index) => (
            <Pressable
              key={choice.value}
              ref={node => {
                options.current[choice.value] = node;
              }}
              accessibilityRole="radio"
              accessibilityLabel={choice.label}
              accessibilityState={{ checked: choice.value === selected }}
              aria-checked={choice.value === selected}
              onPress={() => onChoose(choice.value)}
              {...(Platform.OS === 'web' ? { onKeyDown: (event: KeyboardEvent) => keyDown(event, index) } : {})}
              style={({ pressed }) => ({
                minHeight: 52,
                paddingVertical: 12,
                flexDirection: 'row',
                alignItems: 'center',
                gap: 12,
                opacity: pressed ? 0.75 : 1,
              })}
            >
              <Text style={{ color: colors.text, fontSize: type.control, flex: 1, minWidth: 0 }}>{choice.label}</Text>
              <Text
                aria-hidden
                accessibilityElementsHidden
                importantForAccessibility="no-hide-descendants"
                style={{ width: 24 * fontScale, color: colors.accent, fontSize: type.title, textAlign: 'center' }}
              >
                {choice.value === selected ? '✓' : ''}
              </Text>
            </Pressable>
          ))}
        </View>
      </ScrollView>
    </ModalDialog>
  );
}
