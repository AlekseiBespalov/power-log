import { DISTANCE_SOURCE_LABELS } from '../core/distance';
import { EXPORT_PAGE_MAX_BYTES, EXPORT_PAGE_MAX_ROWS, PROJECTIONS } from '../core/export/catalog';
import {
  ExportError,
  type ExportConnection,
  type ExportConsumer,
  type ExportCursor,
  type ExportKind,
  type ExportOpenRequest,
  type ExportOpenResult,
  type ExportPage,
  type ExportPageRequest,
  type ExportRideMetadata,
  type ExportSource,
  type ProjectionColumn,
  type ProjectionName,
} from '../core/export/types';
import type { TelemetrySample } from '../core/types';
import { isRecord, validateSample, validateTimestamp } from '../core/validation';
import {
  browserDistanceIntervals,
  ensureBrowserDistance,
  type BrowserDistanceHandle,
  type BrowserDistanceInterval,
} from './browser-distance-store';
import { browserRideStore, type BrowserRide, type RideLifecycleRow } from './browser-ride-store';

const READ_BATCH = 1024;
const ACTIONS: ReadonlySet<unknown> = new Set<RideLifecycleRow['action']>([
  'start',
  'pause',
  'resume',
  'lap',
  'save',
  'interrupted',
]);
const CONSUMERS: Record<ExportKind, readonly ExportConsumer[]> = { zip: ['zip'], fit: ['fit', 'discovery'] };

type TelemetryRow = TelemetrySample & { connectionEpoch: string; active: boolean; interval: number };
type CatalogColumn<P extends ProjectionName> = (typeof PROJECTIONS)[P]['columns'][number];
type WebColumn<C, T> = C extends { name: infer N; type: T; platforms: readonly (infer X)[] }
  ? 'web' extends X
    ? N
    : never
  : never;
interface Accessors<P extends ProjectionName, R> {
  numbers: { [K in WebColumn<CatalogColumn<P>, 'number'>]: (row: R) => number };
  strings: { [K in WebColumn<CatalogColumn<P>, 'string'>]: (row: R) => string | null };
}
interface Plan<R> {
  numbers: [string, (row: R) => number][];
  strings: [string, (row: R) => string | null][];
}
interface Stream<R> {
  plan: Plan<R>;
  width: number;
  read(after: ExportCursor | null, count: number): Promise<unknown[]>;
  row(value: unknown): R;
  cursor(row: R): ExportCursor;
}
interface Session {
  token: string;
  id: string;
  kind: ExportKind;
  revision: number;
  distance: BrowserDistanceHandle | null;
  connections: Set<string>;
}

const TELEMETRY: Accessors<'telemetry', TelemetryRow> = {
  numbers: {
    elapsedSeconds: row => row.elapsedSeconds,
    active: row => (row.active ? 1 : 0),
    interval: row => row.interval,
    humanPowerW: row => row.humanPowerW,
    cadenceRpm: row => row.cadenceRpm,
    motorInputPowerW: row => row.motorInputPowerW,
    batteryVoltageV: row => row.batteryVoltageV,
    batteryCurrentA: row => row.batteryCurrentA,
    motorCurrentA: row => row.motorCurrentA,
    motorRpm: row => row.motorRpm,
    pedalTorqueNm: row => row.pedalTorqueNm,
    controllerTempC: row => row.controllerTempC,
    motorTempC: row => row.motorTempC,
    consumedAh: row => row.consumedAh,
    consumedWh: row => row.consumedWh,
    throttleVoltageV: row => row.throttleVoltageV,
    faultCode: row => row.faultCode,
    assistLevel: row => row.assistLevel,
    controllerSpeedMps: row => row.controllerSpeedMps ?? NaN,
    raceMode: row => row.raceMode,
    speedRaw: row => row.speedRaw,
  },
  strings: { timestamp: row => row.timestamp, connection: row => row.connectionEpoch },
};
const LIFECYCLE: Accessors<'lifecycle', RideLifecycleRow> = {
  numbers: { elapsedSeconds: row => row.elapsedSeconds },
  strings: { timestamp: row => row.timestamp, producer: () => 'browser', action: row => row.action },
};
const DISTANCE: Accessors<'distance', BrowserDistanceInterval> = {
  numbers: {
    start: row => row.start,
    end: row => row.end,
    meters: row => row.distance,
    segment: row => row.segment,
    startSpeed: row => row.startSpeed,
    endSpeed: row => row.endSpeed,
  },
  strings: {},
};

