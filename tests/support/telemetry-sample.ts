export interface TelemetrySample {
  timestamp: string;
  elapsedSeconds: number;
  sequence: number;
  humanPowerW: number;
  cadenceRpm: number;
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
  speedRaw: number;
  controllerSpeedMps?: number;
  controllerModel?: string;
  firmwareLabel?: string;
  controllerProtocol?: string;
  connectionEpoch?: string;
  interruptionIndex: number;
}
