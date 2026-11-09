import { EXPORT_TABLES } from '../../../../shared/generated/ts/export-tables';
import type {
  FitDeveloperField,
  FitMessage,
  HealthMetricDefinition,
  HealthRepresentationDefinition,
  TableFieldType,
} from './types';

export interface TableField {
  readonly name: string;
  readonly type: TableFieldType;
  readonly description: string;
  readonly reads: readonly string[];
  readonly unit?: string;
  readonly vendor?: string;
}

export interface TableDefinition {
  readonly name: string;
  readonly path: string;
  readonly projection: string;
  readonly fields: readonly TableField[];
}

export const TABLES: readonly TableDefinition[] = EXPORT_TABLES;

export function table(name: string): TableDefinition {
  const found = TABLES.find(entry => entry.name === name);
  if (!found) throw new Error(`The export catalog has no table ${name}`);
  return found;
}

export const CONSUMPTION_METRICS = [
  'controllerEnergyWh',
  'controllerChargeAh',
  'calculatedEnergyWh',
  'calculatedChargeAh',
] as const;

export const HEALTH_METRICS = [
  {
    metric: 'heartRate',
    key: 'heartRateBpm',
    identifier: 'HKQuantityTypeIdentifierHeartRate',
    unit: '/min',
    factor: 1,
    kindWithoutRepresentation: 'latest',
  },
  {
    metric: 'activeEnergy',
    key: 'activeEnergyKcal',
    identifier: 'HKQuantityTypeIdentifierActiveEnergyBurned',
    unit: 'kcal',
    factor: 1,
    kindWithoutRepresentation: 'cumulative',
  },
  {
    metric: 'basalEnergy',
    key: 'basalEnergyKcal',
    identifier: 'HKQuantityTypeIdentifierBasalEnergyBurned',
    unit: 'kcal',
    factor: 1,
    kindWithoutRepresentation: 'cumulative',
  },
  {
    metric: 'distance',
    key: 'distanceMeters',
    identifier: 'HKQuantityTypeIdentifierDistanceCycling',
    unit: 'm',
    factor: 1,
    kindWithoutRepresentation: 'cumulative',
  },
  {
    metric: 'power',
    key: 'riderPowerW',
    identifier: 'HKQuantityTypeIdentifierCyclingPower',
    unit: 'W',
    factor: 1,
    kindWithoutRepresentation: null,
  },
  {
    metric: 'cadence',
    key: 'cadenceRpm',
    identifier: 'HKQuantityTypeIdentifierCyclingCadence',
    unit: '/min',
    factor: 1,
    kindWithoutRepresentation: null,
  },
  {
    metric: 'speed',
    key: 'speedMps',
    identifier: 'HKQuantityTypeIdentifierCyclingSpeed',
    unit: 'm/s',
    factor: 1,
    kindWithoutRepresentation: null,
  },
  {
    metric: 'respiratoryRate',
    key: null,
    identifier: 'HKQuantityTypeIdentifierRespiratoryRate',
    unit: '/min',
    factor: 1,
    kindWithoutRepresentation: null,
  },
  {
    metric: 'oxygenSaturation',
    key: null,
    identifier: 'HKQuantityTypeIdentifierOxygenSaturation',
    unit: '%',
    factor: 100,
    kindWithoutRepresentation: null,
  },
  {
    metric: 'heartRateVariabilitySDNN',
    key: null,
    identifier: 'HKQuantityTypeIdentifierHeartRateVariabilitySDNN',
    unit: 'ms',
    factor: 1,
    kindWithoutRepresentation: null,
  },
  {
    metric: 'physicalEffort',
    key: null,
    identifier: 'HKQuantityTypeIdentifierPhysicalEffort',
    unit: 'kcal/(kg.h)',
    factor: 1,
    kindWithoutRepresentation: null,
  },
  {
    metric: 'functionalThresholdPower',
    key: null,
    identifier: 'HKQuantityTypeIdentifierCyclingFunctionalThresholdPower',
    unit: 'W',
    factor: 1,
    kindWithoutRepresentation: null,
  },
  {
    metric: 'workoutEffort',
    key: null,
    identifier: 'HKQuantityTypeIdentifierWorkoutEffortScore',
    unit: '{score}',
    factor: 1,
    kindWithoutRepresentation: null,
  },
  {
    metric: 'estimatedWorkoutEffort',
    key: null,
    identifier: 'HKQuantityTypeIdentifierEstimatedWorkoutEffortScore',
    unit: '{score}',
    factor: 1,
    kindWithoutRepresentation: null,
  },
] as const satisfies readonly HealthMetricDefinition[];

