import { AppState, Platform } from 'react-native';

export function appIsForeground(): boolean {
  if (Platform.OS !== 'web') return AppState.currentState === 'active';
  return (
    AppState.currentState !== 'background' &&
    AppState.currentState !== 'inactive' &&
    (typeof document === 'undefined' || document.visibilityState !== 'hidden')
  );
}

export function subscribeAppVisibility(listener: (active: boolean) => void): () => void {
  const receive = () => listener(appIsForeground());
  const subscription = AppState.addEventListener('change', receive);
  const browser = Platform.OS === 'web' && typeof document !== 'undefined' ? document : null;
  browser?.addEventListener('visibilitychange', receive);
  return () => {
    subscription.remove();
    browser?.removeEventListener('visibilitychange', receive);
  };
}

export function subscribeAppBackground(listener: () => void): () => void {
  const subscription = AppState.addEventListener('change', state => {
    if (state === 'background') listener();
  });
  const browser = Platform.OS === 'web' && typeof document !== 'undefined' ? document : null;
  const hidden = () => {
    if (browser?.visibilityState === 'hidden') listener();
  };
  browser?.addEventListener('visibilitychange', hidden);
  return () => {
    subscription.remove();
    browser?.removeEventListener('visibilitychange', hidden);
  };
}
