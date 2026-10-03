import { execFileSync, spawnSync } from 'node:child_process';
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { homedir, tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { exportFit, type FitExportResult, type FitExportTuning } from '../../../../src/core/export/fit-export';
import { projectionColumns } from '../../../../src/core/export/pages';
import {
  ExportError,
  type ExportCommitResult,
  type ExportConnection,
  type ExportDistanceProfile,
  type ExportOpenRequest,
  type ExportOpenResult,
  type ExportPage,
  type ExportPageRequest,
  type ExportPlatform,
  type ExportRideMetadata,
  type ExportSink,
  type ExportSource,
  type Producer,
  type ProducerSet,
  type ProjectionName,
} from '../../../../src/core/export/types';

export type LifecycleRow = {
  t: number;
  action: string;
  producer?: string;
  sequence?: number;
  epoch?: string | null;
  interrupted?: boolean;
  cyc?: number;
};

export type TelemetryRow = {
  t: number;
  connection?: string | null;
  sequence?: number;
  epoch?: string | null;
  active?: boolean;
  interval?: number;
  humanPowerW?: number;
  cadenceRpm?: number;
  motorInputPowerW?: number;
  batteryVoltageV?: number;
  batteryCurrentA?: number;
  motorCurrentA?: number;
  motorRpm?: number;
  pedalTorqueNm?: number;
  controllerTempC?: number;
  motorTempC?: number;
  consumedAh?: number;
  consumedWh?: number;
  assistLevel?: number;
};

export type GpsRow = {
  t: number;
  producer?: Producer;
  sequence?: number;
  epoch?: string | null;
  active?: boolean;
  timestamp?: string;
  latitude?: number;
  longitude?: number;
  altitudeMeters?: number;
  verticalAccuracyM?: number;
  horizontalAccuracyM?: number;
  speedMps?: number;
  speedAccuracyMps?: number;
  distanceBarrier?: boolean;
};

export type HealthRow = {
  t: number;
  producer?: Producer;
  sequence?: number;
  epoch?: string | null;
  connectionEpoch?: string | null;
  representation?: string | null;
  sampleCount?: number;
  heartRateBpm?: number;
  activeEnergyKcal?: number;
};

export type DistanceRow = {
  start: number;
  end: number;
  meters: number;
  segment?: number;
  startSpeed?: number;
  endSpeed?: number;
};

export interface RideSpec {
  platform?: ExportPlatform;
  rideId?: string;
  startedAt?: string;
  end: number;
  watchEnabled?: boolean;
  indoor?: boolean;
  lifecycle?: LifecycleRow[];
  telemetry?: TelemetryRow[];
  gps?: GpsRow[];
  health?: HealthRow[];
  profile?: ExportDistanceProfile | null;
  distance?: DistanceRow[];
  producers?: { gps: ProducerSet; health: ProducerSet };
  pageSize?: number;
}

export const START = '2026-01-01T00:00:00.000Z';
export const FIT_START = Date.parse(START) / 1000 - 631065600;
export const CONTEXT_TIME = '2026-01-02T03:04:05.000Z';

type Row = Readonly<Record<string, unknown>> & { t?: number; start?: number; end?: number };

const IDENTITY = { vendor: 'cyc', model: 'X6', firmware: '20250604', protocol: '5.3' } as const;

function metadata(ride: RideSpec): ExportRideMetadata {
  const started = Date.parse(ride.startedAt ?? START);
  return {
    startedAt: ride.startedAt ?? START,
    endedAt: new Date((Number.isNaN(started) ? Date.parse(START) : started) + ride.end * 1000).toISOString(),
    ownerTiming: null,
    indoor: ride.indoor ?? false,
    interrupted: false,
    watchEnabled: ride.watchEnabled ?? false,
    saveToHealth: false,
    recordGPS: true,
    health: { provider: 'appleHealth', state: 'notRequested', workoutUUID: null, export: null },
    watchSyncState: 'notRequired',
    finalizationState: 'complete',
    example: false,
    sampleHz: null,
  };
}

function present(rows: readonly { producer?: Producer }[] | undefined, fallback: Producer): ProducerSet {
  const seen = new Set((rows ?? []).map(row => row.producer ?? fallback));
  return (['phone', 'watch'] as const).filter(producer => seen.has(producer)) as ProducerSet;
}

const stable = <T extends Row>(rows: readonly T[], key: (row: T) => number) =>
  rows
    .map((row, index) => ({ row, index }))
    .sort((a, b) => key(a.row) - key(b.row) || a.index - b.index)
    .map(item => item.row);

function value(projection: ProjectionName, platform: ExportPlatform, name: string, row: Row, index: number): unknown {
  const field = row[name];
  switch (name) {
    case 'elapsedSeconds':
      return row.t;
    case 'producer':
      if (field !== undefined) return field;
      if (projection === 'lifecycle') return platform === 'web' ? 'browser' : 'phone';
      return projection === 'healthFit' ? 'watch' : 'phone';
    case 'producerSequence':
      return row.sequence ?? index + 1;
    case 'clockEpoch':
      return row.epoch ?? null;
    case 'connectionEpoch':
      return field ?? null;
    case 'connection':
      return field === undefined ? 'connection-a' : field;
    case 'active':
      return row.active === false ? 0 : 1;
    case 'interval':
      return field ?? 0;
    case 'interrupted':
      return row.interrupted ? 1 : 0;
    case 'cycSequence':
      return row.cyc ?? NaN;
    case 'distanceBarrier':
      return row.distanceBarrier ? 1 : 0;
    case 'representation':
      return field ?? null;
    case 'action':
      return field;
    case 'timestamp':
      return field ?? new Date(Date.parse(START) + (row.t ?? 0) * 1000).toISOString();
    case 'segment':
      return field ?? 0;
    default:
      return field ?? NaN;
  }
}

export class MemorySource implements ExportSource {
  readonly requests: ProjectionName[] = [];
  readonly passes: Partial<Record<ProjectionName, number>> = {};
  closed = 0;
  private readonly rows: Record<ProjectionName, Row[]>;
  private readonly seen = new Set<string>();

  constructor(readonly ride: RideSpec) {
    const lifecycle = ride.lifecycle ?? [
      { t: 0, action: 'start' },
      { t: ride.end, action: 'stop' },
    ];
    const gps = stable(ride.gps ?? [], row => row.t);
    this.rows = {
      lifecycle: stable(lifecycle, row => row.t),
      telemetry: stable(ride.telemetry ?? [], row => row.t),
      gps,
      gpsDiscovery: gps,
      healthFit: stable(ride.health ?? [], row => row.t),
      healthZip: [],
      distance: [...(ride.distance ?? [])],
    };
  }

  get platform(): ExportPlatform {
    return this.ride.platform ?? 'ios';
  }

  async open(request: ExportOpenRequest): Promise<ExportOpenResult> {
    if (request.kind !== 'fit') throw new ExportError('unsupported', 'FIT only');
    const ride = this.ride;
    return {
      session: 'session-1',
      metadata: metadata(ride),
      elapsedEnd: ride.end,
      producers: ride.producers ?? { gps: present(ride.gps, 'phone'), health: present(ride.health, 'watch') },
      distanceProfile: ride.profile ?? null,
    };
  }

  async page<P extends ProjectionName>(request: ExportPageRequest<P>): Promise<ExportPage<P>> {
    const projection: ProjectionName = request.projection;
    this.requests.push(projection);
    if (request.after === null) this.passes[projection] = (this.passes[projection] ?? 0) + 1;
    const rows = this.rows[projection];
    const first = request.after === null ? 0 : request.after[1]! + 1;
    if (request.after === null && projection === 'telemetry') this.seen.clear();
    const size = this.ride.pageSize ?? 4096;
    const slice = rows.slice(first, first + size);
    const platform = this.platform;
    const columns: Record<string, Float64Array | (string | null)[]> = {};
    for (const column of projectionColumns(projection, platform, 'fit')) {
      const values = slice.map((row, i) => value(projection, platform, column.name, row, first + i));
      columns[column.name] =
        column.type === 'number' ? Float64Array.from(values as number[]) : (values as (string | null)[]);
    }
    const page: ExportPage = {
      rows: slice.length,
      last:
        slice.length === 0
          ? null
          : [
              projection === 'distance' ? slice[slice.length - 1]!.end! : slice[slice.length - 1]!.t!,
              first + slice.length - 1,
            ],
      done: first + slice.length >= rows.length,
      columns,
    };
    if (projection === 'telemetry') {
      const connections: ExportConnection[] = [];
      for (const token of columns.connection as (string | null)[])
        if (token !== null && !this.seen.has(token)) {
          this.seen.add(token);
          connections.push({ token, ...IDENTITY });
        }
      page.connections = connections;
    }
    return page as ExportPage<P>;
  }

  async close(): Promise<void> {
    this.closed++;
  }
}

export class MemorySink implements ExportSink {
  private buffer = new Uint8Array(1 << 16);
  length = 0;
  writes = 0;
  committed: string | null = null;
  aborted = false;

  get bytes(): Uint8Array {
    return this.buffer.slice(0, this.length);
  }

  async write(bytes: Uint8Array): Promise<void> {
    this.writes++;
    this.reserve(this.length + bytes.length);
    this.buffer.set(bytes, this.length);
    this.length += bytes.length;
  }

  async writeAt(offset: number, bytes: Uint8Array): Promise<void> {
    if (offset + bytes.length > this.length) throw new Error('writeAt past the end');
    this.buffer.set(bytes, offset);
  }

  async beginDeflate(): Promise<void> {
    throw new Error('FIT does not deflate');
  }

  async endDeflate(): Promise<never> {
    throw new Error('FIT does not deflate');
  }

  async commit(name: string): Promise<ExportCommitResult> {
    this.committed = name;
    return { uri: `memory://${name}` };
  }

  async abort(): Promise<void> {
    this.aborted = true;
  }

  private reserve(size: number): void {
    if (size <= this.buffer.length) return;
    const grown = new Uint8Array(Math.max(size, 2 * this.buffer.length));
    grown.set(this.buffer.subarray(0, this.length));
    this.buffer = grown;
  }
}

export interface FitRun {
  readonly result: FitExportResult;
  readonly bytes: Uint8Array;
  readonly source: MemorySource;
  readonly sink: MemorySink;
}

export async function runFit(ride: RideSpec, tuning?: FitExportTuning, signal?: AbortSignal): Promise<FitRun> {
  const source = new MemorySource(ride);
  const sink = new MemorySink();
  const result = await exportFit(
    source,
    async () => sink,
    { rideId: ride.rideId ?? 'ride-1', context: { exportedAt: CONTEXT_TIME, platform: source.platform }, signal },
    tuning,
  );
  return { result, bytes: sink.bytes, source, sink };
}

export async function fitFailure(
  ride: RideSpec,
  tuning?: FitExportTuning,
): Promise<{ code: string; message: string; source: MemorySource; sink: MemorySink }> {
  const source = new MemorySource(ride);
  const sink = new MemorySink();
  try {
    await exportFit(
      source,
      async () => sink,
      { rideId: 'ride-1', context: { exportedAt: CONTEXT_TIME, platform: source.platform } },
      tuning,
    );
  } catch (error) {
    if (error instanceof ExportError) return { code: error.code, message: error.message, source, sink };
    throw error;
  }
  throw new Error('The FIT export did not fail');
}

export interface FitMessage {
  readonly global: number;
  readonly local: number;
  readonly fields: ReadonlyMap<number, number>;
  readonly developer: ReadonlyMap<number, number>;
  readonly definition: readonly (readonly [number, number, number])[];
  readonly developerDefinition: readonly (readonly [number, number, number])[];
  readonly bytes: Uint8Array;
  readonly redefined: boolean;
}

function scalar(view: DataView, at: number, size: number, type: number): number {
  switch (type) {
    case 0x00:
    case 0x02:
    case 0x0d:
      return size === 1 ? view.getUint8(at) : NaN;
    case 0x83:
      return view.getInt16(at, true);
    case 0x84:
      return view.getUint16(at, true);
    case 0x85:
      return view.getInt32(at, true);
    case 0x86:
      return view.getUint32(at, true);
    case 0x88:
      return view.getFloat32(at, true);
    default:
      return NaN;
  }
}

const DEVELOPER_TYPES = [0x83, 0x88, 0x88, 0x88, 0x88, 0x83, 0x83, 0x88, 0x88, 0x88];

export function readFit(bytes: Uint8Array): FitMessage[] {
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  const end = bytes[0]! + view.getUint32(4, true);
  const definitions = new Map<
    number,
    { global: number; native: [number, number, number][]; developer: [number, number, number][] }
  >();
  const messages: FitMessage[] = [];
  let redefined = false;
  let at = bytes[0]!;
  while (at < end) {
    const header = bytes[at++]!;
    const local = header & 0x0f;
    if (header & 0x40) {
      const global = view.getUint16(at + 2, true);
      const count = bytes[at + 4]!;
      at += 5;
      const native: [number, number, number][] = [];
      for (let i = 0; i < count; i++, at += 3) native.push([bytes[at]!, bytes[at + 1]!, bytes[at + 2]!]);
      const developer: [number, number, number][] = [];
      if (header & 0x20) {
        const extra = bytes[at++]!;
        for (let i = 0; i < extra; i++, at += 3) developer.push([bytes[at]!, bytes[at + 1]!, bytes[at + 2]!]);
      }
      definitions.set(local, { global, native, developer });
      redefined = true;
      continue;
    }
    const definition = definitions.get(local)!;
    const start = at;
    const fields = new Map<number, number>();
    for (const [number, size, type] of definition.native) {
      fields.set(number, scalar(view, at, size, type));
      at += size;
    }
    const developer = new Map<number, number>();
    for (const [number, size] of definition.developer) {
      developer.set(number, scalar(view, at, size, DEVELOPER_TYPES[number]!));
      at += size;
    }
    messages.push({
      global: definition.global,
      local,
      fields,
      developer,
      definition: definition.native,
      developerDefinition: definition.developer,
      bytes: bytes.slice(start - 1, at),
      redefined,
    });
    redefined = false;
  }
  return messages;
}

export const records = (messages: readonly FitMessage[]) => messages.filter(message => message.global === 20);

export function fitPython(): string | null {
  const available = (python: string) =>
    spawnSync(python, ['-c', 'import garmin_fit_sdk'], { encoding: 'utf8' }).status === 0;
  const required = process.env.FIT_PYTHON;
  if (required) {
    if (!available(required)) throw new Error(`FIT_PYTHON=${required} cannot import garmin_fit_sdk`);
    return required;
  }
  const local = join(homedir(), '.cache/power-log/fit-venv/bin/python');
  return available(local) ? local : null;
}

export interface DecodedMessage {
  readonly mesg_num: number;
  readonly fields: Readonly<Record<string, unknown>>;
}

export interface DecodedFit {
  readonly header: { size: number; protocol: number; profile: number; data_size: number; type: string };
  readonly messages: readonly DecodedMessage[];
  readonly definitions: readonly { local: number; schema: unknown }[];
  readonly trace: readonly { global: number; native: number[][]; developer: number[][]; payload: string }[];
}

const DECODER = resolve('tests/export/decode_fit.py');

export function decodeFit(python: string, bytes: Uint8Array): DecodedFit {
  const directory = mkdtempSync(join(tmpdir(), 'power-log-fit-'));
  try {
    const path = join(directory, 'ride.fit');
    writeFileSync(path, bytes);
    return JSON.parse(execFileSync(python, [DECODER, path], { encoding: 'utf8', maxBuffer: 1 << 28 })) as DecodedFit;
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
}

export const decoded = (fit: DecodedFit, mesgNum: number) =>
  fit.messages.filter(message => message.mesg_num === mesgNum).map(message => message.fields);
