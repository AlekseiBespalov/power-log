import { TABLES, type TableDefinition } from './catalog';
import { ExportError, type ProducerSet } from './types';

export type ConsumptionTableStatus = { status: 'available' } | { status: 'unavailable'; reason: string };

export interface ExportConnectionEntry {
  readonly connection: number;
  readonly vendor: string;
  readonly model: string | null;
  readonly firmware: string | null;
  readonly protocol: string | null;
}

const SCHEMA = 'https://datapackage.org/profiles/2.0/datapackage.json';
const DESCRIPTION =
  'One Power Log ride: controller readings, GPS fixes, Health values and ride events, each in its own table, with the ride metadata and its sources.';
const DIALECT = { header: true, delimiter: ',', lineTerminator: '\n' } as const;

export interface DescriptorInput {
  readonly consumption?: ConsumptionTableStatus;
  readonly rideId: string;
  readonly created: string;
  readonly metadata: unknown;
  readonly elapsedSeconds: number;
  readonly timerSeconds: number;
  readonly connections: readonly ExportConnectionEntry[];
  readonly producers: { readonly gps: ProducerSet; readonly health: ProducerSet };
}

const invalid = (key: string) =>
  new ExportError('readFailed', `The ride data could not be read for export: its metadata has no valid ${key}.`);

function text(value: unknown, key: string): string {
  if (typeof value !== 'string') throw invalid(key);
  return value;
}

function optionalText(value: unknown, key: string): string | null {
  return value === null ? null : text(value, key);
}

function flag(value: unknown, key: string): boolean {
  if (typeof value !== 'boolean') throw invalid(key);
  return value;
}

function finite(value: unknown, key: string): number {
  if (typeof value !== 'number' || !Number.isFinite(value)) throw invalid(key);
  return value;
}

function count(value: unknown, key: string): number {
  if (!Number.isSafeInteger(value) || (value as number) < 0) throw invalid(key);
  return value as number;
}

function choice<T>(value: unknown, choices: readonly T[], key: string): T {
  if (!choices.includes(value as T)) throw invalid(key);
  return value as T;
}

function record(value: unknown, key: string): Readonly<Record<string, unknown>> {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) throw invalid(key);
  return value as Readonly<Record<string, unknown>>;
}

function ownerTiming(value: unknown) {
  if (value === null) return null;
  const timing = record(value, 'ownerTiming');
  return {
    timestamp: text(timing.timestamp, 'ownerTiming.timestamp'),
    elapsedSeconds: finite(timing.elapsedSeconds, 'ownerTiming.elapsedSeconds'),
    timerSeconds: finite(timing.timerSeconds, 'ownerTiming.timerSeconds'),
  };
}

function health(value: unknown) {
  const outcome = record(value, 'health');
  const exported = outcome.export === null ? null : record(outcome.export, 'health.export');
  return {
    provider: choice(outcome.provider, ['appleHealth', 'healthConnect', null], 'health.provider'),
    state: text(outcome.state, 'health.state'),
    workoutUUID: optionalText(outcome.workoutUUID, 'health.workoutUUID'),
    export:
      exported === null
        ? null
        : {
            written: count(exported.written, 'health.export.written'),
            omitted: count(exported.omitted, 'health.export.omitted'),
            reason: optionalText(exported.reason, 'health.export.reason'),
          },
  };
}

function ride(input: DescriptorInput) {
  if (typeof input.metadata !== 'object' || input.metadata === null || Array.isArray(input.metadata))
    throw new ExportError('readFailed', 'The ride data could not be read for export: its metadata is missing.');
  const metadata = input.metadata as unknown as Readonly<Record<string, unknown>>;
  return {
    startedAt: text(metadata.startedAt, 'startedAt'),
    endedAt: text(metadata.endedAt, 'endedAt'),
    elapsedSeconds: finite(input.elapsedSeconds, 'elapsedSeconds'),
    timerSeconds: finite(input.timerSeconds, 'timerSeconds'),
    ownerTiming: ownerTiming(metadata.ownerTiming),
    indoor: flag(metadata.indoor, 'indoor'),
    interrupted: flag(metadata.interrupted, 'interrupted'),
    watchEnabled: flag(metadata.watchEnabled, 'watchEnabled'),
    saveToHealth: flag(metadata.saveToHealth, 'saveToHealth'),
    recordGPS: flag(metadata.recordGPS, 'recordGPS'),
    sport: 'cycling',
    subSport: 'e_biking',
    health: health(metadata.health),
    example: flag(metadata.example, 'example'),
    sampleHz: choice(metadata.sampleHz, [2, 4, 8, null], 'sampleHz'),
    batteryConsumptionSource: choice(
      metadata.batteryConsumptionSource,
      ['controller', 'calculated'],
      'batteryConsumptionSource',
    ),
  };
}

function resource(table: TableDefinition) {
  return {
    name: table.name,
    type: 'table',
    path: table.path,
    format: 'csv',
    mediatype: 'text/csv',
    encoding: 'utf-8',
    dialect: DIALECT,
    schema: {
      fields: table.fields.map(field => ({
        name: field.name,
        type: field.type,
        description: field.description,
        ...(field.unit === undefined ? {} : { 'powerLog:unit': field.unit }),
        ...(field.vendor === undefined ? {} : { 'powerLog:vendor': field.vendor }),
      })),
      missingValues: [''],
    },
  };
}

export function descriptorJson(input: DescriptorInput): string {
  return JSON.stringify(
    {
      $schema: SCHEMA,
      name: `power-log-ride-${input.rideId}`,
      id: input.rideId,
      title: 'Power Log ride',
      created: input.created,
      description: DESCRIPTION,
      'powerLog:ride': ride(input),
      'powerLog:batteryConsumption': input.consumption,
      'powerLog:sources': {
        connections: input.connections.map(entry => ({
          connection: entry.connection,
          vendor: entry.vendor,
          model: entry.model,
          firmware: entry.firmware,
          protocol: entry.protocol,
        })),
        gps: [...input.producers.gps],
        health: [...input.producers.health],
      },
      resources: TABLES.map(resource),
    },
    null,
    2,
  );
}
