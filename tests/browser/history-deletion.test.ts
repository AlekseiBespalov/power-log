import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import path from 'node:path';
import { chromium, expect as ui, type Browser, type Page } from '@playwright/test';
import { browserTestBundle } from '../helpers/browser-test-bundle';
import { syntheticSample } from '../fixtures/synthetic-sample';
import { parseCsv } from '../support/csv';
import type * as Harness from './history-actions-harness';

declare global {
  interface Window {
    historyTests: typeof Harness;
  }
}
const platform = path.resolve('tests/fixtures/history-deletion-platform.tsx');
const backend = path.resolve('tests/fixtures/history-deletion-backend.ts');
const aliases: Record<string, string> = {
  react: path.resolve('node_modules/react/cjs/react.production.js'),
  'react/jsx-runtime': path.resolve('node_modules/react/cjs/react-jsx-runtime.production.js'),
  'react-dom': path.resolve('node_modules/react-dom/cjs/react-dom.production.js'),
  'react-dom/client': path.resolve('node_modules/react-dom/cjs/react-dom-client.production.js'),
  scheduler: path.resolve('node_modules/scheduler/cjs/scheduler.production.js'),
  'react-native': platform,
  'react-native-safe-area-context': platform,
  'expo-router': platform,
};
for (const name of ['src/services/workouts']) aliases[path.resolve(name)] = backend;
for (const name of [
  'src/services/session-context',
  'src/services/device',
  'src/services/monitor-preferences',
  'src/services/workout-monitor-source',
  'src/services/files',
  'src/components/route-preview',
  'src/features/monitor/monitor-panel',
])
  aliases[path.resolve(name)] = platform;
const bundle = browserTestBundle('tests/browser/history-actions-harness.ts', 'historyTests', aliases);
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
    page.setDefaultTimeout(2000);
    await page.route('http://127.0.0.1:43120/**', route =>
      route.fulfill({ contentType: 'text/html', body: '<!doctype html><title>History lifecycle integration</title>' }),
    );
    await page.goto('http://127.0.0.1:43120/');
    await page.addScriptTag({ content: bundle });
    await work(page);
  } finally {
    await context.close();
  }
}
const rows = (page: Page) => page.getByRole('button', { name: /^Open ride,/ });
const chart = (page: Page) => page.getByTestId('selected-ride-chart');
async function selectFirst(page: Page) {
  await rows(page).first().click();
  await ui(chart(page)).toBeVisible();
  await ui(page.getByText('Ride time', { exact: true })).toBeVisible();
}
const csvFile = {
  name: 'synthetic-import.csv',
  recording: {
    samples: [syntheticSample(0, 0, '2026-01-01T00:00:00.000Z'), syntheticSample(1, 1, '2026-01-01T00:00:01.000Z')],
  },
};
async function openCsv(page: Page) {
  await page.evaluate(file => window.historyTests.setCsvImport(file), csvFile);
  await page.getByRole('button', { name: 'Open CSV', exact: true }).click();
}
async function expectCsv(page: Page) {
  await ui(page.getByText(csvFile.name, { exact: true })).toBeVisible();
  await ui(page.getByText('Imported · source unverified', { exact: true })).toBeVisible();
  await ui(chart(page)).toHaveAttribute('data-source', /^csv-source:/);
  await ui(page.getByTestId('ride-summary')).toHaveCount(0);
  await ui(rows(page)).toHaveCount(0);
}

