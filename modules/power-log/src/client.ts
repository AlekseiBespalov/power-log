import {
  API_HASH,
  type ApiFailure,
  type Calls,
  type ExportPage,
  type ExportPageInput,
  type QueryName,
  type Streams,
} from '../../../shared/generated/ts/api';
import { platformTransport, type Transport } from './transport';

export type { ApiFailure, FailureCode, ExportPage, ExportPageInput } from '../../../shared/generated/ts/api';
export type CallName = keyof Calls;
export type CallInput<O extends CallName> = Calls[O]['input'];
export type CallOutput<O extends CallName> = Calls[O]['output'];
export type StreamName = keyof Streams;
export type StreamParams<S extends StreamName> = Streams[S]['params'];
export type StreamValue<S extends StreamName> = Streams[S]['value'];
export type Result<T> = { ok: true; value: T } | { ok: false; failure: ApiFailure } | { cancelled: true };
export interface StreamEvent<T> {
  revision: number;
  value: T;
}
export type Compatibility = 'checking' | 'compatible' | 'incompatible';
export type SectionCalls<C> = { [O in keyof C]: { input: unknown; output: unknown } };
export type SectionStreams<S> = { [N in keyof S]: { params: unknown; value: unknown } };

export interface SectionClient<C extends SectionCalls<C>, S extends SectionStreams<S>, Q extends keyof C = never> {
  call<O extends keyof C & string>(
    op: O,
    input: C[O]['input'],
    options?: O extends Q ? { signal?: AbortSignal } : never,
  ): Promise<Result<C[O]['output']>>;
  subscribe<N extends keyof S & string>(
    stream: N,
    params: S[N]['params'],
    listener: (event: StreamEvent<S[N]['value']>) => void,
    onFailure?: (failure: ApiFailure) => void,
  ): () => void;
}

export interface PowerLogClient {
  readonly compatibility: Compatibility;
  onCompatibility(listener: (state: Compatibility) => void): () => void;
  call<O extends CallName>(
    op: O,
    input: CallInput<O>,
    options?: O extends QueryName ? { signal?: AbortSignal } : never,
  ): Promise<Result<CallOutput<O>>>;
  exportPage(input: ExportPageInput): Promise<Result<ExportPage>>;
  subscribe<S extends StreamName>(
    stream: S,
    params: StreamParams<S>,
    listener: (event: StreamEvent<StreamValue<S>>) => void,
    onFailure?: (failure: ApiFailure) => void,
  ): () => void;
  localTime(monoUs: number): number | null;
  section<C extends SectionCalls<C>, S extends SectionStreams<S>, Q extends keyof C = never>(): SectionClient<C, S, Q>;
}

export const STREAM_RETRY_MS = 1000;

const failureOf = (code: 'unknown' | 'apiMismatch'): ApiFailure => ({
  code,
  retryable: false,
  occurrence: null,
  detail: null,
});
const unknown = failureOf('unknown');
const mismatch = failureOf('apiMismatch');
const cancelled = { cancelled: true } as const;

type Answer =
  { ok: true; value: unknown; revision: number | null } | { ok: false; failure: ApiFailure } | { cancelled: true };

interface Subscriber {
  readonly listener: (event: StreamEvent<unknown>) => void;
  readonly onFailure?: (failure: ApiFailure) => void;
}

function guarded(deliver: () => void) {
  try {
    deliver();
  } catch (error) {
    console.error(error);
  }
}

interface Loop {
  readonly id: string;
  readonly stream: string;
  readonly params: unknown;
  readonly key: string;
  readonly subscribers: Set<Subscriber>;
  after: number | null;
  cached: StreamEvent<unknown> | null;
  retry: ReturnType<typeof setTimeout> | undefined;
  stopped: boolean;
}

function canonical(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(canonical);
  if (value === null || typeof value !== 'object') return value;
  const object = value as Record<string, unknown>;
  return Object.fromEntries(
    Object.keys(object)
      .sort()
      .map(key => [key, canonical(object[key])]),
  );
}

