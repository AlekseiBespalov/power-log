import { HEALTH_METRICS, HEALTH_REPRESENTATIONS, TABLES } from '../catalog';
import { readProjection } from '../pages';
import type { HealthKind, HealthRepresentationDefinition, ProducerSet } from '../types';
import {
  ROW_BATCH,
  checkProducer,
  csvHeader,
  csvInteger,
  csvNumber,
  csvText,
  tableError,
  type TableInput,
  type Texts,
} from './text';

const TABLE = TABLES[2];
const METRICS = HEALTH_METRICS;
const METRIC_INDEX = new Map<string, number>(METRICS.map((metric, index) => [metric.identifier, index]));
const REPRESENTATIONS: Readonly<Record<string, HealthRepresentationDefinition>> = HEALTH_REPRESENTATIONS;
const SUBJECT = 'a Health record';
const NOT_MEASURED = 'notMeasured';
const POWER_OF_TEN = /^10+$/;

function scaler(factor: number): (value: number) => number {
  if (factor === 1) return value => value;
  const text = String(factor);
  if (!POWER_OF_TEN.test(text)) return value => value * factor;
  const digits = text.length - 1;
  // Shifting the shortest decimal form keeps 0.57 × 100 at 57 instead of the binary product 56.99999999999999.
  return value => {
    if (!Number.isFinite(value)) return value;
    const [mantissa, exponent] = String(value).split('e');
    return Number(`${mantissa}e${Number(exponent ?? 0) + digits}`);
  };
}

const SCALES = METRICS.map(metric => scaler(metric.factor));

function rowKind(representation: string | null, count: number): HealthKind | null | typeof NOT_MEASURED {
  if (representation === null) return null;
  if (!Object.hasOwn(REPRESENTATIONS, representation))
    throw tableError(SUBJECT, `has the Health representation "${representation}", which this export does not know`);
  const definition = REPRESENTATIONS[representation]!;
  if (definition.kind === null) return NOT_MEASURED;
  return definition.condensedKind !== undefined && count > 1 ? definition.condensedKind : definition.kind;
}

function valueLine(
  prefix: string,
  metric: string,
  value: number,
  unit: string | null,
  kind: HealthKind | null,
  count: number,
  suffix: string,
): string {
  const sampleCount = kind === 'aggregate' ? csvInteger(count, SUBJECT, 'sampleCount') : '';
  return `${prefix}${csvText(metric)},${csvNumber(value, SUBJECT, 'value')},${csvText(unit)},${kind ?? ''},${sampleCount}${suffix}`;
}

export async function writeHealth(input: TableInput, producers: ProducerSet): Promise<void> {
  const { source, session, reads, timeline, out } = input;
  const slicer = reads.options.slicer;
  out.line(csvHeader(TABLE));
  const cursor = timeline.cursor('healthZip');
  for await (const page of readProjection(source, session, 'healthZip', reads)) {
    const rows = page.rows;
    if (rows === 0) continue;
    const { interval } = cursor.map(page);
    const columns = page.columns;
    const named = METRICS.map(metric => (metric.key === null ? undefined : columns[metric.key]));
    const elapsed = columns.elapsedSeconds;
    const timestamps: Texts | undefined = columns.timestamp;
    const starts: Texts | undefined = columns.sampleStart;
    const sources: Texts | undefined = columns.producer;
    const apps: Texts | undefined = columns.sourceBundleIdentifier;
    const identifiers: Texts | undefined = columns.identifier;
    const values = columns.value;
    const units: Texts | undefined = columns.unit;
    const representations: Texts | undefined = columns.representation;
    const counts = columns.sampleCount;
    const samples: Texts | undefined = columns.sampleUUID;
    for (let i = 0; i < rows; i++) {
      const count = counts ? counts[i]! : NaN;
      const kind = rowKind(representations?.[i] ?? null, count);
      if (kind !== NOT_MEASURED) {
        const producer = checkProducer(SUBJECT, sources?.[i], producers);
        const identifier = identifiers?.[i] ?? null;
        const generic = values ? values[i]! : NaN;
        if (identifier === null && !Number.isNaN(generic))
          throw tableError(SUBJECT, 'has a value without its identifier');
        const known = identifier === null ? undefined : METRIC_INDEX.get(identifier);
        const time = csvNumber(elapsed ? elapsed[i]! : NaN, SUBJECT, 'elapsedSeconds');
        const active = interval[i] === 0 ? '' : interval[i];
        const prefix = `${csvText(timestamps?.[i])},${csvText(starts?.[i])},${time},${active},${csvText(producer)},${csvText(apps?.[i])},`;
        const suffix = `,${csvText(samples?.[i])}`;
        for (let m = 0; m < METRICS.length; m++) {
          const column = named[m];
          let value = column ? column[i]! : NaN;
          if (Number.isNaN(value) && known === m) value = generic;
          if (Number.isNaN(value)) continue;
          const metric = METRICS[m]!;
          const valueKind = kind ?? metric.kindWithoutRepresentation;
          out.line(valueLine(prefix, metric.metric, SCALES[m]!(value), metric.unit, valueKind, count, suffix));
        }
        if (identifier !== null && known === undefined && !Number.isNaN(generic))
          out.line(valueLine(prefix, identifier, generic, units?.[i] ?? null, kind, count, suffix));
      }
      if (i % ROW_BATCH === ROW_BATCH - 1) {
        if (out.due) await out.drain();
        await slicer.tick();
      }
    }
    if (out.due) await out.drain();
  }
}
