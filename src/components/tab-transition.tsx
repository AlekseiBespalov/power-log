import { createContext, useCallback, useContext, useEffect, useRef, useState, type ReactNode } from 'react';
import { AccessibilityInfo, Animated, Platform, useWindowDimensions } from 'react-native';
import { useFocusEffect } from 'expo-router';

const Context = createContext<{ enter: (index: number) => number; reducedMotion: boolean } | null>(null);

export function TabTransitionProvider({ children }: { children: ReactNode }) {
  const previous = useRef<number | null>(null);
  const enter = useCallback((index: number) => {
    const from = previous.current;
    previous.current = index;
    return from === null ? 0 : Math.sign(index - from);
  }, []);
  const [reducedMotion, setReducedMotion] = useState(false);
  useEffect(() => {
    let active = true;
    void AccessibilityInfo.isReduceMotionEnabled().then(value => { if (active) setReducedMotion(value); });
    const subscription = AccessibilityInfo.addEventListener('reduceMotionChanged', setReducedMotion);
    return () => { active = false; subscription.remove(); };
  }, []);
  return <Context.Provider value={{ enter, reducedMotion }}>{children}</Context.Provider>;
}

export function TabTransition({ index, children }: { index: number; children: ReactNode }) {
  return Platform.OS === 'ios' ? <>{children}</> : <SlidingTabTransition index={index}>{children}</SlidingTabTransition>;
}
function SlidingTabTransition({ index, children }: { index: number; children: ReactNode }) {
  const state = useContext(Context);
  if (!state) throw new Error('TabTransitionProvider is missing.');
  const { enter, reducedMotion } = state;
  const { width } = useWindowDimensions();
  const [offset] = useState(() => new Animated.Value(0));
  useFocusEffect(useCallback(() => {
    const direction = enter(index);
    if (!direction || reducedMotion) { offset.setValue(0); return; }
    offset.setValue(direction * Math.min(width * 0.15, 80));
    const animation = Animated.timing(offset, { toValue: 0, duration: 180, useNativeDriver: Platform.OS !== 'web' });
    animation.start();
    return () => { animation.stop(); offset.setValue(0); };
  }, [enter, index, offset, reducedMotion, width]));
  return <Animated.View testID={`tab-transition-${index}`} style={{ flex: 1, transform: [{ translateX: offset }] }}>{children}</Animated.View>;
}
