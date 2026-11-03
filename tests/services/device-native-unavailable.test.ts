import { expect, it, vi } from 'vitest';
import { deviceAdapter } from '../../src/services/device.native';
import { presentFailure } from '../../src/core/failures';

vi.mock('../../modules/power-log-bridge', () => ({ default: null }));
vi.mock('../../src/services/app-visibility', () => ({ subscribeAppVisibility: () => () => {} }));

it('tells the rider in plain words that a build without the native module has no Bluetooth', async () => {
  expect(deviceAdapter.kind).toBe('unavailable');
  const scan = Promise.resolve().then(() => deviceAdapter.startScan());
  expect(await scan.catch(presentFailure)).toMatchObject({
    code: 'bluetoothUnavailable',
    text: "Bluetooth isn't available in this build.",
  });
});
