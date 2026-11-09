import { TabTransition } from '../../src/components/tab-transition';
import { createElement, type ComponentProps } from 'react';
import { createRequire } from 'node:module';
import { expect, it, vi } from 'vitest';
import { type, MIN_TOUCH_TARGET } from '../../src/components/ui';
const { renderToStaticMarkup } = createRequire(import.meta.url)('react-dom/server') as {
  renderToStaticMarkup: (node: import('react').ReactNode) => string;
};
const fixture = vi.hoisted(() => ({ os: 'web', kind: 'web', status: 'connecting', busy: false, phase: 'idle' }));
vi.mock('react-native', () => ({
  Platform: {
    get OS() {
      return fixture.os;
    },
  },
  View: 'div',
  Text: 'span',
  Animated: { Value: class {}, View: 'animated-view' },
  StyleSheet: { create: (value: unknown) => value },
  useWindowDimensions: () => ({ width: 900 }),
}));
vi.mock('expo-router', () => ({ useFocusEffect: () => {} }));
vi.mock('../../src/components/ui', async importOriginal => ({
  ...(await importOriginal<typeof import('../../src/components/ui')>()),
  colors: {},
  Heading: 'h2',
  Button: ({ children, disabled }: { children: import('react').ReactNode; disabled?: boolean }) =>
    createElement('button', { disabled }, children),
}));
vi.mock('../../src/components/icon', () => ({ Icon: () => null }));

it.each(['ios', 'android', 'web'])('renders route content without an animated transform on %s', os => {
  fixture.os = os;
  const content = createElement('main', null, 'Ride');
  const rendered = renderToStaticMarkup(
    createElement(TabTransition, { index: 1 } as ComponentProps<typeof TabTransition>, content),
  );
  expect(rendered).toBe(
    os === 'web' ? '<div testID="tab-transition-1" style="flex:1"><main>Ride</main></div>' : '<main>Ride</main>',
  );
});

it('shares the iPhone type tokens and touch minimum', () => {
  expect(type).toEqual({ axis: 10, label: 11, caption: 12, body: 14, control: 15, title: 18, value: 26, hero: 38 });
  expect(MIN_TOUCH_TARGET).toBe(48);
});
