import { APP_VERSION } from '../core/app-info';
import { writeDiagnosticsZip } from '../core/connection-diagnostics';
import { deviceAdapter } from './device';
import { exportPlatform, openExportSink } from './export-runtime';
import { shareExport } from './files';

export async function shareConnectionDiagnostics(): Promise<void> {
  const read = deviceAdapter.readConnectionDiagnostics;
  if (!read) throw new Error('Share diagnostics needs the Power Log app on iPhone or Android.');
  const exportedAt = new Date().toISOString();
  const platform = exportPlatform();
  const log = await read().catch((error: unknown) => ({
    error: error instanceof Error ? error.message : String(error),
  }));
  const connection = (await deviceAdapter.getDiagnostics?.().catch(() => null)) ?? null;
  const sink = await openExportSink('zip', { exportedAt, platform });
  const { uri } = await writeDiagnosticsZip(sink, {
    exportedAt,
    appVersion: APP_VERSION,
    platform,
    connection,
    read: log,
  });
  await shareExport(uri);
}
