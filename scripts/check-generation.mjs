import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import {
  existsSync,
  lstatSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  readdirSync,
  readlinkSync,
  renameSync,
  rmSync,
} from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

export function treeSnapshot(directory) {
  return Object.fromEntries(
    readdirSync(directory, { recursive: true })
      .sort()
      .flatMap(name => {
        const file = resolve(directory, name);
        const stat = lstatSync(file);
        if (stat.isDirectory()) return [];
        const bytes = stat.isSymbolicLink() ? readlinkSync(file) : readFileSync(file);
        return [[name, `${stat.mode & 0o777}:${createHash('sha256').update(bytes).digest('hex')}`]];
      }),
  );
}

export function assertSameGeneration(before, after, label) {
  const differences = [...new Set([...Object.keys(before), ...Object.keys(after)])].filter(
    name => before[name] !== after[name],
  );
  if (differences.length) throw new Error(`${label} generation drift: ${differences.join(', ')}`);
}

export function withNativeBuildCache(root, generate) {
  const cacheRoot = resolve(root, 'test-results');
  mkdirSync(cacheRoot, { recursive: true });
  const backup = mkdtempSync(resolve(cacheRoot, 'generation-cache-'));
  const saved = [];
  try {
    for (const relative of [
      'ios/build',
      'ios/Pods',
      'ios/Podfile.lock',
      'android/.gradle',
      'android/build',
      'android/app/build',
      'android/app/.cxx',
      'android/app/.externalNativeBuild',
    ]) {
      const original = resolve(root, relative);
      if (!existsSync(original)) continue;
      const cached = resolve(backup, String(saved.length));
      renameSync(original, cached);
      saved.push({ original, cached });
    }
    return generate();
  } finally {
    for (const { original, cached } of saved) {
      mkdirSync(dirname(original), { recursive: true });
      rmSync(original, { recursive: true, force: true });
      renameSync(cached, original);
    }
    rmSync(backup, { recursive: true, force: true });
  }
}

function main() {
  const started = Date.now();
  const mode = process.argv[2];
  if (process.argv.length > 3 || (mode && !['--js', '--native'].includes(mode)))
    throw new Error('Usage: node scripts/check-generation.mjs [--js|--native]');
  const run = (command, args) =>
    execFileSync(command, args, { stdio: 'inherit', env: { ...process.env, CI: '1', EXPO_NO_DOTENV: '1' } });
  if (mode !== '--native') {
    run('npm', ['run', 'check:core-js']);
    const javascript = treeSnapshot('shared/js');
    run(process.execPath, ['shared/scripts/sync-js.mjs', '--compare']);
    assertSameGeneration(javascript, treeSnapshot('shared/js'), 'Kotlin/JS');
  }
  if (mode !== '--js') {
    const prebuild = ['node_modules/expo/bin/cli', 'prebuild', '--platform', 'all', '--no-install'];
    const maintainedApple = treeSnapshot('apple');
    withNativeBuildCache('.', () => {
      run(process.execPath, [...prebuild, '--clean']);
      assertSameGeneration(maintainedApple, treeSnapshot('apple'), 'Maintained Apple sources');
      const ios = treeSnapshot('ios');
      const android = treeSnapshot('android');
      run(process.execPath, [...prebuild, '--no-clean']);
      assertSameGeneration(maintainedApple, treeSnapshot('apple'), 'Maintained Apple sources');
      assertSameGeneration(ios, treeSnapshot('ios'), 'iOS prebuild');
      assertSameGeneration(android, treeSnapshot('android'), 'Android prebuild');
    });
  }
  console.log(`Generation checks passed in ${Math.round((Date.now() - started) / 1000)} seconds.`);
}

if (resolve(process.argv[1] ?? '') === fileURLToPath(import.meta.url)) main();
