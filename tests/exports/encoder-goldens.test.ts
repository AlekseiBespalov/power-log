import { createHash } from 'node:crypto';
import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { homedir } from 'node:os';
import { join } from 'node:path';
import { describe, expect, it, vi } from 'vitest';
import type { ExportKind } from '../../shared/generated/ts/api';
import { MemorySink, expectedEntries } from '../fixtures/export/zip/harness';
import {
  archiveOf,
  expectZipContainer,
  expectZipEntries,
  expectZipLikeArchive,
  type EntryTexts,
} from '../support/golden-rules';
import { FAILURE_CODES, readPages, replay, type ReplayOutcome, type ReplayRequest } from '../support/page-replay';

vi.mock('../../modules/power-log/src/native-module', () => ({ default: null }));

const ROOT = 'tests/fixtures/export';
const OUTSIDE = join(
  process.env.POWER_LOG_GOLDENS_HOME ?? join(homedir(), '.cache/power-log/goldens'),
  'encoder-cases',
);

const base64 = (path: string) =>
  Buffer.from((JSON.parse(readFileSync(path, 'utf8')) as { base64: string }).base64, 'base64');

const ride = (rideId: string) => ({ kind: 'ride', rideId }) as const;

async function replayed(request: ReplayRequest): Promise<ReplayOutcome> {
  return replay(new MemorySink(), request);
}

function bytesOf(outcome: ReplayOutcome): Uint8Array {
  if ('bytes' in outcome) return outcome.bytes;
  throw new Error(`The replay failed in the ${outcome.failedIn} with ${outcome.code}`);
}

const subdirectories = (path: string) =>
  existsSync(path)
    ? readdirSync(path, { withFileTypes: true })
        .filter(entry => entry.isDirectory())
        .map(entry => entry.name)
        .sort()
    : [];

describe('the FIT families', () => {
  it.each(subdirectories(`${ROOT}/fit`))('%s reproduces its FIT file', async name => {
    const outcome = await replayed({
      pages: readPages(`${ROOT}/fit/${name}/pages`),
      kind: 'fit',
      source: ride('ride-1'),
      exportedAt: '2026-01-01T00:00:00.000Z',
    });
    expect(Buffer.from(bytesOf(outcome)).equals(base64(`${ROOT}/fit/${name}.fit.json`))).toBe(true);
  });
});

const ZIP_FAMILIES: Record<string, { rideId: string; exportedAt: string }> = {
  'gps-android': { rideId: 'live-2b7c9d1e-3f4a-4b5c-8d6e-7f8a9b0c1d2e', exportedAt: '2026-07-04T12:00:00+00:00' },
  'gps-iphone': { rideId: '8e4f2a6c-1b3d-4e5f-a6b7-c8d9e0f1a2b3', exportedAt: '2026-06-21T23:59:59.999Z' },
  'health-descriptor': { rideId: 'c3d4e5f6-a7b8-4c9d-8e0f-1a2b3c4d5e6f', exportedAt: '2026-05-01T08:15:42.000Z' },
  'telemetry-lifecycle': { rideId: '5d0c8a4e-2f6b-4c1d-9e3a-7b8f0a1c2d3e', exportedAt: '2026-03-14T09:27:31.250Z' },
};

describe('the ZIP families', () => {
  it.each(Object.keys(ZIP_FAMILIES))('%s reproduces its archive', async name => {
    const family = ZIP_FAMILIES[name]!;
    const outcome = await replayed({
      pages: readPages(`${ROOT}/zip/${name}/pages`),
      kind: 'zip',
      source: ride(family.rideId),
      exportedAt: family.exportedAt,
    });
    const expected = Object.fromEntries(
      Object.entries(expectedEntries(name)).map(([entry, text]) => [`PowerLog-original/${entry}`, text]),
    );
    expectZipEntries(bytesOf(outcome), expected, family.exportedAt);
    expectZipContainer(bytesOf(outcome), family.exportedAt);
  });
});

describe('the Android Health fixture', () => {
  const exportedAt = '2026-09-09T00:00:30Z';
  const request = (kind: ExportKind): ReplayRequest => ({
    pages: readPages(`${ROOT}/android-health/pages`),
    kind,
    source: ride('generated-android-health'),
    exportedAt,
  });

  it('reproduces its FIT file', async () => {
    const bytes = bytesOf(await replayed(request('fit')));
    expect(Buffer.from(bytes).equals(base64(`${ROOT}/android-health.fit.json`))).toBe(true);
  });

  it('reproduces its archive', async () => {
    const bytes = bytesOf(await replayed(request('zip')));
    const expected = JSON.parse(readFileSync(`${ROOT}/android-health.zip.json`, 'utf8')) as EntryTexts;
    expectZipEntries(bytes, expected, exportedAt);
    expectZipContainer(bytes, exportedAt);
  });
});

