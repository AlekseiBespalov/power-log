import { readFileSync } from 'node:fs';
import { inflateRawSync, crc32 as zlibCrc32 } from 'node:zlib';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { chromium, type Browser, type Download, type Page } from '@playwright/test';
import { browserTestBundle } from '../helpers/browser-test-bundle';
import type * as Harness from '../fixtures/export-sink-harness';

declare global {
  interface Window {
    exportTests: typeof Harness;
  }
}
const ORIGIN = 'http://127.0.0.1:43131';
const bundle = browserTestBundle('tests/fixtures/export-sink-harness.ts', 'exportTests');
const DAY = 24 * 60 * 60 * 1000;
const MiB = 1024 * 1024;

let browser: Browser;
beforeAll(async () => {
  browser = await chromium.launch({ headless: true });
});
afterAll(async () => {
  await browser?.close();
});
async function run<T>(work: (page: Page) => Promise<T>): Promise<T> {
  const context = await browser.newContext();
  try {
    const page = await context.newPage();
    await page.route(`${ORIGIN}/**`, route =>
      route.fulfill({ contentType: 'text/html', body: '<!doctype html><title>Browser export</title>' }),
    );
    await page.goto(`${ORIGIN}/`);
    await page.addScriptTag({ content: bundle });
    return await work(page);
  } finally {
    await context.close();
  }
}
async function withDownload<T>(page: Page, work: () => Promise<T>): Promise<{ result: T; download: Download }> {
  const [download, result] = await Promise.all([page.waitForEvent('download'), work()]);
  return { result, download };
}
const fileOf = async (download: Download) => readFileSync((await download.path())!);
const decode = (base64: string) => Buffer.from(base64, 'base64');

function zipLocalHeader(crc: number, compressed: number, size: number): Buffer {
  const header = Buffer.alloc(30);
  header.writeUInt32LE(0x04034b50, 0);
  header.writeUInt32LE(crc, 14);
  header.writeUInt32LE(compressed, 18);
  header.writeUInt32LE(size, 22);
  return header;
}

