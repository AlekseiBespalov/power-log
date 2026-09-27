import bridge from '../../modules/cyc-bridge';
import type { MonitorSource, NativeMonitorTarget } from '../core/monitor';
import type { DistanceSource } from '../core/distance';
import { nativeMonotonicClock } from './native-monotonic-clock';

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
  return {
    key: `${source}:${id ?? 'current'}`,
    live,
    semanticKey: `distance:${distanceSource}`,
    revisionHint: `distance:${distanceSource}`,
    mapMonotonicSeconds: seconds => nativeMonotonicClock.toJS(seconds),
    describeSource: async request => {
      if (live) await nativeMonotonicClock.ready();
      return native().describeMonitorSource({ ...request, source, id, distanceSource });
    },
    readLatest: async request => {
      if (live) await nativeMonotonicClock.ready();
      return native().readMonitorLatest({ ...request, source, id, distanceSource });
    },
    readPlot: request => native().readMonitorPlot({ ...request, source, id, distanceSource }),
    inspectAt: request => native().inspectMonitorAt({ ...request, source, id, distanceSource }),
    rangeStats: request => native().readMonitorRangeStats({ ...request, source, id, distanceSource }),
    changesSince: request => native().monitorChangesSince({ ...request, source, id, distanceSource }),
  };
}
