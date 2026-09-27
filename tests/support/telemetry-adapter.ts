import type { AdapterEvents, TelemetryAdapter } from '../../src/services/adapter';
import type { ConnectionOptions, NativeState, TelemetrySample } from '../../src/core/types';

export class TestTelemetryAdapter implements TelemetryAdapter {
  readonly kind = 'web';
  private listeners = new Set<AdapterEvents>();
  private state: NativeState = { status: 'idle' };
  private owner: string | null = null;
  connectionEpoch = 'test-epoch';
  sampleRates: number[] = [];
  constructor(private readonly now = () => performance.now() / 1000) {}
  subscribe(events: AdapterEvents) {
    this.listeners.add(events);
    return () => {
      this.listeners.delete(events);
    };
  }
  async getState() {
    return this.state;
  }
  async startScan() {}
  async stopScan() {}
  async setSampleRate(hz: number) {
    this.sampleRates.push(hz);
  }
  setWorkoutOwner(id: string | null) {
    if (id !== null && this.owner !== null && id !== this.owner)
      throw new Error('Finish the ride before changing its bike.');
    this.owner = id;
  }
  async connect(options: ConnectionOptions = { deviceId: 'test-bike', hz: 8 }) {
    if (this.owner !== null && options.deviceId !== this.owner)
      throw new Error('Finish the ride before changing its bike.');
    this.connectionEpoch = crypto.randomUUID();
    this.emitState({ status: 'connected', deviceId: options.deviceId });
  }
  async disconnect() {
    this.emitState({ status: 'idle' });
  }
  emitState(patch: Partial<NativeState>) {
    this.state = { ...this.state, ...patch };
    this.listeners.forEach(listener => listener.state(this.state));
  }
  emitSample(sample: TelemetrySample) {
    this.listeners.forEach(listener => listener.sample(sample, { receivedAtSeconds: this.now() }));
  }
}
