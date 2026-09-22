import { Platform } from 'react-native';

type HapticsModule = typeof import('expo-haptics');
let loading: Promise<HapticsModule | null> | undefined;
function load() {
  return loading ??= Platform.OS === 'web' ? Promise.resolve(null) : import('expo-haptics').catch(() => null);
}
const fire = (run: (module: HapticsModule) => Promise<void>) => { void load().then(module => module && run(module)).catch(() => {}); };

/** Tactile confirmation for user actions only; never driven by held or synthetic values. */
export const haptics = {
  success: () => fire(module => module.notificationAsync(module.NotificationFeedbackType.Success)),
  warning: () => fire(module => module.notificationAsync(module.NotificationFeedbackType.Warning)),
  impact: (style: 'light' | 'medium' = 'medium') => fire(module => module.impactAsync(style === 'light' ? module.ImpactFeedbackStyle.Light : module.ImpactFeedbackStyle.Medium)),
  selection: () => fire(module => module.selectionAsync()),
};
