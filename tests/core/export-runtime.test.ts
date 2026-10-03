import { beforeEach, describe, expect, it, vi } from 'vitest';
import { exportPlatform, exportSource, openExportSink } from '../../src/services/export-runtime.native';
import { ExportError, type ExportContext, type ExportSink, type NativeExportPage } from '../../src/core/export/types';

const native = vi.hoisted(() => ({
  platform: 'ios',
  installed: true,
  bridge: {
    exportOpen: vi.fn(),
    exportPage: vi.fn(),
    exportClose: vi.fn(),
    sinkOpen: vi.fn(),
    sinkWrite: vi.fn(),
    sinkWriteAt: vi.fn(),
    sinkBeginDeflate: vi.fn(),
    sinkEndDeflate: vi.fn(),
    sinkCommit: vi.fn(),
    sinkAbort: vi.fn(),
  },
}));
vi.mock('react-native', () => ({
  Platform: {
    get OS() {
      return native.platform;
    },
  },
}));
vi.mock('../../modules/cyc-bridge', () => ({
  get default() {
    return native.installed ? native.bridge : null;
  },
}));

const context: ExportContext = { exportedAt: '2026-10-02T08:00:00.000Z', platform: 'ios' };
const binary = (...values: number[]) => new Uint8Array(new Float64Array(values).buffer);
const flush = async () => {
  for (let i = 0; i < 10; i++) await Promise.resolve();
};
function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (error: unknown) => void;
  const promise = new Promise<T>((done, fail) => {
    resolve = done;
    reject = fail;
  });
  return { promise, resolve, reject };
}
const coded = (code: string, message: string) => Object.assign(new Error(message), { code });
const iosRejection = (fn: string, code: string, message: string) =>
  coded(
    code,
    `FunctionCallException: Calling the '${fn}' function has failed (at ExpoModulesCore/AsyncFunctionDefinition.swift:123)\n→ Caused by: BridgeException: ${message} (at CycBridge/CycBridgeModule.swift:17)`,
  );

async function openSink(id = 'sink-1'): Promise<ExportSink> {
  native.bridge.sinkOpen.mockResolvedValueOnce(id);
  return openExportSink('zip', context);
}

async function rejection(action: Promise<unknown>): Promise<ExportError> {
  const error = await action.then(
    () => null,
    (caught: unknown) => caught,
  );
  expect(error).toBeInstanceOf(ExportError);
  return error as ExportError;
}

beforeEach(() => {
  vi.resetAllMocks();
  native.platform = 'ios';
  native.installed = true;
});

