import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import {
  FIT_BASE_TYPES,
  FIT_DEVELOPER_APPLICATION_ID,
  FIT_DEVELOPER_DATA_INDEX,
  FIT_DEVELOPER_FIELDS,
  FIT_MESSAGES,
  HEALTH_METRICS,
  HEALTH_REPRESENTATIONS,
  PROJECTIONS,
  TABLES,
} from '../../src/core/export/catalog';
import type {
  FitField,
  ProjectionColumn,
  ProjectionName,
  TableDefinition,
  TableFieldType,
} from '../../src/core/export/types';
import { REQUIRED_SAMPLE_COLUMNS, SAMPLE_IDENTITY_COLUMNS } from '../../src/core/types';

const tables: readonly TableDefinition[] = TABLES;
const columns = (projection: ProjectionName): readonly ProjectionColumn[] => PROJECTIONS[projection].columns;
const column = (projection: ProjectionName, name: string) => columns(projection).find(item => item.name === name);
const table = (name: string) => tables.find(item => item.name === name)!;

type ExpectedColumn = [name: string, type: TableFieldType, unit?: string | null, vendor?: string];
const EXPECTED_TABLES: Record<string, ExpectedColumn[]> = {
  telemetry: [
    ['timestamp', 'datetime'],
    ['elapsedSeconds', 'number', 's'],
    ['activeInterval', 'integer'],
    ['run', 'integer'],
    ['connection', 'integer'],
    ['humanPowerW', 'number', 'W'],
    ['cadenceRpm', 'number', '/min'],
    ['motorInputPowerW', 'number', 'W'],
    ['batteryVoltageV', 'number', 'V'],
    ['batteryCurrentA', 'number', 'A'],
    ['motorCurrentA', 'number', 'A'],
    ['motorRpm', 'number', '/min'],
    ['pedalTorqueNm', 'number', 'N.m'],
    ['controllerTempC', 'number', 'Cel'],
    ['motorTempC', 'number', 'Cel'],
    ['consumedAh', 'number', 'A.h'],
    ['consumedWh', 'number', 'W.h'],
    ['throttleVoltageV', 'number', 'V'],
    ['faultCode', 'integer'],
    ['assistLevel', 'integer'],
    ['controllerSpeedMps', 'number', 'm/s'],
    ['cyc.raceMode', 'integer', null, 'cyc'],
    ['cyc.speedRaw', 'number', null, 'cyc'],
  ],
  gps: [
    ['timestamp', 'datetime'],
    ['elapsedSeconds', 'number', 's'],
    ['activeInterval', 'integer'],
    ['run', 'integer'],
    ['source', 'string'],
    ['latitude', 'number', 'deg'],
    ['longitude', 'number', 'deg'],
    ['altitudeMeters', 'number', 'm'],
    ['verticalAccuracyM', 'number', 'm'],
    ['ellipsoidalAltitudeMeters', 'number', 'm'],
    ['ellipsoidalVerticalAccuracyM', 'number', 'm'],
    ['horizontalAccuracyM', 'number', 'm'],
    ['speedMps', 'number', 'm/s'],
    ['speedAccuracyMps', 'number', 'm/s'],
    ['courseDegrees', 'number', 'deg'],
    ['courseAccuracyDegrees', 'number', 'deg'],
  ],
  health: [
    ['timestamp', 'datetime'],
    ['startTimestamp', 'datetime'],
    ['elapsedSeconds', 'number', 's'],
    ['activeInterval', 'integer'],
    ['source', 'string'],
    ['sourceApp', 'string'],
    ['metric', 'string'],
    ['value', 'number'],
    ['unit', 'string'],
    ['kind', 'string'],
    ['sampleCount', 'integer'],
    ['sampleId', 'string'],
  ],
  events: [
    ['timestamp', 'datetime'],
    ['elapsedSeconds', 'number', 's'],
    ['timerSeconds', 'number', 's'],
    ['action', 'string'],
    ['interrupted', 'boolean'],
    ['source', 'string'],
  ],
};

