import { completionSnapshotFields, type CompletionSnapshotFields } from '../../shared/js';
import type { WorkoutDetail, WorkoutMetadata } from './workouts';

export function validDecimal(value: unknown, kind: 'revision' | 'count', positive = false): value is string {
  if (typeof value !== 'string' || !/^(0|[1-9][0-9]*)$/.test(value)) return false;
  const max = kind === 'revision' ? '9007199254740991' : '9223372036854775807';
  return (!positive || value !== '0') && (value.length < max.length || (value.length === max.length && value <= max));
}
function decimal(value: unknown, kind: 'revision' | 'count', positive = false) {
  if (!validDecimal(value, kind, positive)) throw new Error(`Invalid workout ${kind}`);
}
function object(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('Invalid workout payload');
  return value as Record<string, unknown>;
}
export function watchWorkoutId(value: unknown): string {
  if (typeof value !== 'string' || !/^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(value))
    throw new Error('Invalid Watch ride identity');
  return value.toLowerCase();
}
const completions = new WeakMap<object, CompletionSnapshotFields>();
export function workoutCompletion(value: object): CompletionSnapshotFields {
  return completions.get(value) ?? completionSnapshotFields(value);
}
export function retainWorkoutCompletion<T extends object>(value: T, completion: CompletionSnapshotFields): T {
  completions.set(value, completion);
  return value;
}
export function validateWorkoutPayload(value: unknown): CompletionSnapshotFields {
  const payload = object(value);
  if (
    payload.readWorkoutIds !== undefined &&
    (!Array.isArray(payload.readWorkoutIds) || payload.readWorkoutIds.some(id => typeof id !== 'string'))
  )
    throw new Error('Invalid workout read owners');
  if (payload.collectionRevision != null) decimal(payload.collectionRevision, 'revision');
  const completion = completionSnapshotFields(payload);
  if (payload.componentResults !== undefined) {
    if (!Array.isArray(payload.componentResults)) throw new Error('Invalid Health component');
    for (const raw of payload.componentResults) {
      const component = object(raw);
      const states = ['notRequested', 'pending', 'saved', 'unavailable', 'failed', 'fenced'];
      const components = [
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
      const reasons = [
        'permission',
        'locked',
        'quota',
        'invalid',
        'fixedSize',
        'integrityConflict',
        'ambiguous',
        'outcomeAbsent',
        'providerDeleted',
        'localHistory',
        'discard',
        'closedSource',
        'builderLost',
        'readbackUnavailable',
        'nativeOwnerLost',
        'numericOverflow',
        'noValidReading',
        'noCoverage',
        'inactive',
        'transientFailure',
        'missingSource',
        'clock',
      ];
      if (
        !components.includes(component.component as string) ||
        !states.includes(component.state as string) ||
        !validDecimal(component.sourceRevision, 'revision') ||
        typeof component.required !== 'boolean' ||
        !Array.isArray(component.providerIds) ||
        component.providerIds.some(id => typeof id !== 'string') ||
        (component.reason !== undefined && !reasons.includes(component.reason as string)) ||
        (['unavailable', 'failed', 'fenced'].includes(component.state as string) && !component.reason)
      )
        throw new Error('Invalid Health component');
    }
  }
  for (const field of ['recordingOwner', 'healthPublisher']) {
    if (
      payload[field] !== undefined &&
      !(field === 'recordingOwner' ? ['phone'] : ['phone', 'watch']).includes(payload[field] as string)
    )
      throw new Error('Invalid Health role');
  }
  retainWorkoutCompletion(payload, completion);
  return completion;
}
export function normalizeWorkoutMetadata(value: unknown): WorkoutMetadata {
  validateWorkoutPayload(value);
  decimal(object(value).eventCount, 'count');
  return value as WorkoutMetadata;
}
export function normalizeWorkoutDetail(value: unknown): WorkoutDetail {
  const detail = object(value);
  normalizeWorkoutMetadata(detail.metadata);
  const summary = object(detail.summary);
  for (const field of ['eventCount', 'telemetryCount', 'locationCount', 'healthCount', 'lifecycleCount'] as const)
    decimal(summary[field], 'count');
  if (typeof summary.lapCount !== 'number' || !Number.isSafeInteger(summary.lapCount) || summary.lapCount < 0)
    throw new Error('Invalid workout lap count');
  return value as WorkoutDetail;
}
