import { AppState, Platform } from 'react-native';

export function appIsForeground(): boolean {
  if (Platform.OS !== 'web') return AppState.currentState === 'active';
  return AppState.currentState !== 'background' && AppState.currentState !== 'inactive'
    && (typeof document === 'undefined' || document.visibilityState !== 'hidden');
}

export function subscribeAppVisibility(listener: (active: boolean) => void): () => void {
  const receive = () => listener(appIsForeground());
  const subscription = AppState.addEventListener('change', receive);
  const browser = Platform.OS === 'web' && typeof document !== 'undefined' ? document : null;
  browser?.addEventListener('visibilitychange', receive);
  return () => { subscription.remove(); browser?.removeEventListener('visibilitychange', receive); };
}
