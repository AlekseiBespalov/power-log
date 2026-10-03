import type { ExportReads, ExportSlicer } from '../pages';
import type { Timeline } from '../timeline';
import { ExportError, type ExportSource, type TableFieldType } from '../types';

const TEXT_CHUNK_BYTES = 64 * 1024;
export const ROW_BATCH = 64;
const PENDING_UNITS = 16 * 1024;
const QUOTED = /[",\r\n]/;

export type Texts = readonly (string | null)[];

export interface ByteOutput {
  write(bytes: Uint8Array): Promise<void>;
}

export const CELL_NUMBER = 0;
export const CELL_INTEGER = 1;
export const CELL_TEXT = 2;
export const CELL_ID = 3;
export const CELL_COUNT = 4;
export type CellKind = typeof CELL_NUMBER | typeof CELL_INTEGER | typeof CELL_TEXT | typeof CELL_ID | typeof CELL_COUNT;
export type CellValues = Float64Array | Int32Array | Texts | null | undefined;

export interface TableShape {
  readonly name: string;
  readonly fields: readonly { readonly name: string; readonly type: TableFieldType }[];
}

export interface TableLayout {
  readonly table: TableShape;
  readonly subject: string;
  readonly kinds: readonly CellKind[];
  readonly reads: readonly (string | null)[];
}

export interface TableInput {
  readonly source: ExportSource;
  readonly session: string;
  readonly reads: ExportReads;
  readonly timeline: Timeline;
  readonly out: TextChunks;
}

export const tableError = (subject: string, problem: string) =>
  new ExportError('page', `The ride data could not be read for export: ${subject} ${problem}.`);

export function checkProducer(subject: string, producer: string | null | undefined, listed: readonly string[]): string {
  if (typeof producer === 'string' && listed.includes(producer)) return producer;
  throw tableError(subject, `names the source ${producer ?? 'none'}, which this export does not expect`);
}

const quote = (value: string) => `"${value.replaceAll('"', '""')}"`;

export function csvText(value: string | null | undefined): string {
  if (value === null || value === undefined) return '';
  return QUOTED.test(value) ? quote(value) : value;
}

export function csvNumber(value: number, subject: string, field: string): string {
  if (Number.isFinite(value)) return String(value);
  if (Number.isNaN(value)) return '';
  throw tableError(subject, `has a ${field} value that is not a finite number`);
}

export function csvInteger(value: number, subject: string, field: string): string {
  if (Number.isSafeInteger(value)) return String(value);
  if (Number.isNaN(value)) return '';
  throw tableError(subject, `has a ${field} value that is not an integer`);
}

export const csvBoolean = (value: boolean) => (value ? 'true' : 'false');

export const csvHeader = (table: TableShape) => table.fields.map(field => csvText(field.name)).join(',');

function cellKind(type: TableFieldType): CellKind {
  if (type === 'number') return CELL_NUMBER;
  if (type === 'integer') return CELL_INTEGER;
  if (type === 'string' || type === 'datetime') return CELL_TEXT;
  throw new RangeError(`A ${type} field needs its own formatter`);
}

export function tableLayout(
  table: {
    readonly name: string;
    readonly fields: readonly {
      readonly name: string;
      readonly type: TableFieldType;
      readonly reads: readonly string[];
    }[];
  },
  subject: string,
  computed: Readonly<Record<string, CellKind>>,
): TableLayout {
  const own = (name: string) => Object.hasOwn(computed, name);
  return {
    table,
    subject,
    kinds: table.fields.map(field => (own(field.name) ? computed[field.name]! : cellKind(field.type))),
    reads: table.fields.map(field => (own(field.name) ? null : field.reads[0]!)),
  };
}

export function layoutColumns(
  layout: TableLayout,
  columns: object,
  computed: Readonly<Record<string, CellValues>>,
): CellValues[] {
  const stored = columns as Readonly<Record<string, CellValues>>;
  return layout.reads.map((name, c) => (name === null ? computed[layout.table.fields[c]!.name] : stored[name]));
}

export class TextChunks {
  private pending = '';
  private readonly encoder = new TextEncoder();
  private readonly buffers: readonly [Uint8Array, Uint8Array];
  private current = 0;
  private used = 0;

  constructor(
    private readonly output: ByteOutput,
    private readonly chunkBytes = TEXT_CHUNK_BYTES,
  ) {
    if (!Number.isSafeInteger(chunkBytes) || chunkBytes < 4)
      throw new RangeError('A text chunk holds at least 4 bytes');
    this.buffers = [new Uint8Array(chunkBytes), new Uint8Array(chunkBytes)];
  }

  line(text: string): void {
    this.pending += text + '\n';
  }

  get due(): boolean {
    return this.pending.length >= PENDING_UNITS;
  }

  async drain(): Promise<void> {
    let text = this.pending;
    this.pending = '';
    while (text.length > 0) {
      if (this.used === this.chunkBytes) await this.send();
      const { read, written } = this.encoder.encodeInto(text, this.buffers[this.current]!.subarray(this.used));
      this.used += written;
      if (read === text.length) break;
      text = text.slice(read);
      await this.send();
    }
  }

  async end(): Promise<void> {
    await this.drain();
    if (this.used > 0) await this.send();
  }

  // The output resolves only once its previous write has settled, so the buffer switched to here is free when
  // this resolves; the chunk just handed over stays untouched until the following send switches back to it.
  private send(): Promise<void> {
    const chunk = this.buffers[this.current]!.subarray(0, this.used);
    this.current = 1 - this.current;
    this.used = 0;
    return this.output.write(chunk);
  }
}

export async function writeRows(
  out: TextChunks,
  slicer: ExportSlicer,
  layout: TableLayout,
  columns: readonly CellValues[],
  rows: number,
): Promise<void> {
  const { table, subject, kinds } = layout;
  const count = kinds.length;
  for (let i = 0; i < rows; i++) {
    let line = '';
    for (let c = 0; c < count; c++) {
      if (c > 0) line += ',';
      const values = columns[c];
      if (values === undefined || values === null) continue;
      switch (kinds[c]) {
        case CELL_NUMBER: {
          const value = (values as Float64Array)[i]!;
          if (Number.isFinite(value)) line += value;
          else if (!Number.isNaN(value))
            throw tableError(subject, `has a ${table.fields[c]!.name} value that is not a finite number`);
          break;
        }
        case CELL_INTEGER: {
          const value = (values as Float64Array)[i]!;
          if (Number.isSafeInteger(value)) line += value;
          else if (!Number.isNaN(value))
            throw tableError(subject, `has a ${table.fields[c]!.name} value that is not an integer`);
          break;
        }
        case CELL_TEXT: {
          const value = (values as Texts)[i];
          if (value !== null && value !== undefined) line += QUOTED.test(value) ? quote(value) : value;
          break;
        }
        case CELL_ID: {
          const value = (values as Int32Array)[i]!;
          if (value !== 0) line += value;
          break;
        }
        default:
          line += (values as Int32Array)[i]!;
      }
    }
    out.line(line);
    if (i % ROW_BATCH === ROW_BATCH - 1) {
      if (out.due) await out.drain();
      await slicer.tick();
    }
  }
  if (out.due) await out.drain();
}
