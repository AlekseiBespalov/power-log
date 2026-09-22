import { unavailableWorkoutState, type WorkoutDetail, type WorkoutMetadata, type WorkoutState } from '../../src/core/workouts';
import { afterCatalogCursor, catalogLimit, catalogOrder, type CatalogRequest } from '../../src/core/catalog';
import type { DistanceSource, WorkoutDistanceInfo } from '../../src/core/distance';

let state: WorkoutState = { ...unavailableWorkoutState, supported: true, historyRevision: '0' };
const records = new Map<string, WorkoutMetadata>(), deleted = new Set<string>();
const listeners = new Set<(state: WorkoutState) => void>();
const failures = new Map<string, string>(), holds = new Map<string, { promise: Promise<WorkoutDetail>; value: WorkoutDetail; resolve(value: WorkoutDetail): void; reject(error: Error): void }>();
const counts = { list: 0, read: [] as string[], exports: [] as { id: string; selection: DistanceSource }[] };
const distances = new Map<string, WorkoutDistanceInfo>();
export function setDistanceFixture(id: string, value: WorkoutDistanceInfo) { distances.set(id, value); }
const healthReports = new Map<string, Pick<WorkoutDetail['summary'], 'healthDistanceMeters' | 'healthDistanceProvisional' | 'healthDistanceSource' | 'healthDistanceReportedAt'>>();
export function setHealthReportFixture(id: string, provisional: boolean) { healthReports.set(id, { healthDistanceMeters: 1234, healthDistanceProvisional: provisional, healthDistanceSource: 'watch', healthDistanceReportedAt: '2026-01-01T00:00:40.000Z' }); }
export function metadata(id: string, index: number, phase = 'completed'): WorkoutMetadata {
  return { schemaVersion: 1, id, startedAt: new Date(Date.UTC(2026, 0, 1) + index * 1000).toISOString(), endedAt: new Date(Date.UTC(2026, 0, 1) + index * 1000 + 60000).toISOString(),
    phase, indoor: true, watchEnabled: true, sport: 'cycling', subSport: 'indoorCycling', eventCount: 3, interrupted: false,
    saveToHealth: false, recordGPS: false, healthKitState: 'notRequested', warnings: [], watchSyncState: phase === 'finishing' ? 'pending' : 'received',
    collectionRevision: 1, finalizationState: phase === 'finishing' ? 'pending' : 'complete', sealRevision: 1, verifiedSealRevision: phase === 'finishing' ? undefined : 1 };
}
function detail(record: WorkoutMetadata, selection: DistanceSource = 'auto'): WorkoutDetail {
  const available = distances.get(record.id)?.available;
  const distance = available ? { selection, available, selected: selection === 'auto' ? available[0] ?? null : available.find(value => value.source === selection) ?? null } : undefined;
  return { metadata: { ...record }, summary: { schemaVersion: 1, id: record.id, startedAt: record.startedAt, endedAt: record.endedAt!, elapsedSeconds: 60,
    timerSeconds: 48, telemetryCoveredSeconds: 3, heartRateCoveredSeconds: 0, eventCount: 3, telemetryCount: 3, locationCount: 0, healthCount: 0,
    lapCount: 0, routePreview: [], warnings: [], provenance: {}, distance, distanceMeters: distance?.selected?.distanceMeters, ...healthReports.get(record.id) } };
}
function deletionError() { return Object.assign(new Error('This ride was deleted from Power Log.'), { code: 'ERR_RIDE_DELETED' }); }
export function seed(rows: WorkoutMetadata[], current: Partial<WorkoutState> = {}) {
  records.clear(); deleted.clear(); failures.clear(); holds.clear(); distances.clear(); healthReports.clear(); counts.list = 0; counts.read = []; counts.exports = [];
  for (const record of rows) records.set(record.id, record);
  state = { ...unavailableWorkoutState, supported: true, historyRevision: '0', ...current };
}
export function emit(patch: Partial<WorkoutState> = {}) { state = { ...state, ...patch }; for (const listener of listeners) listener(state); }
export function discard(ids: string[], patch: Partial<WorkoutState> = {}) {
  for (const id of ids) { records.delete(id); deleted.add(id); }
  emit({ historyRevision: String(Number(state.historyRevision ?? 0) + ids.length), lastDeletedWorkoutId: ids.at(-1), ...patch });
}
export function failRead(id: string, message: string) { failures.set(id, message); }
export function holdRead(id: string) {
  let resolve!: (value: WorkoutDetail) => void, reject!: (error: Error) => void;
  const promise = new Promise<WorkoutDetail>((yes, no) => { resolve = yes; reject = no; });
  const record = records.get(id); if (!record) throw new Error('Cannot hold a missing fixture ride');
  holds.set(id, { promise, value: detail(record), resolve, reject });
}
export function releaseRead(id: string, failure?: string) {
  const held = holds.get(id); if (!held) throw new Error('Read was not held'); holds.delete(id);
  if (failure) held.reject(new Error(failure)); else held.resolve(held.value);
}
export function snapshot() { return { state, ids: [...records.keys()], counts: { ...counts, read: [...counts.read] } }; }
export const workouts = {
  getState: async () => state,
  subscribe(listener: (value: WorkoutState) => void) { listeners.add(listener); return () => listeners.delete(listener); },
  getPermissions: async () => ({ health: { available: true, readAuthorization: 'notObservable', requestStatus: 'unnecessary', writeAuthorization: {} }, location: 'denied', locationServicesEnabled: true, locationAccuracyAuthorization: 'full' }),
  async list(request: CatalogRequest = {}) { counts.list++; return [...records.values()].filter(row => afterCatalogCursor(row, request)).sort(catalogOrder).slice(0, catalogLimit(request)); },
  async read(id: string, selection: DistanceSource = 'auto') {
    counts.read.push(id);
    const held = holds.get(id); if (held) return held.promise;
    if (deleted.has(id)) throw deletionError();
    if (failures.has(id)) throw new Error(failures.get(id));
    const record = records.get(id); if (!record) throw new Error('Unknown fixture ride'); return detail(record, selection);
  },
  async remove(id: string) { discard([id], state.id === id ? { id: null, phase: 'idle' } : {}); return state; },
  async recover() { return state; },
  async export(id: string, selection: DistanceSource = 'auto') { counts.exports.push({ id, selection }); return 'file:///fixture.fit'; },
  async exportOriginal() { return 'fixture.zip'; },
};
export function setWorkoutTelemetrySource() {}
export const rideHistorySources = [(request: CatalogRequest) => workouts.list(request)];
