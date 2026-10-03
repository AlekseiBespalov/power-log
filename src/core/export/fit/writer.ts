import { FIT_DEVELOPER_APPLICATION_ID, FIT_DEVELOPER_DATA_INDEX, FIT_DEVELOPER_FIELDS, FIT_MESSAGES } from '../catalog';
import { roundHalfAway } from '../clock';
import type { ExportSlicer } from '../pages';
import { ExportError, type ExportSink, type FitBaseType, type FitField } from '../types';

export type FitMessageName = keyof typeof FIT_MESSAGES;
export type FitValue = number | Uint8Array;

export const FIT_HEADER_SIZE = 14;
export const FIT_PROTOCOL_VERSION = 0x20;
export const FIT_PROFILE_VERSION = 21217;
export const FIT_LOCAL_SLOTS = 16;
export const FIT_MIN_TIME = 0x10000000;
export const FIT_MAX_MESSAGE_INDEX = 0x0fff;

const CHUNK_BYTES = 65536;
const CRC_BATCH = 4096;
const TIME_FIELDS: readonly string[] = ['timestamp', 'time_created', 'start_time'];
const REQUIRED_FIELDS: readonly string[] = [...TIME_FIELDS, 'total_elapsed_time', 'total_timer_time', 'message_index'];
const INTEGER_RANGES: Partial<Record<FitBaseType, readonly [number, number]>> = {
  enum: [0, 254],
  uint8: [0, 254],
  uint16: [0, 65534],
  uint32: [0, 4294967294],
  sint16: [-32768, 32766],
  sint32: [-2147483648, 2147483646],
};
// Math.fround(x) is finite exactly when |x| is below this, halfway between the largest float32 and 2^128.
export const FLOAT32_OVERFLOW = 2 ** 128 - 2 ** 103;

const BYTES = 0;
const UINT8 = 1;
const UINT16 = 2;
const SINT16 = 3;
const UINT32 = 4;
const SINT32 = 5;
const FLOAT32 = 6;
const ENCODINGS: Record<FitBaseType, number> = {
  enum: UINT8,
  uint8: UINT8,
  uint16: UINT16,
  sint16: SINT16,
  uint32: UINT32,
  sint32: SINT32,
  float32: FLOAT32,
  string: BYTES,
  byte: BYTES,
};

const CRC_TABLE = new Uint16Array(256);
for (let n = 0; n < 256; n++) {
  let c = n;
  for (let bit = 0; bit < 8; bit++) c = c & 1 ? (c >>> 1) ^ 0xa001 : c >>> 1;
  CRC_TABLE[n] = c;
}

let crcPairs: Uint16Array | null = null;

// After two bytes b0, b1 the register depends only on crc ^ (b0 | b1 << 8), so one lookup covers both.
function pairTable(): Uint16Array {
  const table = new Uint16Array(65536);
  for (let x = 0; x < 65536; x++) {
    const low = CRC_TABLE[x & 0xff]!;
    table[x] = (low >>> 8) ^ CRC_TABLE[((x >>> 8) ^ low) & 0xff]!;
  }
  return table;
}

export function fitCrc(bytes: Uint8Array, crc = 0): number {
  const pairs = (crcPairs ??= pairTable());
  const length = bytes.length;
  const even = length - (length & 1);
  for (let i = 0; i < even; i += 2) crc = pairs[crc ^ bytes[i]! ^ (bytes[i + 1]! << 8)]!;
  if (even < length) crc = (crc >>> 8) ^ CRC_TABLE[(crc ^ bytes[even]!) & 0xff]!;
  return crc;
}

interface Layout {
  readonly kind: number;
  readonly global: number;
  readonly fields: readonly FitField[];
  readonly required: readonly boolean[];
  readonly encodings: readonly number[];
  readonly lowest: readonly number[];
  readonly highest: readonly number[];
}

function bounds(field: FitField): readonly [number, number] {
  const [low, high] = INTEGER_RANGES[field.type] ?? [-Infinity, Infinity];
  if (TIME_FIELDS.includes(field.name)) return [Math.max(low, FIT_MIN_TIME), high];
  if (field.name === 'message_index') return [low, Math.min(high, FIT_MAX_MESSAGE_INDEX)];
  return [low, high];
}

const KINDS = Object.keys(FIT_MESSAGES) as FitMessageName[];
const LAYOUTS = Object.fromEntries(
  KINDS.map((name, kind) => {
    const message = FIT_MESSAGES[name];
    const fields = message.fields as readonly FitField[];
    const layout: Layout = {
      kind,
      global: message.number,
      fields,
      required: fields.map(field => REQUIRED_FIELDS.includes(field.name)),
      encodings: fields.map(field => ENCODINGS[field.type]),
      lowest: fields.map(field => bounds(field)[0]),
      highest: fields.map(field => bounds(field)[1]),
    };
    return [name, layout];
  }),
) as Record<FitMessageName, Layout>;

