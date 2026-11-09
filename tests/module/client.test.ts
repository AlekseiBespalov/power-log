import { afterEach, describe, expect, it, vi } from 'vitest';
import { API_HASH, type ApiFailure } from '../../shared/generated/ts/api';
import { createPowerLogClient, STREAM_RETRY_MS, type StreamEvent } from '../../modules/power-log/src/client';
import { fakeTransport } from '../../modules/power-log/src/testing/fake-transport';
import type { Transport } from '../../modules/power-log/src/transport';

vi.mock('../../modules/power-log/src/native-module', () => ({ default: null }));

const failure = (code: ApiFailure['code'], retryable: boolean): ApiFailure => ({
  code,
  retryable,
  occurrence: null,
  detail: null,
});
const mismatch = failure('apiMismatch', false);
const rejecting: Transport = {
  call: () => Promise.reject(new Error('missing')),
  next: () => Promise.reject(new Error('missing')),
  exportPage: () => Promise.reject(new Error('missing')),
};
const unparsable: Transport = {
  call: async () => 'not json',
  next: async () => 'not json',
  exportPage: async () => ({ envelope: 'not json', numbers: {} }),
};
const settled = () => new Promise(resolve => setTimeout(resolve, 0));

afterEach(() => {
  vi.useRealTimers();
});

