import { Buffer } from 'node:buffer';
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { existsSync, mkdirSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import { join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { normalizeJsModule, unorderedInterfaces } from './js-output.mjs';

const shared = resolve(import.meta.dirname, '..');
const output = resolve(shared, 'js');
const compiled = resolve(shared, 'build/compileSync/js/main/productionLibrary/kotlin');
const fingerprintFile = resolve(output, 'fingerprint.json');
const generated = name => /\.(mjs|d\.(ts|mts))$/.test(name);
const sha256 = path => createHash('sha256').update(readFileSync(path)).digest('hex');

function filesIn(directory) {
  if (!existsSync(directory)) return [];
  return readdirSync(directory, { recursive: true, withFileTypes: true })
    .filter(entry => entry.isFile() && !entry.name.startsWith('.'))
    .map(entry => join(entry.parentPath, entry.name));
}

function jsInputs(base = shared) {
  return [
    ...filesIn(resolve(base, 'src/commonMain')),
    ...filesIn(resolve(base, 'src/jsMain')),
    ...['build.gradle.kts', 'settings.gradle.kts', 'gradle.properties', 'gradle/wrapper/gradle-wrapper.properties'].map(
      name => resolve(base, name),
    ),
    ...filesIn(resolve(base, 'gradle')).filter(path => path.endsWith('.toml')),
    resolve(base, 'scripts/sync-js.mjs'),
    resolve(base, 'scripts/js-output.mjs'),
  ]
    .map(path => relative(resolve(base, '..'), path))
    .sort();
}

export function jsFingerprint(base = shared) {
  const directory = resolve(base, 'js');
  return {
    inputs: Object.fromEntries(jsInputs(base).map(name => [name, sha256(resolve(base, '..', name))])),
    outputs: Object.fromEntries(
      readdirSync(directory)
        .filter(generated)
        .sort()
        .map(name => [name, sha256(resolve(directory, name))]),
    ),
  };
}

export function fingerprintDifferences(recorded, current) {
  const differences = [];
  for (const [side, label] of [
    ['inputs', 'input'],
    ['outputs', 'generated file'],
  ])
    for (const name of [...new Set([...Object.keys(recorded?.[side] ?? {}), ...Object.keys(current[side])])].sort())
      if (recorded?.[side]?.[name] !== current[side][name]) differences.push(`${label} ${name}`);
  return differences;
}

export function sameGeneratedModule(name, built, checkedIn) {
  if (built === checkedIn) return true;
  return name.endsWith('.mjs') && unorderedInterfaces(built) === unorderedInterfaces(checkedIn);
}

function compile(cold) {
  const javaHome =
    process.env.JAVA_HOME ??
    (process.platform === 'darwin' ? '/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home' : undefined);
  const tasks = cold
    ? ['compileKotlinJs', '--rerun', 'compileProductionLibraryKotlinJs', '--rerun', 'jsProductionLibraryCompileSync']
    : ['jsProductionLibraryCompileSync'];
  const offline = process.env.POWER_LOG_GRADLE_OFFLINE === '1' ? ['--offline'] : [];
  const result = spawnSync(resolve(shared, 'gradlew'), ['-p', shared, ...tasks, '--console=plain', ...offline], {
    stdio: 'inherit',
    env: {
      ...process.env,
      ...(javaHome ? { JAVA_HOME: javaHome } : {}),
      npm_config_cache: resolve(shared, 'build/npm-cache'),
      npm_config_registry: 'https://registry.npmjs.org',
      npm_config_userconfig: '/dev/null',
    },
  });
  if (result.error) throw result.error;
  if (result.status !== 0) process.exit(result.status ?? 1);
  const sourceNames = readdirSync(compiled).filter(generated).sort();
  const modules = sourceNames.map(sourceName => {
    const raw = readFileSync(resolve(compiled, sourceName), 'utf8');
    return [sourceName.replace(/\.d\.mts$/, '.d.ts'), sourceName.endsWith('.mjs') ? normalizeJsModule(raw) : raw];
  });
  if (!modules.some(([name]) => name.endsWith('.d.ts')) || !modules.some(([name]) => name === 'power-log-core.mjs'))
    throw new Error('Missing Kotlin/JS output.');
  return modules;
}

function main() {
  const mode = process.argv[2];
  if (process.argv.length > 3 || (mode && !['--check', '--compare'].includes(mode)))
    throw new Error('Usage: node shared/scripts/sync-js.mjs [--check|--compare]');
  if (mode === '--check') {
    if (!existsSync(fingerprintFile))
      throw new Error('shared/js/fingerprint.json is missing; run npm run build:core-js.');
    const current = jsFingerprint();
    const differences = fingerprintDifferences(JSON.parse(readFileSync(fingerprintFile, 'utf8')), current);
    if (differences.length)
      throw new Error(
        `Checked-in Kotlin/JS output was not generated from the current sources (${differences.join(', ')}); run npm run build:core-js.`,
      );
    console.log(
      `Kotlin/JS fingerprint checked: ${Object.keys(current.inputs).length} inputs, ${Object.keys(current.outputs).length} generated files.`,
    );
    return;
  }
  const modules = compile(mode === '--compare');
  const names = modules.map(([name]) => name);
  if (mode === '--compare') {
    const checkedIn = readdirSync(output).filter(generated).sort();
    if (JSON.stringify(names) !== JSON.stringify(checkedIn))
      throw new Error(`Kotlin/JS output files ${names.join(', ')} differ from checked-in ${checkedIn.join(', ')}.`);
    for (const [name, text] of modules)
      if (!sameGeneratedModule(name, text, readFileSync(resolve(output, name), 'utf8')))
        throw new Error(`Compiled Kotlin/JS differs from checked-in shared/js/${name} beyond interface order.`);
    console.log(`Kotlin/JS compiled from scratch matches the checked-in output: ${names.length} unbundled files.`);
    return;
  }
  mkdirSync(output, { recursive: true });
  for (const [name, text] of modules) {
    const target = resolve(output, name);
    const content = Buffer.from(text);
    if (!existsSync(target) || !readFileSync(target).equals(content)) writeFileSync(target, content);
  }
  for (const name of readdirSync(output).filter(name => generated(name) && !names.includes(name)))
    rmSync(resolve(output, name));
  writeFileSync(fingerprintFile, `${JSON.stringify(jsFingerprint(), null, 2)}\n`);
  console.log(`Kotlin/JS generated: ${names.length} unbundled files.`);
}

if (resolve(process.argv[1] ?? '') === fileURLToPath(import.meta.url)) main();
