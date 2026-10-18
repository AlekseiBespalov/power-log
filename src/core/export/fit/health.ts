import {
  AnalyticsEnergyOrigins,
  AnalyticsHeartRateOrigins,
  AnalyticsEnergy,
  AnalyticsOrigin,
  HealthEnergyConsumption,
  analyticsEnergyAvailable,
  analyticsCollectionAvailable,
  protocolTimestampMicroseconds,
  analyticsHealthSource,
  analyticsValid,
  analyticsTimeUs,
} from '../../../../shared/js';
import { HEALTH_METRICS, HEALTH_REPRESENTATIONS } from '../catalog';
import type { ProjectionPage } from '../pages';
import { ExportError, type HealthKind, type Producer } from '../types';

export type HeartRateKind = 'sample' | 'latest';

export interface HeartRatePlan {
  readonly source: Producer;
  readonly kind: HeartRateKind;
  readonly dataOrigin: string | null;
}

export interface HealthPlan {
  readonly heartRate: HeartRatePlan | null;
  readonly calories: number;
  readonly basal: {
    readonly valueKcal: number | null;
    readonly reason: string | null;
    readonly selectedOrigin: string | null;
  };
}

type Representation = keyof typeof HEALTH_REPRESENTATIONS;

const fallback = (metric: string): HealthKind =>
  HEALTH_METRICS.find(definition => definition.metric === metric)!.kindWithoutRepresentation!;
const HEART_RATE_FALLBACK = fallback('heartRate');
const ENERGY_FALLBACK = fallback('activeEnergy');

const malformed = (problem: string) =>
  new ExportError('page', `The ride data could not be read for export: a Health row ${problem}.`);