describe('History saved and CSV selection', () => {
  it('switches saved → CSV → list → saved and exports only the imported samples', async () =>
    run(async page => {
      await page.evaluate(() => {
        const h = window.historyTests;
        h.seed([h.metadata('first', 2), h.metadata('second', 1)]);
        h.mount();
      });
      await selectFirst(page);
      await openCsv(page);
      await expectCsv(page);
      await ui(page.getByText('2 original samples', { exact: true })).toBeVisible();
      await ui(page.getByRole('button', { name: 'Delete ride', exact: true })).toHaveCount(0);
      await page.getByRole('button', { name: 'Export CSV', exact: true }).click();
      const exported = await page.evaluate(() => window.historyTests.exportedTexts);
      expect(exported).toHaveLength(1);
      expect(exported[0]!.name).toBe(csvFile.name);
      expect(parseCsv(exported[0]!.contents)).toEqual(csvFile.recording);
      await page.getByRole('button', { name: '‹ Rides', exact: true }).click();
      await ui(rows(page)).toHaveCount(2);
      await ui(chart(page)).toHaveCount(0);
      await ui(page.getByText(csvFile.name, { exact: true })).toHaveCount(0);
      await rows(page).last().click();
      await ui(chart(page)).toHaveAttribute('data-source', 'workout:second');
      await ui(page.getByTestId('ride-summary')).toBeVisible();
      await ui(page.getByRole('button', { name: 'Export CSV', exact: true })).toHaveCount(0);
      expect(await page.evaluate(() => window.historyTests.snapshot().counts.read)).toEqual(['first', 'second']);
    }));

  for (const destination of ['csv', 'saved'] as const)
    it(`keeps the newer ${destination} selection when a held deletion completes`, async () =>
      run(async page => {
        await page.evaluate(() => {
          const h = window.historyTests;
          h.seed([h.metadata('delete-target', 2), h.metadata('keep', 1)]);
          h.holdArchive('remove', 'delete-target');
          h.mount();
        });
        await selectFirst(page);
        await page.getByRole('button', { name: 'Delete ride', exact: true }).click();
        const modal = page.getByTestId('delete-ride-sheet');
        await modal.getByRole('button', { name: 'Delete ride', exact: true }).click();
        await ui(modal.getByRole('button', { name: 'Deleting…', exact: true })).toBeDisabled();
        if (destination === 'csv') {
          await openCsv(page);
          await expectCsv(page);
        } else {
          await page.getByRole('button', { name: '‹ Rides', exact: true }).click();
          await rows(page).last().click();
          await ui(chart(page)).toHaveAttribute('data-source', 'workout:keep');
        }
        await page.evaluate(() => window.historyTests.releaseArchive('remove', 'delete-target'));
        await ui(modal).toHaveCount(0);
        if (destination === 'csv') await expectCsv(page);
        else {
          await ui(chart(page)).toHaveAttribute('data-source', 'workout:keep');
          await ui(page.getByTestId('ride-summary')).toBeVisible();
        }
        await page.getByRole('button', { name: '‹ Rides', exact: true }).click();
        await ui(rows(page)).toHaveCount(1);
        expect(await page.evaluate(() => window.historyTests.snapshot().ids)).toEqual(['keep']);
      }));

  for (const failure of [undefined, 'Obsolete saved read failure'])
    it(`keeps CSV selected when a delayed saved read ${failure ? 'fails' : 'succeeds'}`, async () =>
      run(async page => {
        await page.evaluate(() => {
          const h = window.historyTests;
          h.seed([h.metadata('old', 2), h.metadata('new', 1)]);
          h.holdRead('old');
          h.holdArchive('import', 'csv');
          h.mount();
        });
        await rows(page).first().click();
        await page.waitForFunction(() => window.historyTests.snapshot().counts.read.includes('old'));
        await openCsv(page);
        await ui(page.getByRole('button', { name: 'Open CSV', exact: true })).toBeDisabled();
        await ui(chart(page)).toHaveAttribute('data-source', 'workout:old');
        await page.evaluate(() => window.historyTests.releaseArchive('import', 'csv'));
        await expectCsv(page);
        await page.evaluate(failure => window.historyTests.releaseRead('old', failure), failure);
        await page.evaluate(async () => {
          for (let i = 0; i < 4; i++) await new Promise(requestAnimationFrame);
        });
        await expectCsv(page);
        await ui(page.getByRole('alert')).toHaveCount(0);
        await page.getByRole('button', { name: '‹ Rides', exact: true }).click();
        await rows(page).last().click();
        await ui(chart(page)).toHaveAttribute('data-source', 'workout:new');
        await ui(page.getByTestId('ride-summary')).toBeVisible();
        expect(await page.evaluate(() => window.historyTests.snapshot().counts.read)).toEqual(['old', 'new']);
      }));

  for (const destination of ['list', 'other ride', 'same ride'] as const)
    it(`ignores a delayed import after navigating to the ${destination}`, async () =>
      run(async page => {
        await page.evaluate(() => {
          const h = window.historyTests;
          h.seed([h.metadata('first', 2), h.metadata('second', 1)]);
          h.holdArchive('import', 'csv');
          h.mount();
        });
        await selectFirst(page);
        await openCsv(page);
        await ui(page.getByRole('button', { name: 'Open CSV', exact: true })).toBeDisabled();
        await page.getByRole('button', { name: '‹ Rides', exact: true }).click();
        if (destination !== 'list') {
          await rows(page)
            .nth(destination === 'same ride' ? 0 : 1)
            .click();
          await ui(page.getByTestId('ride-summary')).toBeVisible();
        }
        await page.evaluate(() => window.historyTests.releaseArchive('import', 'csv'));
        await ui(page.getByRole('button', { name: 'Open CSV', exact: true })).toBeEnabled();
        await ui(page.getByText(csvFile.name, { exact: true })).toHaveCount(0);
        if (destination === 'list') {
          await ui(rows(page)).toHaveCount(2);
          await ui(chart(page)).toHaveCount(0);
        } else {
          await ui(chart(page)).toHaveAttribute(
            'data-source',
            destination === 'same ride' ? 'workout:first' : 'workout:second',
          );
          await ui(page.getByTestId('ride-summary')).toBeVisible();
        }
      }));

  it('ignores a delayed import started from the list after visiting a ride and returning', async () =>
    run(async page => {
      await page.evaluate(() => {
        const h = window.historyTests;
        h.seed([h.metadata('first', 2), h.metadata('second', 1)]);
        h.holdArchive('import', 'csv');
        h.mount();
      });
      await openCsv(page);
      await rows(page).nth(0).click();
      await ui(page.getByTestId('ride-summary')).toBeVisible();
      await page.getByRole('button', { name: '‹ Rides', exact: true }).click();
      await page.evaluate(() => window.historyTests.releaseArchive('import', 'csv'));
      await ui(page.getByRole('button', { name: 'Open CSV', exact: true })).toBeEnabled();
      await ui(page.getByText(csvFile.name, { exact: true })).toHaveCount(0);
      await ui(rows(page)).toHaveCount(2);
    }));

  it('ignores a delayed import after the ride it started from is deleted', async () =>
    run(async page => {
      await page.evaluate(() => {
        const h = window.historyTests;
        h.seed([h.metadata('delete-target', 2), h.metadata('keep', 1)]);
        h.holdArchive('import', 'csv');
        h.mount();
      });
      await selectFirst(page);
      await openCsv(page);
      await page.getByRole('button', { name: 'Delete ride', exact: true }).click();
      await page.getByTestId('delete-ride-sheet').getByRole('button', { name: 'Delete ride', exact: true }).click();
      await ui(rows(page)).toHaveCount(1);
      await page.evaluate(() => window.historyTests.releaseArchive('import', 'csv'));
      await ui(page.getByRole('button', { name: 'Open CSV', exact: true })).toBeEnabled();
      await ui(page.getByText(csvFile.name, { exact: true })).toHaveCount(0);
      await ui(rows(page)).toHaveCount(1);
    }));

  it('retains a selected CSV when a later import is cancelled', async () =>
    run(async page => {
      await page.evaluate(() => {
        const h = window.historyTests;
        h.seed([]);
        h.mount();
      });
      await openCsv(page);
      await expectCsv(page);
      await page.evaluate(() => {
        window.historyTests.setCsvImport(null);
        window.historyTests.holdArchive('import', 'csv');
      });
      await page.getByRole('button', { name: 'Open CSV', exact: true }).click();
      await ui(page.getByRole('button', { name: 'Open CSV', exact: true })).toBeDisabled();
      await page.evaluate(() => window.historyTests.releaseArchive('import', 'csv'));
      await ui(page.getByRole('button', { name: 'Open CSV', exact: true })).toBeEnabled();
      await expectCsv(page);
    }));
});

