import { expect, it } from 'vitest';
import { readFit, records, runFit, type RideSpec } from '../fixtures/export/fit/ride';

const ride: RideSpec = {
  end: 4,
  telemetry: Array.from({ length: 33 }, (_, i) => ({
    t: i / 8,
    humanPowerW: 100,
    batteryVoltageV: 50,
    batteryCurrentA: 10,
    consumedWh: 123 + i * 0.5,
    consumedAh: 12 + i * 0.01,
  })),
};

it.each(['controller', 'calculated'] as const)(
  'exports the selected %s ride totals before binning, independent of pages',
  async source => {
    const small = await runFit({ ...ride, batteryConsumptionSource: source, pageSize: 1 });
    const large = await runFit({ ...ride, batteryConsumptionSource: source, pageSize: 4096 });
    expect(small.bytes).toEqual(large.bytes);
    const sparse = await runFit({ ...ride, batteryConsumptionSource: source, pageSize: 3 }, { recordInterval: 5 });
    const expectedWh = source === 'controller' ? 16 : 2000 / 3600;
    const expectedAh = source === 'controller' ? 0.32 : 40 / 3600;
    for (const result of [small, sparse]) {
      const last = records(readFit(result.bytes)).at(-1)!;
      expect(last.developer.get(8)).toBe(Math.fround(expectedWh));
      expect(last.developer.get(9)).toBe(Math.fround(expectedAh));
      const text = new TextDecoder().decode(result.bytes);
      expect(text).toContain(source === 'controller' ? 'energy_used_controller' : 'energy_used_calc');
      expect(text).toContain(source === 'controller' ? 'charge_used_controller' : 'charge_used_calc');
    }
  },
);

it('rebaselines counters at an impossible reconnect jump and a pause while retaining supported consumption', async () => {
  const { bytes } = await runFit({
    end: 7,
    pageSize: 1,
    lifecycle: [
      { t: 0, action: 'start' },
      { t: 3, action: 'pause' },
      { t: 5, action: 'resume' },
      { t: 7, action: 'stop' },
    ],
    telemetry: [
      { t: 0, connection: 'a', consumedWh: 100, consumedAh: 10 },
      { t: 1, connection: 'a', consumedWh: 101, consumedAh: 10.1 },
      { t: 2, connection: 'b', consumedWh: 120, consumedAh: 12 },
      { t: 4, connection: 'b', consumedWh: 140, consumedAh: 14 },
      { t: 5, connection: 'b', consumedWh: 150, consumedAh: 15 },
      { t: 6, connection: 'b', consumedWh: 151, consumedAh: 15.1 },
    ],
  });
  const last = records(readFit(bytes)).at(-1)!;
  expect(last.developer.get(8)).toBe(2);
  expect(last.developer.get(9)).toBe(Math.fround(0.2));
});
