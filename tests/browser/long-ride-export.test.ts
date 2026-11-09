import { existsSync, readFileSync } from 'node:fs';
import path from 'node:path';
import { crc32, inflateRawSync } from 'node:zlib';
import { chromium, type Browser } from '@playwright/test';
import { afterAll, beforeAll, expect, it } from 'vitest';
import { browserTestBundle } from '../helpers/browser-test-bundle';
import type * as Harness from '../fixtures/long-ride-harness';

interface LongRideFixture {
  write(
    archiveName: string,
    durationUs: number | null,
    done: (rideId: string | null, error: string | null) => void,
  ): void;
}

declare global {
  interface Window {
    longRideFixtures: { LongRideFixture: LongRideFixture | { getInstance(): LongRideFixture } };
    longRide: typeof Harness;
  }
}

const ORIGIN = 'http://127.0.0.1:43141';
const runtimeTests = path.resolve(
  'shared/runtime/build/compileSync/js/test/testDevelopmentExecutable/kotlin/power-log-runtime-test.mjs',
);

const FIT_CRC = [
  0x0000, 0xcc01, 0xd801, 0x1400, 0xf001, 0x3c00, 0x2800, 0xe401, 0xa001, 0x6c00, 0x7800, 0xb401, 0x5000, 0x9c01,
  0x8801, 0x4400,
];

function fitCrc(bytes: Uint8Array): number {
  let crc = 0;
  for (const byte of bytes) {
    for (const nibble of [byte & 0xf, byte >> 4]) {
      const tmp = FIT_CRC[crc & 0xf]!;
      crc = ((crc >> 4) & 0x0fff) ^ tmp ^ FIT_CRC[nibble]!;
    }
  }
  return crc;
}

function expectFit(file: Buffer) {
  expect(file.toString('ascii', 8, 12)).toBe('.FIT');
  const headerSize = file[0]!;
  const dataSize = file.readUInt32LE(4);
  expect(file.length).toBe(headerSize + dataSize + 2);
  expect(fitCrc(file.subarray(0, file.length - 2))).toBe(file.readUInt16LE(file.length - 2));
}

function expectZip(file: Buffer) {
  const end = file.lastIndexOf(Buffer.from([0x50, 0x4b, 0x05, 0x06]));
  expect(end).toBeGreaterThan(0);
  const count = file.readUInt16LE(end + 10);
  let at = file.readUInt32LE(end + 16);
  const names: string[] = [];
  for (let entry = 0; entry < count; entry++) {
    expect(file.readUInt32LE(at)).toBe(0x02014b50);
    const method = file.readUInt16LE(at + 10);
    const crc = file.readUInt32LE(at + 16);
    const compressed = file.readUInt32LE(at + 20);
    const size = file.readUInt32LE(at + 24);
    const nameLength = file.readUInt16LE(at + 28);
    const extraLength = file.readUInt16LE(at + 30);
    const commentLength = file.readUInt16LE(at + 32);
    const local = file.readUInt32LE(at + 42);
    const name = file.toString('utf8', at + 46, at + 46 + nameLength);
    names.push(name);
    expect(file.readUInt32LE(local), name).toBe(0x04034b50);
    const start = local + 30 + file.readUInt16LE(local + 26) + file.readUInt16LE(local + 28);
    const stored = file.subarray(start, start + compressed);
    const data = method === 8 ? inflateRawSync(stored) : stored;
    expect(data.length, name).toBe(size);
    expect(crc32(data) >>> 0, name).toBe(crc);
    at += 46 + nameLength + extraLength + commentLength;
  }
  expect(names).toEqual(expect.arrayContaining(['PowerLog-original/telemetry.csv', 'PowerLog-original/gps.csv']));
}

let browser: Browser;
beforeAll(async () => {
  browser = await chromium.launch({ headless: true });
});
afterAll(async () => {
  await browser?.close();
});

async function exported(
  archive: string,
  durationUs: number | null,
  kinds: readonly ('zip' | 'fit' | 'csv')[],
): Promise<Map<string, Buffer>> {
  expect(existsSync(runtimeTests), 'Build it with :runtime:jsTestTestDevelopmentExecutableCompileSync').toBe(true);
  const context = await browser.newContext({ acceptDownloads: true });
  const files = new Map<string, Buffer>();
  try {
    const page = await context.newPage();
    await context.route(`${ORIGIN}/**`, route =>
      route.fulfill({ contentType: 'text/html', body: '<!doctype html><title>Long ride export</title>' }),
    );
    await page.goto(`${ORIGIN}/`);
    await page.addScriptTag({ content: 'globalThis.describe = () => {};\nglobalThis.it = () => {};' });
    await page.addScriptTag({ content: browserTestBundle(runtimeTests, 'longRideFixtures') });
    const written = await page.evaluate(
      ([name, cut]) =>
        new Promise<{ rideId: string | null; error: string | null }>(resolve => {
          const fixture = window.longRideFixtures.LongRideFixture;
          ('getInstance' in fixture ? fixture.getInstance() : fixture).write(name, cut, (rideId, error) =>
            resolve({ rideId, error }),
          );
        }),
      [archive, durationUs] as const,
    );
    expect(written.error).toBeNull();
    await page.close();
    const exporter = await context.newPage();
    await exporter.goto(`${ORIGIN}/`);
    await exporter.addScriptTag({
      content: browserTestBundle('tests/fixtures/long-ride-harness.ts', 'longRide', {
        [path.resolve('modules/power-log/src/transport.native')]: path.resolve(
          'modules/power-log/src/transport.web.ts',
        ),
      }),
    });
    for (const kind of kinds) {
      const download = exporter.waitForEvent('download', { timeout: 900_000 });
      const result = await exporter.evaluate(([name, id, kind]) => window.longRide.exportRide(name, id, kind), [
        archive,
        written.rideId!,
        kind,
      ] as const);
      expect(result, `${kind}: ${JSON.stringify(result)}`).toMatchObject({ ok: true });
      files.set(kind, readFileSync((await (await download).path())!));
    }
  } finally {
    await context.close();
  }
  return files;
}

it('exports the long ride from IndexedDB as a ZIP whose entries inflate to their sizes and CRCs and a FIT whose CRC matches', async () => {
  const files = await exported('power-log-long-ride', null, ['zip', 'fit']);
  expectZip(files.get('zip')!);
  expectFit(files.get('fit')!);
}, 1_800_000);

it('cuts the long ride at durationUs and exports only the seconds before the cut', async () => {
  const files = await exported('power-log-long-ride-cut', 600_000_000, ['fit', 'csv']);
  expectFit(files.get('fit')!);
  const lines = files.get('csv')!.toString('utf8').trim().split('\n');
  const elapsed = lines[0]!.split(',').indexOf('elapsedSeconds');
  const last = Number(lines.at(-1)!.split(',')[elapsed]);
  expect(last).toBeGreaterThan(590);
  expect(last).toBeLessThanOrEqual(600);
}, 600_000);
