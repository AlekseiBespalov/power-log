import { Platform } from 'react-native';
import bridge, { type CycBridge } from '../../modules/cyc-bridge';
import { fromNativePage } from '../core/export/pages';
import {
  EXPORT_ERROR_CODES,
  ExportError,
  type ExportCommitResult,
  type ExportErrorCode,
  type ExportOpenResult,
  type ExportPlatform,
  type ExportSink,
  type ExportSource,
  type OpenExportSink,
} from '../core/export/types';

type CallSite = 'page' | 'sink';

const FAILURES: Record<CallSite, string> = {
  page: 'Power Log could not read this ride for export.',
  sink: 'Power Log could not write the export file.',
};
const CAUSE = '\n→ Caused by: ';
// iOS Expo rejections carry each exception of the cause chain as `Name: reason (at File.swift:line)`.
const DECORATED = /^[A-Za-z_][\w.]*(?:<[^>]*>)?: ([\s\S]*) \(at [^\s()]+:\d+\)$/;

function nativeMessage(error: unknown): string {
  const message = typeof error === 'object' && error !== null && 'message' in error ? error.message : error;
  const text = typeof message === 'string' ? message : '';
  const cause = text.lastIndexOf(CAUSE);
  const last = cause < 0 ? text : text.slice(cause + CAUSE.length);
  return (DECORATED.exec(last)?.[1] ?? last).trim();
}

function exportFailure(error: unknown, site: CallSite): ExportError {
  if (error instanceof ExportError) return error;
  const code: unknown = typeof error === 'object' && error !== null && 'code' in error ? error.code : undefined;
  const message = nativeMessage(error);
  if ((EXPORT_ERROR_CODES as readonly unknown[]).includes(code))
    return new ExportError(code as ExportErrorCode, message || FAILURES[site]);
  return new ExportError(site, message ? `${FAILURES[site]} ${message}` : FAILURES[site]);
}

async function call<T>(site: CallSite, operation: (native: CycBridge) => Promise<T>): Promise<T> {
  if (!bridge) throw new ExportError('unsupported', 'Install the current Power Log build to export rides.');
  try {
    return await operation(bridge);
  } catch (error) {
    throw exportFailure(error, site);
  }
}

function opened(result: ExportOpenResult): ExportOpenResult {
  if (typeof result?.session !== 'string')
    throw new ExportError('page', `${FAILURES.page} The export session is missing.`);
  return result;
}

function committed(result: ExportCommitResult): ExportCommitResult {
  if (typeof result?.uri !== 'string') throw new ExportError('sink', `${FAILURES.sink} The saved file is missing.`);
  return result;
}

const source: ExportSource = {
  open: request => call('page', async native => opened(await native.exportOpen(request))),
  page: request => call('page', async native => fromNativePage(await native.exportPage(request), request.projection)),
  close: session => call('page', native => native.exportClose(session)),
};

function nativeSink(id: string): ExportSink {
  let previous: Promise<unknown> = Promise.resolve();
  const queued = <T>(operation: (native: CycBridge) => Promise<T>): Promise<T> => {
    const next = previous.then(() => call('sink', operation));
    previous = next.catch(() => undefined);
    return next;
  };
  return {
    write: bytes => queued(native => native.sinkWrite(id, bytes)),
    writeAt: (offset, bytes) => queued(native => native.sinkWriteAt(id, offset, bytes)),
    beginDeflate: () => queued(native => native.sinkBeginDeflate(id)),
    endDeflate: () => queued(native => native.sinkEndDeflate(id)),
    commit: name => queued(async native => committed(await native.sinkCommit(id, name))),
    abort: () => queued(native => native.sinkAbort(id)),
  };
}

export const exportSource = (): ExportSource => source;

export const openExportSink: OpenExportSink = async (kind, context) => {
  const id = await call('sink', native => native.sinkOpen(kind, context));
  if (typeof id !== 'string' || id === '') throw new ExportError('sink', FAILURES.sink);
  return nativeSink(id);
};

export const exportPlatform = (): ExportPlatform => (Platform.OS === 'ios' ? 'ios' : 'android');
