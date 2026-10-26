import type { BatteryConsumptionSource } from '../consumption';
import { FIT_DEVELOPER_FIELDS } from './catalog';
import type { FitClock } from './clock';
import { perSecondExport, streams, type PerSecondRequest } from './fit-export';
import {
  RecordEmitter,
  SECOND,
  SECOND_VALUES,
  mergeWitnesses,
  type LifecycleItem,
  type RecordSecond,
  type RecordTarget,
} from './fit/records';
import { ExportReads, createSlicer, exportCancelled } from './pages';
import { TextChunks } from './tables/text';
import { ExportError, type ExportCommitResult, type ExportSource, type OpenExportSink } from './types';

export interface CsvExportResult extends ExportCommitResult {
  readonly rows: number;
}

type Column = readonly [name: string, value: number, decimals: number];

const developer = (channel: string) =>
  SECOND.developer + FIT_DEVELOPER_FIELDS.findIndex(field => field.channel === channel);

function columns(consumption: BatteryConsumptionSource): readonly Column[] {
  return [
    ['latitude', SECOND.latitude, 7],
    ['longitude', SECOND.longitude, 7],
    ['altitudeMeters', SECOND.altitude, 2],
    ['speedMps', SECOND.speed, 3],
    ['distanceMeters', SECOND.distance, 2],
    ['heartRateBpm', SECOND.heartRate, 1],
    ['humanPowerW', SECOND.power, 1],
    ['cadenceRpm', SECOND.cadence, 1],
    ['motorInputPowerW', SECOND.motor, 1],
    ['assistLevel', SECOND.assist, 0],
    ['batteryVoltageV', developer('batteryVoltageV'), 2],
    ['batteryCurrentA', developer('batteryCurrentA'), 2],
    ['motorCurrentA', developer('motorCurrentA'), 2],
    ['motorRpm', developer('motorRpm'), 1],
    ['motorTempC', developer('motorTempC'), 1],
    ['controllerTempC', developer('controllerTempC'), 1],
    ['pedalTorqueNm', developer('pedalTorqueNm'), 2],
    [`${consumption}EnergyWh`, developer('consumedWh'), 4],
    [`${consumption}ChargeAh`, developer('consumedAh'), 4],
  ];
}

function fixed(value: number, decimals: number): string {
  if (!Number.isFinite(value)) return '';
  const text = value.toFixed(decimals);
  const trimmed = decimals > 0 ? text.replace(/\.?0+$/, '') : text;
  return trimmed === '-0' ? '0' : trimmed;
}

const noRows = () =>
  new ExportError(
    'noRecords',
    'This ride has no power, cadence, heart rate, GPS or distance readings for a CSV file. The ride-data ZIP is still available.',
  );

class CsvRows implements RecordTarget {
  rows = 0;
  private lap = 1;
  private readonly values = new Float64Array(SECOND_VALUES);

  constructor(
    private readonly out: TextChunks,
    private readonly clock: FitClock,
    private readonly columns: readonly Column[],
  ) {}

  record(second: RecordSecond): void {
    second.read(this.values);
    const time = new Date((this.clock.base + second.second) * 1000).toISOString().replace('.000Z', 'Z');
    let line = `${time},${fixed(second.second - this.clock.f, 3)},${this.lap}`;
    for (const [, index, decimals] of this.columns) line += `,${fixed(this.values[index]!, decimals)}`;
    this.out.line(line);
    this.rows++;
  }

  lifecycle(item: LifecycleItem): void {
    if (item.kind === 'lap') this.lap++;
  }

  drain(): Promise<void> {
    return this.out.due ? this.out.drain() : Promise.resolve();
  }
}

export async function exportCsv(
  source: ExportSource,
  openSink: OpenExportSink,
  request: PerSecondRequest,
): Promise<CsvExportResult> {
  const { rideId, context, signal } = request;
  if (signal?.aborted) throw exportCancelled();
  const slicer = createSlicer(signal);
  const reads = new ExportReads({ platform: context.platform, kind: 'fit', slicer, signal });
  return perSecondExport(
    source,
    request,
    reads,
    'CSV file',
    () => undefined,
    async (ride, adopt) => {
      const { input, consumptionSource, profile, gps, health, clock, groups } = ride;
      const sink = adopt(await openSink('csv', context));
      if (signal?.aborted) throw exportCancelled();
      const out = new TextChunks(sink);
      const layout = columns(consumptionSource);
      out.line(['timestamp', 'elapsedSeconds', 'lap', ...layout.map(([name]) => name)].join(','));
      const rows = new CsvRows(out, clock, layout);
      const emission = streams(input, clock, profile, gps, health, null, null, consumptionSource, 'rider');
      const emitter = new RecordEmitter(rows, clock, 1, item => rows.lifecycle(item));
      await mergeWitnesses(emission.list, groups, input.timeline.intervals, emitter, slicer);
      emitter.finish();
      if (rows.rows === 0) throw noRows();
      await out.end();
      await source.close(input.session);
      if (signal?.aborted) throw exportCancelled();
      const committed = await sink.commit(`power-log-${rideId}.csv`);
      return { ...committed, rows: rows.rows };
    },
  );
}
