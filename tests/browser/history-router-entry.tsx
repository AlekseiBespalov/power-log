import { createElement, useEffect } from 'react';
import { createRoot } from 'react-dom/client';
import { ExpoRoot, Tabs, usePathname, useRouter } from 'expo-router';
import { View } from 'react-native';
import { GestureHandlerRootView } from 'react-native-gesture-handler';
import { SafeAreaProvider } from 'react-native-safe-area-context';
import { WorkoutProvider, useWorkout } from '../../src/services/workout-context';
import { AppHeader } from '../../src/components/app-header';
import * as HistoryLayout from '../../src/app/sessions/_layout';
import * as HistoryIndex from '../../src/app/sessions/index';
import * as HistoryRide from '../../src/app/sessions/ride';
import * as archive from './history-actions-harness';
import { seed, metadata } from '../fixtures/history-deletion-backend';

export * from './history-actions-harness';
let navigate: ReturnType<typeof useRouter>;
function Evidence() {
  const workout = useWorkout();
  const pathname = usePathname();
  const router = useRouter();
  useEffect(() => {
    navigate = router;
  }, [router]);
  return createElement(
    'output',
    { 'data-testid': 'catalog-state' },
    JSON.stringify({ ids: workout.records.map(row => row.id), loading: workout.catalogLoading, pathname }),
  );
}
function Root() {
  return (
    <GestureHandlerRootView style={{ flex: 1 }}>
      <SafeAreaProvider>
        <WorkoutProvider>
          <AppHeader />
          <Evidence />
          <Tabs screenOptions={{ headerShown: false, animation: 'none' }} tabBar={() => null} />
        </WorkoutProvider>
      </SafeAreaProvider>
    </GestureHandlerRootView>
  );
}
const routes: Record<string, unknown> = {
  './_layout.tsx': { default: Root },
  './settings.tsx': { default: () => <View /> },
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
export function back() {
  navigate.back();
}
export function toList() {
  navigate.dismissTo('/sessions');
}
export function persistSeed(count = 2) {
  sessionStorage.setItem('history-router-count', String(count));
}
const count = Number(sessionStorage.getItem('history-router-count') ?? 2);
seed(Array.from({ length: count }, (_, index) => metadata(`ride-${index}`, index)));
document.body.style.cssText = 'margin:0;background:#0b0d10';
const container = document.createElement('div');
container.style.cssText = 'height:100vh;display:flex';
document.body.append(container);
createRoot(container).render(createElement(ExpoRoot, { context, location: new URL(location.href) }));
Object.assign(window, { historyRouterTests: { ...archive, push, back, toList, persistSeed, routerReady } });
