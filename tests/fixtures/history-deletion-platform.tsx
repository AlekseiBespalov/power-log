import { useEffect, useSyncExternalStore } from 'react';

export const useSafeAreaInsets = () => ({ top: 0, bottom: 0, left: 0, right: 0 });
export function useFocusEffect(effect: () => void | (() => void)) {
  useEffect(effect, [effect]);
}
export const useIsFocused = () => true;
export const RoutePreview = () => null;

let route = { pathname: '/sessions', id: undefined as string | undefined };
const routeListeners = new Set<() => void>();
const subscribeRoute = (listener: () => void) => {
  routeListeners.add(listener);
  return () => {
    routeListeners.delete(listener);
  };
};
const routeSnapshot = () => route;
export function usePathname() {
  return useSyncExternalStore(subscribeRoute, routeSnapshot).pathname;
}
export function useGlobalSearchParams() {
  return useSyncExternalStore(subscribeRoute, routeSnapshot);
}
const router = {
  push(value: { pathname: string; params: { id: string } }) {
    route = { pathname: value.pathname, id: value.params.id };
    for (const listener of routeListeners) listener();
  },
  dismissTo(pathname: string) {
    route = { pathname, id: undefined };
    for (const listener of routeListeners) listener();
  },
};
export const useRouter = () => router;
