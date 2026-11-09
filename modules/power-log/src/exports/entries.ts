import type { ApiFailure, DiagnosticsPage, FitPowerSource } from '../../../../shared/generated/ts/api';
import type { PowerLogClient, Result } from '../client';
import { encodeDiagnostics, encodeTrace, diagnosticsFileName } from './diagnostics';
import { exportFile } from './export-file';
import { cleanExports, openSink, purge, share } from './sink';
import { ExportError, type ExportFile, type FileKind, type SharedFile } from './types';

export type Shared = 'shared' | 'dismissed';

export interface ExportEntries {
  shareRide(
    rideId: string,
    kind: 'fit' | 'csv' | 'zip',
    fitPower: FitPowerSource,
    signal: AbortSignal,
  ): Promise<Result<Shared>>;
  shareImported(importId: string, name: string, signal: AbortSignal): Promise<Result<Shared>>;
}

export interface DiagnosticsEntries {
  share(): Promise<Result<Shared>>;
  shareTrace(): Promise<Result<Shared>>;
  purgeTrace(): Promise<Result<void>>;
}

export interface EntriesContext {
  readonly platform: string;
  readonly appVersion: string;
}

const failure = (code: ApiFailure['code']): ApiFailure => ({ code, retryable: false, occurrence: null, detail: null });

function answered(error: unknown): Result<never> {
  if (error instanceof ExportError) return { ok: false, failure: failure(error.code) };
  return { ok: false, failure: failure('unknown') };
}

async function shareFile(file: SharedFile): Promise<Result<Shared>> {
  try {
    return { ok: true, value: await share(file) };
  } catch (error) {
    return answered(error);
  }
}

async function written(
  kind: FileKind,
  name: string,
  write: (sink: Awaited<ReturnType<typeof openSink>>) => Promise<void>,
) {
  const sink = await openSink(kind);
  try {
    await write(sink);
    return await sink.commit(name);
  } catch (error) {
    await sink.abort().catch(() => undefined);
    throw error;
  }
}

export function createExportEntries(
  client: PowerLogClient,
  context: EntriesContext,
  now: () => string = () => new Date().toISOString(),
): ExportEntries & DiagnosticsEntries {
  void cleanExports().catch(() => undefined);
  const exported = async (file: ExportFile, signal: AbortSignal): Promise<Result<Shared>> => {
    try {
      const result = await exportFile(client, file, openSink, signal);
      if ('cancelled' in result || !result.ok) return result;
      return await shareFile(result.value);
    } catch (error) {
      return answered(error);
    }
  };
  return {
    shareRide: (rideId, kind, fitPower, signal) =>
      exported({ source: { kind: 'ride', rideId }, kind, fitPower, exportedAt: now() }, signal),
    shareImported: (importId, name, signal) =>
      exported(
        { source: { kind: 'imported', importId }, kind: 'csv', fitPower: 'rider', exportedAt: now(), name },
        signal,
      ),
    async share() {
      try {
        const pages: DiagnosticsPage[] = [];
        let after: number | null = null;
        do {
          const result: Result<DiagnosticsPage> = await client.call('diagnostics.readLog', { after });
          if ('cancelled' in result || !result.ok) return result;
          pages.push(result.value);
          after = result.value.next;
        } while (after !== null);
        const exportedAt = now();
        const file = await written('zip', diagnosticsFileName(exportedAt), sink =>
          encodeDiagnostics(sink, { ...context, exportedAt }, pages),
        );
        return await shareFile(file);
      } catch (error) {
        return answered(error);
      }
    },
    async shareTrace() {
      try {
        await client.call('diagnostics.stopTrace', {});
        const result = await client.call('diagnostics.readTrace', {});
        if ('cancelled' in result || !result.ok) return result;
        const document = { ...result.value, exportedAt: now() };
        const file = await written('json', 'PowerLog-Bluetooth.json', sink => encodeTrace(sink, document));
        return await shareFile(file);
      } catch (error) {
        return answered(error);
      }
    },
    async purgeTrace() {
      try {
        await purge('json');
        return { ok: true, value: undefined };
      } catch (error) {
        return answered(error);
      }
    },
  };
}
