import { runInNewContext } from 'node:vm';
import { browserTestBundle } from '../helpers/browser-test-bundle';
import { readFileSync, writeFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { exportFit } from '../../src/core/export/fit-export';
import { fromNativePage } from '../../src/core/export/pages';
import type {
  ExportOpenResult,
  ExportPage,
  ExportPageRequest,
  ExportSource,
  NativeExportPage,
  ProjectionName,
} from '../../src/core/export/types';
import { exportZip } from '../../src/core/export/zip-export';
import { decodeFit, fitPython, readFit } from '../fixtures/export/fit/ride';
import { MemorySink, entryText, readZip } from '../fixtures/export/zip/harness';

type CapturedPage = Omit<NativeExportPage, 'columns'> & {
  columns: Record<string, { base64: string } | (string | null)[]>;
};
type Captured = Record<'zip' | 'fit', { open: ExportOpenResult; pages: Record<ProjectionName, CapturedPage> }>;
const captured = (): Captured =>
  JSON.parse(readFileSync('tests/fixtures/export/android-health-native.json', 'utf8')) as Captured;
const context = { platform: 'android', exportedAt: '2026-09-09T00:00:30Z' } as const;

function source(): ExportSource {
  const fixture = captured();
  let kind: 'zip' | 'fit' = 'zip';
  return {
    async open(request) {
      kind = request.kind;
      return fixture[kind].open;
    },
    async page<P extends ProjectionName>(request: ExportPageRequest<P>): Promise<ExportPage<P>> {
      const page = fixture[kind].pages[request.projection];
      const columns = Object.fromEntries(
        Object.entries(page.columns).map(([name, values]) => [
          name,
          Array.isArray(values) ? values : Uint8Array.from(Buffer.from(values.base64, 'base64')),
        ]),
      );
      return fromNativePage({ ...page, columns } as NativeExportPage<P>, request.projection);
    },
    async close() {},
  };
}

async function exported(kind: 'zip' | 'fit') {
  const sink = new MemorySink();
  const request = { rideId: 'generated-android-health', context };
  if (kind === 'fit') await exportFit(source(), async () => sink, request);
  else await exportZip(source(), async () => sink, request);
  return sink.file;
}

function golden(name: string, value: unknown): unknown {
  const path = `tests/fixtures/export/${name}.json`;
  if (process.env.POWER_LOG_UPDATE_ANDROID_HEALTH_FIXTURES === '1')
    writeFileSync(path, JSON.stringify(value, null, 2) + '\n');
  return JSON.parse(readFileSync(path, 'utf8')) as unknown;
}

describe('Android physiology through native pages and production exporters', () => {
  it('exports FIT with clipped interval energy and selected HR', async () => {
    const file = await exported('fit');
    const messages = readFit(file);
    const session = messages.find(message => message.global === 18)!;
    expect(session.fields.get(11)).toBe(10);
    expect(decodeFit(fitPython()!, file).messages.find(message => message.mesg_num === 18)!.fields.total_calories).toBe(
      10,
    );
    const heart = messages.filter(message => message.global === 20).map(message => message.fields.get(3));
    expect(heart).toEqual([100, 120, 140]);
    const expected = { base64: Buffer.from(file).toString('base64') };
    expect(expected).toEqual(golden('android-health.fit', expected));
  });

  it('runs the browser-bundled production FIT exporter with the generated core', async () => {
    const window: { fit?: { exportFit: typeof exportFit } } = {};
    runInNewContext(browserTestBundle('src/core/export/fit-export.ts', 'fit'), {
      window,
      Uint8Array,
      Float64Array,
      Int32Array,
      Int16Array,
      Uint16Array,
      Uint32Array,
      DataView,
      ArrayBuffer,
      TextEncoder,
      TextDecoder,
      Date,
      Number,
      setTimeout,
      queueMicrotask,
    });
    const sink = new MemorySink();
    await window.fit!.exportFit(source(), async () => sink, { rideId: 'generated-android-health', context });
    const expected = JSON.parse(readFileSync('tests/fixtures/export/android-health.fit.json', 'utf8')) as {
      base64: string;
    };
    expect(Buffer.from(sink.file).toString('base64')).toEqual(expected.base64);
    expect(
      readFit(sink.file)
        .find(message => message.global === 18)!
        .fields.get(11),
    ).toBe(10);
  });

  it('exports ZIP with retained provider, parent, member, origin and original times', async () => {
    const file = await exported('zip');
    const entries = Object.fromEntries(readZip(file).map(entry => [entry.name, entryText(entry)]));
    const csv = entries['PowerLog-original/health.csv']!;
    const header = csv.split('\n')[0]!.split(',');
    expect(header).toEqual(
      expect.arrayContaining([
        'provider',
        'parentId',
        'memberIndex',
        'dataOrigin',
        'originalEventId',
        'originalEndTimestamp',
        'logicalKey',
        'evidence',
      ]),
    );
    expect(csv).toContain('healthConnect');
    expect(csv).toContain('app.generated.lab');
    expect(csv).toContain('99999999-8888-4777-8666-555555555555');
    expect(csv).toContain('2026-09-08T23:59:55Z');
    expect(csv).toContain('2026-09-09T00:00:15Z');
    expect(csv).toContain('intervalRecord');
    expect(entries).toEqual(golden('android-health.zip', entries));
  });
});
