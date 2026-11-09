import { API_HASH, type ApiFailure, type ExportPage, type ExportPageInput } from '../../../../shared/generated/ts/api';
import type { CallInput, CallName, CallOutput, Result, StreamName, StreamParams, StreamValue } from '../client';
import type { BinaryPage, Transport } from '../transport';

export interface FakeTransport extends Transport {
  answer<O extends CallName>(
    op: O,
    reply: (input: CallInput<O>) => Result<CallOutput<O>> | Promise<Result<CallOutput<O>>>,
  ): void;
  answerPages(reply: (input: ExportPageInput) => Result<ExportPage> | Promise<Result<ExportPage>>): void;
  emit<S extends StreamName>(stream: S, params: StreamParams<S>, value: StreamValue<S>): void;
  fail<S extends StreamName>(stream: S, params: StreamParams<S>, failure: ApiFailure): void;
  setNow(monoUs: number): void;
  readonly calls: readonly { op: string; input: unknown }[];
}

const OTHER_HASH = '0000000000000000';
const CANCELLED = JSON.stringify({ cancelled: true });

type Reply = (input: never) => Result<unknown> | Promise<Result<unknown>>;
type Settle = (envelope: string) => void;

interface Waiting {
  readonly after: number | null;
  readonly settle: Settle;
}

interface Feed {
  revision: number;
  value: unknown;
  failure: ApiFailure | null;
  readonly waiting: Set<Waiting>;
}

function sorted(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(sorted);
  if (value === null || typeof value !== 'object') return value;
  const object = value as Record<string, unknown>;
  return Object.fromEntries(
    Object.keys(object)
      .sort()
      .map(key => [key, sorted(object[key])]),
  );
}

const feedKey = (stream: string, params: unknown) => `${stream} ${JSON.stringify(sorted(params))}`;

function keyOf(input: unknown): string | undefined {
  const key = (input as { key?: unknown } | null)?.key;
  return typeof key === 'string' ? key : undefined;
}

function once(resolve: Settle): Settle {
  let settled = false;
  return envelope => {
    if (settled) return;
    settled = true;
    resolve(envelope);
  };
}

export function fakeTransport(options: { compatible?: boolean } = {}): FakeTransport {
  const replies = new Map<string, Reply>();
  const feeds = new Map<string, Feed>();
  const inFlight = new Map<string, Settle>();
  const calls: { op: string; input: unknown }[] = [];
  let pages: ((input: ExportPageInput) => Result<ExportPage> | Promise<Result<ExportPage>>) | undefined;
  let now = 0;

  const envelope = (result: Result<unknown>): string => {
    if ('cancelled' in result) return CANCELLED;
    if (result.ok) return JSON.stringify({ ok: true, value: result.value, now });
    return JSON.stringify({ ok: false, failure: result.failure, now });
  };

  const track = (key: string | undefined, resolve: Settle): Settle => {
    const settle = once(resolve);
    if (key === undefined) return settle;
    inFlight.get(key)?.(CANCELLED);
    const own: Settle = text => {
      if (inFlight.get(key) === own) inFlight.delete(key);
      settle(text);
    };
    inFlight.set(key, own);
    return own;
  };

  const feed = (stream: string, params: unknown): Feed => {
    const key = feedKey(stream, params);
    let found = feeds.get(key);
    if (!found) {
      found = { revision: 0, value: undefined, failure: null, waiting: new Set() };
      feeds.set(key, found);
    }
    return found;
  };

  const record = (op: string, request: string): unknown => {
    const input: unknown = JSON.parse(request);
    calls.push({ op, input });
    return input;
  };

  return {
    calls,

    answer(op, reply) {
      replies.set(op, reply as Reply);
    },

    answerPages(reply) {
      pages = reply;
    },

    setNow(monoUs) {
      now = monoUs;
    },

    emit(stream, params, value) {
      const target = feed(stream, params);
      target.revision++;
      target.value = value;
      for (const waiting of target.waiting)
        if (waiting.after === null || waiting.after < target.revision)
          waiting.settle(JSON.stringify({ ok: true, revision: target.revision, value, now }));
    },

    fail(stream, params, failure) {
      const target = feed(stream, params);
      const [waiting] = target.waiting;
      if (waiting) waiting.settle(JSON.stringify({ ok: false, failure, now }));
      else target.failure = failure;
    },

    call(op, request) {
      const input = record(op, request);
      if (op === 'apiVersion') {
        const hash = options.compatible === false ? OTHER_HASH : API_HASH;
        return Promise.resolve(JSON.stringify({ ok: true, value: { hash }, now }));
      }
      if (op === 'cancel') {
        const key = keyOf(input);
        if (key !== undefined) inFlight.get(key)?.(CANCELLED);
        return Promise.resolve(JSON.stringify({ ok: true, value: null, now }));
      }
      const reply = replies.get(op);
      if (!reply) return Promise.reject(new Error(`The fake transport has no answer for ${op}.`));
      return new Promise<string>((resolve, reject) => {
        const settle = track(keyOf(input), resolve);
        Promise.resolve()
          .then(() => reply(input as never))
          .then(result => settle(envelope(result)), reject);
      });
    },

    next(stream, request) {
      const input = record(stream, request) as { params: unknown; after: number | null; key: string };
      const target = feed(stream, input.params);
      return new Promise<string>(resolve => {
        let waiting: Waiting | undefined;
        const settle = track(input.key, text => {
          if (waiting) target.waiting.delete(waiting);
          resolve(text);
        });
        if (target.failure) {
          const failure = target.failure;
          target.failure = null;
          settle(JSON.stringify({ ok: false, failure, now }));
        } else if (target.revision > 0 && (input.after === null || input.after < target.revision))
          settle(JSON.stringify({ ok: true, revision: target.revision, value: target.value, now }));
        else {
          waiting = { after: input.after, settle };
          target.waiting.add(waiting);
        }
      });
    },

    async exportPage(request) {
      const input = record('exports.page', request) as ExportPageInput;
      if (!pages) throw new Error('The fake transport has no answer for exports.page.');
      const result = await pages(input);
      if ('cancelled' in result || !result.ok) return { envelope: envelope(result), numbers: {} };
      const { columns, ...page } = result.value;
      const texts: Record<string, (string | null)[]> = {};
      const numbers: BinaryPage['numbers'] = {};
      for (const [name, column] of Object.entries(columns)) {
        if (column instanceof Uint8Array) numbers[name] = new Uint8Array(column);
        else texts[name] = column;
      }
      return { envelope: JSON.stringify({ ok: true, value: { ...page, columns: texts }, now }), numbers };
    },
  };
}
