import { useCallback, useEffect, useRef, useState } from 'react';
import { ReadCancelled, readConsumer, readPressure, reads } from './read-scheduler';
import { useForegroundActivity } from './use-foreground-activity';

/** Keep a useful completed summary while one newest replacement waits globally. */
export function useReadResource<T>(identity: string | null, revision: string | null, load: (consumer: string, current: () => boolean) => Promise<T>) {
  const active = useForegroundActivity();
  const [consumer] = useState(() => readConsumer('summary'));
  const operation = useRef(load);
  const [state, setState] = useState<{ identity: string | null; revision: string | null; data?: T; error?: string; errorCode?: string; loading: boolean }>({ identity: null, revision: null, loading: false });
  const clearError = useCallback(() => setState(previous => ({ ...previous, error: undefined, errorCode: undefined })), []);
  useEffect(() => { operation.current = load; }, [load]);
  useEffect(() => {
    if (!identity || !active) return;
    let current = true; let retry: ReturnType<typeof setTimeout> | undefined;
    const loadCurrent = operation.current;
    const run = () => {
      setState(previous => ({ ...(previous.identity === identity ? previous : { identity, revision: null }), loading: true }));
      void reads.schedule('summary', consumer, 'summary', () => loadCurrent(consumer, () => current)).then(data => {
        if (current) setState({ identity, revision, data, loading: false });
      }, error => {
        if (!current || error instanceof ReadCancelled) return;
        if (readPressure(error)) { retry = setTimeout(run, 1000); return; }
        const errorCode = typeof error === 'object' && error !== null && 'code' in error && typeof error.code === 'string' ? error.code : undefined;
        setState(previous => ({ ...previous, ...(errorCode === 'ERR_RIDE_DELETED' ? { data: undefined } : {}),
          error: error instanceof Error ? error.message : String(error), errorCode, loading: false }));
      });
    };
    run();
    return () => { current = false; if (retry) clearTimeout(retry); reads.cancel(consumer); };
  }, [active, consumer, identity, revision]);
  const matches = state.identity === identity;
  return { data: matches ? state.data : undefined, error: matches ? state.error : undefined, errorCode: matches ? state.errorCode : undefined,
    loading: active && identity !== null && (!matches || state.loading || (!state.error && state.revision !== revision)),
    current: state.identity === identity && state.revision === revision,
    clearError };
}
