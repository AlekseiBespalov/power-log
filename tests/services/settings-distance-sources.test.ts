import { describe, expect, it, vi } from 'vitest';
import { DISTANCE_SOURCE_LABELS } from '../../src/core/distance';
import { distanceSourceChoices } from '../../src/features/settings/settings-screen';

vi.mock('react-native', () => ({ Platform: { OS: 'web' } }));
vi.mock('expo-router', () => ({}));
vi.mock('react-native-safe-area-context', () => ({}));
vi.mock('../../src/components/app-shell', () => ({}));
vi.mock('../../src/components/modal-dialog', () => ({}));
vi.mock('../../src/components/stable-label', () => ({}));
vi.mock('../../src/components/toggle', () => ({}));
vi.mock('../../src/components/ui', () => ({}));
vi.mock('../../src/services/monitor-preferences', () => ({}));
vi.mock('../../src/services/workout-context', () => ({}));
vi.mock('../../src/services/workouts', () => ({}));

describe('settings distance sources', () => {
  it('offers only Auto and controller distance in the browser', () => {
    expect(distanceSourceChoices('web')).toEqual([
      { value: 'auto', label: 'Auto' },
      { value: 'controller', label: 'Controller estimate' },
    ]);
  });
  it('retains phone GPS on Android and all recorded producers on iOS', () => {
    expect(distanceSourceChoices('android').map(choice => choice.value)).toEqual(['auto', 'gps:phone', 'controller']);
    expect(distanceSourceChoices('ios')).toEqual(
      Object.entries(DISTANCE_SOURCE_LABELS).map(([value, label]) => ({ value, label })),
    );
  });
});
