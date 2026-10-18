import bridge from '../../modules/power-log-bridge';
import type { MonitorSource, NativeMonitorTarget } from '../core/monitor';
import type { DistanceSource } from '../core/distance';
import { nativeMonotonicClock } from './native-monotonic-clock';
import { nativeRead } from './native-read';

export function nativeMonitorSource(
  source: NativeMonitorTarget['source'],
  id?: string,
  live = source === 'live',
  distanceSource: DistanceSource = 'auto',
): MonitorSource {
  const native = () => {
    if (!bridge?.describeMonitorSource) throw new Error('Install the latest Power Log build to view these charts.');
    return bridge;
  };
  const execute = <R extends { generation: number; signal?: AbortSignal }, T>(
    request: R,
    read: (options: NativeMonitorTarget & Omit<R, 'signal'> & { cancellationId?: string }) => Promise<T>,
  ) => {
    const { signal, ...input } = request;
    const module = native();
    return nativeRead(
      signal,
      cancellationId => read({ ...input, source, id, distanceSource, ...(cancellationId ? { cancellationId } : {}) }),
      module.cancelMonitorRead?.bind(module),
    );
  };
  return {
    key: `${source}:${id ?? 'current'}`,
    live,
    semanticKey: `distance:${distanceSource}`,
    revisionHint: `distance:${distanceSource}`,
    mapMonotonicSeconds: seconds => nativeMonotonicClock.toJS(seconds),
    describeSource: async request => {
      if (live) await nativeMonotonicClock.ready();
      return execute(request, options => native().describeMonitorSource(options));
    },
    readLatest: async request => {
      if (live) await nativeMonotonicClock.ready();
      return execute(request, options => native().readMonitorLatest(options));
    },
    readPlot: request => execute(request, options => native().readMonitorPlot(options)),
    inspectAt: request => execute(request, options => native().inspectMonitorAt(options)),
    rangeStats: request => execute(request, options => native().readMonitorRangeStats(options)),
    changesSince: request => execute(request, options => native().monitorChangesSince(options)),
  };
}
