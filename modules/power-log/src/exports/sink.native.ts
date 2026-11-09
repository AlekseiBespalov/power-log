import nativeModule from '../native-module';
import { ExportError, type ExportSink, type FileKind, type OpenSink, type SharedFile } from './types';

// The module rejects with code full, io, invalid, uiUnavailable or failed (50 §3.12).
function rejected(error: unknown): ExportError {
  const code = (error as { code?: unknown } | null)?.code;
  if (code === 'full') return new ExportError('storageFull', "There isn't enough space for this export.");
  if (code === 'uiUnavailable') return new ExportError('uiUnavailable', 'Power Log has no screen to share from.');
  return new ExportError('exportSinkFailed', "Power Log couldn't write the export file.");
}

async function call<T>(work: () => Promise<T>): Promise<T> {
  try {
    return await work();
  } catch (error) {
    throw rejected(error);
  }
}

function module() {
  const powerLog = nativeModule;
  if (!powerLog) throw new ExportError('exportSinkFailed', 'The PowerLog native module is missing.');
  return powerLog;
}

export async function cleanExports(): Promise<void> {}

export const openSink: OpenSink = async kind => {
  const powerLog = module();
  const id = await call(() => powerLog.sinkOpen(kind));
  const sink: ExportSink = {
    write: bytes => call(() => powerLog.sinkWrite(id, bytes)),
    writeAt: (offset, bytes) => call(() => powerLog.sinkWriteAt(id, offset, bytes)),
    beginDeflate: () => call(() => powerLog.sinkBeginDeflate(id)),
    endDeflate: () => call(() => powerLog.sinkEndDeflate(id)),
    commit: name => call(() => powerLog.sinkCommit(id, name)),
    abort: () => powerLog.sinkAbort(id).catch(() => undefined),
  };
  return sink;
};

export function purge(kind: FileKind): Promise<void> {
  return call(() => module().sinkPurge(kind));
}

export function share(file: SharedFile): Promise<'shared' | 'dismissed'> {
  return call(() => module().share(file));
}