describe('export CSV tables', () => {
  it('lists every table column with its type, unit and vendor in order', () => {
    expect(tables.map(item => [item.name, item.path, item.projection])).toEqual([
      ['telemetry', 'telemetry.csv', 'telemetry'],
      ['gps', 'gps.csv', 'gps'],
      ['health', 'health.csv', 'healthZip'],
      ['events', 'events.csv', 'lifecycle'],
    ]);
    for (const item of tables)
      expect(
        item.fields.map(field => [
          field.name,
          field.type,
          field['powerLog:unit'] ?? null,
          field['powerLog:vendor'] ?? null,
        ]),
      ).toEqual(
        EXPECTED_TABLES[item.name]!.map(([name, type, unit = null, vendor = null]) => [name, type, unit, vendor]),
      );
  });

  it('covers the 18 measurement channels of a telemetry sample without identity or bookkeeping columns', () => {
    const removed = [...SAMPLE_IDENTITY_COLUMNS, 'sequence', 'connectionEpoch', 'interruptionIndex'];
    const channels = [...REQUIRED_SAMPLE_COLUMNS, 'controllerSpeedMps'].filter(
      name => !['timestamp', 'elapsedSeconds', 'sequence'].includes(name),
    );
    expect(channels).toHaveLength(18);
    const telemetry = table('telemetry');
    for (const channel of channels) {
      const fields = telemetry.fields.filter(field => field.reads.length === 1 && field.reads[0] === channel);
      expect(fields.map(field => field.name)).toEqual([
        channel === 'raceMode' || channel === 'speedRaw' ? `cyc.${channel}` : channel,
      ]);
      expect(column('telemetry', channel)).toEqual({
        name: channel,
        type: 'number',
        platforms: ['ios', 'android', 'web'],
        consumers: expect.arrayContaining(['zip']),
      });
    }
    for (const name of [...removed, 'raceMode', 'speedRaw'])
      expect(telemetry.fields.map(field => field.name)).not.toContain(name);
    for (const name of removed) expect(column('telemetry', name)).toBeUndefined();
  });

  it('reads only columns that the matching projection delivers to the ZIP', () => {
    for (const item of tables)
      for (const field of item.fields) {
        expect(field.reads.length).toBeGreaterThan(0);
        for (const name of field.reads) {
          const source = column(item.projection, name);
          expect(source, `${item.name}.${field.name} reads ${item.projection}.${name}`).toBeDefined();
          expect(source!.consumers).toContain('zip');
        }
      }
  });
});

describe('export projections', () => {
  it('declares each projection for the export kinds its columns serve', () => {
    expect(Object.keys(PROJECTIONS)).toEqual([
      'telemetry',
      'gps',
      'gpsDiscovery',
      'healthZip',
      'healthFit',
      'lifecycle',
      'distance',
    ]);
    const types = new Map<string, string>();
    for (const [name, definition] of Object.entries(PROJECTIONS)) {
      const items = columns(name as ProjectionName);
      expect(new Set(items.map(item => item.name)).size).toBe(items.length);
      const kinds = new Set(
        items.flatMap(item => item.consumers.map(consumer => (consumer === 'zip' ? 'zip' : 'fit'))),
      );
      expect([...kinds].sort()).toEqual([...definition.kinds].sort());
      for (const item of items) {
        expect(item.platforms.length).toBeGreaterThan(0);
        expect(item.consumers.length).toBeGreaterThan(0);
        expect(types.get(item.name) ?? item.type, item.name).toBe(item.type);
        types.set(item.name, item.type);
      }
    }
  });

  it('omits original timestamps from FIT reads except Android GPS discovery', () => {
    const timestamps = Object.keys(PROJECTIONS).flatMap(name =>
      columns(name as ProjectionName)
        .filter(item => item.name === 'timestamp' && item.consumers.some(consumer => consumer !== 'zip'))
        .map(item => [name, item.platforms]),
    );
    expect(timestamps).toEqual([['gpsDiscovery', ['android']]]);
  });
});

