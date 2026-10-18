import { AnalyticsItem, analyticsTimeUs } from '../../../../shared/js';

export interface FitOriginal {
  readonly elapsed: number;
  readonly timeUs?: string;
  readonly kind: string;
  readonly producer?: string;
  readonly sequence?: number;
  readonly clockEpoch?: string | null;
  readonly connectionEpoch?: string | null;
  readonly interruption?: number;
  readonly barrier?: boolean;
  readonly action?: string | null;
  readonly interrupted?: boolean;
  readonly controllerSequence?: number;
  readonly values?: Readonly<Record<string, number>>;
}

export function fitOriginal(row: FitOriginal): AnalyticsItem {
  const timeUs = row.timeUs ?? analyticsTimeUs(row.elapsed);
  const producer = row.producer ?? 'phone';
  const sequence = row.sequence ?? 1;
  const identity = `${row.kind}:${producer}:${sequence}:${timeUs}`;
  const entries = Object.entries(row.values ?? {}).filter(([, value]) => !Number.isNaN(value));
  return new AnalyticsItem(
    timeUs,
    row.elapsed,
    identity,
    identity,
    producer,
    String(sequence),
    row.kind,
    row.kind === 'telemetry' ? 'controller' : producer,
    '',
    entries.map(([metric]) => metric),
    entries.map(() => 'real64'),
    entries.map(([, value]) => String(value)),
    null,
    null,
    row.clockEpoch ?? null,
    row.connectionEpoch ?? null,
    row.interruption ?? 0,
    row.action ?? null,
    row.barrier ?? false,
    row.interrupted ?? false,
    row.controllerSequence === undefined ? null : String(row.controllerSequence),
    true,
  );
}
