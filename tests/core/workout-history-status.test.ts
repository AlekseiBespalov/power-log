import { expect, it } from 'vitest';
import { workoutHealthResultPresentation, workoutHistoryStatusPresentation } from '../../src/core/workout-presentation';
import type { ComponentResult, HealthComponent, OwnerHealth, WorkoutMetadata } from '../../src/core/workouts';

const ride: WorkoutMetadata = {
  schemaVersion: 1,
  id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
  startedAt: '2026-10-07T00:00:00.000Z',
  elapsedSeconds: 60,
  phase: 'completed',
  indoor: true,
  watchEnabled: false,
  eventCount: '0',
  interrupted: false,
  healthKitState: 'saved',
};
const components: HealthComponent[] = [
  'workout',
  'route',
  'laps',
  'rider-power',
  'cycling-cadence',
  'cycling-speed',
  'cycling-distance',
  'association',
  'readback',
];
function result(component: HealthComponent, outcome: Pick<ComponentResult, 'state' | 'reason'>): ComponentResult {
  return { component, sourceRevision: '1', required: true, providerIds: [], ...outcome } as ComponentResult;
}

it.each(['appleHealth', 'healthConnect'] as const)('preserves every %s owner outcome', healthProvider => {
  const provider = healthProvider === 'healthConnect' ? 'Health Connect' : 'Apple Health';
  const labels: Record<OwnerHealth, string> = {
    saved: `${provider} saved`,
    notRequested: 'Saved only in Power Log',
    pending: `${provider} save pending`,
    failed: `${provider} not saved`,
    notSaved: `${provider} not saved`,
    unavailable: `${provider} unavailable`,
    unknown: `${provider} save unconfirmed`,
    discarded: `${provider} discarded`,
  };
  for (const [state, label] of Object.entries(labels)) {
    const record = { ...ride, healthProvider, healthKitState: state as OwnerHealth };
    expect(workoutHistoryStatusPresentation(record)).toBe(label);
    expect(workoutHistoryStatusPresentation(record)).toBe(workoutHealthResultPresentation(record).label);
  }
});

it('counts saved and pending components without counting Health-off items', () => {
  const record: WorkoutMetadata = {
    ...ride,
    healthKitState: 'pending',
    componentResults: components.map((component, index) =>
      result(component, { state: index < 4 ? 'saved' : 'pending' }),
    ),
  };
  expect(workoutHistoryStatusPresentation(record)).toBe('Apple Health save pending · 4 of 9 saved');
  record.componentResults!.push(result('route', { state: 'notRequested' }));
  expect(workoutHistoryStatusPresentation(record)).toBe('Apple Health save pending · 4 of 9 saved');
});

it('keeps workout saving independent of component saving', () => {
  const record = { ...ride, componentResults: [result('readback', { state: 'pending' })] };
  expect(workoutHistoryStatusPresentation(record)).toBe('Apple Health saved · 0 of 1 saved · 1 item pending');
  expect(workoutHealthResultPresentation(record)).toEqual({
    label: 'Apple Health saved',
    details: ['Health readback pending'],
  });
});

it('shows a saved result without extra counts when all requested items are saved', () => {
  const record = {
    ...ride,
    componentResults: components.map(component => result(component, { state: 'saved' })),
  };
  record.componentResults.push(result('route', { state: 'notRequested' }));
  expect(workoutHistoryStatusPresentation(record)).toBe('Apple Health saved');
});

it.each(['appleHealth', 'healthConnect'] as const)(
  'counts %s permission items independently of their publication state',
  healthProvider => {
    const record = {
      ...ride,
      healthProvider,
      componentResults: [
        result('route', { state: 'unavailable', reason: 'permission' }),
        result('rider-power', { state: 'failed', reason: 'permission' }),
        result('readback', { state: 'pending', reason: 'permission' }),
        result('workout', { state: 'saved', reason: 'permission' }),
        result('laps', { state: 'notRequested', reason: 'permission' }),
      ],
    };
    const provider = healthProvider === 'healthConnect' ? 'Health Connect' : 'Apple Health';
    expect(workoutHistoryStatusPresentation(record)).toBe(`${provider} saved · 3 items waiting for permission`);
    record.componentResults = record.componentResults.slice(0, 1);
    expect(workoutHistoryStatusPresentation(record)).toBe(
      `${provider} saved · 1 item waiting for permission: Allow Power Log to save workouts in ${provider} permissions.`,
    );
  },
);