describe('the client', () => {
  it.each([
    ['another hash', fakeTransport({ compatible: false })],
    ['no native module', rejecting],
    ['an unparsable answer', unparsable],
  ])('turns incompatible for %s and answers every call, page and stream with apiMismatch', async (_, transport) => {
    const client = createPowerLogClient(transport);
    const states: string[] = [];
    client.onCompatibility(state => states.push(state));
    const failures: ApiFailure[] = [];

    const call = client.call('history.delete', { rideId: 'r' });
    const page = client.exportPage({ sessionId: 's', projection: 'telemetry', after: null });
    client.subscribe(
      'recording.state',
      {},
      () => undefined,
      received => failures.push(received),
    );

    expect(await call).toEqual({ ok: false, failure: mismatch });
    expect(await page).toEqual({ ok: false, failure: mismatch });
    expect(failures).toEqual([mismatch]);
    expect(client.compatibility).toBe('incompatible');
    expect(states).toEqual(['incompatible']);
    expect(await client.call('connection.disconnect', {})).toEqual({ ok: false, failure: mismatch });
  });

  it('sends calls made while checking in their order once the check passes', async () => {
    const transport = fakeTransport();
    transport.answer('connection.startSearch', () => ({ ok: true, value: null }));
    transport.answer('connection.stopSearch', () => ({ ok: true, value: null }));
    transport.answer('history.page', () => ({ ok: true, value: { rows: [], next: null } }));
    const client = createPowerLogClient(transport);
    expect(client.compatibility).toBe('checking');

    const answers = Promise.all([
      client.call('connection.startSearch', {}),
      client.call('history.page', { after: null, key: 'k' }),
      client.call('connection.stopSearch', {}),
    ]);

    await answers;
    expect(transport.calls.map(call => call.op)).toEqual([
      'apiVersion',
      'connection.startSearch',
      'history.page',
      'connection.stopSearch',
    ]);
    expect(client.compatibility).toBe('compatible');
  });

  it('reaches the transport inside call() once compatible, as a user activation needs', async () => {
    const transport = fakeTransport();
    transport.answer('connection.startSearch', () => ({ ok: true, value: null }));
    const client = createPowerLogClient(transport);
    await client.call('connection.startSearch', {});

    void client.call('connection.startSearch', {});

    expect(transport.calls.filter(call => call.op === 'connection.startSearch')).toHaveLength(2);
  });

  it('runs one loop for two listeners whose params differ only in key order, the second getting the cached event', async () => {
    const transport = fakeTransport();
    const client = createPowerLogClient(transport);
    const first: StreamEvent<unknown>[] = [];
    const second: StreamEvent<unknown>[] = [];
    client.subscribe('charts.head', { source: { kind: 'ride', rideId: 'r' } }, event => first.push(event));
    await settled();
    transport.emit('charts.head', { source: { rideId: 'r', kind: 'ride' } } as never, { revision: 7 } as never);
    await settled();

    client.subscribe('charts.head', { source: { rideId: 'r', kind: 'ride' } } as never, event => second.push(event));
    expect(second).toEqual([]);
    await Promise.resolve();

    expect(second).toEqual([{ revision: 1, value: { revision: 7 } }]);
    expect(first).toEqual(second);
    expect(transport.calls.filter(call => call.op === 'charts.head').map(call => call.input)).toEqual([
      { params: { source: { kind: 'ride', rideId: 'r' } }, after: null, key: 'w:1' },
      { params: { source: { kind: 'ride', rideId: 'r' } }, after: 1, key: 'w:1' },
    ]);
  });

  it('asks again after STREAM_RETRY_MS with the same after on a retryable failure and ends on a non-retryable one', async () => {
    vi.useFakeTimers();
    const transport = fakeTransport();
    const client = createPowerLogClient(transport);
    const failures: ApiFailure[] = [];
    const events: StreamEvent<unknown>[] = [];
    client.subscribe(
      'recording.state',
      {},
      event => events.push(event),
      received => failures.push(received),
    );
    await vi.advanceTimersByTimeAsync(0);
    transport.emit('recording.state', {}, { phase: null } as never);
    await vi.advanceTimersByTimeAsync(0);
    transport.fail('recording.state', {}, failure('storageBusy', true));
    await vi.advanceTimersByTimeAsync(0);
    const sent = () => transport.calls.filter(call => call.op === 'recording.state').map(call => call.input);
    expect(failures).toEqual([failure('storageBusy', true)]);
    expect(sent()).toHaveLength(2);

    await vi.advanceTimersByTimeAsync(STREAM_RETRY_MS - 1);
    expect(sent()).toHaveLength(2);
    await vi.advanceTimersByTimeAsync(1);
    expect(sent()).toHaveLength(3);
    expect(sent()[2]).toMatchObject({ after: 1 });

    transport.fail('recording.state', {}, failure('readFailed', false));
    await vi.advanceTimersByTimeAsync(STREAM_RETRY_MS * 3);
    expect(failures).toEqual([failure('storageBusy', true), failure('readFailed', false)]);
    expect(sent()).toHaveLength(3);
    expect(events).toHaveLength(1);
  });

  it('cancels a loop by its key when its last listener leaves', async () => {
    const transport = fakeTransport();
    const client = createPowerLogClient(transport);
    const stopFirst = client.subscribe('connection.state', {}, () => undefined);
    const stopSecond = client.subscribe('connection.state', {}, () => undefined);
    await settled();

    stopFirst();
    expect(transport.calls.some(call => call.op === 'cancel')).toBe(false);
    stopSecond();

    expect(transport.calls.filter(call => call.op === 'cancel').map(call => call.input)).toEqual([{ key: 'w:1' }]);
  });

  it('resolves an aborted query as cancelled and cancels its key', async () => {
    const transport = fakeTransport();
    transport.answer('history.page', () => new Promise(() => undefined));
    const client = createPowerLogClient(transport);
    const controller = new AbortController();

    const page = client.call('history.page', { after: null, key: 'history:list:page' }, { signal: controller.signal });
    await settled();
    controller.abort();

    expect(await page).toEqual({ cancelled: true });
    expect(transport.calls.filter(call => call.op === 'cancel').map(call => call.input)).toEqual([
      { key: 'history:list:page' },
    ]);
    const early = new AbortController();
    early.abort();
    expect(await client.call('history.page', { after: null, key: 'k' }, { signal: early.signal })).toEqual({
      cancelled: true,
    });
  });

  it('resolves a transport rejection as unknown and never rejects', async () => {
    const transport = fakeTransport();
    const client = createPowerLogClient(transport);

    expect(await client.call('connection.stopSearch', {})).toEqual({ ok: false, failure: failure('unknown', false) });
    expect(await client.exportPage({ sessionId: 's', projection: 'telemetry', after: null })).toEqual({
      ok: false,
      failure: failure('unknown', false),
    });
  });

  it('maps app instants to performance.now() from the newest answer carrying now', async () => {
    const transport = fakeTransport();
    transport.answer('connection.disconnect', () => ({ ok: true, value: null }));
    const client = createPowerLogClient(transport);
    expect(client.localTime(0)).toBeNull();

    transport.setNow(5_000_000);
    const before = performance.now();
    await client.call('connection.disconnect', {});
    const after = performance.now();

    const local = client.localTime(5_000_000)!;
    expect(local).toBeGreaterThanOrEqual(before);
    expect(local).toBeLessThanOrEqual(after);
    expect(client.localTime(5_250_000)! - local).toBeCloseTo(250, 9);
    expect(API_HASH).toMatch(/^[0-9a-f]{16}$/);
  });

  it('keeps a loop and its other listeners going when one listener throws', async () => {
    const reported = vi.spyOn(console, 'error').mockImplementation(() => undefined);
    const transport = fakeTransport();
    const client = createPowerLogClient(transport);
    const received: unknown[] = [];
    client.subscribe('recording.state', {}, () => {
      throw new Error('a holder defect');
    });
    client.subscribe('recording.state', {}, event => received.push(event.value));
    await settled();

    transport.emit('recording.state', {}, 'first' as never);
    await settled();
    transport.emit('recording.state', {}, 'second' as never);
    await settled();

    expect(received).toEqual(['first', 'second']);
    expect(reported).toHaveBeenCalledTimes(2);
    reported.mockRestore();
  });

  it('serves an extra API section through the same transport, checks and loops', async () => {
    interface SampleCalls {
      'sample.touch': { input: { key: string }; output: null };
      'sample.read': { input: { key: string }; output: number };
    }
    interface SampleStreams {
      'sample.feed': { params: { scope: string }; value: number };
    }
    const transport = fakeTransport();
    transport.answer('sample.touch' as never, (() => ({ ok: true, value: null })) as never);
    transport.answer('sample.read' as never, (() => ({ ok: true, value: 7 })) as never);
    const sample = createPowerLogClient(transport).section<SampleCalls, SampleStreams, 'sample.read'>();
    const fed: StreamEvent<number>[] = [];

    expect(await sample.call('sample.touch', { key: 'k' })).toEqual({ ok: true, value: null });
    const query = new AbortController();
    expect(await sample.call('sample.read', { key: 'q' }, { signal: query.signal })).toEqual({ ok: true, value: 7 });
    // @ts-expect-error a command takes no signal
    const typeOnly = () => sample.call('sample.touch', { key: 'k' }, { signal: query.signal });
    expect(typeof typeOnly).toBe('function');
    sample.subscribe('sample.feed', { scope: 'all' }, event => fed.push(event));
    await settled();
    transport.emit('sample.feed' as never, { scope: 'all' } as never, 3 as never);
    await settled();

    expect(fed).toEqual([{ revision: 1, value: 3 }]);
    expect(transport.calls.map(call => call.op)).toEqual([
      'apiVersion',
      'sample.touch',
      'sample.read',
      'sample.feed',
      'sample.feed',
    ]);
  });
});
