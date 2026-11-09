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
const restricted = (...patterns) => ({
  'no-restricted-imports': ['error', { patterns: patterns.map(([regex, message]) => ({ regex, message })) }],
});
const ban = {
  expoModulesCore: ['^expo-modules-core(?:/|$)', 'Reach native code through modules/power-log.'],
  shared: ['(?:^|/)shared/', 'Reach the shared Kotlin through modules/power-log.'],
  moduleFiles: ['(?:^|/)modules/power-log/.+', 'Import modules/power-log itself, not a file inside it.'],
  ui: [
    '(?:^|/)src/(?:app|features|components)(?:/|$)|^@/(?:app|features|components)(?:/|$)',
    'The module never imports the UI.',
  ],
  generatedJs: ['(?:^|/)shared/generated/js/', 'Only the platform transport and kit files load a Kotlin/JS build.'],
  webBuild: ['(?:^|/)shared/generated/js/web(?:/|$)', 'Native files load kit’s own build, never the web runtime.'],
  kitBuild: ['(?:^|/)shared/generated/js/kit(?:/|$)', 'Web takes kit from the web runtime build.'],
  sharedButTypes: ['(?:^|/)shared/(?!generated/ts(?:/|$))', 'The encoders read only the generated types from shared/.'],
  reactAndExpo: ['^(?:react|react-native|expo-[^/]+)(?:/|$)', 'The encoders are plain TypeScript.'],
};
module.exports = defineConfig([
  expo,
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
      'shared/*/build/**',
      'shared/.gradle/**',
      'shared/.kotlin/**',
      'shared/generated/**',
      '.expo/**',
      'ios/**',
      'android/**',
      'modules/power-log/android/build/**',
      'playwright-report/**',
      'test-results/**',
    ],
  },
  {
    files: ['src/**/*.{ts,tsx}'],
    rules: restricted(ban.expoModulesCore, ban.shared, ban.moduleFiles),
  },
  {
    files: ['modules/power-log/index.ts', 'modules/power-log/src/**/*.{ts,tsx}'],
    ignores: [
      'modules/power-log/src/exports/**',
      'modules/power-log/src/**/*.native.*',
      'modules/power-log/src/**/*.web.*',
    ],
    rules: restricted(ban.ui, ban.generatedJs),
  },
  {
    files: ['modules/power-log/src/**/*.native.{ts,tsx}'],
    ignores: ['modules/power-log/src/exports/**'],
    rules: restricted(ban.ui, ban.webBuild),
  },
  {
    files: ['modules/power-log/src/**/*.web.{ts,tsx}'],
    ignores: ['modules/power-log/src/exports/**'],
    rules: restricted(ban.ui, ban.kitBuild, ban.expoModulesCore),
  },
  {
    files: ['modules/power-log/src/exports/**/*.{ts,tsx}'],
    rules: restricted(ban.ui, ban.sharedButTypes, ban.reactAndExpo),
  },
]);
