import type { MonitorSource, NativeMonitorTarget } from '../core/monitor';
export function nativeMonitorSource(source: NativeMonitorTarget['source'], id?: string, live = false, _distanceSource?: import('../core/distance').DistanceSource): MonitorSource {
  const unavailable = async (): Promise<never> => { throw new Error('Open this ride on the iPhone that recorded it.'); };
  return { key: `${source}:${id ?? 'current'}`, live, describeSource: unavailable, readLatest: unavailable, readPlot: unavailable, inspectAt: unavailable, rangeStats: unavailable, changesSince: unavailable };
}
