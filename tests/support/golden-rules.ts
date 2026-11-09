import { expect } from 'vitest';
import { zipTime } from '../../modules/power-log/src/exports/zip';
import { entryText, readZip, verifyWithPython, type ZipEntry } from '../fixtures/export/zip/harness';

export const STARTING_BUILD_HEALTH = ['logicalKey', 'targetEventId', 'originRevision', 'valueBits', 'evidence'];

function splitUnquoted(text: string, separator: string): string[] {
  const parts: string[] = [];
  let start = 0;
  let quoted = false;
  for (let i = 0; i < text.length; i++) {
    if (text[i] === '"') quoted = !quoted;
    else if (!quoted && text[i] === separator) {
      parts.push(text.slice(start, i));
      start = i + 1;
    }
  }
  parts.push(text.slice(start));
  return parts;
}

const csvRecords = (text: string): string[] => splitUnquoted(text, '\n');
const csvCells = (record: string): string[] => splitUnquoted(record, ',');

export function withoutStartingBuildCells(csv: string): string {
  const records = csvRecords(csv);
  const header = csvCells(records[0] ?? '');
  const blank = new Set(STARTING_BUILD_HEALTH.map(name => header.indexOf(name)).filter(index => index >= 0));
  return records
    .map((record, row) =>
      row === 0
        ? record
        : csvCells(record)
            .map((cell, column) => (blank.has(column) ? '' : cell))
            .join(','),
    )
    .join('\n');
}

type Span = readonly [start: number, end: number];

function jsonSpans(text: string): Map<string, Span> {
  JSON.parse(text);
  const spans = new Map<string, Span>();
  let at = 0;
  const skip = () => {
    while (at < text.length && ' \t\r\n'.includes(text[at]!)) at++;
  };
  const string = (): string => {
    const start = at++;
    while (text[at] !== '"') at += text[at] === '\\' ? 2 : 1;
    return JSON.parse(text.slice(start, ++at)) as string;
  };
  const value = (path: string): void => {
    skip();
    const start = at;
    const open = text[at];
    if (open === '{' || open === '[') {
      const close = open === '{' ? '}' : ']';
      at++;
      skip();
      for (let index = 0; text[at] !== close; index++) {
        if (index > 0) {
          at++;
          skip();
        }
        if (open === '{') {
          const key = string();
          skip();
          at++;
          value(`${path}/${key}`);
        } else value(`${path}/${index}`);
        skip();
      }
      at++;
    } else if (open === '"') string();
    else while (at < text.length && !',}] \t\r\n'.includes(text[at]!)) at++;
    spans.set(path, [start, at]);
  };
  value('');
  return spans;
}

const spliced = (text: string, edits: readonly (readonly [number, number, string])[]): string =>
  [...edits]
    .sort((a, b) => b[0] - a[0])
    .reduce((out, [start, end, value]) => out.slice(0, start) + value + out.slice(end), text);

const HEALTH_EXPORT = '/powerLog:ride/health/export';

export function withoutStartingBuildExport(descriptor: string): string {
  const span = jsonSpans(descriptor).get(HEALTH_EXPORT);
  if (!span) throw new Error(`datapackage.json has no ${HEALTH_EXPORT}`);
  return spliced(descriptor, [[...span, 'null']]);
}

export type EntryTexts = Readonly<Record<string, string>>;

function comparable(name: string, text: string): string {
  if (name.endsWith('/health.csv')) return withoutStartingBuildCells(text);
  if (name.endsWith('/datapackage.json')) return withoutStartingBuildExport(text);
  return text;
}

export function archiveOf(captured: Uint8Array): Uint8Array {
  const bytes = Buffer.from(captured);
  const end = bytes.lastIndexOf(Buffer.from([0x50, 0x4b, 0x05, 0x06]));
  if (end < 0) throw new Error('No end of central directory record');
  return captured.subarray(0, end + 22 + bytes.readUInt16LE(end + 20));
}

function frame(entry: ZipEntry) {
  return {
    name: entry.name,
    time: entry.time,
    date: entry.date,
    flags: entry.flags,
    versionNeeded: entry.versionNeeded,
    method: entry.method,
    centralExtra: extraFields(entry.centralExtra),
    localExtra: extraFields(entry.localExtra),
  };
}