describe('the storable fixtures', () => {
  const storable = subdirectories(`${ROOT}/storable`);
  const fixture = (name: string) =>
    JSON.parse(readFileSync(`${ROOT}/storable/${name}/fixture.json`, 'utf8')) as {
      rideId: string;
      context: { exportedAt: string; platform: string };
    };
  const request = (name: string, kind: ExportKind): ReplayRequest => {
    const directory = `${ROOT}/storable/${name}/pages`;
    const csv: Record<string, string> =
      kind === 'csv' && existsSync(`${directory}/seconds-csv.json`) ? { seconds: 'seconds-csv' } : {};
    const { rideId, context } = fixture(name);
    return { pages: readPages(directory, csv), kind, source: ride(rideId), exportedAt: context.exportedAt };
  };
  const file = (name: string, extension: string) => readFileSync(`${ROOT}/storable/${name}/ride.${extension}`);

  it.each(storable)('%s reproduces its FIT file', async name => {
    expect(Buffer.from(bytesOf(await replayed(request(name, 'fit')))).equals(file(name, 'fit'))).toBe(true);
  });

  it.each(storable)('%s reproduces its ride CSV', async name => {
    expect(Buffer.from(bytesOf(await replayed(request(name, 'csv')))).toString('utf8')).toBe(
      file(name, 'csv').toString('utf8'),
    );
  });

  it.each(storable)('%s reproduces its archive', async name => {
    const bytes = bytesOf(await replayed(request(name, 'zip')));
    expectZipLikeArchive(bytes, file(name, 'zip'));
    expectZipContainer(bytes, fixture(name).context.exportedAt);
  });
});

interface CaseFile {
  readonly tests: readonly string[];
  readonly failsIn?: 'pages' | 'encoder';
  readonly kind: ExportKind;
  readonly fitPower?: 'rider' | 'battery';
  readonly rideId: string;
  readonly context: { readonly exportedAt: string; readonly platform: string };
  readonly output:
    { readonly base64: string } | { readonly failure: { readonly code: string; readonly message: string } };
}

function cases(root: string): [string, string][] {
  return subdirectories(root).flatMap(group =>
    subdirectories(join(root, group)).map(name => [`${group}/${name}`, join(root, group, name)] as [string, string]),
  );
}

async function expectCase(directory: string): Promise<void> {
  const found = JSON.parse(readFileSync(join(directory, 'case.json'), 'utf8')) as CaseFile;
  const pages = readPages(join(directory, 'pages'));
  const outcome = await replayed({
    pages,
    kind: found.kind,
    source: ride(found.rideId),
    exportedAt: found.context.exportedAt,
    ...(found.fitPower ? { fitPower: found.fitPower } : {}),
  });
  const output = found.output;
  if ('base64' in output) {
    const expected = Buffer.from(output.base64, 'base64');
    if (found.kind === 'zip') {
      // A captured archive is its memory sink's whole buffer, zero-padded after the end of the archive.
      expectZipLikeArchive(bytesOf(outcome), archiveOf(expected));
      expectZipContainer(bytesOf(outcome), found.context.exportedAt);
    } else expect(Buffer.from(bytesOf(outcome)).equals(expected)).toBe(true);
    return;
  }
  const code = FAILURE_CODES[output.failure.code]!;
  if (found.failsIn === 'encoder') expect(outcome).toEqual({ failedIn: 'encoder', code });
  // failsIn pages: the starting build threw where today's export failed, so its pages answer unknown.
  else if (found.failsIn === 'pages') expect(outcome).toEqual({ failedIn: 'pages', code: 'unknown' });
  else expect(outcome).toEqual({ failedIn: 'pages', code });
}

describe('the captured cases', () => {
  it.each(cases(`${ROOT}/cases`))('%s', async (_, directory) => expectCase(directory));
});

const manifest = JSON.parse(readFileSync('tests/fixtures/rides/manifest.json', 'utf8')) as {
  files: Record<string, { bytes: number; sha256: string }>;
};
const outside = Object.keys(manifest.files)
  .filter(path => path.startsWith('encoder-cases/'))
  .map(path => path.split('/').slice(1, 3).join('/'))
  .filter((name, index, names) => names.indexOf(name) === index);

if (existsSync(OUTSIDE))
  describe('the captured cases kept outside Git', () => {
    it.each(outside)(
      '%s',
      async name => {
        for (const [path, listed] of Object.entries(manifest.files))
          if (path.startsWith(`encoder-cases/${name}/`)) {
            const bytes = readFileSync(join(OUTSIDE, path.slice('encoder-cases/'.length)));
            expect({ bytes: bytes.length, sha256: createHash('sha256').update(bytes).digest('hex') }, path).toEqual(
              listed,
            );
          }
        await expectCase(join(OUTSIDE, name));
      },
      120_000,
    );
  });
