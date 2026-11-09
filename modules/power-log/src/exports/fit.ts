import type { ExportSessionView } from '../../../../shared/generated/ts/api';
import { FIT_DEVELOPER_FIELDS } from './catalog';
import { fitClock, parseStartedAt, roundHalfAway, type FitClock } from './clock';
import { FitWriter } from './fit-writer';
import { columnRows, type ExportPageReader, type ReaderPage, type Row, numberOf } from './reader';
import type { ExportSlicer } from './slicer';
import { ExportError, type ExportSink } from './types';

const FILE_ACTIVITY = 4;
const MANUFACTURER_DEVELOPMENT = 255;
const PRODUCT = 1;
const EVENT_TIMER = 0;
const EVENT_SESSION = 8;
const EVENT_LAP = 9;
const EVENT_ACTIVITY = 26;
const EVENT_TYPE_START = 0;
const EVENT_TYPE_STOP = 1;
const EVENT_TYPE_STOP_ALL = 4;
const SPORT_CYCLING = 2;
const SUB_SPORT_INDOOR_CYCLING = 6;
const SUB_SPORT_E_BIKE_FITNESS = 28;
const LAP_TRIGGER_MANUAL = 0;
const LAP_TRIGGER_SESSION_END = 7;
const ACTIVITY_MANUAL = 0;
const SEMICIRCLES = 2147483648;
const DEVELOPER_COUNT = FIT_DEVELOPER_FIELDS.length;

// One record second in physical units; the developer channels follow in catalog order.
export const SECOND = {
  power: 0,
  cadence: 1,
  motor: 2,
  assist: 3,
  heartRate: 4,
  latitude: 5,
  longitude: 6,
  altitude: 7,
  speed: 8,
  distance: 9,
  developer: 10,
} as const;
export const SECOND_VALUES = SECOND.developer + DEVELOPER_COUNT;

export const SECOND_COLUMNS: readonly (readonly [string, number])[] = [
  ['power', SECOND.power],
  ['cadence', SECOND.cadence],
  ['motor', SECOND.motor],
  ['assist', SECOND.assist],
  ['heartRate', SECOND.heartRate],
  ['latitude', SECOND.latitude],
  ['longitude', SECOND.longitude],
  ['altitude', SECOND.altitude],
  ['speed', SECOND.speed],
  ['distance', SECOND.distance],
  ...FIT_DEVELOPER_FIELDS.map((field, index) => [field.channel, SECOND.developer + index] as const),
];

export const noRecords = () =>
  new ExportError(
    'exportNoRecords',
    'This ride has no power, cadence, heart rate, GPS or distance readings for a FIT file. The ride-data ZIP is still available.',
  );

export function secondValues(row: Row, values: Float64Array): void {
  for (const [name, index] of SECOND_COLUMNS) values[index] = numberOf(row, name);
}

function encodeRecord(
  row: Row,
  physical: Float64Array,
  values: Float64Array,
  developer: Float64Array,
  clock: FitClock,
) {
  secondValues(row, physical);
  values[0] = clock.timestamp(numberOf(row, 'second'));
  const longitude = (physical[SECOND.longitude]! / 180) * SEMICIRCLES;
  values[1] = (physical[SECOND.latitude]! / 180) * SEMICIRCLES;
  values[2] = roundHalfAway(longitude) >= SEMICIRCLES - 1 ? -SEMICIRCLES : longitude;
  values[3] = (physical[SECOND.altitude]! + 500) * 5;
  values[4] = physical[SECOND.speed]! * 1000;
  values[5] = physical[SECOND.distance]! * 100;
  values[6] = physical[SECOND.heartRate]!;
  values[7] = physical[SECOND.power]!;
  values[8] = physical[SECOND.cadence]!;
  const motor = physical[SECOND.motor]!;
  values[9] = motor >= 0 ? motor : NaN;
  values[10] = physical[SECOND.assist]!;
  for (let j = 0; j < DEVELOPER_COUNT; j++) developer[j] = physical[SECOND.developer + j]!;
}

const lapDistance = (meters: number) => (Number.isNaN(meters) ? NaN : meters * 100);
const atLeastZero = (value: number) => (value >= 0 ? value : NaN);

