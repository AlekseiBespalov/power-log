import { afterEach, describe, expect, it, vi } from 'vitest';
import { appIsForeground } from '../../src/services/app-visibility';

const native = vi.hoisted(() => ({
  AppState: { currentState: null as string | null, addEventListener: vi.fn() },
  Platform: { OS: 'ios' },
}));
vi.mock('react-native', () => native);

afterEach(() => { native.AppState.currentState = null; native.Platform.OS = 'ios'; vi.unstubAllGlobals(); });

describe('app visibility', () => {
  it.each([null, 'unknown', 'background', 'inactive'])('does not start native presentation before an active lifecycle state (%s)', state => {
    native.AppState.currentState = state;
    expect(appIsForeground()).toBe(false);
    native.AppState.currentState = 'active';
    expect(appIsForeground()).toBe(true);
  });
  it('uses document visibility on web when the native lifecycle is unavailable', () => {
    native.Platform.OS = 'web';
    vi.stubGlobal('document', { visibilityState: 'visible' });
    expect(appIsForeground()).toBe(true);
    vi.stubGlobal('document', { visibilityState: 'hidden' });
    expect(appIsForeground()).toBe(false);
  });
});
