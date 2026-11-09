const MESSAGE_NAMES: Record<number, string> = {
  0: 'fileId',
  18: 'session',
  19: 'lap',
  20: 'record',
  21: 'event',
  34: 'activity',
  206: 'fieldDescription',
  207: 'developerDataId',
};

interface Field {
  readonly number: number;
  readonly size: number;
  readonly type: number;
}

interface Message {
  readonly global: number;
  readonly cells: ReadonlyMap<string, string>;
  readonly start: number;
  readonly end: number;
}

function cell(view: DataView, at: number, field: Field, little: boolean): string {
  switch (field.size === 1 || field.size === 2 || field.size === 4 || field.size === 8 ? field.type : -1) {
    case 0x01:
      return String(view.getInt8(at));
    case 0x00:
    case 0x02:
    case 0x0a:
    case 0x0d:
      return field.size === 1 ? String(view.getUint8(at)) : hex(view, at, field.size);
    case 0x83:
      return String(view.getInt16(at, little));
    case 0x84:
    case 0x8b:
      return String(view.getUint16(at, little));
    case 0x85:
      return String(view.getInt32(at, little));
    case 0x86:
    case 0x8c:
      return String(view.getUint32(at, little));
    case 0x88:
      return String(view.getFloat32(at, little));
    case 0x89:
      return String(view.getFloat64(at, little));
    default:
      return hex(view, at, field.size);
  }
}

function hex(view: DataView, at: number, size: number): string {
  let text = '0x';
  for (let i = 0; i < size; i++)
    text += view
      .getUint8(at + i)
      .toString(16)
      .padStart(2, '0');
  return text;
}

function fields(bytes: Uint8Array, at: number, count: number): Field[] {
  const list: Field[] = [];
  for (let i = 0; i < count; i++)
    list.push({ number: bytes[at + 3 * i]!, size: bytes[at + 3 * i + 1]!, type: bytes[at + 3 * i + 2]! });
  return list;
}

function messages(bytes: Uint8Array): { header: string; messages: Message[] } {
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  const headerSize = bytes[0]!;
  const end = headerSize + view.getUint32(4, true);
  const header = `size ${headerSize}, protocol ${bytes[1]}, profile ${view.getUint16(2, true)}, data ${end - headerSize}`;
  const definitions = new Map<number, { global: number; little: boolean; native: Field[]; developer: Field[] }>();
  const list: Message[] = [];
  let at = headerSize;
  while (at < end) {
    const start = at;
    const record = bytes[at++]!;
    if (record & 0x80) throw new Error('compressed-timestamp records are not written');
    const local = record & 0x0f;
    if (record & 0x40) {
      const little = bytes[at + 1] === 0;
      const global = little ? view.getUint16(at + 2, true) : view.getUint16(at + 2, false);
      const native = fields(bytes, at + 5, bytes[at + 4]!);
      at += 5 + 3 * native.length;
      let developer: Field[] = [];
      if (record & 0x20) {
        developer = fields(bytes, at + 1, bytes[at]!);
        at += 1 + 3 * developer.length;
      }
      definitions.set(local, { global, little, native, developer });
      continue;
    }
    const definition = definitions.get(local);
    if (!definition) throw new Error(`a data message uses local type ${local} before its definition`);
    const cells = new Map<string, string>();
    for (const field of definition.native) {
      cells.set(`field ${field.number}`, cell(view, at, field, definition.little));
      at += field.size;
    }
    for (const field of definition.developer) {
      cells.set(`developer field ${field.number}`, hex(view, at, field.size));
      at += field.size;
    }
    list.push({ global: definition.global, cells, start, end: at });
  }
  return { header, messages: list };
}

const nameOf = (global: number) => MESSAGE_NAMES[global] ?? `global ${global}`;

const describe = (message: Message) =>
  `${nameOf(message.global)} ${[...message.cells].map(([key, value]) => `${key.replace('field ', '')}=${value}`).join(' ')}`;

