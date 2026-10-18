import { expect, it, vi } from 'vitest';
import { BrowserRideStore } from '../../src/services/browser-ride-store';
import { scanBrowserLocations } from '../../src/services/browser-analytics';
import { browserRide } from '../support/browser-ride';
import { TelemetryMonitor } from '../../src/core/monitor-data';
import { syntheticSample } from '../fixtures/synthetic-sample';
import references from '../fixtures/contract/analytics/iphone-reference.json';

it('loads the invalid-barrier contract through the production browser monitor', async () => {
  const fixture = (
    references.cases as unknown as {
      name: string;
      input: { events: { elapsedSeconds: number; timestamp: string; payload: Record<string, number> }[] };
    }[]
  ).find(item => item.name === 'invalid-barrier')!;
  const samples = fixture.input.events.map((event, sequence) => ({
    ...syntheticSample(event.elapsedSeconds!, sequence, event.timestamp),
    ...event.payload,
  }));
  const monitor = new TelemetryMonitor('contract', false, samples);
  const result = await monitor.rangeStats({
    generation: 1,
    expectedRevision: '3',
    startSeconds: 0,
    endSeconds: 1,
    metrics: ['humanPowerW', 'cadenceRpm'],
  });
  expect(result.status).toBe('ok');
  if (result.status !== 'ok') throw new Error('Unexpected retry');
  expect(result.statistics.humanPowerW).toMatchObject({ count: 2, coveredSeconds: 0, integral: 0, sampleMean: 100 });
  expect(result.statistics.cadenceRpm).toMatchObject({ count: 2, coveredSeconds: 0, integral: 0, sampleMean: 80 });
});

it('uses C15 half-even admission and clipped contributions in the browser monitor', async () => {
  const monitor = new TelemetryMonitor('contract', false, [
    { ...syntheticSample(0, 0), humanPowerW: 0.1875 },
    { ...syntheticSample(1, 1), humanPowerW: 0.1875 },
  ]);
  const result = await monitor.rangeStats({
    generation: 1,
    expectedRevision: '2',
    startSeconds: 0,
    endSeconds: 1,
    metrics: ['humanPowerW'],
  });
  if (result.status !== 'ok') throw new Error('Unexpected retry');
  expect(result.statistics.humanPowerW).toMatchObject({ sampleMean: 0.188, integral: 0.188 });
});

it('preserves signed-zero originals in browser statistics extrema', async () => {
  const monitor = new TelemetryMonitor('signed-zero', false, [
    { ...syntheticSample(0, 0), humanPowerW: -0 },
    { ...syntheticSample(1, 1), humanPowerW: 0 },
  ]);
  const result = await monitor.rangeStats({
    generation: 1,
    expectedRevision: '2',
    startSeconds: 0,
    endSeconds: 1,
    metrics: ['humanPowerW'],
  });
  if (result.status !== 'ok') throw new Error('Unexpected retry');
  expect(Object.is(result.statistics.humanPowerW?.min?.value, -0)).toBe(true);
  expect(Object.is(result.statistics.humanPowerW?.max?.value, -0)).toBe(true);
});

it('skips GPS crossing neighbors for an inverted scan', async () => {
  const store = new BrowserRideStore();
  const neighbor = vi.spyOn(store, 'locationNeighbor').mockResolvedValue(undefined);
  const page = vi.spyOn(store, 'locationPage').mockResolvedValue([]);
  const receive = vi.fn();
  await scanBrowserLocations(browserRide(), store, 2, 1, receive);
  expect(neighbor).not.toHaveBeenCalled();
  expect(page).not.toHaveBeenCalled();
  expect(receive).not.toHaveBeenCalled();
});
