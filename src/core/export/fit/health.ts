import { HEALTH_METRICS, HEALTH_REPRESENTATIONS } from '../catalog';
import type { ProjectionPage } from '../pages';
import { ExportError, type HealthKind, type Producer } from '../types';

export const HEART_RATE_MIN = 1;
export const HEART_RATE_MAX = 254;

export type HeartRateKind = 'sample' | 'latest';

export interface HeartRatePlan {
  readonly source: Producer;
  readonly kind: HeartRateKind;
}

export interface HealthPlan {
  readonly heartRate: HeartRatePlan | null;
  readonly calories: number;
}

type Representation = keyof typeof HEALTH_REPRESENTATIONS;

const fallback = (metric: string): HealthKind =>
  HEALTH_METRICS.find(definition => definition.metric === metric)!.kindWithoutRepresentation!;
const HEART_RATE_FALLBACK = fallback('heartRate');
const ENERGY_FALLBACK = fallback('activeEnergy');

const malformed = (problem: string) =>
  new ExportError('page', `The ride data could not be read for export: a Health row ${problem}.`);

export function validHeartRate(value: number): boolean {
  return value >= HEART_RATE_MIN && value <= HEART_RATE_MAX;
}

export function healthKind(
  representation: string | null | undefined,
  sampleCount: number,
  withoutRepresentation: HealthKind,
): HealthKind | null {
  if (representation === null || representation === undefined) return withoutRepresentation;
  if (!Object.hasOwn(HEALTH_REPRESENTATIONS, representation)) throw malformed(`has the unknown kind ${representation}`);
  const definition = HEALTH_REPRESENTATIONS[representation as Representation];
  if ('condensedKind' in definition && sampleCount > 1) return definition.condensedKind;
  return definition.kind;
}

export function heartRateKind(representation: string | null | undefined, sampleCount: number): HealthKind | null {
  return healthKind(representation, sampleCount, HEART_RATE_FALLBACK);
}

export function healthProducer(value: string | null | undefined): Producer {
  if (value === 'phone' || value === 'watch') return value;
  throw malformed('names an unknown source');
}

interface SourceEvidence {
  heartRate: boolean;
  samples: boolean;
  energy: boolean;
  cumulative: number;
  finalTime: number;
  final: number;
}

const evidence = (): SourceEvidence => ({
  heartRate: false,
  samples: false,
  energy: false,
  cumulative: NaN,
  finalTime: -Infinity,
  final: NaN,
});

export class HealthDiscovery {
  private readonly sources: Record<Producer, SourceEvidence> = { phone: evidence(), watch: evidence() };

  constructor(
    readonly owner: Producer,
    readonly end: number,
  ) {}

  add(page: ProjectionPage<'healthFit'>, intervals: Int32Array): void {
    const { elapsedSeconds, producer, representation, sampleCount, heartRateBpm, activeEnergyKcal } = page.columns;
    for (let i = 0; i < page.rows; i++) {
      const source = this.sources[healthProducer(producer?.[i])];
      const t = elapsedSeconds![i]!;
      if (!Number.isFinite(t)) throw malformed('has no valid elapsed time');
      const stored = representation?.[i] ?? null;
      const count = sampleCount ? sampleCount[i]! : NaN;
      const hrKind = healthKind(stored, count, HEART_RATE_FALLBACK);
      if (validHeartRate(heartRateBpm ? heartRateBpm[i]! : NaN)) {
        if (hrKind === 'sample' || hrKind === 'latest') source.heartRate = true;
        if (hrKind === 'sample' && intervals[i] !== 0) source.samples = true;
      }
      const energy = activeEnergyKcal ? activeEnergyKcal[i]! : NaN;
      if (!Number.isFinite(energy)) continue;
      const energyKind = healthKind(stored, count, ENERGY_FALLBACK);
      if (energyKind === 'cumulative') {
        source.energy = true;
        if (t <= this.end && !(source.cumulative >= energy)) source.cumulative = energy;
      } else if (energyKind === 'final') {
        source.energy = true;
        if (t >= source.finalTime) {
          source.finalTime = t;
          source.final = energy;
        }
      }
    }
  }

  plan(): HealthPlan {
    const choose = (qualifies: (source: SourceEvidence) => boolean): Producer | null => {
      const other: Producer = this.owner === 'watch' ? 'phone' : 'watch';
      if (qualifies(this.sources[this.owner])) return this.owner;
      return qualifies(this.sources[other]) ? other : null;
    };
    const hrSource = choose(source => source.heartRate);
    const energySource = choose(source => source.energy);
    const energy = energySource === null ? null : this.sources[energySource];
    return {
      heartRate:
        hrSource === null ? null : { source: hrSource, kind: this.sources[hrSource].samples ? 'sample' : 'latest' },
      calories: energy === null ? NaN : Number.isNaN(energy.final) ? energy.cumulative : energy.final,
    };
  }
}