const DEVELOPER_FLOAT32 = FIT_DEVELOPER_FIELDS.map(field => field.type === 'float32');
const [SINT16_MIN, SINT16_MAX] = INTEGER_RANGES.sint16!;
const DEVELOPER_SIZES = FIT_DEVELOPER_FIELDS.map(field => (field.type === 'float32' ? 4 : 2));

function uuidBytes(uuid: string): Uint8Array {
  const hex = uuid.replace(/-/g, '');
  const bytes = new Uint8Array(16);
  for (let i = 0; i < 16; i++) bytes[i] = parseInt(hex.slice(2 * i, 2 * i + 2), 16);
  return bytes;
}

export function fitString(text: string, size: number): Uint8Array {
  const bytes = new Uint8Array(size);
  bytes.set(new TextEncoder().encode(text).subarray(0, size - 1));
  return bytes;
}

export function fitInteger(value: number, type: FitBaseType): number {
  if (!Number.isFinite(value)) return NaN;
  const rounded = roundHalfAway(value);
  const range = INTEGER_RANGES[type]!;
  return rounded >= range[0] && rounded <= range[1] ? rounded : NaN;
}

export function finiteAsFloat32(value: number): boolean {
  return value > -FLOAT32_OVERFLOW && value < FLOAT32_OVERFLOW;
}

export function fitFloat32(value: number): number {
  return finiteAsFloat32(value) ? value : NaN;
}

function invalidRequired(field: FitField): ExportError {
  if (field.name === 'message_index')
    return new ExportError('limit', 'A FIT file can hold at most 4096 laps, and this ride has more.');
  if (TIME_FIELDS.includes(field.name))
    return new ExportError('limit', 'The ride’s times are outside the range a FIT file can store.');
  return new ExportError('limit', 'The ride is too long for a FIT file.');
}

export class FitWriter {
  private buffer = new Uint8Array(2 * CHUNK_BYTES);
  private view = new DataView(this.buffer.buffer);
  private used = 0;
  private written = 0;
  private crc = 0;
  private fresh = false;
  private readonly slots: { key: number; local: number }[] = [];
  private readonly numbers = new Float64Array(32);
  private readonly developer = new Float64Array(FIT_DEVELOPER_FIELDS.length);

  constructor(
    private readonly sink: ExportSink,
    private readonly slicer: ExportSlicer,
  ) {}

  get pending(): number {
    return this.used;
  }

  async begin(): Promise<void> {
    await this.sink.write(new Uint8Array(FIT_HEADER_SIZE));
  }

  developerMetadata(): void {
    this.message('developerDataId', [uuidBytes(FIT_DEVELOPER_APPLICATION_ID), FIT_DEVELOPER_DATA_INDEX]);
    for (const field of FIT_DEVELOPER_FIELDS)
      this.message('fieldDescription', [
        FIT_DEVELOPER_DATA_INDEX,
        field.number,
        field.code,
        fitString(field.name, 23),
        fitString(field.units, 4),
      ]);
  }

  message(name: FitMessageName, values: ArrayLike<FitValue>, developer?: ArrayLike<number>): void {
    const layout = LAYOUTS[name];
    const { fields, encodings, lowest, highest } = layout;
    const numbers = this.numbers;
    let mask = 0;
    let size = 1;
    let definitionSize = 6;
    for (let i = 0; i < fields.length; i++) {
      const field = fields[i]!;
      const value = values[i];
      let present: boolean;
      if (typeof value === 'number') {
        // A rounded infinity or NaN lies outside every integer range.
        const encoded = encodings[i] === FLOAT32 ? fitFloat32(value) : roundHalfAway(value);
        numbers[i] = encoded;
        present = encoded >= lowest[i]! && encoded <= highest[i]!;
      } else if (value instanceof Uint8Array) {
        if (value.length !== field.size) throw new RangeError(`FIT field ${field.name} needs ${field.size} bytes`);
        present = true;
      } else present = false;
      if (present) {
        mask |= 1 << i;
        size += field.size;
        definitionSize += 3;
      } else if (layout.required[i]) throw invalidRequired(field);
    }
    let developerMask = 0;
    if (developer) {
      const encodedDeveloper = this.developer;
      for (let j = 0; j < DEVELOPER_SIZES.length; j++) {
        const value = developer[j]!;
        const float32 = DEVELOPER_FLOAT32[j];
        const encoded = float32 ? value : roundHalfAway(value);
        if (float32 ? !finiteAsFloat32(value) : !(encoded >= SINT16_MIN && encoded <= SINT16_MAX)) continue;
        encodedDeveloper[j] = encoded;
        developerMask |= 1 << j;
        size += DEVELOPER_SIZES[j]!;
        definitionSize += 3;
      }
      if (developerMask !== 0) definitionSize += 1;
    }
    const key = (layout.kind * 1024 + developerMask) * 2 ** 27 + mask;
    const local = this.slot(key);
    const fresh = this.fresh;
    this.ensure(size + (fresh ? definitionSize : 0));
    if (fresh) this.definition(layout, local, mask, developerMask);
    const buffer = this.buffer;
    const view = this.view;
    let at = this.used;
    buffer[at++] = local;
    for (let i = 0; i < fields.length; i++) {
      if ((mask & (1 << i)) === 0) continue;
      const value = values[i];
      if (typeof value !== 'number') {
        buffer.set(value!, at);
        at += value!.length;
        continue;
      }
      const n = numbers[i]!;
      switch (encodings[i]) {
        case UINT8:
          buffer[at] = n;
          break;
        case UINT16:
          view.setUint16(at, n, true);
          break;
        case SINT16:
          view.setInt16(at, n, true);
          break;
        case UINT32:
          view.setUint32(at, n, true);
          break;
        case SINT32:
          view.setInt32(at, n, true);
          break;
        case FLOAT32:
          view.setFloat32(at, n, true);
          break;
        default:
          throw new RangeError(`FIT field ${fields[i]!.name} needs bytes`);
      }
      at += fields[i]!.size;
    }
    for (let j = 0; j < DEVELOPER_SIZES.length; j++) {
      if ((developerMask & (1 << j)) === 0) continue;
      if (DEVELOPER_FLOAT32[j]) view.setFloat32(at, this.developer[j]!, true);
      else view.setInt16(at, this.developer[j]!, true);
      at += DEVELOPER_SIZES[j]!;
    }
    this.used = at;
  }

