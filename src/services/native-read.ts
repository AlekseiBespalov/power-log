let readSequence = 0;
const processId = `${Date.now()}:${Math.random().toString(36).slice(2)}`;

export async function nativeRead<T>(
  signal: AbortSignal | undefined,
  execute: (cancellationId?: string) => Promise<T>,
  cancel?: (cancellationId: string) => Promise<void> | void,
): Promise<T> {
  if (signal?.aborted) throw new Error('Read cancelled.');
  const cancellationId = cancel ? `${processId}:${++readSequence}` : undefined;
  const abort = () => {
    if (cancellationId && cancel) void Promise.resolve(cancel(cancellationId)).catch(() => {});
  };
  signal?.addEventListener('abort', abort, { once: true });
  try {
    return await execute(cancellationId);
  } finally {
    signal?.removeEventListener('abort', abort);
  }
}