let client: PowerLogClient | undefined;

export function powerLogClient(): PowerLogClient {
  client ??= createPowerLogClient(platformTransport());
  return client;
}

export function createPowerLogClient(transport: Transport): PowerLogClient {
  let compatibility: Compatibility = 'checking';
  let anchor: { now: number; receivedAt: number } | null = null;
  let counter = 0;
  const waiting: (() => void)[] = [];
  const compatibilityListeners = new Set<(state: Compatibility) => void>();
  const loops = new Map<string, Loop>();

  const answerOf = (text: string): Answer => {
    let parsed: unknown;
    try {
      parsed = JSON.parse(text);
    } catch {
      return { ok: false, failure: unknown };
    }
    if (parsed === null || typeof parsed !== 'object') return { ok: false, failure: unknown };
    const envelope = parsed as Record<string, unknown>;
    if (envelope.cancelled === true) return cancelled;
    if (typeof envelope.now === 'number') anchor = { now: envelope.now, receivedAt: performance.now() };
    if (envelope.ok === true)
      return {
        ok: true,
        value: envelope.value,
        revision: typeof envelope.revision === 'number' ? envelope.revision : null,
      };
    if (envelope.ok === false && envelope.failure !== null && typeof envelope.failure === 'object')
      return { ok: false, failure: envelope.failure as ApiFailure };
    return { ok: false, failure: unknown };
  };

  const resultOf = <T>(answer: Answer): Result<T> => {
    if ('cancelled' in answer) return cancelled;
    if (answer.ok) return { ok: true, value: answer.value as T };
    return { ok: false, failure: answer.failure };
  };

  const settle = (state: Compatibility) => {
    compatibility = state;
    for (const listener of [...compatibilityListeners]) guarded(() => listener(state));
    for (const run of waiting.splice(0)) run();
  };

  const whenSettled = (run: () => void) => {
    if (compatibility === 'checking') waiting.push(run);
    else run();
  };

  const send = (op: string, input: unknown): Promise<Answer> => {
    try {
      return transport.call(op, JSON.stringify(input)).then(answerOf, () => ({ ok: false, failure: unknown }));
    } catch {
      return Promise.resolve({ ok: false, failure: unknown });
    }
  };

  const cancel = (key: string) => {
    void send('cancel', { key });
  };

  const deliverFailure = (loop: Loop, failure: ApiFailure) => {
    for (const subscriber of [...loop.subscribers]) guarded(() => subscriber.onFailure?.(failure));
  };

  const end = (loop: Loop) => {
    loop.stopped = true;
    clearTimeout(loop.retry);
    if (loops.get(loop.id) === loop) loops.delete(loop.id);
  };

  const next = (loop: Loop) => {
    if (loop.stopped) return;
    const request = JSON.stringify({ params: loop.params, after: loop.after, key: loop.key });
    let pending: Promise<string>;
    try {
      pending = transport.next(loop.stream, request);
    } catch {
      pending = Promise.reject(new Error('The transport threw.'));
    }
    void pending
      .then(answerOf, (): Answer => ({ ok: false, failure: unknown }))
      .then(answer => {
        if (loop.stopped) return;
        if ('cancelled' in answer) end(loop);
        else if (answer.ok) {
          const event = { revision: answer.revision ?? (loop.after ?? 0) + 1, value: answer.value };
          loop.after = event.revision;
          loop.cached = event;
          for (const subscriber of [...loop.subscribers]) guarded(() => subscriber.listener(event));
          next(loop);
        } else if (answer.failure.retryable) {
          deliverFailure(loop, answer.failure);
          if (!loop.stopped) loop.retry = setTimeout(() => next(loop), STREAM_RETRY_MS);
        } else {
          end(loop);
          deliverFailure(loop, answer.failure);
        }
      });
  };

  const join = (stream: string, params: unknown, subscriber: Subscriber) => {
    const sorted = canonical(params);
    const id = `${stream} ${JSON.stringify(sorted)}`;
    const running = loops.get(id);
    if (running) {
      running.subscribers.add(subscriber);
      const cached = running.cached;
      if (cached)
        queueMicrotask(() => {
          if (running.subscribers.has(subscriber)) guarded(() => subscriber.listener(cached));
        });
      return running;
    }
    const loop: Loop = {
      id,
      stream,
      params: sorted,
      key: `w:${++counter}`,
      subscribers: new Set([subscriber]),
      after: null,
      cached: null,
      retry: undefined,
      stopped: false,
    };
    loops.set(id, loop);
    next(loop);
    return loop;
  };

  const call = (op: string, input: unknown, options?: { signal?: AbortSignal }): Promise<Result<unknown>> => {
    const signal = options?.signal;
    return new Promise(resolve => {
      if (signal?.aborted) return resolve(cancelled);
      let sent = false;
      const abort = () => {
        resolve(cancelled);
        const key = (input as { key?: unknown }).key;
        if (sent && typeof key === 'string') cancel(key);
      };
      signal?.addEventListener('abort', abort, { once: true });
      whenSettled(() => {
        if (signal?.aborted) return;
        if (compatibility === 'incompatible') {
          signal?.removeEventListener('abort', abort);
          return resolve({ ok: false, failure: mismatch });
        }
        sent = true;
        void send(op, input).then(answer => {
          signal?.removeEventListener('abort', abort);
          resolve(resultOf(answer));
        });
      });
    });
  };

  const subscribe = (
    stream: string,
    params: unknown,
    listener: (event: StreamEvent<unknown>) => void,
    onFailure?: (failure: ApiFailure) => void,
  ): (() => void) => {
    const subscriber: Subscriber = { listener, onFailure };
    let loop: Loop | null = null;
    let active = true;
    whenSettled(() => {
      if (!active) return;
      if (compatibility === 'incompatible') guarded(() => onFailure?.(mismatch));
      else loop = join(stream, params, subscriber);
    });
    return () => {
      if (!active) return;
      active = false;
      const joined = loop as Loop | null;
      if (!joined) return;
      joined.subscribers.delete(subscriber);
      if (joined.subscribers.size > 0 || joined.stopped) return;
      end(joined);
      cancel(joined.key);
    };
  };

  const checked = send('apiVersion', {});
  void checked.then(answer => {
    const hash = 'ok' in answer && answer.ok ? (answer.value as { hash?: unknown } | null)?.hash : undefined;
    settle(hash === API_HASH ? 'compatible' : 'incompatible');
  });

  return {
    get compatibility() {
      return compatibility;
    },

    onCompatibility(listener) {
      compatibilityListeners.add(listener);
      return () => {
        compatibilityListeners.delete(listener);
      };
    },

    call: call as PowerLogClient['call'],

    exportPage(input) {
      return new Promise(resolve => {
        whenSettled(() => {
          if (compatibility === 'incompatible') return resolve({ ok: false, failure: mismatch });
          let pending: ReturnType<Transport['exportPage']>;
          try {
            pending = transport.exportPage(JSON.stringify(input));
          } catch {
            return resolve({ ok: false, failure: unknown });
          }
          pending.then(
            page => {
              const result = resultOf<ExportPage>(answerOf(page.envelope));
              if (!('ok' in result) || !result.ok) return resolve(result);
              resolve({ ok: true, value: { ...result.value, columns: { ...result.value.columns, ...page.numbers } } });
            },
            () => resolve({ ok: false, failure: unknown }),
          );
        });
      });
    },

    subscribe: subscribe as PowerLogClient['subscribe'],

    localTime(monoUs) {
      if (!anchor) return null;
      return anchor.receivedAt + (monoUs - anchor.now) / 1000;
    },

    section: <C extends SectionCalls<C>, S extends SectionStreams<S>, Q extends keyof C = never>() =>
      ({ call, subscribe }) as unknown as SectionClient<C, S, Q>,
  };
}
