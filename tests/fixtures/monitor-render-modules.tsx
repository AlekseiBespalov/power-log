import { createElement, forwardRef, useEffect, useRef, useState, type ReactNode, type Ref } from 'react';
import { ScrollView, View } from 'react-native-web/dist/cjs/index.js';
import { count, counts } from './monitor-render-counts';

const gesture: object = new Proxy({}, { get: () => () => gesture });
export const Gesture = { Pan: () => gesture, Tap: () => gesture, Simultaneous: () => gesture, Race: () => gesture };
export const GestureDetector = ({ children }: { children: ReactNode }) => children;
export const GestureHandlerRootView = View;
export { ScrollView };

export function useSharedValue<T>(initial: T) {
  return useState(() => ({ value: initial }))[0];
}
export function useAnimatedProps<T>(updater: () => T) {
  return updater();
}
function createAnimatedComponent(Component: Parameters<typeof createElement>[0]) {
  return forwardRef(function Animated(
    { animatedProps, ...props }: { animatedProps?: object } & Record<string, unknown>,
    ref: Ref<unknown>,
  ) {
    return createElement(Component, { ...props, ...animatedProps, ref });
  });
}
export default { createAnimatedComponent };

export function scheduleOnUI<A extends unknown[]>(callback: (...args: A) => void, ...args: A) {
  callback(...args);
}
export const scheduleOnRN = scheduleOnUI;

export const SafeAreaProvider = ({ children }: { children: ReactNode }) => children;
export const SafeAreaView = View;
export const useSafeAreaInsets = () => ({ top: 0, bottom: 0, left: 0, right: 0 });

export function useFocusEffect(effect: () => void | (() => void)) {
  useEffect(effect, [effect]);
}
export const useIsFocused = () => true;

export function requireNativeViewManager() {
  return forwardRef(function NativePlot({ scene, style }: { scene: string; style?: object }, ref: Ref<unknown>) {
    const previous = useRef<string | undefined>(undefined);
    useEffect(() => {
      if (previous.current === scene) return;
      previous.current = scene;
      const ids = (JSON.parse(scene) as { series: { id: string }[] }).series.map(series => series.id);
      count(counts.nativeScenes, ids.join('+'));
    }, [scene]);
    return <View ref={ref as Ref<View>} style={style} />;
  });
}

export const requireOptionalNativeModule = () => null;