export const HEALTH_REPRESENTATIONS = {
  rawSeries: { kind: 'sample' },
  intervalRecord: { kind: 'intervalRecord' },
  rawQuantity: { kind: 'sample', condensedKind: 'aggregate' },
  builderMostRecent: { kind: 'latest' },
  cumulativeWorkoutTotal: { kind: 'cumulative' },
  finalWorkoutTotal: { kind: 'final' },
  workoutAssociation: { kind: null },
  healthTombstone: { kind: null },
  workoutMetadata: { kind: null },
} as const satisfies Record<string, HealthRepresentationDefinition>;

export const FIT_MESSAGES = {
  fileId: {
    number: 0,
    fields: [
      { name: 'type', number: 0, type: 'enum', code: 0x00, size: 1, scale: 1, offset: 0 },
      { name: 'manufacturer', number: 1, type: 'uint16', code: 0x84, size: 2, scale: 1, offset: 0 },
      { name: 'product', number: 2, type: 'uint16', code: 0x84, size: 2, scale: 1, offset: 0 },
      { name: 'time_created', number: 4, type: 'uint32', code: 0x86, size: 4, scale: 1, offset: 0 },
    ],
  },
  developerDataId: {
    number: 207,
    fields: [
      { name: 'application_id', number: 1, type: 'byte', code: 0x0d, size: 16, scale: 1, offset: 0 },
      { name: 'developer_data_index', number: 3, type: 'uint8', code: 0x02, size: 1, scale: 1, offset: 0 },
    ],
  },
  fieldDescription: {
    number: 206,
    fields: [
      { name: 'developer_data_index', number: 0, type: 'uint8', code: 0x02, size: 1, scale: 1, offset: 0 },
      { name: 'field_definition_number', number: 1, type: 'uint8', code: 0x02, size: 1, scale: 1, offset: 0 },
      { name: 'fit_base_type_id', number: 2, type: 'uint8', code: 0x02, size: 1, scale: 1, offset: 0 },
      { name: 'field_name', number: 3, type: 'string', code: 0x07, size: 23, scale: 1, offset: 0 },
      { name: 'units', number: 8, type: 'string', code: 0x07, size: 4, scale: 1, offset: 0 },
    ],
  },
  event: {
    number: 21,
    fields: [
      { name: 'timestamp', number: 253, type: 'uint32', code: 0x86, size: 4, scale: 1, offset: 0 },
      { name: 'event', number: 0, type: 'enum', code: 0x00, size: 1, scale: 1, offset: 0 },
      { name: 'event_type', number: 1, type: 'enum', code: 0x00, size: 1, scale: 1, offset: 0 },
      { name: 'data', number: 3, type: 'uint32', code: 0x86, size: 4, scale: 1, offset: 0 },
    ],
  },
  record: {
    number: 20,
    fields: [
      { name: 'timestamp', number: 253, type: 'uint32', code: 0x86, size: 4, scale: 1, offset: 0 },
      { name: 'position_lat', number: 0, type: 'sint32', code: 0x85, size: 4, scale: 1, offset: 0 },
      { name: 'position_long', number: 1, type: 'sint32', code: 0x85, size: 4, scale: 1, offset: 0 },
      { name: 'enhanced_altitude', number: 78, type: 'uint32', code: 0x86, size: 4, scale: 5, offset: 500 },
      { name: 'enhanced_speed', number: 73, type: 'uint32', code: 0x86, size: 4, scale: 1000, offset: 0 },
      { name: 'distance', number: 5, type: 'uint32', code: 0x86, size: 4, scale: 100, offset: 0 },
      { name: 'heart_rate', number: 3, type: 'uint8', code: 0x02, size: 1, scale: 1, offset: 0 },
      { name: 'power', number: 7, type: 'uint16', code: 0x84, size: 2, scale: 1, offset: 0 },
      { name: 'cadence', number: 4, type: 'uint8', code: 0x02, size: 1, scale: 1, offset: 0 },
      { name: 'motor_power', number: 82, type: 'uint16', code: 0x84, size: 2, scale: 1, offset: 0 },
      { name: 'ebike_assist_mode', number: 119, type: 'uint8', code: 0x02, size: 1, scale: 1, offset: 0 },
    ],
  },
  lap: {
    number: 19,
    fields: [
      { name: 'timestamp', number: 253, type: 'uint32', code: 0x86, size: 4, scale: 1, offset: 0 },
      { name: 'message_index', number: 254, type: 'uint16', code: 0x84, size: 2, scale: 1, offset: 0 },
      { name: 'event', number: 0, type: 'enum', code: 0x00, size: 1, scale: 1, offset: 0 },
      { name: 'event_type', number: 1, type: 'enum', code: 0x00, size: 1, scale: 1, offset: 0 },
      { name: 'start_time', number: 2, type: 'uint32', code: 0x86, size: 4, scale: 1, offset: 0 },
      { name: 'total_elapsed_time', number: 7, type: 'uint32', code: 0x86, size: 4, scale: 1000, offset: 0 },
      { name: 'total_timer_time', number: 8, type: 'uint32', code: 0x86, size: 4, scale: 1000, offset: 0 },
      { name: 'total_distance', number: 9, type: 'uint32', code: 0x86, size: 4, scale: 100, offset: 0 },
      { name: 'lap_trigger', number: 24, type: 'enum', code: 0x00, size: 1, scale: 1, offset: 0 },
      { name: 'sport', number: 25, type: 'enum', code: 0x00, size: 1, scale: 1, offset: 0 },
    ],
  },
  session: {
    number: 18,
    fields: [
      { name: 'timestamp', number: 253, type: 'uint32', code: 0x86, size: 4, scale: 1, offset: 0 },
      { name: 'message_index', number: 254, type: 'uint16', code: 0x84, size: 2, scale: 1, offset: 0 },
      { name: 'event', number: 0, type: 'enum', code: 0x00, size: 1, scale: 1, offset: 0 },
      { name: 'event_type', number: 1, type: 'enum', code: 0x00, size: 1, scale: 1, offset: 0 },
      { name: 'start_time', number: 2, type: 'uint32', code: 0x86, size: 4, scale: 1, offset: 0 },
      { name: 'sport', number: 5, type: 'enum', code: 0x00, size: 1, scale: 1, offset: 0 },
      { name: 'sub_sport', number: 6, type: 'enum', code: 0x00, size: 1, scale: 1, offset: 0 },
      { name: 'total_elapsed_time', number: 7, type: 'uint32', code: 0x86, size: 4, scale: 1000, offset: 0 },
      { name: 'total_timer_time', number: 8, type: 'uint32', code: 0x86, size: 4, scale: 1000, offset: 0 },
      { name: 'total_distance', number: 9, type: 'uint32', code: 0x86, size: 4, scale: 100, offset: 0 },
      { name: 'total_calories', number: 11, type: 'uint16', code: 0x84, size: 2, scale: 1, offset: 0 },
      { name: 'avg_speed', number: 14, type: 'uint16', code: 0x84, size: 2, scale: 1000, offset: 0 },
      { name: 'max_speed', number: 15, type: 'uint16', code: 0x84, size: 2, scale: 1000, offset: 0 },
      { name: 'avg_heart_rate', number: 16, type: 'uint8', code: 0x02, size: 1, scale: 1, offset: 0 },
      { name: 'max_heart_rate', number: 17, type: 'uint8', code: 0x02, size: 1, scale: 1, offset: 0 },
      { name: 'avg_cadence', number: 18, type: 'uint8', code: 0x02, size: 1, scale: 1, offset: 0 },
      { name: 'max_cadence', number: 19, type: 'uint8', code: 0x02, size: 1, scale: 1, offset: 0 },
      { name: 'avg_power', number: 20, type: 'uint16', code: 0x84, size: 2, scale: 1, offset: 0 },
      { name: 'max_power', number: 21, type: 'uint16', code: 0x84, size: 2, scale: 1, offset: 0 },
      { name: 'total_ascent', number: 22, type: 'uint16', code: 0x84, size: 2, scale: 1, offset: 0 },
      { name: 'total_descent', number: 23, type: 'uint16', code: 0x84, size: 2, scale: 1, offset: 0 },
      { name: 'first_lap_index', number: 25, type: 'uint16', code: 0x84, size: 2, scale: 1, offset: 0 },
      { name: 'num_laps', number: 26, type: 'uint16', code: 0x84, size: 2, scale: 1, offset: 0 },
      { name: 'total_work', number: 48, type: 'uint32', code: 0x86, size: 4, scale: 1, offset: 0 },
      { name: 'avg_lev_motor_power', number: 129, type: 'uint16', code: 0x84, size: 2, scale: 1, offset: 0 },
      { name: 'max_lev_motor_power', number: 130, type: 'uint16', code: 0x84, size: 2, scale: 1, offset: 0 },
    ],
  },
  activity: {
    number: 34,
    fields: [
      { name: 'timestamp', number: 253, type: 'uint32', code: 0x86, size: 4, scale: 1, offset: 0 },
      { name: 'total_timer_time', number: 0, type: 'uint32', code: 0x86, size: 4, scale: 1000, offset: 0 },
      { name: 'num_sessions', number: 1, type: 'uint16', code: 0x84, size: 2, scale: 1, offset: 0 },
      { name: 'type', number: 2, type: 'enum', code: 0x00, size: 1, scale: 1, offset: 0 },
      { name: 'event', number: 3, type: 'enum', code: 0x00, size: 1, scale: 1, offset: 0 },
      { name: 'event_type', number: 4, type: 'enum', code: 0x00, size: 1, scale: 1, offset: 0 },
    ],
  },
} as const satisfies Record<string, FitMessage>;

