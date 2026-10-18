import { createHash } from 'node:crypto';
import { readFileSync, writeFileSync } from 'node:fs';
import { expect, it } from 'vitest';
import { exportZip } from '../../src/core/export/zip-export';
import { ride as android } from '../fixtures/export/zip/gps-android/ride';
import { ride as iphone } from '../fixtures/export/zip/gps-iphone/ride';
import { ride as health } from '../fixtures/export/zip/health-descriptor/ride';
import { ride as telemetry } from '../fixtures/export/zip/telemetry-lifecycle/ride';
import { MemorySink, MemorySource } from '../fixtures/export/zip/harness';

it('matches the ZIP golden archives after the Health provenance column consolidation', async () => {
  const hashes: Record<string, string> = {};
  for (const [name, ride] of Object.entries({ telemetry, iphone, android, health })) {
    const sink = new MemorySink();
    await exportZip(new MemorySource(ride), async () => sink, { rideId: ride.rideId, context: ride.context });
    hashes[name] = createHash('sha256').update(sink.file).digest('hex');
  }
  if (process.env.POWER_LOG_UPDATE_ZIP_FIXTURES === '1')
    writeFileSync('tests/fixtures/contract/export/zip-original-sha256.json', JSON.stringify(hashes, null, 2) + '\n');
  expect(hashes).toEqual(JSON.parse(readFileSync('tests/fixtures/contract/export/zip-original-sha256.json', 'utf8')));
});
