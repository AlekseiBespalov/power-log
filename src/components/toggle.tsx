import { useEffect, useState } from 'react';
import { Animated, Platform, Pressable, View } from 'react-native';
import { useReducedMotion } from 'react-native-reanimated';
import { haptics } from '../services/haptics';
import { colors } from './ui';

export function Toggle({ label, value, onChange }: { label: string; value: boolean; onChange: (value: boolean) => void }) {
  const [progress] = useState(() => new Animated.Value(value ? 1 : 0));
  const reducedMotion = useReducedMotion();
  useEffect(() => {
    const animation = Animated.timing(progress, { toValue: value ? 1 : 0, duration: reducedMotion ? 0 : 160, useNativeDriver: Platform.OS !== 'web' });
    animation.start();
    return () => animation.stop();
  }, [progress, reducedMotion, value]);
  return <Pressable accessibilityRole="switch" accessibilityLabel={label} accessibilityState={{ checked: value }} aria-checked={value}
    onPress={() => { haptics.selection(); onChange(!value); }}
    style={({ pressed }) => ({ minWidth: 51, minHeight: 44, alignItems: 'center', justifyContent: 'center', flexShrink: 0, opacity: pressed ? 0.8 : 1 })}>
    <View pointerEvents="none" style={{ width: 51, height: 31, borderRadius: 16, backgroundColor: colors.border }}>
      <Animated.View style={{ position: 'absolute', width: '100%', height: '100%', borderRadius: 16, backgroundColor: '#9b461d', opacity: progress }} />
      <Animated.View style={{ width: 27, height: 27, margin: 2, borderRadius: 14, backgroundColor: colors.text, transform: [{ translateX: progress.interpolate({ inputRange: [0, 1], outputRange: [0, 20] }) }] }} />
    </View>
  </Pressable>;
}
