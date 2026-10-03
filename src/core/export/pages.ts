import { EXPORT_PAGE_MAX_BYTES, EXPORT_PAGE_MAX_ROWS, PROJECTIONS } from './catalog';
import {
  ExportError,
  type ExportColumns,
  type ExportConnection,
  type ExportConsumer,
  type ExportCursor,
  type ExportKind,
  type ExportPage,
  type ExportPageRequest,
  type ExportPlatform,
  type ExportSource,
  type ExportVendor,
  type NativeExportPage,
  type ProjectionColumn,
  type ProjectionName,
} from './types';

export const EXPORT_SLICE_MS = 40;

export interface ExportSlicer {
  tick(): Promise<void>;
}

export interface ProjectionPage<P extends ProjectionName = ProjectionName> {
  readonly rows: number;
  readonly columns: ExportColumns<P>;
  readonly connection: Int32Array | null;
}

export interface ExportConnectionEntry {
  readonly connection: number;
  readonly vendor: ExportVendor;
  readonly model: string | null;
  readonly firmware: string | null;
  readonly protocol: string | null;
}

export interface ExportReadOptions {
  readonly platform: ExportPlatform;
  readonly kind: ExportKind;
  readonly slicer: ExportSlicer;
  readonly signal?: AbortSignal;
}

type Column = Float64Array | Uint8Array | readonly unknown[] | undefined;
type ColumnMap = Readonly<Record<string, Column>>;
type Envelope = Pick<ExportPage, 'rows' | 'last' | 'done' | 'connections'>;

const ready = Promise.resolve();
const VENDORS: readonly string[] = ['cyc'];
const CONSUMERS: Record<ExportKind, readonly ExportConsumer[]> = { zip: ['zip'], fit: ['fit', 'discovery'] };
const STREAMS: Record<ProjectionName, string> = {
  telemetry: 'telemetry',
  gps: 'gps',
  gpsDiscovery: 'gps',
  healthZip: 'health',
  healthFit: 'health',
  lifecycle: 'lifecycle',
  distance: 'distance',
};

const now = () => (typeof performance === 'undefined' ? Date.now() : performance.now());
export const exportCancelled = () => new ExportError('cancelled', 'The export was cancelled.');
const malformed = (projection: ProjectionName, problem: string) =>
  new ExportError('page', `The ride data could not be read for export: a ${projection} page ${problem}.`);

export function createSlicer(signal?: AbortSignal, budgetMs = EXPORT_SLICE_MS): ExportSlicer {
  let started = now();
  const pause = async () => {
    await new Promise<void>(resolve => setTimeout(resolve, 0));
    started = now();
    if (signal?.aborted) throw exportCancelled();
  };
  return {
    tick() {
      if (signal?.aborted) return Promise.reject(exportCancelled());
      return now() - started < budgetMs ? ready : pause();
    },
  };
}

export function projectionColumns(
  projection: ProjectionName,
  platform: ExportPlatform,
  kind: ExportKind,
): readonly ProjectionColumn[] {
  const consumers = CONSUMERS[kind];
  return (PROJECTIONS[projection].columns as readonly ProjectionColumn[]).filter(
    column => column.platforms.includes(platform) && column.consumers.some(consumer => consumers.includes(consumer)),
  );
}

function definition(projection: ProjectionName, name: string): ProjectionColumn {
  const column = (PROJECTIONS[projection].columns as readonly ProjectionColumn[]).find(item => item.name === name);
  if (!column) throw malformed(projection, `has an unknown column ${name}`);
  return column;
}

function checkEnvelope(projection: ProjectionName, page: Envelope): void {
  const { rows, done, last } = page;
  if (!Number.isSafeInteger(rows) || rows < 0) throw malformed(projection, 'has an invalid row count');
  if (rows > EXPORT_PAGE_MAX_ROWS) throw malformed(projection, `has more than ${EXPORT_PAGE_MAX_ROWS} rows`);
  if (typeof done !== 'boolean') throw malformed(projection, 'has no completion flag');
  if (last !== null && (!Array.isArray(last) || last.length === 0 || !last.every(value => Number.isFinite(value))))
    throw malformed(projection, 'has an invalid cursor');
  if (rows === 0 && (!done || last !== null)) throw malformed(projection, 'is empty before the end of its stream');
  if (rows > 0 && last === null) throw malformed(projection, 'has rows without a cursor');
  if (page.connections !== undefined && !Array.isArray(page.connections))
    throw malformed(projection, 'has an invalid connection list');
}

