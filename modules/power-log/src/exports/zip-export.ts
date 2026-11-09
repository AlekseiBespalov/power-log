import type { ExportConnection, ExportSessionView } from '../../../../shared/generated/ts/api';
import { CONSUMPTION_METRICS, HEALTH_METRICS, HEALTH_REPRESENTATIONS, table } from './catalog';
import { descriptorJson, type ExportConnectionEntry } from './descriptor';
import type { ExportPageReader, ReaderPage } from './reader';
import type { ExportSlicer } from './slicer';
import {
  CELL_COUNT,
  CELL_ID,
  TextChunks,
  checkProducer,
  csvBoolean,
  csvHeader,
  csvInteger,
  csvNumber,
  csvText,
  layoutColumns,
  tableError,
  tableLayout,
  writeRows,
} from './text';
import {
  ExportError,
  type ExportPlatform,
  type ExportSink,
  type HealthKind,
  type HealthRepresentationDefinition,
  type ProducerSet,
} from './types';
import { ZipWriter, zipTime } from './zip';

const TELEMETRY_LAYOUT = tableLayout(table('telemetry'), 'a telemetry reading', {
  activeInterval: CELL_ID,
  run: CELL_COUNT,
  connection: CELL_ID,
});
const GPS_LAYOUT = tableLayout(table('gps'), 'a GPS fix', { activeInterval: CELL_ID, run: CELL_COUNT });
const CONSUMPTION_TABLE = table('battery_consumption');
const HEALTH_TABLE = table('health');
const EVENTS_TABLE = table('events');

const PRODUCER_SETS: readonly (readonly string[])[] = [[], ['phone'], ['watch'], ['phone', 'watch']];

function producerSet(value: readonly string[], stream: string): ProducerSet {
  if (PRODUCER_SETS.some(set => set.length === value.length && set.every((p, i) => value[i] === p)))
    return [...value] as ProducerSet;
  throw new ExportError(
    'readFailed',
    `The ride data could not be read for export: its ${stream} sources are not valid.`,
  );
}

const integers = (page: ReaderPage, name: string): Int32Array => {
  const column = page.columns[name] as Float64Array | undefined;
  return Int32Array.from({ length: page.rows }, (_, i) => {
    const value = column?.[i];
    return value === undefined || Number.isNaN(value) ? 0 : value;
  });
};

const texts = (page: ReaderPage, name: string) => page.columns[name] as readonly (string | null)[] | undefined;
const numbers = (page: ReaderPage, name: string) => page.columns[name] as Float64Array | undefined;

export async function telemetryTable(
  reader: ExportPageReader,
  view: ExportSessionView,
  out: TextChunks,
  slicer: ExportSlicer,
  imported = false,
): Promise<ExportConnectionEntry[]> {
  out.line(csvHeader(TELEMETRY_LAYOUT.table));
  const connections: ExportConnectionEntry[] = [];
  const identities = new Map<string, ExportConnection>();
  const assigned = new Map<string, number>();
  const numberOf = (token: string | null | undefined): number => {
    if (token === null || token === undefined) return 0;
    if (imported) return Number(token);
    const known = assigned.get(token);
    if (known !== undefined) return known;
    const identity = identities.get(token);
    if (!identity) throw new ExportError('unknown', 'A telemetry row names a connection without its identity.');
    const number = connections.length + 1;
    assigned.set(token, number);
    connections.push({
      connection: number,
      vendor: identity.vendor,
      model: identity.model,
      firmware: identity.firmware,
      protocol: identity.protocol,
    });
    return number;
  };
  for await (const page of reader.pages(view.sessionId, 'telemetry')) {
    for (const identity of page.connections)
      if (!identities.has(identity.token)) identities.set(identity.token, identity);
    const tokens = texts(page, 'connection');
    const connection = Int32Array.from({ length: page.rows }, (_, i) => numberOf(tokens?.[i]));
    const columns = layoutColumns(TELEMETRY_LAYOUT, page.columns, {
      activeInterval: integers(page, 'activeInterval'),
      run: integers(page, 'run'),
      connection,
    });
    await writeRows(out, slicer, TELEMETRY_LAYOUT, columns, page.rows);
  }
  return connections;
}

async function consumptionTable(reader: ExportPageReader, view: ExportSessionView, out: TextChunks): Promise<void> {
  out.line(CONSUMPTION_TABLE.fields.map(field => field.name).join(','));
  const subject = 'derived battery reading';
  for await (const page of reader.pages(view.sessionId, 'consumption')) {
    const timestamps = texts(page, 'timestamp');
    const number = (name: string, i: number) => numbers(page, name)?.[i] ?? NaN;
    for (let i = 0; i < page.rows; i++)
      out.line(
        [
          csvText(timestamps?.[i]),
          csvNumber(number('elapsedSeconds', i), subject, 'elapsedSeconds'),
          ...CONSUMPTION_METRICS.map(metric => csvNumber(number(metric, i), subject, metric)),
        ].join(','),
      );
    if (out.due) await out.drain();
  }
}