export async function encodeFit(
  reader: ExportPageReader,
  view: ExportSessionView,
  sink: ExportSink,
  slicer: ExportSlicer,
): Promise<void> {
  const metadata = view.metadata!;
  const summary = columnRows(await reader.all(view.sessionId, 'summary'));
  const clock = fitClock(parseStartedAt(metadata.startedAt));
  const session = summary.find(row => row.kind === 'session');
  const laps = summary.filter(row => row.kind === 'lap');
  const writer = new FitWriter(sink, slicer);
  await writer.begin();
  writer.message('fileId', [FILE_ACTIVITY, MANUFACTURER_DEVELOPMENT, PRODUCT, clock.time(0)]);
  writer.developerMetadata(metadata.batteryConsumptionSource);
  const physical = new Float64Array(SECOND_VALUES);
  const values = new Float64Array(11);
  const developer = new Float64Array(DEVELOPER_COUNT);
  let written = 0;
  let lapStart = 0;
  let end = view.elapsedEnd;
  for await (const page of reader.pages(view.sessionId, 'seconds')) {
    for (const row of columnRows([page])) {
      if (row.kind === 'record') {
        encodeRecord(row, physical, values, developer, clock);
        writer.message('record', values, developer);
        written++;
      } else if (row.kind === 'lap') {
        const index = numberOf(row, 'lap');
        const lap = laps[index];
        const lapEnd = numberOf(row, 'elapsedSeconds');
        if (!lap) throw new ExportError('readFailed', 'The FIT pages hold a lap the summary does not.');
        writer.message('lap', [
          clock.time(lapEnd),
          index,
          EVENT_LAP,
          EVENT_TYPE_STOP,
          clock.time(lapStart),
          (numberOf(lap, 'endUs') - numberOf(lap, 'startUs')) / 1000,
          numberOf(lap, 'timerUs') / 1000,
          lapDistance(numberOf(lap, 'distanceM')),
          index === laps.length - 1 ? LAP_TRIGGER_SESSION_END : LAP_TRIGGER_MANUAL,
          SPORT_CYCLING,
        ]);
        lapStart = lapEnd;
        end = lapEnd;
      } else {
        const elapsed = numberOf(row, 'elapsedSeconds');
        writer.message('event', [
          clock.time(elapsed),
          EVENT_TIMER,
          row.kind === 'start' ? EVENT_TYPE_START : EVENT_TYPE_STOP_ALL,
          0,
        ]);
      }
      await writer.drain();
    }
  }
  if (written === 0) throw noRecords();
  if (!session) throw new ExportError('readFailed', 'The FIT pages hold records but no session summary.');
  const work = numberOf(session, 'workJ');
  const ascent = numberOf(session, 'ascentM');
  writer.message('session', [
    clock.time(end),
    0,
    EVENT_SESSION,
    EVENT_TYPE_STOP,
    clock.time(0),
    SPORT_CYCLING,
    metadata.indoor ? SUB_SPORT_INDOOR_CYCLING : SUB_SPORT_E_BIKE_FITNESS,
    numberOf(session, 'elapsedUs') / 1000,
    numberOf(session, 'timerUs') / 1000,
    lapDistance(numberOf(session, 'distanceM')),
    numberOf(session, 'caloriesKcal'),
    numberOf(session, 'averageSpeedMps') * 1000,
    numberOf(session, 'maxSpeedMps') * 1000,
    numberOf(session, 'averageHeartRateBpm'),
    numberOf(session, 'maxHeartRateBpm'),
    numberOf(session, 'averageCadenceRpm'),
    numberOf(session, 'maxCadenceRpm'),
    numberOf(session, 'averagePowerW'),
    numberOf(session, 'maxPowerW'),
    Number.isNaN(ascent) ? NaN : ascent,
    Number.isNaN(ascent) ? NaN : numberOf(session, 'descentM'),
    0,
    laps.length,
    Number.isNaN(work) ? NaN : work,
    atLeastZero(numberOf(session, 'averageMotorPowerW')),
    atLeastZero(numberOf(session, 'maxMotorPowerW')),
  ]);
  writer.message('activity', [
    clock.time(end),
    numberOf(session, 'timerUs') / 1000,
    1,
    ACTIVITY_MANUAL,
    EVENT_ACTIVITY,
    EVENT_TYPE_STOP,
  ]);
  await writer.finish();
}

export type { ReaderPage };