describe('production History and resource lifecycle in React', () => {
  it('shows retained catalog elapsed after forward and backward UTC jumps', async () =>
    run(async page => {
      await page.evaluate(() => {
        const h = window.historyTests;
        h.seed([
          { ...h.metadata('backward-clock', 1), elapsedSeconds: 75, endedAt: '2025-12-31T23:00:00Z' },
          { ...h.metadata('forward-clock', 2), elapsedSeconds: 125, endedAt: '2026-01-01T01:00:00Z' },
        ]);
        h.mount();
      });
      await ui(page.getByText(/· 01:15 elapsed/)).toBeVisible();
      await ui(page.getByText(/· 02:05 elapsed/)).toBeVisible();
    }));
  it('shows partial catalog warnings alongside healthy rides', async () =>
    run(async page => {
      await page.evaluate(() => {
        const h = window.historyTests;
        h.seed([h.metadata('healthy', 1)]);
        h.setCatalogWarnings(3, 2);
        h.mount();
      });
      await ui(rows(page)).toHaveCount(1);
      await ui(page.getByRole('alert').filter({ hasText: '3 unreadable rides' })).toBeVisible();
      await ui(page.getByRole('alert').filter({ hasText: '2 rides are missing' })).toBeVisible();
      await page.evaluate(async () => {
        window.historyTests.setCatalogWarnings(0, 0);
        await window.historyTests.refreshCatalog();
      });
      await ui(page.getByRole('alert')).toHaveCount(0);
    }));
  it('ignores event-count-only changes, but refreshes detail explicitly and after Android Health completion', async () =>
    run(async page => {
      await page.evaluate(() => {
        const h = window.historyTests,
          record = h.metadata('health-save', 1);
        h.seed([
          {
            ...record,
            healthProvider: 'healthConnect',
            watchEnabled: false,
            saveToHealth: true,
            healthKitState: 'notSaved',
          },
        ]);
        h.mount();
      });
      await selectFirst(page);
      expect(await page.evaluate(() => window.historyTests.snapshot().counts.read)).toEqual(['health-save']);
      await page.evaluate(async () => {
        const h = window.historyTests;
        h.updateRecord('health-save', { eventCount: 4 });
        await h.refreshCatalog();
      });
      await page.evaluate(async () => {
        for (let i = 0; i < 4; i++) await new Promise(requestAnimationFrame);
      });
      expect(await page.evaluate(() => window.historyTests.snapshot().counts.read)).toEqual(['health-save']);
      await page.getByRole('button', { name: 'Refresh', exact: true }).click();
      await page.waitForFunction(() => window.historyTests.snapshot().counts.read.length === 2);
      await page.evaluate(() => {
        const h = window.historyTests;
        h.updateRecord('health-save', { healthKitState: 'saved', healthKitUUID: 'synthetic-health-id' });
        h.emit({ historyRevision: 'health-completed' });
      });
      await page.waitForFunction(() => window.historyTests.snapshot().counts.read.length === 3);
      await ui(page.getByRole('button', { name: 'Retry Health Connect', exact: true })).toHaveCount(0);
    }));
  it('exports FIT with the chosen distance source and opens manual Strava upload only on request', async () =>
    run(async page => {
      await page.evaluate(() => {
        const h = window.historyTests;
        h.seed([h.metadata('manual-export', 1)]);
        h.setGlobalDistanceSource('controller');
        h.mount();
      });
      await selectFirst(page);
      expect(await page.evaluate(() => window.historyTests.snapshot().counts.exports)).toEqual([]);
      expect(await page.evaluate(() => window.historyTests.openedURLs)).toEqual([]);
      await page.getByRole('button', { name: 'Export FIT', exact: true }).click();
      await page.waitForFunction(() => window.historyTests.sharedFiles.length === 1);
      expect(await page.evaluate(() => window.historyTests.snapshot().counts.exports)).toEqual([
        { id: 'manual-export', selection: 'controller' },
      ]);
      expect(await page.evaluate(() => window.historyTests.sharedFiles)).toEqual([
        { uri: 'file:///fixture.fit', name: 'power-log-manual-export.fit' },
      ]);
      expect(await page.evaluate(() => window.historyTests.openedURLs)).toEqual([]);
      await page.getByRole('button', { name: 'Open Strava upload', exact: true }).click();
      expect(await page.evaluate(() => window.historyTests.openedURLs)).toEqual([
        'https://www.strava.com/upload/select',
      ]);
      expect(await page.evaluate(() => window.historyTests.snapshot().counts.exports.length)).toBe(1);
    }));
  it('keeps FIT sharing unavailable until ride verification finishes', async () =>
    run(async page => {
      await page.evaluate(() => {
        const h = window.historyTests;
        h.seed([h.metadata('pending-export', 1, 'finishing')]);
        h.mount();
      });
      await selectFirst(page);
      await ui(page.getByRole('button', { name: 'Export FIT', exact: true })).toBeDisabled();
      await ui(page.getByRole('button', { name: 'Open Strava upload', exact: true })).toBeDisabled();
      expect(await page.evaluate(() => window.historyTests.snapshot().counts.exports)).toEqual([]);
      expect(await page.evaluate(() => window.historyTests.openedURLs)).toEqual([]);
    }));
  for (const kind of ['example', 'browser'] as const)
    it(`omits the Strava shortcut for ${kind} rides and retains their export`, async () =>
      run(async page => {
        await page.evaluate(kind => {
          const h = window.historyTests,
            record = h.metadata('export-kind', 1);
          if (kind === 'example') record.example = true;
          else record.storage = 'browser';
          h.seed([record]);
          h.mount();
        }, kind);
        await selectFirst(page);
        await ui(page.getByRole('button', { name: 'Open Strava upload', exact: true })).toHaveCount(0);
        await ui(
          page.getByRole('button', { name: kind === 'example' ? 'Export FIT' : 'Export CSV', exact: true }),
        ).toBeEnabled();
      }));
  it('identifies a builder Health snapshot as provisional and refreshes a separately named final report', async () =>
    run(async page => {
      await page.evaluate(() => {
        const h = window.historyTests;
        h.seed([h.metadata('health-report', 1)]);
        h.setDistanceFixture('health-report', { selection: 'auto', selected: null, available: [] });
        h.setHealthReportFixture('health-report', true);
        h.mount();
      });
      await selectFirst(page);
      await ui(page.getByText('Health distance · Provisional', { exact: true })).toBeVisible();
      await ui(page.getByTestId('distance-source-caption')).toHaveText('Distance unavailable');
      await page.evaluate(() => {
        window.historyTests.setHealthReportFixture('health-report', false);
        window.historyTests.emit({ historyRevision: '1' });
      });
      await ui(page.getByText('Health reported distance', { exact: true })).toBeVisible();
      await ui(page.getByText('Health distance · Provisional', { exact: true })).toHaveCount(0);
      await ui(page.getByTestId('distance-source-caption')).toHaveText('Distance unavailable');
    }));
  it('shows source coverage, applies global interpretation without metadata writes and keeps live history actions disabled', async () =>
    run(async page => {
      await page.evaluate(() => {
        const h = window.historyTests;
        h.seed([h.metadata('saved-source', 2), h.metadata('live-source', 1, 'running')]);
        const gps = {
          source: 'gps:watch' as const,
          label: 'GPS · Watch',
          distanceMeters: 800,
          coveredSeconds: 40,
          uncoveredSeconds: 8,
          partial: true,
        };
        const controller = {
          source: 'controller' as const,
          label: 'Controller estimate',
          distanceMeters: 900,
          coveredSeconds: 48,
          uncoveredSeconds: 0,
          estimated: true,
        };
        for (const id of ['saved-source', 'live-source'])
          h.setDistanceFixture(id, { selection: 'auto', selected: gps, available: [gps, controller] });
        h.mount();
      });
      await selectFirst(page);
      await ui(page.getByTestId('distance-source-caption')).toHaveText('GPS · Watch · Partial');
      await ui(page.getByText('Ride distance', { exact: true })).toBeVisible();
      await ui(page.getByRole('button', { name: 'Distance source', exact: true })).toHaveCount(0);
      await page.evaluate(() => window.historyTests.setGlobalDistanceSource('controller'));
      await ui(page.getByTestId('distance-source-caption')).toHaveText('Controller estimate');
      await ui(page.getByText('Ride distance', { exact: true })).toBeVisible();
      const saved = await page.evaluate(() => window.historyTests.workouts.read('saved-source', 'controller'));
      expect(saved.metadata).toMatchObject({ collectionRevision: 1, eventCount: 3 });
      expect(saved.summary.distanceMeters).toBe(900);
      await page.getByRole('button', { name: '‹ Rides', exact: true }).click();
      await ui(rows(page).last()).toBeDisabled();
      await ui(page.getByRole('button', { name: 'Distance source', exact: true })).toHaveCount(0);
    }));
  it('removes the cached finishing row and selected chart after remote discard becomes idle, ignoring a late old summary', async () =>
    run(async page => {
      await page.evaluate(() => {
        const h = window.historyTests;
        h.seed([h.metadata('discard-me', 1, 'finishing')], { id: 'discard-me', phase: 'finishing' });
        h.mount();
      });
      await selectFirst(page);
      await page.evaluate(() => {
        window.historyTests.holdRead('discard-me');
        window.historyTests.emit({ historyRevision: '1' });
      });
      await page.waitForFunction(
        () => window.historyTests.snapshot().counts.read.filter(id => id === 'discard-me').length === 2,
      );
      await page.evaluate(() => window.historyTests.discard(['discard-me'], { id: null, phase: 'idle' }));
      await ui(chart(page)).toHaveCount(0);
      await ui(page.getByText('No saved rides yet.', { exact: true })).toBeVisible();
      await page.evaluate(() => window.historyTests.releaseRead('discard-me'));
      await ui(chart(page)).toHaveCount(0);
      await ui(rows(page)).toHaveCount(0);
      const evidence = await page.evaluate(() => ({
        ...window.historyTests.snapshot(),
        unmounted: window.historyTests.monitorUnmounts,
      }));
      expect(evidence.state.phase).toBe('idle');
      expect(evidence.unmounted).toContain('workout:discard-me');
      await ui(page.getByText('This ride was deleted from Power Log.', { exact: true })).toHaveCount(0);
    }));

  it('refreshes historical deletion while another owner remains running and detaches the selected chart', async () =>
    run(async page => {
      await page.evaluate(() => {
        const h = window.historyTests;
        h.seed([h.metadata('historical', 2), h.metadata('other-owner', 1, 'running')], {
          id: 'other-owner',
          phase: 'running',
        });
        h.mount();
      });
      await selectFirst(page);
      const before = await page.evaluate(() => window.historyTests.snapshot().counts.list);
      await page.evaluate(() => window.historyTests.discard(['historical']));
      await ui(chart(page)).toHaveCount(0);
      await ui(rows(page)).toHaveCount(1);
      await page.waitForFunction(
        () =>
          JSON.parse(document.querySelector('[data-testid="catalog-state"]')!.textContent!).ids.join() ===
          'other-owner',
      );
      const evidence = await page.evaluate(() => window.historyTests.snapshot());
      expect(evidence.counts.list).toBeGreaterThan(before);
      expect(evidence.state).toMatchObject({ id: 'other-owner', phase: 'running' });
    }));

  it('rechecks a selected earlier deletion when only a later coalesced deleted ID reaches React', async () =>
    run(async page => {
      await page.evaluate(() => {
        const h = window.historyTests;
        h.seed([h.metadata('selected-first', 3), h.metadata('last-deleted', 2), h.metadata('keep', 1)], {
          id: 'another-owner',
          phase: 'running',
        });
        h.mount();
      });
      await selectFirst(page);
      await page.evaluate(() => window.historyTests.discard(['selected-first', 'last-deleted']));
      await ui(chart(page)).toHaveCount(0);
      await ui(rows(page)).toHaveCount(1);
      await page.waitForFunction(
        () => window.historyTests.snapshot().counts.read.filter(id => id === 'selected-first').length >= 2,
      );
      expect(await page.evaluate(() => window.historyTests.snapshot().state.lastDeletedWorkoutId)).toBe('last-deleted');
      await ui(page.getByText('This ride was deleted from Power Log.', { exact: true })).toHaveCount(0);
    }));

  it('keeps a valid older selection outside the refreshed first fifty and still allows its confirmed deletion', async () =>
    run(async page => {
      await page.evaluate(() => {
        const h = window.historyTests;
        h.seed(
          Array.from({ length: 80 }, (_, i) => h.metadata(`ride-${i}`, i)),
          { id: 'another-owner', phase: 'running' },
        );
        h.mount();
      });
      await ui(rows(page)).toHaveCount(50);
      await page.getByRole('button', { name: 'Load more rides', exact: true }).click();
      await ui(rows(page)).toHaveCount(80);
      await rows(page).last().click();
      await ui(chart(page)).toHaveAttribute('data-source', 'workout:ride-0');
      await ui(page.getByText('Ride time', { exact: true })).toBeVisible();
      await page.evaluate(() => window.historyTests.discard(['ride-79']));
      await page.waitForFunction(() => {
        const value = JSON.parse(document.querySelector('[data-testid="catalog-state"]')!.textContent!);
        return !value.loading && value.ids.length === 50 && !value.ids.includes('ride-0');
      });
      await page.waitForFunction(
        () => window.historyTests.snapshot().counts.read.filter(id => id === 'ride-0').length >= 2,
      );
      await ui(chart(page)).toHaveAttribute('data-source', 'workout:ride-0');
      await ui(page.getByText('Ride time', { exact: true })).toBeVisible();
      await page.getByRole('button', { name: 'Delete ride', exact: true }).click();
      await page.getByTestId('delete-ride-sheet').getByRole('button', { name: 'Delete ride', exact: true }).click();
      await ui(page.getByTestId('delete-ride-sheet')).toHaveCount(0);
      await ui(chart(page)).toHaveCount(0);
      expect(await page.evaluate(() => window.historyTests.snapshot().ids.includes('ride-0'))).toBe(false);
    }));

  it('closes a list deletion confirmation when the Watch independently deletes its target', async () =>
    run(async page => {
      await page.evaluate(() => {
        const h = window.historyTests;
        h.seed([h.metadata('remote-target', 2), h.metadata('keep', 1)], { id: 'another-owner', phase: 'running' });
        h.mount();
      });
      await page.getByRole('button', { name: 'Edit', exact: true }).click();
      await page
        .getByRole('button', { name: /^Delete ride,/ })
        .first()
        .click();
      await ui(page.getByTestId('delete-ride-sheet')).toBeVisible();
      await page.evaluate(() => window.historyTests.discard(['remote-target']));
      await ui(page.getByTestId('delete-ride-sheet')).toHaveCount(0);
      await ui(rows(page)).toHaveCount(1);
      expect(await page.evaluate(() => window.historyTests.snapshot().counts.read)).toEqual([]);
    }));

  it('keeps arbitrary storage errors visible and retains the valid selected summary', async () =>
    run(async page => {
      await page.evaluate(() => {
        const h = window.historyTests;
        h.seed([h.metadata('keep', 1)]);
        h.mount();
      });
      await selectFirst(page);
      await page.evaluate(() => {
        window.historyTests.failRead('keep', 'SQLite disk I/O failure');
        window.historyTests.emit({ historyRevision: '1' });
      });
      await ui(page.getByText('SQLite disk I/O failure', { exact: true })).toBeVisible();
      await ui(chart(page)).toHaveAttribute('data-source', 'workout:keep');
      await ui(page.getByText('Ride time', { exact: true })).toBeVisible();
    }));

  it('does not leak a completed old identity error when returning to the list or selecting another ride', async () =>
    run(async page => {
      await page.evaluate(() => {
        const h = window.historyTests;
        h.seed([h.metadata('bad', 2), h.metadata('good', 1)]);
        h.failRead('bad', 'Bad ride read failure');
        h.mount();
      });
      await rows(page).first().click();
      await ui(page.getByText('Bad ride read failure', { exact: true })).toBeVisible();
      await page.getByRole('button', { name: '‹ Rides', exact: true }).click();
      await ui(page.getByText('Bad ride read failure', { exact: true })).toHaveCount(0);
      await rows(page).last().click();
      await ui(chart(page)).toHaveAttribute('data-source', 'workout:good');
      await ui(page.getByText('Ride time', { exact: true })).toBeVisible();
      await ui(page.getByText('Bad ride read failure', { exact: true })).toHaveCount(0);
    }));

  it('cancels a held old read without releasing admission until it settles, then shows only the new identity', async () =>
    run(async page => {
      await page.evaluate(() => {
        const h = window.historyTests;
        h.seed([h.metadata('old', 2), h.metadata('new', 1)]);
        h.holdRead('old');
        h.mount();
      });
      await rows(page).first().click();
      await ui(chart(page)).toHaveAttribute('data-source', 'workout:old');
      await page.waitForFunction(() => window.historyTests.snapshot().counts.read.includes('old'));
      await page.getByRole('button', { name: '‹ Rides', exact: true }).click();
      await rows(page).last().click();
      await ui(chart(page)).toHaveAttribute('data-source', 'workout:new');
      expect(await page.evaluate(() => window.historyTests.snapshot().counts.read)).toEqual(['old']);
      await page.evaluate(() => window.historyTests.releaseRead('old', 'Obsolete old read rejection'));
      await ui(page.getByText('Ride time', { exact: true })).toBeVisible();
      await ui(chart(page)).toHaveAttribute('data-source', 'workout:new');
      await ui(page.getByText('Obsolete old read rejection', { exact: true })).toHaveCount(0);
      expect(await page.evaluate(() => window.historyTests.snapshot().counts.read)).toEqual(['old', 'new']);
    }));
});

