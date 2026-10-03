import { describe, expect, it } from 'vitest';
import {
  ExportError,
  type ExportErrorCode,
  type ExportOpenResult,
  type ExportSink,
  type ExportSource,
} from '../../src/core/export/types';
import { exportZip } from '../../src/core/export/zip-export';
import {
  MemorySink,
  MemorySource,
  entryText,
  readZip,
  type CanonicalRide,
  type Row,
  type SinkHooks,
  type SourceHooks,
} from '../fixtures/export/zip/harness';
import { ride } from '../fixtures/export/zip/telemetry-lifecycle/ride';

interface Harness {
  readonly log: string[];
  readonly source: MemorySource;
  readonly sink: MemorySink;
  readonly closes: { count: number };
  readonly run: (signal?: AbortSignal) => Promise<unknown>;
}

interface Options {
  readonly canonical?: CanonicalRide;
  readonly pageSize?: number;
  readonly source?: SourceHooks;
  readonly sink?: SinkHooks;
  readonly open?: (result: ExportOpenResult) => ExportOpenResult;
  readonly openSink?: () => void;
  readonly close?: () => void;
}

function harness(options: Options = {}): Harness {
  const canonical = options.canonical ?? ride;
  const log: string[] = [];
  const source = new MemorySource(canonical, options.pageSize ?? 4096, options.source);
  const sink = new MemorySink(options.sink);
  const closes = { count: 0 };
  const logged: ExportSource = {
    open: async request => {
      log.push('open');
      const result = await source.open(request);
      return options.open ? options.open(result) : result;
    },
    page: request => {
      log.push(`page ${request.projection}${request.after === null ? '' : ' next'}`);
      return source.page(request);
    },
    close: async session => {
      log.push('close');
      closes.count++;
      options.close?.();
      await source.close(session);
    },
  };
  const loggedSink: ExportSink = {
    write: bytes => (log.push('write'), sink.write(bytes)),
    writeAt: (offset, bytes) => (log.push('writeAt'), sink.writeAt(offset, bytes)),
    beginDeflate: () => (log.push('beginDeflate'), sink.beginDeflate()),
    endDeflate: () => (log.push('endDeflate'), sink.endDeflate()),
    commit: name => (log.push(`commit ${name}`), sink.commit(name)),
    abort: () => (log.push('abort'), sink.abort()),
  };
  const run = (signal?: AbortSignal) =>
    exportZip(
      logged,
      async kind => {
        log.push(`openSink ${kind}`);
        options.openSink?.();
        return loggedSink;
      },
      { rideId: canonical.rideId, context: canonical.context, ...(signal ? { signal } : {}) },
    );
  return { log, source, sink, closes, run };
}

async function rejection(run: () => Promise<unknown>): Promise<unknown> {
  try {
    await run();
  } catch (error) {
    return error;
  }
  throw new Error('The export did not fail');
}

const codeOf = (error: unknown): ExportErrorCode | undefined => (error instanceof ExportError ? error.code : undefined);

function expectAbandoned(test: Harness): void {
  expect(test.sink.committed).toBeNull();
  expect(test.sink.aborted).toBe(true);
  expect(test.log[test.log.length - 2]).toBe('abort');
  expect(test.log[test.log.length - 1]).toBe('close');
  expect(test.source.closed.length).toBeGreaterThan(0);
  expect(test.sink.violations).toEqual([]);
}

const wide: CanonicalRide = {
  ...ride,
  rows: {
    telemetry: Array.from({ length: 3000 }, (_, i) => ({
      elapsedSeconds: i / 1000,
      timestamp: `2026-03-14T08:00:00.${String(i).padStart(4, '0')}Z`,
      producerSequence: i + 1,
      clockEpoch: 'epoch-a',
      connection: 'connection-9',
      humanPowerW: 100 + (i % 50) + 0.125,
      cadenceRpm: 80 + (i % 7) / 8,
      batteryVoltageV: 50 + (i % 13) / 16,
      motorTempC: 40 + (i % 11) / 4,
    })),
    lifecycle: ride.rows.lifecycle!.filter(row => row.action === 'start' || row.action === 'stop'),
  },
};