export const FIT_DEVELOPER_APPLICATION_ID = '9b716e18-08fe-4c23-b64f-be64d517cb48';
export const FIT_DEVELOPER_DATA_INDEX = 0;

export const FIT_DEVELOPER_FIELDS = [
  {
    number: 0,
    name: 'battery_power',
    type: 'sint16',
    code: 0x83,
    units: 'W',
    policy: 'mean',
    channel: 'motorInputPowerW',
  },
  {
    number: 1,
    name: 'battery_voltage',
    type: 'float32',
    code: 0x88,
    units: 'V',
    policy: 'mean',
    channel: 'batteryVoltageV',
  },
  {
    number: 2,
    name: 'battery_current',
    type: 'float32',
    code: 0x88,
    units: 'A',
    policy: 'mean',
    channel: 'batteryCurrentA',
  },
  {
    number: 3,
    name: 'motor_current',
    type: 'float32',
    code: 0x88,
    units: 'A',
    policy: 'mean',
    channel: 'motorCurrentA',
  },
  { number: 4, name: 'motor_speed', type: 'float32', code: 0x88, units: 'rpm', policy: 'mean', channel: 'motorRpm' },
  {
    number: 5,
    name: 'motor_temperature',
    type: 'sint16',
    code: 0x83,
    units: 'C',
    policy: 'mean',
    channel: 'motorTempC',
  },
  {
    number: 6,
    name: 'controller_temperature',
    type: 'sint16',
    code: 0x83,
    units: 'C',
    policy: 'mean',
    channel: 'controllerTempC',
  },
  {
    number: 7,
    name: 'pedal_torque',
    type: 'float32',
    code: 0x88,
    units: 'Nm',
    policy: 'mean',
    channel: 'pedalTorqueNm',
  },
  {
    number: 8,
    name: 'consumed_energy',
    type: 'float32',
    code: 0x88,
    units: 'Wh',
    policy: 'last',
    channel: 'consumedWh',
  },
  {
    number: 9,
    name: 'consumed_charge',
    type: 'float32',
    code: 0x88,
    units: 'Ah',
    policy: 'last',
    channel: 'consumedAh',
  },
] as const satisfies readonly FitDeveloperField[];
