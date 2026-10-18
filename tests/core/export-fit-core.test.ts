import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { HealthDiscovery } from '../../src/core/export/fit/health';
import { ExportError, type ExportDistanceProfile } from '../../src/core/export/types';
import type { ProjectionPage } from '../../src/core/export/pages';
import { FIT_EPOCH_SECONDS } from '../../src/core/export/clock';
import { decodeFit, fitFailure, fitPython, readFit, records, runFit, MemorySource } from '../fixtures/export/fit/ride';
import {
  MemorySource as ZipSource,
  MemorySink,
  entryText,
  readZip,
  type CanonicalRide,
} from '../fixtures/export/zip/harness';
import { exportZip } from '../../src/core/export/zip-export';

const f29 = JSON.parse(readFileSync('tests/fixtures/contract/export/f29.json', 'utf8')) as {
  startedAt: string;
  elapsedSeconds: number;
  originalTimestamp: string;
  anchorMs: number;
  sampleHz: 2 | 4 | 8;
  recordTimestamp: string;
};
const session = (bytes: Uint8Array) => readFit(bytes).find(message => message.global === 18)!.fields;

describe('FIT core contracts', () => {
  it.each(['ios', 'android', 'web'] as const)('loads F29 through the %s production exporter', async platform => {
    const { bytes, source } = await runFit({
      platform,
      startedAt: f29.startedAt,
      sampleHz: f29.sampleHz,
      end: 1,
      gps: [
        { t: f29.elapsedSeconds, timestamp: f29.originalTimestamp, latitude: 0, longitude: 0, horizontalAccuracyM: 0 },
      ],
    });
    expect(records(readFit(bytes))[0]!.fields.get(253)).toBe(
      Date.parse(f29.recordTimestamp) / 1000 - FIT_EPOCH_SECONDS,
    );
    expect(decodeFit(fitPython()!, bytes).messages.find(message => message.mesg_num === 20)!.fields.timestamp).toBe(
      Date.parse(f29.recordTimestamp) / 1000 - FIT_EPOCH_SECONDS,
    );
    expect(source.requests).not.toContain('gpsDiscovery');
    const { session: _session, ...opened } = await new MemorySource({
      platform,
      startedAt: f29.startedAt,
      sampleHz: f29.sampleHz,
      end: 1,
    }).open({
      kind: 'fit',
      rideId: 'f29',
      context: { platform, exportedAt: f29.recordTimestamp },
    });
    const original: CanonicalRide = {
      rideId: 'f29',
      context: { platform, exportedAt: f29.recordTimestamp },
      open: { ...opened, producers: { ...opened.producers, gps: ['phone'] } },
      rows: {
        gps: [
          {
            elapsedSeconds: f29.elapsedSeconds,
            timestamp: f29.originalTimestamp,
            producer: 'phone',
            producerSequence: 1,
            active: 1,
            interval: 0,
            latitude: 0,
            longitude: 0,
            horizontalAccuracyM: 0,
          },
        ].map(({ producerSequence, active, interval, ...row }) =>
          platform === 'ios'
            ? { ...row, producerSequence }
            : platform === 'web'
              ? { ...row, producerSequence, active, interval }
              : { ...row, active },
        ),
      },
    };
    const sink = new MemorySink();
    await exportZip(new ZipSource(original), async () => sink, { rideId: 'f29', context: original.context });
    const entries = readZip(sink.file);
    expect(entryText(entries.find(entry => entry.name.endsWith('/gps.csv'))!)).toContain(f29.originalTimestamp);
    expect(
      JSON.parse(entryText(entries.find(entry => entry.name.endsWith('/datapackage.json'))!))['powerLog:ride'].sampleHz,
    ).toBe(f29.sampleHz);
  });

  it('rejects a Health distance profile', async () => {
    const profile = { source: 'health:phone', kind: 'health', producer: 'phone' } as unknown as ExportDistanceProfile;
    const failure = await fitFailure({ end: 1, profile });
    expect(failure.code).toBe('page');
    expect(failure.message).toContain('distance settings');
  });

  it('supports the normalized inclusive 2.5-second power edge', async () => {
    const { bytes } = await runFit({
      end: 3,
      telemetry: [0.1, 2.6000000000000005].map(t => ({ t, humanPowerW: 100 })),
    });
    expect(session(bytes).get(48)).toBe(250);
  });

  it.each(['ios', 'android', 'web'] as const)('excludes the ordinary pause endpoint on %s', async platform => {
    const { bytes } = await runFit({
      platform,
      end: 3,
      lifecycle: [{ t: 2, action: 'pause' }],
      telemetry: [
        { t: 1, humanPowerW: 100 },
        { t: 2, humanPowerW: 200 },
      ],
    });
    expect(session(bytes).get(48)).toBeUndefined();
    expect(session(bytes).get(21)).toBe(100);
  });

  it('encodes stored MSL with zero vertical accuracy and accumulates ascent', async () => {
    const { bytes } = await runFit({
      end: 1,
      gps: [0, 1].map(t => ({
        t,
        latitude: 0,
        longitude: t * 0.00001,
        horizontalAccuracyM: 0,
        altitudeMeters: 100 + 4 * t,
        verticalAccuracyM: 0,
      })),
    });
    expect(records(readFit(bytes)).map(message => message.fields.get(78))).toEqual([3000, 3020]);
    expect(session(bytes).get(22)).toBe(4);
  });

  it('breaks selected HR integration at a missing reading', async () => {
    const { bytes } = await runFit({
      end: 3,
      health: [
        { t: 0, heartRateBpm: 100, representation: 'rawSeries' },
        { t: 1, representation: 'rawSeries' },
        { t: 2, heartRateBpm: 100, representation: 'rawSeries' },
      ],
    });
    expect(session(bytes).get(16)).toBeUndefined();
  });

  it('refuses an interruption without the retained telemetry cutoff', async () => {
    await expect(
      runFit({
        platform: 'android',
        end: 3,
        lifecycle: [{ t: 2, action: 'pause', interrupted: true }],
        telemetry: [{ t: 1, humanPowerW: 100 }],
      }),
    ).rejects.toThrow(ExportError);
  });

  it('sorts active and Apple resting energy across production pages', async () => {
    const { result } = await runFit({
      end: 10,
      telemetry: [{ t: 0, humanPowerW: 100 }],
      pageSize: 1,
      health: [
        {
          t: 4,
          producer: 'phone',
          representation: 'intervalRecord',
          dataOrigin: 'app.generated',
          startUs: '2000000',
          endUs: '4000000',
          activeEnergyKcal: 10,
          basalEnergyKcal: 10,
        },
        {
          t: 10,
          producer: 'phone',
          representation: 'intervalRecord',
          dataOrigin: 'app.generated',
          startUs: '0',
          endUs: '10000000',
          activeEnergyKcal: 100,
          basalEnergyKcal: 100,
        },
      ],
    });
    expect(result.health.calories).toBe(110);
    expect(result.health.basal.valueKcal).toBe(110);
  });

  it('bounds retained interval operands across the whole Health stream', async () => {
    await expect(
      runFit({
        end: 10,
        telemetry: [{ t: 0, humanPowerW: 100 }],
        health: Array.from({ length: 65537 }, (_, i) => ({
          t: i,
          producer: 'phone',
          representation: 'intervalRecord',
          startUs: '0',
          endUs: '10000000',
          activeEnergyKcal: 1,
        })),
      }),
    ).rejects.toMatchObject({ code: 'limit', message: expect.stringContaining('65,536') });
  });

  it('keeps the owner selected when its only cumulative energy is after Stop', () => {
    const discovery = new HealthDiscovery('watch', 10);
    const page = {
      rows: 2,
      connection: null,
      columns: {
        elapsedSeconds: Float64Array.of(1, 11),
        producer: ['phone', 'watch'],
        representation: [null, null],
        activeEnergyKcal: Float64Array.of(7, 80),
      },
    } as ProjectionPage<'healthFit'>;
    discovery.add(page);
    discovery.prepareConsumption();
    discovery.addConsumption(page);
    expect(discovery.plan().calories).toBeNaN();
  });
  it.each(['cumulative', 'interval', 'final'] as const)(
    'preserves Apple %s resting energy precedence in the production exporter',
    async representation => {
      const health = [
        {
          t: 0,
          producer: 'phone' as const,
          representation: 'intervalRecord',
          startUs: '0',
          endUs: '10000000',
          activeEnergyKcal: 100,
          dataOrigin: 'app.a',
        },
        {
          t: 2,
          producer: 'phone' as const,
          representation: 'cumulativeWorkoutTotal',
          basalEnergyKcal: 70,
          dataOrigin: 'app.a',
        },
        ...(representation === 'cumulative'
          ? []
          : [
              {
                t: 0,
                producer: 'phone' as const,
                representation: 'intervalRecord',
                startUs: '0',
                endUs: '10000000',
                basalEnergyKcal: 10,
                dataOrigin: 'app.a',
              },
            ]),
        ...(representation === 'final'
          ? [
              {
                t: 11,
                producer: 'phone' as const,
                representation: 'finalWorkoutTotal',
                basalEnergyKcal: 0,
                dataOrigin: 'app.a',
              },
            ]
          : []),
      ];
      const { result, bytes } = await runFit({ end: 10, health, telemetry: [{ t: 0, humanPowerW: 100 }] });
      expect(result.health.basal).toMatchObject({
        valueKcal: representation === 'cumulative' ? 70 : representation === 'interval' ? 10 : 0,
        reason: null,
        selectedOrigin: null,
      });
      expect(session(bytes).get(11)).toBe(100);
    },
  );

  it('clips Apple active and resting energy intervals to the ride window', async () => {
    const { result } = await runFit({
      end: 10,
      telemetry: [{ t: 0, humanPowerW: 100 }],
      health: [
        {
          t: 0,
          producer: 'phone',
          representation: 'intervalRecord',
          startUs: '-5000000',
          endUs: '15000000',
          activeEnergyKcal: 200,
          dataOrigin: 'app.a',
        },
        {
          t: 0,
          producer: 'phone',
          representation: 'intervalRecord',
          startUs: '-5000000',
          endUs: '15000000',
          basalEnergyKcal: 40,
          dataOrigin: 'app.a',
        },
      ],
    });
    expect(result.health).toMatchObject({ calories: 100, basal: { valueKcal: 20, reason: null } });
  });
});
