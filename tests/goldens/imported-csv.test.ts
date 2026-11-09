import { existsSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it, vi } from 'vitest';
import { MemorySink, expectedEntries } from '../fixtures/export/zip/harness';
import { readPages, replay } from '../support/page-replay';
import { pendingTests } from '../../scripts/pending-tests.mjs';

vi.mock('../../modules/power-log/src/native-module', () => ({ default: null }));

const PAGES = 'shared/build/encoder-import-pages';
const ENCODER_PAGES = 'app.powerlog.application.exports.EncoderPagesTest';
const FAMILIES = ['gps-android', 'gps-iphone', 'health-descriptor', 'telemetry-lifecycle'];
const pendingKotlin: string[] = pendingTests(process.cwd(), 'kotlin');

const producerOf = (family: string) =>
  `${ENCODER_PAGES}.${family.replace(/-(\w)/g, (_, letter: string) => letter.toUpperCase())}ImportedCsv`;

describe('an imported telemetry.csv', () => {
  it.each(FAMILIES)('of %s re-exports byte for byte', async family => {
    const pages = join(PAGES, family);
    const producer = producerOf(family);
    if (!existsSync(join(pages, 'open.json')))
      throw new Error(
        pendingKotlin.includes(ENCODER_PAGES) || pendingKotlin.includes(producer)
          ? `An operation is not implemented: ${producer} is pending, so core:tests wrote no pages`
          : `${pages} is missing: run core:tests first`,
      );
    const outcome = await replay(new MemorySink(), {
      pages: readPages(pages),
      source: { kind: 'imported', importId: 'imported-ride' },
      kind: 'csv',
      exportedAt: '2026-01-01T00:00:00.000Z',
    });
    if (!('bytes' in outcome)) throw new Error(`The replay failed in the ${outcome.failedIn} with ${outcome.code}`);
    expect(Buffer.from(outcome.bytes).toString('utf8')).toBe(expectedEntries(family)['telemetry.csv']);
  });
});
