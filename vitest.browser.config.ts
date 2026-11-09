import { configDefaults, defineConfig } from 'vitest/config';
export default defineConfig({
  test: { include: ['tests/browser/**/*.test.ts'], exclude: [...configDefaults.exclude, 'tests/browser/kotlin/**'] },
});
