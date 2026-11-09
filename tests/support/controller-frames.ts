import { controllerCrc, controllerSelectedMask, controllerWireFields } from '../../shared/generated/js/kit';

export function framePayload(payload: Uint8Array): Uint8Array {
  const crc = controllerCrc(new Int8Array(payload));
  const header = payload.length > 255 ? [3, payload.length >> 8, payload.length & 255] : [2, payload.length];
  return Uint8Array.of(...header, ...payload, crc >> 8, crc & 255, 3);
}

export function controllerReadingFrame(values: Readonly<Record<string, number>>): Uint8Array {
  const fields = controllerWireFields();
  const payload = new Uint8Array(5 + fields.reduce((bytes, field) => bytes + field.width, 0));
  const view = new DataView(payload.buffer);
  payload[0] = 50;
  view.setUint32(1, controllerSelectedMask(), false);
  let offset = 5;
  for (const field of fields) {
    const raw = Math.round(values[field.metric]! * field.divisor);
    if (!Number.isFinite(raw)) throw new Error(`Missing synthetic metric ${field.metric}`);
    if (field.width === 1) {
      if (field.signed) view.setInt8(offset, raw);
      else view.setUint8(offset, raw);
    } else if (field.width === 2) {
      if (field.signed) view.setInt16(offset, raw, false);
      else view.setUint16(offset, raw, false);
    } else if (field.width === 4) {
      if (field.signed) view.setInt32(offset, raw, false);
      else view.setUint32(offset, raw, false);
    } else throw new Error('Unsupported synthetic wire width');
    offset += field.width;
  }
  return framePayload(payload);
}
