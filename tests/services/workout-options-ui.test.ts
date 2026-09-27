import { createElement, type ReactNode } from 'react';
import { createRequire } from 'node:module';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { defaultMonitorPreferences } from '../../src/core/monitor';
import {
  normalizeWorkoutState,
  unavailableWorkoutState,
  type WorkoutOptions,
  type WorkoutState,
} from '../../src/core/workouts';
import rideSnapshots from '../fixtures/contract/ride-snapshots.json';
import { SettingsScreen } from '../../src/features/settings/settings-screen';
import { RideActionBar, RideControlsProvider, RideStatus } from '../../src/features/workout/workout-screen';

const { renderToStaticMarkup } = createRequire(import.meta.url)('react-dom/server') as {
  renderToStaticMarkup: (node: ReactNode) => string;
};
let preferences = defaultMonitorPreferences();
let state: WorkoutState;
const toggles = new Map<string, { value: boolean; onChange: (value: boolean) => void }>();
const buttons = new Map<string, () => void>();
const run = vi.fn(async (operation: () => Promise<unknown>) => operation());
const start = vi.fn(async (_options: WorkoutOptions) => unavailableWorkoutState);
function container({ children }: { children?: ReactNode }) {
  return createElement('div', null, children);
}

vi.mock('react-native', () => ({
  Platform: { OS: 'ios' },
  View: container,
  Text: container,
  Pressable: container,
  ScrollView: container,
  ActivityIndicator: () => null,
  useWindowDimensions: () => ({ width: 400, fontScale: 1 }),
}));
vi.mock('react-native-safe-area-context', () => ({ useSafeAreaInsets: () => ({ bottom: 0 }) }));
vi.mock('expo-router', () => ({ Link: container }));
vi.mock('../../src/components/app-shell', () => ({ AppShell: container }));
vi.mock('../../src/components/modal-dialog', () => ({ ModalDialog: container }));
vi.mock('../../src/components/stable-label', () => ({ StableLabel: ({ value }: { value: string }) => value }));
vi.mock('../../src/components/icon', () => ({ Icon: () => null }));
vi.mock('../../src/components/toggle', () => ({
  Toggle: ({ label, ...control }: { label: string; value: boolean; onChange: (value: boolean) => void }) => {
    toggles.set(label, control);
    return createElement('button', { 'aria-label': label, 'aria-pressed': control.value }, label);
  },
}));
vi.mock('../../src/components/ui', () => ({
  colors: {},
  styles: {},
  headingLevel: () => ({}),
  Body: container,
  Chip: container,
  Metric: () => null,
  formatDuration: (seconds: number) => String(seconds),
  Button: ({ children, onPress, testID }: { children: ReactNode; onPress: () => void; testID?: string }) => {
    buttons.set(testID ?? String(children), onPress);
    return createElement('button', { 'data-testid': testID }, children);
  },
}));
vi.mock('../../src/services/haptics', () => ({ haptics: { success: vi.fn(), warning: vi.fn() } }));
vi.mock('../../src/services/workouts', () => ({ workouts: { start: (options: WorkoutOptions) => start(options) } }));
vi.mock('../../src/services/monitor-preferences', () => ({
  useMonitorPreferences: () => ({
    preferences,
    ready: true,
    error: null,
    setWorkoutOptions: (update: (previous: WorkoutOptions) => WorkoutOptions) => {
      preferences = { ...preferences, workoutOptions: update(preferences.workoutOptions) };
    },
  }),
}));
vi.mock('../../src/services/workout-context', () => ({
  useWorkout: () => ({
    state,
    options: preferences.workoutOptions,
    ready: true,
    optionsReady: true,
    busy: false,
    permissions: null,
    permissionsError: null,
    error: null,
    run,
  }),
}));
vi.mock('../../src/services/session-context', () => ({
  useSession: () => ({
    hz: 4,
    adapter: { kind: 'native' },
    display: 'live',
    state: { status: 'connected', deviceId: 'synthetic-bike', deviceName: 'Example bike' },
  }),
}));

