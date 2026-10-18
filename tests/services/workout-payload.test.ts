import { beforeEach, expect, it, vi } from 'vitest';
import { workouts } from '../../src/services/workouts.native';
import {
  normalizeWorkoutState,
  unavailableWorkoutState,
  workoutCanDelete,
  workoutExportReady,
} from '../../src/core/workouts';
import { normalizeWorkoutMetadata, validateWorkoutPayload } from '../../src/core/workout-payload';
import { WorkoutStateDelivery } from '../../src/services/workout-state-delivery';

const native = vi.hoisted(() => ({ getWorkoutState: vi.fn(), listWorkouts: vi.fn(), readWorkout: vi.fn() }));
vi.mock('../../modules/power-log-bridge', () => ({ default: native }));
beforeEach(() => vi.clearAllMocks());
const metadata = () => ({
  schemaVersion: 1,
  id: 'ride',
  startedAt: '2026-01-01T00:00:00Z',
  elapsedSeconds: 1,
  phase: 'completed',
  indoor: true,
  watchEnabled: false,
  eventCount: '9223372036854775807',
  interrupted: false,
  healthKitState: 'saved',
  collectionRevision: '9007199254740991',
});
it.each(['01', '+1', '1.0', '1e3', ' 1', '9007199254740992', 1])('rejects malformed state revision %s', async value => {
  native.getWorkoutState.mockResolvedValue({ ...unavailableWorkoutState, collectionRevision: value });
  await expect(workouts.getState()).rejects.toThrow('revision');
});
it('preserves exact revisions and large counts in catalog and detail reads', async () => {
  native.listWorkouts.mockResolvedValue([metadata()]);
  native.readWorkout.mockResolvedValue({
    metadata: metadata(),
    summary: {
      eventCount: '9223372036854775807',
      telemetryCount: '2',
      locationCount: '0',
      healthCount: '0',
      lifecycleCount: '1',
      lapCount: 1,
    },
  });
  expect((await workouts.list()).records[0]?.eventCount).toBe('9223372036854775807');
  expect((await workouts.read('ride')).summary.lifecycleCount).toBe('1');
});
it.each(['01', '-1', '9223372036854775808', 1])('skips and counts malformed catalog count %s', async eventCount => {
  native.listWorkouts.mockResolvedValue([{ ...metadata(), eventCount }]);
  await expect(workouts.list()).resolves.toEqual({ records: [], unreadableCount: 1, unindexedCount: 0 });
});
it('fills a native History page after an unreadable row without hiding the next page', async () => {
  const good = (id: string) => ({ ...metadata(), id });
  native.listWorkouts
    .mockResolvedValueOnce([good('z'), { ...good('y'), eventCount: '-1' }])
    .mockResolvedValueOnce([good('x')]);
  const page = await workouts.list({ limit: 2 });
  expect(page.records.map(row => row.id)).toEqual(['z', 'x']);
  expect(page.unreadableCount).toBe(1);
  expect(native.listWorkouts.mock.calls[1]![0]).toEqual({
    limit: 1,
    beforeStartedAt: metadata().startedAt,
    beforeID: 'y',
  });
});
const summary = () => ({
  eventCount: '1',
  telemetryCount: '0',
  locationCount: '0',
  healthCount: '0',
  lifecycleCount: '1',
  lapCount: 1,
});
it.each(
  (['eventCount', 'telemetryCount', 'locationCount', 'healthCount', 'lifecycleCount'] as const).flatMap(field =>
    ['01', '-1', '9223372036854775808', 1, null, undefined].map(value => ({ field, value })),
  ),
)('rejects original summary $field=$value independently of lapCount', async ({ field, value }) => {
  native.readWorkout.mockResolvedValue({ metadata: metadata(), summary: { ...summary(), [field]: value } });
  await expect(workouts.read('ride')).rejects.toThrow('count');
});
it('rejects a malformed bounded lap count', async () => {
  native.readWorkout.mockResolvedValue({ metadata: metadata(), summary: { ...summary(), lapCount: '01' } });
  await expect(workouts.read('ride')).rejects.toThrow('count');
});
it('rejects unknown Health outcomes at the read boundary', async () => {
  native.getWorkoutState.mockResolvedValue({ ...unavailableWorkoutState, healthKitState: 'allSynced' });
  await expect(workouts.getState()).rejects.toThrow('Health');
});

