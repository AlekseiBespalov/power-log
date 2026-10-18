import { afterAll, beforeAll, expect, it } from 'vitest';
import { chromium, type Browser, type Page } from '@playwright/test';
import { browserTestBundle } from '../helpers/browser-test-bundle';
import type * as Harness from '../fixtures/browser-ride-harness';
import keys from '../fixtures/contract/storage/exact-index-keys.json';
import admission from '../fixtures/contract/storage/store-admission.json';

declare global {
  interface Window {
    storedModel: typeof Harness;
  }
}
const bundle = browserTestBundle('tests/fixtures/browser-ride-harness.ts', 'storedModel');
let browser: Browser;
beforeAll(async () => {
  browser = await chromium.launch({ headless: true });
});
afterAll(async () => {
  await browser?.close();
});
async function run(work: (page: Page) => Promise<void>) {
  const context = await browser.newContext();
  try {
    const page = await context.newPage();
    await page.route('http://127.0.0.1:43129/**', route =>
      route.fulfill({ contentType: 'text/html', body: '<!doctype html><title>Stored model</title>' }),
    );
    await page.goto('http://127.0.0.1:43129/');
    await page.addScriptTag({ content: bundle });
    await work(page);
  } finally {
    await context.close();
  }
}
const marker = admission.platforms.find(row => row.platform === 'browser')!;
it.each(admission.cases.filter(row => row.onlyPlatform !== 'apple'))(
  'loads store admission fixture $id through IndexedDB',
  async fixture =>
    run(async page => {
      const result = await page.evaluate(
        async ({ fixture, marker }) => {
          const version = {
            zero: 0,
            previous: marker.previousMarker,
            required: marker.requiredMarker,
            requiredPlusOne: marker.requiredMarker + 1,
          }[fixture.storedMarker]!;
          const rawOpen = (version?: number) =>
            new Promise<IDBDatabase>((resolve, reject) => {
              const req = version === undefined ? indexedDB.open('power-log') : indexedDB.open('power-log', version);
              req.onupgradeneeded = () => {
                req.result
                  .createObjectStore('recordings', { keyPath: 'id' })
                  .put({ id: 'retained', payload: 'original' });
              };
              req.onsuccess = () => resolve(req.result);
              req.onerror = () => reject(req.error);
            });
          if (version && fixture.expected.outcome === 'reject') (await rawOpen(version)).close();
          const error = await (
            fixture.buildMarker === 'previous' ? rawOpen(marker.previousMarker) : window.storedModel.browserDatabase()
          ).then(
            db => {
              db.close();
              return null;
            },
            (error: Error) => ({ name: error.name, message: error.message }),
          );
          const retained = await rawOpen();
          const record = await window.storedModel.idbRequest(
            retained.transaction('recordings').objectStore('recordings').get('retained'),
          );
          const result = { error, record, version: retained.version, stores: Array.from(retained.objectStoreNames) };
          retained.close();
          return result;
        },
        { fixture, marker },
      );
      if (fixture.expected.outcome === 'reject') {
        expect(result.error).not.toBeNull();
        expect(result.record).toEqual({ id: 'retained', payload: 'original' });
        expect(result.version).toBe(
          fixture.storedMarker === 'previous'
            ? marker.previousMarker
            : fixture.storedMarker === 'required'
              ? marker.requiredMarker
              : marker.requiredMarker + 1,
        );
        expect(result.stores).toEqual(['recordings']);
        if (fixture.buildMarker !== 'previous')
          expect(result.error?.message).toMatch(
            fixture.expected.reason === 'earlierVersion' ? /earlier version/ : /Update Power Log/,
          );
      } else {
        expect(result.error).toBeNull();
        expect(result.version).toBe(marker.requiredMarker);
        expect(result.stores).toContain('ride-locations');
      }
    }),
);
it('uses exact fixture range, adjacent, producer and revision keys', async () =>
  run(async page => {
    const result = await page.evaluate(async fixture => {
      const h = window.storedModel,
        ride = await h.store.begin('owner', { indoor: false, recordGPS: false, sampleHz: 8 }, '2026-09-09T00:00:00Z');
      await h.browserTransaction(['samples'], 'readwrite', async tx => {
        for (const [sequence, us] of fixture.range.insertionOrderUs.entries()) {
          const row = {
            ...h.browserRow(0, sequence),
            recordingId: ride.id,
            elapsedUs: us,
            producerSequence: fixture.producerOrder.insertionOrder[sequence] ?? String(sequence + 200),
            revision: '2',
            elapsedKey: h.browserIndexKey(us),
            sequenceKey: h.browserIndexKey(fixture.producerOrder.insertionOrder[sequence] ?? String(sequence + 200)),
            revisionKey: h.browserIndexKey('2'),
          };
          await h.idbRequest(tx.objectStore('samples').add(row));
        }
      });
      const rows = await h.store.pageExact(ride.id, fixture.range.lowerUsInclusive, fixture.range.upperUsInclusive);
      const before = await h.store.neighborExact(ride.id, '1', 'prev'),
        after = await h.store.neighborExact(ride.id, '11', 'next');
      const producerRows = await h.browserTransaction(['samples'], 'readonly', tx =>
        h.idbRequest(
          tx.objectStore('samples').getAll(IDBKeyRange.bound([ride.id, 'controller'], [ride.id, 'controller', []])),
        ),
      );
      const oldPin = await h.store.pageExact(ride.id, '-9223372036854775808', '9223372036854775807', null, 256, '1');
      const currentPin = await h.store.pageExact(
        ride.id,
        '-9223372036854775808',
        '9223372036854775807',
        null,
        256,
        '2',
      );
      return {
        range: rows.map(row => row.elapsedUs),
        before: before?.elapsedUs,
        after: after?.elapsedUs,
        positions: producerRows.slice(0, 3).map(row => row.producerSequence),
        oldPin,
        currentPin: currentPin.length,
      };
    }, keys);
    expect(result.range).toEqual(keys.range.expectedUs);
    expect(result.before).toBe(keys.range.expectedPredecessorUs);
    expect(result.after).toBe(keys.range.expectedSuccessorUs);
    expect(result.positions).toEqual(keys.producerOrder.expectedOrder);
    expect(result.oldPin).toEqual([]);
    expect(result.currentPin).toBe(5);
  }));