describe('export Health mapping', () => {
  it('maps every WatchHealthMetrics identifier', () => {
    const swift = readFileSync('apple/WatchApp/WatchHealthMetrics.swift', 'utf8');
    const identifiers = [...swift.matchAll(/identifier: \.(\w+)/g)].map(
      ([, name]) => `HKQuantityTypeIdentifier${name![0]!.toUpperCase()}${name!.slice(1)}`,
    );
    expect(identifiers).toHaveLength(14);
    const mapped = HEALTH_METRICS.map(metric => metric.identifier);
    expect(mapped).toEqual(expect.arrayContaining(identifiers));
    expect(new Set(mapped).size).toBe(mapped.length);
  });

  it('keeps the Health metric order, units, conversions and kinds', () => {
    expect(
      HEALTH_METRICS.map(({ metric, key, unit, factor, kindWithoutRepresentation }) => [
        metric,
        key,
        unit,
        factor,
        kindWithoutRepresentation,
      ]),
    ).toEqual([
      ['heartRate', 'heartRateBpm', '/min', 1, 'latest'],
      ['activeEnergy', 'activeEnergyKcal', 'kcal', 1, 'cumulative'],
      ['basalEnergy', 'basalEnergyKcal', 'kcal', 1, 'cumulative'],
      ['distance', 'distanceMeters', 'm', 1, 'cumulative'],
      ['power', 'riderPowerW', 'W', 1, null],
      ['cadence', 'cadenceRpm', '/min', 1, null],
      ['speed', 'speedMps', 'm/s', 1, null],
      ['respiratoryRate', null, '/min', 1, null],
      ['oxygenSaturation', null, '%', 100, null],
      ['heartRateVariabilitySDNN', null, 'ms', 1, null],
      ['physicalEffort', null, 'kcal/(kg.h)', 1, null],
      ['functionalThresholdPower', null, 'W', 1, null],
      ['workoutEffort', null, '{score}', 1, null],
      ['estimatedWorkoutEffort', null, '{score}', 1, null],
    ]);
    expect(HEALTH_REPRESENTATIONS).toEqual({
      rawSeries: { kind: 'sample' },
      rawQuantity: { kind: 'sample', condensedKind: 'aggregate' },
      builderMostRecent: { kind: 'latest' },
      cumulativeWorkoutTotal: { kind: 'cumulative' },
      finalWorkoutTotal: { kind: 'final' },
      workoutAssociation: { kind: null },
      healthTombstone: { kind: null },
      workoutMetadata: { kind: null },
    });
    for (const { key } of HEALTH_METRICS)
      if (key) expect(column('healthZip', key)).toMatchObject({ type: 'number', consumers: ['zip'] });
    for (const key of ['heartRateBpm', 'activeEnergyKcal'])
      expect(column('healthFit', key)).toMatchObject({ type: 'number', consumers: ['discovery', 'fit'] });
  });
});

describe('export FIT catalog', () => {
  it('matches the developer-field table of docs/storage.md', () => {
    const docs = readFileSync('docs/storage.md', 'utf8');
    const rows = [...docs.matchAll(/^\| (\d+) \| `(\w+)` \| (\w+) \| (\S+) \| (.+) \|$/gm)].map(
      ([, number, name, type, units, value]) => ({
        number: Number(number),
        name,
        type,
        units,
        policy: /last value in the bin/.test(value!) ? 'last' : 'mean',
      }),
    );
    expect(rows).toHaveLength(10);
    expect(
      FIT_DEVELOPER_FIELDS.map(({ number, name, type, units, policy }) => ({ number, name, type, units, policy })),
    ).toEqual(rows);
    expect(docs).toContain(`application ID \`${FIT_DEVELOPER_APPLICATION_ID}\``);
    expect(docs).toContain(`developer data index ${FIT_DEVELOPER_DATA_INDEX}`);
  });

  it('reads each developer field from a FIT telemetry channel and fits its description', () => {
    const description: readonly FitField[] = FIT_MESSAGES.fieldDescription.fields;
    const nameSize = description.find(field => field.name === 'field_name')!.size;
    const unitsSize = description.find(field => field.name === 'units')!.size;
    for (const field of FIT_DEVELOPER_FIELDS) {
      expect(field.code).toBe(FIT_BASE_TYPES[field.type].code);
      expect(field.name.length).toBeLessThan(nameSize);
      expect(field.units.length).toBeLessThan(unitsSize);
      expect(column('telemetry', field.channel)).toMatchObject({
        type: 'number',
        platforms: ['ios', 'android', 'web'],
      });
      expect(column('telemetry', field.channel)!.consumers).toContain('fit');
    }
  });

  it('describes every message field with its base type code and size', () => {
    expect(Object.entries(FIT_MESSAGES).map(([name, message]) => [name, message.number])).toEqual([
      ['fileId', 0],
      ['developerDataId', 207],
      ['fieldDescription', 206],
      ['event', 21],
      ['record', 20],
      ['lap', 19],
      ['session', 18],
      ['activity', 34],
    ]);
    for (const message of Object.values(FIT_MESSAGES)) {
      const fields: readonly FitField[] = message.fields;
      expect(new Set(fields.map(field => field.number)).size).toBe(fields.length);
      for (const field of fields) {
        expect(field.code).toBe(FIT_BASE_TYPES[field.type].code);
        expect(field.size % FIT_BASE_TYPES[field.type].size).toBe(0);
      }
    }
  });
});
