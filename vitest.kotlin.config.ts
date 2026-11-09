import { defineConfig } from 'vitest/config';
export default defineConfig({ test: { include: ['tests/browser/kotlin/**/*.test.ts'] } });