describe('native export source', () => {
  it('views binary64 page columns in place and keeps text columns and the envelope', async () => {
    const elapsed = binary(0, 1.5, 3);
    const page: NativeExportPage<'lifecycle'> = {
      rows: 3,
      last: [3, 1, 7, 9],
      done: true,
      columns: { elapsedSeconds: elapsed, producer: ['phone', 'phone', 'watch'], action: ['start', 'pause', 'stop'] },
    };
    native.bridge.exportPage.mockResolvedValue(page);
    const request = { session: 'session-1', projection: 'lifecycle' as const, after: [1, 1, 2, 3] };
    const converted = await exportSource().page(request);
    expect(native.bridge.exportPage).toHaveBeenCalledExactlyOnceWith(request);
    expect(converted).toMatchObject({ rows: 3, last: [3, 1, 7, 9], done: true });
    expect(converted.columns.elapsedSeconds).toBeInstanceOf(Float64Array);
    expect([...converted.columns.elapsedSeconds!]).toEqual([0, 1.5, 3]);
    expect(converted.columns.elapsedSeconds!.buffer).toBe(elapsed.buffer);
    expect(converted.columns.producer).toBe(page.columns.producer);
    expect(converted.columns.action).toEqual(['start', 'pause', 'stop']);
  });

  it('passes telemetry connection identities through with the page', async () => {
    const connections = [{ token: 'epoch-1', vendor: 'cyc' as const, model: 'X6', firmware: '1.2', protocol: '5.3' }];
    native.bridge.exportPage.mockResolvedValue({
      rows: 1,
      last: [0.125, 0, 1, 4],
      done: false,
      connections,
      columns: { elapsedSeconds: binary(0.125), connection: ['epoch-1'], humanPowerW: binary(NaN) },
    });
    const converted = await exportSource().page({ session: 's', projection: 'telemetry', after: null });
    expect(converted.connections).toBe(connections);
    expect(converted.columns.connection).toEqual(['epoch-1']);
    expect(Number.isNaN(converted.columns.humanPowerW![0])).toBe(true);
  });

  it.each([
    ['a numeric column with too few bytes', { elapsedSeconds: binary(0, 1) }],
    ['a numeric column sent as numbers', { elapsedSeconds: [0, 1, 2] }],
    ['an unknown column', { elapsedSeconds: binary(0, 1, 2), speed: binary(0, 1, 2) }],
  ])('fails a page with %s', async (_name, columns) => {
    native.bridge.exportPage.mockResolvedValue({ rows: 3, last: [2, 0, 1, 3], done: true, columns });
    const error = await rejection(exportSource().page({ session: 's', projection: 'lifecycle', after: null }));
    expect(error.code).toBe('page');
  });

  it('opens and closes sessions with the request unchanged', async () => {
    const opened = {
      session: 'session-9',
      metadata: {},
      elapsedEnd: 12,
      producers: { gps: [], health: [] },
      distanceProfile: null,
    };
    native.bridge.exportOpen.mockResolvedValue(opened);
    native.bridge.exportClose.mockResolvedValue(undefined);
    const request = { rideId: 'ride-1', kind: 'fit' as const, distanceSource: 'auto' as const, context };
    expect(await exportSource().open(request)).toBe(opened);
    expect(native.bridge.exportOpen).toHaveBeenCalledExactlyOnceWith(request);
    await exportSource().close('session-9');
    expect(native.bridge.exportClose).toHaveBeenCalledExactlyOnceWith('session-9');
  });

  it('fails an open result without a session', async () => {
    native.bridge.exportOpen.mockResolvedValue({ metadata: {} });
    const error = await rejection(exportSource().open({ rideId: 'ride-1', kind: 'zip', context }));
    expect(error.code).toBe('page');
  });
});