describe('History archive controls alongside an active ride', () => {
  it('keeps pause available during a held export and allows a different record to export', async () =>
    run(async page => {
      await page.evaluate(() => {
        const h = window.historyTests;
        h.seed([h.metadata('first', 2), h.metadata('second', 1)], { id: 'active', phase: 'running' });
        h.holdArchive('export', 'first');
        h.mountWithControls();
      });
      await selectFirst(page);
      await page.getByRole('button', { name: 'Export FIT', exact: true }).click();
      await ui(page.getByRole('button', { name: 'Export FIT', exact: true })).toBeDisabled();
      await ui(page.getByRole('button', { name: 'Pause active ride' })).toBeEnabled();
      expect(JSON.parse(await page.getByTestId('ride-controls-state').innerText()).busy).toBe(false);
      await page.getByRole('button', { name: 'Pause active ride' }).click();
      await page.waitForFunction(
        () =>
          JSON.parse(document.querySelector('[data-testid="ride-controls-state"]')!.textContent!).phase === 'paused',
      );
      expect(await page.evaluate(() => window.historyTests.pauseCalls)).toBe(1);
      await page.getByRole('button', { name: '‹ Rides', exact: true }).click();
      await rows(page).last().click();
      await ui(page.getByRole('button', { name: 'Export FIT', exact: true })).toBeEnabled();
      await page.getByRole('button', { name: 'Export FIT', exact: true }).click();
      await page.waitForFunction(() => window.historyTests.sharedFiles.length === 1);
      await page.evaluate(() => window.historyTests.releaseArchive('export', 'first'));
      await page.waitForFunction(() => window.historyTests.sharedFiles.length === 2);
      expect(JSON.parse(await page.getByTestId('ride-controls-state').innerText()).busy).toBe(false);
    }));

  it('announces archive failures locally and retains the error only on its record', async () =>
    run(async page => {
      await page.evaluate(() => {
        const h = window.historyTests;
        h.seed([h.metadata('failed-export', 2), h.metadata('other', 1)], { id: 'active', phase: 'running' });
        h.holdArchive('export', 'failed-export');
        h.mountWithControls();
      });
      await selectFirst(page);
      await page.getByRole('button', { name: 'Export FIT', exact: true }).click();
      await page.evaluate(() =>
        window.historyTests.releaseArchive('export', 'failed-export', 'Share destination unavailable'),
      );
      await ui(page.getByRole('alert')).toHaveText('Share destination unavailable');
      expect(JSON.parse(await page.getByTestId('ride-controls-state').innerText()).error).toBeNull();
      await page.getByRole('button', { name: '‹ Rides', exact: true }).click();
      await rows(page).last().click();
      await ui(page.getByRole('alert')).toHaveCount(0);
      await page.getByRole('button', { name: '‹ Rides', exact: true }).click();
      await rows(page).first().click();
      await ui(page.getByRole('alert')).toHaveText('Share destination unavailable');
      await page.getByRole('button', { name: 'Dismiss', exact: true }).click();
      await ui(page.getByRole('alert')).toHaveCount(0);
    }));

  it('keeps deletion pending and failure out of the ride gate and announces the modal error', async () =>
    run(async page => {
      await page.evaluate(() => {
        const h = window.historyTests;
        h.seed([h.metadata('delete-target', 1)], { id: 'active', phase: 'running' });
        h.holdArchive('remove', 'delete-target');
        h.mountWithControls();
      });
      await selectFirst(page);
      await page.getByRole('button', { name: 'Delete ride', exact: true }).click();
      const modal = page.getByTestId('delete-ride-sheet');
      await modal.getByRole('button', { name: 'Delete ride', exact: true }).click();
      await ui(modal.getByRole('button', { name: 'Deleting…', exact: true })).toBeDisabled();
      expect(JSON.parse(await page.getByTestId('ride-controls-state').innerText()).busy).toBe(false);
      await page.evaluate(() => window.historyTests.releaseArchive('remove', 'delete-target', 'Recording is in use'));
      await ui(modal.getByRole('alert')).toHaveText('Recording is in use');
      expect(JSON.parse(await page.getByTestId('ride-controls-state').innerText()).error).toBeNull();
    }));
});

