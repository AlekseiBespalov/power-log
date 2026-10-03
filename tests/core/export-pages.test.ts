import { afterEach, describe, expect, it, vi } from 'vitest';
import { EXPORT_PAGE_MAX_BYTES, EXPORT_PAGE_MAX_ROWS } from '../../src/core/export/catalog';
import {
  ExportReads,
  checkPage,
  createSlicer,
  fromNativePage,
  projectionColumns,
  readProjection,
  type ExportSlicer,
} from '../../src/core/export/pages';
import {
  ExportError,
  type ExportConnection,
  type ExportCursor,
  type ExportErrorCode,
  type ExportKind,
  type ExportPage,
  type ExportPageRequest,
  type ExportPlatform,
  type ExportSource,
  type NativeExportPage,
  type ProjectionName,
} from '../../src/core/export/types';

type Values = Record<string, number[] | (string | null)[]>;

const idle: ExportSlicer = { tick: () => Promise.resolve() };
const binary = (values: number[]) => new Uint8Array(Float64Array.from(values).buffer);

function code(run: () => unknown): ExportErrorCode | undefined {
  try {
    run();
  } catch (error) {
    if (error instanceof ExportError) return error.code;
    throw error;
  }
  return undefined;
}

async function rejection(run: () => Promise<unknown>): Promise<ExportErrorCode | undefined> {
  try {
    await run();
  } catch (error) {
    if (error instanceof ExportError) return error.code;
    throw error;
  }
  return undefined;
}

function columnsFor(
  projection: ProjectionName,
  platform: ExportPlatform,
  kind: ExportKind,
  rows: number,
  values: Values,
) {
  const columns: Record<string, Float64Array | (string | null)[]> = {};
  for (const column of projectionColumns(projection, platform, kind)) {
    const given = values[column.name];
    if (column.type === 'number')
      columns[column.name] = Float64Array.from(
        (given as number[] | undefined) ?? Array.from({ length: rows }, (_, i) => i),
      );
    else columns[column.name] = (given as (string | null)[] | undefined) ?? Array.from({ length: rows }, () => null);
  }
  return columns;
}

function page<P extends ProjectionName>(
  projection: P,
  rows: number,
  last: ExportCursor | null,
  done: boolean,
  values: Values = {},
  options: { platform?: ExportPlatform; kind?: ExportKind; connections?: ExportConnection[] } = {},
): ExportPage<P> {
  return {
    rows,
    last,
    done,
    ...(options.connections ? { connections: options.connections } : {}),
    columns: columnsFor(projection, options.platform ?? 'ios', options.kind ?? 'zip', rows, values),
  } as ExportPage<P>;
}

function source(pages: Partial<Record<ProjectionName, ExportPage[]>>) {
  const requests: ExportPageRequest[] = [];
  const passes: Partial<Record<ProjectionName, number>> = {};
  const value: ExportSource = {
    open: () => Promise.reject(new Error('unused')),
    close: () => Promise.resolve(),
    page: async <P extends ProjectionName>(request: ExportPageRequest<P>) => {
      requests.push(request);
      const list = pages[request.projection]!;
      if (request.after === null) passes[request.projection] = 0;
      const index = passes[request.projection]!;
      passes[request.projection] = index + 1;
      return list[index] as ExportPage<P>;
    },
  };
  return { value, requests };
}

const reads = (platform: ExportPlatform = 'ios', kind: ExportKind = 'zip', signal?: AbortSignal, slicer = idle) =>
  new ExportReads({ platform, kind, slicer, signal });

async function collect<P extends ProjectionName>(
  from: ExportSource,
  projection: P,
  context: ExportReads,
): Promise<{ rows: number; connection: number[] | null }[]> {
  const pages: { rows: number; connection: number[] | null }[] = [];
  for await (const item of readProjection(from, 'session', projection, context))
    pages.push({ rows: item.rows, connection: item.connection ? Array.from(item.connection) : null });
  return pages;
}

const identity = (token: string, model: string | null = 'X12'): ExportConnection => ({
  token,
  vendor: 'cyc',
  model,
  firmware: '20250604',
  protocol: '5.3',
});