function plan<P extends ProjectionName, R>(projection: P, kind: ExportKind, accessors: Accessors<P, R>): Plan<R> {
  const columns: readonly ProjectionColumn[] = PROJECTIONS[projection].columns;
  const numbers: Record<string, (row: R) => number> = accessors.numbers;
  const strings: Record<string, (row: R) => string | null> = accessors.strings;
  const result: Plan<R> = { numbers: [], strings: [] };
  for (const column of columns) {
    if (!column.platforms.includes('web') || !column.consumers.some(item => CONSUMERS[kind].includes(item))) continue;
    if (column.type === 'number') result.numbers.push([column.name, numbers[column.name]!]);
    else result.strings.push([column.name, strings[column.name]!]);
  }
  return result;
}
const PLANS = {
  telemetry: { zip: plan('telemetry', 'zip', TELEMETRY), fit: plan('telemetry', 'fit', TELEMETRY) },
  lifecycle: { zip: plan('lifecycle', 'zip', LIFECYCLE), fit: plan('lifecycle', 'fit', LIFECYCLE) },
  distance: { zip: plan('distance', 'zip', DISTANCE), fit: plan('distance', 'fit', DISTANCE) },
};
const NO_COLUMNS: Plan<never> = { numbers: [], strings: [] };

function utf8Length(text: string): number {
  let bytes = 0;
  for (let i = 0; i < text.length; i++) {
    const code = text.charCodeAt(i);
    if (code < 0x80) bytes += 1;
    else if (code < 0x800) bytes += 2;
    else if (code >= 0xd800 && code <= 0xdbff && (text.charCodeAt(i + 1) & 0xfc00) === 0xdc00) {
      bytes += 4;
      i++;
    } else bytes += 3;
  }
  return bytes;
}
function rowBytes<R>(plan: Plan<R>, row: R): number {
  let bytes = plan.numbers.length * 8;
  for (const [, read] of plan.strings) {
    const value = read(row);
    if (value !== null) bytes += utf8Length(value);
  }
  return bytes;
}
function columnsOf<R>(plan: Plan<R>, rows: readonly R[]): ExportPage['columns'] {
  const columns: Record<string, Float64Array | (string | null)[]> = {};
  for (const [name, read] of plan.numbers) {
    const values = new Float64Array(rows.length);
    for (let i = 0; i < rows.length; i++) values[i] = read(rows[i]!);
    columns[name] = values;
  }
  for (const [name, read] of plan.strings) columns[name] = rows.map(read);
  return columns as ExportPage['columns'];
}

const unreadable = (detail?: unknown) =>
  new ExportError(
    'gate',
    `Power Log can't read this ride's saved data, so it can't be exported.${detail instanceof Error ? ` ${detail.message.replace(/\.?$/, '.')}` : ''}`,
  );
const deleted = () => new ExportError('deleted', "This ride no longer exists, so it can't be exported.");
const changed = () => new ExportError('changed', 'This ride changed while it was exporting. Export it again.');
const closed = () => new ExportError('cancelled', 'This export was closed.');
async function stored<T>(read: () => Promise<T>): Promise<T> {
  try {
    return await read();
  } catch (error) {
    if (error instanceof ExportError || error instanceof DOMException) throw error;
    throw unreadable(error);
  }
}

