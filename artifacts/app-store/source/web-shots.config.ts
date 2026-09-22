import { defineConfig } from '@playwright/test';

const port = process.env.POWER_LOG_WEB_PORT ?? '8099';
export default defineConfig({
  testDir: '.',
  testMatch: 'web-shots.spec.ts',
  fullyParallel: false, retries: 0, workers: 1, timeout: 1200000,
  use: { baseURL: `http://127.0.0.1:${port}`, colorScheme: 'dark', trace: 'off' },
  webServer: { command: `npm run web -- --port ${port}`, url: `http://127.0.0.1:${port}`, reuseExistingServer: true, timeout: 180000 },
});
