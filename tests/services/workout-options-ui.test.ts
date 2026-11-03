import { createElement, type ReactNode } from 'react';
import { createRequire } from 'node:module';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { defaultMonitorPreferences } from '../../src/core/monitor';
import { MonitorPreferencesController } from '../../src/services/monitor-preferences-controller';
import {
  normalizeWorkoutState,
  unavailableWorkoutState,
  type WorkoutOptions,
  type WorkoutState,
} from '../../src/core/workouts';
import { SavedRides } from '../../src/features/history/saved-rides';
import { metadata } from '../fixtures/history-deletion-backend';
import rideSnapshots from '../fixtures/contract/ride-snapshots.json';
import failures from '../fixtures/contract/presentation/failures.json';
import { presentationFailure } from '../../shared/js';
import { SettingsScreen } from '../../src/features/settings/settings-screen';
import { RideActionBar, RideControlsProvider, RideStatus } from '../../src/features/workout/workout-screen';
import { ConnectionSettings } from '../../src/features/dashboard/connection-settings';
import { DashboardScreen } from '../../src/features/dashboard/dashboard-screen';
import { WorkoutActions, type WorkoutActionFlight } from '../../src/services/workout-actions';
import { formatRideDate } from '../../src/core/formatting';
vi.mock('../../src/services/device', () => ({ deviceAdapter: {} }));
const clock = vi.hoisted(() => ({ twentyFourHour: null as boolean | null }));
vi.mock('../../src/services/clock-preference', () => ({ deviceUsesTwentyFourHourClock: () => clock.twentyFourHour }));
vi.mock('../../src/services/files', () => ({}));
vi.mock('../../src/services/diagnostics-export', () => ({}));

// Static renders retain hook cells only for tests that drive captured button handlers.
const localState = vi.hoisted(() => ({
  enabled: false,
  index: 0,
  values: new Map<number, unknown>(),
  writes: [] as unknown[],
}));
vi.mock('react', async importOriginal => {
  const react = await importOriginal<typeof import('react')>();
  return {
    ...react,
    useState: <T>(initial: T | (() => T)) => {
      if (!localState.enabled) return react.useState(initial);
      const index = localState.index++;
      const [value] = react.useState<T>(() =>
        localState.values.has(index)
          ? (localState.values.get(index) as T)
          : typeof initial === 'function'
            ? (initial as () => T)()
            : initial,
      );
      localState.values.set(index, value);
      return [
        value,
        (update: import('react').SetStateAction<T>) => {
          const next =
            typeof update === 'function' ? (update as (previous: T) => T)(localState.values.get(index) as T) : update;
          localState.values.set(index, next);
          localState.writes.push(next);
        },
      ] as const;
    },
  };
});
const { renderToStaticMarkup } = createRequire(import.meta.url)('react-dom/server') as {
  renderToStaticMarkup: (node: ReactNode) => string;
};
const escaped = (text: string) => text.replaceAll("'", '&#x27;');
let preferences = defaultMonitorPreferences();
let state: WorkoutState;
const platformState = vi.hoisted(() => ({ os: 'ios' }));
let bikeConnected = true;
let bikeReconnecting = false;
let bikeHeld = false;
let bikeBusy = false;
let bikeFault = 0;
let permissionStatus: import('../../src/core/workouts').WorkoutPermissionStatus | null = null;
let permissionsError: string | null = null;
const refreshPermissions = vi.fn(async () => {});
const disconnect = vi.fn(async () => {});
const controls = new Map<string, { disabled: boolean; secondary: boolean }>();
const toggles = new Map<string, { value: boolean; onChange: (value: boolean) => void }>();
const buttons = new Map<string, () => void>();
const run = vi.fn(async (operation: (flight: WorkoutActionFlight) => Promise<unknown>) =>
  operation({ current: () => true, signal: new AbortController().signal }),
);
const addExampleRides = vi.fn<() => Promise<number>>();
const getPermissions = vi.fn<() => Promise<import('../../src/core/workouts').WorkoutPermissionStatus>>();
const start = vi.fn(async (_options: WorkoutOptions) => unavailableWorkoutState);
const prepareRecording = vi.fn(async () => preferences);
const resetSettings = vi.fn();
let settingsError: string | null = null;
let resetOffered = false;
const scan = vi.fn(async () => {});
const refreshHealth = vi.fn(async (_id: string) => {});
const retryHealthSave = vi.fn(async (_id: string) => {});
const stop = vi.fn(async (_id?: string) => {});
function container({ children }: { children?: ReactNode }) {
  return createElement('div', null, children);
}

