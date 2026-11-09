import { router, type Href } from 'expo-router';
import { Alert, AppState, Linking, Platform } from 'react-native';
import { appIsForeground, subscribeAppBackground, subscribeAppVisibility } from './app-visibility';
import { haptics } from './haptics';
import type { Observable } from './screen-model';

export type HapticKind = 'success' | 'warning' | 'impact' | 'lightImpact' | 'selection';

export interface UiPlatform {
  readonly kind: 'native' | 'web';
  haptic(kind: HapticKind): void;
  openUrl(url: `https://${string}`): void;
  openAppSettings(): void;
  alert(title: string, message: string): void;
  navigate(href: Href): void;
  dismissTo(href: Href): void;
  readonly foreground: Observable<boolean>;
  readonly background: Observable<boolean>;
}

function observable(read: () => boolean, watch: (changed: () => void) => () => void): Observable<boolean> {
  return {
    current: read,
    subscribe(listener) {
      let last = read();
      return watch(() => {
        const next = read();
        if (next === last) return;
        last = next;
        listener(next);
      });
    },
  };
}

const inBackground = () =>
  Platform.OS === 'web'
    ? typeof document !== 'undefined' && document.visibilityState === 'hidden'
    : AppState.currentState === 'background';

export function createUiPlatform(): UiPlatform {
  return {
    kind: Platform.OS === 'web' ? 'web' : 'native',
    haptic(kind) {
      if (kind === 'lightImpact') haptics.impact('light');
      else if (kind === 'impact') haptics.impact();
      else haptics[kind]();
    },
    openUrl(url) {
      void Linking.openURL(url).catch(() => undefined);
    },
    openAppSettings() {
      void Linking.openSettings().catch(() => undefined);
    },
    alert(title, message) {
      Alert.alert(title, message);
    },
    navigate(href) {
      router.navigate(href);
    },
    dismissTo(href) {
      router.dismissTo(href);
    },
    foreground: observable(appIsForeground, changed => subscribeAppVisibility(changed)),
    background: observable(inBackground, changed => {
      const stopBackground = subscribeAppBackground(changed);
      const stopVisibility = subscribeAppVisibility(changed);
      return () => {
        stopBackground();
        stopVisibility();
      };
    }),
  };
}