function utf8Length(text: string): number {
  let bytes = text.length;
  for (let i = 0; i < text.length; i++) {
    const code = text.charCodeAt(i);
    if (code < 0x80) continue;
    if (code < 0x800) {
      bytes += 1;
      continue;
    }
    bytes += 2;
    // A surrogate pair is 4 bytes; a lone surrogate encodes as U+FFFD, 3 bytes.
    if (code >= 0xd800 && code < 0xdc00 && (text.charCodeAt(i + 1) & 0xfc00) === 0xdc00) i++;
  }
  return bytes;
}

function checkColumns(
  projection: ProjectionName,
  page: Envelope & { columns: object },
  expected?: readonly ProjectionColumn[],
): void {
  const rows = page.rows;
  const columns = page.columns as ColumnMap;
  let numericBytes = 0;
  let stringUnits = 0;
  const strings: (readonly unknown[])[] = [];
  for (const name of Object.keys(columns)) {
    const value = columns[name];
    if (value === undefined) continue;
    const column = definition(projection, name);
    if (expected && !expected.includes(column))
      throw malformed(projection, `has column ${name}, which this export does not read`);
    if (column.type === 'number') {
      if (!(value instanceof Float64Array)) throw malformed(projection, `has a non-numeric ${name} column`);
      if (value.length !== rows) throw malformed(projection, `has ${value.length} ${name} values for ${rows} rows`);
      numericBytes += rows * 8;
    } else {
      if (!Array.isArray(value)) throw malformed(projection, `has a non-text ${name} column`);
      if (value.length !== rows) throw malformed(projection, `has ${value.length} ${name} values for ${rows} rows`);
      for (let i = 0; i < rows; i++) {
        const item: unknown = value[i];
        if (typeof item === 'string') stringUnits += item.length;
        else if (item !== null) throw malformed(projection, `has a non-text ${name} value`);
      }
      strings.push(value);
    }
  }
  if (expected && rows > 0)
    for (const column of expected)
      if (columns[column.name] === undefined) throw malformed(projection, `has no ${column.name} column`);
  // Each UTF-16 code unit encodes to at most 3 UTF-8 bytes, so exact counting is needed only near the ceiling.
  if (numericBytes + 3 * stringUnits <= EXPORT_PAGE_MAX_BYTES) return;
  let bytes = numericBytes;
  for (const value of strings) for (const item of value) if (typeof item === 'string') bytes += utf8Length(item);
  if (bytes > EXPORT_PAGE_MAX_BYTES) throw malformed(projection, 'exceeds 4 MiB');
}

export function checkPage<P extends ProjectionName>(
  projection: P,
  page: ExportPage<P>,
  expected?: readonly ProjectionColumn[],
): ExportPage<P> {
  if (typeof page !== 'object' || page === null || typeof page.columns !== 'object' || page.columns === null)
    throw malformed(projection, 'has no columns');
  checkEnvelope(projection, page);
  checkColumns(projection, page, expected);
  return page;
}

export function fromNativePage<P extends ProjectionName>(page: NativeExportPage<P>, projection: P): ExportPage<P> {
  if (typeof page !== 'object' || page === null || typeof page.columns !== 'object' || page.columns === null)
    throw malformed(projection, 'has no columns');
  checkEnvelope(projection, page);
  const native = page.columns as ColumnMap;
  const columns: Record<string, Column> = {};
  for (const name of Object.keys(native)) {
    const value = native[name];
    if (value === undefined) continue;
    if (definition(projection, name).type === 'number') {
      if (!(value instanceof Uint8Array)) throw malformed(projection, `has a non-binary ${name} column`);
      if (value.byteOffset !== 0) throw malformed(projection, `has a ${name} column that does not start its buffer`);
      if (value.byteLength !== page.rows * 8)
        throw malformed(projection, `has ${value.byteLength} ${name} bytes for ${page.rows} rows`);
      columns[name] = new Float64Array(value.buffer, 0, page.rows);
    } else columns[name] = value;
  }
  const converted = {
    rows: page.rows,
    last: page.last,
    done: page.done,
    ...(page.connections === undefined ? {} : { connections: page.connections }),
    columns: columns as ExportColumns<P>,
  };
  checkColumns(projection, converted);
  return converted;
}

