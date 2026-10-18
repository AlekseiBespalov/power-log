import { afterAll, beforeAll, it } from 'vitest';
import { chromium, type Browser } from '@playwright/test';
import { interruptionPoints, webCrashPath } from '../helpers/web-crash-path';
let browser: Browser;
beforeAll(async () => {
  browser = await chromium.launch({ headless: true });
});
afterAll(async () => {
  await browser?.close();
});
for (const afterCommit of interruptionPoints)
  it(`reload with a batch ${afterCommit ? 'committed' : 'uncommitted'} keeps saved 8 Hz data and exports`, async () => {
    const context = await browser.newContext();
    try {
      await webCrashPath(await context.newPage(), afterCommit, 'http://127.0.0.1:43149');
    } finally {
      await context.close();
    }
  }, 60000);
