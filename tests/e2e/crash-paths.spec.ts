import { test } from '@playwright/test';
import { interruptionPoints, webCrashPath } from '../helpers/web-crash-path';
for (const afterCommit of interruptionPoints)
  test(`interrupted 8 Hz ride: ${afterCommit ? 'committed' : 'uncommitted'} batch, Finish, FIT and ZIP`, async ({
    page,
  }) => {
    await webCrashPath(page, afterCommit, 'http://127.0.0.1:43149');
  });
