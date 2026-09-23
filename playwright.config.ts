import { defineConfig, devices } from '@playwright/test';
const port = process.env.POWER_LOG_WEB_PORT ?? '8081';
export default defineConfig({
  testDir: './tests/e2e', fullyParallel: false, retries: 0,
  use: { baseURL: `http://127.0.0.1:${port}`, trace: 'retain-on-failure', screenshot: 'only-on-failure', ...devices['Desktop Chrome'] },
  webServer: { command: `npm run web -- --port ${port}`, url: `http://127.0.0.1:${port}`, reuseExistingServer: !process.env.CI, timeout: 120000 },
});
