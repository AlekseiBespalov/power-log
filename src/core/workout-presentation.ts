import {
  presentationHealthSetup,
  presentationProvider,
  presentationPhysiology,
  presentationLocationPermission,
  presentationSources,
  presentationStateSources,
  presentationOwnership,
  presentationDistanceOptionLabel,
  presentationResolveOptions,
  presentationStartReadiness,
  PresentationStartContext,
} from '../../shared/js';
import { presentationCapabilities, presentationOptions } from './presentation-inputs';
import type { DistanceSource } from './distance';
import type { TelemetryDisplay } from './telemetry-display';
import {
  workoutInProgress,
  type WorkoutOptions,
  type WorkoutState,
  type WorkoutMetadata,
  type WorkoutHealthResult,
  type OwnerHealth,
  type HealthProvider,
  type HealthReason,
} from './workouts';

export type WorkoutSourcePresentation = {
  label: string;
  detail: string;
  tone: 'ready' | 'neutral' | 'warning';
};

export function workoutOwnershipPresentation(state: WorkoutState): string | null {
  return presentationOwnership(state.foreignOwner) ?? null;
}

export function workoutHealthPresentation(
  options: WorkoutOptions,
  capabilities: WorkoutState['capabilities'],
): { label: string; detail: string | null } {
  const result = presentationHealthSetup(presentationOptions(options), presentationCapabilities(capabilities));
  return { label: result.label, detail: result.detail || null };
}

export function healthProviderLabel(provider?: HealthProvider | null): string {
  return presentationProvider(provider ?? null);
}

const componentLabels = {
  workout: 'Workout',
  route: 'Route',
  laps: 'Laps',
  'rider-power': 'Rider power',
  'cycling-cadence': 'Cycling cadence',
  'cycling-speed': 'Cycling speed',
  'cycling-distance': 'Cycling distance',
  association: 'Workout links',
  readback: 'Health measurements',
};
const componentStates = {
  pending: 'pending',
  saved: 'saved',
  notRequested: 'not requested',
  unavailable: 'unavailable',
  failed: 'not saved',
  fenced: 'stopped',
};
const ownerStates: Record<OwnerHealth, string> = {
  pending: 'save pending',
  saved: 'saved',
  failed: 'not saved',
  notSaved: 'not saved',
  unavailable: 'unavailable',
  unknown: 'save unconfirmed',
  discarded: 'discarded',
  notRequested: 'not requested',
};
function healthReasonPresentation(reason: string | null | undefined, provider: string): string | null {
  if (!reason) return null;
  const reasons: Record<HealthReason, string> = {
    permission: `Allow Power Log to save workouts in ${provider} permissions.`,
    locked: `${provider} is locked. Unlock the device to allow Health access.`,
    quota: `${provider} is temporarily limiting requests.`,
    invalid: `This reading can’t be saved to ${provider}. It stays in Power Log.`,
    fixedSize: `The record exceeds the size supported by ${provider}.`,
    integrityConflict: `${provider} has conflicting records for this ride. Review them in ${provider}.`,
    ambiguous: `The matching ${provider} record could not be confirmed.`,
    outcomeAbsent: `The ${provider} save couldn’t be confirmed. Power Log won’t save it again, to avoid a duplicate.`,
    providerDeleted: `The workout was deleted from ${provider}. Power Log will not recreate it.`,
    localHistory: 'The ride was deleted from Power Log, so Health saving stopped.',
    discard: 'The ride was discarded.',
    closedSource: 'The ride finished without this data, so it won’t be saved to Health.',
    builderLost: `The ${provider} workout session ended before it could be saved. Your ride is kept in Power Log.`,
    readbackUnavailable: `Power Log couldn’t read measurements from ${provider}. Your ride is kept in Power Log.`,
    nativeOwnerLost: `The device that was saving this ride to ${provider} is unavailable. Your ride is kept in Power Log.`,
    numericOverflow: `This reading is outside the range ${provider} accepts. It stays in Power Log.`,
    noValidReading: 'No usable reading was recorded for this.',
    noCoverage: 'Nothing was measured for this during the ride.',
    inactive: 'No measurement was recorded during active riding.',
    transientFailure: `${provider} could not confirm this request. Your local ride is available.`,
    missingSource: 'The device that measured this is no longer available.',
    clock:
      'Health can’t save this ride because its finish time isn’t after its start time. Your ride is kept in Power Log.',
  };
  return reasons[reason as HealthReason] ?? null;
}
export function workoutHealthResultPresentation(
  record: WorkoutHealthResult & {
    healthKitState: OwnerHealth;
    healthReason?: string | null;
    healthProvider?: HealthProvider;
  },
): { label: string; details: string[] } {
  const provider = healthProviderLabel(record.healthProvider ?? 'appleHealth');
  const label =
    record.healthKitState === 'notRequested'
      ? 'Saved only in Power Log'
      : `${provider} ${ownerStates[record.healthKitState]}`;
  const details =
    record.componentResults
      ?.filter(result => !['saved', 'notRequested'].includes(result.state))
      .map(result => {
        const reason = healthReasonPresentation(result.reason, provider);
        return `${componentLabels[result.component]} ${componentStates[result.state]}${reason ? `: ${reason}` : ''}`;
      }) ?? [];
  const reason = healthReasonPresentation(record.healthReason, provider);
  if (reason && record.healthKitState !== 'saved') details.unshift(reason);
  return { label, details };
}
export function workoutHistoryStatusPresentation(record: WorkoutMetadata): string | null {
  const parts: string[] = [];
  if (record.storage !== 'browser') {
    const health = workoutHealthResultPresentation(record);
    parts.push(health.label);
    if (record.healthKitState !== 'notRequested') {
      const requested = record.componentResults?.filter(result => result.state !== 'notRequested') ?? [];
      const unsaved = requested.filter(result => result.state !== 'saved');
      const pending = unsaved.filter(result => result.state === 'pending' && result.reason !== 'permission');
      if (pending.length) parts.push(`${requested.length - unsaved.length} of ${requested.length} saved`);
      const groups = [
        { results: unsaved.filter(result => result.reason === 'permission'), label: 'waiting for permission' },
        ...(['failed', 'unavailable', 'fenced'] as const).map(state => ({
          results: unsaved.filter(result => result.state === state && result.reason !== 'permission'),
          label: componentStates[state],
        })),
      ];
      const issueCount = groups.reduce((count, group) => count + group.results.length, 0);
      if (pending.length && (record.healthKitState !== 'pending' || (unsaved.length === 1 && pending[0]?.reason)))
        groups.push({ results: pending, label: componentStates.pending });
      const provider = healthProviderLabel(record.healthProvider ?? 'appleHealth');
      for (const group of groups) {
        if (!group.results.length) continue;
        const count = group.results.length;
        const onlyIssue = group.label !== componentStates.pending && issueCount === 1;
        const reason =
          onlyIssue || unsaved.length === 1 ? healthReasonPresentation(group.results[0]?.reason, provider) : null;
        parts.push(`${count} ${count === 1 ? 'item' : 'items'} ${group.label}${reason ? `: ${reason}` : ''}`);
      }
      const reason = healthReasonPresentation(record.healthReason, provider);
      if (!unsaved.length && reason && record.healthKitState !== 'saved') parts.push(reason);
    }
  }
  return parts.length ? parts.join(' · ') : null;
}
export function workoutPhysiologyLabel(record: Pick<WorkoutMetadata, 'healthProvider'>, label: string): string {
  return presentationPhysiology(label, record.healthProvider ?? null);
}

