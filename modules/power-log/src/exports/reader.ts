import type {
  ApiFailure,
  ExportConnection,
  ExportOpenInput,
  ExportSessionView,
  FailureCode,
} from '../../../../shared/generated/ts/api';
import type { PowerLogClient } from '../client';
import { createSlicer, type ExportSlicer } from './slicer';

export interface ReaderPage {
  readonly rows: number;
  readonly next: number[] | null;
  readonly done: boolean;
  readonly connections: readonly ExportConnection[];
  readonly columns: Readonly<Record<string, Float64Array | readonly (string | null)[]>>;
}

export type Row = Readonly<Record<string, number | string | null>>;

export class PageFailure extends Error {
  constructor(readonly failure: ApiFailure) {
    super(`The export failed with ${failure.code}`);
  }
}

export class SessionClosed extends Error {
  constructor() {
    super('The export session closed');
  }
}

function float64(bytes: Uint8Array, rows: number): Float64Array {
  if (bytes.byteOffset % 8 === 0) return new Float64Array(bytes.buffer, bytes.byteOffset, rows);
  return new Float64Array(bytes.slice(0, rows * 8).buffer);
}

export class ExportPageReader {
  constructor(
    private readonly client: PowerLogClient,
    private readonly slicer: ExportSlicer = createSlicer(),
  ) {}

  async open(input: ExportOpenInput): Promise<ExportSessionView> {
    const result = await this.client.call('exports.open', input);
    if ('cancelled' in result) throw new SessionClosed();
    if (!result.ok) throw new PageFailure(result.failure);
    return result.value;
  }

  async *pages(sessionId: string, projection: string): AsyncGenerator<ReaderPage> {
    let after: number[] | null = null;
    while (true) {
      await this.slicer.tick();
      const result = await this.client.exportPage({ sessionId, projection, after });
      await this.slicer.tick();
      if ('cancelled' in result) throw new SessionClosed();
      if (!result.ok) throw new PageFailure(result.failure);
      const page = result.value;
      const columns: Record<string, Float64Array | readonly (string | null)[]> = {};
      for (const [name, column] of Object.entries(page.columns))
        columns[name] = column instanceof Uint8Array ? float64(column, page.rows) : column;
      yield { rows: page.rows, next: page.next, done: page.done, connections: page.connections, columns };
      if (page.done) return;
      after = page.next;
    }
  }

  async all(sessionId: string, projection: string): Promise<ReaderPage[]> {
    const pages: ReaderPage[] = [];
    for await (const page of this.pages(sessionId, projection)) pages.push(page);
    return pages;
  }

  async close(sessionId: string, failure: FailureCode | null): Promise<void> {
    await this.client.call('exports.close', { sessionId, failure });
  }
}

export function columnRows(pages: readonly ReaderPage[]): Row[] {
  const rows: Row[] = [];
  for (const page of pages) {
    const names = Object.keys(page.columns);
    for (let i = 0; i < page.rows; i++) {
      const row: Record<string, number | string | null> = {};
      for (const name of names) {
        const column = page.columns[name]!;
        row[name] = column instanceof Float64Array ? column[i]! : (column[i] ?? null);
      }
      rows.push(row);
    }
  }
  return rows;
}

export function numberOf(row: Row, name: string): number {
  const value = row[name];
  return typeof value === 'number' ? value : NaN;
}