const explanation =
  'Phone-recorded rides cannot be saved to Apple Health on this device. This ride will stay in Power Log.';
function settings() {
  toggles.clear();
  return renderToStaticMarkup(createElement(SettingsScreen));
}
function ride() {
  buttons.clear();
  return renderToStaticMarkup(
    createElement(
      RideControlsProvider,
      null,
      createElement(RideActionBar),
      createElement(RideStatus, { connection: null }),
    ),
  );
}

beforeEach(() => {
  vi.stubGlobal('__DEV__', false);
  vi.clearAllMocks();
  preferences = defaultMonitorPreferences();
  state = {
    ...unavailableWorkoutState,
    supported: true,
    capabilities: {
      phoneWorkout: true,
      watchWorkout: true,
      phoneHealth: false,
      watchHealth: true,
      healthProvider: 'appleHealth',
      gps: true,
      foregroundOnly: false,
    },
    watch: { installed: true },
  };
});

describe('owner-aware recording controls', () => {
  it.each([true, false])(
    'keeps Health preference %s through Phone → Watch → Phone and sends effective Start options',
    async saveToHealth => {
      preferences.workoutOptions = { indoor: true, useWatch: false, saveToHealth, recordGPS: false };
      for (const useWatch of [false, true, false]) {
        settings();
        toggles.get('Use Apple Watch')!.onChange(useWatch);
        const settingsMarkup = settings();
        expect(toggles.get('Save to Apple Health')!.value).toBe(saveToHealth);
        const rideMarkup = ride();
        if (saveToHealth && !useWatch) {
          expect(settingsMarkup).toContain(explanation);
          expect(rideMarkup).toContain(explanation);
          expect(rideMarkup).toContain('Start ride');
        } else {
          expect(settingsMarkup).not.toContain(explanation);
          expect(rideMarkup).not.toContain(explanation);
        }
        start.mockClear();
        buttons.get('Start ride')!();
        await vi.waitFor(() =>
          expect(start).toHaveBeenLastCalledWith({
            indoor: true,
            useWatch,
            saveToHealth: saveToHealth && useWatch,
            recordGPS: false,
            sampleHz: 4,
          }),
        );
        expect(preferences.workoutOptions.saveToHealth).toBe(saveToHealth);
      }
    },
  );

  it('labels the toggle by provider and hides it when neither owner can save', () => {
    state.capabilities = {
      phoneWorkout: true,
      watchWorkout: false,
      phoneHealth: true,
      watchHealth: false,
      healthProvider: 'healthConnect',
      gps: true,
      foregroundOnly: false,
    };
    expect(settings()).toContain('Save to Health Connect');
    expect(toggles.has('Use Apple Watch')).toBe(false);
    state.capabilities = { ...state.capabilities, phoneHealth: false, healthProvider: null };
    settings();
    expect(toggles.has('Save to Health Connect')).toBe(false);
    expect(toggles.has('Save to Apple Health')).toBe(false);
  });

  it('shows GPS settings only when GPS is supported', () => {
    settings();
    expect(toggles.has('Record GPS route')).toBe(true);
    state.capabilities = { ...state.capabilities, gps: false };
    settings();
    expect(toggles.has('Record GPS route')).toBe(false);
  });

  it.each(['unresolved recovery with an error', 'Watch ride finishing after Health saved'])(
    'presents the shared snapshot: %s',
    name => {
      const fixture = rideSnapshots.cases.find(value => value.name === name)!;
      state = normalizeWorkoutState(fixture.wire as WorkoutState);
      const markup = ride();
      if (name === 'unresolved recovery with an error') {
        expect(markup).toContain(state.recoveryMessage);
        expect(markup).toContain(state.error);
        expect(markup).toContain('Needs attention');
      } else {
        expect(markup).toContain('Saved to Apple Health. Syncing Watch data…');
        expect(markup).toContain('Syncing…');
      }
    },
  );
});
