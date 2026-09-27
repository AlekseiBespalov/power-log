import { createElement, type ComponentProps } from 'react';
import { createRequire } from 'node:module';
import { expect, it, vi } from 'vitest';
import type { NativeState } from '../../src/core/types';
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
  useWindowDimensions: () => ({ width: 900 }),
}));
vi.mock('expo-router', () => ({ useFocusEffect: () => {} }));
vi.mock('../../src/components/ui', () => ({
  colors: {},
  Heading: 'h2',
  Button: ({ children, disabled }: { children: import('react').ReactNode; disabled?: boolean }) =>
    createElement('button', { disabled }, children),
}));
vi.mock('../../src/components/icon', () => ({ Icon: () => null }));
vi.mock('../../src/features/dashboard/connection-health', () => ({ ConnectionHealth: () => null }));
vi.mock('../../src/services/session-context', () => ({
  useSession: () => ({
    state: { status: fixture.status },
    display: 'unavailable',
    busy: fixture.busy,
    adapter: { kind: fixture.kind },
    devices: [],
  }),
}));
vi.mock('../../src/services/workout-context', () => ({ useWorkoutIdentity: () => ({ phase: fixture.phase }) }));
import { ConnectionSettings } from '../../src/features/dashboard/connection-settings';
import { TabTransition } from '../../src/components/tab-transition';

it.each(['ios', 'android', 'web'])('uses the same connecting control and active-ride exclusion on %s', os => {
  fixture.os = os;
  fixture.kind = os === 'web' ? 'web' : 'native';
  for (const status of ['connecting', 'reconnecting', 'connected'] satisfies NativeState['status'][]) {
    fixture.status = status;
    fixture.phase = 'idle';
    fixture.busy = false;
    expect(renderToStaticMarkup(createElement(ConnectionSettings))).toContain('<button>Disconnect</button>');
    fixture.phase = 'running';
    expect(renderToStaticMarkup(createElement(ConnectionSettings))).toContain(
      '<button disabled="">Disconnect</button>',
    );
    fixture.phase = 'idle';
    fixture.busy = true;
    expect(renderToStaticMarkup(createElement(ConnectionSettings))).toContain(
      '<button disabled="">Disconnect</button>',
    );
  }
});
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