describe('native page conversion', () => {
  const native = (rows: number, columns: Record<string, unknown>, envelope: Partial<NativeExportPage> = {}) =>
    ({
      rows,
      last: rows ? [rows] : null,
      done: rows === 0,
      columns,
      ...envelope,
    }) as unknown as NativeExportPage<'telemetry'>;

  it('views every binary64 column in place and keeps text columns', () => {
    const elapsed = binary([0, 0.125, 0.25]);
    const power = binary([100, NaN, -0]);
    const connection = ['a', 'a', null];
    const converted = fromNativePage(
      native(3, { elapsedSeconds: elapsed, humanPowerW: power, connection }),
      'telemetry',
    );
    expect(converted.columns.elapsedSeconds).toBeInstanceOf(Float64Array);
    expect(converted.columns.elapsedSeconds!.buffer).toBe(elapsed.buffer);
    expect(converted.columns.humanPowerW!.buffer).toBe(power.buffer);
    expect(Array.from(converted.columns.elapsedSeconds!)).toEqual([0, 0.125, 0.25]);
    expect(Number.isNaN(converted.columns.humanPowerW![1])).toBe(true);
    expect(converted.columns.connection).toBe(connection);
    expect({ rows: converted.rows, last: converted.last, done: converted.done }).toEqual({
      rows: 3,
      last: [3],
      done: false,
    });
  });

  it('accepts an empty final page', () => {
    const converted = fromNativePage(native(0, { elapsedSeconds: new Uint8Array(0), connection: [] }), 'telemetry');
    expect(converted.columns.elapsedSeconds!.length).toBe(0);
    expect(fromNativePage(native(0, {}), 'telemetry').rows).toBe(0);
  });

  const shifted = () => {
    const bytes = new Uint8Array(32);
    bytes.set(binary([1, 2]), 8);
    return bytes.subarray(8, 24);
  };
  it.each([
    ['a numeric column sent as an array', native(2, { elapsedSeconds: [0, 1] })],
    ['a numeric column sent as Float64Array', native(2, { elapsedSeconds: Float64Array.from([0, 1]) })],
    ['a numeric column inside a larger buffer', native(2, { elapsedSeconds: shifted() })],
    ['a numeric column one byte short', native(2, { elapsedSeconds: binary([0, 1]).subarray(0, 15) })],
    ['a numeric column one value long', native(2, { elapsedSeconds: binary([0, 1, 2]) })],
    ['a text column with too few values', native(2, { connection: ['a'] })],
    ['a text column with too many values', native(2, { connection: ['a', 'b', 'c'] })],
    ['a text column with a number', native(2, { connection: ['a', 7] })],
    ['a text column sent as bytes', native(2, { connection: binary([0, 1]) })],
    ['an unknown numeric column', native(2, { elapsedSeconds: binary([0, 1]), speedKph: binary([0, 1]) })],
    ['an unknown text column', native(2, { elapsedSeconds: binary([0, 1]), nickname: ['a', 'b'] })],
    ['too many rows', native(EXPORT_PAGE_MAX_ROWS + 1, {})],
    ['a fractional row count', native(1.5, {})],
    ['a negative row count', native(-1, {}, { last: null, done: true })],
    ['an empty page before the end', native(0, {}, { done: false })],
    ['an empty page with a cursor', native(0, {}, { done: true, last: [1] })],
    ['rows without a cursor', native(2, {}, { last: null })],
    ['a cursor that is not finite', native(2, {}, { last: [1, NaN] })],
    ['an empty cursor', native(2, {}, { last: [] })],
    ['a missing completion flag', native(2, {}, { done: 'no' as unknown as boolean })],
    ['no columns', native(2, null as unknown as Record<string, unknown>)],
  ])('rejects %s', (_, value) => {
    expect(code(() => fromNativePage(value, 'telemetry'))).toBe('page');
  });

  it('measures the 4 MiB ceiling in UTF-8 bytes', () => {
    const rows = EXPORT_PAGE_MAX_ROWS;
    const elapsed = () => binary(Array.from({ length: rows }, (_, i) => i));
    const text = (value: string) => Array.from({ length: rows }, () => value);
    const exact = (EXPORT_PAGE_MAX_BYTES - rows * 8) / rows;
    const accepts = (value: string) =>
      code(() => fromNativePage(native(rows, { elapsedSeconds: elapsed(), timestamp: text(value) }), 'telemetry'));
    expect(accepts('a'.repeat(exact))).toBeUndefined();
    expect(accepts('a'.repeat(exact + 1))).toBe('page');
    expect(accepts('é'.repeat(exact / 2))).toBeUndefined();
    expect(accepts('é'.repeat(exact / 2) + 'a')).toBe('page');
    expect(accepts('€'.repeat(338) + 'aa')).toBeUndefined();
    expect(accepts('€'.repeat(339))).toBe('page');
    expect(accepts('😀'.repeat(254))).toBeUndefined();
    expect(accepts('😀'.repeat(254) + 'a'.repeat(5))).toBe('page');
    expect(accepts('\ud800'.repeat(338) + 'aa')).toBeUndefined();
    expect(accepts('\ud800'.repeat(339))).toBe('page');
  });
});

