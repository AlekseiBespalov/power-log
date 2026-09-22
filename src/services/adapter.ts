import type { ConnectionDiagnostics, ConnectionOptions, Device, NativeState, TelemetrySample } from '../core/types';

export interface AdapterEvents {
  device: (device: Device) => void;
  state: (state: NativeState) => void;
  sample: (sample: TelemetrySample) => void;
}
export interface TelemetryAdapter {
  readonly kind: 'native' | 'web' | 'unavailable';
  readonly description: string;
  subscribe(events: AdapterEvents): () => void;
  getState(): Promise<NativeState>;
  getDiagnostics?(): Promise<ConnectionDiagnostics>;
  startScan(): Promise<void>;
  stopScan(): Promise<void>;
  connect(options: ConnectionOptions): Promise<void>;
  setSampleRate?(hz: number): Promise<void>;
  disconnect(): Promise<void>;
  setWorkoutOwner?(deviceId: string | null): void;
}
export const idleState = (): NativeState => ({ status: 'idle' });