vi.mock('react-native', () => ({
  Platform: {
    get OS() {
      return platformState.os;
    },
  },
  AppState: { currentState: 'active', addEventListener: () => ({ remove: () => undefined }) },
  View: container,
  Text: container,
  Pressable: ({
    children,
    accessibilityLabel,
    disabled = false,
    onPress,
  }: {
    children: ReactNode;
    accessibilityLabel?: string;
    disabled?: boolean;
    onPress: () => void;
  }) => {
    if (accessibilityLabel) {
      controls.set(accessibilityLabel, { disabled, secondary: true });
      buttons.set(accessibilityLabel, onPress);
    }
    return createElement('button', { disabled, 'aria-label': accessibilityLabel }, children);
  },
  ScrollView: container,
  ActivityIndicator: () => null,
  useWindowDimensions: () => ({ width: 400, fontScale: 1 }),
}));
vi.mock('react-native-safe-area-context', () => ({ useSafeAreaInsets: () => ({ bottom: 0 }) }));
vi.mock('expo-router', () => ({ Link: container, useFocusEffect: () => undefined }));
vi.mock('../../src/components/app-shell', () => ({ AppShell: container }));
vi.mock('../../src/components/modal-dialog', () => ({
  ModalDialog: (props: { children?: ReactNode; visible?: boolean }) =>
    localState.enabled && props.visible === false ? null : container(props),
}));
vi.mock('../../src/components/stable-label', () => ({ StableLabel: ({ value }: { value: string }) => value }));
vi.mock('../../src/components/icon', () => ({ Icon: () => null }));
vi.mock('../../src/features/monitor/monitor-panel', () => ({
  MonitorPanel: ({ emptyState }: { emptyState?: ReactNode }) => createElement('section', null, emptyState),
}));
vi.mock('../../src/services/monitor-source', () => ({ nativeMonitorSource: () => ({ key: 'live' }) }));
vi.mock('../../src/services/workout-monitor-source', () => ({ workoutMonitorSource: () => ({ key: 'ride' }) }));
vi.mock('../../src/components/toggle', () => ({
  Toggle: ({ label, ...control }: { label: string; value: boolean; onChange: (value: boolean) => void }) => {
    toggles.set(label, control);
    return createElement('button', { 'aria-label': label, 'aria-pressed': control.value }, label);
  },
}));
vi.mock('../../src/components/ui', async () => ({
  Pressable: (await import('react-native')).Pressable,
  MIN_TOUCH_TARGET: 48,
  lineHeight: { label: 14, body: 20 },
  colors: {},
  type: { caption: 12, body: 14, control: 15, title: 18, value: 26 },
  styles: {},
  headingLevel: () => ({}),
  Body: container,
  Heading: container,
  Card: container,
  Chip: container,
  Metric: () => null,
  formatDuration: (seconds: number) => String(seconds),
  Button: ({
    children,
    onPress,
    testID,
    disabled = false,
    secondary = false,
  }: {
    children: ReactNode;
    onPress: () => void;
    testID?: string;
    disabled?: boolean;
    secondary?: boolean;
  }) => {
    buttons.set(testID ?? String(children), onPress);
    controls.set(testID ?? String(children), { disabled, secondary });
    return createElement('button', { 'data-testid': testID, disabled, 'data-secondary': secondary }, children);
  },
}));
vi.mock('../../src/services/haptics', () => ({ haptics: { success: vi.fn(), warning: vi.fn() } }));
vi.mock('../../src/services/workouts', () => ({
  workouts: {
    addExampleRides: () => addExampleRides(),
    getPermissions: () => getPermissions(),
    start: (options: WorkoutOptions) => start(options),
    refreshHealth: (id: string) => refreshHealth(id),
    retryHealthSave: (id: string) => retryHealthSave(id),
    stop: (id?: string) => stop(id),
  },
}));
vi.mock('../../src/services/monitor-preferences', () => ({
  useMonitorPreferences: () => ({
    preferences,
    ready: true,
    error: settingsError,
    resetOffered,
    resetSettings,
    prepareRecording,
    setWorkoutOptions: (update: (previous: WorkoutOptions) => WorkoutOptions) => {
      preferences = { ...preferences, workoutOptions: update(preferences.workoutOptions) };
    },
  }),
}));
vi.mock('../../src/services/workout-context', () => ({
  useWorkoutIdentity: () => ({ phase: state.phase, id: state.id }),
  useWorkout: () => ({
    state,
    options: preferences.workoutOptions,
    ready: true,
    optionsReady: true,
    busy: false,
    permissions: permissionStatus,
    permissionsError,
    refreshPermissions,
    error: null,
    clearError: () => {},
    run,
  }),
}));
vi.mock('../../src/services/session-context', () => ({
  useSession: () => ({
    hz: 4,
    adapter: { kind: platformState.os === 'web' ? 'web' : 'native', disconnect },
    display: bikeConnected ? 'live' : bikeHeld ? 'held' : 'unavailable',
    faultCode: bikeFault,
    state: {
      status: bikeConnected ? 'connected' : bikeReconnecting ? 'reconnecting' : 'idle',
      deviceId: 'synthetic-bike',
      deviceName: 'Example bike',
    },
    devices: [],
    busy: bikeBusy,
    error: null,
    scan,
    run,
  }),
}));

