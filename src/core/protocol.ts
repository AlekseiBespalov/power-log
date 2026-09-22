import type { SampleTiming, TelemetryMeasurements, TelemetrySample } from './types';
import { validateSample } from './validation';
import { hasKnownControllerSpeedUnit } from './controller-speed';

export const UART_SERVICE = '6e400001-b5a3-f393-e0a9-e50e24dcca9e';
export const UART_WRITE = '6e400002-b5a3-f393-e0a9-e50e24dcca9e';
export const UART_NOTIFY = '6e400003-b5a3-f393-e0a9-e50e24dcca9e';
export const SELECTED_MASK = 0x03c0fb8f;
export const ALL_MASK = 0x03ffffff;
const MAX_PAYLOAD_LENGTH = 1024;

export function crc16Xmodem(bytes: Uint8Array): number {
  let crc = 0;
  for (const byte of bytes) {
    crc ^= byte << 8;
    for (let bit = 0; bit < 8; bit += 1) crc = ((crc << 1) ^ ((crc & 0x8000) ? 0x1021 : 0)) & 0xffff;
  }
  return crc;
}

/** Only approved reads are constructible. There is deliberately no arbitrary write builder. */
export function requestFrame(kind: 'identity' | 'selective'): Uint8Array {
  let payload: Uint8Array;
  switch (kind) {
    case 'identity': payload = Uint8Array.of(111); break;
    case 'selective': payload = Uint8Array.of(50, SELECTED_MASK >>> 24, (SELECTED_MASK >>> 16) & 0xff, (SELECTED_MASK >>> 8) & 0xff, SELECTED_MASK & 0xff); break;
    default: throw new Error('Unsupported controller request');
  }
  const crc = crc16Xmodem(payload);
  return Uint8Array.of(2, payload.length, ...payload, crc >> 8, crc & 0xff, 3);
}

/** Split/coalesced BLE notifications; CRC-valid payloads only, bounded retained state. */
export class FrameDecoder {
  private buffer = new Uint8Array(0);
  discardedBytes = 0;
  get bufferedBytes(): number { return this.buffer.length }
  reset(): void { this.discardedBytes += this.buffer.length; this.buffer = new Uint8Array(0) }

  feed(chunk: Uint8Array): Uint8Array[] {
    if (chunk.length > 65536) throw new Error('Notification chunk exceeds supported size');
    const combined = new Uint8Array(this.buffer.length + chunk.length);
    combined.set(this.buffer); combined.set(chunk, this.buffer.length); this.buffer = combined;
    const packets: Uint8Array[] = [];
    while (this.buffer.length > 0) {
      let incomplete: number | null = null;
      let found = false;
      for (let offset = 0; offset < this.buffer.length; offset += 1) {
        const start = this.buffer[offset];
        if (start !== 2 && start !== 3) continue;
        const header = start === 2 ? 2 : 3;
        const remaining = this.buffer.length - offset;
        if (remaining < header) { incomplete ??= offset; continue }
        const length = start === 2 ? this.buffer[offset + 1]! : (this.buffer[offset + 1]! << 8) | this.buffer[offset + 2]!;
        if (length < 1 || length > MAX_PAYLOAD_LENGTH || (start === 3 && length <= 255)) continue;
        const size = header + length + 3;
        if (remaining < size) { incomplete ??= offset; continue }
        const payload = this.buffer.slice(offset + header, offset + header + length);
        const crcOffset = offset + header + length;
        const crc = (this.buffer[crcOffset]! << 8) | this.buffer[crcOffset + 1]!;
        if (this.buffer[offset + size - 1] !== 3 || crc16Xmodem(payload) !== crc) continue;
        packets.push(payload);
        this.discardedBytes += offset;
        this.buffer = this.buffer.slice(offset + size);
        found = true;
        break;
      }
      if (found) continue;
      const discard = incomplete ?? this.buffer.length;
      this.discardedBytes += discard;
      this.buffer = this.buffer.slice(discard);
      break;
    }
    return packets;
  }
}

export interface ControllerIdentity {
  command: 0 | 111;
  major: number;
  minor: number;
  productString: string;
  controllerModel: string;
  firmwareLabel: string;
}

/** Only the strict ASCII model/firmware prefix is public; the controller tail is opaque. */
export function decodeIdentity(payload: Uint8Array): ControllerIdentity {
  const unsupported = (): never => { throw new Error('Unsupported controller. Connect a CYC X6 or X12.') };
  const command = payload[0];
  if (payload.length < 4 || payload.length > MAX_PAYLOAD_LENGTH || (command !== 0 && command !== 111)) return unsupported();
  const end = payload.indexOf(0, 3);
  if (end < 4 || end - 3 > 128) return unsupported();
  let asciiEnd = 3;
  while (asciiEnd < end && payload[asciiEnd]! >= 0x20 && payload[asciiEnd]! <= 0x7e) asciiEnd += 1;
  const prefix = String.fromCharCode(...payload.slice(3, asciiEnd));
  const match = /^(X(?:6|12)(?:[A-Za-z_][A-Za-z0-9_]{0,29})?) +([0-9]{6,8}[A-Z]{0,8})/.exec(prefix);
  if (!match) return unsupported();
  const boundary = 3 + match[0].length;
  if (boundary !== end && payload[boundary] !== 0x20) return unsupported();
  const controllerModel = match[1]!; const firmwareLabel = match[2]!;
  return { command, major: payload[1]!, minor: payload[2]!, productString: `${controllerModel} ${firmwareLabel}`, controllerModel, firmwareLabel };
}