describe('native export errors', () => {
  const sourceCalls: [string, string, () => Promise<unknown>][] = [
    ['exportOpen', 'exportOpen', () => exportSource().open({ rideId: 'ride-1', kind: 'zip', context })],
    ['exportPage', 'exportPage', () => exportSource().page({ session: 's', projection: 'gps', after: null })],
    ['exportClose', 'exportClose', () => exportSource().close('s')],
  ];
  const sinkCalls: [string, string, (sink: ExportSink) => Promise<unknown>][] = [
    ['sinkWrite', 'sinkWrite', sink => sink.write(new Uint8Array([1]))],
    ['sinkWriteAt', 'sinkWriteAt', sink => sink.writeAt(0, new Uint8Array([1]))],
    ['sinkBeginDeflate', 'sinkBeginDeflate', sink => sink.beginDeflate()],
    ['sinkEndDeflate', 'sinkEndDeflate', sink => sink.endDeflate()],
    ['sinkCommit', 'sinkCommit', sink => sink.commit('power-log-ride.fit')],
    ['sinkAbort', 'sinkAbort', sink => sink.abort()],
  ];
  const fail = (method: string, error: unknown) =>
    native.bridge[method as keyof typeof native.bridge].mockRejectedValue(error);

  it.each(sourceCalls)('%s keeps an export error code and the rider message', async (_name, method, run) => {
    fail(method, coded('deleted', 'This ride was deleted from Power Log.'));
    const error = await rejection(run());
    expect(error.code).toBe('deleted');
    expect(error.message).toBe('This ride was deleted from Power Log.');
  });

  it.each(sinkCalls)('%s keeps an export error code and the rider message', async (_name, method, run) => {
    const sink = await openSink();
    fail(method, coded('sink', 'The export file could not be written: No space left on device.'));
    const error = await rejection(run(sink));
    expect(error.code).toBe('sink');
    expect(error.message).toBe('The export file could not be written: No space left on device.');
  });

  it.each(sourceCalls)('%s takes the native reason out of an iOS rejection', async (_name, method, run) => {
    fail(method, iosRejection(method, 'changed', 'The ride changed while it was exported. Try the export again.'));
    const error = await rejection(run());
    expect(error.code).toBe('changed');
    expect(error.message).toBe('The ride changed while it was exported. Try the export again.');
  });

  it('keeps parentheses and colons inside an iOS reason', async () => {
    const sink = await openSink();
    fail('sinkWrite', iosRejection('sinkWrite', 'sink', 'The export file could not write: Disk full (28).'));
    const error = await rejection(sink.write(new Uint8Array([1])));
    expect(error.message).toBe('The export file could not write: Disk full (28).');
  });

  it.each(sourceCalls)('%s turns any other rejection into a page error', async (_name, method, run) => {
    fail(method, coded('E_POWER_LOG', 'database is locked'));
    const error = await rejection(run());
    expect(error.code).toBe('page');
    expect(error.message).toBe('Power Log could not read this ride for export. database is locked');
  });

  it.each(sinkCalls)('%s turns any other rejection into a sink error', async (_name, method, run) => {
    const sink = await openSink();
    fail(method, iosRejection(method, 'ERR_UNEXPECTED', 'Operation not permitted'));
    const error = await rejection(run(sink));
    expect(error.code).toBe('sink');
    expect(error.message).toBe('Power Log could not write the export file. Operation not permitted');
  });

  it('maps rejections without a code or message by call site', async () => {
    fail('exportPage', 'busy');
    expect(await rejection(exportSource().page({ session: 's', projection: 'gps', after: null }))).toMatchObject({
      code: 'page',
      message: 'Power Log could not read this ride for export. busy',
    });
    native.bridge.sinkOpen.mockRejectedValue(new TypeError(''));
    expect(await rejection(openExportSink('fit', context))).toMatchObject({
      code: 'sink',
      message: 'Power Log could not write the export file.',
    });
  });

  it('reads the code and message of a rejected plain object', async () => {
    fail('exportOpen', { code: 'gate', message: 'Only saved rides can be exported.' });
    expect(await rejection(exportSource().open({ rideId: 'ride-1', kind: 'zip', context }))).toMatchObject({
      code: 'gate',
      message: 'Only saved rides can be exported.',
    });
  });

  it('uses the call-site message when a coded rejection has no text', async () => {
    fail('exportOpen', coded('gate', ''));
    expect(await rejection(exportSource().open({ rideId: 'ride-1', kind: 'zip', context }))).toMatchObject({
      code: 'gate',
      message: 'Power Log could not read this ride for export.',
    });
  });

  it('reports a build without the native module as unsupported', async () => {
    native.installed = false;
    expect((await rejection(exportSource().open({ rideId: 'ride-1', kind: 'zip', context }))).code).toBe('unsupported');
    expect((await rejection(openExportSink('zip', context))).code).toBe('unsupported');
  });

  it('fails a sink without an identifier or a committed file without a location', async () => {
    native.bridge.sinkOpen.mockResolvedValue(42);
    expect((await rejection(openExportSink('zip', context))).code).toBe('sink');
    const sink = await openSink();
    native.bridge.sinkCommit.mockResolvedValue({});
    expect((await rejection(sink.commit('power-log-original-ride.zip'))).code).toBe('sink');
  });
});

