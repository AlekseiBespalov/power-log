import type { Href } from 'expo-router';
import { afterEach, expect } from 'vitest';
import { kit, type ApiFailure, type DeviceInfoView, type FailureCode, type Platform } from '../../modules/power-log';
import { createPowerLogClient } from '../../modules/power-log/src/client';
import { FakeExportEntries } from '../../modules/power-log/src/testing/fake-exports';
import { fakeTransport, type FakeTransport } from '../../modules/power-log/src/testing/fake-transport';
import type { ControlState, ScreenModel, UiEnv } from '../../src/components/screen-model';
import type { ConnectionPanelState } from '../../src/features/connection/connection-panel-model';
import type { RideControlsState } from '../../src/features/recording/ride-controls-model';
import type { HapticKind, UiPlatform } from '../../src/components/ui-platform';
import { AppShellModel } from '../../src/features/shell/shell-model';

export interface FakeUi extends UiPlatform {
  readonly haptics: HapticKind[];
  readonly navigations: Href[];
  readonly dismissals: Href[];
  readonly alerts: [string, string][];
  readonly settingsOpened: number;
}

export function fakeUi(kind: UiPlatform['kind']): FakeUi {
  const always = { current: () => true, subscribe: () => () => undefined };
  const never = { current: () => false, subscribe: () => () => undefined };
  let settingsOpened = 0;
  const ui = {
    kind,
    haptics: [] as HapticKind[],
    navigations: [] as Href[],
    dismissals: [] as Href[],
    alerts: [] as [string, string][],
    get settingsOpened() {
      return settingsOpened;
    },
    haptic: (haptic: HapticKind) => ui.haptics.push(haptic),
    openUrl: () => undefined,
    openAppSettings: () => {
      settingsOpened++;
    },
    alert: (title: string, message: string) => ui.alerts.push([title, message]),
    navigate: (href: Href) => ui.navigations.push(href),
    dismissTo: (href: Href) => ui.dismissals.push(href),
    foreground: always,
    background: never,
  };
  return ui;
}

export function device(platform: Platform, overrides: Partial<DeviceInfoView> = {}): DeviceInfoView {
  return {
    platform,
    appVersion: '0.7.0',
    sdkLevel: null,
    uses24h: true,
    locale: 'en-GB',
    capabilities: {
      recording: true,
      bluetooth: true,
      gps: false,
      health: null,
      watch: false,
      diagnosticLog: false,
    },
    storage: null,
    developerTools: false,
    ...overrides,
  };
}

export interface HolderEnv {
  transport: FakeTransport;
  ui: FakeUi;
  shell: AppShellModel;
  exports: FakeExportEntries;
  env: UiEnv;
}

export async function holderEnv(platform: Platform = 'web', info: DeviceInfoView = device(platform)) {
  const transport = fakeTransport();
  const client = createPowerLogClient(transport);
  const ui = fakeUi(platform === 'web' ? 'web' : 'native');
  const shell = new AppShellModel(client, ui, platform);
  shell.activate();
  await settle();
  transport.emit('device.info', {}, info);
  await settle();
  const exports = new FakeExportEntries();
  return {
    transport,
    ui,
    shell,
    exports,
    env: { client, ui, shell, exports, diagnostics: exports },
  } satisfies HolderEnv;
}

export const kitText = (code: FailureCode, platform: Platform = 'web') =>
  kit.textsFailure(code, null, platform, null).text;

export const settle = () => new Promise(resolve => setTimeout(resolve, 0));

export const failure = (code: ApiFailure['code'], occurrence: string | null = null, retryable = false): ApiFailure => ({
  code,
  retryable,
  occurrence,
  detail: null,
});

export function sent(transport: FakeTransport, op: string): unknown[] {
  return transport.calls.filter(call => call.op === op).map(call => call.input);
}

const exitProblems = new Set<string>();

afterEach(() => {
  const found = [...exitProblems];
  exitProblems.clear();
  expect(found, 'every waiting state has an enabled exit').toEqual([]);
});

const open = (control: ControlState | null | undefined) => control != null && (control.enabled || control.busy);

export function rideExits(state: RideControlsState): string[] {
  if (state.phase === 'preparing' && !open(state.stop)) return ['a preparing ride without an enabled Stop'];
  if (state.phase === 'interrupted' && !(state.reviewRide && open(state.finish)))
    return ['an interrupted ride without an enabled Finish or discard ride'];
  return [];
}

export function connectionExits(state: ConnectionPanelState): string[] {
  if (state.status === 'Searching…' && !open(state.stopSearch) && !open(state.cancelConnection))
    return ['a search without an enabled Stop search or Cancel connection'];
  if (state.status === 'Connecting…' && !open(state.cancelConnection))
    return ['a connect without an enabled Cancel connection'];
  return [];
}

export function checkExits<S>(model: ScreenModel<S>, problems: (state: S) => string[]): void {
  const check = () => problems(model.getSnapshot()).forEach(problem => exitProblems.add(problem));
  model.subscribe(check);
  check();
}
