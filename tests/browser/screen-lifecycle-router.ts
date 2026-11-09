import { useSyncExternalStore } from 'react';

const listeners = new Set<() => void>();
let focused = true;

export function setFocused(value: boolean) {
  focused = value;
  for (const listener of listeners) listener();
}
export function useIsFocused() {
  return useSyncExternalStore(
    listener => {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
    () => focused,
  );
}
