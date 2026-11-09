import type { DiagnosticsPage, TraceDocument } from '../../../../shared/generated/ts/api';
import type { ExportSink } from './types';
import { ZipWriter, zipTime } from './zip';

const FOLDER = 'PowerLog-diagnostics';
const CHUNK_BYTES = 256 * 1024;
const encoder = new TextEncoder();

export interface DiagnosticsContext {
  readonly appVersion: string;
  readonly platform: string;
  readonly exportedAt: string;
}

export const diagnosticsFileName = (exportedAt: string) =>
  `power-log-diagnostics-${exportedAt
    .replace(/\.\d+Z$/, 'Z')
    .replace(/[-:]/g, '')
    .replace('T', '-')}.zip`;

async function entry(zip: ZipWriter, name: string, bytes: Uint8Array): Promise<void> {
  await zip.begin(`${FOLDER}/${name}`);
  for (let offset = 0; offset < bytes.length; offset += CHUNK_BYTES)
    await zip.write(bytes.subarray(offset, offset + CHUNK_BYTES));
  await zip.end();
}

export async function encodeDiagnostics(
  sink: ExportSink,
  context: DiagnosticsContext,
  pages: readonly DiagnosticsPage[],
): Promise<void> {
  const first = pages[0];
  const about = {
    appVersion: context.appVersion,
    platform: context.platform,
    exportedAt: context.exportedAt,
    device: first?.device ?? null,
    connection: first?.connection ?? null,
    ...(first?.logError ? { connectionLogError: first.logError } : {}),
  };
  const zip = new ZipWriter(sink, zipTime(context.exportedAt));
  await entry(zip, 'device.json', encoder.encode(`${JSON.stringify(about, null, 2)}\n`));
  await entry(zip, 'connection-log.jsonl', encoder.encode(pages.map(page => page.log).join('')));
  await zip.finish();
}

export async function encodeTrace(sink: ExportSink, document: TraceDocument): Promise<void> {
  await sink.write(encoder.encode(JSON.stringify(document, null, 2)));
}
