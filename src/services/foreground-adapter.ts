import type { ConnectionOptions, Device, NativeState, TelemetrySample } from '../core/types';
import { idleState, type AdapterEvents, type TelemetryAdapter } from './adapter';

/** Browser capture runs in JS while the page is active. iPhone device capture never runs here. */
export abstract class ForegroundAdapter implements TelemetryAdapter {
  abstract readonly kind: 'web';
  abstract readonly description: string;
  protected state = idleState();
  private connectionEpoch?: string;
  private listeners = new Set<AdapterEvents>();
  protected workoutDeviceId: string | null = null;
  setWorkoutOwner(deviceId: string | null): void {
    if (deviceId !== null && this.workoutDeviceId !== null && this.workoutDeviceId !== deviceId) throw new Error('Finish the ride before changing its bike.');
    this.workoutDeviceId = deviceId;
  }
  protected requireWorkoutDevice(deviceId: string): void {
    if (this.workoutDeviceId !== null && this.workoutDeviceId !== deviceId) throw new Error('Finish the ride before changing its bike.');
  }
  constructor(protected readonly now = () => performance.now() / 1000) {}
  subscribe(events: AdapterEvents) { this.listeners.add(events); return () => { this.listeners.delete(events); }; }
  async getState() { return { ...this.state }; }
  protected update(patch: Partial<NativeState>) {
    if (patch.status === 'connected' && this.state.status !== 'connected') this.connectionEpoch = crypto.randomUUID();
    this.state = { ...this.state, ...patch }; this.listeners.forEach(listener => listener.state({ ...this.state }));
  }
  protected discovered(device: Device) { this.listeners.forEach(listener => listener.device(device)); }
  protected publish(sample: TelemetrySample) {
    if (this.connectionEpoch) sample = { ...sample, connectionEpoch: this.connectionEpoch };
    this.listeners.forEach(listener => listener.sample(sample));
  }
  abstract startScan(): Promise<void>;
  abstract stopScan(): Promise<void>;
  abstract connect(options: ConnectionOptions): Promise<void>;
  abstract disconnect(): Promise<void>;
}