const explanation =
  'Phone-recorded rides cannot be saved to Apple Health on this device. This ride will stay in Power Log.';
function settings() {
  localState.index = 0;
  toggles.clear();
  return renderToStaticMarkup(createElement(SettingsScreen));
}
function ride() {
  localState.index = 0;
  buttons.clear();
  controls.clear();
  return renderToStaticMarkup(
    createElement(
      RideControlsProvider,
      null,
      createElement(RideActionBar),
      createElement(RideStatus, { connection: createElement(ConnectionSettings) }),
    ),
  );
}

beforeEach(() => {
  localState.enabled = false;
  localState.index = 0;
  localState.values.clear();
  localState.writes = [];
  run.mockImplementation(async operation => operation({ current: () => true, signal: new AbortController().signal }));
  vi.stubGlobal('__DEV__', false);
  vi.clearAllMocks();
  platformState.os = 'ios';
  bikeConnected = true;
  bikeReconnecting = false;
  bikeHeld = false;
  bikeBusy = false;
  bikeFault = 0;
  clock.twentyFourHour = null;
  permissionStatus = null;
  permissionsError = null;
  preferences = defaultMonitorPreferences();
  preferences.sampleHz = 4;
  prepareRecording.mockImplementation(async () => preferences);
  settingsError = null;
  resetOffered = false;
  state = {
    ...unavailableWorkoutState,
    supported: true,
    capabilities: {
      phoneWorkout: true,
      watchWorkout: true,
      phoneHealth: true,
      watchHealth: true,
      healthProvider: 'appleHealth',
      gps: true,
      foregroundOnly: false,
    },
    watch: { installed: true },
  };
});

