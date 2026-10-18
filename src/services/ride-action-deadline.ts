import { presentationWait, presentationWaitDeadline } from '../../shared/js';
import { RideFailureError } from '../core/failures';

export function waitForRideAction<T>(
  operation: () => Promise<T>,
  kind: 'start' | 'recovery',
  now = () => performance.now() / 1000,
  signal?: AbortSignal,
): Promise<T> {
  const beganAtUs = String(Math.floor(now() * 1_000_000));
  const deadline = Number(presentationWaitDeadline(kind, beganAtUs)) / 1_000_000;
  return new Promise((resolve, reject) => {
    let timer: ReturnType<typeof setTimeout>;
    let settled = false;
    const finish = (work: () => void) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      signal?.removeEventListener('abort', abort);
      work();
    };
    const abort = () =>
      finish(() => reject(new RideFailureError(kind === 'start' ? 'startUnconfirmed' : 'ownerUnconfirmed')));
    const check = () => {
      const decision = presentationWait(kind, beganAtUs, String(Math.floor(now() * 1_000_000)));
      if (decision.pending) timer = setTimeout(check, Math.max(1, (deadline - now()) * 1000));
      else finish(() => reject(new RideFailureError(decision.error)));
    };
    if (signal?.aborted) {
      abort();
      return;
    }
    signal?.addEventListener('abort', abort, { once: true });
    timer = setTimeout(check, Math.max(1, (deadline - now()) * 1000));
    Promise.resolve()
      .then(operation)
      .then(
        value =>
          finish(() => {
            const decision = presentationWait(kind, beganAtUs, String(Math.floor(now() * 1_000_000)));
            if (decision.pending) resolve(value);
            else reject(new RideFailureError(decision.error));
          }),
        error => finish(() => reject(error)),
      );
  });
}
