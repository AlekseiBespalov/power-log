import type { DistanceSource } from '../core/distance';
import { exportFit } from '../core/export/fit-export';
import { exportCancelled } from '../core/export/pages';
import { ExportError, type ExportCommitResult, type ExportKind } from '../core/export/types';
import { exportZip } from '../core/export/zip-export';
import { writeRecordingCsv, type ImportedSample } from '../core/recordings';
import { subscribeAppBackground } from './app-visibility';
import { exportPlatform, exportSource, openExportSink } from './export-runtime';
import { shareExport } from './files';

const context = () => ({ exportedAt: new Date().toISOString(), platform: exportPlatform() });

async function inForeground(work: (signal: AbortSignal) => Promise<ExportCommitResult>): Promise<string> {
  const controller = new AbortController();
  const stop = subscribeAppBackground(() => controller.abort());
  try {
    return (await work(controller.signal)).uri;
  } catch (error) {
    if (controller.signal.aborted && error instanceof ExportError && error.code === 'cancelled')
      throw new ExportError('cancelled', 'The export stopped because Power Log left the screen. Export again.');
    throw error;
  } finally {
    stop();
  }
}

export async function exportRide(kind: ExportKind, rideId: string, distanceSource: DistanceSource = 'auto') {
  const uri = await inForeground(signal => {
    const request = { rideId, context: context(), signal };
    return kind === 'fit'
      ? exportFit(exportSource(), openExportSink, { ...request, distanceSource })
      : exportZip(exportSource(), openExportSink, request);
  });
  await shareExport(uri);
}

const csvName = (name: string) =>
  `${
    name
      .replace(/\.csv$/i, '')
      .replace(/[^a-zA-Z0-9_.-]/g, '-')
      .replace(/^\.+/, '')
      .slice(0, 120) || 'telemetry'
  }.csv`;

export async function exportRecording(name: string, samples: readonly ImportedSample[]) {
  const uri = await inForeground(async signal => {
    const sink = await openExportSink('csv', context());
    const abort = () => void sink.abort().catch(() => undefined);
    signal.addEventListener('abort', abort);
    try {
      await writeRecordingCsv(samples, sink);
      if (signal.aborted) throw exportCancelled();
      return await sink.commit(csvName(name));
    } catch (error) {
      await sink.abort().catch(() => undefined);
      throw signal.aborted ? exportCancelled() : error;
    } finally {
      signal.removeEventListener('abort', abort);
    }
  });
  await shareExport(uri);
}
