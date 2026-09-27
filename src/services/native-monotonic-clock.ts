import bridge from '../../modules/cyc-bridge';
import { subscribeAppVisibility } from './app-visibility';
import { MonotonicClock } from './monotonic-clock';

export const nativeMonotonicClock = new MonotonicClock(() => {
  if (!bridge) return Promise.reject(new Error('Native clock is unavailable.'));
  return bridge.getMonotonicSeconds();
});

let subscribers = 0;
let release: (() => void) | undefined;
export function subscribeNativeClock(): () => void {
  const sync = () => void nativeMonotonicClock.sync().catch(() => {});
  if (!subscribers) nativeMonotonicClock.invalidate();
  sync();
  if (subscribers++ === 0) {
    const timer = setInterval(sync, 60_000);
    const unsubscribe = subscribeAppVisibility(active => {
      if (active) {
        nativeMonotonicClock.invalidate();
        sync();
      }
    });
    release = () => {
      clearInterval(timer);
      unsubscribe();
    };
  }
  return () => {
    if (--subscribers === 0) {
      release?.();
      release = undefined;
    }
  };
}
