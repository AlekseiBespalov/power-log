import { createElement, useEffect } from 'react';
import { createRoot } from 'react-dom/client';
import { ExpoRoot, Tabs, useRouter } from 'expo-router';
import { View } from 'react-native';
import { GestureHandlerRootView } from 'react-native-gesture-handler';
import { SafeAreaProvider } from 'react-native-safe-area-context';
import type {
  ChartHeadView,
  DeviceInfoView,
  HistoryRow,
  ImportedRideView,
  PlotView,
  SettingsView,
} from '../../modules/power-log';
import { createPowerLogClient } from '../../modules/power-log/src/client';
import { FakeExportEntries } from '../../modules/power-log/src/testing/fake-exports';
import { fakeTransport } from '../../modules/power-log/src/testing/fake-transport';
import { AppHeader } from '../../src/components/app-header';
import type { UiEnv } from '../../src/components/screen-model';
import { createUiPlatform } from '../../src/components/ui-platform';
import { UiEnvProvider } from '../../src/components/use-screen-model';
import { AppShellModel } from '../../src/features/shell/shell-model';
import * as HistoryLayout from '../../src/app/sessions/_layout';
import * as HistoryIndex from '../../src/app/sessions/index';
import * as HistoryRide from '../../src/app/sessions/ride';

