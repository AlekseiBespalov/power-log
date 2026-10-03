import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { FAMILIES } from '../fixtures/export/fit/families';
import { FIT_START, decodeFit, fitPython, readFit, records, runFit, type RideSpec } from '../fixtures/export/fit/ride';

const python = fitPython();
const T = FIT_START;
const f = (value: number) => Math.fround(value);
const half = (value: number) => (value < 0 ? -Math.floor(-value + 0.5) : Math.floor(value + 0.5));
const semicircles = (degrees: number) => half((degrees / 180) * 2147483648);
const altitude = (meters: number) => half((meters + 500) * 5) / 5 - 500;
const LAT45 = semicircles(45);

type Fields = Record<string, unknown>;
type Expected = [number, Fields][];

const timer = (timestamp: number, start: boolean): [number, Fields] => [
  21,
  { timestamp, event: 'timer', event_type: start ? 'start' : 'stop_all', data: 0, timer_trigger: 'manual' },
];

const lap = (
  index: number,
  start: number,
  timestamp: number,
  elapsed: number,
  timerSeconds: number,
  last: boolean,
  distance?: number,
): [number, Fields] => [
  19,
  {
    timestamp,
    message_index: index,
    event: 'lap',
    event_type: 'stop',
    start_time: start,
    total_elapsed_time: elapsed,
    total_timer_time: timerSeconds,
    ...(distance === undefined ? {} : { total_distance: distance }),
    lap_trigger: last ? 'session_end' : 'manual',
    sport: 'cycling',
  },
];

const record = (timestamp: number, fields: Fields): [number, Fields] => [20, { timestamp, ...fields }];

const session = (fields: Fields): [number, Fields] => [
  18,
  {
    message_index: 0,
    event: 'session',
    event_type: 'stop',
    sport: 'cycling',
    sub_sport: 'e_bike_fitness',
    first_lap_index: 0,
    ...fields,
  },
];

const activity = (timestamp: number, timerSeconds: number): [number, Fields] => [
  34,
  { timestamp, total_timer_time: timerSeconds, num_sessions: 1, type: 'manual', event: 'activity', event_type: 'stop' },
];

const telemetry = (power: number, cadence: number, motor: number, voltage: number, assist?: number): Fields => ({
  power,
  cadence,
  motor_power: motor,
  ...(assist === undefined ? {} : { ebike_assist_mode: assist }),
  developer_fields: { battery_power: motor, battery_voltage: f(voltage) },
});

const fix = (longitude: number, meters: number | null, speed: number | null): Fields => ({
  position_lat: LAT45,
  position_long: semicircles(longitude),
  ...(meters === null ? {} : { enhanced_altitude: altitude(meters) }),
  ...(speed === null ? {} : { enhanced_speed: speed }),
});

