import { createElement, type ReactNode } from 'react';
import { createRequire } from 'node:module';
import { expect, it, vi } from 'vitest';
import { TabBar } from '../../src/components/tab-bar';

const { renderToStaticMarkup } = createRequire(import.meta.url)('react-dom/server') as {
  renderToStaticMarkup: (node: ReactNode) => string;
};
const controls = new Map<string, { selected: boolean; press: () => void }>();
function container({ children }: { children?: ReactNode }) {
  return createElement('div', null, children);
}
vi.mock('react-native', () => ({
  View: container,
  Text: container,
  Pressable: ({
    accessibilityLabel,
    accessibilityState,
    onPress,
  }: {
    accessibilityLabel: string;
    accessibilityState: { selected: boolean };
    onPress: () => void;
  }) => {
    controls.set(accessibilityLabel, { selected: accessibilityState.selected, press: onPress });
    return null;
  },
}));
vi.mock('react-native-safe-area-context', () => ({ useSafeAreaInsets: () => ({ bottom: 0 }) }));
vi.mock('../../src/components/icon', () => ({ Icon: () => null }));
vi.mock('../../src/components/ui', () => ({ colors: {}, type: {} }));
vi.mock('../../src/services/haptics', () => ({ haptics: { selection: vi.fn() } }));

it('keeps Privacy under Settings while allowing the native Settings tab to return there', () => {
  const navigate = vi.fn();
  const emit = vi.fn(() => ({ defaultPrevented: false }));
  const routes = ['index', 'sessions', 'settings', 'privacy'].map(name => ({ name, key: name }));
  const props = {
    state: { index: 3, routes },
    navigation: { navigate, emit },
  } as unknown as Parameters<typeof TabBar>[0];
  renderToStaticMarkup(createElement(TabBar, props));
  expect([...controls.keys()]).toEqual(['Ride', 'History', 'Settings']);
  expect(controls.get('Settings')!.selected).toBe(true);
  expect(controls.get('Ride')!.selected).toBe(false);
  controls.get('Settings')!.press();
  expect(navigate).toHaveBeenCalledWith('settings');
  expect(emit).toHaveBeenCalledWith({ type: 'tabPress', target: 'settings', canPreventDefault: true });

  navigate.mockClear();
  renderToStaticMarkup(createElement(TabBar, { ...props, state: { ...props.state, index: 2 } }));
  controls.get('Settings')!.press();
  expect(navigate).not.toHaveBeenCalled();
});
