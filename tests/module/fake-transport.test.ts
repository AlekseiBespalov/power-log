import { describe, expect, it } from 'vitest';
import { API_HASH, type ApiFailure } from '../../shared/generated/ts/api';
import { fakeTransport } from '../../modules/power-log/src/testing/fake-transport';

const failure: ApiFailure = { code: 'storageBusy', retryable: true, occurrence: null, detail: null };
const parse = (text: string): unknown => JSON.parse(text);

describe('fakeTransport', () => {
  it('answers apiVersion with the generated hash, or another one when incompatible', async () => {
    expect(parse(await fakeTransport().call('apiVersion', '{}'))).toEqual({
      ok: true,
      value: { hash: API_HASH },
      now: 0,
    });
    const other = parse(await fakeTransport({ compatible: false }).call('apiVersion', '{}')) as {
      value: { hash: string };
    };
    expect(other.value.hash).not.toBe(API_HASH);
  });

  it('rejects a call it has no answer for and a page without answerPages', async () => {
    const transport = fakeTransport();
    await expect(transport.call('history.delete', '{"rideId":"r"}')).rejects.toThrow();
    await expect(transport.exportPage('{"sessionId":"s","projection":"telemetry","after":null}')).rejects.toThrow();
  });

  it('writes each answer as an envelope with the latest now and records every request', async () => {
    const transport = fakeTransport();
    transport.answer('connection.disconnect', () => ({ ok: true, value: null }));
    transport.answer('history.delete', () => ({ ok: false, failure }));
    transport.answer('recording.start', () => ({ cancelled: true }));
    transport.setNow(42);
    expect(parse(await transport.call('connection.disconnect', '{}'))).toEqual({ ok: true, value: null, now: 42 });
    expect(parse(await transport.call('history.delete', '{"rideId":"r"}'))).toEqual({ ok: false, failure, now: 42 });
    expect(parse(await transport.call('recording.start', '{}'))).toEqual({ cancelled: true });
    expect(transport.calls).toEqual([
      { op: 'connection.disconnect', input: {} },
      { op: 'history.delete', input: { rideId: 'r' } },
      { op: 'recording.start', input: {} },
    ]);
  });

  it('answers cancel itself and cancels the query in flight with that key', async () => {
    const transport = fakeTransport();
    let release = (): void => undefined;
    transport.answer(
      'history.page',
      () => new Promise(resolve => (release = () => resolve({ ok: true, value: { rows: [], next: null } }))),
    );
    const query = transport.call('history.page', '{"key":"k","after":null,"limit":10}');
    await Promise.resolve();
    expect(parse(await transport.call('cancel', '{"key":"k"}'))).toEqual({ ok: true, value: null, now: 0 });
    expect(parse(await query)).toEqual({ cancelled: true });
    release();
  });

  it('cancels a query when a newer one with the same key arrives', async () => {
    const transport = fakeTransport();
    const releases: (() => void)[] = [];
    transport.answer(
      'history.page',
      () => new Promise(resolve => releases.push(() => resolve({ ok: true, value: { rows: [], next: null } }))),
    );
    const older = transport.call('history.page', '{"key":"k","after":null,"limit":10}');
    const newer = transport.call('history.page', '{"key":"k","after":null,"limit":20}');
    expect(parse(await older)).toEqual({ cancelled: true });
    await Promise.resolve();
    releases.forEach(release => release());
    expect(parse(await newer)).toEqual({ ok: true, value: { rows: [], next: null }, now: 0 });
  });

  it('splits a page into its text columns and one byte buffer per numeric column', async () => {
    const transport = fakeTransport();
    const values = new Float64Array([1.5, NaN, 3]);
    const shared = new Uint8Array(32);
    shared.set(new Uint8Array(values.buffer), 8);
    transport.answerPages(() => ({
      ok: true,
      value: {
        rows: 3,
        next: [3],
        done: true,
        connections: [],
        columns: { timestamp: ['a', null, 'c'], power: shared.subarray(8, 32) },
      },
    }));
    const page = await transport.exportPage('{"sessionId":"s","projection":"telemetry","after":null}');
    expect(parse(page.envelope)).toEqual({
      ok: true,
      value: { rows: 3, next: [3], done: true, connections: [], columns: { timestamp: ['a', null, 'c'] } },
      now: 0,
    });
    const power = page.numbers.power!;
    expect(power.byteOffset).toBe(0);
    expect(Array.from(new Float64Array(power.buffer, 0, 3))).toEqual([1.5, NaN, 3]);
    expect(transport.calls).toEqual([
      { op: 'exports.page', input: { sessionId: 's', projection: 'telemetry', after: null } },
    ]);
  });

  it('sends no numbers with a failed or cancelled page', async () => {
    const transport = fakeTransport();
    transport.answerPages(input => (input.after === null ? { ok: false, failure } : { cancelled: true }));
    expect(await transport.exportPage('{"sessionId":"s","projection":"gps","after":null}')).toEqual({
      envelope: JSON.stringify({ ok: false, failure, now: 0 }),
      numbers: {},
    });
    expect(await transport.exportPage('{"sessionId":"s","projection":"gps","after":[1]}')).toEqual({
      envelope: '{"cancelled":true}',
      numbers: {},
    });
  });

  describe('streams', () => {
    const request = (after: number | null, key = 'w:1', params: object = { source: { kind: 'preview' } }) =>
      JSON.stringify({ params, after, key });

    it('answers a waiting next with the next revision, whatever the order of its params', async () => {
      const transport = fakeTransport();
      const waiting = transport.next('charts.head', request(null, 'w:1', { source: { kind: 'ride', rideId: 'r' } }));
      transport.emit('charts.head', { source: { rideId: 'r', kind: 'ride' } } as never, { live: true } as never);
      expect(parse(await waiting)).toEqual({ ok: true, revision: 1, value: { live: true }, now: 0 });
    });

    it('answers at once while the stream is ahead of after, and waits once it is not', async () => {
      const transport = fakeTransport();
      transport.emit('settings.state', {}, { first: true } as never);
      transport.emit('settings.state', {}, { second: true } as never);
      expect(parse(await transport.next('settings.state', request(1, 'w:1', {})))).toEqual({
        ok: true,
        revision: 2,
        value: { second: true },
        now: 0,
      });
      let answered = false;
      const later = transport.next('settings.state', request(2, 'w:1', {})).then(text => {
        answered = true;
        return text;
      });
      await Promise.resolve();
      expect(answered).toBe(false);
      transport.setNow(7);
      transport.emit('settings.state', {}, { third: true } as never);
      expect(parse(await later)).toEqual({ ok: true, revision: 3, value: { third: true }, now: 7 });
    });

    it('fails a waiting next, or the next one to arrive', async () => {
      const transport = fakeTransport();
      const waiting = transport.next('recording.state', request(null, 'w:1', {}));
      transport.fail('recording.state', {}, failure);
      expect(parse(await waiting)).toEqual({ ok: false, failure, now: 0 });
      transport.fail('recording.state', {}, failure);
      expect(parse(await transport.next('recording.state', request(null, 'w:2', {})))).toEqual({
        ok: false,
        failure,
        now: 0,
      });
    });

    it('cancels a waiting next by cancel or by a newer next with its key', async () => {
      const transport = fakeTransport();
      const first = transport.next('history.list', request(null, 'w:1', {}));
      const second = transport.next('history.list', request(null, 'w:1', {}));
      expect(parse(await first)).toEqual({ cancelled: true });
      await transport.call('cancel', '{"key":"w:1"}');
      expect(parse(await second)).toEqual({ cancelled: true });
      transport.emit('history.list', {}, { rows: [] } as never);
      expect(transport.calls.map(call => call.op)).toEqual(['history.list', 'history.list', 'cancel']);
    });
  });
});
