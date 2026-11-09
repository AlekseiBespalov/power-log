import { useIsFocused } from 'expo-router';
import { createContext, useCallback, useContext, useEffect, useState, useSyncExternalStore } from 'react';
import type { ScreenModel, UiEnv } from './screen-model';

const UiEnvContext = createContext<UiEnv | null>(null);

export const UiEnvProvider = UiEnvContext.Provider;

export function useUiEnv(): UiEnv {
  const env = useContext(UiEnvContext);
  if (!env) throw new Error('UiEnvProvider is missing.');
  return env;
}

export function useForeground(env: UiEnv): boolean {
  const { foreground } = env.ui;
  return useSyncExternalStore(foreground.subscribe, foreground.current, foreground.current);
}

export function useModelSnapshot<S>(model: ScreenModel<S>): S {
  const subscribe = useCallback((onChange: () => void) => model.subscribe(onChange), [model]);
  const snapshot = useCallback(() => model.getSnapshot(), [model]);
  return useSyncExternalStore(subscribe, snapshot, snapshot);
}

export function useScreenModel<M extends ScreenModel<unknown>>(
  create: (env: UiEnv) => M,
  deps: readonly unknown[],
): readonly [ReturnType<M['getSnapshot']>, M] {
  const env = useUiEnv();
  const [holder, setHolder] = useState(() => ({ env, deps, model: create(env) }));
  let model = holder.model;
  if (holder.env !== env || !sameDeps(holder.deps, deps)) {
    model = create(env);
    setHolder({ env, deps, model });
  }
  const focused = useIsFocused();
  const foreground = useForeground(env);
  useEffect(() => () => model.dispose(), [model]);
  useEffect(() => {
    if (!focused || !foreground) return;
    model.activate();
    return () => model.deactivate();
  }, [model, focused, foreground]);
  return [useModelSnapshot(model) as ReturnType<M['getSnapshot']>, model] as const;
}

const sameDeps = (previous: readonly unknown[], next: readonly unknown[]) =>
  previous.length === next.length && previous.every((value, index) => Object.is(value, next[index]));