function telemetryRow(value: unknown, id: string): TelemetryRow {
  let sample: TelemetrySample;
  try {
    sample = validateSample(value);
  } catch (error) {
    throw unreadable(error);
  }
  const row = value as Record<string, unknown>;
  const interval = row.interval;
  if (
    row.recordingId !== id ||
    typeof row.active !== 'boolean' ||
    typeof interval !== 'number' ||
    !Number.isSafeInteger(interval) ||
    interval < 0 ||
    !sample.connectionEpoch
  )
    throw unreadable();
  return { ...sample, connectionEpoch: sample.connectionEpoch, active: row.active, interval };
}
function lifecycleRow(value: unknown, id: string): RideLifecycleRow {
  if (
    !isRecord(value) ||
    value.recordingId !== id ||
    typeof value.revision !== 'number' ||
    !Number.isSafeInteger(value.revision) ||
    value.revision < 1 ||
    !ACTIONS.has(value.action) ||
    typeof value.elapsedSeconds !== 'number' ||
    !Number.isFinite(value.elapsedSeconds) ||
    value.elapsedSeconds < 0
  )
    throw unreadable();
  try {
    validateTimestamp(value.timestamp);
  } catch (error) {
    throw unreadable(error);
  }
  return value as RideLifecycleRow;
}
function distanceRow(value: unknown): BrowserDistanceInterval {
  const measure = (item: unknown): item is number => typeof item === 'number' && Number.isFinite(item) && item >= 0;
  const counter = (item: unknown) => typeof item === 'number' && Number.isSafeInteger(item) && item >= 0;
  if (
    !isRecord(value) ||
    !measure(value.start) ||
    !measure(value.end) ||
    value.end <= value.start ||
    !measure(value.distance) ||
    !measure(value.startSpeed) ||
    !measure(value.endSpeed) ||
    !counter(value.segment) ||
    !counter(value.sequence)
  )
    throw unreadable();
  return value as unknown as BrowserDistanceInterval;
}
function exportMetadata(record: BrowserRide, endedAt: string): ExportRideMetadata {
  return {
    startedAt: record.startedAt,
    endedAt,
    ownerTiming: {
      timestamp: record.checkpointAt,
      elapsedSeconds: record.elapsedSeconds,
      timerSeconds: record.timerSeconds,
    },
    indoor: record.indoor,
    interrupted: record.interrupted,
    watchEnabled: false,
    saveToHealth: false,
    recordGPS: false,
    health: { provider: null, state: 'notRequested', workoutUUID: null, export: null },
    watchSyncState: 'notRequired',
    finalizationState: 'complete',
    example: false,
    sampleHz: null,
  };
}
function cursorOf(after: ExportCursor | null, width: number): ExportCursor | null {
  if (after === null) return null;
  if (
    !Array.isArray(after) ||
    after.length !== width ||
    !after.every(value => typeof value === 'number' && Number.isFinite(value))
  )
    throw new ExportError('cursor', 'The export lost its place in the ride. Export it again.');
  return after;
}
function pageLimit(value: number | undefined, ceiling: number): number {
  if (value === undefined) return ceiling;
  if (!Number.isSafeInteger(value) || value < 1 || value > ceiling) throw new Error('Invalid export page limit');
  return value;
}

export class BrowserExportSource implements ExportSource {
  private readonly sessions = new Map<string, Session>();
  private readonly maxRows: number;
  private readonly maxBytes: number;
  constructor(limits: { maxRows?: number; maxBytes?: number } = {}) {
    this.maxRows = pageLimit(limits.maxRows, EXPORT_PAGE_MAX_ROWS);
    this.maxBytes = pageLimit(limits.maxBytes, EXPORT_PAGE_MAX_BYTES);
  }

  async open(request: ExportOpenRequest): Promise<ExportOpenResult> {
    if (request.kind !== 'fit' && request.kind !== 'zip')
      throw new ExportError('unsupported', 'Choose a FIT or ride-data export.');
    if (request.context.platform !== 'web')
      throw new ExportError('unsupported', 'This browser can export only its own rides.');
    const selection = request.distanceSource ?? 'auto';
    if (!Object.hasOwn(DISTANCE_SOURCE_LABELS, selection))
      throw new ExportError('unsupported', 'Choose an available distance source.');
    const record = await stored(() => browserRideStore.find(request.rideId));
    if (!record) throw deleted();
    if (record.phase !== 'completed' || record.endedAt === undefined)
      throw new ExportError('gate', 'Save the ride before exporting it.');
    let distance: BrowserDistanceHandle | null = null;
    if (request.kind === 'fit') {
      let handle: BrowserDistanceHandle | null = null;
      try {
        handle = await ensureBrowserDistance(record.id, selection);
      } catch (error) {
        if (!(await stored(() => browserRideStore.find(record.id)))) throw deleted();
        if (error instanceof DOMException) throw error;
        // Unreadable distance data leaves the FIT export without distance.
      }
      if (handle && handle.record.revision !== record.revision) throw changed();
      if (handle?.info.selected?.source === 'controller') distance = handle;
    }
    const token = crypto.randomUUID();
    this.sessions.set(token, {
      token,
      id: record.id,
      kind: request.kind,
      revision: record.revision,
      distance,
      connections: new Set(),
    });
    return {
      session: token,
      metadata: exportMetadata(record, record.endedAt),
      elapsedEnd: record.elapsedSeconds,
      producers: { gps: [], health: [] },
      distanceProfile: distance ? { source: 'controller', kind: 'controller' } : null,
    };
  }

  async page<P extends ProjectionName>(request: ExportPageRequest<P>): Promise<ExportPage<P>> {
    const session = this.sessions.get(request.session);
    if (!session) throw closed();
    const projection: ProjectionName = request.projection;
    const kinds: readonly ExportKind[] = Object.hasOwn(PROJECTIONS, projection) ? PROJECTIONS[projection].kinds : [];
    if (!kinds.includes(session.kind))
      throw new ExportError('unsupported', "Browser rides can't provide this export data.");
    let page: ExportPage;
    if (projection === 'telemetry') page = await this.telemetry(session, request.after);
    else if (projection === 'lifecycle') page = await this.paged(session, this.lifecycle(session), request.after);
    else if (projection === 'distance' && session.distance)
      page = await this.paged(session, this.distance(session, session.distance), request.after);
    else {
      this.pin(session, await stored(() => browserRideStore.find(session.id)));
      const plan = projection === 'distance' ? PLANS.distance[session.kind] : NO_COLUMNS;
      page = { rows: 0, last: null, done: true, columns: columnsOf(plan, []) };
    }
    return page as ExportPage<P>;
  }

