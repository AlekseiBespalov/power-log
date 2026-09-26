import bridge from '../../modules/cyc-bridge';
import { idleState, type TelemetryAdapter } from './adapter';

function native() {
  if (!bridge) throw new Error('Bluetooth needs an installed Power Log native build.');
  return bridge;
}
export const deviceAdapter: TelemetryAdapter = {
  kind: bridge ? 'native' : 'unavailable',
  description: 'Your phone records with the native Bluetooth engine, including while the screen is locked.',
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