describe('owner-aware recording controls', () => {
  it.each(['ios', 'android', 'web'])('offers local Finish for an interrupted ride on %s', platform => {
    platformState.os = platform;
    state = {
      ...state,
      id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
      phase: 'interrupted',
      useWatch: true,
      healthKitState: 'unknown',
      pendingAction: null,
    };
    const markup = ride();
    expect(controls.get('Finish')?.disabled).toBe(false);
    expect(controls.get('Pause')?.disabled).toBe(true);
    expect(controls.get('Lap')?.disabled).toBe(true);
    expect(controls.has('Retry')).toBe(false);
    expect(markup).toContain('Finish or discard ride');
  });
  it('prevents a foreign browser tab from finishing the active ride', () => {
    state = {
      ...state,
      id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
      phase: 'running',
      foreignOwner: 'anotherBrowserTab',
    };
    ride();
    expect(controls.get('Finish')?.disabled).toBe(true);
  });
  it('keeps Find bike primary when the selected Watch is not installed and explains the disabled Start', () => {
    bikeConnected = false;
    state.watch.installed = false;
    preferences.workoutOptions = { indoor: true, useWatch: true, saveToHealth: false, recordGPS: false };
    const markup = ride();
    expect(controls.get('setup-start-ride')).toEqual({ disabled: true, secondary: true });
    expect(controls.get('setup-find-bike')).toEqual({ disabled: false, secondary: false });
    expect(markup).toContain(presentationFailure('bikeRequired').text);
    bikeConnected = true;
    expect(ride()).toContain(escaped(presentationFailure('watchNotInstalled').text));
  });
  it.each(['ios', 'android', 'web'])(
    'puts Find bike in the empty Ride card and opens discovery on %s',
    async platform => {
      platformState.os = platform;
      bikeConnected = false;
      const markup = renderToStaticMarkup(createElement(DashboardScreen));
      expect(markup).toContain('<section><div><div>No bike connected</div>');
      expect(markup).toContain('Turn on your bike, then find it to see live readings.');
      expect(markup).toContain('Works with CYC X6 and X12 controllers over Bluetooth.');
      expect(controls.get('Find bike')).toEqual({ secondary: false, disabled: false });
      buttons.get('Find bike')!();
      await vi.waitFor(() => expect(scan).toHaveBeenCalledTimes(1));
      state = { ...state, id: 'active-ride', phase: 'running' };
      expect(renderToStaticMarkup(createElement(DashboardScreen))).not.toContain('No bike connected');
    },
  );

  it.each(['ios', 'android', 'web'])('requires a bike without recaps or healthy permission prose on %s', platform => {
    platformState.os = platform;
    bikeConnected = false;
    preferences.workoutOptions = { indoor: false, useWatch: false, saveToHealth: false, recordGPS: true };
    const markup = ride();
    for (const removed of [
      'This ride will record',
      'GPS route On',
      'Connection details',
      'Permissions',
      'No permission changes needed',
      'Checking permission readiness',
    ])
      expect(markup).not.toContain(removed);
    expect(markup).toContain('Ride settings');
    expect(controls.get('setup-start-ride')).toEqual({ disabled: true, secondary: true });
    expect(controls.get('setup-find-bike')).toEqual({ disabled: false, secondary: false });
    expect(markup).toContain(presentationFailure('bikeRequired').text);
    expect(controls.get('start-ride')?.secondary).toBe(true);
  });

  it('does not admit held readings before Start but preserves the active connection appearance', () => {
    bikeConnected = false;
    bikeReconnecting = true;
    bikeHeld = true;
    expect(ride()).toContain('Reconnecting');
    expect(controls.get('setup-start-ride')?.disabled).toBe(true);
    state = { ...state, id: 'active-ride', phase: 'running' };
    const active = ride();
    expect(active).toContain('Connected');
    expect(active).not.toContain('Reconnecting');
    expect(controls.get('Finish')?.disabled).toBe(false);
  });

  it('allows cancelling a pending connection before Start', async () => {
    bikeConnected = false;
    bikeReconnecting = true;
    bikeBusy = true;
    ride();
    expect(controls.get('Cancel connection')?.disabled).toBe(false);
    buttons.get('Cancel connection')!();
    await vi.waitFor(() => expect(disconnect).toHaveBeenCalledOnce());
  });

  it('keeps a failed permission status query visible and retryable even without optional sources', async () => {
    preferences.workoutOptions = { indoor: true, useWatch: false, saveToHealth: false, recordGPS: false };
    permissionsError = 'Could not check permissions.';
    const markup = ride();
    expect(markup).toContain(permissionsError);
    expect(controls.get('Check permissions')?.disabled).toBe(false);
    buttons.get('Check permissions')!();
    await vi.waitFor(() => expect(refreshPermissions).toHaveBeenCalledOnce());
  });

  it('closes setup before navigating to Ride settings', () => {
    localState.enabled = true;
    ride();
    buttons.get([...buttons.keys()].find(key => key.startsWith('Bike and ride setup.'))!)!();
    expect(ride()).toContain('Ride settings');
    buttons.get('Ride settings')!();
    expect(ride()).not.toContain('Ride settings');
  });

  it.each(['running', 'paused', 'preparing', 'interrupted'] as const)(
    'disables foreign-owner controls and stop admission while %s',
    phase => {
      state = {
        ...state,
        id: 'foreign-ride',
        phase,
        pendingAction: null,
        foreignOwner: 'anotherBrowserTab',
      } as WorkoutState;
      const markup = ride();
      expect(markup).toContain('Another browser tab is recording this ride. Use that tab to control it.');
      for (const label of ['Pause', 'Resume', 'Lap', 'Finish', 'Stop', 'Retry']) {
        if (controls.has(label)) expect(controls.get(label)?.disabled, label).toBe(true);
      }
      expect(markup).not.toContain('Waiting for the ride to respond…');
    },
  );

  it.each(['ios', 'android', 'web'])('keeps labelled controls in place through pause on %s', platform => {
    platformState.os = platform;
    state = { ...state, id: 'active-ride', phase: 'running' };
    expect(ride()).toContain('Running');
    expect(controls.has('Pause')).toBe(true);
    expect(controls.has('Lap')).toBe(true);
    state = { ...state, phase: 'paused' };
    expect(ride()).toContain('Paused');
    expect(controls.has('Resume')).toBe(true);
    expect(controls.get('Lap')?.disabled).toBe(true);
    expect(controls.has('Finish')).toBe(true);
    state = { ...state, phase: 'finishing', pendingAction: 'stop' };
    expect(ride()).toContain('Finishing');
  });

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
        expect(settingsMarkup).not.toContain(explanation);
        expect(rideMarkup).not.toContain(explanation);
        start.mockClear();
        buttons.get('setup-start-ride')!();
        await vi.waitFor(() =>
          expect(start).toHaveBeenLastCalledWith({
            indoor: true,
            useWatch,
            saveToHealth,
            recordGPS: false,
            sampleHz: 4,
            distanceSource: 'controller',
            batteryConsumptionSource: 'controller',
          }),
        );
        expect(preferences.workoutOptions.saveToHealth).toBe(saveToHealth);
      }
    },
  );

  it('does not start with stale committed settings after a failed save', async () => {
    const committed = defaultMonitorPreferences();
    const controller = new MonitorPreferencesController({
      load: async () => ({ preferences: committed }),
      save: async () => {
        throw new Error('This device is out of storage.');
      },
    });
    await controller.hydrate();
    controller.setSampleHz(8);
    preferences = controller.snapshot().preferences;
    prepareRecording.mockImplementation(controller.prepareRecording);
    const actions = new WorkoutActions(() => {});
    run.mockImplementation(operation => actions.run(operation, async () => {}, 'start'));
    ride();
    buttons.get('setup-start-ride')!();
    await vi.waitFor(() => expect(actions.snapshot().error).toContain('Your settings weren’t saved'));
    expect(actions.snapshot().error).toContain('out of storage');
    expect(start).not.toHaveBeenCalled();
    expect(committed.sampleHz).toBe(2);
  });

  it('starts with the committed battery source and retains it after the preference changes', async () => {
    preferences.batteryConsumptionSource = 'calculated';
    ride();
    buttons.get('setup-start-ride')!();
    await vi.waitFor(() =>
      expect(start).toHaveBeenCalledWith(expect.objectContaining({ batteryConsumptionSource: 'calculated' })),
    );
    preferences.batteryConsumptionSource = 'controller';
    expect(start.mock.lastCall![0].batteryConsumptionSource).toBe('calculated');
  });

  it.each([2, 4, 8] as const)('starts with committed %s Hz instead of a stale screen snapshot', async sampleHz => {
    preferences.sampleHz = sampleHz;
    preferences.distanceSource = 'controller';
    preferences.workoutOptions = { indoor: false, useWatch: true, saveToHealth: true, recordGPS: true };
    ride();
    buttons.get('setup-start-ride')!();
    await vi.waitFor(() =>
      expect(start).toHaveBeenCalledWith(expect.objectContaining({ sampleHz, distanceSource: 'controller' })),
    );
  });

  it('does not start if the action expires while waiting for a preference save', async () => {
    let committed!: () => void;
    prepareRecording.mockImplementation(
      () =>
        new Promise(resolve => {
          committed = () => resolve(preferences);
        }),
    );
    let current = true;
    run.mockImplementation(operation => operation({ current: () => current, signal: new AbortController().signal }));
    ride();
    buttons.get('setup-start-ride')!();
    await vi.waitFor(() => expect(prepareRecording).toHaveBeenCalledOnce());
    current = false;
    committed();
    await Promise.resolve();
    expect(start).not.toHaveBeenCalled();
  });

  it('only describes local removal in the Discard confirmation', () => {
    localState.enabled = true;
    state = { ...state, id: 'discard-ride', phase: 'running' };
    ride();
    buttons.get('Finish')!();
    ride();
    buttons.get('Discard ride')!();
    const markup = ride();
    expect(markup).toContain('Discarding permanently removes this ride from Power Log and ends recording.');
    expect(markup).not.toContain('Health cleanup');
    expect(markup).not.toContain('Health sensor samples');
  });

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

  it('tells the rider what to do about a controller fault', () => {
    bikeFault = 23;
    const markup = renderToStaticMarkup(createElement(DashboardScreen));
    expect(markup).toContain(escaped("Your controller reports fault 23. Check the bike's display."));
    expect(markup).not.toContain('Controller fault');
    bikeFault = 0;
    expect(renderToStaticMarkup(createElement(DashboardScreen))).not.toContain('reports fault');
  });

  it('explains in About where rides are kept and how far readings can be trusted', () => {
    const markup = settings();
    expect(markup).toContain('Rides are stored on this device. Export a ride to keep a copy elsewhere.');
    expect(markup).toContain(
      'Readings come from your controller and sensors and can be inaccurate; battery use can read low. Set up before you ride, and don’t use the app while moving.',
    );
    expect(markup).toContain('Third-party notices');
  });

  it('offers Reset settings only for settings that cannot be read', () => {
    settingsError = "Power Log couldn't read your saved settings. Reset settings to use the defaults.";
    resetOffered = true;
    const unreadable = settings();
    expect(unreadable).toContain('Settings unavailable');
    expect(unreadable).toContain('Reset settings');
    buttons.get('Reset settings')!();
    expect(resetSettings).toHaveBeenCalledTimes(1);
    settingsError = 'Your settings weren’t saved. Disk full';
    resetOffered = false;
    buttons.clear();
    expect(settings()).not.toContain('Reset settings');
  });

  it('shows GPS settings only when GPS is supported', () => {
    settings();
    expect(toggles.has('Record GPS route')).toBe(true);
    state.capabilities = { ...state.capabilities, gps: false };
    settings();
    expect(toggles.has('Record GPS route')).toBe(false);
  });

  it.each(failures.bridge.snapshot.cases.filter(({ wire }) => wire.failure))(
    'shows the native failure $name only as shared plain text',
    ({ wire, nativeDetail }) => {
      const idle = rideSnapshots.cases.find(value => value.platform === 'ios' && value.name === 'idle')!.wire;
      state = normalizeWorkoutState({
        ...idle,
        phase: 'failed',
        error: nativeDetail,
        recoveryMessage: nativeDetail,
        failure: { code: wire.failure!.code, message: nativeDetail },
      } as unknown as WorkoutState);
      const markup = ride();
      expect(markup).toContain(escaped(presentationFailure(wire.failure!.code).text));
      expect(markup).not.toContain(nativeDetail!);
      for (const forbidden of failures.forbidden) expect(markup).not.toContain(forbidden);
    },
  );
});