it('counts every original and composes laps from distinct interior boundaries', async () =>
  run(async page => {
    const result = await page.evaluate(async () => {
      const h = window.storedModel,
        ride = await h.store.begin('owner', { indoor: true, recordGPS: false, sampleHz: 4 }, '2026-09-09T00:00:00Z');
      await h.store.transition(ride.id, 'owner', 'lap', 0, 0, ride.startedAt);
      const rows = [0, 2].map((elapsed, sequence) => ({
        ...h.browserRow(elapsed, sequence),
        recordingId: ride.id,
        humanPowerW: -1,
      }));
      const committed = await h.store.append(ride.id, 'owner', rows, 2, 2, '2026-09-09T00:00:02Z');
      const replay = await h.store.append(ride.id, 'owner', rows, 2, 2, '2026-09-09T00:00:02Z');
      await h.store.transition(ride.id, 'owner', 'lap', 3, 3, '2026-09-09T00:00:03Z');
      await h.store.transition(ride.id, 'owner', 'lap', 3, 3, '2026-09-09T00:00:03Z');
      await h.store.transition(ride.id, 'owner', 'lap', 5, 5, '2026-09-09T00:00:05Z');
      const ended = await h.store.transition(ride.id, 'owner', 'stop', 5, 5, '2026-09-09T00:00:05Z');
      return { committed, replay, ended, metadata: h.metadataOf(ended) };
    });
    expect(result.replay).toEqual(result.committed);
    expect(result.ended).toMatchObject({
      telemetryCount: 2,
      locationCount: 0,
      lifecycleCount: 6,
      originalMembershipCount: 8,
      lapCount: 2,
    });
    expect(result.metadata.eventCount).toBe('8');
  }));