/** Local recording finishes independently of the Health writer. */
export function workoutFinishPresentation(state: WorkoutState): { label: string; detail: string } {
  return state.pendingAction === 'discard'
    ? { label: 'Discarding…', detail: 'Discarding local ride…' }
    : { label: 'Saving…', detail: 'Saving local ride…' };
}

export function workoutStartContext(state: WorkoutState, busy = false): PresentationStartContext {
  return new PresentationStartContext(
    'rider',
    state.sessionBusy === true,
    state.endingOwner === true,
    state.foreignOwner !== null,
    state.recoveryState === 'unresolved',
    state.phase === 'finishing',
    busy || state.pendingAction !== null || state.startBusy === true || state.finalizationBusy === true,
    state.recoveryState === 'checking',
    false,
  );
}

export function workoutStartReadiness(
  state: WorkoutState,
  options: WorkoutOptions,
  bikeReady: boolean,
  busy = false,
  error: string | null = null,
) {
  const capabilities = presentationCapabilities({
    ...state.capabilities,
    phoneWorkout: state.capabilities.phoneWorkout || (state.supported && state.capabilities.foregroundOnly),
  });
  return presentationStartReadiness(
    presentationResolveOptions(presentationOptions(options), capabilities),
    capabilities,
    bikeReady,
    state.watch.installed,
    state.phase,
    workoutStartContext(state, busy),
    error,
  );
}

export function workoutSourcePresentation(
  state: WorkoutState,
  options: WorkoutOptions,
  bikeReady: boolean,
  display?: TelemetryDisplay,
): { bike: WorkoutSourcePresentation; heartRate: WorkoutSourcePresentation; gps: WorkoutSourcePresentation } {
  const args = [
    bikeReady,
    state.streams.controller.status,
    state.streams.heartRate.status,
    state.streams.gps.status,
    state.streams.gps.source,
    state.streams.gps.accuracyMeters,
    state.capabilities.healthProvider,
  ] as const;
  const result = workoutInProgress(state.phase)
    ? presentationStateSources(
        state.phase,
        state.indoor,
        state.useWatch,
        state.saveToHealth,
        state.recordGPS,
        ...args,
        display ?? null,
      )
    : presentationSources(
        state.phase,
        presentationResolveOptions(presentationOptions(options), presentationCapabilities(state.capabilities)),
        ...args,
      );
  const source = (value: typeof result.bike): WorkoutSourcePresentation => ({
    label: value.label,
    detail: value.detail,
    tone: value.tone as WorkoutSourcePresentation['tone'],
  });
  return { bike: source(result.bike), heartRate: source(result.heartRate), gps: source(result.gps) };
}

export function workoutDistanceSelectionLabel(source: DistanceSource, foregroundOnly: boolean): string {
  return presentationDistanceOptionLabel(source, foregroundOnly);
}

export const locationPermissionLabel = (status: string) => presentationLocationPermission(status);