async function gpsTable(
  reader: ExportPageReader,
  view: ExportSessionView,
  out: TextChunks,
  slicer: ExportSlicer,
  producers: ProducerSet,
): Promise<void> {
  out.line(csvHeader(GPS_LAYOUT.table));
  for await (const page of reader.pages(view.sessionId, 'gps')) {
    const sources = texts(page, 'producer');
    for (let i = 0; i < page.rows; i++) checkProducer(GPS_LAYOUT.subject, sources?.[i], producers);
    const columns = layoutColumns(GPS_LAYOUT, page.columns, {
      activeInterval: integers(page, 'activeInterval'),
      run: integers(page, 'run'),
    });
    await writeRows(out, slicer, GPS_LAYOUT, columns, page.rows);
  }
}

const HEALTH_SUBJECT = 'a Health record';
const PROVENANCE_FIELDS = HEALTH_TABLE.fields.slice(HEALTH_TABLE.fields.findIndex(field => field.name === 'provider'));
const METRIC_INDEX = new Map<string, number>(
  HEALTH_METRICS.flatMap(
    (metric, index) =>
      [
        [metric.identifier, index],
        [metric.metric, index],
      ] as [string, number][],
  ),
);
const REPRESENTATIONS: Readonly<Record<string, HealthRepresentationDefinition>> = HEALTH_REPRESENTATIONS;
const NOT_MEASURED = 'notMeasured';

function scaler(factor: number): (value: number) => number {
  if (factor === 1) return value => value;
  const text = String(factor);
  if (!/^10+$/.test(text)) return value => value * factor;
  const digits = text.length - 1;
  return value => {
    if (!Number.isFinite(value)) return value;
    const [mantissa, exponent] = String(value).split('e');
    return Number(`${mantissa}e${Number(exponent ?? 0) + digits}`);
  };
}

const SCALES = HEALTH_METRICS.map(metric => scaler(metric.factor));

function rowKind(representation: string | null, count: number): HealthKind | null | typeof NOT_MEASURED {
  if (representation === null) return null;
  if (!Object.hasOwn(REPRESENTATIONS, representation))
    throw tableError(
      HEALTH_SUBJECT,
      `has the Health representation "${representation}", which this export does not know`,
    );
  const definition = REPRESENTATIONS[representation]!;
  if (definition.kind === null) return NOT_MEASURED;
  return definition.condensedKind !== undefined && count > 1 ? definition.condensedKind : definition.kind;
}

function valueLine(
  prefix: string,
  metric: string,
  value: number,
  unit: string | null,
  kind: HealthKind | null,
  count: number,
  suffix: string,
): string {
  const sampleCount = kind === 'aggregate' ? csvInteger(count, HEALTH_SUBJECT, 'sampleCount') : '';
  return `${prefix}${csvText(metric)},${csvNumber(value, HEALTH_SUBJECT, 'value')},${csvText(unit)},${kind ?? ''},${sampleCount}${suffix}`;
}

async function healthTable(
  reader: ExportPageReader,
  view: ExportSessionView,
  out: TextChunks,
  producers: ProducerSet,
): Promise<void> {
  out.line(csvHeader(HEALTH_TABLE));
  for await (const page of reader.pages(view.sessionId, 'healthZip')) {
    const interval = integers(page, 'activeInterval');
    const named = HEALTH_METRICS.map(metric => (metric.key === null ? undefined : numbers(page, metric.key)));
    for (let i = 0; i < page.rows; i++) {
      const count = numbers(page, 'sampleCount')?.[i] ?? NaN;
      const representations = texts(page, 'representation');
      const kind = rowKind(representations?.[i] ?? null, count);
      const provenance = texts(page, 'originalEventId')?.[i] != null;
      if (kind === NOT_MEASURED && !provenance) continue;
      const producer = checkProducer(HEALTH_SUBJECT, texts(page, 'producer')?.[i], producers);
      const identifier = texts(page, 'identifier')?.[i] ?? null;
      const generic = numbers(page, 'value')?.[i] ?? NaN;
      if (identifier === null && !Number.isNaN(generic))
        throw tableError(HEALTH_SUBJECT, 'has a value without its identifier');
      const index = identifier === null ? undefined : METRIC_INDEX.get(identifier);
      const time = csvNumber(numbers(page, 'elapsedSeconds')?.[i] ?? NaN, HEALTH_SUBJECT, 'elapsedSeconds');
      const active = interval[i] === 0 ? '' : interval[i];
      const prefix = `${csvText(texts(page, 'timestamp')?.[i])},${csvText(texts(page, 'sampleStart')?.[i])},${time},${active},${csvText(producer)},`;
      const suffix = `,${PROVENANCE_FIELDS.map(field => csvText(texts(page, field.reads[0]!)?.[i])).join(',')}`;
      if (kind === NOT_MEASURED) {
        out.line(`${prefix}${csvText(identifier)},,,${csvText(representations?.[i])},${suffix}`);
        continue;
      }
      for (let m = 0; m < HEALTH_METRICS.length; m++) {
        let value = named[m]?.[i] ?? NaN;
        if (Number.isNaN(value) && index === m) value = generic;
        if (Number.isNaN(value)) continue;
        const metric = HEALTH_METRICS[m]!;
        const units = texts(page, 'unit');
        out.line(
          valueLine(
            prefix,
            metric.metric,
            identifier === metric.metric && units?.[i] === metric.unit ? value : SCALES[m]!(value),
            metric.unit,
            kind ?? metric.kindWithoutRepresentation,
            count,
            suffix,
          ),
        );
      }
      if (identifier !== null && index === undefined && !Number.isNaN(generic))
        out.line(valueLine(prefix, identifier, generic, texts(page, 'unit')?.[i] ?? null, kind, count, suffix));
    }
    if (out.due) await out.drain();
  }
}

