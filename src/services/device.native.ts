import bridge from '../../modules/cyc-bridge';
import { nativeMonotonicClock, subscribeNativeClock } from './native-monotonic-clock';
import { idleState, type TelemetryAdapter } from './adapter';

function native() {
  if (!bridge) throw new Error('Bluetooth needs an installed Power Log native build.');
  return bridge;
}
export const deviceAdapter: TelemetryAdapter = {
  kind: bridge ? 'native' : 'unavailable',
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
  startScan: () => native().startScan(),
  stopScan: () => native().stopScan(),
  connect: options => native().connect(options),
  disconnect: () => native().disconnect(),
};
