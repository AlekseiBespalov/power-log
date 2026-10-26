import type { ConnectionLogExport } from './bluetooth-diagnostics';
import type { ExportCommitResult, ExportSink } from './export/types';
import { ZipWriter, zipTime } from './export/zip';

export const DIAGNOSTICS_FOLDER = 'PowerLog-diagnostics';
const CHUNK_BYTES = 256 * 1024;
const encoder = new TextEncoder();

export interface DiagnosticsArchive {
  readonly exportedAt: string;
  readonly appVersion: string;
  readonly platform: string;
  readonly connection: unknown;
  readonly read: ConnectionLogExport | { readonly error: string };
}

export const diagnosticsFileName = (exportedAt: string) =>
  `power-log-diagnostics-${exportedAt
    .replace(/\.\d+Z$/, 'Z')
    .replace(/[-:]/g, '')
    .replace('T', '-')}.zip`;

async function entry(zip: ZipWriter, name: string, bytes: Uint8Array): Promise<void> {
  await zip.begin(`${DIAGNOSTICS_FOLDER}/${name}`);
  for (let offset = 0; offset < bytes.length; offset += CHUNK_BYTES)
    await zip.write(bytes.subarray(offset, offset + CHUNK_BYTES));
  await zip.end();
}

export async function writeDiagnosticsZip(sink: ExportSink, archive: DiagnosticsArchive): Promise<ExportCommitResult> {
  const zip = new ZipWriter(sink, zipTime(archive.exportedAt));
  const { read } = archive;
  const about = {
    appVersion: archive.appVersion,
    platform: archive.platform,
    exportedAt: archive.exportedAt,
    device: 'device' in read ? read.device : null,
    connection: archive.connection,
    ...('error' in read ? { connectionLogError: read.error } : {}),
  };
  try {
    await entry(zip, 'device.json', encoder.encode(`${JSON.stringify(about, null, 2)}\n`));
    await entry(zip, 'connection-log.jsonl', encoder.encode('log' in read ? read.log : ''));
    await zip.finish();
    return await sink.commit(diagnosticsFileName(archive.exportedAt));
  } catch (error) {
    await zip.settle().catch(() => undefined);
    await sink.abort().catch(() => undefined);
    throw error;
  }
}
