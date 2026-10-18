const { defineConfig } = require('eslint/config');
const expo = require('eslint-config-expo/flat');
const noNumericFontSize = {
  meta: {
    type: 'problem',
    schema: [],
    messages: { token: 'Use a type token from src/components/ui.tsx instead of a numeric fontSize.' },
  },
  create(context) {
    return {
      Literal(node) {
        if (typeof node.value !== 'number') return;
        const ancestors = context.sourceCode.getAncestors(node);
        if (
          ancestors.some(
            parent =>
              (parent.type === 'Property' && (parent.key.name ?? parent.key.value) === 'fontSize') ||
              (parent.type === 'JSXAttribute' && parent.name.name === 'fontSize'),
          )
        )
          context.report({ node, messageId: 'token' });
      },
    };
  },
};
module.exports = defineConfig([
  expo,
  {
    files: ['scripts/*.cjs'],
    languageOptions: { sourceType: 'commonjs', globals: { __dirname: 'readonly' } },
  },
  {
    files: ['src/**/*.{ts,tsx}'],
    ignores: ['src/components/ui.tsx'],
    plugins: { ui: { rules: { 'no-numeric-font-size': noNumericFontSize } } },
    rules: { 'ui/no-numeric-font-size': 'error' },
  },
  {
    ignores: [
      'dist/**',
      'shared/build/**',
      'shared/.gradle/**',
      'shared/.kotlin/**',
      'shared/js/**',
      '.expo/**',
      'ios/**',
      'android/**',
      'modules/power-log-bridge/android/build/**',
      'playwright-report/**',
      'test-results/**',
    ],
  },
]);
