import type { ApiFailure, ExportSessionView, FailureCode } from '../../../../shared/generated/ts/api';
import type { PowerLogClient, Result } from '../client';
import { encodeCsv, encodeImportedCsv } from './csv';
import { encodeFit } from './fit';
import { ExportPageReader, PageFailure, SessionClosed } from './reader';
import { createSlicer, exportLeftScreen, type ExportSlicer } from './slicer';
import { ExportError, type ExportFile, type ExportSink, type OpenSink, type SharedFile } from './types';
import { encodeZip } from './zip-export';

const failure = (code: FailureCode): ApiFailure => ({ code, retryable: false, occurrence: null, detail: null });

export const csvName = (name: string) =>
  `${
    name
      .replace(/\.csv$/i, '')
      .replace(/[^a-zA-Z0-9_.-]/g, '-')
      .replace(/^\.+/, '')
      .slice(0, 120) || 'telemetry'
  }.csv`;

function fileName(file: ExportFile): string {
  if (file.source.kind === 'imported') return csvName(file.name ?? '');
  const rideId = file.source.rideId;
  if (file.kind === 'zip') return `power-log-original-${rideId}.zip`;
  return `power-log-${rideId}.${file.kind}`;
}

function encode(
  reader: ExportPageReader,
  view: ExportSessionView,
  sink: ExportSink,
  slicer: ExportSlicer,
  file: ExportFile,
): Promise<void> {
  if (file.source.kind === 'imported') return encodeImportedCsv(reader, view, sink, slicer);
  if (file.kind === 'fit') return encodeFit(reader, view, sink, slicer);
  if (file.kind === 'csv') return encodeCsv(reader, view, sink, slicer);
  return encodeZip(reader, view, sink, slicer, file.source.rideId, file.exportedAt);
}

function failureOf(error: unknown): ApiFailure {
  if (error instanceof PageFailure) return error.failure;
  if (error instanceof ExportError) return failure(error.code);
  return failure('unknown');
}

// exports.open → the sink → pages into the encoder → sinkCommit → exports.close; an abort or a failure before the
// commit aborts the sink and closes the session, and nothing after the commit aborts.
export async function exportFile(
  client: PowerLogClient,
  file: ExportFile,
  openSink: OpenSink,
  signal?: AbortSignal,
): Promise<Result<SharedFile>> {
  const slicer = createSlicer(signal);
  const reader = new ExportPageReader(client, slicer);
  let sessionId: string | null = null;
  let sink: ExportSink | null = null;
  try {
    if (signal?.aborted) throw exportLeftScreen();
    const view = await reader.open({ source: file.source, kind: file.kind, fitPower: file.fitPower });
    sessionId = view.sessionId;
    if (signal?.aborted) throw exportLeftScreen();
    sink = await openSink(file.kind);
    await encode(reader, view, sink, slicer, file);
    if (signal?.aborted) throw exportLeftScreen();
    const shared = await sink.commit(fileName(file));
    sink = null;
    await reader.close(sessionId, null);
    return { ok: true, value: shared };
  } catch (error) {
    await sink?.abort().catch(() => undefined);
    const left = signal?.aborted === true;
    if (!left && error instanceof SessionClosed) return { cancelled: true };
    const answer = left ? failure('exportLeftScreen') : failureOf(error);
    if (sessionId !== null)
      await reader.close(sessionId, answer.code === 'exportLeftScreen' ? null : answer.code).catch(() => undefined);
    if (!left && answer.code === 'unknown' && !(error instanceof PageFailure)) throw error;
    return { ok: false, failure: answer };
  }
}
