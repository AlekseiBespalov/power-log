import * as hitTest from '../../src/components/charts/hit-test.ts';
import { count, counts } from './monitor-render-counts';

export const { admitHitScene, hitChart, hitTargets, HitAdmission } = hitTest;

export function buildHitScene(...args: Parameters<typeof hitTest.buildHitScene>) {
  for (const metric of args[0].metrics) count(counts.scenes, metric.id);
  return hitTest.buildHitScene(...args);
}
