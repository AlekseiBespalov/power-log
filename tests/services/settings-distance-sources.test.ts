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
vi.mock('../../src/services/device', () => ({ deviceAdapter: {} }));
vi.mock('../../src/services/files', () => ({}));
vi.mock('../../src/services/diagnostics-export', () => ({}));

describe('settings distance sources', () => {
  it('offers browser GPS alongside Auto and controller distance', () => {
    expect(distanceSourceChoices('web')).toEqual([
      { value: 'auto', label: 'Auto' },
      { value: 'gps:phone', label: 'GPS · Browser' },
      { value: 'controller', label: 'Controller estimate' },
    ]);
  });
  it('uses phone GPS on both Android and iOS', () => {
    expect(distanceSourceChoices('android').map(choice => choice.value)).toEqual(['auto', 'gps:phone', 'controller']);
    expect(distanceSourceChoices('ios')).toEqual(
      ['auto', 'gps:phone', 'controller'].map(value => ({
        value,
        label: DISTANCE_SOURCE_LABELS[value as keyof typeof DISTANCE_SOURCE_LABELS],
      })),
    );
  });
});
