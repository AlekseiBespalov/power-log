import type { ExportKind, ExportSource, FailureCode, FitPowerSource } from '../../../../shared/generated/ts/api';

import type { FileKind, SharedFile } from '../native-module';

export type { FileKind, SharedFile };

export interface DeflateResult {
  crc32: number;
  inputBytes: number;
  outputBytes: number;
}

export interface ExportSink {
  write(bytes: Uint8Array): Promise<void>;
  writeAt(offset: number, bytes: Uint8Array): Promise<void>;
  beginDeflate(): Promise<void>;
  endDeflate(): Promise<DeflateResult>;
  commit(name: string): Promise<SharedFile>;
  abort(): Promise<void>;
}

export type OpenSink = (kind: FileKind) => Promise<ExportSink>;

export interface ExportFile {
  readonly source: ExportSource;
  readonly kind: ExportKind;
  readonly fitPower: FitPowerSource;
  readonly exportedAt: string;
  readonly name?: string;
}

export type ExportPlatform = 'ios' | 'android' | 'web';
export type ProducerSet = [] | ['phone'] | ['watch'] | ['phone', 'watch'];

export class ExportError extends Error {
  constructor(
    readonly code: FailureCode,
    message: string,
  ) {
    super(message);
    this.name = 'ExportError';
  }
}

export type TableFieldType = 'string' | 'number' | 'integer' | 'boolean' | 'datetime';

export type HealthKind = 'sample' | 'aggregate' | 'latest' | 'cumulative' | 'final' | 'intervalRecord';

export interface HealthMetricDefinition {
  metric: string;
  key: string | null;
  identifier: string;
  unit: string;
  factor: number;
  kindWithoutRepresentation: HealthKind | null;
}

export interface HealthRepresentationDefinition {
  kind: HealthKind | null;
  condensedKind?: HealthKind;
}

export type FitBaseType = 'enum' | 'uint8' | 'sint16' | 'uint16' | 'sint32' | 'uint32' | 'string' | 'float32' | 'byte';

export interface FitField {
  name: string;
  number: number;
  type: FitBaseType;
  code: number;
  size: number;
  scale: number;
  offset: number;
}

export interface FitMessage {
  number: number;
  fields: readonly FitField[];
}

export interface FitDeveloperField {
  number: number;
  name: string;
  type: 'sint16' | 'float32';
  code: number;
  units: string;
  policy: 'mean' | 'last';
  channel: string;
}