export interface ControllerAdapter {
  readonly family: 'X6' | 'X12';
  decodeTelemetry(payload: Uint8Array): TelemetryValues;
}
const CONTROLLER_ADAPTERS: Record<ControllerAdapter['family'], ControllerAdapter> = {
  X6: { family: 'X6', decodeTelemetry: payload => decodeSelectiveValues(payload) },
  X12: { family: 'X12', decodeTelemetry: payload => decodeSelectiveValues(payload) },
};

export function identifyController(payload: Uint8Array): { identity: ControllerIdentity; adapter: ControllerAdapter } {
  const identity = decodeIdentity(payload);
  const family = identity.controllerModel.startsWith('X12') ? 'X12' : 'X6';
  return { identity, adapter: CONTROLLER_ADAPTERS[family] };
}

export interface ExtraTelemetryFields {
  idCurrentA: number; iqCurrentA: number; dutyCycle: number; tripTimeRaw: number;
  ioFlags: number; controllerId: number; mos1TempC: number; mos2TempC: number;
  mcuTempC: number; vdV: number; vqV: number; odometerRaw: number;
}
type WireFieldName = Exclude<keyof TelemetryMeasurements, 'motorInputPowerW'> | keyof ExtraTelemetryFields;
type WireFormat = 'i16' | 'i32' | 'u32' | 'u8';
type WireField = readonly [WireFieldName, WireFormat, number];
export const TELEMETRY_FIELDS: readonly (readonly WireField[])[] = [
  [['controllerTempC', 'i16', 10]], [['motorTempC', 'i16', 10]],
  [['motorCurrentA', 'i32', 100]], [['batteryCurrentA', 'i32', 100]],
  [['idCurrentA', 'i32', 100]], [['iqCurrentA', 'i32', 100]], [['dutyCycle', 'i16', 1000]],
  [['motorRpm', 'i32', 1]], [['batteryVoltageV', 'i16', 10]], [['consumedAh', 'i32', 10000]],
  [['tripTimeRaw', 'i32', 1]], [['consumedWh', 'i32', 10000]], [['cadenceRpm', 'i32', 10000]],
  [['throttleVoltageV', 'i32', 100]], [['pedalTorqueNm', 'i32', 100]], [['faultCode', 'u8', 1]],
  [['ioFlags', 'u32', 1]], [['controllerId', 'u8', 1]],
  [['mos1TempC', 'i16', 10], ['mos2TempC', 'i16', 10], ['mcuTempC', 'i16', 10]],
  [['vdV', 'i32', 1000]], [['vqV', 'i32', 1000]], [['odometerRaw', 'i32', 1]],
  [['humanPowerW', 'i32', 1]], [['speedRaw', 'i32', 100]], [['raceMode', 'u8', 1]], [['assistLevel', 'u8', 1]],
];
export type TelemetryValues = Partial<TelemetryMeasurements & ExtraTelemetryFields> & { responseCommand: 50; fieldMask: number };

/** The app accepts selective command 50 only. Pass an explicit mask for offline field tests. */
export function decodeSelectiveValues(payload: Uint8Array, expectedMask = SELECTED_MASK): TelemetryValues {
  if (payload[0] !== 50 || payload.length < 5) throw new Error('Not a complete selective response');
  if (!Number.isInteger(expectedMask) || expectedMask < 0 || expectedMask > ALL_MASK) throw new Error('Invalid expected mask');
  const view = new DataView(payload.buffer, payload.byteOffset, payload.byteLength);
  const fieldMask = view.getUint32(1, false);
  if ((fieldMask & ~ALL_MASK) !== 0) throw new Error('Unknown telemetry mask bits');
  if (fieldMask !== expectedMask) throw new Error('Unexpected telemetry mask');
  const result: TelemetryValues = { responseCommand: 50, fieldMask };
  let offset = 5;
  for (let bit = 0; bit < TELEMETRY_FIELDS.length; bit += 1) {
    if (!(fieldMask & (1 << bit))) continue;
    for (const [name, format, divisor] of TELEMETRY_FIELDS[bit]!) {
      const size = format === 'u8' ? 1 : format === 'i16' ? 2 : 4;
      if (offset + size > payload.length) throw new Error(`Truncated field ${name}`);
      const raw = format === 'u8' ? view.getUint8(offset) : format === 'i16' ? view.getInt16(offset, false)
        : format === 'u32' ? view.getUint32(offset, false) : view.getInt32(offset, false);
      result[name] = raw / divisor;
      offset += size;
    }
  }
  if (offset !== payload.length) throw new Error('Unknown trailing telemetry data');
  if (result.batteryVoltageV !== undefined && result.batteryCurrentA !== undefined)
    result.motorInputPowerW = Math.round(result.batteryVoltageV * result.batteryCurrentA * 10000) / 10000;
  return result;
}

export function toTelemetrySample(values: TelemetryValues, timing: SampleTiming, identity?: ControllerIdentity): TelemetrySample {
  const provenance = identity && { controllerModel: identity.controllerModel, firmwareLabel: identity.firmwareLabel, controllerProtocol: `${identity.major}.${identity.minor}` };
  const controllerSpeedMps = provenance && hasKnownControllerSpeedUnit(provenance.controllerModel, provenance.controllerProtocol) && values.speedRaw !== undefined
    ? values.speedRaw / 3.6 : undefined;
  return validateSample({ ...values, ...timing, ...provenance, controllerSpeedMps });
}
