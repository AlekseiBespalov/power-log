import { readFileSync } from 'node:fs';
import { describe, expect, it, vi } from 'vitest';
import { API_HASH, type ApiFailure, type ExportPage } from '../../shared/generated/ts/api';
import { createPowerLogClient, type StreamEvent } from '../../modules/power-log/src/client';
import type { BinaryPage, Transport } from '../../modules/power-log/src/transport';

vi.mock('../../modules/power-log/src/native-module', () => ({ default: null }));

type Envelope =
  { ok: true; value: unknown; now: number; revision?: number } | { ok: false; failure: ApiFailure; now: number };

const fixture = <T>(name: string): T[] =>
  JSON.parse(readFileSync(`tests/fixtures/contract/api/${name}.json`, 'utf8')) as T[];
const compatible = JSON.stringify({ ok: true, value: { hash: API_HASH }, now: 0 });

const silent = () => new Promise<string>(() => undefined);

function transport(
  answer: (name: string, request: string) => string | Promise<string>,
  page?: BinaryPage,
): Transport & { sent: string[] } {
  const sent: string[] = [];
  return {
    sent,
    call: async (op, request) => {
      sent.push(`${op} ${request}`);
      return op === 'apiVersion' ? compatible : answer(op, request);
    },
    next: async (stream, request) => {
      sent.push(`${stream} ${request}`);
      return answer(stream, request);
    },
    exportPage: async request => {
      sent.push(`exports.page ${request}`);
      if (!page) throw new Error('No page');
      return page;
    },
  };
}

describe('the API fixtures through the client', () => {
  it.each(fixture<Envelope & { ok: true }>('ok'))('resolves ok envelope %# without its now', async envelope => {
    const client = createPowerLogClient(transport(() => JSON.stringify(envelope)));
    const result = await client.call('history.page', { after: null, key: 'k' });
    expect(result).toEqual({ ok: true, value: envelope.value });
    expect(client.localTime(envelope.now)! - client.localTime(envelope.now - 2000)!).toBeCloseTo(2, 9);
  });

  it.each(fixture<Envelope & { ok: true }>('ok-unit'))('resolves Unit answer %# as null', async envelope => {
    const client = createPowerLogClient(transport(() => JSON.stringify(envelope)));
    expect(await client.call('connection.disconnect', {})).toEqual({ ok: true, value: null });
  });

  it.each(fixture<Envelope & { ok: true }>('failed'))(
    'resolves failed envelope %# with its failure',
    async envelope => {
      const client = createPowerLogClient(transport(() => JSON.stringify(envelope)));
      expect(await client.call('history.delete', { rideId: 'r' })).toEqual({
        ok: false,
        failure: (envelope as unknown as { failure: ApiFailure }).failure,
      });
    },
  );

  it.each(fixture<{ cancelled: true }>('cancelled'))('resolves cancelled envelope %# as cancelled', async envelope => {
    const client = createPowerLogClient(transport(() => JSON.stringify(envelope)));
    expect(await client.call('history.page', { after: null, key: 'k' })).toEqual({
      cancelled: true,
    });
  });

  it.each(fixture<Envelope & { ok: true; revision: number }>('next-ok'))(
    'delivers next answer %# as a stream event',
    async envelope => {
      let answered = false;
      const client = createPowerLogClient(
        transport(() => {
          if (answered) return silent();
          answered = true;
          return JSON.stringify(envelope);
        }),
      );
      const event = await new Promise<StreamEvent<unknown>>(resolve => {
        const stop = client.subscribe('recording.state', {}, value => {
          stop();
          resolve(value);
        });
      });
      expect(event).toEqual({ revision: envelope.revision, value: envelope.value });
    },
  );

  it.each(fixture<{ params: { source: unknown }; after: null; key: string }>('next-request'))(
    'sends next request %# with params, after and key in order',
    async request => {
      const wire = transport(silent);
      const client = createPowerLogClient(wire);
      const stop = client.subscribe('charts.head', request.params as never, () => undefined);
      await vi.waitFor(() => expect(wire.sent.some(line => line.startsWith('charts.head '))).toBe(true));
      stop();
      const sent = JSON.parse(wire.sent.find(line => line.startsWith('charts.head '))!.slice('charts.head '.length));
      expect(Object.keys(sent)).toEqual(['params', 'after', 'key']);
      expect(sent).toEqual({ params: request.params, after: null, key: expect.stringMatching(/^w:\d+$/) });
    },
  );

  it.each(fixture<{ envelope: string; numbers: Record<string, string> }>('export-page'))(
    'merges export page %# with its numeric columns',
    async page => {
      const numbers = Object.fromEntries(
        Object.entries(page.numbers).map(([name, base64]) => [name, new Uint8Array(Buffer.from(base64, 'base64'))]),
      );
      const client = createPowerLogClient(transport(() => compatible, { envelope: page.envelope, numbers }));
      const result = await client.exportPage({ sessionId: 's', projection: 'telemetry', after: null });
      const value = (JSON.parse(page.envelope) as { value: ExportPage }).value;
      expect(result).toMatchObject({ ok: true, value: { ...value, columns: { ...value.columns, ...numbers } } });
      const merged = (result as { value: ExportPage }).value.columns.humanPowerW as Uint8Array;
      expect(merged.byteOffset).toBe(0);
      expect(Array.from(new Float64Array(merged.buffer, 0, value.rows))).toEqual([1.5, NaN, 3]);
    },
  );
});