const transport = fakeTransport();
const client = createPowerLogClient(transport);
const ui = createUiPlatform();
const entries = new FakeExportEntries();
const env: UiEnv = { client, ui, shell: new AppShellModel(client, ui, 'web'), exports: entries, diagnostics: entries };
const count = Number(sessionStorage.getItem('history-router-count') ?? 2);
const firstStartUs = Date.UTC(2026, 8, 6, 11, 30) * 1000;
const rides: HistoryRow[] = Array.from({ length: count }, (_, index) => ({
  rideId: `ride-${index}`,
  startedUtcUs: firstStartUs + index * 60_000_000,
  elapsedUs: 2_750_000,
  phase: 'completed' as const,
  interrupted: false,
  indoor: true,
  example: false,
  health: null,
  open: { enabled: true, reason: null },
  delete: { enabled: true, reason: null },
})).reverse();
const plot: PlotView = {
  revision: 1,
  rangeUs: { startUs: 0, endUs: 2_750_000 },
  slotUs: 1_000_000,
  series: [
    {
      metric: 'humanPowerW',
      segment: [1, 1, 1],
      firstUs: [0, 1_250_000, 2_750_000],
      first: [100, 604, 200],
      minUs: [0, 1_250_000, 2_750_000],
      min: [100, 604, 200],
      maxUs: [0, 1_250_000, 2_750_000],
      max: [100, 604, 200],
      lastUs: [0, 1_250_000, 2_750_000],
      last: [100, 604, 200],
    },
  ],
};
const head: ChartHeadView = {
  revision: 1,
  rangeUs: plot.rangeUs,
  live: false,
  originMonoUs: null,
  metrics: ['humanPowerW'],
  catchingUp: false,
};
const device: DeviceInfoView = {
  platform: 'web',
  appVersion: '0.7.0',
  sdkLevel: null,
  uses24h: true,
  locale: 'en-GB',
  capabilities: { recording: true, bluetooth: true, gps: false, health: null, watch: false, diagnosticLog: false },
  storage: null,
  developerTools: false,
};
let listed: string[] = [];
let revision = 1;
transport.answer('history.page', ({ after }) => {
  const start = after === null ? 0 : Number(after);
  const rows = rides.slice(start, start + 50);
  listed = rows.map(row => row.rideId);
  return { ok: true, value: { rows, next: start + 50 < rides.length ? String(start + 50) : null } };
});
const preset = { numbers: [], charts: ['humanPowerW' as const], range: 'all' as const, webColumns: 2 };
const settings: SettingsView = {
  ride: {
    sampleRate: '2',
    distance: 'auto',
    battery: 'controller',
    indoor: true,
    useWatch: false,
    saveToHealth: false,
    recordGps: null,
    syncBeeps: false,
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
};
transport.answer('charts.plot', () => ({ ok: true, value: plot }));
transport.answer('charts.latest', () => ({ ok: true, value: { revision: 1, readings: [] } }));
transport.answer('charts.statistics', () => ({ ok: true, value: { revision: 1, statistics: [], nextUs: null } }));
transport.emit('settings.state', {}, settings);
const lateCsv: ImportedRideView = {
  importId: 'late',
  name: 'late.csv',
  summary: {
    samples: 0,
    durationUs: 0,
    gaps: 0,
    clockAdjustments: 0,
    averagePowerW: null,
    peakPowerW: null,
    averageCadenceRpm: null,
    riderWorkWh: null,
  },
};
let heldCsv: (() => void) | null = null;
transport.answer('imports.open', () => new Promise(resolve => (heldCsv = () => resolve({ ok: true, value: lateCsv }))));
transport.answer('imports.close', () => ({ ok: true, value: null }));
for (const ride of rides) transport.emit('charts.head', { source: { kind: 'ride', rideId: ride.rideId } }, head);
transport.emit('device.info', {}, device);
transport.emit('history.list', {}, { revision });
env.shell.activate();

let navigate: ReturnType<typeof useRouter>;
function RouterHandle() {
  const router = useRouter();
  useEffect(() => {
    navigate = router;
  }, [router]);
  return null;
}
function Root() {
  return (
    <GestureHandlerRootView style={{ flex: 1 }}>
      <SafeAreaProvider>
        <UiEnvProvider value={env}>
          <AppHeader />
          <RouterHandle />
          <Tabs screenOptions={{ headerShown: false, animation: 'none' }} tabBar={() => null} />
        </UiEnvProvider>
      </SafeAreaProvider>
    </GestureHandlerRootView>
  );
}
let failing = false;
function FailingSettings() {
  if (failing) throw new Error('Settings failed to render');
  return <View testID="settings-placeholder" />;
}
const routes: Record<string, unknown> = {
  './_layout.tsx': { default: Root },
  './settings.tsx': { default: FailingSettings, ErrorBoundary: HistoryRide.ErrorBoundary },
  './index.tsx': { default: () => <View /> },
  './sessions/_layout.tsx': HistoryLayout,
  './sessions/index.tsx': HistoryIndex,
  './sessions/ride.tsx': HistoryRide,
};
const context = Object.assign((key: string) => routes[key], {
  keys: () => Object.keys(routes),
  resolve: (key: string) => key,
  id: 'history-real-router',
});
export function routerReady() {
  return navigate !== undefined;
}
export function push(id: string) {
  navigate.push({ pathname: '/sessions/ride', params: { id } });
}
export function failSettings(value: boolean) {
  failing = value;
}
export function persistSeed(rides = 2) {
  sessionStorage.setItem('history-router-count', String(rides));
}
export function plottedRide() {
  const plots = transport.calls.filter(call => call.op === 'charts.plot');
  return (plots.at(-1)?.input as { source: { rideId: string } } | undefined)?.source.rideId ?? null;
}
export function listedRides() {
  return listed;
}
export function pageCalls() {
  return transport.calls.filter(call => call.op === 'history.page').length;
}
export function releaseCsv() {
  heldCsv?.();
  heldCsv = null;
}
export function closedImports() {
  return transport.calls
    .filter(call => call.op === 'imports.close')
    .map(call => (call.input as { importId: string }).importId);
}
export function moveHistory() {
  transport.emit('history.list', {}, { revision: ++revision });
}
document.body.style.cssText = 'margin:0;background:#0b0d10';
const container = document.createElement('div');
container.style.cssText = 'height:100vh;display:flex';
document.body.append(container);
createRoot(container).render(createElement(ExpoRoot, { context, location: new URL(location.href) }));
Object.assign(window, {
  historyRouterTests: {
    push,
    releaseCsv,
    closedImports,
    persistSeed,
    routerReady,
    failSettings,
    plottedRide,
    listedRides,
    pageCalls,
    moveHistory,
  },
});
