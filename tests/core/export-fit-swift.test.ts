import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import {
  decodeFit,
  fitPython,
  runFit,
  type DistanceRow,
  type GpsRow,
  type HealthRow,
  type LifecycleRow,
  type RideSpec,
  type TelemetryRow,
} from '../fixtures/export/fit/ride';

interface SharedEvent {
  t: number;
  kind: 'lifecycle' | 'telemetry' | 'location' | 'health';
  source: 'cyc' | 'phone' | 'watch';
  payload: Record<string, number | string>;
}

interface SharedRide {
  name: string;
  startedAt: string;
  end: number;
  watchEnabled: boolean;
  indoor: boolean;
  distanceSource: 'gps:phone';
  events: SharedEvent[];
}

const python = fitPython();
const rides = JSON.parse(readFileSync('tests/fixtures/export/fit/swift-rides.json', 'utf8')) as SharedRide[];

function reference(name: string): { fit: Uint8Array; distance: DistanceRow[] } {
  const stored = JSON.parse(readFileSync(`tests/fixtures/export/fit/swift-${name}.json`, 'utf8')) as {
    fit: string;
    distance: DistanceRow[];
  };
  return { fit: Buffer.from(stored.fit, 'base64'), distance: stored.distance };
}

function rideSpec(ride: SharedRide, distance: DistanceRow[]): RideSpec {
  const of = (kind: SharedEvent['kind']) => ride.events.filter(event => event.kind === kind);
  return {
    platform: 'ios',
    rideId: ride.name,
    startedAt: ride.startedAt,
    end: ride.end,
    watchEnabled: ride.watchEnabled,
    indoor: ride.indoor,
    lifecycle: of('lifecycle').map(({ t, source, payload }): LifecycleRow => ({
      t,
      action: payload.action as string,
      producer: source,
    })),
    telemetry: of('telemetry').map(({ t, payload }): TelemetryRow => {
      const { connectionEpoch, clockEpoch, ...values } = payload;
      return { t, connection: (connectionEpoch as string) ?? null, epoch: (clockEpoch as string) ?? null, ...values };
    }),
    gps: of('location').map(({ t, source, payload }): GpsRow => ({ t, producer: source as 'phone', ...payload })),
    health: of('health').map(({ t, source, payload }): HealthRow => ({ t, producer: source as 'phone', ...payload })),
    profile: { source: ride.distanceSource, kind: 'gps', producer: 'phone' },
    distance,
  };
}

const START = 1136160000;
type Messages = { mesg_num: number; fields: Record<string, unknown> }[];

const find = (messages: Messages, mesgNum: number, timestamp?: number) => {
  const index = messages.findIndex(
    message => message.mesg_num === mesgNum && (timestamp === undefined || message.fields.timestamp === timestamp),
  );
  expect(index, `message ${mesgNum} at ${timestamp}`).toBeGreaterThanOrEqual(0);
  return index;
};

function retrigger(messages: Messages, triggers: string[]): void {
  const laps = messages.filter(message => message.mesg_num === 19);
  expect(laps.map(lap => lap.fields.lap_trigger)).toEqual(['manual', ...Array(laps.length - 1).fill('time')]);
  laps.forEach((lap, index) => (lap.fields.lap_trigger = triggers[index]));
}

const SPEC_CHANGES: Record<string, (messages: Messages) => void> = {
  shared: messages => {
    retrigger(messages, ['manual', 'session_end']);
    const record = messages.findLastIndex(message => message.mesg_num === 20);
    const stop = messages.findLastIndex(message => message.mesg_num === 21);
    expect(messages[record]!.fields.timestamp).toBe(START + 40);
    expect(record).toBeGreaterThan(stop);
    messages.splice(stop, 0, ...messages.splice(record, 1));
  },
  changes: messages => {
    retrigger(messages, ['manual', 'manual', 'session_end']);
    for (const lap of messages.filter(message => message.mesg_num === 19).slice(1)) {
      expect(lap.fields.total_distance).toBe(0);
      delete lap.fields.total_distance;
    }
    const third = messages[find(messages, 20, START + 3)]!.fields;
    expect(third.enhanced_speed).toBeUndefined();
    third.enhanced_speed = 3;
    const fifth = messages[find(messages, 20, START + 5)]!.fields;
    expect(fifth.enhanced_altitude).toBe(100);
    delete fifth.enhanced_altitude;
    const stop = find(messages, 21, START + 20);
    expect(messages[stop + 1]!.mesg_num).toBe(19);
    messages.splice(stop, 0, ...messages.splice(stop + 1, 1));
    const session = messages[find(messages, 18)]!.fields;
    expect(session.avg_heart_rate).toBeUndefined();
    session.avg_heart_rate = 115;
  },
};

describe.skipIf(!python).each(rides)('the iPhone FIT writer for the shared ride $name', ride => {
  it('decodes to the same values except for the changes SPEC lists', async () => {
    const swift = reference(ride.name);
    const { bytes } = await runFit(rideSpec(ride, swift.distance));
    const mine = decodeFit(python!, bytes).messages;
    const theirs = decodeFit(python!, swift.fit).messages;
    expect(mine.slice(0, 12)).toEqual(theirs.slice(0, 12));
    const expected = theirs.slice(12).map(message => ({ ...message, fields: { ...message.fields } }));
    SPEC_CHANGES[ride.name]!(expected);
    expect(mine.slice(12)).toEqual(expected);
  });
});
