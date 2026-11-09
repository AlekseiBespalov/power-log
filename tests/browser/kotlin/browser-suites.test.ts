import fs from 'node:fs';
import path from 'node:path';
import { chromium, type Browser } from '@playwright/test';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { browserTestBundle } from '../../helpers/browser-test-bundle';
import { browserPendingProblems, pendingTests } from '../../../scripts/pending-tests.mjs';
import { browserCases } from '../../../shared/scripts/discover-common-tests.mjs';

const pending: string[] = pendingTests(process.cwd(), 'kotlin');
const registered: { module: string; name: string }[] = browserCases(path.resolve('shared'));

declare global {
  interface Window {
    kotlinSuite: { runBrowserSuite(fixtures: string, done: (report: string) => void): void };
  }
}

const storable = 'tests/fixtures/export/storable';
const encoderFixtures = fs
  .readdirSync(storable)
  .flatMap(name => [
    ...['fixture.json', 'ride.json'].map(file => `${storable}/${name}/${file}`),
    ...fs.readdirSync(`${storable}/${name}/pages`).map(file => `${storable}/${name}/pages/${file}`),
  ]);

const fixturesOf: Record<string, string[]> = {
  runtime: ['tests/fixtures/contract/ports/ble-central.json', 'tests/fixtures/protocol.json', ...encoderFixtures],
};
const modules = [...new Set(registered.map(row => row.module))];

let browser: Browser;
beforeAll(async () => {
  browser = await chromium.launch({ headless: true });
});
afterAll(async () => {
  await browser?.close();
});

describe.each(modules)('the %s module in Chromium', module => {
  it('runs the browser cases its sources register, failing only where pending', async () => {
    const fixtures = fixturesOf[module] ?? [];
    const entry = path.resolve(
      `shared/${module}/build/compileSync/js/test/testDevelopmentExecutable/kotlin/power-log-${module}-test.mjs`,
    );
    expect(fs.existsSync(entry), `Build it with :${module}:jsTestTestDevelopmentExecutableCompileSync`).toBe(true);
    const contents = Object.fromEntries(fixtures.map(name => [name, fs.readFileSync(path.resolve(name), 'utf8')]));
    const context = await browser.newContext({
      geolocation: { latitude: 52.52, longitude: 13.405, accuracy: 12 },
      permissions: ['geolocation'],
    });
    try {
      const page = await context.newPage();
      await page.route('http://127.0.0.1:43121/**', route =>
        route.fulfill({ contentType: 'text/html', body: '<!doctype html><title>Kotlin/JS browser suite</title>' }),
      );
      await page.goto('http://127.0.0.1:43121/');
      await page.addScriptTag({ content: 'globalThis.describe = () => {};\nglobalThis.it = () => {};' });
      await page.addScriptTag({ content: browserTestBundle(entry, 'kotlinSuite') });
      const report = await page.evaluate(
        fixtures => new Promise<string>(resolve => window.kotlinSuite.runBrowserSuite(fixtures, resolve)),
        JSON.stringify(contents),
      );
      const cases = JSON.parse(report) as { name: string; failure: string | null }[];
      const reports = process.env.POWER_LOG_TEST_REPORTS;
      if (reports) fs.writeFileSync(path.join(reports, `kotlin-browser-${module}.json`), report);
      expect(cases.map(row => row.name).sort(), 'the cases the bundle runs').toEqual(
        registered
          .filter(row => row.module === module)
          .map(row => row.name)
          .sort(),
      );
      expect(browserPendingProblems(cases, pending).problems).toEqual([]);
    } finally {
      await context.close();
    }
  }, 120_000);
});
