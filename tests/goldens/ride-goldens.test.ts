import { createHash } from 'node:crypto';
import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { homedir } from 'node:os';
import { join } from 'node:path';
import { describe, expect, it, vi } from 'vitest';
import type { ExportKind } from '../../shared/generated/ts/api';
import type { ExportPlatform } from '../../modules/power-log/src/exports/types';
import { entryText, readZip } from '../fixtures/export/zip/harness';
import { csvDifferences, expectRideGoldenArchive } from '../support/golden-rules';
import { fitDifferences, withoutDuplicateFinalStop } from '../support/fit-diff';
import { readPages, replay } from '../support/page-replay';
import { FileSink } from './file-sink';
import { pendingTests } from '../../scripts/pending-tests.mjs';

vi.mock('../../modules/power-log/src/native-module', () => ({ default: null }));

const PAGES = 'shared/build/ride-golden-pages';
const EXPORTS = 'shared/build/ride-golden-exports';
const HOME = process.env.POWER_LOG_GOLDENS_HOME ?? join(homedir(), '.cache/power-log/goldens');
const KINDS: readonly ExportKind[] = ['zip', 'fit', 'csv'];
const RIDE_GOLDEN_PAGES = 'app.powerlog.application.exports.RideGoldenPagesTest';
const pendingKotlin: string[] = pendingTests(process.cwd(), 'kotlin');

const manifest = JSON.parse(readFileSync('tests/fixtures/rides/manifest.json', 'utf8')) as {
  files: Record<string, { bytes: number; sha256: string }>;
};

interface Golden {
  readonly name: string;
  readonly directory: string;
  readonly listed: readonly string[];
}

const directories = (path: string) =>
  existsSync(path)
    ? readdirSync(path, { withFileTypes: true })
        .filter(entry => entry.isDirectory())
        .map(entry => entry.name)
        .sort()
    : [];

const inGit: Golden[] = ['android', 'ios', 'web'].flatMap(platform =>
  directories(`tests/fixtures/rides/${platform}`).map(name => ({
    name: `${platform}/${name}`,
    directory: `tests/fixtures/rides/${platform}/${name}`,
    listed: [],
  })),
);

const outside: Golden[] = existsSync(join(HOME, 'rides'))
  ? Object.keys(manifest.files)
      .filter(path => path.startsWith('rides/'))
      .map(path => path.split('/').slice(1, 3).join('/'))
      .filter((name, index, names) => names.indexOf(name) === index)
      .map(name => ({
        name,
        directory: join(HOME, 'rides', name),
        listed: Object.keys(manifest.files).filter(path => path.startsWith(`rides/${name}/`)),
      }))
  : [];

function expectListed(golden: Golden): void {
  for (const path of golden.listed) {
    const bytes = readFileSync(join(HOME, path));
    expect({ bytes: bytes.length, sha256: createHash('sha256').update(bytes).digest('hex') }, path).toEqual(
      manifest.files[path],
    );
  }
}

function exportOf(golden: Golden) {
  const sidecar = JSON.parse(readFileSync(join(golden.directory, 'sidecar.json'), 'utf8')) as {
    platform: ExportPlatform;
    rideId: string;
  };
  const datapackage = readZip(readFileSync(join(golden.directory, 'ride.zip'))).find(entry =>
    entry.name.endsWith('/datapackage.json'),
  )!;
  const { created } = JSON.parse(entryText(datapackage)) as { created: string };
  const recorded = join(golden.directory, 'ride.json');
  const fitPower = existsSync(recorded)
    ? ((JSON.parse(readFileSync(recorded, 'utf8')) as { fitPower?: 'rider' | 'battery' }).fitPower ?? 'rider')
    : 'rider';
  return { platform: sidecar.platform, rideId: sidecar.rideId, exportedAt: created, fitPower };
}

describe.each([...inGit, ...outside])('the ride golden $name', golden => {
  it.each(KINDS)(
    'reproduces its %s',
    async kind => {
      expectListed(golden);
      const pages = join(PAGES, golden.name, kind);
      if (!existsSync(join(pages, 'open.json')))
        throw new Error(
          pendingKotlin.includes(RIDE_GOLDEN_PAGES)
            ? `An operation is not implemented: ${RIDE_GOLDEN_PAGES} is pending, so goldens:ride-pages wrote no pages`
            : `${pages} is missing: run goldens:ride-pages first`,
        );
      const { platform, rideId, exportedAt, fitPower } = exportOf(golden);
      const sink = new FileSink(join(EXPORTS, golden.name, `ride.${kind}`));
      const outcome = await replay(sink, {
        pages: readPages(pages),
        source: { kind: 'ride', rideId },
        kind,
        fitPower,
        exportedAt,
      });
      if (!('bytes' in outcome)) throw new Error(`The replay failed in the ${outcome.failedIn} with ${outcome.code}`);
      const recorded = readFileSync(join(golden.directory, `ride.${kind}`));
      const expected =
        kind === 'fit' && platform !== 'web' ? Buffer.from(withoutDuplicateFinalStop(recorded)) : recorded;
      if (kind === 'zip') {
        const dropped = JSON.parse(readFileSync(join(PAGES, golden.name, 'dropped-health-lines.json'), 'utf8'));
        expectRideGoldenArchive(outcome.bytes, expected, platform, dropped as number[]);
      } else {
        const differences =
          kind === 'fit'
            ? fitDifferences(outcome.bytes, expected)
            : csvDifferences(Buffer.from(outcome.bytes).toString('utf8'), expected.toString('utf8'));
        expect(differences, `ride.${kind}`).toEqual([]);
        expect(Buffer.from(outcome.bytes).equals(expected), `ride.${kind} differs`).toBe(true);
      }
    },
    600_000,
  );
});
