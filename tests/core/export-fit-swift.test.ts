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
  source: 'controller' | 'phone' | 'watch';
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

// Retain the independent distance projection from the earlier Swift parity rides.
// The retired Swift FIT bytes used raw counters and no longer represent this export contract.
function reference(name: string): DistanceRow[] {
  return (
    JSON.parse(readFileSync(`tests/fixtures/export/fit/swift-${name}.json`, 'utf8')) as {
      distance: DistanceRow[];
    }
  ).distance;
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

type Messages = { mesg_num: number; fields: Record<string, unknown> }[];
const expected = JSON.parse(readFileSync('tests/fixtures/export/fit/swift-expected.json', 'utf8')) as Record<
  string,
  Messages
>;

describe.skipIf(!python).each(rides)('current FIT exporter for the regression ride $name', ride => {
  it('matches the frozen current FIT values and ordering', async () => {
    const { bytes } = await runFit(rideSpec(ride, reference(ride.name)));
    const mine = decodeFit(python!, bytes).messages;
    expect(mine.slice(12)).toEqual(expected[ride.name]);
  });
});
