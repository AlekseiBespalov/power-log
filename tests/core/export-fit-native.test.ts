import { describe, expect, it } from 'vitest';
import { exportFit } from '../../src/core/export/fit-export';
import { fromNativePage } from '../../src/core/export/pages';
import type {
  ExportOpenResult,
  ExportPage,
  ExportPageRequest,
  ExportSource,
  NativeExportPage,
  ProjectionName,
} from '../../src/core/export/types';
import { readNativeFixture } from '../fixtures/export/native';
import { decodeFit, fitPython, MemorySink, readFit } from '../fixtures/export/fit/ride';

type CapturedPage = Omit<NativeExportPage, 'columns'> & {
  columns: Record<string, { base64: string } | (string | null)[]>;
};
type Capture = {
  open: ExportOpenResult;
  pages: Record<ProjectionName, CapturedPage | CapturedPage[]>;
  history?: { activeEnergyKcal?: number; basalEnergyKcal?: number };
};
function captured(path: string, name: string): ExportSource {
  const fixture = (readNativeFixture(path) as Record<string, Capture>)[name]!;
  return {
    async open() {
      return fixture.open;
    },
    async page<P extends ProjectionName>(request: ExportPageRequest<P>): Promise<ExportPage<P>> {
      const pages = fixture.pages[request.projection];
      const index =
        request.after === null || !Array.isArray(pages)
          ? 0
          : pages.findIndex(page => JSON.stringify(page.last) === JSON.stringify(request.after)) + 1;
      const page = (Array.isArray(pages) ? pages[index]! : pages) as CapturedPage;
      const columns = Object.fromEntries(
        Object.entries(page.columns).map(([name, values]) => [
          name,
          Array.isArray(values) ? values : Uint8Array.from(Buffer.from(values.base64, 'base64')),
        ]),
      );
      return fromNativePage({ ...page, columns } as NativeExportPage<P>, request.projection);
    },
    async close() {},
  };
}

describe.each(['ios', 'android'] as const)('FIT from %s production projection pages', platform => {
  it.each(['stop', 'resume'])('exports the retained interruption followed by %s', async name => {
    const sink = new MemorySink();
    const result = await exportFit(
      captured(`tests/fixtures/contract/export/interruption-${platform}.json`, name),
      async () => sink,
      { rideId: 'generated-interruption', context: { platform, exportedAt: '2026-01-01T00:00:30.000Z' } },
    );
    const session = readFit(sink.bytes).find(message => message.global === 18)!.fields;
    expect(result.records).toBeGreaterThan(0);
    expect(session.get(48)).toBe(name === 'resume' ? 400 : 100);
    expect(session.get(8)).toBe(name === 'resume' ? 3000 : 1000);
    const decoded = decodeFit(fitPython()!, sink.bytes);
    expect(decoded.messages.find(message => message.mesg_num === 18)!.fields.total_work).toBe(
      name === 'resume' ? 400 : 100,
    );
  });
});

describe('Apple resting energy', () => {
  it.each(['observed', 'unavailable'])('reports %s directly supplied Apple resting energy', async name => {
    const sink = new MemorySink();
    const result = await exportFit(captured('tests/fixtures/contract/export/basal-ios.json', name), async () => sink, {
      rideId: 'generated-basal',
      context: { platform: 'ios', exportedAt: '2026-01-01T00:00:30.000Z' },
    });
    expect(result.health).toMatchObject({
      calories: 100,
      basal: {
        valueKcal: name === 'observed' ? 100 : null,
        reason: name === 'observed' ? null : 'unavailable',
        selectedOrigin: null,
      },
    });
    expect(
      readFit(sink.bytes)
        .find(message => message.global === 18)!
        .fields.get(11),
    ).toBe(100);
    expect(
      decodeFit(fitPython()!, sink.bytes).messages.find(message => message.mesg_num === 18)!.fields.total_calories,
    ).toBe(100);
  });
});

it.each([
  ['integer-boundary', 7, 513],
  ['overlapping-energy', 11, 110],
  ['hr-membership', 16, 100],
  ['hr-origin', 16, 100],
  ['energy-qualification', 11, 7],
  ['utc-bounds', 11, 100],
] as const)('replays %s through the iOS production projection', async (name, field, expected) => {
  const sink = new MemorySink();
  const result = await exportFit(captured('tests/fixtures/contract/export/review-ios.json', name), async () => sink, {
    rideId: 'generated-review',
    context: { platform: 'ios', exportedAt: '2026-01-01T00:00:30.000Z' },
  });
  const messages = readFit(sink.bytes);
  const session = messages.find(message => message.global === 18)!.fields;
  expect(session.get(field)).toBe(expected);
  if (name === 'integer-boundary') {
    expect(session.get(8)).toBe(expected);
    expect(session.get(21)).toBe(200);
    const lap = messages.find(message => message.global === 19)!.fields;
    expect(lap.get(7)).toBe(expected);
    expect(lap.get(8)).toBe(expected);
  }
  if (name === 'overlapping-energy') expect(result.health.basal.valueKcal).toBeNull();
  if (name === 'hr-origin') {
    expect(
      messages
        .filter(message => message.global === 20)
        .map(message => message.fields.get(3))
        .filter(Boolean),
    ).toEqual([100, 100]);
  }
  const decoded = decodeFit(fitPython()!, sink.bytes);
  expect(decoded.messages.some(message => message.mesg_num === 18)).toBe(true);
});

