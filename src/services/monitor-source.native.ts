import bridge from '../../modules/cyc-bridge';
import type { MonitorSource, NativeMonitorTarget } from '../core/monitor';
import type { DistanceSource } from '../core/distance';

export function nativeMonitorSource(source: NativeMonitorTarget['source'], id?: string, live = source === 'live', distanceSource: DistanceSource = 'auto'): MonitorSource {
  const native = () => {
    if (!bridge?.describeMonitorSource) throw new Error('Install the latest Power Log build to view these charts.');
    return bridge;
  };
  return {
    key: `${source}:${id ?? 'current'}`, live, semanticKey: `distance:v1:${distanceSource}`, revisionHint: `distance:v1:${distanceSource}`,
    describeSource: request => native().describeMonitorSource({ ...request, source, id, distanceSource }),
    readLatest: request => native().readMonitorLatest({ ...request, source, id, distanceSource }),
    readPlot: request => native().readMonitorPlot({ ...request, source, id, distanceSource }),
    inspectAt: request => native().inspectMonitorAt({ ...request, source, id, distanceSource }),
    rangeStats: request => native().readMonitorRangeStats({ ...request, source, id, distanceSource }),
    changesSince: request => native().monitorChangesSince({ ...request, source, id, distanceSource }),
  };
}