const EXPECTED: Record<keyof typeof FAMILIES, { created: number; messages: Expected }> = {
  'telemetry-lifecycle': {
    created: T,
    messages: [
      timer(T, true),
      record(T, {
        power: 161,
        cadence: 82,
        motor_power: 310,
        ebike_assist_mode: 3,
        developer_fields: {
          battery_power: 310,
          battery_voltage: f((50.5 + 50.4) / 2),
          battery_current: f((5.94 + 6.36) / 2),
          motor_current: f(7.5),
          motor_speed: f(3050),
          motor_temperature: 41,
          controller_temperature: 35,
          pedal_torque: f(31),
          consumed_energy: f(60.51),
          consumed_charge: f(1.2501),
        },
      }),
      record(T + 1, {
        power: 0,
        cadence: 0,
        ebike_assist_mode: 0,
        developer_fields: {
          battery_power: -120,
          battery_voltage: f(51.2),
          battery_current: -2.34375,
          motor_current: -3.5,
          motor_speed: 2500,
          motor_temperature: 41,
          controller_temperature: 36,
          pedal_torque: 0,
          consumed_energy: f(60.52),
          consumed_charge: f(1.2502),
        },
      }),
      record(T + 2, { cadence: 60, developer_fields: { battery_voltage: 51 } }),
      record(T + 3, {
        power: 210,
        cadence: 91,
        motor_power: 420,
        ebike_assist_mode: 2,
        developer_fields: {
          battery_power: 420,
          battery_voltage: f((50 + 49.8) / 2),
          battery_current: f((8 + 8.8) / 2),
          consumed_energy: 0.5,
          consumed_charge: f(0.01),
        },
      }),
      record(T + 4, telemetry(210, 88, 410, 49.9, 2)),
      timer(T + 4, false),
      lap(0, T, T + 6, 6, 4.2, false),
      timer(T + 7, true),
      record(T + 7, telemetry(120, 70, 150, 50.1, 1)),
      record(T + 9, telemetry(135, 73, 165, (50.2 + 50.3) / 2, 1)),
      lap(1, T + 6, T + 9, 3, 1.6, false),
      record(T + 12, telemetry(170, 72, 262, (50 + 50.1 + 49) / 3, 5)),
      timer(T + 12, false),
      timer(T + 12, true),
      record(T + 13, telemetry(250, 90, 450, 49.5, 5)),
      record(T + 20, telemetry(180, 85, 300, 49.4, 5)),
      timer(T + 20, false),
      lap(2, T + 9, T + 20, 11, 11, true),
      session({
        timestamp: T + 20,
        start_time: T,
        total_elapsed_time: 20,
        total_timer_time: 16.8,
        avg_cadence: 65,
        max_cadence: 95,
        avg_power: 160,
        max_power: 300,
        num_laps: 3,
        total_work: 594,
        avg_lev_motor_power: 242,
        max_lev_motor_power: 500,
      }),
      activity(T + 20, 16.8),
    ],
  },
  'gps-distance': {
    created: T + 1,
    messages: [
      timer(T + 1, true),
      record(T + 1, { ...fix(7 + 2 * 0.00001, 101.5, 2.5), distance: 1.6 }),
      record(T + 2, { ...fix(7 + 3 * 0.00001, 103, 3), distance: 2.4 }),
      record(T + 3, { ...fix(7 + 4 * 0.00001, null, null), distance: 3.2 }),
      record(T + 4, { ...fix(7 + 5 * 0.00001, null, 3.5), distance: 4 }),
      lap(0, T + 1, T + 5, 4.25, 4.25, false, 4.6),
      record(T + 5, { ...fix(7 + 6 * 0.00001, 110, 4), distance: 4.8 }),
      record(T + 7, fix(7 + 8 * 0.00001, 114, 4.5)),
      record(T + 8, { ...fix(7 + 10 * 0.00001, 118, 5), distance: 4.8 }),
      record(T + 9, { distance: 5.6 }),
      timer(T + 9, false),
      timer(T + 11, true),
      record(T + 11, fix(7 + 20 * 0.00001, 116, 5.5)),
      record(T + 12, { ...fix(7 + 80 * 0.00001, 120, 6), distance: 5.6 }),
      record(T + 13, { ...fix(7 + 81 * 0.00001, 124, 6.5), distance: 6.4 }),
      record(T + 23, { ...fix(7 + 82 * 0.00001, 128, 7), distance: 14.3 }),
      record(T + 33, { ...fix(7 + 83 * 0.00001, 130, 7.5), distance: 14.3 }),
      record(T + 34, { ...fix(7 + 84 * 0.00001, 127, 8), distance: 15.1 }),
      record(T + 41, { ...fix(7 + 85 * 0.00001, 126.5, 8.5), distance: 20.1 }),
      timer(T + 41, false),
      lap(1, T + 5, T + 41, 35.75, 33.75, true, 15.5),
      session({
        timestamp: T + 41,
        start_time: T + 1,
        total_elapsed_time: 40,
        total_timer_time: 38,
        total_distance: 20.1,
        max_speed: 8.5,
        enhanced_max_speed: 8.5,
        total_ascent: 11,
        total_descent: 3,
        num_laps: 2,
      }),
      activity(T + 41, 38),
    ],
  },
  'android-controller': {
    created: T + 1,
    messages: [
      timer(T + 1, true),
      record(T + 1, { ...fix(8, 200, 4), distance: 0, ...telemetry(150, 80, 200, 50) }),
      lap(0, T + 1, T + 2, 1, 1, false, 5),
      record(T + 2, { ...fix(8 + 0.00001, null, 5), ...telemetry(160, 82, 210, 50.2) }),
      record(T + 3, { ...fix(8 + 2 * 0.00001, null, 6), distance: 12, ...telemetry(170, 84, 220, 50.4) }),
      lap(1, T + 2, T + 4, 2, 2, false, 15),
      record(T + 4, { ...fix(8 + 3 * 0.00001, 207, 7), ...telemetry(180, 86, 230, 50.6) }),
      record(T + 5, { distance: 28, ...telemetry(190, 88, 240, 50.8) }),
      record(T + 6, { distance: 32.2, ...telemetry(200, 90, 250, 51) }),
      timer(T + 6, false),
      timer(T + 7, true),
      record(T + 7, { ...fix(8 + 5 * 0.00001, 210, 6), distance: 32.2, ...telemetry(120, 70, 100, 49) }),
      record(T + 8, telemetry(130, 72, 110, 49.2)),
      record(T + 9, fix(8 + 6 * 0.00001, 214, 6)),
      record(T + 10, fix(8 + 7 * 0.00001, 214.2, 6)),
      record(T + 11, { distance: 59.8, ...telemetry(140, 74, 120, 49.4) }),
      timer(T + 11, false),
      lap(2, T + 4, T + 11, 7, 6.2, true, 39.8),
      session({
        timestamp: T + 11,
        start_time: T + 1,
        total_elapsed_time: 10,
        total_timer_time: 9.2,
        total_distance: 59.8,
        avg_speed: 6.5,
        max_speed: 7,
        enhanced_avg_speed: 6.5,
        enhanced_max_speed: 7,
        avg_cadence: 81,
        max_cadence: 90,
        avg_power: 161,
        max_power: 200,
        total_ascent: 4,
        total_descent: 0,
        num_laps: 3,
        total_work: 997,
        avg_lev_motor_power: 193,
        max_lev_motor_power: 250,
      }),
      activity(T + 11, 9.2),
    ],
  },
  'health-latest': {
    created: T,
    messages: [
      timer(T, true),
      record(T, { distance: 0 }),
      record(T + 1, { heart_rate: 100 }),
      record(T + 4, { heart_rate: 110 }),
      record(T + 5, { distance: 20 }),
      record(T + 8, { distance: 20, heart_rate: 120 }),
      lap(0, T, T + 10, 10, 10, false),
      record(T + 12, { distance: 35, heart_rate: 125 }),
      record(T + 14, { distance: 35 }),
      record(T + 17, { heart_rate: 130 }),
      record(T + 19, { distance: 53 }),
      timer(T + 20, false),
      timer(T + 22, true),
      record(T + 23, { distance: 53, heart_rate: 140 }),
      record(T + 26, { heart_rate: 145 }),
      record(T + 29, { distance: 78 }),
      record(T + 30, { heart_rate: 135 }),
      timer(T + 30, false),
      lap(1, T + 10, T + 30, 20, 18, true),
      session({
        timestamp: T + 30,
        start_time: T,
        total_elapsed_time: 30,
        total_timer_time: 28,
        total_distance: 78,
        total_calories: 38,
        avg_heart_rate: 125,
        max_heart_rate: 145,
        num_laps: 2,
      }),
      activity(T + 30, 28),
    ],
  },
  'health-fallback': {
    created: T,
    messages: [
      timer(T, true),
      record(T + 1, { heart_rate: 100 }),
      record(T + 3, { heart_rate: 104 }),
      record(T + 5, { heart_rate: 108 }),
      timer(T + 12, false),
      lap(0, T, T + 12, 12, 12, true),
      session({
        timestamp: T + 12,
        start_time: T,
        total_elapsed_time: 12,
        total_timer_time: 12,
        total_calories: 7,
        avg_heart_rate: 104,
        max_heart_rate: 108,
        num_laps: 1,
      }),
      activity(T + 12, 12),
    ],
  },
};

