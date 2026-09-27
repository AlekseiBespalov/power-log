import { useEffect, useState } from 'react';

// eslint-disable-next-line @typescript-eslint/no-require-imports
export const AppState: typeof import('react-native').AppState = require('react-native-web/dist/cjs/exports/AppState');
// eslint-disable-next-line @typescript-eslint/no-require-imports
export const Platform: typeof import('react-native').Platform = require('react-native-web/dist/cjs/exports/Platform');

export function useFocusEffect(effect: () => void | (() => void)) {
  useEffect(effect, [effect]);
}
export function useSharedValue<T>(initial: T) {
  return useState(() => ({ value: initial }))[0];
}
export const uiTasks: (() => void)[] = [];
export const rnTasks: (() => void)[] = [];
export function scheduleOnUI<A extends unknown[]>(callback: (...args: A) => void, ...args: A) {
  uiTasks.push(() => callback(...args));
}
export function scheduleOnRN<A extends unknown[]>(callback: (...args: A) => void, ...args: A) {
  rnTasks.push(() => callback(...args));
}
