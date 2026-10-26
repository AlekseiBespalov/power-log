import * as hitTest from '../../src/core/monitor-hit-test.ts';
import { count, counts } from './monitor-render-counts';

export const { admitMonitorHitScene, hitMonitorScene, monitorHitTargets, MonitorHitAdmission } = hitTest;

export function monitorHitScene(...args: Parameters<typeof hitTest.monitorHitScene>) {
  count(counts.scenes, args[1].id);
  return hitTest.monitorHitScene(...args);
}