const ENTRY = ['write', 'beginDeflate'];
const END = ['write', 'endDeflate', 'writeAt'];

describe('the ZIP export driver', () => {
  it('opens, reads the timeline, streams four tables, writes the descriptor, closes and commits', async () => {
    const test = harness();
    expect(await test.run()).toEqual({ uri: `memory://power-log-original-${ride.rideId}.zip` });
    expect(test.log).toEqual([
      'open',
      'page lifecycle',
      'openSink zip',
      ...ENTRY,
      'page telemetry',
      ...END,
      ...ENTRY,
      'page gps',
      ...END,
      ...ENTRY,
      'page healthZip',
      ...END,
      ...ENTRY,
      'page lifecycle',
      ...END,
      ...ENTRY,
      ...END,
      'write',
      'close',
      `commit power-log-original-${ride.rideId}.zip`,
    ]);
    expect(test.sink.violations).toEqual([]);
  });

  it('aborts the sink and closes the session when cancelled mid-entry', async () => {
    const controller = new AbortController();
    const test = harness({
      pageSize: 4,
      source: {
        beforePage: request => {
          if (request.projection === 'telemetry' && request.after !== null) controller.abort();
        },
      },
    });
    const error = await rejection(() => test.run(controller.signal));
    expect(codeOf(error)).toBe('cancelled');
    expectAbandoned(test);
    expect(test.log.filter(entry => entry === 'page telemetry next')).toEqual(['page telemetry next']);
    expect(test.log.some(entry => entry === 'page gps')).toBe(false);
  });

  it.each(['failure', 'cancellation'])('settles the write in flight before aborting after a %s', async kind => {
    const controller = new AbortController();
    let open = () => {};
    const gate = new Promise<void>(resolve => (open = resolve));
    let held = false;
    let pendingAtFailure = false;
    const test: Harness = harness({
      canonical: wide,
      pageSize: 128,
      sink: {
        beforeWrite: async index => {
          if (index !== 1) return;
          held = true;
          await gate;
        },
      },
      source: {
        beforePage: request => {
          if (!held || request.projection !== 'telemetry') return;
          pendingAtFailure = test.sink.busy;
          setTimeout(open, 5);
          if (kind === 'cancellation') controller.abort();
          else throw new ExportError('changed', 'The ride changed.');
        },
      },
    });
    const code = codeOf(await rejection(() => test.run(controller.signal)));
    expect(code).toBe(kind === 'cancellation' ? 'cancelled' : 'changed');
    expect(pendingAtFailure).toBe(true);
    expectAbandoned(test);
  });

  it('does not open a session when cancelled before it starts', async () => {
    const controller = new AbortController();
    controller.abort();
    const test = harness();
    expect(codeOf(await rejection(() => test.run(controller.signal)))).toBe('cancelled');
    expect(test.log).toEqual([]);
  });

  it('aborts instead of committing when cancelled after the session closed', async () => {
    const controller = new AbortController();
    const test = harness({ close: () => controller.abort() });
    expect(codeOf(await rejection(() => test.run(controller.signal)))).toBe('cancelled');
    expectAbandoned(test);
    expect(test.closes.count).toBe(2);
  });

  it('aborts and closes when a sink write fails', async () => {
    const failure = new ExportError('sink', 'The disk is full.');
    const test = harness({
      sink: {
        beforeWrite: index => {
          if (index === 3) throw failure;
        },
      },
    });
    expect(await rejection(() => test.run())).toBe(failure);
    expectAbandoned(test);
  });

  it('aborts and closes when a page fails', async () => {
    const test = harness({
      source: {
        beforePage: request => {
          if (request.projection === 'gps') throw new ExportError('changed', 'The ride changed.');
        },
      },
    });
    expect(codeOf(await rejection(() => test.run()))).toBe('changed');
    expectAbandoned(test);
  });

  it('closes the session when the sink cannot open', async () => {
    const test = harness({
      openSink: () => {
        throw new ExportError('sink', 'No space for an export.');
      },
    });
    expect(codeOf(await rejection(() => test.run()))).toBe('sink');
    expect(test.log).toEqual(['open', 'page lifecycle', 'openSink zip', 'close']);
  });

  it('aborts and closes again when the commit fails', async () => {
    const test = harness({
      sink: {
        commit: () => {
          throw new ExportError('sink', 'The export could not be published.');
        },
      },
    });
    expect(codeOf(await rejection(() => test.run()))).toBe('sink');
    expectAbandoned(test);
    expect(test.closes.count).toBe(2);
  });

  it('aborts without committing when closing the session fails', async () => {
    const test = harness({
      close: () => {
        throw new ExportError('deleted', 'The ride was deleted.');
      },
    });
    expect(codeOf(await rejection(() => test.run()))).toBe('deleted');
    expect(test.sink.committed).toBeNull();
    expect(test.sink.aborted).toBe(true);
    expect(test.log.some(entry => entry.startsWith('commit'))).toBe(false);
  });

  it('fails a second lifecycle pass that delivers a different row count', async () => {
    const lifecycle = ride.rows.lifecycle!;
    let passes = 0;
    const rows: CanonicalRide['rows'] = {
      ...ride.rows,
      get lifecycle(): readonly Row[] {
        return passes++ === 0
          ? lifecycle
          : [...lifecycle, { ...lifecycle[lifecycle.length - 1]!, producerSequence: 11 }];
      },
    };
    const test = harness({ canonical: { ...ride, rows } });
    expect(codeOf(await rejection(() => test.run()))).toBe('changed');
    expectAbandoned(test);
  });

  it.each([
    ['producers', (result: ExportOpenResult) => ({ ...result, producers: { gps: ['watch', 'phone'], health: [] } })],
    ['missing producers', (result: ExportOpenResult) => ({ ...result, producers: undefined })],
    ['metadata', (result: ExportOpenResult) => ({ ...result, metadata: { ...result.metadata, health: undefined } })],
    ['sampleHz', (result: ExportOpenResult) => ({ ...result, metadata: { ...result.metadata, sampleHz: 5 } })],
    [
      'ownerTiming',
      (result: ExportOpenResult) => ({ ...result, metadata: { ...result.metadata, ownerTiming: { timestamp: 't' } } }),
    ],
    [
      'Health export counts',
      (result: ExportOpenResult) => ({
        ...result,
        metadata: {
          ...result.metadata,
          health: { ...result.metadata.health, export: { written: -1, omitted: 0, reason: null } },
        },
      }),
    ],
  ])('fails an open result with invalid %s', async (_name, change) => {
    const test = harness({ open: change as (result: ExportOpenResult) => ExportOpenResult });
    expect(codeOf(await rejection(() => test.run()))).toBe('page');
    expect(test.sink.committed).toBeNull();
    expect(test.log[test.log.length - 1]).toBe('close');
  });

  it('rejects an export time that is not UTC before opening the ride', async () => {
    const test = harness({
      canonical: { ...ride, context: { ...ride.context, exportedAt: '2026-03-14T10:27:31+01:00' } },
    });
    expect(codeOf(await rejection(() => test.run()))).toBe('unsupported');
    expect(test.log).toEqual([]);
  });

  it('keeps one page request and one sink call in flight and never changes a buffer before its write settles', async () => {
    const test = harness({
      canonical: wide,
      pageSize: 128,
      sink: { beforeWrite: () => new Promise(resolve => setTimeout(resolve, 0)) },
    });
    await test.run();
    expect(test.source.maxInFlight).toBe(1);
    expect(test.sink.maxInFlight).toBe(1);
    expect(test.sink.violations).toEqual([]);
    expect(test.sink.writes).toBeGreaterThan(12);
    const csv = entryText(readZip(test.sink.file)[0]!).split('\n');
    expect(csv.length).toBe(3000 + 2);
    expect(csv[3000]).toBe(`2026-03-14T08:00:00.2999Z,2.999,1,1,1,149.125,80.375,,50.5625,,,,,,41.75${','.repeat(8)}`);
  });
});
