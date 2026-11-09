import { createElement, useEffect } from 'react';
import { createRoot } from 'react-dom/client';
import type { ScreenModel, UiEnv } from '../../src/components/screen-model';
import { UiEnvProvider, useScreenModel } from '../../src/components/use-screen-model';
import { setFocused } from './screen-lifecycle-router';

const listeners = new Set<() => void>();
let foreground = true;
export const calls: string[] = [];
let shown = '';

class Holder implements ScreenModel<{ value: number }> {
  private state = { value: 0 };
  getSnapshot = () => this.state;
  subscribe = () => () => undefined;
  activate() {
    calls.push('activate');
    this.state = { value: this.state.value + 1 };
  }
  deactivate() {
    calls.push('deactivate');
  }
  dispose() {
    calls.push('dispose');
  }
}

const env = {
  ui: {
    foreground: {
      current: () => foreground,
      subscribe: (listener: () => void) => {
        listeners.add(listener);
        return () => listeners.delete(listener);
      },
    },
  },
} as unknown as UiEnv;

function Screen() {
  const [state] = useScreenModel(() => new Holder(), []);
  useEffect(() => {
    shown = String(state.value);
  });
  return null;
}

export function mount() {
  const container = document.createElement('div');
  document.body.append(container);
  createRoot(container).render(createElement(UiEnvProvider, { value: env }, createElement(Screen)));
}
export function setForeground(value: boolean) {
  foreground = value;
  for (const listener of listeners) listener();
}
export { setFocused };
export const snapshot = () => ({ calls: [...calls], shown });
