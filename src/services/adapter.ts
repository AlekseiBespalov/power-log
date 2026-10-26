import type {
  ConnectionDiagnostics,
  ConnectionOptions,
  Device,
  NativeState,
  SampleDelivery,
  TelemetrySample,
} from '../core/types';
import type { BluetoothDiagnostics, ConnectionLogExport } from '../core/bluetooth-diagnostics';

export interface AdapterEvents {
  device: (device: Device) => void;
  state: (state: NativeState) => void;
  sample: (sample: TelemetrySample, delivery: SampleDelivery) => void;
}
export interface TelemetryAdapter {
  readonly kind: 'native' | 'web' | 'unavailable';
  readonly bluetoothDiagnostics?: BluetoothDiagnostics;
  subscribe(events: AdapterEvents): () => void;
  getState(): Promise<NativeState>;
  getDiagnostics?(): Promise<ConnectionDiagnostics>;
  readConnectionDiagnostics?(): Promise<ConnectionLogExport>;
  startScan(): Promise<void>;
  stopScan(): Promise<void>;
  connect(options: ConnectionOptions): Promise<void>;
  setSampleRate?(hz: number): Promise<void>;
  disconnect(): Promise<void>;
  setWorkoutOwner?(deviceId: string | null): void;
}
export const idleState = (): NativeState => ({ status: 'idle' });
