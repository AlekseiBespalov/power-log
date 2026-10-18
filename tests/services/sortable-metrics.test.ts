import { createElement, type ComponentProps, type ReactNode } from 'react';
import { createRequire } from 'node:module';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { SortableMetrics } from '../../src/features/monitor/sortable-metrics';

const { renderToStaticMarkup } = createRequire(import.meta.url)('react-dom/server') as {
  renderToStaticMarkup: (node: ReactNode) => string;
};
const platform = vi.hoisted(() => ({ os: 'ios' }));
type Control = { accessibilityRole: string; disabled: boolean; onPress: () => void; style: unknown };
type Handle = {
  children?: ReactNode;
  accessibilityRole: string;
  accessibilityActions: { name: string; label: string }[];
  onAccessibilityAction: (event: { nativeEvent: { actionName: string } }) => void;
};
const handles = new Map<string, Handle>();
const announce = vi.fn();
function container({ children }: { children?: ReactNode }) {
  return createElement('div', null, children);
}
vi.mock('react-native', () => ({
  Platform: {
    get OS() {
      return platform.os;
    },
  },
  View: ({ accessibilityLabel, ...handle }: Handle & { accessibilityLabel?: string }) => {
    if (accessibilityLabel) handles.set(accessibilityLabel, handle);
    return container(handle);
  },
  Text: container,
  ScrollView: container,
  Pressable: ({
    children,
    accessibilityLabel,
    ...control
  }: Control & { children: ReactNode; accessibilityLabel: string }) => {
    return createElement('button', { disabled: control.disabled, 'aria-label': accessibilityLabel }, children);
  },
  Animated: {
    Value: class {
      setValue() {}
    },
    View: container,
  },
  PanResponder: { create: () => ({ panHandlers: {} }) },
  StyleSheet: { create: (value: unknown) => value },
  AccessibilityInfo: { announceForAccessibility: (text: string) => announce(text) },
  useWindowDimensions: () => ({ fontScale: 1 }),
}));
vi.mock('react-native-svg', () => ({ default: container, Path: () => null }));
vi.mock('../../src/components/ui', async () => ({
  Pressable: (await import('react-native')).Pressable,
  MIN_TOUCH_TARGET: 48,
  lineHeight: { label: 14, body: 20 },
  colors: {},
  type: { caption: 12, body: 14, label: 11, value: 26 },
  Label: container,
}));
vi.mock('../../src/services/haptics', () => ({ haptics: { selection: () => {} } }));
beforeEach(() => {
  handles.clear();
  announce.mockClear();
});

function render(selected: string[], onChange: (ids: string[]) => void) {
  handles.clear();
  return renderToStaticMarkup(
    createElement(
      SortableMetrics,
      {
        selected,
        kind: 'numbers',
        showSelected: true,
        onChange,
      } as ComponentProps<typeof SortableMetrics>,
      null,
    ),
  );
}

describe('monitor drag-handle accessibility actions', () => {
  it.each(['ios', 'android', 'web'])(
    'moves one position, omits boundary actions and announces the new position on %s',
    os => {
      platform.os = os;
      let selected = ['humanPowerW', 'cadenceRpm', 'motorInputPowerW'];
      const change = (ids: string[]) => {
        selected = ids;
      };
      const markup = render(selected, change);
      expect(markup).not.toContain('Move up');
      expect(markup).not.toContain('Move down');
      expect(handles.get('Reorder Rider power')!.accessibilityActions.map(action => action.name)).toEqual([
        'increment',
      ]);
      expect(handles.get('Reorder Battery input power')!.accessibilityActions.map(action => action.name)).toEqual([
        'decrement',
      ]);
      const handle = handles.get('Reorder Cadence')!;
      expect(handle.accessibilityRole).toBe('adjustable');
      expect(handle.accessibilityActions.map(action => action.name)).toEqual(['decrement', 'increment']);
      handle.onAccessibilityAction({ nativeEvent: { actionName: 'decrement' } });
      expect(selected).toEqual(['cadenceRpm', 'humanPowerW', 'motorInputPowerW']);
      expect(announce).toHaveBeenLastCalledWith('Cadence, position 1 of 3');
      render(selected, change);
      handles.get('Reorder Cadence')!.onAccessibilityAction({ nativeEvent: { actionName: 'increment' } });
      expect(selected).toEqual(['humanPowerW', 'cadenceRpm', 'motorInputPowerW']);
      expect(announce).toHaveBeenLastCalledWith('Cadence, position 2 of 3');
    },
  );
});