let historyRecord = metadata('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 1);
let repairingId: string | null = null;
let refreshVersion = 0;
vi.mock('../../src/features/history/history-controller', () => ({
  recordKey: (id: string) => `record:${id}`,
  useHistory: () => ({
    workout: { state: unavailableWorkoutState, busy: false, run },
    archiveState: new Map(),
    pending: () => false,
    preferences,
    records: [historyRecord],
    repairingId,
    setRepairingId: (update: string | null | ((value: string | null) => string | null)) => {
      repairingId = typeof update === 'function' ? update(repairingId) : update;
    },
    setRefreshVersion: (update: (version: number) => number) => {
      refreshVersion = update(refreshVersion);
    },
    resource: {},
    record: historyRecord,
    selectedId: historyRecord.id,
    csv: null,
    action: () => () => {},
  }),
}));
vi.mock('../../src/components/route-preview', () => ({ RoutePreview: () => null }));
vi.mock('../../src/features/history/csv-ride-details', () => ({ CsvRideDetails: () => null }));
vi.mock('../../src/services/ride-export', () => ({ exportRecording: vi.fn(), exportRide: vi.fn() }));
afterEach(() => {
  vi.clearAllTimers();
  vi.useRealTimers();
  localState.enabled = false;
});
function held<T>() {
  let resolve!: (value: T) => void;
  let reject!: (error: Error) => void;
  const promise = new Promise<T>((yes, no) => {
    resolve = yes;
    reject = no;
  });
  return { promise, resolve, reject };
}
function timedActions() {
  vi.useFakeTimers();
  const actions = new WorkoutActions(() => {});
  const refresh = vi.fn(async () => {});
  run.mockImplementation(operation => actions.run(operation, refresh));
  return { actions, refresh };
}
function history(id: string, healthRetryAvailable = false) {
  historyRecord = {
    ...metadata(id, 1),
    watchEnabled: false,
    healthRetryAvailable,
  };
  buttons.clear();
  controls.clear();
  return renderToStaticMarkup(createElement(SavedRides, { detail: true }));
}
it.each([true, false])('dates a History ride with the device clock when 24-hour time is %s', twentyFourHour => {
  clock.twentyFourHour = twentyFourHour;
  const markup = history('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa');
  expect(markup).toContain(formatRideDate(historyRecord.startedAt, { twentyFourHour }));
});
it('notes once in the History list that rides stay on this device', () => {
  const markup = renderToStaticMarkup(createElement(SavedRides, { detail: false }));
  expect(markup.split('Rides are stored on this device. Export a ride to keep a copy elsewhere.')).toHaveLength(2);
});
it.each([false, true])('routes History Health action with retry eligibility %s', async retry => {
  const id = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa';
  repairingId = null;
  history(id, retry);
  buttons.get(retry ? 'Retry Health save' : 'Refresh Health data')!();
  await Promise.resolve();
  expect(retryHealthSave.mock.calls).toEqual(retry ? [[id]] : []);
  expect(refreshHealth.mock.calls).toEqual(retry ? [] : [[id]]);
});
it.each(['another ride', 'same ride'])(
  'keeps a newer History refresh for %s after an expired refresh settles',
  async destination => {
    const { actions, refresh } = timedActions();
    const first = held<void>(),
      second = held<void>();
    refreshHealth
      .mockImplementationOnce(() => first.promise)
      .mockImplementationOnce(() => (destination === 'same ride' ? first.promise : second.promise));
    repairingId = null;
    refreshVersion = 0;
    const a = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa';
    const b = destination === 'same ride' ? a : 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb';
    history(a);
    buttons.get('Refresh Health data')!();
    await vi.advanceTimersByTimeAsync(0);
    expect(history(a)).toContain('Refreshing Health…');
    expect(controls.get('Delete ride')?.disabled).toBe(true);
    await vi.advanceTimersByTimeAsync(15000);
    expect(actions.snapshot().busy).toBe(false);
    history(a);
    expect(controls.get('Delete ride')?.disabled).toBe(false);
    expect(buttons.has('Refresh Health data')).toBe(true);
    history(b);
    buttons.get('Refresh Health data')!();
    await vi.advanceTimersByTimeAsync(0);
    first.resolve();
    await vi.advanceTimersByTimeAsync(0);
    if (destination === 'another ride') {
      expect(actions.snapshot().busy).toBe(true);
      expect(history(b)).toContain('Refreshing Health…');
      expect(controls.get('Delete ride')?.disabled).toBe(true);
      expect(refreshVersion).toBe(0);
      expect(refresh).not.toHaveBeenCalled();
      second.resolve();
      await vi.advanceTimersByTimeAsync(0);
    }
    expect(actions.snapshot().busy).toBe(false);
    history(b);
    expect(controls.get('Delete ride')?.disabled).toBe(false);
    expect(refreshVersion).toBe(1);
    expect(refresh).toHaveBeenCalledOnce();
    expect(refreshHealth.mock.calls.map(([id]) => id)).toEqual([a, b]);
  },
);
it.each(['resolve', 'reject'] as const)(
  'ignores an expired example-ride %s during a newer Settings flight',
  async outcome => {
    const { actions } = timedActions();
    localState.enabled = true;
    vi.stubGlobal('__DEV__', true);
    const first = held<number>(),
      second = held<number>();
    addExampleRides.mockImplementationOnce(() => first.promise).mockImplementationOnce(() => second.promise);
    settings();
    buttons.get('Add example rides')!();
    await vi.advanceTimersByTimeAsync(15000);
    settings();
    buttons.get('Add example rides')!();
    await vi.advanceTimersByTimeAsync(0);
    localState.writes = [];
    if (outcome === 'resolve') first.resolve(1);
    else first.reject(new Error('Late example failure'));
    await vi.advanceTimersByTimeAsync(0);
    expect(actions.snapshot().busy).toBe(true);
    expect(localState.writes).toEqual([]);
    second.resolve(2);
    await vi.advanceTimersByTimeAsync(0);
    expect(settings()).toContain('2 example rides added to History.');
  },
);

it.each(['resolve', 'reject'] as const)(
  'ignores an expired permission refresh %s during a later flight',
  async outcome => {
    const { actions } = timedActions();
    localState.enabled = true;
    const context = await vi.importActual<typeof import('../../src/services/workout-context')>(
      '../../src/services/workout-context',
    );
    let controller!: ReturnType<typeof context.useWorkout>;
    function Probe() {
      controller = context.useWorkout();
      return null;
    }
    renderToStaticMarkup(createElement(context.WorkoutProvider, null, createElement(Probe)));
    const first = held<import('../../src/core/workouts').WorkoutPermissionStatus>();
    getPermissions.mockImplementationOnce(() => first.promise);
    const expired = actions.run(
      flight => controller.refreshPermissions(flight.current),
      async () => {},
    );
    await vi.advanceTimersByTimeAsync(15000);
    await expired;
    const second = held<void>();
    const pending = actions.run(
      () => second.promise,
      async () => {},
    );
    await vi.advanceTimersByTimeAsync(0);
    localState.writes = [];
    if (outcome === 'resolve')
      first.resolve({
        health: { available: false, writeAuthorization: {} },
        location: 'denied',
        locationServicesEnabled: false,
        locationAccuracyAuthorization: 'unknown',
      });
    else first.reject(new Error('Late permission failure'));
    await vi.advanceTimersByTimeAsync(0);
    expect(actions.snapshot().busy).toBe(true);
    expect(localState.writes).toEqual([]);
    second.resolve();
    await pending;
  },
);
