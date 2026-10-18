import { readFileSync } from 'node:fs';
import path from 'node:path';
import { beforeAll, expect, it, vi } from 'vitest';
import { normalizeWorkoutState, type WorkoutState } from '../../src/core/workouts';
import { normalizeWorkoutDetail, normalizeWorkoutMetadata } from '../../src/core/workout-payload';
import { WorkoutStateDelivery } from '../../src/services/workout-state-delivery';

let directory: string;
let status: number | null;
let log: string;
let output: {
  states: Parameters<typeof normalizeWorkoutState>[0][];
  detail: { metadata: unknown };
  watchRunning: Parameters<typeof normalizeWorkoutState>[0][];
};
const supplied = process.env.POWER_LOG_BRIDGE_PROJECTION;
beforeAll(() => {
  if (!supplied) return;
  directory = supplied;
  status = Number(readFileSync(path.join(directory, 'status'), 'utf8'));
  log = readFileSync(path.join(directory, 'log'), 'utf8');
  output = JSON.parse(readFileSync(path.join(directory, 'projection.json'), 'utf8'));
});

it.skipIf(!supplied)('normalizes the real Swift fixture snapshots and stored metadata without coercion', () => {
  const states = output.states.map(normalizeWorkoutState);
  expect(states.length).toBe(8);
  expect(states.find(state => state.healthKitState === 'unavailable')?.healthReason).toBe('clock');
  expect(normalizeWorkoutMetadata(output.detail.metadata).eventCount).toBe('5');
});
it.skipIf(!supplied)('normalizes the real Swift summary and requires every native projection assertion to pass', () => {
  const detail = normalizeWorkoutDetail(output.detail);
  expect(detail.summary.lifecycleCount).toBe('3');
  expect(detail.summary.telemetryCount).toBe('2');
  expect(detail.summary.lapCount).toBeTypeOf('number');
  expect(status, log).toBe(0);
});
it.skipIf(!supplied)(
  'delivers the real running Watch projection at most once a second as its revision advances',
  async () => {
    vi.useFakeTimers();
    const displayed: WorkoutState[] = [];
    const delivery = new WorkoutStateDelivery(value => displayed.push(value));
    try {
      const states = output.watchRunning.map(normalizeWorkoutState);
      expect(new Set(states.map(state => state.collectionRevision)).size).toBe(32);
      for (const state of states) {
        delivery.receive(state);
        await vi.advanceTimersByTimeAsync(125);
      }
      expect(displayed.length).toBeLessThanOrEqual(5);
    } finally {
      delivery.setActive(false);
      vi.useRealTimers();
    }
  },
);
