import type { DistanceSource } from '../distance';
import type { PROJECTIONS } from './catalog';

export type ExportPlatform = 'ios' | 'android' | 'web';
export type ExportKind = 'fit' | 'zip';
export type ExportFileKind = ExportKind | 'csv';
export type ExportConsumer = 'zip' | 'fit' | 'discovery';
export type Producer = 'phone' | 'watch';
export type ProducerSet = [] | ['phone'] | ['watch'] | ['phone', 'watch'];
export type ExportVendor = 'cyc';

export interface ExportContext {
  exportedAt: string;
  platform: ExportPlatform;
}

export const EXPORT_ERROR_CODES = [
  'gate',
  'changed',
  'deleted',
  'cursor',
  'limit',
  'noRecords',
  'cancelled',
  'sink',
  'unsupported',
  'page',
] as const;
export type ExportErrorCode = (typeof EXPORT_ERROR_CODES)[number];

export class ExportError extends Error {
  readonly code: ExportErrorCode;
  constructor(code: ExportErrorCode, message: string) {
    super(message);
    this.name = 'ExportError';
    this.code = code;
  }
}

export interface ExportOwnerTiming {
  timestamp: string;
  elapsedSeconds: number;
  timerSeconds: number;
}

export interface ExportHealthOutcome {
  written: number;
  omitted: number;
  reason: string | null;
}

export interface ExportRideMetadata {
  startedAt: string;
  endedAt: string;
  ownerTiming: ExportOwnerTiming | null;
  indoor: boolean;
  interrupted: boolean;
  watchEnabled: boolean;
  saveToHealth: boolean;
  recordGPS: boolean;
  health: {
    provider: 'appleHealth' | 'healthConnect' | null;
    state: string;
    workoutUUID: string | null;
    export: ExportHealthOutcome | null;
  };
  watchSyncState: 'pending' | 'received' | 'notRequired';
  finalizationState: 'complete' | 'partial';
  example: boolean;
  sampleHz: 2 | 4 | 8 | null;
}

export type ExportDistanceProfile =
  | { source: 'controller'; kind: 'controller' }
  | { source: 'gps:phone'; kind: 'gps'; producer: 'phone' }
  | { source: 'gps:watch'; kind: 'gps'; producer: 'watch' }
  | { source: 'health:phone'; kind: 'health'; producer?: 'phone' }
  | { source: 'health:watch'; kind: 'health'; producer?: 'watch' };

export interface ExportOpenRequest {
  rideId: string;
  kind: ExportKind;
  distanceSource?: DistanceSource;
  context: ExportContext;
}

export interface ExportOpenResult {
  session: string;
  metadata: ExportRideMetadata;
  elapsedEnd: number;
  producers: { gps: ProducerSet; health: ProducerSet };
  distanceProfile: ExportDistanceProfile | null;
}

export type ProjectionName =
  'telemetry' | 'gps' | 'gpsDiscovery' | 'healthZip' | 'healthFit' | 'lifecycle' | 'distance';

export interface ProjectionColumn {
  name: string;
  type: 'number' | 'string';
  platforms: readonly ExportPlatform[];
  consumers: readonly ExportConsumer[];
}

export interface ProjectionDefinition {
  kinds: readonly ExportKind[];
  columns: readonly ProjectionColumn[];
}

type ColumnOf<P extends ProjectionName> = (typeof PROJECTIONS)[P]['columns'][number];
export type ProjectionColumnName<P extends ProjectionName> = ColumnOf<P>['name'];
export type NumericColumnName<P extends ProjectionName> = Extract<ColumnOf<P>, { type: 'number' }>['name'];
export type StringColumnName<P extends ProjectionName> = Extract<ColumnOf<P>, { type: 'string' }>['name'];

export type ExportCursor = number[];

export interface ExportPageRequest<P extends ProjectionName = ProjectionName> {
  session: string;
  projection: P;
  after: ExportCursor | null;
}

export interface ExportConnection {
  token: string;
  vendor: ExportVendor;
  model: string | null;
  firmware: string | null;
  protocol: string | null;
}

interface PageEnvelope {
  rows: number;
  last: ExportCursor | null;
  done: boolean;
  connections?: ExportConnection[];
}

export type ExportColumns<P extends ProjectionName> = { [K in NumericColumnName<P>]?: Float64Array } & {
  [K in StringColumnName<P>]?: (string | null)[];
};

export type NativeExportColumns<P extends ProjectionName> = { [K in NumericColumnName<P>]?: Uint8Array } & {
  [K in StringColumnName<P>]?: (string | null)[];
};

export interface ExportPage<P extends ProjectionName = ProjectionName> extends PageEnvelope {
  columns: ExportColumns<P>;
}

export interface NativeExportPage<P extends ProjectionName = ProjectionName> extends PageEnvelope {
  columns: NativeExportColumns<P>;
}

export interface ExportSource {
  open(request: ExportOpenRequest): Promise<ExportOpenResult>;
  page<P extends ProjectionName>(request: ExportPageRequest<P>): Promise<ExportPage<P>>;
  close(session: string): Promise<void>;
}

export interface ExportDeflateResult {
  crc32: number;
  inputBytes: number;
  outputBytes: number;
}

export interface ExportCommitResult {
  uri: string;
}

export interface ExportSink {
  write(bytes: Uint8Array): Promise<void>;
  writeAt(offset: number, bytes: Uint8Array): Promise<void>;
  beginDeflate(): Promise<void>;
  endDeflate(): Promise<ExportDeflateResult>;
  commit(name: string): Promise<ExportCommitResult>;
  abort(): Promise<void>;
}

export type OpenExportSink = (kind: ExportFileKind, context: ExportContext) => Promise<ExportSink>;

export type TableFieldType = 'string' | 'number' | 'integer' | 'boolean' | 'datetime';

export interface TableField<P extends ProjectionName = ProjectionName> {
  name: string;
  type: TableFieldType;
  description: string;
  'powerLog:unit'?: string;
  'powerLog:vendor'?: ExportVendor;
  reads: readonly ProjectionColumnName<P>[];
}

export interface TableDefinition<P extends ProjectionName = ProjectionName> {
  name: string;
  path: string;
  projection: P;
  fields: readonly TableField<P>[];
}

export type AnyTableDefinition = { [P in ProjectionName]: TableDefinition<P> }[ProjectionName];

export type HealthKind = 'sample' | 'aggregate' | 'latest' | 'cumulative' | 'final';

export interface HealthMetricDefinition {
  metric: string;
  key: NumericColumnName<'healthZip'> | null;
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
  channel: NumericColumnName<'telemetry'>;
}
