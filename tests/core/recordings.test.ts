import { describe, expect, it } from 'vitest';
import { exportZip } from '../../src/core/export/zip-export';
import { summarizeRecording, writeRecordingCsv, type ImportedSample } from '../../src/core/recordings';
import { ride as android } from '../fixtures/export/zip/gps-android/ride';
import {
  MemorySink,
  MemorySource,
  entryText,
  expectedEntries,
  readZip,
  type CanonicalRide,
} from '../fixtures/export/zip/harness';
import { ride as iphone } from '../fixtures/export/zip/gps-iphone/ride';
import { ride as health } from '../fixtures/export/zip/health-descriptor/ride';
import { ride as telemetry } from '../fixtures/export/zip/telemetry-lifecycle/ride';
import { parseCsv } from '../support/csv';
import { syntheticCsvSample } from './csv-fixture';

async function reexported(samples: readonly ImportedSample[]): Promise<string> {
  const chunks: Uint8Array[] = [];
  await writeRecordingCsv(samples, { write: async bytes => void chunks.push(bytes.slice()) });
  return Buffer.concat(chunks).toString('utf8');
}

async function zipTelemetry(ride: CanonicalRide): Promise<string> {
  const sink = new MemorySink();
  await exportZip(new MemorySource(ride, 4096), async () => sink, { rideId: ride.rideId, context: ride.context });
  return entryText(readZip(sink.file).find(entry => entry.name === 'PowerLog-original/telemetry.csv')!);
}

const row = (patch: Partial<ImportedSample>, index = 0): ImportedSample => ({
  ...syntheticCsvSample(index),
  ...patch,
});

describe('telemetry.csv round trip', () => {
  it.each([
    ['telemetry-lifecycle', telemetry],
    ['gps-iphone', iphone],
    ['gps-android', android],
    ['health-descriptor', health],
  ] as const)('re-exports the %s telemetry.csv byte for byte', async (family, ride) => {
    const text = await zipTelemetry(ride);
    expect(text).toBe(expectedEntries(family)['telemetry.csv']);
    expect(await reexported(parseCsv(text).samples)).toBe(text);
  });

  it('keeps pauses, runs, connections, extension columns and normalized speed as exported', async () => {
    const text = expectedEntries('telemetry-lifecycle')['telemetry.csv'];
    const samples = parseCsv(text).samples;
    expect(samples.some(sample => sample.activeInterval === null)).toBe(true);
    expect(new Set(samples.map(sample => sample.run)).size).toBeGreaterThan(2);
    expect(new Set(samples.map(sample => sample.connection)).size).toBeGreaterThan(1);
    expect(samples.some(sample => Number.isFinite(sample.controllerSpeedMps))).toBe(true);
    expect(samples.every(sample => sample.interruptionIndex === sample.run)).toBe(true);
    const twice = parseCsv(await reexported(samples)).samples;
    expect(twice).toEqual(samples);
  });

  it('keeps missing values missing', async () => {
    const samples = [row({ cadenceRpm: NaN, controllerSpeedMps: NaN, connection: null, activeInterval: null })];
    const text = await reexported(samples);
    expect(text.split('\n')[1]).toBe(
      '2026-01-01T00:00:00.000Z,0,,1,,200,,504,50.4,10,15.25,1200,24.5,30.25,40.75,2.1,105.84,1.2,0,2,,0,36',
    );
    expect(parseCsv(text).samples).toEqual(samples);
  });
});

describe('imported ride summaries', () => {
  it('integrates rider and battery power within one run of one activity interval', () => {
    const samples = [
      row({ elapsedSeconds: 0, humanPowerW: 100, cadenceRpm: 60, motorInputPowerW: 100 }),
      row({ elapsedSeconds: 1, humanPowerW: 200, cadenceRpm: 80, motorInputPowerW: 200 }, 1),
      row({ elapsedSeconds: 3, humanPowerW: 400, cadenceRpm: 100, motorInputPowerW: 300 }, 2),
    ];
    const summary = summarizeRecording(samples);
    expect(summary.averageHumanPowerW).toBe(250);
    expect(summary.humanEnergyWh).toBeCloseTo(750 / 3600);
    expect(summary.motorInputEnergyWh).toBeCloseTo(650 / 3600);
    expect(summary.averageCadenceRpm).toBeCloseTo(250 / 3);
    expect(summary.peakHumanPowerW).toBe(400);
  });

  it('leaves paused rows out of energy, averages and peaks without counting the pause as a gap', () => {
    const samples = [
      row({ elapsedSeconds: 0, humanPowerW: 100 }),
      row({ elapsedSeconds: 1, humanPowerW: 100 }, 1),
      row({ elapsedSeconds: 1.5, humanPowerW: 900, activeInterval: null, run: 2, interruptionIndex: 2 }, 2),
      row({ elapsedSeconds: 2, humanPowerW: 900, activeInterval: null, run: 2, interruptionIndex: 2 }, 3),
      row({ elapsedSeconds: 3, humanPowerW: 100, activeInterval: 2, run: 3, interruptionIndex: 3 }, 4),
      row({ elapsedSeconds: 4, humanPowerW: 100, activeInterval: 2, run: 3, interruptionIndex: 3 }, 5),
    ];
    expect(summarizeRecording(samples)).toMatchObject({
      durationSeconds: 4,
      coveredSeconds: 2,
      gapCount: 0,
      averageHumanPowerW: 100,
      peakHumanPowerW: 100,
    });
  });

  it.each([{ run: 2, interruptionIndex: 2 }, { elapsedSeconds: 4 }])(
    'counts a break inside an activity interval as a gap: %j',
    patch => {
      const samples = [row({ elapsedSeconds: 0 }), row({ elapsedSeconds: 1, ...patch }, 1)];
      expect(summarizeRecording(samples)).toMatchObject({ coveredSeconds: 0, gapCount: 1, humanEnergyWh: 0 });
    },
  );

  it('skips a missing value for its own channel only', () => {
    const samples = [
      row({ elapsedSeconds: 0, humanPowerW: 100, cadenceRpm: NaN }),
      row({ elapsedSeconds: 1, humanPowerW: 100, cadenceRpm: 90 }, 1),
      row({ elapsedSeconds: 2, humanPowerW: NaN, cadenceRpm: 90 }, 2),
    ];
    expect(summarizeRecording(samples)).toMatchObject({
      coveredSeconds: 2,
      averageHumanPowerW: 100,
      averageCadenceRpm: 90,
      peakHumanPowerW: 100,
    });
  });

  it('reports wall-clock corrections and treats equal elapsed times as neither coverage nor a gap', () => {
    const samples = [
      row({ elapsedSeconds: 0 }),
      row({ elapsedSeconds: 0 }, 1),
      row({ elapsedSeconds: 0.5, timestamp: '2025-12-31T23:59:00Z' }, 2),
    ];
    expect(summarizeRecording(samples)).toMatchObject({ coveredSeconds: 0.5, gapCount: 0, clockDiscontinuities: 1 });
  });

  it('summarizes header-only and single-row recordings without inventing energy', () => {
    expect(summarizeRecording([])).toMatchObject({ sampleCount: 0, averageHumanPowerW: null, peakHumanPowerW: null });
    expect(summarizeRecording([row({ humanPowerW: 100 })])).toMatchObject({
      averageHumanPowerW: null,
      peakHumanPowerW: 100,
      humanEnergyWh: 0,
      coveredSeconds: 0,
    });
  });
});
