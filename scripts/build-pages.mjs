import { spawnSync } from 'node:child_process';
import { copyFileSync, mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, relative, resolve } from 'node:path';
import { generateWebNotices, removeSourceMaps } from './web-notices.mjs';

const baseUrl = process.argv[2] ?? '/power-log';
if (process.argv.length > 3 || !/^\/(?:[a-zA-Z0-9_-]+(?:\/[a-zA-Z0-9_-]+)*)?$/.test(baseUrl)) {
  throw new Error('Usage: npm run build:pages -- /project-path (or / for a dedicated hostname)');
}

// Public exports do not inherit signing, OAuth or EXPO_PUBLIC values from a developer's shell.
const env = Object.fromEntries(
  ['PATH', 'HOME', 'TMPDIR', 'TEMP', 'TMP', 'SystemRoot', 'CI', 'TERM', 'FORCE_COLOR']
    .filter(name => process.env[name] !== undefined)
    .map(name => [name, process.env[name]]),
);
Object.assign(env, {
  NODE_ENV: 'production',
  EXPO_NO_DOTENV: '1',
  POWER_LOG_WEB_BASE_URL: baseUrl === '/' ? '' : baseUrl,
});

// Source maps are needed to inventory the bundle and must never reach dist/.
const staging = mkdtempSync(join(tmpdir(), 'power-log-web-'));
try {
  const result = spawnSync(
    process.execPath,
    ['node_modules/expo/bin/cli', 'export', '--platform', 'web', '--output-dir', staging, '--clear', '--source-maps'],
    { env, stdio: 'inherit' },
  );
  if (result.error) throw result.error;
  if (result.status !== 0) throw new Error(`Expo export failed (${result.status}).`);
  const { packageCount, missingLicenseFile, missingCopyright } = generateWebNotices(process.cwd(), staging);
  const files = removeSourceMaps(staging);
  rmSync('dist', { recursive: true, force: true });
  for (const file of files) {
    const destination = resolve('dist', relative(staging, file));
    mkdirSync(dirname(destination), { recursive: true });
    copyFileSync(file, destination);
  }
  writeFileSync('dist/.nojekyll', '');
  console.log(
    `Third-party notices cover ${packageCount} bundled packages; ${missingLicenseFile.length} publish no licence file, ${missingCopyright.length} carry no copyright notice.`,
  );
} finally {
  rmSync(staging, { recursive: true, force: true });
}