export function validHeartRate(value: number): boolean {
  return analyticsValid('heartRateBpm', value, null);
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

export interface HealthOriginSelection {
  readonly heartRate?: string | null;
  readonly activeEnergy: string | null;
}

interface SourceEvidence {
  heartRate: boolean;
  representations: Map<string, { sample: boolean; latest: boolean }>;
  heartOrigins: AnalyticsHeartRateOrigins;
  energyAvailable: boolean;
  basalAvailable: boolean;
  consumption: HealthEnergyConsumption;
  basalConsumption: HealthEnergyConsumption;
  activeOrigins: AnalyticsEnergyOrigins;
}

const evidence = (source: Producer, end: number): SourceEvidence => ({
  heartRate: false,
  representations: new Map(),
  heartOrigins: new AnalyticsHeartRateOrigins(source, '0', analyticsTimeUs(end)),
  energyAvailable: false,
  basalAvailable: false,
  consumption: new HealthEnergyConsumption(source, analyticsTimeUs(end), null),
  basalConsumption: new HealthEnergyConsumption(source, analyticsTimeUs(end), null, 'basal'),
  activeOrigins: new AnalyticsEnergyOrigins(source, '0', analyticsTimeUs(end)),
});

export function heartRateMember(page: ProjectionPage<'healthFit'>, i: number): boolean {
  const channel = page.columns.channel?.[i];
  const identifier = page.columns.identifier?.[i];
  if (channel != null) return channel === 'heartRateBpm' || channel === 'heartRate';
  return identifier == null || identifier === 'HKQuantityTypeIdentifierHeartRate' || identifier === 'heartRate';
}

const intervalOrder = (a: AnalyticsEnergy, b: AnalyticsEnergy) => {
  const x = BigInt(a.startUs);
  const y = BigInt(b.startUs);
  return x < y ? -1 : x > y ? 1 : 0;
};

function energyEvidence(
  page: ProjectionPage<'healthFit'>,
  i: number,
  metric: string,
  value: number,
  actualEnd: number,
): AnalyticsEnergy | null {
  if (!Number.isFinite(value)) return null;
  const c = page.columns;
  const t = c.elapsedSeconds![i]!;
  const kind = healthKind(c.representation?.[i], c.sampleCount?.[i] ?? NaN, ENERGY_FALLBACK);
  if (kind !== 'cumulative' && kind !== 'final' && kind !== 'intervalRecord') return null;
  const interval = kind === 'intervalRecord';
  const originalStart = c.originalStartTimestamp?.[i] ?? '';
  const originalEnd = c.originalEndTimestamp?.[i] ?? '';
  const collection = analyticsTimeUs(t);
  const duration =
    interval && originalStart && originalEnd
      ? BigInt(protocolTimestampMicroseconds(originalEnd)) - BigInt(protocolTimestampMicroseconds(originalStart))
      : null;
  const end = interval ? (c.endUs?.[i] ?? collection) : analyticsTimeUs(actualEnd);
  const start = interval ? (c.startUs?.[i] ?? (duration === null ? null : (BigInt(end) - duration).toString())) : '0';
  if (start == null || end == null || !/^-?\d+$/.test(start) || !/^-?\d+$/.test(end))
    throw malformed('has invalid interval energy bounds');
  const producer = healthProducer(c.producer?.[i]);
  return new AnalyticsEnergy(
    metric,
    interval ? 'intervalRecord' : kind === 'final' ? 'finalWorkoutTotal' : 'cumulativeSnapshot',
    producer,
    new AnalyticsOrigin(
      c.provider?.[i] ?? null,
      c.dataOrigin?.[i] ?? null,
      c.providerParentId?.[i] ?? null,
      c.originalEventId?.[i] ?? `${producer}:${c.producerSequence?.[i] ?? i + 1}`,
      producer,
      String(c.producerSequence?.[i] ?? i + 1),
      null,
    ),
    c.observationRevision?.[i] ?? '1',
    collection,
    start,
    end,
    originalStart,
    originalEnd,
    value,
  );
}

export const FIT_MAX_ENERGY_OPERANDS = 65_536;

function retainOperand<T>(operands: T[], operand: T): void {
  if (operands.length === FIT_MAX_ENERGY_OPERANDS)
    throw new ExportError(
      'limit',
      'A FIT export can retain at most 65,536 energy interval operands. The ride-data ZIP is still available.',
    );
  operands.push(operand);
}

export class HealthDiscovery {
  private readonly sources: Record<Producer, SourceEvidence>;
  private prepared = false;
  private readonly originOperands: AnalyticsEnergy[] = [];

  constructor(
    readonly owner: Producer,
    readonly end: number,
    private readonly selectedOrigins: HealthOriginSelection | null = null,
  ) {
    this.sources = { phone: evidence('phone', end), watch: evidence('watch', end) };
  }

  add(page: ProjectionPage<'healthFit'>): void {
    const c = page.columns;
    for (let i = 0; i < page.rows; i++) {
      const source = this.sources[healthProducer(c.producer?.[i])];
      if (!Number.isFinite(c.elapsedSeconds![i]!)) throw malformed('has no valid elapsed time');
      const kind = heartRateKind(c.representation?.[i], c.sampleCount?.[i] ?? NaN);
      if (
        analyticsCollectionAvailable(analyticsTimeUs(c.elapsedSeconds![i]!)) &&
        heartRateMember(page, i) &&
        validHeartRate(c.heartRateBpm?.[i] ?? NaN) &&
        (kind === 'sample' || kind === 'latest')
      ) {
        const origin = c.dataOrigin?.[i] ?? '';
        const representations = source.representations.get(origin) ?? { sample: false, latest: false };
        representations[kind] = true;
        source.representations.set(origin, representations);
        source.heartRate = true;
      }
      const active = energyEvidence(page, i, 'active', c.activeEnergyKcal?.[i] ?? NaN, this.end);
      if (active) {
        source.energyAvailable ||= analyticsEnergyAvailable(active);
        if (
          this.selectedOrigins === null &&
          active.kind === 'intervalRecord' &&
          analyticsCollectionAvailable(active.collectionUs)
        ) {
          retainOperand(this.originOperands, active);
        }
      }
      const basal = energyEvidence(page, i, 'basal', c.basalEnergyKcal?.[i] ?? NaN, this.end);
      if (basal) source.basalAvailable ||= analyticsEnergyAvailable(basal);
    }
  }

  prepareConsumption(): void {
    if (this.prepared) throw new Error('Health discovery is already prepared');
    this.originOperands.sort(intervalOrder);
    for (const operand of this.originOperands)
      this.sources[operand.source as Producer].activeOrigins.append(operand.metric, operand);
    this.originOperands.length = 0;
    this.prepared = true;
    for (const name of ['phone', 'watch'] as const) {
      const source = this.sources[name];
      const retained = name === 'phone' ? this.selectedOrigins : null;
      const activeOrigin =
        retained === null ? (source.activeOrigins.selected()?.dataOrigin ?? null) : retained.activeEnergy;
      source.consumption = new HealthEnergyConsumption(name, analyticsTimeUs(this.end), activeOrigin || null);
    }
  }

  addConsumption(page: ProjectionPage<'healthFit'>): void {
    const c = page.columns;
    for (let i = 0; i < page.rows; i++) {
      const producer = healthProducer(c.producer?.[i]);
      const source = this.sources[producer];
      const collectionUs = analyticsTimeUs(c.elapsedSeconds![i]!);
      const origin = c.dataOrigin?.[i] ?? '';
      const representations = source.representations.get(origin);
      const kind = heartRateKind(c.representation?.[i], c.sampleCount?.[i] ?? NaN);
      if (
        analyticsCollectionAvailable(collectionUs) &&
        heartRateMember(page, i) &&
        representations &&
        kind === (representations.sample ? 'sample' : 'latest')
      ) {
        source.heartOrigins.append(
          collectionUs,
          new AnalyticsOrigin(
            c.provider?.[i] ?? null,
            origin,
            c.providerParentId?.[i] ?? null,
            c.originalEventId?.[i] ?? String(i),
            producer,
            String(c.producerSequence?.[i] ?? i + 1),
            null,
          ),
          Number.isFinite(c.heartRateBpm?.[i]) ? c.heartRateBpm![i]! : null,
          c.clockEpoch?.[i] ?? null,
          c.connectionEpoch?.[i] ?? null,
        );
      }
      const active = energyEvidence(page, i, 'active', c.activeEnergyKcal?.[i] ?? NaN, this.end);
      if (active) source.consumption.append(active);
      const explicit = energyEvidence(page, i, 'basal', c.basalEnergyKcal?.[i] ?? NaN, this.end);
      if (explicit) source.basalConsumption.append(explicit);
    }
  }

  plan(): HealthPlan {
    if (!this.prepared) throw new Error('Health consumption has not been prepared');
    const choose = (qualifies: (source: SourceEvidence) => boolean): Producer | null =>
      analyticsHealthSource(
        this.owner,
        (['phone', 'watch'] as const).filter(name => qualifies(this.sources[name])),
      ) as Producer | null;
    const hrSource = choose(source => source.heartRate);
    const energySource = choose(source => source.energyAvailable);
    const hrOrigin =
      hrSource === null
        ? null
        : (this.selectedOrigins?.heartRate ?? this.sources[hrSource].heartOrigins.selected() ?? '');
    const basalSource = choose(source => source.basalAvailable);
    const value = basalSource === null ? null : (this.sources[basalSource].basalConsumption.value() ?? null);
    return {
      heartRate:
        hrSource === null
          ? null
          : {
              source: hrSource,
              kind: this.sources[hrSource].representations.get(hrOrigin ?? '')?.sample ? 'sample' : 'latest',
              dataOrigin: hrOrigin || null,
            },
      calories: energySource === null ? NaN : (this.sources[energySource].consumption.value() ?? NaN),
      basal: {
        valueKcal: value,
        reason: value === null ? 'unavailable' : null,
        selectedOrigin: null,
      },
    };
  }
}