function extraFields(bytes: Uint8Array): [number, number][] {
  const fields: [number, number][] = [];
  for (let at = 0; at + 4 <= bytes.length; at += 4 + (bytes[at + 2]! | (bytes[at + 3]! << 8)))
    fields.push([bytes[at]! | (bytes[at + 1]! << 8), bytes[at + 2]! | (bytes[at + 3]! << 8)]);
  return fields;
}

export function expectZipLikeArchive(actual: Uint8Array, expected: Uint8Array): void {
  const got = readZip(actual);
  const wanted = readZip(expected);
  expect(got.map(frame)).toEqual(wanted.map(frame));
  for (const [index, entry] of got.entries())
    expect(entryText(entry), entry.name).toBe(comparable(entry.name, entryText(wanted[index]!)));
}

export function expectZipEntries(actual: Uint8Array, expected: EntryTexts, exportedAt: string): void {
  const entries = readZip(actual);
  expect(entries.map(entry => entry.name)).toEqual(Object.keys(expected));
  const { time, date } = zipTime(exportedAt);
  for (const entry of entries) {
    expect(entryText(entry), entry.name).toBe(comparable(entry.name, expected[entry.name]!));
    expect({ time: entry.time, date: entry.date }, entry.name).toEqual({ time, date });
  }
}

export function microsOf(text: string): number | null {
  const match = /^(-?)(\d+)(?:\.(\d+))?$/.exec(text);
  if (!match) return null;
  const micros = Number(match[2]) * 1_000_000 + Number((match[3] ?? '').padEnd(6, '0').slice(0, 6));
  return match[1] ? -micros : micros;
}

export function utcUs(text: string): number | null {
  const match = /^(20\d{2})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,9}))?(?:Z|\+00:00)$/.exec(text);
  if (!match) return null;
  const [year, month, day, hour, minute, second] = match.slice(1, 7).map(Number) as [
    number,
    number,
    number,
    number,
    number,
    number,
  ];
  const date = new Date(Date.UTC(year, month - 1, day, hour, minute, second));
  if (date.getUTCDate() !== day || date.getUTCMonth() !== month - 1 || hour > 23 || minute > 59 || second > 59)
    return null;
  return date.getTime() * 1000 + Number((match[7] ?? '').padEnd(6, '0').slice(0, 6));
}

function withoutLines(csv: string, lines: readonly number[]): string {
  const records = csvRecords(csv);
  const last = records.at(-1) === '' ? records.length - 1 : records.length;
  for (const line of lines)
    if (!Number.isInteger(line) || line < 2 || line > last) throw new Error(`health.csv has no line ${line} to drop`);
  return records.filter((_, index) => !lines.includes(index + 1)).join('\n');
}

const UTC_COLUMNS = ['timestamp', 'startTimestamp', 'originalEndTimestamp'];
const BOUNDARY_ACTIONS = ['start', 'resume', 'pause', 'stop'];

function sameCell(entry: string, name: string, golden: string, actual: string, platform: string): boolean {
  if (golden === actual) return true;
  if (name === 'elapsedSeconds') {
    const [a, b] = [microsOf(golden), microsOf(actual)];
    return a !== null && b !== null && Math.abs(a - b) <= 1;
  }
  if (platform === 'ios' && entry === 'health.csv' && UTC_COLUMNS.includes(name))
    return utcUs(golden) !== null && utcUs(golden) === utcUs(actual);
  return false;
}

function expectRideTable(entry: string, actual: string, golden: string, platform: string, dropped: readonly number[]) {
  const wanted = entry === 'health.csv' ? withoutLines(withoutStartingBuildCells(golden), dropped) : golden;
  if (actual === wanted) return;
  const [got, want] = [csvRecords(actual), csvRecords(wanted)];
  expect(got[0], `${entry} header`).toBe(want[0]);
  expect(got.length, `${entry} records`).toBe(want.length);
  const names = csvCells(want[0]!);
  const differences: string[] = [];
  for (const [row, record] of want.entries()) {
    if (got[row] === record) continue;
    const [a, b] = [csvCells(record), csvCells(got[row]!)];
    if (a.length !== b.length) differences.push(`row ${row} has ${b.length} cells, not ${a.length}`);
    else
      a.forEach((cell, index) => {
        if (!sameCell(entry, names[index]!, cell, b[index]!, platform))
          differences.push(`row ${row} ${names[index]}: ${b[index]} is not ${cell}`);
      });
  }
  expect(differences.slice(0, 20), entry).toEqual([]);
}

