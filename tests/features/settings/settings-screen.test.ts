import { createElement, type ReactNode } from 'react';
import { createRequire } from 'node:module';
import { beforeEach, expect, it, vi } from 'vitest';
import { SettingsScreen } from '../../../src/features/settings/settings-screen';

const { renderToStaticMarkup } = createRequire(import.meta.url)('react-dom/server') as {
  renderToStaticMarkup: (node: ReactNode) => string;
};
const buttons = new Map<string, () => void>();
const screen = vi.hoisted(() => ({
  dialog: null as null | { kind: 'unreadable' | 'saveFailed'; text: string; resetOffered: boolean },
  resetSettings: () => undefined as void,
  dismissDialog: () => undefined as void,
}));
function container({ children }: { children?: ReactNode }) {
  return createElement('div', null, children);
}

vi.mock('react-native', () => ({
  Platform: { OS: 'ios' },
  View: container,
  Text: container,
  ScrollView: container,
  Pressable: ({ children }: { children: ReactNode }) => createElement('button', null, children),
  useWindowDimensions: () => ({ width: 400, fontScale: 1 }),
}));
vi.mock('react-native-safe-area-context', () => ({ useSafeAreaInsets: () => ({ bottom: 0 }) }));
vi.mock('expo-router', () => ({ Link: container }));
vi.mock('../../../src/components/app-shell', () => ({ AppShell: container }));
vi.mock('../../../src/components/modal-dialog', () => ({ ModalDialog: container }));
vi.mock('../../../src/components/stable-label', () => ({ StableLabel: ({ value }: { value: string }) => value }));
vi.mock('../../../src/components/toggle', () => ({ Toggle: () => null }));
vi.mock('../../../src/components/choice-sheet', () => ({ ChoiceSheet: () => null }));
vi.mock('../../../src/components/external-link', () => ({ ExternalLink: container }));
vi.mock('../../../src/components/notice-banner', () => ({ NoticeBanner: () => null }));
vi.mock('../../../src/features/diagnostics/diagnostics-section', () => ({ DiagnosticsSection: () => null }));
vi.mock('../../../src/components/ui', () => ({
  Pressable: ({ children }: { children: ReactNode }) => createElement('button', null, children),
  MIN_TOUCH_TARGET: 48,
  lineHeight: { label: 14, body: 20 },
  colors: {},
  type: { caption: 12, body: 14, control: 15, title: 18, value: 26 },
  headingLevel: () => ({}),
  Button: ({ children, onPress }: { children: ReactNode; onPress: () => void }) => {
    buttons.set(String(children), onPress);
    return createElement('button', null, children);
  },
}));
vi.mock('../../../src/components/use-screen-model', () => {
  const row = (title: string, value: string) => ({ title, value, label: value, choices: [], busy: false });
  return {
    useScreenModel: () => [
      {
        ready: true,
        refused: false,
        rows: {
          units: row('Units', 'Metric'),
          temperature: row('Temperature', '°C'),
          distance: row('Distance source', 'Auto'),
          battery: row('Battery consumption source', 'Controller'),
          fitPower: row('FIT power source', 'Rider power'),
          rideType: row('Ride type', 'Outdoor'),
          sampleRate: row('Bike sample rate', '2 Hz'),
        },
        toggles: { useWatch: null, recordGps: null, saveToHealth: null },
        healthNote: null,
        storage: null,
        examples: null,
        dialog: screen.dialog,
        notice: null,
        version: '0.7.0',
      },
      {
        choose: () => undefined,
        setToggle: () => undefined,
        resetSettings: () => screen.resetSettings(),
        dismissDialog: () => screen.dismissDialog(),
      },
    ],
  };
});

const settings = () => renderToStaticMarkup(createElement(SettingsScreen));

beforeEach(() => {
  buttons.clear();
  screen.dialog = null;
});

it('offers Reset settings and Dismiss in the dialog for unreadable settings, and only Dismiss for a failed save', () => {
  const reset = vi.fn();
  const dismiss = vi.fn();
  screen.resetSettings = reset;
  screen.dismissDialog = dismiss;
  screen.dialog = { kind: 'unreadable', text: 'Power Log couldn’t read your saved settings.', resetOffered: true };
  expect(settings()).toContain('Settings unavailable');
  buttons.get('Reset settings')!();
  buttons.get('Dismiss')!();
  expect([reset.mock.calls.length, dismiss.mock.calls.length]).toEqual([1, 1]);

  screen.dialog = { kind: 'saveFailed', text: 'Your settings weren’t saved.', resetOffered: false };
  buttons.clear();
  expect(settings()).toContain('Settings not saved');
  expect(buttons.has('Reset settings')).toBe(false);
});

it('explains in About where rides are kept and how far readings can be trusted', () => {
  const markup = settings();
  expect(markup).toContain('Rides are stored on this device. Export a ride to keep a copy elsewhere.');
  expect(markup).toContain(
    'Readings come from your controller and sensors and can be inaccurate; battery use can read low. Set up before you ride, and don’t use the app while moving.',
  );
  expect(markup).toContain('Third-party notices');
});
