import { afterEach, beforeEach, expect, it, vi } from 'vitest';

const feedback = vi.hoisted(() => ({
  os: 'web',
  notificationAsync: vi.fn(async () => {}),
  impactAsync: vi.fn(async () => {}),
  selectionAsync: vi.fn(async () => {}),
}));
vi.mock('react-native', () => ({
  Platform: {
    get OS() {
      return feedback.os;
    },
  },
}));
vi.mock('expo-haptics', () => ({
  notificationAsync: feedback.notificationAsync,
  impactAsync: feedback.impactAsync,
  selectionAsync: feedback.selectionAsync,
  NotificationFeedbackType: { Success: 'success', Warning: 'warning' },
  ImpactFeedbackStyle: { Light: 'light', Medium: 'medium' },
}));
import { haptics } from '../../src/services/haptics';

beforeEach(() => {
  feedback.os = 'web';
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.clearAllMocks();
});
it('vibrates only at the existing action boundaries when browser feedback is supported', () => {
  const vibrate = vi.fn();
  vi.stubGlobal('navigator', { vibrate });
  expect(vibrate).not.toHaveBeenCalled();
  haptics.selection();
  haptics.impact('light');
  haptics.impact();
  haptics.success();
  haptics.warning();
  expect(vibrate.mock.calls).toEqual([[5], [10], [20], [[10, 50, 20]], [[20, 40, 20]]]);
  expect(feedback.selectionAsync).not.toHaveBeenCalled();
});
it.each([
  undefined,
  {},
  { vibrate: () => false },
  {
    vibrate: () => {
      throw new Error('Unavailable');
    },
  },
])('keeps actions usable when browser feedback is unavailable: %j', navigator => {
  vi.stubGlobal('navigator', navigator);
  expect(() => {
    haptics.selection();
    haptics.impact();
    haptics.success();
    haptics.warning();
  }).not.toThrow();
});
it.each(['ios', 'android'])('retains native feedback on %s', async os => {
  feedback.os = os;
  const vibrate = vi.fn();
  vi.stubGlobal('navigator', { vibrate });
  haptics.selection();
  haptics.impact('light');
  haptics.impact();
  haptics.success();
  haptics.warning();
  await vi.waitFor(() => expect(feedback.notificationAsync).toHaveBeenCalledTimes(2));
  expect(feedback.notificationAsync.mock.calls).toEqual([['success'], ['warning']]);
  expect(feedback.impactAsync.mock.calls).toEqual([['light'], ['medium']]);
  expect(feedback.selectionAsync).toHaveBeenCalledOnce();
  expect(vibrate).not.toHaveBeenCalled();
});
