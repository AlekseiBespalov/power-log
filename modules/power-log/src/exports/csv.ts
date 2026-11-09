import type { ExportSessionView } from '../../../../shared/generated/ts/api';
import { FIT_DEVELOPER_FIELDS } from './catalog';
import { fitClock, parseStartedAt, type FitClock } from './clock';
import { SECOND, SECOND_VALUES, secondValues } from './fit';
import { columnRows, numberOf, type ExportPageReader } from './reader';
import type { ExportSlicer } from './slicer';
import { TextChunks } from './text';
import { ExportError, type ExportSink } from './types';
import { telemetryTable } from './zip-export';

const developer = (channel: string) =>
  SECOND.developer + FIT_DEVELOPER_FIELDS.findIndex(field => field.channel === channel);

function csvColumns(consumption: string): readonly (readonly [string, number, number])[] {
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

export async function encodeCsv(
  reader: ExportPageReader,
  view: ExportSessionView,
  sink: ExportSink,
  slicer: ExportSlicer,
): Promise<void> {
  const metadata = view.metadata!;
  let clock: FitClock | null = null;
  const out = new TextChunks(sink);
  const layout = csvColumns(metadata.batteryConsumptionSource);
  out.line(['timestamp', 'elapsedSeconds', 'lap', ...layout.map(([name]) => name)].join(','));
  const values = new Float64Array(SECOND_VALUES);
  let lap = 1;
  let written = 0;
  for await (const page of reader.pages(view.sessionId, 'seconds')) {
    clock ??= fitClock(parseStartedAt(metadata.startedAt));
    for (const row of columnRows([page])) {
      if (row.kind === 'lap') lap++;
      if (row.kind !== 'record') continue;
      secondValues(row, values);
      const second = numberOf(row, 'second');
      const time = new Date((clock.base + second) * 1000).toISOString().replace('.000Z', 'Z');
      let line = `${time},${fixed(second - clock.f, 3)},${lap}`;
      for (const [, index, decimals] of layout) line += `,${fixed(values[index]!, decimals)}`;
      out.line(line);
      written++;
      if (out.due) {
        await out.drain();
        await slicer.tick();
      }
    }
  }
  if (written === 0)
    throw new ExportError(
      'exportNoRecords',
      'This ride has no power, cadence, heart rate, GPS or distance readings for a CSV file. The ride-data ZIP is still available.',
    );
  await out.end();
}

export async function encodeImportedCsv(
  reader: ExportPageReader,
  view: ExportSessionView,
  sink: ExportSink,
  slicer: ExportSlicer,
): Promise<void> {
  const out = new TextChunks(sink);
  await telemetryTable(reader, view, out, slicer, true);
  await out.end();
}
