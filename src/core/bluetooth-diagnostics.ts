export interface BluetoothTraceStatus {
  capturing: boolean;
  eventCount: number;
  byteCount: number;
  stopReason: string | null;
  lastEvent: string | null;
}

export interface ConnectionLogExport {
  readonly log: string;
  readonly device: Readonly<Record<string, string | number | boolean | null>>;
}

export interface BluetoothDiagnostics {
  getBluetoothTrace(): Promise<BluetoothTraceStatus>;
  startBluetoothTrace(): Promise<void>;
  stopBluetoothTrace(): Promise<void>;
  clearBluetoothTrace(): Promise<void>;
  readBluetoothTrace(): Promise<string>;
}