describe('native export sink', () => {
  it('binds its native identifier to every call and passes buffers without copying', async () => {
    const sink = await openSink('sink-a');
    expect(native.bridge.sinkOpen).toHaveBeenCalledExactlyOnceWith('zip', context);
    const bytes = new Uint8Array([1, 2, 3]).subarray(1);
    native.bridge.sinkEndDeflate.mockResolvedValue({ crc32: 7, inputBytes: 2, outputBytes: 4 });
    native.bridge.sinkCommit.mockResolvedValue({ uri: 'file:///exports/a/power-log-original-ride.zip' });
    await sink.write(bytes);
    await sink.beginDeflate();
    await sink.write(bytes);
    expect(await sink.endDeflate()).toEqual({ crc32: 7, inputBytes: 2, outputBytes: 4 });
    await sink.writeAt(10, bytes);
    expect(await sink.commit('power-log-original-ride.zip')).toEqual({
      uri: 'file:///exports/a/power-log-original-ride.zip',
    });
    await sink.abort();
    expect(native.bridge.sinkWrite.mock.calls).toEqual([
      ['sink-a', bytes],
      ['sink-a', bytes],
    ]);
    expect(native.bridge.sinkWrite.mock.calls[0]![1]).toBe(bytes);
    expect(native.bridge.sinkBeginDeflate).toHaveBeenCalledExactlyOnceWith('sink-a');
    expect(native.bridge.sinkEndDeflate).toHaveBeenCalledExactlyOnceWith('sink-a');
    expect(native.bridge.sinkWriteAt).toHaveBeenCalledExactlyOnceWith('sink-a', 10, bytes);
    expect(native.bridge.sinkCommit).toHaveBeenCalledExactlyOnceWith('sink-a', 'power-log-original-ride.zip');
    expect(native.bridge.sinkAbort).toHaveBeenCalledExactlyOnceWith('sink-a');
  });

  it('keeps the identifiers of two open sinks apart', async () => {
    const first = await openSink('sink-a');
    const second = await openSink('sink-b');
    await second.write(new Uint8Array([2]));
    await first.abort();
    expect(native.bridge.sinkWrite).toHaveBeenCalledExactlyOnceWith('sink-b', new Uint8Array([2]));
    expect(native.bridge.sinkAbort).toHaveBeenCalledExactlyOnceWith('sink-a');
  });

  it('resolves a write only after the native write and starts the next call only after it settles', async () => {
    const sink = await openSink();
    const first = deferred<void>();
    const second = deferred<void>();
    native.bridge.sinkWrite.mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise);
    const settled: string[] = [];
    void sink.write(new Uint8Array([1])).then(() => settled.push('first'));
    void sink.write(new Uint8Array([2])).then(() => settled.push('second'));
    await flush();
    expect(native.bridge.sinkWrite).toHaveBeenCalledTimes(1);
    expect(settled).toEqual([]);
    first.resolve();
    await flush();
    expect(settled).toEqual(['first']);
    expect(native.bridge.sinkWrite).toHaveBeenCalledTimes(2);
    expect(native.bridge.sinkWrite.mock.calls[1]![1]).toEqual(new Uint8Array([2]));
    second.resolve();
    await flush();
    expect(settled).toEqual(['first', 'second']);
  });

  it('runs a queued abort after a failed write', async () => {
    const sink = await openSink();
    const write = deferred<void>();
    native.bridge.sinkWrite.mockReturnValueOnce(write.promise);
    native.bridge.sinkAbort.mockResolvedValue(undefined);
    const writing = rejection(sink.write(new Uint8Array([1])));
    const aborting = sink.abort();
    await flush();
    expect(native.bridge.sinkAbort).not.toHaveBeenCalled();
    write.reject(coded('sink', 'The export file could not be written.'));
    expect((await writing).message).toBe('The export file could not be written.');
    await aborting;
    expect(native.bridge.sinkAbort).toHaveBeenCalledExactlyOnceWith('sink-1');
  });
});

describe('native export platform', () => {
  it.each([
    ['ios', 'ios'],
    ['android', 'android'],
  ])('reports %s as %s', (os, platform) => {
    native.platform = os;
    expect(exportPlatform()).toBe(platform);
  });
});