describe('page checks', () => {
  it('rejects numeric columns that are not Float64Array values of every row in a core page', () => {
    const valid = page('lifecycle', 2, [1, 0, 1, 2], false, { elapsedSeconds: [0, 1], action: ['start', 'lap'] });
    expect(code(() => checkPage('lifecycle', valid))).toBeUndefined();
    const short = { ...valid, columns: { ...valid.columns, elapsedSeconds: Float64Array.from([0]) } };
    expect(code(() => checkPage('lifecycle', short))).toBe('page');
    const bytes = {
      ...valid,
      columns: { ...valid.columns, elapsedSeconds: binary([0, 1]) },
    } as unknown as typeof valid;
    expect(code(() => checkPage('lifecycle', bytes))).toBe('page');
    for (const elapsedSeconds of [[0, 1], Uint8Array.from([0, 1]), Float32Array.from([0, 1])]) {
      const typed = { ...valid, columns: { ...valid.columns, elapsedSeconds } } as unknown as typeof valid;
      expect(code(() => checkPage('lifecycle', typed))).toBe('page');
    }
  });

  it('requires exactly the columns of the platform and export kind', () => {
    const expected = projectionColumns('telemetry', 'ios', 'fit');
    expect(expected.map(column => column.name)).toEqual([
      'elapsedSeconds',
      'producerSequence',
      'clockEpoch',
      'connection',
      'humanPowerW',
      'cadenceRpm',
      'motorInputPowerW',
      'batteryVoltageV',
      'batteryCurrentA',
      'motorCurrentA',
      'motorRpm',
      'pedalTorqueNm',
      'controllerTempC',
      'motorTempC',
      'consumedAh',
      'consumedWh',
      'assistLevel',
    ]);
    const full = page('telemetry', 1, [0, 0, 1, 1], false, {}, { kind: 'fit' });
    expect(code(() => checkPage('telemetry', full, expected))).toBeUndefined();
    const withoutEpoch = { ...full.columns };
    delete withoutEpoch.clockEpoch;
    expect(code(() => checkPage('telemetry', { ...full, columns: withoutEpoch }, expected))).toBe('page');
    const extra = { ...full, columns: { ...full.columns, timestamp: ['2026-01-01T00:00:00.000Z'] } };
    expect(code(() => checkPage('telemetry', extra, expected))).toBe('page');
    const empty = page('telemetry', 0, null, true, {}, { kind: 'fit' });
    expect(code(() => checkPage('telemetry', empty, expected))).toBeUndefined();
    expect(
      code(() => checkPage('telemetry', { rows: 0, last: null, done: true, columns: {} }, expected)),
    ).toBeUndefined();
  });
});