export function csvDifferences(actual: string, golden: string, limit = 20): string[] {
  if (actual === golden) return [];
  const [got, want] = [csvRecords(actual), csvRecords(golden)];
  const differences: string[] = [];
  if (got.length !== want.length) differences.push(`${got.length} records, not ${want.length}`);
  const names = csvCells(want[0] ?? '');
  for (let row = 0; row < Math.min(got.length, want.length) && differences.length < limit; row++) {
    if (got[row] === want[row]) continue;
    const [a, b] = [csvCells(got[row]!), csvCells(want[row]!)];
    if (a.length !== b.length) differences.push(`row ${row} has ${a.length} cells, not ${b.length}`);
    else
      b.forEach((cell, index) => {
        if (a[index] !== cell) differences.push(`row ${row} ${names[index] ?? index}: ${a[index]} is not ${cell}`);
      });
  }
  return differences.slice(0, limit);
}

function activeBoundaries(events: string): number {
  const [header = '', ...records] = csvRecords(events);
  const action = csvCells(header).indexOf('action');
  return records.filter(record => BOUNDARY_ACTIONS.includes(csvCells(record)[action] ?? '')).length;
}

function expectRideDescriptor(actual: string, golden: string, boundaries: number): void {
  const wanted = withoutStartingBuildExport(golden);
  const [wantedSpans, actualSpans] = [jsonSpans(wanted), jsonSpans(actual)];
  const tolerated: [string, number][] = [
    ['/powerLog:ride/elapsedSeconds', 1],
    ['/powerLog:ride/timerSeconds', boundaries],
    ['/powerLog:ride/ownerTiming/elapsedSeconds', 1],
    ['/powerLog:ride/ownerTiming/timerSeconds', 1],
  ];
  const edits: [number, number, string][] = [];
  const differences: string[] = [];
  for (const [path, tolerance] of tolerated) {
    const [want, got] = [wantedSpans.get(path), actualSpans.get(path)];
    if (!want || !got) continue;
    const [a, b] = [wanted.slice(...want), actual.slice(...got)];
    if (a === b) continue;
    const [x, y] = [microsOf(a), microsOf(b)];
    if (x === null || y === null || Math.abs(x - y) > tolerance) differences.push(`${path}: ${b} is not ${a}`);
    else edits.push([...got, a]);
  }
  expect(differences, 'datapackage.json').toEqual([]);
  expect(spliced(actual, edits), 'datapackage.json').toBe(wanted);
}

export function expectRideGoldenArchive(
  actual: Uint8Array,
  golden: Uint8Array,
  platform: string,
  droppedHealthLines: readonly number[],
): void {
  const got = readZip(actual);
  const wanted = readZip(golden);
  expect(got.map(frame)).toEqual(wanted.map(frame));
  const text = (entries: ZipEntry[], name: string) =>
    entryText(entries.find(entry => entry.name.endsWith(`/${name}`))!);
  for (const [index, entry] of got.entries()) {
    const name = entry.name.slice(entry.name.lastIndexOf('/') + 1);
    const golden = entryText(wanted[index]!);
    if (name === 'datapackage.json')
      expectRideDescriptor(entryText(entry), golden, activeBoundaries(text(wanted, 'events.csv')));
    else expectRideTable(name, entryText(entry), golden, platform, droppedHealthLines);
  }
}

export function expectZipContainer(actual: Uint8Array, exportedAt: string): void {
  const entries = readZip(actual);
  const { time, date } = zipTime(exportedAt);
  for (const entry of entries) {
    expect(entry, entry.name).toMatchObject({ flags: 0x0800, method: 8, versionNeeded: 45, time, date });
    expect(entry.centralExtra).toEqual(new Uint8Array(0));
    expect(Array.from(entry.localExtra.subarray(0, 4))).toEqual([0x01, 0x00, 0x10, 0x00]);
    expect(entry.localSizes).toEqual([entry.size, entry.compressedSize]);
  }
  const verified = verifyWithPython(actual);
  expect(verified.zip64End).toBe(false);
  expect(verified.entries.map(entry => [entry.name, entry.text])).toEqual(
    entries.map(entry => [entry.name, entryText(entry)]),
  );
}