  async drain(final = false): Promise<void> {
    while (this.used >= CHUNK_BYTES || (final && this.used > 0)) {
      const size = Math.min(this.used, CHUNK_BYTES);
      const chunk = this.buffer.subarray(0, size);
      for (let offset = 0; offset < size; offset += CRC_BATCH) {
        this.crc = fitCrc(chunk.subarray(offset, Math.min(size, offset + CRC_BATCH)), this.crc);
        await this.slicer.tick();
      }
      await this.sink.write(chunk);
      this.written += size;
      this.buffer.copyWithin(0, size, this.used);
      this.used -= size;
    }
  }

  async finish(): Promise<number> {
    await this.drain(true);
    const size = this.written;
    if (size > 0xffffffff) throw new ExportError('limit', 'The ride is too large for a FIT file.');
    // The header ends with its own CRC, so the CRC over the whole file equals the CRC over the data alone.
    await this.sink.write(new Uint8Array([this.crc & 0xff, this.crc >>> 8]));
    const header = new Uint8Array(FIT_HEADER_SIZE);
    const view = new DataView(header.buffer);
    header[0] = FIT_HEADER_SIZE;
    header[1] = FIT_PROTOCOL_VERSION;
    view.setUint16(2, FIT_PROFILE_VERSION, true);
    view.setUint32(4, size, true);
    header.set([0x2e, 0x46, 0x49, 0x54], 8);
    view.setUint16(12, fitCrc(header.subarray(0, 12)), true);
    await this.sink.writeAt(0, header);
    return FIT_HEADER_SIZE + size + 2;
  }

  // Also sets `fresh` when the local message number must be defined again before use.
  private slot(key: number): number {
    const slots = this.slots;
    for (let i = slots.length - 1; i >= 0; i--) {
      const slot = slots[i]!;
      if (slot.key !== key) continue;
      if (i !== slots.length - 1) {
        slots.splice(i, 1);
        slots.push(slot);
      }
      this.fresh = false;
      return slot.local;
    }
    const local = slots.length < FIT_LOCAL_SLOTS ? slots.length : slots.shift()!.local;
    slots.push({ key, local });
    this.fresh = true;
    return local;
  }

  private definition(layout: Layout, local: number, mask: number, developerMask: number): void {
    const buffer = this.buffer;
    let at = this.used;
    buffer[at++] = 0x40 | (developerMask === 0 ? 0 : 0x20) | local;
    buffer[at++] = 0;
    buffer[at++] = 0;
    this.view.setUint16(at, layout.global, true);
    at += 2;
    const count = at++;
    let fields = 0;
    layout.fields.forEach((field, i) => {
      if ((mask & (1 << i)) === 0) return;
      buffer[at++] = field.number;
      buffer[at++] = field.size;
      buffer[at++] = field.code;
      fields++;
    });
    buffer[count] = fields;
    if (developerMask !== 0) {
      const countAt = at++;
      let developer = 0;
      FIT_DEVELOPER_FIELDS.forEach((field, j) => {
        if ((developerMask & (1 << j)) === 0) return;
        buffer[at++] = field.number;
        buffer[at++] = DEVELOPER_SIZES[j]!;
        buffer[at++] = FIT_DEVELOPER_DATA_INDEX;
        developer++;
      });
      buffer[countAt] = developer;
    }
    this.used = at;
  }

  private ensure(bytes: number): void {
    if (this.used + bytes <= this.buffer.length) return;
    const grown = new Uint8Array(Math.max(this.buffer.length * 2, this.used + bytes));
    grown.set(this.buffer.subarray(0, this.used));
    this.buffer = grown;
    this.view = new DataView(grown.buffer);
  }
}
