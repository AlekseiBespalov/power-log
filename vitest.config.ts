import path from 'node:path';
import { defineConfig } from 'vitest/config';
export default defineConfig({
  resolve: { alias: { 'expo-modules-core': path.resolve('tests/helpers/expo-modules-core.ts') } },
  test: {
    include: [
      'tests/exports/**/*.test.ts',
      'tests/repo/**/*.test.ts',
      'tests/module/**/*.test.ts',
      'tests/features/**/*.test.ts',
      'tests/components/**/*.test.ts',
    ],
  },
});
