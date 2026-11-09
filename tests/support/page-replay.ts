import { STARTING_BUILD_HEALTH } from './golden-rules';
import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import type {
  ApiFailure,
  ExportConnection,
  ExportKind,
  ExportPage,
  ExportSource,
  FailureCode,
  FitPowerSource,
} from '../../shared/generated/ts/api';
import { createPowerLogClient } from '../../modules/power-log/src/client';
import { exportFile } from '../../modules/power-log/src/exports/export-file';
import type { ExportPlatform, ExportSink } from '../../modules/power-log/src/exports/types';
import { fakeTransport, type FakeTransport } from '../../modules/power-log/src/testing/fake-transport';

export type Cell = number | string | null;

export interface DumpPage {
  readonly rows: number;
  readonly next: number[] | null;
  readonly done: boolean;
  readonly connections: ExportConnection[];
  readonly columns: Record<string, Cell[]>;
}

export interface DumpFailure {
  readonly failure: { readonly code: FailureCode; readonly today: string; readonly message: string };
}

export type PageSequence = readonly (DumpPage | DumpFailure)[];

export interface OpenView {
  readonly sessionId: string;
  readonly platform: ExportPlatform;
  readonly metadata: Readonly<Record<string, unknown>>;
  readonly elapsedEnd: number;
  readonly timerSeconds: number | null;
  readonly producers: { readonly gps: string[]; readonly health: string[] };
  readonly consumption: { readonly kind: 'available' } | { readonly kind: 'unavailable'; readonly reason: string };
}

export interface DumpedPages {
  readonly open: OpenView | DumpFailure;
  readonly projections: ReadonlyMap<string, PageSequence>;
}

const isFailure = (element: DumpPage | DumpFailure | OpenView): element is DumpFailure => 'failure' in element;

export function readPages(directory: string, renamed: Readonly<Record<string, string>> = {}): DumpedPages {
  const read = (name: string): unknown => JSON.parse(readFileSync(join(directory, `${name}.json`), 'utf8'));
  const projections = new Map<string, PageSequence>();
  for (const name of ['telemetry', 'gps', 'healthZip', 'lifecycle', 'consumption', 'seconds', 'summary']) {
    const file = renamed[name] ?? name;
    if (existsSync(join(directory, `${file}.json`))) projections.set(name, read(file) as PageSequence);
  }
  // The dump writes a derived pass that failed before its summary as seconds ending in the failure; summary,
  // which shares the pass, answers the same failure.
  const seconds = projections.get('seconds');
  const last = seconds?.[seconds.length - 1];
  if (last && isFailure(last) && !projections.has('summary')) projections.set('summary', [last]);
  return { open: read('open') as OpenView | DumpFailure, projections };
}

const TEXT_COLUMNS: Readonly<Record<string, ReadonlySet<string>>> = {
  telemetry: new Set(['timestamp', 'connection']),
  gps: new Set(['timestamp', 'producer', 'originalAltitudeDatum']),
  healthZip: new Set([
    'timestamp',
    'producer',
    'identifier',
    'unit',
    'representation',
    'sampleStart',
    'provider',
    'providerParentId',
    'dataOrigin',
    'originalEventId',
    'originalEndTimestamp',
    'memberIndex',
    ...STARTING_BUILD_HEALTH,
  ]),
  lifecycle: new Set(['timestamp', 'producer', 'action']),
  consumption: new Set(['timestamp']),
  seconds: new Set(['kind']),
  summary: new Set(['kind']),
};

function textColumn(projection: string, column: string): boolean {
  const texts = TEXT_COLUMNS[projection];
  if (!texts) throw new Error(`The replay does not know the ${projection} projection`);
  return texts.has(column);
}

