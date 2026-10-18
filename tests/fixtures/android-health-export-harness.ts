import { exportFit } from '../../src/core/export/fit-export';
import { exportZip } from '../../src/core/export/zip-export';
import { crc32 } from '../../src/core/export/crc32';
import { fromNativePage } from '../../src/core/export/pages';
import type {
  ExportOpenResult,
  ExportPage,
  ExportPageRequest,
  ExportSink,
  ExportSource,
  ExportPlatform,
  NativeExportPage,
  ProjectionName,
} from '../../src/core/export/types';

type CapturedPage = Omit<NativeExportPage, 'columns'> & {
  columns: Record<string, { base64: string } | (string | null)[]>;
};
export type Captured = Record<
  'zip' | 'fit',
  { open: ExportOpenResult; pages: Record<ProjectionName, CapturedPage | CapturedPage[]> }
>;

export async function run(
  fixture: Captured,
  kind: 'zip' | 'fit',
  platform: ExportPlatform = 'android',
): Promise<number[]> {
  const source: ExportSource = {
    async open() {
      return fixture[kind].open;
    },
    async page<P extends ProjectionName>(request: ExportPageRequest<P>): Promise<ExportPage<P>> {
      const pages = fixture[kind].pages[request.projection];
      const index =
        request.after === null || !Array.isArray(pages)
          ? 0
          : pages.findIndex(page => JSON.stringify(page.last) === JSON.stringify(request.after)) + 1;
      const page = (Array.isArray(pages) ? pages[index]! : pages) as CapturedPage;
      const columns = Object.fromEntries(
        Object.entries(page.columns).map(([name, values]) => [
          name,
          Array.isArray(values) ? values : Uint8Array.from(atob(values.base64), byte => byte.charCodeAt(0)),
        ]),
      );
      return fromNativePage({ ...page, columns } as NativeExportPage<P>, request.projection);
    },
    async close() {},
  };
  let file = new Uint8Array(0);
  let stage: Uint8Array[] | null = null;
  function append(bytes: Uint8Array) {
    const next = new Uint8Array(file.length + bytes.length);
    next.set(file);
    next.set(bytes, file.length);
    file = next;
  }
  const sink: ExportSink = {
    async write(bytes) {
      if (stage) stage.push(bytes.slice());
      else append(bytes);
    },
    async writeAt(offset, bytes) {
      file.set(bytes, offset);
    },
    async beginDeflate() {
      stage = [];
    },
    async endDeflate() {
      const input = new Uint8Array(stage!.reduce((total, bytes) => total + bytes.length, 0));
      let offset = 0;
      for (const bytes of stage!) {
        input.set(bytes, offset);
        offset += bytes.length;
      }
      stage = null;
      const compressed = new Uint8Array(
        await new Response(new Blob([input]).stream().pipeThrough(new CompressionStream('deflate-raw'))).arrayBuffer(),
      );
      append(compressed);
      return { crc32: crc32(input), inputBytes: input.length, outputBytes: compressed.length };
    },
    async commit(name) {
      return { uri: name };
    },
    async abort() {},
  };
  const request = {
    rideId: 'generated-android-health',
    context: { platform, exportedAt: '2026-09-09T00:00:30Z' } as const,
  };
  if (kind === 'fit') await exportFit(source, async () => sink, request);
  else await exportZip(source, async () => sink, request);
  return Array.from(file);
}
