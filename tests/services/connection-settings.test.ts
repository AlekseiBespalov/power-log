import { createElement, type ReactNode } from 'react';
import { createRequire } from 'node:module';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { NativeState } from '../../src/core/types';
import { ConnectionSettings } from '../../src/features/dashboard/connection-settings';

const { renderToStaticMarkup } = createRequire(import.meta.url)('react-dom/server') as {
  renderToStaticMarkup: (node: ReactNode) => string;
};
const fixture = vi.hoisted(() => ({
  kind: 'native' as 'native' | 'web',
  state: { status: 'error' } as NativeState,
  error: null as string | null,
  errorCode: null as string | null,
  buttons: new Map<string, () => void>(),
  openSettings: vi.fn(async () => {}),
  run: vi.fn(async (operation: () => Promise<unknown>) => {
    await operation();
  }),
}));

vi.mock('react-native', () => ({
  Platform: { OS: 'ios' },
  Linking: { openSettings: () => fixture.openSettings() },
  View: 'div',
  Text: 'span',
}));
vi.mock('../../src/components/ui', () => ({
  colors: {},
  type: { caption: 12 },
  Heading: 'h2',
  Button: ({ children, onPress }: { children: ReactNode; onPress: () => void }) => {
    fixture.buttons.set(String(children), onPress);
    return createElement('button', null, children);
  },
}));
vi.mock('../../src/components/icon', () => ({ Icon: () => null }));
vi.mock('../../src/services/workout-context', () => ({ useWorkoutIdentity: () => ({ phase: 'idle' }) }));
vi.mock('../../src/services/session-context', () => ({
  useSession: () => ({
    state: fixture.state,
    display: fixture.state.status === 'connected' ? 'live' : 'unavailable',
    busy: false,
    adapter: { kind: fixture.kind },
    devices: [],
    error: fixture.error,
    errorCode: fixture.errorCode,
    run: fixture.run,
    clearError: () => {},
  }),
}));

function render() {
  fixture.buttons.clear();
  return renderToStaticMarkup(createElement(ConnectionSettings));
}

beforeEach(() => {
  vi.clearAllMocks();
  fixture.kind = 'native';
  fixture.state = { status: 'error' };
  fixture.error = 'Power Log can’t use Bluetooth.';
  fixture.errorCode = 'bluetoothDenied';
});

describe('bike connection setup', () => {
  it('opens the app settings for a denied Bluetooth permission', async () => {
    render();
    expect([...fixture.buttons.keys()]).toEqual(['Find bike', 'Open Settings', 'Dismiss']);
    fixture.buttons.get('Open Settings')!();
    await Promise.resolve();
    expect(fixture.run).toHaveBeenCalledOnce();
    expect(fixture.openSettings).toHaveBeenCalledOnce();
  });

  it('offers Open Settings only for a denied permission on the phone apps', () => {
    fixture.errorCode = 'bikeNotFound';
    render();
    expect(fixture.buttons.has('Open Settings')).toBe(false);
    expect(fixture.buttons.has('Dismiss')).toBe(true);
    fixture.errorCode = 'bluetoothDenied';
    fixture.kind = 'web';
    render();
    expect(fixture.buttons.has('Open Settings')).toBe(false);
  });

  it('explains missing controller speed under a connected bike whose firmware lacks it', () => {
    const note = 'Controller speed needs CYC X6 or X12 firmware with protocol 5.3.';
    fixture.error = null;
    fixture.errorCode = null;
    const bike = { deviceId: 'synthetic', deviceName: 'CYC bike', controllerModel: 'X6', firmwareLabel: '20240110' };
    fixture.state = { status: 'connected', ...bike, controllerProtocol: '5.2' };
    expect(render()).toContain(note);
    fixture.state = { status: 'connected', ...bike, controllerProtocol: '5.3' };
    expect(render()).not.toContain(note);
    fixture.state = { status: 'idle', ...bike, controllerProtocol: '5.2' };
    expect(render()).not.toContain(note);
  });
});
