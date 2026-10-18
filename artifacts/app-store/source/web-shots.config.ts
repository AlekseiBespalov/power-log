import { defineConfig } from '@playwright/test';

const port = process.env.POWER_LOG_WEB_PORT ?? '8099';
export default defineConfig({
  testDir: '.',
  testMatch: 'web-shots.spec.ts',
  outputDir: '../../../test-results/web-shots',
  fullyParallel: false,
  retries: 0,
  workers: 1,
  timeout: 1200000,
  expect: { timeout: 15000 },
  use: {
    baseURL: `http://127.0.0.1:${port}`,
    colorScheme: 'dark',
    trace: 'off',
    actionTimeout: 15000,
    navigationTimeout: 180000,
  },
  webServer: {
    command: `npm run web -- --port ${port} --no-dev --minify`,
    url: `http://127.0.0.1:${port}`,
    reuseExistingServer: false,
    timeout: 180000,
  },
});
