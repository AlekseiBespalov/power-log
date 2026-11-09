import { readFileSync } from 'node:fs';
import { describe, expect, it, vi } from 'vitest';
import type { ExportPage, ExportSessionView } from '../../shared/generated/ts/api';
import { createPowerLogClient } from '../../modules/power-log/src/client';
import { exportFile } from '../../modules/power-log/src/exports/export-file';
import { ExportError, type ExportSink } from '../../modules/power-log/src/exports/types';
import { fakeTransport, type FakeTransport } from '../../modules/power-log/src/testing/fake-transport';

vi.mock('../../modules/power-log/src/native-module', () => ({ default: null }));

const view: ExportSessionView = {
  sessionId: 'session-1',
  platform: 'android',
  metadata: null,
  elapsedEnd: 2,
  timerSeconds: 2,
  producers: { gps: [], health: [] },
  consumption: { kind: 'unavailable', reason: 'No controller telemetry.' },
};

const rideView = JSON.parse(
  readFileSync('tests/fixtures/export/storable/ios-controller-distance/pages/open.json', 'utf8'),
) as ExportSessionView;

const telemetry = (done: boolean): ExportPage => ({
  rows: 1,
  next: [1],
  done,
  connections: [],
  columns: {
    elapsedSeconds: new Uint8Array(new Float64Array([1]).buffer),
    timestamp: ['2026-01-01T00:00:01.000Z'],
  },
});

class RecordingSink implements ExportSink {
  readonly calls: string[] = [];
  async write() {
    this.calls.push('write');
  }
  async writeAt() {
    this.calls.push('writeAt');
  }
  async beginDeflate() {
    this.calls.push('beginDeflate');
  }
  async endDeflate() {
    this.calls.push('endDeflate');
    return { crc32: 0, inputBytes: 0, outputBytes: 0 };
  }
  async commit(name: string) {
    this.calls.push(`commit ${name}`);
    return { uri: `memory://${name}`, name, mimeType: 'text/csv', sizeBytes: 0 };
  }
  async abort() {
    this.calls.push('abort');
  }
}

function serve(transport: FakeTransport, pages: (after: number[] | null) => ExportPage | 'failed' | 'cancelled') {
  transport.answer('exports.open', () => ({ ok: true, value: view }));
  transport.answer('exports.close', () => ({ ok: true, value: null }));
  transport.answerPages(input => {
    const page = pages(input.after);
    if (page === 'cancelled') return { cancelled: true };
    if (page === 'failed')
      return { ok: false, failure: { code: 'exportChanged', retryable: false, occurrence: null, detail: null } };
    return { ok: true, value: page };
  });
}

const imported = { source: { kind: 'imported', importId: 'import-1' }, kind: 'csv', fitPower: 'rider' } as const;
const file = { ...imported, exportedAt: '2026-01-01T00:00:00.000Z', name: 'My ride.csv' };
const closes = (transport: FakeTransport) => transport.calls.filter(call => call.op === 'exports.close');

