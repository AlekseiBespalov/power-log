import bridge from '../../modules/power-log-bridge';
import { RideFailureError } from '../core/failures';
import { nativeMonotonicClock, subscribeNativeClock } from './native-monotonic-clock';
import { idleState, type TelemetryAdapter } from './adapter';

function native() {
  if (!bridge) throw new RideFailureError('bluetoothUnavailable');
  return bridge;
}
export const deviceAdapter: TelemetryAdapter = {
  kind: bridge ? 'native' : 'unavailable',
  bluetoothDiagnostics: bridge
    ? {
        getBluetoothTrace: () => native().getBluetoothTrace(),
        startBluetoothTrace: () => native().startBluetoothTrace(),
        stopBluetoothTrace: () => native().stopBluetoothTrace(),
        clearBluetoothTrace: () => native().clearBluetoothTrace(),
        readBluetoothTrace: () => native().readBluetoothTrace(),
      }
    : undefined,
  subscribe(events) {
    if (!bridge) return () => {};
    const unsubscribeClock = subscribeNativeClock();
    const subscriptions = [
      bridge.addListener('onDevice', events.device),
      bridge.addListener('onState', events.state),
      bridge.addListener('onSample', ({ acquiredAtMonotonic, ...sample }) =>
        events.sample(
          { ...sample, interruptionIndex: 0 },
          {
            acquiredAtMonotonic,
            clock: nativeMonotonicClock,
          },
        ),
      ),
    ];
    return () => {
      subscriptions.forEach(subscription => subscription.remove());
      unsubscribeClock();
    };
  },
  getState: () => bridge?.getState() ?? Promise.resolve(idleState()),
  getDiagnostics: bridge ? () => native().getDiagnostics() : undefined,
  readConnectionDiagnostics: bridge ? () => native().readConnectionDiagnostics() : undefined,
  startScan: () => native().startScan(),
  stopScan: () => native().stopScan(),
  connect: options => native().connect(options),
  disconnect: () => native().disconnect(),
};
