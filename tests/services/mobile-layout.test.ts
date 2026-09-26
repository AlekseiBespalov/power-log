import { createElement } from 'react';
import { createRequire } from 'node:module';
import { describe, expect, it, vi } from 'vitest';
const { renderToStaticMarkup } = createRequire(import.meta.url)('react-dom/server') as { renderToStaticMarkup: (node: import('react').ReactNode) => string };
async function render(os: string) {
  native.os = os;
  vi.resetModules();
  const { default: RootLayout } = await import('../../src/app/_layout');
  return renderToStaticMarkup(createElement(RootLayout));
}

const native = vi.hoisted(() => ({ os: 'ios' }));
vi.mock('react-native', () => ({ Platform: { get OS() { return native.os; } }, View: 'view' }));
vi.mock('react', async original => ({ ...await original<typeof import('react')>(), useSyncExternalStore: () => true }));
vi.mock('expo-status-bar', () => ({ StatusBar: () => null }));
vi.mock('react-native-safe-area-context', () => ({ SafeAreaProvider: 'provider', SafeAreaView: 'safe-area' }));
vi.mock('expo-router', () => ({ Tabs: ({ tabBar }: { tabBar: () => unknown }) => createElement('tabs', null, tabBar() as never) }));
vi.mock('../../src/services/session-context', () => ({ SessionProvider: 'session-provider' }));
vi.mock('../../src/services/workout-context', () => ({ WorkoutProvider: 'workout-provider' }));
vi.mock('../../src/services/monitor-preferences', () => ({ MonitorPreferencesProvider: 'preferences-provider' }));
vi.mock('../../src/components/ui', () => ({ colors: { bg: '#0b0d10' } }));
vi.mock('../../src/components/tab-transition', () => ({ TabTransitionProvider: 'transition-provider' }));
vi.mock('../../src/components/app-header', () => ({ AppHeader: () => createElement('header', null, 'Web navigation') }));
vi.mock('../../src/components/tab-bar', () => ({ TabBar: () => createElement('nav', null, 'Ride History Settings') }));

describe('mobile navigation parity', () => {
  it('mounts the same bottom navigation and safe-area ownership on both native platforms', async () => {
    const iphone = await render('ios');
    expect(await render('android')).toBe(iphone);
    expect(iphone).toContain('<nav>Ride History Settings</nav>');
    expect(iphone).not.toContain('<header>');
    const web = await render('web');
    expect(web).toContain('<header>Web navigation</header>');
    expect(web).not.toContain('<nav>');
  });
});