function counts(list: readonly Message[]): Map<number, number> {
  const counted = new Map<number, number>();
  for (const message of list) counted.set(message.global, (counted.get(message.global) ?? 0) + 1);
  return counted;
}

export function fitDifferences(actual: Uint8Array, golden: Uint8Array, limit = 26): string[] {
  const [got, want] = [messages(actual), messages(golden)];
  const differences: string[] = [];
  if (got.header !== want.header) differences.push(`header: ${got.header} is not ${want.header}`);
  const [gotCounts, wantCounts] = [counts(got.messages), counts(want.messages)];
  for (const global of new Set([...gotCounts.keys(), ...wantCounts.keys()]))
    if (gotCounts.get(global) !== wantCounts.get(global))
      differences.push(`${nameOf(global)}: ${gotCounts.get(global) ?? 0} messages, not ${wantCounts.get(global) ?? 0}`);
  const shared = Math.min(got.messages.length, want.messages.length);
  for (let i = 0; i < shared && differences.length < limit; i++) {
    const [a, b] = [got.messages[i]!, want.messages[i]!];
    if (a.global !== b.global) {
      differences.push(`message ${i} is a ${nameOf(a.global)}, not a ${nameOf(b.global)}`);
      for (const [side, list] of [
        ['written', got.messages],
        ['golden', want.messages],
      ] as const)
        for (const message of list.slice(i, i + 3)) differences.push(`${side}: ${describe(message)}`);
      break;
    }
    for (const key of new Set([...a.cells.keys(), ...b.cells.keys()]))
      if (a.cells.get(key) !== b.cells.get(key) && differences.length < limit)
        differences.push(
          `message ${i} (${nameOf(a.global)}) ${key}: ${a.cells.get(key) ?? 'absent'} is not ${b.cells.get(key) ?? 'absent'}`,
        );
  }
  return differences.slice(0, limit);
}

const CRC_TABLE = [
  0x0000, 0xcc01, 0xd801, 0x1400, 0xf001, 0x3c00, 0x2800, 0xe401, 0xa001, 0x6c00, 0x7800, 0xb401, 0x5000, 0x9c01,
  0x8801, 0x4400,
];

function fitCrc(bytes: Uint8Array): number {
  let crc = 0;
  for (const byte of bytes)
    for (const nibble of [byte & 0xf, byte >> 4])
      crc = ((crc >> 4) & 0x0fff) ^ CRC_TABLE[crc & 0xf]! ^ CRC_TABLE[nibble]!;
  return crc;
}

const isTimerStop = (message: Message | undefined) =>
  message?.global === 21 && message.cells.get('field 0') === '0' && message.cells.get('field 1') === '4';

// 60 §7.3 rule 6: today's phones can write a second timer stop just before the final lap.
export function withoutDuplicateFinalStop(golden: Uint8Array): Uint8Array {
  const { messages: list } = messages(golden);
  let lap = list.length - 1;
  while (lap >= 0 && list[lap]!.global !== 19) lap--;
  const [first, second] = [list[lap - 2], list[lap - 1]];
  if (!isTimerStop(first) || !isTimerStop(second) || first!.cells.get('field 253') !== second!.cells.get('field 253'))
    return golden;
  const headerSize = golden[0]!;
  const view = new DataView(golden.buffer, golden.byteOffset, golden.byteLength);
  const dataEnd = headerSize + view.getUint32(4, true);
  const body = new Uint8Array(dataEnd - (second!.end - second!.start) + 2);
  body.set(golden.subarray(0, second!.start));
  body.set(golden.subarray(second!.end, dataEnd), second!.start);
  const out = new DataView(body.buffer);
  out.setUint32(4, body.length - 2 - headerSize, true);
  if (headerSize >= 14 && view.getUint16(12, true) !== 0) out.setUint16(12, fitCrc(body.subarray(0, 12)), true);
  out.setUint16(body.length - 2, fitCrc(body.subarray(0, body.length - 2)), true);
  return body;
}