describe('paged projection reads', () => {
  const lifecycle = (rows: number, last: ExportCursor | null, done: boolean) =>
    page('lifecycle', rows, last, done, {
      elapsedSeconds: Array.from({ length: rows }, (_, i) => i),
      action: Array.from({ length: rows }, () => 'lap'),
    });

  it('requests each page after the previous cursor and counts the pass', async () => {
    const pages = [lifecycle(2, [1, 1, 4, 9], false), lifecycle(1, [1, 1, 5, 2], false), lifecycle(0, null, true)];
    const { value, requests } = source({ lifecycle: pages });
    const context = reads();
    expect((await collect(value, 'lifecycle', context)).map(item => item.rows)).toEqual([2, 1, 0]);
    expect(requests.map(request => request.after)).toEqual([null, [1, 1, 4, 9], [1, 1, 5, 2]]);
    expect(requests.every(request => request.session === 'session' && request.projection === 'lifecycle')).toBe(true);
    expect(context.passes.rows('lifecycle')).toBe(3);
  });

  it.each([
    ['a regressing cursor', [3, 1], [2, 9]],
    ['a repeated cursor', [3, 1], [3, 1]],
    ['a regressing tie', [3, 2, 7], [3, 1, 9]],
    ['a cursor of another shape', [3, 1], [3, 2, 0]],
  ])('fails on %s', async (_, first, second) => {
    const { value } = source({ lifecycle: [lifecycle(1, first, false), lifecycle(1, second, true)] });
    expect(await rejection(() => collect(value, 'lifecycle', reads()))).toBe('cursor');
  });

  it('accepts a final page with rows', async () => {
    const { value } = source({ lifecycle: [lifecycle(1, [3, 1], false), lifecycle(2, [3, 2], true)] });
    expect((await collect(value, 'lifecycle', reads())).map(item => item.rows)).toEqual([1, 2]);
  });

  it('fails a repeated pass over a stream that delivers another row count', async () => {
    const telemetry = (rows: number) => [
      page(
        'telemetry',
        rows,
        [rows, rows],
        true,
        { connection: Array.from({ length: rows }, () => 'a') },
        {
          connections: [identity('a')],
        },
      ),
    ];
    const context = reads();
    await collect(source({ telemetry: telemetry(2) }).value, 'telemetry', context);
    await collect(source({ telemetry: telemetry(2) }).value, 'telemetry', context);
    expect(context.passes.rows('telemetry')).toBe(2);
    expect(await rejection(() => collect(source({ telemetry: telemetry(3) }).value, 'telemetry', context))).toBe(
      'changed',
    );
  });

  it('compares the GPS discovery pass with the GPS pass', async () => {
    const fit = reads('android', 'fit');
    const gps = (projection: 'gps' | 'gpsDiscovery', rows: number) => ({
      [projection]: [page(projection, rows, [rows, 1], true, {}, { platform: 'android', kind: 'fit' })],
    });
    await collect(source(gps('gpsDiscovery', 4)).value, 'gpsDiscovery', fit);
    await collect(source(gps('gps', 4)).value, 'gps', fit);
    expect(fit.passes.rows('gps')).toBe(4);
    const other = reads('android', 'fit');
    await collect(source(gps('gpsDiscovery', 4)).value, 'gpsDiscovery', other);
    expect(await rejection(() => collect(source(gps('gps', 5)).value, 'gps', other))).toBe('changed');
  });

  it('does not record a pass that stopped early', async () => {
    const { value } = source({ lifecycle: [lifecycle(1, [1, 1], false), lifecycle(0, null, true)] });
    const partial = reads();
    const iterator = readProjection(value, 'session', 'lifecycle', partial)[Symbol.asyncIterator]();
    expect((await iterator.next()).value).toMatchObject({ rows: 1 });
    await iterator.return(undefined);
    expect(partial.passes.rows('lifecycle')).toBeUndefined();
    const complete = reads();
    await collect(value, 'lifecycle', complete);
    expect(complete.passes.rows('lifecycle')).toBe(1);
  });

  it('interns connection tokens in order of first appearance across pages and passes', async () => {
    const telemetry = [
      page(
        'telemetry',
        3,
        [0, 2],
        false,
        { connection: ['first', 'first', 'second'] },
        {
          connections: [identity('second', 'X6'), identity('first')],
        },
      ),
      page(
        'telemetry',
        3,
        [1, 5],
        true,
        { connection: ['third', 'second', null] },
        {
          connections: [identity('third', null)],
        },
      ),
    ];
    const context = reads();
    expect((await collect(source({ telemetry }).value, 'telemetry', context)).map(item => item.connection)).toEqual([
      [1, 1, 2],
      [3, 2, 0],
    ]);
    const again = [{ ...telemetry[0]!, connections: [identity('first'), identity('second', 'X6')] }, telemetry[1]!];
    expect(
      (await collect(source({ telemetry: again }).value, 'telemetry', context)).map(item => item.connection),
    ).toEqual([
      [1, 1, 2],
      [3, 2, 0],
    ]);
    expect(context.connections.entries).toEqual([
      { connection: 1, vendor: 'cyc', model: 'X12', firmware: '20250604', protocol: '5.3' },
      { connection: 2, vendor: 'cyc', model: 'X6', firmware: '20250604', protocol: '5.3' },
      { connection: 3, vendor: 'cyc', model: null, firmware: '20250604', protocol: '5.3' },
    ]);
  });

  it('keeps distinct connections apart when their identities match', async () => {
    const telemetry = [
      page(
        'telemetry',
        2,
        [0, 2],
        true,
        { connection: ['radio-a', 'radio-b'] },
        {
          connections: [identity('radio-a'), identity('radio-b')],
        },
      ),
    ];
    const context = reads();
    expect((await collect(source({ telemetry }).value, 'telemetry', context))[0]!.connection).toEqual([1, 2]);
    expect(context.connections.entries.map(entry => entry.connection)).toEqual([1, 2]);
  });

  it.each([
    ['a token without an identity', { connection: ['first', 'unknown'] }, [identity('first')]],
    ['an identity of another vendor', { connection: ['first'] }, [{ ...identity('first'), vendor: 'bafang' }]],
    ['an identity without a token', { connection: [null] }, [{ ...identity('first'), token: 7 }]],
  ])('rejects %s', async (_, values, connections) => {
    const rows = values.connection.length;
    const telemetry = [
      page('telemetry', rows, [0, rows], true, values, { connections: connections as ExportConnection[] }),
    ];
    expect(await rejection(() => collect(source({ telemetry }).value, 'telemetry', reads()))).toBe('page');
  });

  it('checks every page against the projection of its session', async () => {
    const missing = page('lifecycle', 1, [0, 1], true, { elapsedSeconds: [0], action: ['start'] });
    delete (missing.columns as Record<string, unknown>).cycSequence;
    expect(await rejection(() => collect(source({ lifecycle: [missing] }).value, 'lifecycle', reads()))).toBe('page');
    const android = page('lifecycle', 1, [0, 1], true, { elapsedSeconds: [0], action: ['start'] });
    expect(await rejection(() => collect(source({ lifecycle: [android] }).value, 'lifecycle', reads('android')))).toBe(
      'page',
    );
    expect(await rejection(() => collect(source({}).value, 'distance', reads('ios', 'zip')))).toBe('unsupported');
    expect(await rejection(() => collect(source({}).value, 'healthZip', reads('ios', 'fit')))).toBe('unsupported');
  });

  it('stops with cancelled before, between and after page requests', async () => {
    const pages = [lifecycle(1, [1, 1], false), lifecycle(1, [2, 1], false), lifecycle(0, null, true)];
    const before = new AbortController();
    before.abort();
    const early = source({ lifecycle: pages });
    expect(await rejection(() => collect(early.value, 'lifecycle', reads('ios', 'zip', before.signal)))).toBe(
      'cancelled',
    );
    expect(early.requests).toHaveLength(0);

    const between = new AbortController();
    const middle = source({ lifecycle: pages });
    const seen: number[] = [];
    const run = async () => {
      for await (const item of readProjection(
        middle.value,
        'session',
        'lifecycle',
        reads('ios', 'zip', between.signal),
      )) {
        seen.push(item.rows);
        between.abort();
      }
    };
    expect(await rejection(run)).toBe('cancelled');
    expect(seen).toEqual([1]);
    expect(middle.requests).toHaveLength(1);

    const inflight = new AbortController();
    let release: (() => void) | undefined;
    const slow: ExportSource = {
      ...source({}).value,
      page: <P extends ProjectionName>() =>
        new Promise<ExportPage<P>>(resolve => {
          release = () => resolve(lifecycle(1, [1, 1], false) as ExportPage<P>);
        }),
    };
    let delivered = 0;
    const pending = rejection(async () => {
      for await (const item of readProjection(slow, 'session', 'lifecycle', reads('ios', 'zip', inflight.signal)))
        delivered += item.rows;
    });
    while (!release) await new Promise(resolve => setTimeout(resolve, 0));
    inflight.abort();
    release();
    expect(await pending).toBe('cancelled');
    expect(delivered).toBe(0);
  });

  it('keeps one page request in flight across projections', async () => {
    let active = 0;
    let most = 0;
    const releases: (() => void)[] = [];
    const slow: ExportSource = {
      ...source({}).value,
      page: <P extends ProjectionName>(request: ExportPageRequest<P>) => {
        active++;
        most = Math.max(most, active);
        return new Promise<ExportPage<P>>(resolve => {
          releases.push(() => {
            active--;
            resolve(
              (request.projection === 'lifecycle'
                ? lifecycle(0, null, true)
                : page('telemetry', 0, null, true)) as ExportPage<P>,
            );
          });
        });
      },
    };
    const context = reads();
    const first = readProjection(slow, 'session', 'lifecycle', context)[Symbol.asyncIterator]();
    const second = readProjection(slow, 'session', 'telemetry', context)[Symbol.asyncIterator]();
    const results = Promise.all([first.next(), second.next()]);
    for (let round = 0; round < 4; round++) {
      await new Promise(resolve => setTimeout(resolve, 0));
      releases.shift()?.();
    }
    expect((await results).map(result => result.done)).toEqual([false, false]);
    expect(most).toBe(1);
  });

  it('gives the slicer a turn after every page', async () => {
    let ticks = 0;
    const counting: ExportSlicer = {
      tick: () => {
        ticks++;
        return Promise.resolve();
      },
    };
    const pages = [lifecycle(1, [1, 1], false), lifecycle(1, [2, 1], false), lifecycle(0, null, true)];
    await collect(source({ lifecycle: pages }).value, 'lifecycle', reads('ios', 'zip', undefined, counting));
    expect(ticks).toBe(3);
  });
});

