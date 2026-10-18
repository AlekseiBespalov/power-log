import { TabTransition } from '../../src/components/tab-transition';
import { ConnectionSettings } from '../../src/features/dashboard/connection-settings';
import { createElement, type ComponentProps } from 'react';
import { createRequire } from 'node:module';
import { expect, it, vi } from 'vitest';
import type { NativeState } from '../../src/core/types';
import { metricById } from '../../src/core/monitor';
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

it.each(['ios', 'android', 'web'])('uses the same connecting control and active-ride exclusion on %s', os => {
  fixture.os = os;
  fixture.kind = os === 'web' ? 'web' : 'native';
  for (const status of ['connecting', 'reconnecting', 'connected'] satisfies NativeState['status'][]) {
    fixture.status = status;
    fixture.phase = 'idle';
    fixture.busy = false;
    const label = status === 'connected' ? 'Disconnect' : 'Cancel connection';
    expect(renderToStaticMarkup(createElement(ConnectionSettings))).toContain(`<button>${label}</button>`);
    fixture.phase = 'running';
    expect(renderToStaticMarkup(createElement(ConnectionSettings))).toContain(`<button disabled="">${label}</button>`);
    fixture.phase = 'idle';
    fixture.busy = true;
    expect(renderToStaticMarkup(createElement(ConnectionSettings))).toContain(
      `<button${status === 'connected' ? ' disabled=""' : ''}>${label}</button>`,
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

it('shares the iPhone type tokens, touch minimum and uncalibrated speed wording', () => {
  expect(type).toEqual({ axis: 10, label: 11, caption: 12, body: 14, control: 15, title: 18, value: 26, hero: 38 });
  expect(MIN_TOUCH_TARGET).toBe(48);
  expect(metricById('speedRaw')).toMatchObject({
    label: 'Controller speed signal (uncalibrated)',
    shortLabel: 'Speed signal',
    unit: 'raw',
  });
});
