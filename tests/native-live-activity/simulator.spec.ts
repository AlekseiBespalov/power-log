import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { test, expect } from '@playwright/test';

test('qualifies ActivityKit renders and both deadlines while running and suspended', async () => {
  test.setTimeout(600_000);
  const result = await promisify(execFile)('python3', ['tests/native-live-activity/qualify.py'], {
    timeout: 590_000,
    maxBuffer: 4 * 1024 * 1024,
  });
  expect(JSON.parse(result.stdout).qualification).toBe('passed');
});
