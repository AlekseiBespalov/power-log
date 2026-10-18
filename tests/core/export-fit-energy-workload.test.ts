import { expect, it } from 'vitest';
import { exportFit } from '../../src/core/export/fit-export';
import { projectionColumns } from '../../src/core/export/pages';
import type { ExportPage, ExportPageRequest, ExportSource, ProjectionName } from '../../src/core/export/types';
import { decodeFit, fitPython, readFit, MemorySource, MemorySink } from '../fixtures/export/fit/ride';

const seconds = 6000;
const calories = seconds / 100;

function energySource(stage: 'active' | 'basal'): ExportSource {
  const origins = stage === 'active' ? ['app.generated.a', 'app.generated.b'] : ['app.generated.a'];
  const count = seconds * origins.length;
  const source = new MemorySource({
    platform: 'ios',
    watchEnabled: true,
    end: seconds,
    telemetry: [{ t: 0, humanPowerW: 100 }],
    producers: { gps: [], health: ['watch'] },
  });
  return {
    open: request => source.open(request),
    close: () => source.close(),
    async page<P extends ProjectionName>(request: ExportPageRequest<P>): Promise<ExportPage<P>> {
      if (request.projection !== 'healthFit') return source.page(request);
      const first = request.after === null ? 0 : request.after[1]! + 1;
      const rows = Math.min(4096, count - first);
      const columns: Record<string, Float64Array | (string | null)[]> = {};
      for (const column of projectionColumns('healthFit', 'ios', 'fit'))
        columns[column.name] =
          column.type === 'number' ? new Float64Array(rows).fill(NaN) : Array<string | null>(rows).fill(null);
      const number = (name: string) => columns[name] as Float64Array;
      const text = (name: string) => columns[name] as (string | null)[];
      number('activeEnergyKcal').fill(0.01);
      if (stage === 'basal') number('basalEnergyKcal').fill(0.01);
      text('producer').fill('watch');
      text('representation').fill('intervalRecord');
      text('provider').fill('appleHealth');
      text('observationRevision').fill('1');
      for (let i = 0; i < rows; i++) {
        const index = first + i;
        const second = Math.floor(index / origins.length);
        number('elapsedSeconds')[i] = second + 1;
        number('producerSequence')[i] = index + 1;
        text('dataOrigin')[i] = origins[index % origins.length]!;
        text('startUs')[i] = String(second * 1_000_000);
        text('endUs')[i] = String((second + 1) * 1_000_000);
      }
      return {
        rows,
        columns,
        last: rows ? [Math.floor((first + rows - 1) / origins.length) + 1, first + rows - 1] : null,
        done: first + rows === count,
      } as ExportPage<P>;
    },
  };
}

it.each(['active', 'basal'] as const)(
  'completes a 6000-second %s energy workload per stage through production FIT',
  async stage => {
    const sink = new MemorySink();
    const result = await exportFit(energySource(stage), async () => sink, {
      rideId: 'generated-energy-workload',
      context: { platform: 'ios', exportedAt: '2026-01-01T08:00:01.000Z' },
    });
    expect(result.records).toBeGreaterThan(0);
    const session = readFit(sink.bytes).find(message => message.global === 18)!.fields;
    const decoded = decodeFit(fitPython()!, sink.bytes).messages.find(message => message.mesg_num === 18)!.fields;
    expect(result.health.calories).toBe(calories);
    expect(session.get(11)).toBe(calories);
    expect(decoded.total_calories).toBe(calories);
    if (stage === 'basal')
      expect(result.health.basal).toMatchObject({
        valueKcal: calories,
        reason: null,
        selectedOrigin: null,
      });
  },
  30_000,
);
