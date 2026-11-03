import { Platform } from 'react-native';
import bridge, { type PowerLogBridge } from '../../modules/power-log-bridge';
import { fromNativePage } from '../core/export/pages';
import { presentFailure } from '../core/failures';
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
const NATIVE_FAILURES: Record<ExportErrorCode, string> = {
  gate: "This ride isn't ready to export yet.",
  changed: 'This ride changed while it was exporting. Export it again.',
  deleted: "This ride no longer exists, so it can't be exported.",
  cursor: 'The export lost its place in the ride. Export it again.',
  limit: 'The ride is too large for a FIT file.',
  noRecords: 'This ride has no recorded data to export.',
  cancelled: 'The export was cancelled.',
  sink: FAILURES.sink,
  unsupported: 'Install the current Power Log build to export rides.',
  page: FAILURES.page,
};

function exportFailure(error: unknown, site: CallSite): ExportError {
  if (error instanceof ExportError) return error;
  const code: unknown = typeof error === 'object' && error !== null && 'code' in error ? error.code : undefined;
  if ((EXPORT_ERROR_CODES as readonly unknown[]).includes(code))
    return new ExportError(code as ExportErrorCode, NATIVE_FAILURES[code as ExportErrorCode]);
  const failure = presentFailure(error);
  return new ExportError(site, failure.code === 'unknown' ? FAILURES[site] : `${FAILURES[site]} ${failure.text}`);
}

async function call<T>(site: CallSite, operation: (native: PowerLogBridge) => Promise<T>): Promise<T> {
  if (!bridge) throw new ExportError('unsupported', 'Install the current Power Log build to export rides.');
  try {
    return await operation(bridge);
  } catch (error) {
    throw exportFailure(error, site);
  }
}

function opened(result: ExportOpenResult): ExportOpenResult {
  if (typeof result?.session !== 'string') throw new ExportError('page', FAILURES.page);
  return result;
}

function committed(result: ExportCommitResult): ExportCommitResult {
  if (typeof result?.uri !== 'string') throw new ExportError('sink', FAILURES.sink);
  return result;
}

const source: ExportSource = {
  open: request => call('page', async native => opened(await native.exportOpen(request))),
  page: request => call('page', async native => fromNativePage(await native.exportPage(request), request.projection)),
  close: session => call('page', native => native.exportClose(session)),
};

function nativeSink(id: string): ExportSink {
  let previous: Promise<unknown> = Promise.resolve();
  const queued = <T>(operation: (native: PowerLogBridge) => Promise<T>): Promise<T> => {
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
