import { Platform } from 'react-native';

type HapticsModule = typeof import('expo-haptics');
let loading: Promise<HapticsModule | null> | undefined;
function load() {
  return (loading ??= import('expo-haptics').catch(() => null));
}
const fire = (pattern: number | number[], run: (module: HapticsModule) => Promise<void>) => {
  if (Platform.OS === 'web') {
    try {
      if (typeof navigator !== 'undefined') navigator.vibrate?.(pattern);
    } catch {}
    return;
  }
  void load()
    .then(module => module && run(module))
    .catch(() => {});
};

export const haptics = {
  success: () => fire([10, 50, 20], module => module.notificationAsync(module.NotificationFeedbackType.Success)),
  warning: () => fire([20, 40, 20], module => module.notificationAsync(module.NotificationFeedbackType.Warning)),
  impact: (style: 'light' | 'medium' = 'medium') =>
    fire(style === 'light' ? 10 : 20, module =>
      module.impactAsync(style === 'light' ? module.ImpactFeedbackStyle.Light : module.ImpactFeedbackStyle.Medium),
    ),
  selection: () => fire(5, module => module.selectionAsync()),
};