export class ExportConnections {
  private readonly identities = new Map<string, ExportConnection>();
  private readonly ids = new Map<string, number>();
  private readonly list: ExportConnectionEntry[] = [];

  get entries(): readonly ExportConnectionEntry[] {
    return this.list;
  }

  intern(page: Pick<ExportPage<'telemetry'>, 'rows' | 'columns' | 'connections'>): Int32Array {
    for (const identity of page.connections ?? []) {
      const valid =
        typeof identity === 'object' &&
        identity !== null &&
        typeof identity.token === 'string' &&
        VENDORS.includes(identity.vendor) &&
        [identity.model, identity.firmware, identity.protocol].every(
          value => value === null || typeof value === 'string',
        );
      if (!valid) throw malformed('telemetry', 'has an invalid connection identity');
      if (!this.identities.has(identity.token)) this.identities.set(identity.token, identity);
    }
    const ids = new Int32Array(page.rows);
    const tokens = page.columns.connection;
    if (!tokens) return ids;
    for (let i = 0; i < page.rows; i++) {
      const token = tokens[i];
      if (token === null || token === undefined) continue;
      let id = this.ids.get(token);
      if (id === undefined) {
        const identity = this.identities.get(token);
        if (!identity) throw malformed('telemetry', 'names a connection without its identity');
        id = this.list.length + 1;
        this.ids.set(token, id);
        this.list.push({
          connection: id,
          vendor: identity.vendor,
          model: identity.model,
          firmware: identity.firmware,
          protocol: identity.protocol,
        });
      }
      ids[i] = id;
    }
    return ids;
  }
}

export class ExportPasses {
  private readonly counts = new Map<string, number>();

  rows(projection: ProjectionName): number | undefined {
    return this.counts.get(STREAMS[projection]);
  }

  complete(projection: ProjectionName, rows: number): void {
    const stream = STREAMS[projection];
    const previous = this.counts.get(stream);
    if (previous === undefined) this.counts.set(stream, rows);
    else if (previous !== rows)
      throw new ExportError('changed', 'The ride changed while it was being exported. Export it again.');
  }
}

export class ExportReads {
  readonly connections = new ExportConnections();
  readonly passes = new ExportPasses();
  private pending: Promise<unknown> = ready;

  constructor(readonly options: ExportReadOptions) {}

  request<T>(start: () => Promise<T>): Promise<T> {
    const next = this.pending.then(start);
    this.pending = next.catch(() => undefined);
    return next;
  }
}

function compareCursors(projection: ProjectionName, previous: ExportCursor, next: ExportCursor): void {
  if (previous.length !== next.length) throw new ExportError('cursor', `The ${projection} cursor changed its shape.`);
  for (let i = 0; i < next.length; i++) {
    if (next[i]! > previous[i]!) return;
    if (next[i]! < previous[i]!) break;
  }
  throw new ExportError('cursor', `The ${projection} pages did not advance in order.`);
}

export async function* readProjection<P extends ProjectionName>(
  source: ExportSource,
  session: string,
  projection: P,
  reads: ExportReads,
): AsyncGenerator<ProjectionPage<P>, void, undefined> {
  const { platform, kind, slicer, signal } = reads.options;
  if (!(PROJECTIONS[projection].kinds as readonly ExportKind[]).includes(kind))
    throw new ExportError('unsupported', `A ${kind} export does not read ${projection} pages.`);
  const expected = projectionColumns(projection, platform, kind);
  let after: ExportCursor | null = null;
  let rows = 0;
  while (true) {
    if (signal?.aborted) throw exportCancelled();
    const request: ExportPageRequest<P> = { session, projection, after };
    const page: ExportPage<P> = await reads.request(() => source.page(request));
    if (signal?.aborted) throw exportCancelled();
    checkPage(projection, page, expected);
    if (page.rows > 0) {
      if (after) compareCursors(projection, after, page.last!);
      after = page.last;
    }
    rows += page.rows;
    const connection =
      projection === 'telemetry' ? reads.connections.intern(page as unknown as ExportPage<'telemetry'>) : null;
    yield { rows: page.rows, columns: page.columns, connection };
    await slicer.tick();
    if (page.done) break;
  }
  reads.passes.complete(projection, rows);
}