const golden = (name: string) => `tests/fixtures/export/fit/${name}.fit.json`;
const expectedBytes = (name: string) =>
  Buffer.from((JSON.parse(readFileSync(golden(name), 'utf8')) as { base64: string }).base64, 'base64');

describe.each(Object.entries(FAMILIES) as [keyof typeof FAMILIES, RideSpec][])('FIT family %s', (name, ride) => {
  it('matches its checked-in bytes at every page size', async () => {
    const { bytes, sink, source } = await runFit(ride);
    if (process.env.POWER_LOG_UPDATE_FIT_FIXTURES === '1' || !existsSync(golden(name)))
      writeFileSync(golden(name), JSON.stringify({ base64: Buffer.from(bytes).toString('base64') }) + '\n');
    expect(Buffer.from(bytes).equals(expectedBytes(name))).toBe(true);
    expect(sink.committed).toBe(`power-log-${ride.rideId}.fit`);
    expect(source.closed).toBe(1);
    for (const pageSize of [1, 17]) {
      const paged = await runFit({ ...ride, pageSize });
      expect(Buffer.from(paged.bytes).equals(Buffer.from(bytes)), `page size ${pageSize}`).toBe(true);
    }
  });

  it('orders its messages as derived from the rules', async () => {
    const { bytes, result } = await runFit(ride);
    const messages = readFit(bytes);
    const kinds = messages.slice(12).map(message => message.global);
    expect(kinds).toEqual(EXPECTED[name].messages.map(([kind]) => kind));
    expect(result.records).toBe(records(messages).length);
    expect(result.recordInterval).toBe(1);
  });

  it.skipIf(!python)('decodes with the Garmin FIT SDK to the values derived from the rules', async () => {
    const { bytes } = await runFit(ride);
    const decoded = decodeFit(python!, bytes);
    expect(decoded.header).toEqual({
      size: 14,
      protocol: 32,
      profile: 21217,
      data_size: bytes.length - 16,
      type: '.FIT',
    });
    const [file, developer, ...descriptions] = decoded.messages.slice(0, 12);
    expect(file).toEqual({
      mesg_num: 0,
      fields: { type: 'activity', manufacturer: 'development', product: 1, time_created: EXPECTED[name].created },
    });
    expect(developer!.mesg_num).toBe(207);
    expect(descriptions.map(message => message.mesg_num)).toEqual(Array(10).fill(206));
    const actual = decoded.messages.slice(12).map(message => [message.mesg_num, message.fields]);
    expect(actual).toEqual(EXPECTED[name].messages);
  });
});