  async close(session: string): Promise<void> {
    this.sessions.delete(session);
  }

  private pin(session: Session, record: BrowserRide | undefined): void {
    if (this.sessions.get(session.token) !== session) throw closed();
    if (!record) throw deleted();
    if (record.revision !== session.revision) throw changed();
  }

  private async telemetry(session: Session, after: ExportCursor | null): Promise<ExportPage> {
    const { rows, done, last } = await this.collect(
      session,
      {
        plan: PLANS.telemetry[session.kind],
        width: 2,
        read: async (cursor, count) => {
          const result = await stored(() =>
            browserRideStore.samplesAfter(session.id, cursor as [number, number] | null, count),
          );
          this.pin(session, result.record);
          return result.rows;
        },
        row: value => telemetryRow(value, session.id),
        cursor: row => [row.elapsedSeconds, row.sequence],
      },
      after,
    );
    const known = after === null ? new Set<string>() : session.connections;
    const connections: ExportConnection[] = [];
    for (const row of rows) {
      if (known.has(row.connectionEpoch)) continue;
      known.add(row.connectionEpoch);
      connections.push({
        token: row.connectionEpoch,
        vendor: 'cyc',
        model: row.controllerModel ?? null,
        firmware: row.firmwareLabel ?? null,
        protocol: row.controllerProtocol ?? null,
      });
    }
    session.connections = known;
    return { rows: rows.length, last, done, connections, columns: columnsOf(PLANS.telemetry[session.kind], rows) };
  }

  private lifecycle(session: Session): Stream<RideLifecycleRow> {
    return {
      plan: PLANS.lifecycle[session.kind],
      width: 1,
      read: async (cursor, count) => {
        const result = await stored(() =>
          browserRideStore.lifecycleAfter(session.id, cursor ? cursor[0]! : null, count),
        );
        this.pin(session, result.record);
        return result.rows;
      },
      row: value => lifecycleRow(value, session.id),
      cursor: row => [row.revision],
    };
  }

  private distance(session: Session, handle: BrowserDistanceHandle): Stream<BrowserDistanceInterval> {
    return {
      plan: PLANS.distance[session.kind],
      width: 2,
      read: async (cursor, count) => {
        const rows = await stored(() => browserDistanceIntervals(handle, cursor as [number, number] | null, count));
        if (rows) {
          this.pin(session, handle.record);
          return rows;
        }
        this.pin(session, await stored(() => browserRideStore.find(session.id)));
        throw changed();
      },
      row: distanceRow,
      cursor: row => [row.end, row.sequence],
    };
  }

  private async paged<R>(session: Session, stream: Stream<R>, after: ExportCursor | null): Promise<ExportPage> {
    const { rows, done, last } = await this.collect(session, stream, after);
    return { rows: rows.length, last, done, columns: columnsOf(stream.plan, rows) };
  }

  /** Bounded reads with a one-row lookahead, so a page reports `done` without a trailing empty page. */
  private async collect<R>(
    session: Session,
    stream: Stream<R>,
    after: ExportCursor | null,
  ): Promise<{ rows: R[]; done: boolean; last: ExportCursor | null }> {
    let cursor = cursorOf(after, stream.width);
    const rows: R[] = [];
    let bytes = 0;
    const finish = (done: boolean) => {
      if (this.sessions.get(session.token) !== session) throw closed();
      return { rows, done, last: rows.length ? stream.cursor(rows[rows.length - 1]!) : null };
    };
    while (true) {
      const want = Math.min(this.maxRows - rows.length, READ_BATCH);
      const batch = await stream.read(cursor, want + 1);
      for (let i = 0; i < Math.min(batch.length, want); i++) {
        const row = stream.row(batch[i]);
        const size = rowBytes(stream.plan, row);
        if (bytes + size > this.maxBytes) {
          if (!rows.length) throw new ExportError('limit', 'A saved ride observation is too large to export.');
          return finish(false);
        }
        rows.push(row);
        bytes += size;
      }
      if (batch.length <= want) return finish(true);
      if (rows.length === this.maxRows) return finish(false);
      cursor = stream.cursor(rows[rows.length - 1]!);
    }
  }
}

export const browserExportSource = new BrowserExportSource();
