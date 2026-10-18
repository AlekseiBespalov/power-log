import { defineConfig } from '@playwright/test';

export default defineConfig({
  testDir: '.',
  testMatch: '*.spec.ts',
  outputDir: '../../test-results/la/simulator-playwright',
  workers: 1,
  retries: 0,
});