const EVENT_SUBJECT = 'a ride event';
const EVENT_SOURCES: Record<ExportPlatform, readonly string[]> = {
  ios: ['phone', 'watch'],
  android: ['phone'],
  web: ['browser'],
};
type EventAction = { readonly action: string; readonly interrupted: boolean };
const plain = (action: string): [string, EventAction] => [action, { action, interrupted: false }];
const COMMON = [plain('start'), plain('pause'), plain('resume'), plain('lap'), plain('stop')];
const INTERRUPTION: [string, EventAction] = ['interruption', { action: 'pause', interrupted: true }];
const EVENT_ACTIONS: Record<ExportPlatform, ReadonlyMap<string, EventAction>> = {
  ios: new Map([...COMMON, INTERRUPTION]),
  android: new Map(COMMON),
  web: new Map([...COMMON, INTERRUPTION]),
};

async function eventsTable(
  reader: ExportPageReader,
  view: ExportSessionView,
  out: TextChunks,
  platform: ExportPlatform,
) {
  out.line(csvHeader(EVENTS_TABLE));
  for await (const page of reader.pages(view.sessionId, 'lifecycle')) {
    for (let i = 0; i < page.rows; i++) {
      const flagged = numbers(page, 'interrupted')?.[i] === 1;
      const action = texts(page, 'action')?.[i] ?? null;
      const stored = action === 'pause' && flagged ? 'interruption' : action;
      const mapped = stored === null ? undefined : EVENT_ACTIONS[platform].get(stored);
      if (!mapped)
        throw tableError(EVENT_SUBJECT, `has the action ${stored ?? 'none'}, which this export does not know`);
      const producer = checkProducer(EVENT_SUBJECT, texts(page, 'producer')?.[i], EVENT_SOURCES[platform]);
      const time = csvNumber(numbers(page, 'elapsedSeconds')?.[i] ?? NaN, EVENT_SUBJECT, 'elapsedSeconds');
      const timer = csvNumber(numbers(page, 'timerSeconds')?.[i] ?? NaN, EVENT_SUBJECT, 'timerSeconds');
      const interrupted = mapped.interrupted || flagged;
      out.line(
        `${csvText(texts(page, 'timestamp')?.[i])},${time},${timer},${mapped.action},${csvBoolean(interrupted)},${producer}`,
      );
    }
    if (out.due) await out.drain();
  }
}

export async function encodeZip(
  reader: ExportPageReader,
  view: ExportSessionView,
  sink: ExportSink,
  slicer: ExportSlicer,
  rideId: string,
  exportedAt: string,
): Promise<void> {
  const zip = new ZipWriter(sink, zipTime(exportedAt));
  const out = new TextChunks(zip);
  const producers = {
    gps: producerSet(view.producers.gps, 'GPS'),
    health: producerSet(view.producers.health, 'Health'),
  };
  const entry = async (path: string, write: () => Promise<unknown>) => {
    await zip.begin(`PowerLog-original/${path}`);
    await write();
    await out.end();
    await zip.end();
  };
  let connections: ExportConnectionEntry[] = [];
  await entry('telemetry.csv', async () => (connections = await telemetryTable(reader, view, out, slicer)));
  await entry('battery-consumption.csv', () => consumptionTable(reader, view, out));
  await entry('gps.csv', () => gpsTable(reader, view, out, slicer, producers.gps));
  await entry('health.csv', () => healthTable(reader, view, out, producers.health));
  await entry('events.csv', () => eventsTable(reader, view, out, view.platform));
  const consumption =
    view.consumption.kind === 'available'
      ? ({ status: 'available' } as const)
      : ({ status: 'unavailable', reason: view.consumption.reason } as const);
  const metadata = view.metadata!;
  const descriptor = descriptorJson({
    consumption,
    rideId,
    created: exportedAt,
    metadata: { ...metadata, health: { ...metadata.health, export: null } },
    elapsedSeconds: view.elapsedEnd,
    timerSeconds: view.timerSeconds,
    connections,
    producers,
  });
  await entry('datapackage.json', async () => out.line(descriptor));
  await zip.finish();
}