it.each([
  { component: 'motor-power', sourceRevision: '1', required: true, state: 'saved', providerIds: [] },
  { component: 'readback', sourceRevision: '01', required: true, state: 'pending', providerIds: [] },
  { component: 'readback', sourceRevision: '1', required: true, state: 'blocked', providerIds: [] },
  { component: 'readback', sourceRevision: '1', required: true, state: 'unavailable', providerIds: [] },
  {
    component: 'readback',
    sourceRevision: '1',
    required: true,
    state: 'unavailable',
    reason: 'unrecognized',
    providerIds: [],
  },
])('rejects an invalid closed component result %j', async component => {
  native.getWorkoutState.mockResolvedValue({ ...unavailableWorkoutState, componentResults: [component] });
  await expect(workouts.getState()).rejects.toThrow('component');
});

it.each([undefined, null, '', 'deletion-1'])(
  'rejects a missing or nondecimal catalog revision %s',
  async historyRevision => {
    native.getWorkoutState.mockResolvedValue({ ...unavailableWorkoutState, historyRevision });
    await expect(workouts.getState()).rejects.toThrow('revision');
  },
);

it('validates the catalog revision through the required state path without optional rereads', async () => {
  let reads = 0;
  const state = { ...unavailableWorkoutState };
  Object.defineProperty(state, 'historyRevision', {
    enumerable: true,
    get() {
      reads += 1;
      return '1';
    },
  });
  native.getWorkoutState.mockResolvedValue(state);
  expect((await workouts.getState()).historyRevision).toBe('1');
  expect(reads).toBe(2);
});

it('validates history and delivers completion objects without JSON serialization', () => {
  const page = Array.from({ length: 200 }, (_, index) => ({
    ...metadata(),
    id: `ride-${index}`,
    endedAt: '2026-01-01T00:00:01Z',
    componentResults: [
      { component: 'workout' as const, sourceRevision: '1', required: true, state: 'saved' as const, providerIds: [] },
    ],
  }));
  const displayed: string[] = [];
  const delivery = new WorkoutStateDelivery(value => displayed.push(value.id!));
  const parse = vi.spyOn(JSON, 'parse').mockImplementation(() => {
    throw new Error('JSON parsing at completion boundary');
  });
  const stringify = vi.spyOn(JSON, 'stringify').mockImplementation(() => {
    throw new Error('JSON serialization at completion boundary');
  });
  let results: unknown[] = [];
  try {
    for (const record of page) validateWorkoutPayload(record);
    const record = normalizeWorkoutMetadata(page[0]);
    const state = normalizeWorkoutState({ ...unavailableWorkoutState, ...record, phase: 'completed', useWatch: false });
    results = [workoutExportReady(record), workoutCanDelete(record, state)];
    delivery.receive(state);
    const request = delivery.beginRefresh();
    delivery.finishRefresh(request, { ...state, id: 'next-ride' });
  } finally {
    delivery.setActive(false);
    parse.mockRestore();
    stringify.mockRestore();
  }
  expect(results).toEqual([true, true]);
  expect(displayed).toEqual(['ride-0', 'next-ride']);
});

it.each([NaN, Infinity, -Infinity])('rejects non-finite state revision %s without null coercion', async value => {
  native.getWorkoutState.mockResolvedValue({ ...unavailableWorkoutState, collectionRevision: value });
  await expect(workouts.getState()).rejects.toThrow();
});
