import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import {
  FIT_DEVELOPER_FIELDS,
  FIT_MESSAGES,
  HEALTH_METRICS,
  HEALTH_REPRESENTATIONS,
  TABLES,
  type TableDefinition,
} from '../../modules/power-log/src/exports/catalog';
import type { FitBaseType, FitField, TableFieldType } from '../../modules/power-log/src/exports/types';

const FIT_BASE_TYPES = {
  enum: { code: 0x00, size: 1 },
  uint8: { code: 0x02, size: 1 },
  sint16: { code: 0x83, size: 2 },
  uint16: { code: 0x84, size: 2 },
  sint32: { code: 0x85, size: 4 },
  uint32: { code: 0x86, size: 4 },
  string: { code: 0x07, size: 1 },
  float32: { code: 0x88, size: 4 },
  byte: { code: 0x0d, size: 1 },
} as const satisfies Record<FitBaseType, { code: number; size: number }>;

const tables: readonly TableDefinition[] = TABLES;

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
    ['controller.raceMode', 'integer', null, 'controller'],
    ['controller.speedRaw', 'number', null, 'controller'],
  ],
  battery_consumption: [
    ['timestamp', 'datetime'],
    ['elapsedSeconds', 'number', 's'],
    ['controllerEnergyWh', 'number', 'Wh'],
    ['controllerChargeAh', 'number', 'Ah'],
    ['calculatedEnergyWh', 'number', 'Wh'],
    ['calculatedChargeAh', 'number', 'Ah'],
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
    ['originalAltitudeMeters', 'number', 'm'],
    ['originalVerticalAccuracyM', 'number', 'm'],
    ['originalAltitudeDatum', 'string'],
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
    ['metric', 'string'],
    ['value', 'number'],
    ['unit', 'string'],
    ['kind', 'string'],
    ['sampleCount', 'integer'],
    ['provider', 'string'],
    ['parentId', 'string'],
    ['memberIndex', 'string'],
    ['dataOrigin', 'string'],
    ['originalEventId', 'string'],
    ['originalEndTimestamp', 'string'],
    ['logicalKey', 'string'],
    ['targetEventId', 'string'],
    ['originRevision', 'string'],
    ['valueBits', 'string'],
    ['evidence', 'string'],
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
      ['battery_consumption', 'battery-consumption.csv', 'consumption'],
      ['gps', 'gps.csv', 'gps'],
      ['health', 'health.csv', 'healthZip'],
      ['events', 'events.csv', 'lifecycle'],
    ]);
    for (const item of tables)
      expect(item.fields.map(field => [field.name, field.type, field.unit ?? null, field.vendor ?? null])).toEqual(
        EXPECTED_TABLES[item.name]!.map(([name, type, unit = null, vendor = null]) => [name, type, unit, vendor]),
      );
  });
});

describe('export Health mapping', () => {
  it('maps every Apple Health readback identifier', () => {
    const swift = readFileSync('modules/power-log/ios/health/HealthTypes.swift', 'utf8');
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
      intervalRecord: { kind: 'intervalRecord' },
      workoutMetadata: { kind: null },
    });
  });
});

describe('export FIT catalog', () => {
  it('gives each developer field its base type and a name and units its description fits', () => {
    const description: readonly FitField[] = FIT_MESSAGES.fieldDescription.fields;
    const nameSize = description.find(field => field.name === 'field_name')!.size;
    const unitsSize = description.find(field => field.name === 'units')!.size;
    for (const field of FIT_DEVELOPER_FIELDS) {
      expect(field.code).toBe(FIT_BASE_TYPES[field.type].code);
      expect(field.name.length).toBeLessThan(nameSize);
      expect(field.units.length).toBeLessThan(unitsSize);
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
