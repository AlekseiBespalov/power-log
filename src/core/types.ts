/** UTC export time and monotonic capture time are intentionally separate. */
export interface TelemetrySample {
  timestamp: string;
  elapsedSeconds: number;
  sequence: number;
  humanPowerW: number;
  cadenceRpm: number;
  /** Battery voltage × battery current; not mechanical motor or rider power. */
  motorInputPowerW: number;
  batteryVoltageV: number;
  batteryCurrentA: number;
  motorCurrentA: number;
  motorRpm: number;
  pedalTorqueNm: number;
  controllerTempC: number;
  motorTempC: number;
  consumedAh: number;
  consumedWh: number;
  throttleVoltageV: number;
  faultCode: number;
  assistLevel: number;
  raceMode: number;
  /** Original wire value divided by 100; units depend on the recorded protocol profile. */
  speedRaw: number;
  controllerSpeedMps?: number;
  controllerModel?: string;
  firmwareLabel?: string;
  controllerProtocol?: string;
  /** Opaque per-connection token; never a device serial. */
  connectionEpoch?: string;
}

export interface Device { id: string; name: string; rssi: number; controllerModel?: string; firmwareLabel?: string }
export interface ConnectionOptions { deviceId: string; hz: number }
export type ConnectionStatus = 'idle' | 'scanning' | 'connecting' | 'connected' | 'reconnecting' | 'error';
export interface NativeState {
  status: ConnectionStatus;
  deviceName?: string;
  deviceId?: string;
  controllerModel?: string;
  firmwareLabel?: string;
  error?: string;
  /** Only a transient connection error may wait for the display hold to expire. */
  recoverableConnectionError?: boolean;
}

/** Local transport health; contains no device identifiers or telemetry values. */
export interface ConnectionDiagnostics {
  schemaVersion: 1;
  timestamp: string;
  status: ConnectionStatus;
  requestedHz: number;
  sampleCount: number;
  connectionAttempts: number;
  reconnects: number;
  requestTimeouts: number;
  decoderDiscardedBytes: number;
  recentSampleHz: number | null;
  responseLatencyMs: { mean: number; max: number } | null;
  lastSampleAgeSeconds: number | null;
  lastGapSeconds: number | null;
  background: boolean;
  lastDisconnect: {
    initiator: 'app' | 'peer_or_system';
    reason: string;
    errorDomain: string | null;
    errorCode: number | null;
    connectionSeconds: number | null;
    sampleAgeSeconds: number | null;
  } | null;
}

export type RecordingSource = 'device';
export interface Recording {
  id: string;
  startedAt: string;
  endedAt?: string;
  samples: number;
  uri: string;
  source: RecordingSource;
  interrupted?: boolean;
}

export const REQUIRED_SAMPLE_COLUMNS = [
  'timestamp', 'elapsedSeconds', 'sequence', 'humanPowerW', 'cadenceRpm',
  'motorInputPowerW', 'batteryVoltageV', 'batteryCurrentA', 'motorCurrentA',
  'motorRpm', 'pedalTorqueNm', 'controllerTempC', 'motorTempC', 'consumedAh',
  'consumedWh', 'throttleVoltageV', 'faultCode', 'assistLevel', 'raceMode', 'speedRaw',
] as const satisfies readonly (keyof TelemetrySample)[];
export const SAMPLE_IDENTITY_COLUMNS = ['controllerModel', 'firmwareLabel', 'controllerProtocol'] as const;
export const SAMPLE_COLUMNS = [...REQUIRED_SAMPLE_COLUMNS, 'controllerSpeedMps', ...SAMPLE_IDENTITY_COLUMNS, 'connectionEpoch'] as const;

export type SampleTiming = Pick<TelemetrySample, 'timestamp' | 'elapsedSeconds' | 'sequence'>;
export type TelemetryMeasurements = Pick<TelemetrySample, Exclude<typeof REQUIRED_SAMPLE_COLUMNS[number], keyof SampleTiming>>;
export const MAX_SAMPLE_GAP_SECONDS = 2.5;

export function isFreshSample(receivedAtSeconds: number | null, nowSeconds: number, maxAgeSeconds = MAX_SAMPLE_GAP_SECONDS): boolean {
  return receivedAtSeconds !== null && Number.isFinite(receivedAtSeconds)
    && Number.isFinite(nowSeconds) && Number.isFinite(maxAgeSeconds) && maxAgeSeconds > 0
    && nowSeconds >= receivedAtSeconds && nowSeconds - receivedAtSeconds <= maxAgeSeconds;
}
