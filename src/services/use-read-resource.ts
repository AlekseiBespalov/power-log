import { useCallback, useEffect, useRef, useState } from 'react';
import { failureCode, presentFailure } from '../core/failures';
import { ReadCancelled, readConsumer, readPressure, reads } from './read-scheduler';
import { useForegroundActivity } from './use-foreground-activity';
import { RetryBackoff } from './retry-backoff';

export function useReadResource<T>(
  identity: string | null,
  revision: string | null,
  load: (consumer: string, current: () => boolean, signal?: AbortSignal) => Promise<T>,
) {
  const active = useForegroundActivity();
  const [consumer] = useState(() => readConsumer('summary'));
  const operation = useRef(load);
  const [state, setState] = useState<{
    identity: string | null;
    revision: string | null;
    data?: T;
    error?: string;
    errorCode?: string;
    loading: boolean;
  }>({ identity: null, revision: null, loading: false });
  const clearError = useCallback(
    () => setState(previous => ({ ...previous, error: undefined, errorCode: undefined })),
    [],
  );
  useEffect(() => {
    operation.current = load;
  }, [load]);
  useEffect(() => {
    if (!identity || !active) return;
    let current = true;
    const backoff = new RetryBackoff();
    let retry: ReturnType<typeof setTimeout> | undefined;
    const loadCurrent = operation.current;
    const run = () => {
      setState(previous => ({
        ...(previous.identity === identity ? previous : { identity, revision: null }),
        loading: true,
      }));
      void reads
        .schedule('summary', consumer, 'summary', signal => loadCurrent(consumer, () => current, signal))
        .then(
          data => {
            if (current) setState({ identity, revision, data, loading: false });
          },
          error => {
            if (!current || error instanceof ReadCancelled) return;
            if (readPressure(error)) {
              retry = setTimeout(run, backoff.next());
              return;
            }
            const errorCode = failureCode(error);
            setState(previous => ({
              ...previous,
              ...(errorCode === 'rideDeleted' ? { data: undefined } : {}),
              error: presentFailure(error).text,
              errorCode,
              loading: false,
            }));
          },
        );
    };
    run();
    return () => {
      current = false;
      if (retry) clearTimeout(retry);
      reads.cancel(consumer);
    };
  }, [active, consumer, identity, revision]);
  const matches = state.identity === identity;
  return {
    data: matches ? state.data : undefined,
    error: matches ? state.error : undefined,
    errorCode: matches ? state.errorCode : undefined,
    loading:
      active && identity !== null && (!matches || state.loading || (!state.error && state.revision !== revision)),
    current: state.identity === identity && state.revision === revision,
    clearError,
  };
}
