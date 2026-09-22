import { Platform } from 'react-native';
import bridge from '../../modules/cyc-bridge';
import { idleState, type TelemetryAdapter } from './adapter';

function native() {
  if (!bridge || Platform.OS !== 'ios') throw new Error(Platform.OS === 'android' ? 'Android support is planned. Use iPhone or web for now.' : 'Bluetooth needs an iPhone development build. Expo Go and the simulator cannot run this engine.');
  return bridge;
}
export const deviceAdapter: TelemetryAdapter = {
  kind: bridge && Platform.OS === 'ios' ? 'native' : 'unavailable',
  description: 'iPhone captures in the native Bluetooth engine. Watch pairing and locked-screen behavior need an on-device test.',
  subscribe(events) {
    if (!bridge) return () => {};
    const subscriptions = [bridge.addListener('onDevice', events.device), bridge.addListener('onState', events.state), bridge.addListener('onSample', events.sample)];
    return () => subscriptions.forEach(subscription => subscription.remove());
  },
  getState: () => bridge?.getState() ?? Promise.resolve(idleState()),
  getDiagnostics: bridge ? () => native().getDiagnostics() : undefined,
  startScan: () => native().startScan(), stopScan: () => native().stopScan(),
  connect: options => native().connect(options), disconnect: () => native().disconnect(),
};
