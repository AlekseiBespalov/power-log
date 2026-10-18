import { expect, it } from 'vitest';
import { exportFit } from '../../src/core/export/fit-export';
import type { FitPowerSource } from '../../src/core/export/types';
import { MemorySource, MemorySink, readFit, records, type RideSpec } from '../fixtures/export/fit/ride';

async function exported(ride: RideSpec, powerSource?: FitPowerSource, recordInterval = 1) {
  const sink = new MemorySink();
  await exportFit(
    new MemorySource(ride),
    async () => sink,
    { rideId: 'ride', context: { platform: 'ios', exportedAt: '2026-01-01T08:00:00Z' }, powerSource },
    { recordInterval },
  );
  const messages = readFit(sink.bytes);
  return { bytes: sink.bytes, records: records(messages), session: messages.find(m => m.global === 18)!.fields };
}

it('defaults to rider power and switches standard records, average, maximum and work together', async () => {
  const ride: RideSpec = {
    end: 3,
    telemetry: [0, 1, 2].map(t => ({ t, humanPowerW: 100, motorInputPowerW: 500 + t * 250 })),
  };
  const rider = await exported(ride);
  const battery = await exported(ride, 'battery');
  const paged = await exported({ ...ride, pageSize: 1 }, 'battery');
  expect(paged.bytes).toEqual(battery.bytes);
  expect(rider.records.map(r => r.fields.get(7))).toEqual([100, 100, 100]);
  expect(battery.records.map(r => r.fields.get(7))).toEqual([500, 750, 1000]);
  for (const result of [rider, battery]) {
    expect(result.records.map(r => r.fields.get(82))).toEqual([500, 750, 1000]);
    expect(result.records.map(r => r.developer.get(0))).toEqual([500, 750, 1000]);
    expect(result.session.get(129)).toBe(750);
    expect(result.session.get(130)).toBe(1000);
  }
  expect([rider.session.get(20), rider.session.get(21), rider.session.get(48)]).toEqual([100, 100, 200]);
  expect([battery.session.get(20), battery.session.get(21), battery.session.get(48)]).toEqual([750, 1000, 1500]);
  const sparse = await exported(ride, 'battery', 5);
  expect(sparse.records.map(r => r.fields.get(7))).toEqual([750]);
  expect(sparse.session).toEqual(battery.session);
});

it('omits missing, negative and out-of-range battery power without falling back to rider power', async () => {
  const result = await exported(
    {
      end: 5,
      telemetry: [undefined, -20, 40_000, 0, 300].map((motorInputPowerW, t) => ({
        t,
        humanPowerW: 100,
        cadenceRpm: 80,
        motorInputPowerW,
      })),
    },
    'battery',
  );
  expect(result.records.map(r => r.fields.get(7))).toEqual([undefined, undefined, undefined, 0, 300]);
  expect([result.session.get(20), result.session.get(21), result.session.get(48)]).toEqual([150, 300, 150]);
  expect(result.records[1]!.developer.get(0)).toBe(-20);
});

it('exports a battery-only controller without requiring a rider-power channel', async () => {
  const result = await exported({ end: 2, telemetry: [{ t: 0, motorInputPowerW: 600 }] }, 'battery');
  expect(result.records[0]!.fields.get(7)).toBe(600);
});
