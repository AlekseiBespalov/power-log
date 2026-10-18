import { execFileSync } from 'node:child_process';
import { expect, it } from 'vitest';
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
import { readNativeFixture } from '../fixtures/export/native';
import { entryText, MemorySink, readZip } from '../fixtures/export/zip/harness';

type CapturedPage = Omit<NativeExportPage, 'columns'> & {
  columns: Record<string, { base64: string } | (string | null)[]>;
};
type Capture = {
  open: ExportOpenResult;
  pages: Record<ProjectionName, CapturedPage[]>;
  originalMembers: string[];
};
function capture(name: string) {
  const fixture = (
    readNativeFixture('tests/fixtures/contract/export/health-evidence-ios.json') as Record<string, Capture>
  )[name]!;
  const closed: string[] = [];
  const source: ExportSource = {
    async open() {
      return fixture.open;
    },
    async page<P extends ProjectionName>(request: ExportPageRequest<P>): Promise<ExportPage<P>> {
      const pages = fixture.pages[request.projection];
      const index =
        request.after === null
          ? 0
          : pages.findIndex(page => JSON.stringify(page.last) === JSON.stringify(request.after)) + 1;
      const page = pages[index]!;
      const columns = Object.fromEntries(
        Object.entries(page.columns).map(([name, values]) => [
          name,
          Array.isArray(values) ? values : Uint8Array.from(Buffer.from(values.base64, 'base64')),
        ]),
      );
      return fromNativePage({ ...page, columns } as NativeExportPage<P>, request.projection);
    },
    async close(session) {
      closed.push(session);
    },
  };
  return { fixture, source, closed };
}
async function exported(name: string) {
  const captured = capture(name);
  const sink = new MemorySink();
  await exportZip(captured.source, async () => sink, {
    rideId: `generated-${name}`,
    context: { platform: 'ios', exportedAt: '2026-01-01T00:00:30.000Z' },
  });
  const entries = readZip(sink.file);
  const csv = entryText(entries.find(entry => entry.name.endsWith('/health.csv'))!);
  const rows = JSON.parse(
    execFileSync(
      'python3',
      ['-c', 'import csv,io,json,sys; print(json.dumps(list(csv.DictReader(io.StringIO(sys.stdin.read())))))'],
      { input: csv, encoding: 'utf8' },
    ),
  ) as Record<string, string>[];
  const descriptor = JSON.parse(entryText(entries.find(entry => entry.name.endsWith('/datapackage.json'))!)) as {
    'powerLog:sources': { health: string[] };
  };
  expect(sink.aborted).toBe(false);
  expect(sink.committed).toBe(`power-log-original-generated-${name}.zip`);
  expect(captured.closed).toEqual(['captured-native']);
  return { ...captured, rows, descriptor };
}

it.each(['last-phone-deleted', 'watch-beside-phone-evidence'])(
  'exports retained iPhone evidence when %s through the production ZIP exporter',
  async name => {
    const { fixture, rows, descriptor } = await exported(name);
    const expected = name === 'last-phone-deleted' ? ['phone'] : ['phone', 'watch'];
    expect(fixture.open.producers.health).toEqual(expected);
    expect(descriptor['powerLog:sources'].health).toEqual(expected);
    expect([...new Set(rows.map(row => row.source))].sort()).toEqual(expected);
    expect(
      rows
        .filter(row => row.source === 'phone')
        .map(row => row.kind)
        .sort(),
    ).toEqual(['healthTombstone', 'workoutAssociation']);
    for (const row of rows.filter(row => row.source === 'phone')) {
      expect(row.originalEventId).not.toBe('');
      expect(row.evidence).not.toBe('');
      expect(row.value).toBe('');
    }
    expect(rows.filter(row => row.source === 'watch')).toHaveLength(name === 'last-phone-deleted' ? 0 : 1);
  },
);

it.each(['last-phone-deleted', 'series-member-deleted', 'series-parent-deleted', 'unknown-parent-deleted'])(
  'exports event identity targets when %s through the production ZIP exporter',
  async name => {
    const native = capture(name).fixture;
    const target = name === 'series-member-deleted' || name === 'last-phone-deleted' ? native.originalMembers[0] : '';
    const deletionPage = native.pages.healthZip.find(page =>
      (page.columns.representation as (string | null)[]).includes('healthTombstone'),
    )!;
    const index = (deletionPage.columns.representation as (string | null)[]).indexOf('healthTombstone');
    expect((deletionPage.columns.targetEventId as (string | null)[])[index] ?? '').toBe(target);
    const { fixture, rows } = await exported(name);
    const deletion = rows.find(row => row.kind === 'healthTombstone')!;
    expect(deletion.targetEventId).toBe(target);
    expect(deletion.targetEventId).not.toBe(deletion.parentId);
    if (name === 'series-member-deleted') {
      expect(rows.some(row => row.originalEventId === fixture.originalMembers[0])).toBe(false);
      expect(rows.some(row => row.originalEventId === fixture.originalMembers[1])).toBe(true);
    } else expect(rows).toHaveLength(name === 'last-phone-deleted' ? 2 : 1);
  },
);
