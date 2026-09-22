import type { MonitorSnap } from '../../core/monitor-hit-test';
import { useCallback, useEffect, useLayoutEffect, useState } from 'react';
import type { ChartViewport } from '../../core/chart-viewport';
import type { MonitorRange, MonitorSource } from '../../core/monitor';
import { MonitorReadController } from './monitor-read-controller';
import { useForegroundActivity } from '../../services/use-foreground-activity';
export { rangeViewport } from './monitor-read-controller';

export function useMonitorData(source: MonitorSource, metrics: readonly string[], range: MonitorRange, pixelWidth = 360) {
  const [controller] = useState(() => new MonitorReadController(source));
  const active = useForegroundActivity();
  const [state, setState] = useState(() => controller.getSnapshot());
  useEffect(() => { const unsubscribe = controller.subscribe(setState); return () => { unsubscribe(); controller.dispose(); }; }, [controller]);
  useLayoutEffect(() => { controller.updateSource(source); }, [controller, source]);
  useEffect(() => { controller.configure(metrics, range, pixelWidth); }, [controller, metrics, range, pixelWidth]);
  useEffect(() => {
    if (!active) { controller.deactivate(); return; }
    controller.activate(); controller.refresh();
    const timer = setInterval(() => { if (source.live || controller.getSnapshot().description?.outcome === 'pending' || Object.values(controller.getSnapshot().deferred).some(Boolean)) controller.refresh(); }, source.live ? 750 : 3000);
    const clock = source.live ? setInterval(() => controller.tick(), 250) : null;
    return () => { clearInterval(timer); if (clock) clearInterval(clock); controller.deactivate(); };
  }, [active, controller, source.key, source.live]);
  useEffect(() => { if (active) controller.refresh(); }, [active, controller, source.revisionHint, source.semanticKey]);
  const inspect = useCallback((seconds: number | null, interacting = false, snap?: MonitorSnap | null) => controller.inspect(seconds, interacting, snap), [controller]);
  const setReference = useCallback((seconds: number | null) => controller.setReference(seconds), [controller]);
  const changeViewport = useCallback((next: ChartViewport, interacting = false) => controller.changeViewport(next, interacting), [controller]);
  return { ...state, error: Object.values(state.errors).find(Boolean) ?? null,
    inspect, setReference, changeViewport, resume: () => controller.resume(), refresh: () => controller.refresh() };
}
