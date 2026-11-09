import { existsSync } from 'node:fs';
import path from 'node:path';
import type { Page } from '@playwright/test';
import { browserTestBundle } from './browser-test-bundle';

export type SavedRide = {
  startUtcMs: number;
  durationMs: number;
  atMs?: number[];
  powerW?: number[];
  lapsMs?: number[];
  sampleHz?: number;
  indoor?: boolean;
  battery?: 'controller' | 'calculated';
  columns?: Record<string, number[]>;
};

interface SavedRidesFixture {
  write(archiveName: string, rides: string, done: (rideIds: string | null, error: string | null) => void): void;
}

declare global {
  interface Window {
    savedRideFixtures: { SavedRidesFixture: SavedRidesFixture | { getInstance(): SavedRidesFixture } };
  }
}

const runtimeTests = path.resolve(
  'shared/runtime/build/compileSync/js/test/testDevelopmentExecutable/kotlin/power-log-runtime-test.mjs',
);
let bundle: string | undefined;

export async function saveRides(page: Page, rides: SavedRide[]): Promise<string[]> {
  if (!existsSync(runtimeTests)) throw new Error('Build it with :runtime:jsTestTestDevelopmentExecutableCompileSync');
  bundle ??= browserTestBundle(runtimeTests, 'savedRideFixtures');
  const archive = await page.evaluate(async () =>
    (await indexedDB.databases()).map(database => database.name).find(name => name?.startsWith('power-log-archive-')),
  );
  if (!archive) throw new Error('The app has not opened its archive yet');
  const writer = await page.context().newPage();
  try {
    const url = new URL('/__saved-rides', page.url()).href;
    await writer.route(url, route =>
      route.fulfill({ contentType: 'text/html', body: '<!doctype html><title>Saved rides</title>' }),
    );
    await writer.goto(url);
    await writer.addScriptTag({ content: 'globalThis.describe = () => {};\nglobalThis.it = () => {};' });
    await writer.addScriptTag({ content: bundle });
    const written = await writer.evaluate(
      ({ archive, rides }) =>
        new Promise<{ ids: string | null; error: string | null }>(resolve => {
          const exported = window.savedRideFixtures.SavedRidesFixture;
          const fixture = 'getInstance' in exported ? exported.getInstance() : exported;
          fixture.write(archive, rides, (ids, error) => resolve({ ids, error }));
        }),
      { archive, rides: JSON.stringify(rides) },
    );
    if (!written.ids) throw new Error(`The saved rides were not written: ${written.error}`);
    return JSON.parse(written.ids) as string[];
  } finally {
    await writer.close();
  }
}