function exportPage(projection: string, page: DumpPage, done: boolean): ExportPage {
  const columns: ExportPage['columns'] = {};
  for (const [name, cells] of Object.entries(page.columns)) {
    if (projection === 'healthZip' && STARTING_BUILD_HEALTH.includes(name)) continue;
    columns[name] = textColumn(projection, name)
      ? (cells as (string | null)[])
      : new Uint8Array(Float64Array.from(cells, cell => (cell === null ? NaN : (cell as number))).buffer);
  }
  return { rows: page.rows, next: page.next, done, connections: page.connections, columns };
}

const apiFailure = (failure: DumpFailure['failure']): ApiFailure => ({
  code: failure.code,
  retryable: false,
  occurrence: null,
  detail: null,
});

// A dumped sequence that ends with a failure fails after its rows: the page before the failure is not the last,
// and an empty page before it is never served.
function served(sequence: PageSequence): { element: DumpPage | DumpFailure; done: boolean }[] {
  const elements = [...sequence];
  const failure = elements.findIndex(isFailure);
  if (failure > 0) {
    const before = elements[failure - 1] as DumpPage;
    if (before.rows === 0) elements.splice(failure - 1, 1);
  }
  return elements.map((element, index) => ({
    element,
    done: !isFailure(element) && element.done && !elements.slice(index + 1).some(isFailure),
  }));
}

function servePages(transport: FakeTransport, pages: DumpedPages): { readonly failed: boolean } {
  const outcome = { failed: false };
  const open = pages.open;
  transport.answer('exports.open', () => {
    if (!isFailure(open)) return { ok: true, value: open as never };
    outcome.failed = true;
    return { ok: false, failure: apiFailure(open.failure) };
  });
  transport.answer('exports.close', () => ({ ok: true, value: null }));
  transport.answerPages(input => {
    const sequence = pages.projections.get(input.projection);
    if (!sequence) throw new Error(`The fixture has no ${input.projection} pages`);
    const elements = served(sequence);
    const after = JSON.stringify(input.after);
    const index =
      input.after === null
        ? 0
        : elements.findIndex(item => !isFailure(item.element) && JSON.stringify(item.element.next) === after) + 1;
    const item = elements[index];
    if (index <= 0 && input.after !== null) throw new Error(`No ${input.projection} page follows ${after}`);
    if (!item) throw new Error(`The ${input.projection} pages end before ${after}`);
    if (isFailure(item.element)) {
      outcome.failed = true;
      return { ok: false, failure: apiFailure(item.element.failure) };
    }
    return { ok: true, value: exportPage(input.projection, item.element, item.done) };
  });
  return outcome;
}

export const FAILURE_CODES: Readonly<Record<string, FailureCode>> = {
  gate: 'exportNotReady',
  changed: 'exportChanged',
  deleted: 'rideDeleted',
  cursor: 'unknown',
  limit: 'exportLimit',
  noRecords: 'exportNoRecords',
  cancelled: 'exportLeftScreen',
  sink: 'exportSinkFailed',
  unsupported: 'apiMismatch',
  page: 'readFailed',
};

export interface ExportFile {
  readonly source: ExportSource;
  readonly kind: ExportKind;
  readonly fitPower: FitPowerSource;
  readonly exportedAt: string;
}

export type ReplayOutcome =
  { readonly bytes: Uint8Array } | { readonly failedIn: 'pages' | 'encoder'; readonly code: FailureCode };

export interface ReplayRequest extends Omit<ExportFile, 'fitPower'> {
  readonly pages: DumpedPages;
  readonly fitPower?: FitPowerSource;
}

export async function replay(
  sink: ExportSink & { readonly file: Uint8Array },
  { pages, fitPower = 'rider', ...file }: ReplayRequest,
): Promise<ReplayOutcome> {
  const transport = fakeTransport();
  const server = servePages(transport, pages);
  const result = await exportFile(createPowerLogClient(transport), { ...file, fitPower }, async () => sink);
  if ('cancelled' in result) throw new Error('The export answered cancelled');
  if (result.ok) return { bytes: sink.file };
  return { failedIn: server.failed ? 'pages' : 'encoder', code: result.failure.code };
}
