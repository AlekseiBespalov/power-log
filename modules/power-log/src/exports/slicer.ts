import { ExportError } from './types';

export const EXPORT_SLICE_MS = 40;

export interface ExportSlicer {
  tick(): Promise<void>;
}

const ready = Promise.resolve();
const now = () => (typeof performance === 'undefined' ? Date.now() : performance.now());

export const exportLeftScreen = () =>
  new ExportError('exportLeftScreen', 'The export stopped because Power Log left the screen. Export again.');

// Yields to the event loop once a slice has run its budget, so a long export never blocks the UI for longer.
export function createSlicer(signal?: AbortSignal, budgetMs = EXPORT_SLICE_MS): ExportSlicer {
  let started = now();
  const pause = async () => {
    await new Promise<void>(resolve => setTimeout(resolve, 0));
    started = now();
    if (signal?.aborted) throw exportLeftScreen();
  };
  return {
    tick() {
      if (signal?.aborted) return Promise.reject(exportLeftScreen());
      return now() - started < budgetMs ? ready : pause();
    },
  };
}
