import { hasKnownControllerSpeedUnit } from './controller-speed';

export type DistanceSource = 'auto' | 'gps:watch' | 'gps:phone' | 'health:watch' | 'health:phone' | 'controller';
export interface MetricSourceInfo {
  source: string; label: string; estimated?: boolean; partial?: boolean;
  coveredSeconds?: number; uncoveredSeconds?: number; policyVersion?: number;
}
export interface DistanceProfileInfo extends MetricSourceInfo { source: Exclude<DistanceSource, 'auto'>; distanceMeters: number }
export interface WorkoutDistanceInfo { selection: DistanceSource; selected?: DistanceProfileInfo | null; available: DistanceProfileInfo[] }
export const DISTANCE_POLICY_VERSION = 1;
export const DISTANCE_SOURCE_LABELS: Record<DistanceSource, string> = {
  auto: 'Auto', 'gps:watch': 'GPS · Watch', 'gps:phone': 'GPS · iPhone',
  'health:watch': 'Health · Watch', 'health:phone': 'Health · iPhone', controller: 'Controller estimate',
};
export const distanceSourceLabel = (info: Pick<MetricSourceInfo, 'source' | 'label'>) => DISTANCE_SOURCE_LABELS[info.source as DistanceSource] ?? info.label;
export const distanceSourceCaption = (info: MetricSourceInfo) => `${distanceSourceLabel(info)}${info.partial ? ' · Partial' : ''}`;

export interface ControllerDistanceInput {
  time: number; speed?: number; model?: string; protocol?: string; identity?: string;
  continuity: number; connectionEpoch?: string; active: boolean; id: string;
}
export interface ControllerDistanceInterval { start: number; end: number; startSpeed: number; endSpeed: number; distance: number; from: string; to: string }
export interface ControllerDistanceState { previous?: ControllerDistanceInput; distance: number; covered: number; intervals: number }
export const emptyControllerDistance = (): ControllerDistanceState => ({ distance: 0, covered: 0, intervals: 0 });

/** Only observed, known-unit controller values enter this estimator. UI holds are not inputs. */
export function appendControllerDistance(state: ControllerDistanceState, input: ControllerDistanceInput): ControllerDistanceInterval | undefined {
  const previous = state.previous;
  const valid = input.active && Number.isFinite(input.time) && input.time >= 0 && input.speed !== undefined && Number.isFinite(input.speed)
    && input.speed >= 0 && input.speed <= 40 && Boolean(input.identity) && Boolean(input.connectionEpoch) && hasKnownControllerSpeedUnit(input.model ?? '', input.protocol ?? '');
  state.previous = valid ? { ...input } : undefined;
  if (previous && input.time <= previous.time) { state.previous = undefined; return; }
  if (!valid || !previous || input.time <= previous.time || input.time - previous.time > 2.5 || previous.continuity !== input.continuity
    || previous.connectionEpoch !== input.connectionEpoch || previous.identity !== input.identity || previous.model !== input.model || previous.protocol !== input.protocol) return;
  const dt = input.time - previous.time, distance = (previous.speed! + input.speed!) * dt / 2;
  state.distance += distance; state.covered += dt; state.intervals += 1;
  return { start: previous.time, end: input.time, startSpeed: previous.speed!, endSpeed: input.speed!, distance, from: previous.id, to: input.id };
}

/** Integrate the stated linear speed model at range boundaries, without inventing sensor samples. */
export function clipControllerDistance(interval: Pick<ControllerDistanceInterval, 'start' | 'end' | 'startSpeed' | 'endSpeed'>, start: number, end: number) {
  const a = Math.max(start, interval.start), b = Math.min(end, interval.end);
  if (b <= a) return { distance: 0, covered: 0 };
  const speed = (time: number) => interval.startSpeed + (interval.endSpeed - interval.startSpeed) * (time - interval.start) / (interval.end - interval.start);
  return { distance: (speed(a) + speed(b)) * (b - a) / 2, covered: b - a };
}