it.each([
  [
    'failed',
    'not saved',
    'transientFailure',
    'Apple Health could not confirm this request. Your local ride is available.',
  ],
  ['unavailable', 'unavailable', 'noCoverage', 'No measurement coverage is available for this component.'],
  [
    'fenced',
    'blocked',
    'providerDeleted',
    'The workout was deleted from Apple Health. Power Log will not recreate it.',
  ],
] as const)('counts %s items and preserves a single reason', (state, label, reason, explanation) => {
  const componentResults = [result('route', { state, reason }), result('laps', { state, reason })];
  expect(workoutHistoryStatusPresentation({ ...ride, componentResults })).toBe(`Apple Health saved · 2 items ${label}`);
  const record = { ...ride, componentResults: componentResults.slice(0, 1) };
  expect(workoutHealthResultPresentation(record).details).toEqual([`Route ${label}: ${explanation}`]);
  expect(workoutHistoryStatusPresentation(record)).toBe(`Apple Health saved · 1 item ${label}: ${explanation}`);
});

it('summarizes mixed progress and issues without repeating component reasons', () => {
  const record = {
    ...ride,
    healthKitState: 'pending' as const,
    componentResults: [
      result('workout', { state: 'saved' }),
      result('route', { state: 'pending' }),
      result('laps', { state: 'failed', reason: 'transientFailure' }),
      result('rider-power', { state: 'unavailable', reason: 'permission' }),
    ],
  };
  expect(workoutHistoryStatusPresentation(record)).toBe(
    'Apple Health save pending · 1 of 4 saved · 1 item waiting for permission · 1 item not saved',
  );
});

it('includes the single failed item reason while other items are still saving', () => {
  const record = {
    ...ride,
    healthKitState: 'pending' as const,
    componentResults: [
      result('workout', { state: 'saved' }),
      result('route', { state: 'pending' }),
      result('rider-power', { state: 'failed', reason: 'transientFailure' }),
    ],
  };
  expect(workoutHistoryStatusPresentation(record)).toBe(
    'Apple Health save pending · 1 of 3 saved · 1 item not saved: Apple Health could not confirm this request. Your local ride is available.',
  );
});

it('retains the reason when Health access is locked', () => {
  const record = {
    ...ride,
    healthKitState: 'pending' as const,
    componentResults: [result('route', { state: 'pending', reason: 'locked' })],
  };
  expect(workoutHistoryStatusPresentation(record)).toBe(
    'Apple Health save pending · 0 of 1 saved · 1 item pending: Apple Health is locked. Unlock the device to allow Health access.',
  );
});

it('never shows a Health reason outside the shared reason set', () => {
  const raw = 'Error Domain=com.apple.healthkit Code=3 (at PowerLogBridge/WorkoutHealth.swift:938)';
  const record = {
    ...ride,
    healthKitState: 'notSaved' as const,
    healthReason: raw,
    componentResults: [result('route', { state: 'failed', reason: raw as ComponentResult['reason'] })],
  };
  expect(workoutHistoryStatusPresentation(record)).toBe('Apple Health not saved · 1 item not saved');
  expect(workoutHealthResultPresentation(record).details).toEqual(['Route not saved']);
});

it('retains the owner reason when there are no outstanding components', () => {
  const record = { ...ride, healthKitState: 'notSaved' as const, healthReason: 'providerDeleted' };
  expect(workoutHistoryStatusPresentation(record)).toBe(
    'Apple Health not saved · The workout was deleted from Apple Health. Power Log will not recreate it.',
  );
});

it('omits the Health status entirely for browser rides', () => {
  const record = {
    ...ride,
    storage: 'browser' as const,
    healthKitState: 'pending' as const,
    componentResults: [result('workout', { state: 'failed', reason: 'permission' })],
  };
  expect(workoutHistoryStatusPresentation(record)).toBeNull();
  expect(workoutHistoryStatusPresentation({ ...record, watchEnabled: true })).toBeNull();
});