describe('deadline slicer', () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  const settled = async (promise: Promise<void>) => {
    let done = false;
    void promise.then(() => (done = true));
    for (let i = 0; i < 5; i++) await Promise.resolve();
    return done;
  };

  it('yields to a timer only after 40 ms of continuous work', async () => {
    vi.useFakeTimers();
    const slicer = createSlicer();
    vi.advanceTimersByTime(39);
    expect(await settled(slicer.tick())).toBe(true);
    expect(vi.getTimerCount()).toBe(0);
    vi.advanceTimersByTime(1);
    const pause = slicer.tick();
    expect(vi.getTimerCount()).toBe(1);
    expect(await settled(pause)).toBe(false);
    await vi.advanceTimersByTimeAsync(0);
    expect(await settled(pause)).toBe(true);
    vi.advanceTimersByTime(39);
    expect(await settled(slicer.tick())).toBe(true);
    expect(vi.getTimerCount()).toBe(0);
    vi.advanceTimersByTime(1);
    void slicer.tick();
    expect(vi.getTimerCount()).toBe(1);
  });

  it('rejects ticks after cancellation, including one waiting for its timer', async () => {
    vi.useFakeTimers();
    const controller = new AbortController();
    const slicer = createSlicer(controller.signal);
    vi.advanceTimersByTime(40);
    const pause = slicer.tick();
    const outcome = rejection(() => pause);
    controller.abort();
    await vi.advanceTimersByTimeAsync(0);
    expect(await outcome).toBe('cancelled');
    expect(await rejection(() => slicer.tick())).toBe('cancelled');
  });

  it('yields during a paged read once work passes the deadline', async () => {
    vi.useFakeTimers();
    const slicer = createSlicer();
    const pages = Array.from({ length: 3 }, (_, i) =>
      page('lifecycle', 1, [i + 1, 1], i === 2, { elapsedSeconds: [i], action: ['lap'] }),
    );
    const { value } = source({ lifecycle: pages });
    const timed: ExportSource = {
      ...value,
      page: request => {
        vi.advanceTimersByTime(25);
        return value.page(request);
      },
    };
    const iterator = readProjection(timed, 'session', 'lifecycle', reads('ios', 'zip', undefined, slicer))[
      Symbol.asyncIterator
    ]();
    expect((await iterator.next()).done).toBe(false);
    const second = iterator.next();
    for (let i = 0; i < 5; i++) await Promise.resolve();
    expect(vi.getTimerCount()).toBe(0);
    expect((await second).done).toBe(false);
    const third = iterator.next();
    for (let i = 0; i < 5; i++) await Promise.resolve();
    expect(vi.getTimerCount()).toBe(1);
    await vi.advanceTimersByTimeAsync(0);
    expect((await third).done).toBe(false);
  });
});