describe('browser export sink', () => {
  const context = { exportedAt: '2026-02-01T00:00:00.000Z', platform: 'web' };
  const staging = (entries: Harness.Entry[]) =>
    entries.filter(entry => entry.path.startsWith('.powerlog-export-')).map(entry => entry.path);
  const committed = (entries: Harness.Entry[]) =>
    entries.filter(entry => entry.kind === 'file' && entry.path.includes('/')).map(entry => entry.path.split('/')[1]);

  it('writes, patches and deflates an OPFS staging file, then commits it in its own directory as a download', async () =>
    run(async page => {
      const { result, download } = await withDownload(page, () =>
        page.evaluate(async context => {
          const api = window.exportTests;
          const sink = await api.openBrowserExportSink('zip', context);
          const written: string[] = [];
          let crc = 0;
          await sink.write(new Uint8Array(30));
          await sink.beginDeflate();
          const buffer = new Uint8Array(1024 * 1024);
          for (let round = 0; round < 12; round++) {
            if (round % 2) api.fillRandom(buffer);
            else for (let i = 0; i < buffer.length; i++) buffer[i] = i % 61 === 0 ? 10 : 48 + ((i * 7 + round) % 10);
            written.push(api.base64(buffer));
            crc = api.crc32(buffer, crc);
            await sink.write(buffer);
          }
          const long = api.randomBytes(3 * 1024 * 1024 + 5);
          written.push(api.base64(long));
          crc = api.crc32(long, crc);
          await sink.write(long);
          const stage = await sink.endDeflate();
          await sink.writeAt(0, Uint8Array.of(0x50, 0x4b, 0x03, 0x04));
          const sizes = new Uint8Array(12);
          const view = new DataView(sizes.buffer);
          view.setUint32(0, stage.crc32, true);
          view.setUint32(4, stage.outputBytes, true);
          view.setUint32(8, stage.inputBytes, true);
          await sink.writeAt(14, sizes);
          await sink.write(new TextEncoder().encode('central directory'));
          const staged = await api.listing();
          const { uri } = await sink.commit('ride.zip');
          const entries = await api.listing();
          const folder = entries.find(entry => entry.kind === 'directory')?.path;
          return {
            written,
            crc,
            stage,
            staged,
            uri,
            entries,
            file: folder ? await api.readExport(`${folder}/ride.zip`) : '',
          };
        }, context),
      );
      const input = Buffer.concat(result.written.map(decode));
      const file = decode(result.file);
      const compressed = file.length - 30 - 'central directory'.length;
      expect(result.stage).toEqual({ crc32: zlibCrc32(input), inputBytes: input.length, outputBytes: compressed });
      expect(result.crc).toBe(result.stage.crc32);
      expect(file.subarray(0, 30)).toEqual(zipLocalHeader(result.stage.crc32, compressed, input.length));
      expect(Buffer.compare(inflateRawSync(file.subarray(30, 30 + compressed)), input)).toBe(0);
      expect(file.subarray(30 + compressed).toString()).toBe('central directory');
      expect(download.suggestedFilename()).toBe('ride.zip');
      expect(Buffer.compare(await fileOf(download), file)).toBe(0);
      expect(result.uri).toMatch(/^blob:/);
      const folder = result.entries[0]!.path;
      expect(folder).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/);
      expect(result.entries.map(entry => [entry.path, entry.kind, entry.size])).toEqual([
        [folder, 'directory', undefined],
        [`${folder}/ride.zip`, 'file', file.length],
      ]);
      const name = `.powerlog-export-${folder}.part`;
      expect(result.staged.find(entry => entry.path === name)).toMatchObject({ kind: 'file', size: 0 });
      expect(result.staged.every(entry => entry.path.startsWith(name))).toBe(true);
    }));

  it('deletes the staging file on abort, cancels a write in flight and never removes a committed file', async () =>
    run(async page => {
      const { result, download } = await withDownload(page, () =>
        page.evaluate(async context => {
          const api = window.exportTests;
          const first = await api.openBrowserExportSink('zip', context);
          await first.write(new Uint8Array(1000));
          await first.beginDeflate();
          await first.write(api.randomBytes(2 * 1024 * 1024));
          const during = await api.listing();
          await first.abort();
          const afterAbort = await api.listing();
          const late = [
            await api.failure(() => first.write(new Uint8Array(1))),
            await api.failure(() => first.commit('late.zip')),
            await api.failure(() => first.abort()),
          ];
          const kept = await api.openBrowserExportSink('fit', context);
          await kept.write(Uint8Array.of(1, 2, 3));
          await kept.commit('ride.fit');
          await kept.abort();
          const afterCommit = await api.listing();
          const busy = await api.openBrowserExportSink('zip', context);
          await busy.beginDeflate();
          const writing = api.failure(() => busy.write(api.randomBytes(32 * 1024 * 1024)));
          await new Promise(resolve => setTimeout(resolve, 30));
          await busy.abort();
          return { during, afterAbort, late, afterCommit, inFlight: await writing, afterBusy: await api.listing() };
        }, context),
      );
      expect(staging(result.during).length).toBeGreaterThan(0);
      expect(result.afterAbort).toEqual([]);
      expect(result.late).toEqual([
        { code: 'exportLeftScreen', message: 'The export was cancelled.' },
        { code: 'exportLeftScreen', message: 'The export was cancelled.' },
        null,
      ]);
      expect(download.suggestedFilename()).toBe('ride.fit');
      expect([...(await fileOf(download))]).toEqual([1, 2, 3]);
      const folder = result.afterCommit[0]!.path;
      expect(result.afterCommit.map(entry => [entry.path, entry.size])).toEqual([
        [folder, undefined],
        [`${folder}/ride.fit`, 3],
      ]);
      expect(result.inFlight).toMatchObject({ code: 'exportLeftScreen' });
      expect(result.afterBusy).toEqual(result.afterCommit);
    }));

  it('cleans staging files at each open and committed exports only once they are older than 24 hours', async () =>
    run(async page => {
      const result = await page.evaluate(
        async ({ context, day }) => {
          const api = window.exportTests;
          const save = async (name: string) => {
            const sink = await api.openBrowserExportSink('zip', context);
            await sink.write(new TextEncoder().encode(name));
            await sink.commit(name);
          };
          const modified = async (name: string) =>
            (await api.listing()).find(entry => entry.path.endsWith(`/${name}`))!.lastModified!;
          await save('a.zip');
          await new Promise(resolve => setTimeout(resolve, 20));
          await save('b.zip');
          const live = await api.openBrowserExportSink('zip', context);
          await live.write(new Uint8Array(64));
          const stale = crypto.randomUUID();
          await api.leaveFile(`.powerlog-export-${stale}.part`, 10);
          await api.leaveFile(`.powerlog-export-${stale}.part.crswap`, 20);
          await api.leaveFile('notes.txt', 3);
          const before = await api.listing();
          const second = await api.openBrowserExportSink('fit', context);
          await second.write(new Uint8Array(8));
          const afterOpen = await api.listing();
          const a = await modified('a.zip');
          const b = await modified('b.zip');
          await api.cleanBrowserExports(a + day + 1);
          const afterA = await api.listing();
          await api.cleanBrowserExports(b + day);
          const atBoundary = await api.listing();
          await api.cleanBrowserExports(b + day + 1);
          const afterB = await api.listing();
          const held = afterOpen
            .find(entry => entry.path.endsWith('.part'))!
            .path.slice('.powerlog-export-'.length, -'.part'.length);
          const orphan = crypto.randomUUID();
          for (const folder of [held, orphan])
            await (await api.exportsDirectory()).getDirectoryHandle(folder, { create: true });
          await api.cleanBrowserExports();
          const folders = (await api.listing()).filter(entry => entry.kind === 'directory').map(entry => entry.path);
          await live.commit('live.zip');
          await second.commit('second.fit');
          await api.cleanBrowserExports();
          return {
            stale,
            a,
            b,
            before,
            afterOpen,
            afterA,
            atBoundary,
            afterB,
            held,
            orphan,
            folders,
            final: await api.listing(),
          };
        },
        { context, day: DAY },
      );
      expect(result.folders).toEqual([result.held]);
      expect(result.orphan).not.toBe(result.held);
      const stale = [`.powerlog-export-${result.stale}.part`, `.powerlog-export-${result.stale}.part.crswap`];
      expect(result.b).toBeGreaterThan(result.a);
      expect(staging(result.before)).toEqual(expect.arrayContaining(stale));
      expect(committed(result.before)).toEqual(expect.arrayContaining(['a.zip', 'b.zip']));
      expect(staging(result.afterOpen)).not.toEqual(expect.arrayContaining([stale[0]]));
      expect(staging(result.afterOpen).some(path => path.includes(result.stale))).toBe(false);
      expect(staging(result.afterOpen).filter(path => path.endsWith('.part'))).toHaveLength(2);
      expect(result.afterOpen.map(entry => entry.path)).toContain('notes.txt');
      expect(committed(result.afterOpen).sort()).toEqual(['a.zip', 'b.zip']);
      expect(committed(result.afterA)).toEqual(['b.zip']);
      expect(result.afterA.filter(entry => entry.kind === 'directory')).toHaveLength(1);
      expect(staging(result.afterA)).toEqual(staging(result.afterOpen));
      expect(committed(result.atBoundary)).toEqual(['b.zip']);
      expect(committed(result.afterB)).toEqual([]);
      expect(staging(result.afterB)).toEqual(staging(result.afterOpen));
      expect(committed(result.final).sort()).toEqual(['live.zip', 'second.fit']);
      expect(staging(result.final)).toEqual([]);
      expect(result.final.map(entry => entry.path)).toContain('notes.txt');
    }));

  it.each(['afterFolderCheck', 'everyAttempt'] as const)(
    'keeps an export whose commit finishes while another cleanup inspects its folder (%s)',
    async hold =>
      run(async page => {
        const result = await page.evaluate(hold => window.exportTests.cleanDuringCommit(hold), hold);
        expect(result.entries.map(entry => entry.path)).toEqual([result.uuid, `${result.uuid}/committed.zip`]);
        expect(decode(result.content!).toString()).toBe('committed during cleanup');
      }),
  );

  it('falls back to an in-memory sink capped at 128 MiB without OPFS', async () =>
    run(async page => {
      const { result, download } = await withDownload(page, () =>
        page.evaluate(async context => {
          const api = window.exportTests;
          api.withoutOpfs();
          const text = new TextEncoder().encode('time,power\n' + '0,100\n'.repeat(20_000));
          const sink = await api.openBrowserExportSink('zip', context);
          await sink.write(new Uint8Array(30));
          await sink.beginDeflate();
          await sink.write(text);
          const stage = await sink.endDeflate();
          const crc = new Uint8Array(4);
          new DataView(crc.buffer).setUint32(0, stage.crc32, true);
          await sink.writeAt(14, crc);
          await sink.write(Uint8Array.of(7));
          const { uri } = await sink.commit('memory.zip');
          const limit = api.BROWSER_EXPORT_MEMORY_LIMIT;
          const block = new Uint8Array(8 * 1024 * 1024);
          const full = await api.openBrowserExportSink('zip', context);
          for (let size = 0; size < limit; size += block.length) await full.write(block);
          const overflow = await api.failure(() => full.write(new Uint8Array(1)));
          await full.abort();
          const deflated = await api.openBrowserExportSink('zip', context);
          for (let size = 0; size < limit - block.length; size += block.length) await deflated.write(block);
          await deflated.write(block.subarray(0, block.length - 65_536));
          await deflated.beginDeflate();
          const deflateOverflow = await api.failure(async () => {
            await deflated.write(api.randomBytes(1024 * 1024));
            await deflated.endDeflate();
          });
          await deflated.abort();
          return { text: api.base64(text), stage, uri, limit, overflow, deflateOverflow, opfs: await api.listing() };
        }, context),
      );
      const text = decode(result.text);
      const file = await fileOf(download);
      expect(download.suggestedFilename()).toBe('memory.zip');
      expect(result.uri).toMatch(/^blob:/);
      expect(result.stage).toEqual({
        crc32: zlibCrc32(text),
        inputBytes: text.length,
        outputBytes: file.length - 31,
      });
      expect(file.readUInt32LE(14)).toBe(zlibCrc32(text));
      expect(file.subarray(0, 14)).toEqual(Buffer.alloc(14));
      expect(Buffer.compare(inflateRawSync(file.subarray(30, file.length - 1)), text)).toBe(0);
      expect(file.at(-1)).toBe(7);
      expect(result.limit).toBe(128 * MiB);
      expect(result.overflow).toMatchObject({ code: 'exportLimit', message: expect.stringContaining('128 MiB') });
      expect(result.deflateOverflow).toMatchObject({
        code: 'exportLimit',
        message: expect.stringContaining('128 MiB'),
      });
      expect(result.opfs).toEqual([]);
    }));

  it.each(['OPFS', 'memory'])('yields to the event loop while the %s sink deflates a long buffer', async storage =>
    run(async page => {
      const result = await page.evaluate(
        async ({ context, storage }) => {
          const api = window.exportTests;
          if (storage === 'memory') api.withoutOpfs();
          const sink = await api.openBrowserExportSink('zip', context);
          const data = api.randomBytes(32 * 1024 * 1024);
          await sink.beginDeflate();
          const { stallMs } = await api.longestStall(() => sink.write(data));
          const stage = await sink.endDeflate();
          await sink.abort();
          return { stallMs, crc: stage.crc32 === api.crc32(data), inputBytes: stage.inputBytes };
        },
        { context, storage },
      );
      expect(result.crc).toBe(true);
      expect(result.inputBytes).toBe(32 * MiB);
      expect(result.stallMs).toBeLessThan(200);
    }),
  );

  it('rejects writes it cannot honour', async () =>
    run(async page => {
      const result = await page.evaluate(async context => {
        const api = window.exportTests;
        type Sink = Awaited<ReturnType<typeof api.openBrowserExportSink>>;
        const ten = new Uint8Array(10);
        const fresh = async (setup: (sink: Sink) => Promise<unknown>, misuse: (sink: Sink) => Promise<unknown>) => {
          const sink = await api.openBrowserExportSink('zip', context);
          await setup(sink);
          const outcome = await api.failure(() => misuse(sink));
          await sink.abort();
          return outcome;
        };
        const staged = async (sink: Sink) => {
          await sink.write(ten);
          await sink.beginDeflate();
          await sink.write(ten);
        };
        const names = [];
        for (const name of ['', 'a/b', 'a\\b', '..', '.', 'line\nbreak'])
          names.push(
            await fresh(
              async () => {},
              sink => sink.commit(name),
            ),
          );
        const saved = await api.openBrowserExportSink('fit', context);
        await saved.write(ten);
        await saved.commit('done.fit');
        return {
          beyondEnd: await fresh(
            sink => sink.write(ten),
            sink => sink.writeAt(8, new Uint8Array(4)),
          ),
          negative: await fresh(
            sink => sink.write(ten),
            sink => sink.writeAt(-1, new Uint8Array(1)),
          ),
          intoStage: await fresh(staged, sink => sink.writeAt(8, new Uint8Array(4))),
          beforeStage: await fresh(staged, async sink => {
            await sink.writeAt(2, new Uint8Array(8));
            await sink.endDeflate();
          }),
          nested: await fresh(
            sink => sink.beginDeflate(),
            sink => sink.beginDeflate(),
          ),
          unopened: await fresh(
            async () => {},
            sink => sink.endDeflate(),
          ),
          commitInStage: await fresh(
            sink => sink.beginDeflate(),
            sink => sink.commit('ride.zip'),
          ),
          names,
          overlap: await fresh(
            async () => {},
            async sink => {
              const first = sink.write(new Uint8Array(1024));
              const second = sink.write(new Uint8Array(1));
              await first;
              await second;
            },
          ),
          afterFailure: await fresh(
            async sink => {
              await sink.write(ten);
              await sink.writeAt(20, ten).catch(() => {});
            },
            sink => sink.write(ten),
          ),
          afterCommit: await api.failure(() => saved.write(ten)),
          kind: await api.failure(() => api.openBrowserExportSink('gpx' as Harness.FileKindName, context)),
        };
      }, context);
      for (const key of ['beyondEnd', 'negative', 'intoStage', 'nested', 'unopened', 'commitInStage'] as const)
        expect(result[key], key).toMatchObject({ code: 'exportSinkFailed' });
      expect(result.beforeStage).toBeNull();
      expect(result.names).toEqual(
        Array(6).fill({ code: 'exportSinkFailed', message: 'The export file name is not valid.' }),
      );
      expect(result.overlap).toEqual({ code: 'exportSinkFailed', message: 'Export writes must not overlap.' });
      expect(result.afterFailure).toMatchObject({
        code: 'exportSinkFailed',
        message: expect.stringContaining('earlier write'),
      });
      expect(result.afterCommit).toEqual({ code: 'exportSinkFailed', message: 'This export file is already saved.' });
      expect(result.kind).toMatchObject({ code: 'exportSinkFailed' });
    }));
});