it('keeps ride commands available while the CSV chooser is open', async () =>
  run(async page => {
    await page.evaluate(() => {
      const h = window.historyTests;
      h.seed([h.metadata('saved', 1)], { id: 'active', phase: 'running' });
      h.holdArchive('import', 'csv');
      h.mountWithControls();
    });
    await page.getByRole('button', { name: 'Open CSV', exact: true }).click();
    await ui(page.getByRole('button', { name: 'Open CSV', exact: true })).toBeDisabled();
    expect(JSON.parse(await page.getByTestId('ride-controls-state').innerText()).busy).toBe(false);
    await page.getByRole('button', { name: 'Pause active ride' }).click();
    await page.waitForFunction(() => window.historyTests.pauseCalls === 1);
    await page.evaluate(() => window.historyTests.releaseArchive('import', 'csv'));
    await ui(page.getByRole('button', { name: 'Open CSV', exact: true })).toBeEnabled();
  }));

it('keeps catalog refresh failures in History without reporting a ride-command error', async () =>
  run(async page => {
    await page.evaluate(() => {
      const h = window.historyTests;
      h.seed([h.metadata('saved', 1)], { id: 'active', phase: 'running' });
      h.mountWithControls();
    });
    await ui(rows(page)).toHaveCount(1);
    await page.evaluate(() => {
      window.historyTests.failCatalog('History storage unavailable');
      window.historyTests.emit({ historyRevision: 'changed' });
    });
    await ui(page.getByRole('alert')).toHaveText('History storage unavailable');
    expect(JSON.parse(await page.getByTestId('ride-controls-state').innerText()).error).toBeNull();
    await ui(page.getByRole('button', { name: 'Pause active ride' })).toBeEnabled();
  }));

it.each(['browser', 'native'] as const)(
  'describes only applicable storage when deleting a %s ride with Health off',
  async storage =>
    run(async page => {
      await page.evaluate(storage => {
        const h = window.historyTests;
        h.seed([{ ...h.metadata('local-only', 1), storage, watchEnabled: false, saveToHealth: false }]);
        h.mount();
      }, storage);
      await selectFirst(page);
      await page.getByRole('button', { name: 'Delete ride', exact: true }).click();
      const modal = page.getByTestId('delete-ride-sheet');
      await ui(modal).toContainText('This permanently removes the recording and charts from Power Log.');
      await ui(modal).not.toContainText('The Watch copy');
      if (storage === 'browser') await ui(modal).not.toContainText('Health');
      else await ui(modal).toContainText('Workouts already saved in Apple Health stay there.');
    }),
);