describe('exportFile', () => {
  it('commits the encoded file, then closes the session without a failure', async () => {
    const transport = fakeTransport();
    serve(transport, after => telemetry(after !== null));
    const sink = new RecordingSink();
    const result = await exportFile(createPowerLogClient(transport), file, async () => sink);
    expect(result).toEqual({
      ok: true,
      value: { uri: 'memory://My-ride.csv', name: 'My-ride.csv', mimeType: 'text/csv', sizeBytes: 0 },
    });
    expect(sink.calls.at(-1)).toBe('commit My-ride.csv');
    expect(closes(transport).map(call => call.input)).toEqual([{ sessionId: 'session-1', failure: null }]);
  });

  it('aborts the sink and passes a page failure to exports.close', async () => {
    const transport = fakeTransport();
    serve(transport, after => (after === null ? telemetry(false) : 'failed'));
    const sink = new RecordingSink();
    const result = await exportFile(createPowerLogClient(transport), file, async () => sink);
    expect(result).toEqual({
      ok: false,
      failure: { code: 'exportChanged', retryable: false, occurrence: null, detail: null },
    });
    expect(sink.calls).toContain('abort');
    expect(sink.calls.some(call => call.startsWith('commit'))).toBe(false);
    expect(closes(transport).map(call => call.input)).toEqual([{ sessionId: 'session-1', failure: 'exportChanged' }]);
  });

  it('answers exportLeftScreen and closes the session when aborted before the commit', async () => {
    const transport = fakeTransport();
    const controller = new AbortController();
    serve(transport, after => {
      if (after !== null) controller.abort();
      return telemetry(after !== null);
    });
    const sink = new RecordingSink();
    const result = await exportFile(createPowerLogClient(transport), file, async () => sink, controller.signal);
    expect(result).toEqual({
      ok: false,
      failure: { code: 'exportLeftScreen', retryable: false, occurrence: null, detail: null },
    });
    expect(sink.calls).toContain('abort');
    expect(closes(transport).map(call => call.input)).toEqual([{ sessionId: 'session-1', failure: null }]);
  });

  it.each([
    ['ride CSV', 'csv', 'seconds'],
    ['FIT', 'fit', 'summary'],
  ] as const)('stops reading a %s at the page during which Power Log left the screen', async (_, kind, first) => {
    const transport = fakeTransport();
    const controller = new AbortController();
    const asked: string[] = [];
    transport.answer('exports.open', () => ({ ok: true, value: rideView }));
    transport.answer('exports.close', () => ({ ok: true, value: null }));
    transport.answerPages(input => {
      asked.push(input.projection);
      controller.abort();
      return { ok: true, value: telemetry(false) };
    });
    const sink = new RecordingSink();
    const ride = { source: { kind: 'ride', rideId: 'ride-1' }, kind, fitPower: 'rider' } as const;
    const result = await exportFile(
      createPowerLogClient(transport),
      { ...ride, exportedAt: '2026-01-01T00:00:00.000Z' },
      async () => sink,
      controller.signal,
    );
    expect(result).toEqual({
      ok: false,
      failure: { code: 'exportLeftScreen', retryable: false, occurrence: null, detail: null },
    });
    expect(asked).toEqual([first]);
    expect(sink.calls).toEqual(['abort']);
    expect(closes(transport).map(call => call.input)).toEqual([{ sessionId: rideView.sessionId, failure: null }]);
  });

  it('answers exportLeftScreen when a page fails after Power Log left the screen', async () => {
    const transport = fakeTransport();
    const controller = new AbortController();
    serve(transport, () => {
      controller.abort();
      return 'failed';
    });
    const sink = new RecordingSink();
    const result = await exportFile(createPowerLogClient(transport), file, async () => sink, controller.signal);
    expect(result).toEqual({
      ok: false,
      failure: { code: 'exportLeftScreen', retryable: false, occurrence: null, detail: null },
    });
    expect(closes(transport).map(call => call.input)).toEqual([{ sessionId: 'session-1', failure: null }]);
  });

  it('opens no session and no sink when Power Log left the screen before it started', async () => {
    const transport = fakeTransport();
    serve(transport, after => telemetry(after !== null));
    const controller = new AbortController();
    controller.abort();
    const openSink = vi.fn();
    const result = await exportFile(createPowerLogClient(transport), file, openSink, controller.signal);
    expect(result).toEqual({
      ok: false,
      failure: { code: 'exportLeftScreen', retryable: false, occurrence: null, detail: null },
    });
    expect(transport.calls.filter(call => call.op.startsWith('exports.'))).toEqual([]);
    expect(openSink).not.toHaveBeenCalled();
  });

  it('closes the session with exportSinkFailed when the sink cannot open', async () => {
    const transport = fakeTransport();
    serve(transport, after => telemetry(after !== null));
    const result = await exportFile(createPowerLogClient(transport), file, async () => {
      throw new ExportError('exportSinkFailed', 'No space for an export.');
    });
    expect(result).toEqual({
      ok: false,
      failure: { code: 'exportSinkFailed', retryable: false, occurrence: null, detail: null },
    });
    expect(closes(transport).map(call => call.input)).toEqual([
      { sessionId: 'session-1', failure: 'exportSinkFailed' },
    ]);
  });

  it('aborts the sink and closes the session with exportSinkFailed when the commit fails', async () => {
    const transport = fakeTransport();
    serve(transport, after => telemetry(after !== null));
    const sink = new RecordingSink();
    sink.commit = async () => {
      throw new ExportError('exportSinkFailed', 'The export could not be published.');
    };
    const result = await exportFile(createPowerLogClient(transport), file, async () => sink);
    expect(result).toMatchObject({ ok: false, failure: { code: 'exportSinkFailed' } });
    expect(sink.calls.at(-1)).toBe('abort');
    expect(closes(transport).map(call => call.input)).toEqual([
      { sessionId: 'session-1', failure: 'exportSinkFailed' },
    ]);
  });

  it.each(['leaving the screen', 'a failed write'])(
    'lets the write in flight settle before aborting the sink after %s',
    async cause => {
      const transport = fakeTransport();
      serve(transport, after => telemetry(after !== null));
      const controller = new AbortController();
      let release = () => {};
      const sink = new RecordingSink();
      sink.write = () => {
        sink.calls.push('write');
        if (cause === 'leaving the screen') controller.abort();
        return new Promise<void>((resolve, reject) => {
          release = () => {
            sink.calls.push('write settled');
            if (cause === 'a failed write') reject(new ExportError('exportSinkFailed', 'The disk is full.'));
            else resolve();
          };
        });
      };
      const exported = exportFile(createPowerLogClient(transport), file, async () => sink, controller.signal);
      await vi.waitFor(() => expect(sink.calls).toContain('write'));
      expect(sink.calls).not.toContain('abort');
      release();
      const result = await exported;
      const code = cause === 'leaving the screen' ? 'exportLeftScreen' : 'exportSinkFailed';
      expect(result).toMatchObject({ ok: false, failure: { code } });
      expect(sink.calls.slice(-2)).toEqual(['write settled', 'abort']);
    },
  );

  it('stops without an error when its session closes under it', async () => {
    const transport = fakeTransport();
    serve(transport, () => 'cancelled');
    const sink = new RecordingSink();
    const result = await exportFile(createPowerLogClient(transport), file, async () => sink);
    expect(result).toEqual({ cancelled: true });
    expect(sink.calls).toEqual(['abort']);
  });
});