it('retains integer boundaries through the Android production projection', async () => {
  const sink = new MemorySink();
  await exportFit(
    captured('tests/fixtures/contract/export/review-android.json', 'integer-boundary'),
    async () => sink,
    { rideId: 'generated-review', context: { platform: 'android', exportedAt: '2026-01-01T00:00:30.000Z' } },
  );
  const session = readFit(sink.bytes).find(message => message.global === 18)!.fields;
  expect(session.get(7)).toBe(513);
  expect(session.get(8)).toBe(513);
  expect(session.get(21)).toBe(200);
  const decoded = decodeFit(fitPython()!, sink.bytes).messages.find(message => message.mesg_num === 18)!.fields;
  expect(decoded.max_power).toBe(200);
  expect(decoded.total_elapsed_time).toBe(decoded.total_timer_time);
});

it.each(['hr-barrier', 'hr-origin-precedence'])('retains %s through the iOS production projection', async name => {
  const sink = new MemorySink();
  const result = await exportFit(captured('tests/fixtures/contract/export/review-ios.json', name), async () => sink, {
    rideId: 'generated-review',
    context: { platform: 'ios', exportedAt: '2026-01-01T00:00:30.000Z' },
  });
  const session = readFit(sink.bytes).find(message => message.global === 18)!.fields;
  expect(session.get(17)).toBe(100);
  if (name === 'hr-barrier') expect(session.has(16)).toBe(false);
  else {
    expect(session.get(16)).toBe(100);
    expect(result.health.heartRate).toEqual({ source: 'phone', kind: 'latest', dataOrigin: 'app.a' });
  }
  expect(decodeFit(fitPython()!, sink.bytes).messages.some(message => message.mesg_num === 18)).toBe(true);
});

describe.each(['ios', 'android'] as const)('N5 through %s production projections', platform => {
  it.each([
    ['energy-n5', 5],
    ['energy-spanning', 10],
  ] as const)('clips %s using the interval end collection time', async (name, expected) => {
    const sink = new MemorySink();
    const path = platform === 'ios' ? 'review-ios' : 'energy-android';
    const result = await exportFit(captured(`tests/fixtures/contract/export/${path}.json`, name), async () => sink, {
      rideId: 'generated-energy',
      context: { platform, exportedAt: '2026-01-01T00:00:30.000Z' },
    });
    expect(result.health.basal.valueKcal).toBeNull();
    expect(result.health.calories).toBe(expected);
    expect(
      readFit(sink.bytes)
        .find(message => message.global === 18)!
        .fields.get(11),
    ).toBe(expected);
    expect(
      decodeFit(fitPython()!, sink.bytes).messages.find(message => message.mesg_num === 18)!.fields.total_calories,
    ).toBe(expected);
  });
});

it.each(['energy-n5', 'energy-spanning', 'overlapping-energy', 'energy-after-bound'])(
  'keeps Android History and FIT energy equal for %s',
  async name => {
    const path = 'tests/fixtures/contract/export/energy-android.json';
    const fixture = (readNativeFixture(path) as Record<string, Capture>)[name]!;
    expect(fixture.history).toBeDefined();
    const sink = new MemorySink();
    const result = await exportFit(captured(path, name), async () => sink, {
      rideId: 'generated-energy-parity',
      context: { platform: 'android', exportedAt: '2026-01-01T00:00:30.000Z' },
    });
    const history = fixture.history!;
    expect(Number.isNaN(result.health.calories) ? undefined : result.health.calories).toBe(history.activeEnergyKcal);
    expect(result.health.basal.valueKcal).toBeNull();
    expect(history.basalEnergyKcal).toBeUndefined();
    const session = readFit(sink.bytes).find(message => message.global === 18)!.fields;
    expect(session.get(11)).toBe(history.activeEnergyKcal);
    if (name === 'energy-after-bound') {
      expect(history.activeEnergyKcal).toBeUndefined();
      expect(history.basalEnergyKcal).toBeUndefined();
      const page = fixture.pages.healthFit as CapturedPage;
      expect(page.columns.endUs).toEqual([]);
    } else if (name === 'energy-spanning') expect(history.activeEnergyKcal).toBe(10);
  },
);

it.each(['ios', 'android'] as const)(
  'reduces cross-page Health energy through %s production projections',
  async platform => {
    const path = platform === 'ios' ? 'review-ios' : 'energy-android';
    const sink = new MemorySink();
    const result = await exportFit(
      captured(`tests/fixtures/contract/export/${path}.json`, 'overlapping-energy'),
      async () => sink,
      {
        rideId: 'generated-ordering',
        context: { platform, exportedAt: '2026-01-01T00:00:30Z' },
      },
    );
    expect(result.health.calories).toBe(110);
    expect(result.health.basal.valueKcal).toBeNull();
    expect(
      decodeFit(fitPython()!, sink.bytes).messages.find(message => message.mesg_num === 18)!.fields.total_calories,
    ).toBe(110);
  },
);
