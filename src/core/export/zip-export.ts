import { descriptorJson } from './descriptor';
import { ExportReads, createSlicer, exportCancelled } from './pages';
import { writeEvents } from './tables/events';
import { writeGps } from './tables/gps';
import { writeHealth } from './tables/health';
import { writeTelemetry } from './tables/telemetry';
import { TextChunks, type TableInput } from './tables/text';
import { readTimeline } from './timeline';
import {
  ExportError,
  type ExportCommitResult,
  type ExportContext,
  type ExportOpenResult,
  type ExportSink,
  type ExportSource,
  type OpenExportSink,
  type ProducerSet,
} from './types';
import { ZipWriter, zipTime } from './zip';

const ZIP_FOLDER = 'PowerLog-original';

export interface ZipExportRequest {
  readonly rideId: string;
  readonly context: ExportContext;
  readonly signal?: AbortSignal;
}

const PRODUCER_SETS: readonly (readonly string[])[] = [[], ['phone'], ['watch'], ['phone', 'watch']];

function producerSet(value: unknown, stream: string): ProducerSet {
  if (
    Array.isArray(value) &&
    PRODUCER_SETS.some(set => set.length === value.length && set.every((p, i) => value[i] === p))
  )
    return [...value] as ProducerSet;
  throw new ExportError('page', `The ride data could not be read for export: its ${stream} sources are not valid.`);
}

function producersOf(opened: ExportOpenResult): { gps: ProducerSet; health: ProducerSet } {
  const producers: unknown = opened.producers;
  const sets = typeof producers === 'object' && producers !== null ? (producers as Record<string, unknown>) : {};
  return { gps: producerSet(sets.gps, 'GPS'), health: producerSet(sets.health, 'Health') };
}

async function entry(zip: ZipWriter, out: TextChunks, path: string, write: () => Promise<void> | void): Promise<void> {
  await zip.begin(`${ZIP_FOLDER}/${path}`);
  await write();
  await out.end();
  await zip.end();
}

export async function exportZip(
  source: ExportSource,
  openSink: OpenExportSink,
  request: ZipExportRequest,
): Promise<ExportCommitResult> {
  const { rideId, context, signal } = request;
  const time = zipTime(context.exportedAt);
  const ensureRunning = () => {
    if (signal?.aborted) throw exportCancelled();
  };
  ensureRunning();
  const slicer = createSlicer(signal);
  const reads = new ExportReads({ platform: context.platform, kind: 'zip', slicer, signal });
  const opened = await source.open({ rideId, kind: 'zip', context });
  const session = opened.session;
  let sink: ExportSink | undefined;
  let zip: ZipWriter | undefined;
  try {
    ensureRunning();
    const producers = producersOf(opened);
    const timeline = await readTimeline(source, session, opened.elapsedEnd, reads, { fitLimits: false });
    sink = await openSink('zip', context);
    ensureRunning();
    zip = new ZipWriter(sink, time);
    const out = new TextChunks(zip);
    const input: TableInput = { source, session, reads, timeline, out };
    await entry(zip, out, 'telemetry.csv', () => writeTelemetry(input));
    await entry(zip, out, 'gps.csv', () => writeGps(input, producers.gps));
    await entry(zip, out, 'health.csv', () => writeHealth(input, producers.health));
    await entry(zip, out, 'events.csv', () => writeEvents(input));
    ensureRunning();
    const descriptor = descriptorJson({
      rideId,
      created: context.exportedAt,
      metadata: opened.metadata,
      elapsedSeconds: opened.elapsedEnd,
      timerSeconds: timeline.timerSeconds,
      connections: reads.connections.entries,
      producers,
    });
    await entry(zip, out, 'datapackage.json', () => out.line(descriptor));
    await zip.finish();
    await source.close(session);
    ensureRunning();
    return await sink.commit(`power-log-original-${rideId}.zip`);
  } catch (error) {
    await zip?.settle().catch(() => undefined);
    await sink?.abort().catch(() => undefined);
    await source.close(session).catch(() => undefined);
    throw error;
  }
}
