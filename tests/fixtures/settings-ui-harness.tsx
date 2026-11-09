import { createRoot } from 'react-dom/client';
import { SettingsScreen } from '../../src/features/settings/settings-screen';
import { createPowerLogClient } from '../../modules/power-log/src/client';
import { FakeExportEntries } from '../../modules/power-log/src/testing/fake-exports';
import { fakeTransport } from '../../modules/power-log/src/testing/fake-transport';
import type { UiEnv } from '../../src/components/screen-model';
import { UiEnvProvider } from '../../src/components/use-screen-model';
import type { UiPlatform } from '../../src/components/ui-platform';
import { AppShellModel } from '../../src/features/shell/shell-model';

const transport = fakeTransport();
const client = createPowerLogClient(transport);
const preset = { numbers: [], charts: [], range: 'seconds120' as const, webColumns: 2 };
transport.emit(
  'device.info',
  {},
  {
    platform: 'ios',
    appVersion: '0.7.0',
    sdkLevel: null,
    uses24h: true,
    locale: 'en-GB',
    capabilities: {
      recording: true,
      bluetooth: true,
      gps: true,
      health: 'appleHealth',
      watch: true,
      diagnosticLog: true,
    },
    storage: null,
    developerTools: false,
  },
);
transport.emit(
  'settings.state',
  {},
  {
    ride: {
      sampleRate: '4',
      distance: 'auto',
      battery: 'controller',
      indoor: false,
      useWatch: true,
      saveToHealth: true,
      recordGps: null,
    },
    app: {
      units: 'metric',
      temperature: 'celsius',
      fitPower: 'rider',
      views: { ride: preset, battery: preset, temperature: preset },
      activeView: 'ride',
      historyView: null,
      historyRange: 'all',
    },
    effective: null,
    state: 'ok',
    failure: null,
  },
);
transport.emit('diagnostics.state', {}, { trace: { state: 'idle', events: 0, stopReason: null } });
const steady = (value: boolean) => ({ current: () => value, subscribe: () => () => undefined });
const ui: UiPlatform = {
  kind: 'native',
  haptic: () => undefined,
  openUrl: () => undefined,
  openAppSettings: () => undefined,
  alert: () => undefined,
  navigate: () => undefined,
  dismissTo: () => undefined,
  foreground: steady(true),
  background: steady(false),
};
const entries = new FakeExportEntries();
const env: UiEnv = { client, ui, shell: new AppShellModel(client, ui, 'ios'), exports: entries, diagnostics: entries };
env.shell.activate();
export function mount() {
  const container = document.createElement('div');
  document.body.append(container);
  createRoot(container).render(
    <UiEnvProvider value={env}>
      <SettingsScreen />
    </UiEnvProvider>,
  );
}
