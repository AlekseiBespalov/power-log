import { Buffer } from 'node:buffer';
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { existsSync, mkdirSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import ts from 'typescript';
import { normalizeJsModule, unorderedInterfaces } from './js-output.mjs';

const shared = resolve(import.meta.dirname, '..');
const fingerprintFile = resolve(shared, 'generated/fingerprint.json');
const generator = 'application/src/jvmTest/kotlin/app/powerlog/application/api/generator';
const outputs = [
  {
    directory: 'ts',
    build: 'application/build/generated/api/ts',
    modules: ['domain', 'application'],
    task: ':application:generateApiTypes',
    rerun: [':application:generateApiTypes'],
    required: ['api.ts', 'export-tables.ts'],
  },
  {
    directory: 'kotlin',
    build: 'application/build/generated/api/kotlin',
    modules: ['domain', 'application'],
    task: ':application:generateApiTypes',
    rerun: [':application:generateApiTypes'],
    required: ['app/powerlog/runtime/ApiHash.kt', 'app/powerlog/runtime/bridge/ApiDispatch.kt'],
  },
  {
    directory: 'js/kit',
    build: 'kit/build/compileSync/js/main/productionLibrary/kotlin',
    modules: ['domain', 'kit'],
    task: ':kit:jsProductionLibraryCompileSync',
    rerun: [':domain:compileKotlinJs', ':kit:compileKotlinJs', ':kit:compileProductionLibraryKotlinJs'],
    required: ['power-log-kit.mjs', 'power-log-kit.d.ts'],
    javascript: true,
  },
  {
    directory: 'js/web',
    build: 'runtime/build/compileSync/js/main/productionLibrary/kotlin',
    modules: ['domain', 'application', 'data', 'kit', 'runtime'],
    task: ':runtime:jsProductionLibraryCompileSync',
    rerun: [
      ':application:compileKotlinJs',
      ':data:compileKotlinJs',
      ':runtime:compileKotlinJs',
      ':runtime:compileProductionLibraryKotlinJs',
    ],
    entry: {
      declarations: 'power-log-runtime.d.mts',
      exports: ["export * from './power-log-runtime.mjs';", "export * from './power-log-kit.mjs';"],
    },
    required: ['index.mjs', 'index.d.ts', 'power-log-runtime.mjs', 'power-log-kit.mjs'],
    javascript: true,
  },
];
const javascriptFile = name => /\.(mjs|d\.(ts|mts))$/.test(name);
const sha256 = path => createHash('sha256').update(readFileSync(path)).digest('hex');

function filesIn(directory) {
  if (!existsSync(directory)) return [];
  return readdirSync(directory, { recursive: true, withFileTypes: true })
    .filter(entry => entry.isFile() && !entry.name.startsWith('.'))
    .map(entry => join(entry.parentPath, entry.name));
}

function generatedNames(directory, output) {
  return filesIn(directory)
    .filter(path => !output.javascript || javascriptFile(path))
    .map(path => relative(directory, path))
    .sort();
}

function inputs(base = shared) {
  const modules = [...new Set(outputs.flatMap(output => output.modules))];
  return [
    ...modules.flatMap(module => ['commonMain', 'jsMain'].flatMap(set => filesIn(resolve(base, module, 'src', set)))),
    ...filesIn(resolve(base, generator)).filter(path => !path.endsWith('Test.kt')),
    ...filesIn(resolve(base, 'build-logic/src')),
    ...filesIn(resolve(base, 'gradle')),
    ...[
      'settings.gradle.kts',
      'gradle.properties',
      'build-logic/settings.gradle.kts',
      'build-logic/build.gradle.kts',
      ...modules.map(module => `${module}/build.gradle.kts`),
    ].map(name => resolve(base, name)),
    resolve(base, 'scripts/sync-generated.mjs'),
    resolve(base, 'scripts/js-output.mjs'),
  ]
    .map(path => relative(resolve(base, '..'), path))
    .sort();
}

export function fingerprint(base = shared) {
  const generated = resolve(base, 'generated');
  return {
    inputs: Object.fromEntries(inputs(base).map(name => [name, sha256(resolve(base, '..', name))])),
    outputs: Object.fromEntries(
      outputs
        .flatMap(output =>
          generatedNames(resolve(generated, output.directory), output).map(name => join(output.directory, name)),
        )
        .sort()
        .map(name => [name, sha256(resolve(generated, name))]),
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

function gradleArguments(cold) {
  const tasks = [...new Set(outputs.map(output => output.task))];
  if (!cold) return tasks;
  const rerun = [...new Set(outputs.flatMap(output => output.rerun))];
  return [...rerun.flatMap(task => [task, '--rerun']), ...tasks.filter(task => !rerun.includes(task))];
}

function built(output) {
  const directory = resolve(shared, output.build);
  const files = generatedNames(directory, output).map(name => {
    const text = readFileSync(resolve(directory, name), 'utf8');
    if (!output.javascript) return [name, text];
    if (name === output.entry?.declarations) return ['index.d.ts', `${text.trimEnd()}\nexport {};\n`];
    return [name.replace(/\.d\.mts$/, '.d.ts'), name.endsWith('.mjs') ? normalizeJsModule(text) : text];
  });
  if (output.entry) files.push(['index.mjs', `${output.entry.exports.join('\n')}\n`]);
  const missing = output.required.filter(name => !files.some(([file]) => file === name));
  if (missing.length) throw new Error(`Missing generated ${missing.join(', ')} in ${output.build}.`);
  return files.sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0));
}

function compile(cold) {
  const javaHome =
    process.env.JAVA_HOME ??
    (process.platform === 'darwin' ? '/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home' : undefined);
  const androidHome =
    process.env.ANDROID_HOME ?? (process.platform === 'darwin' ? resolve(homedir(), 'Library/Android/sdk') : undefined);
  const offline = process.env.POWER_LOG_GRADLE_OFFLINE === '1' ? ['--offline'] : [];
  const result = spawnSync(
    resolve(shared, 'gradlew'),
    ['-p', shared, ...gradleArguments(cold), '--console=plain', ...offline],
    {
      stdio: 'inherit',
      env: {
        ...process.env,
        ...(javaHome ? { JAVA_HOME: javaHome } : {}),
        ...(androidHome ? { ANDROID_HOME: androidHome } : {}),
        npm_config_cache: resolve(shared, 'build/npm-cache'),
        npm_config_registry: 'https://registry.npmjs.org',
        npm_config_userconfig: '/dev/null',
      },
    },
  );
  if (result.error) throw result.error;
  if (result.status !== 0) process.exit(result.status ?? 1);
  return outputs.map(output => [output, built(output)]);
}

async function checkEntry(output) {
  const directory = resolve(shared, 'generated', output.directory);
  const declarations = resolve(directory, 'index.d.ts');
  const program = ts.createProgram([declarations], { noEmit: true, skipLibCheck: true });
  const checker = program.getTypeChecker();
  const declared = checker
    .getExportsOfModule(checker.getSymbolAtLocation(program.getSourceFile(declarations)))
    .filter(symbol => symbol.flags & ts.SymbolFlags.Value)
    .map(symbol => symbol.name)
    .sort();
  const exported = Object.keys(await import(pathToFileURL(resolve(directory, 'index.mjs')).href)).sort();
  const missing = declared.filter(name => !exported.includes(name));
  const undeclared = exported.filter(name => !declared.includes(name));
  if (missing.length || undeclared.length)
    throw new Error(
      `shared/generated/${output.directory}/index.mjs differs from index.d.ts: declared but not exported [${missing.join(', ')}], exported but not declared [${undeclared.join(', ')}].`,
    );
  console.log(
    `shared/generated/${output.directory}/index.mjs exports the ${exported.length} values index.d.ts declares.`,
  );
}

function check() {
  if (!existsSync(fingerprintFile))
    throw new Error('shared/generated/fingerprint.json is missing; run npm run build:generated.');
  const current = fingerprint();
  const differences = fingerprintDifferences(JSON.parse(readFileSync(fingerprintFile, 'utf8')), current);
  if (differences.length)
    throw new Error(
      `Checked-in shared/generated was not generated from the current sources (${differences.join(', ')}); run npm run build:generated.`,
    );
  console.log(
    `Generated outputs fingerprint checked: ${Object.keys(current.inputs).length} inputs, ${Object.keys(current.outputs).length} generated files.`,
  );
}

async function compare() {
  for (const [output, files] of compile(true)) {
    const directory = resolve(shared, 'generated', output.directory);
    const names = files.map(([name]) => name);
    const checkedIn = generatedNames(directory, output);
    if (JSON.stringify(names) !== JSON.stringify(checkedIn))
      throw new Error(
        `Generated shared/generated/${output.directory} files ${names.join(', ')} differ from checked-in ${checkedIn.join(', ')}.`,
      );
    for (const [name, text] of files) {
      const same = output.javascript
        ? sameGeneratedModule(name, text, readFileSync(resolve(directory, name), 'utf8'))
        : text === readFileSync(resolve(directory, name), 'utf8');
      if (!same) throw new Error(`A cold build differs from checked-in shared/generated/${output.directory}/${name}.`);
    }
    if (output.entry) await checkEntry(output);
  }
  console.log('A cold build matches every checked-in file under shared/generated.');
}

async function generate() {
  for (const [output, files] of compile(false)) {
    const directory = resolve(shared, 'generated', output.directory);
    for (const [name, text] of files) {
      const target = resolve(directory, name);
      mkdirSync(dirname(target), { recursive: true });
      if (!existsSync(target) || !sameGeneratedModule(name, text, readFileSync(target, 'utf8')))
        writeFileSync(target, Buffer.from(text));
    }
    const names = new Set(files.map(([name]) => name));
    for (const name of generatedNames(directory, output)) if (!names.has(name)) rmSync(resolve(directory, name));
    const bytes = files.reduce((sum, [, text]) => sum + Buffer.byteLength(text), 0);
    console.log(`shared/generated/${output.directory}: ${files.length} files, ${bytes} bytes.`);
    if (output.entry) await checkEntry(output);
  }
  writeFileSync(fingerprintFile, `${JSON.stringify(fingerprint(), null, 2)}\n`);
}

async function main() {
  const mode = process.argv[2];
  if (process.argv.length > 3 || (mode && !['--check', '--compare'].includes(mode)))
    throw new Error('Usage: node shared/scripts/sync-generated.mjs [--check|--compare]');
  if (mode === '--check') check();
  else if (mode === '--compare') await compare();
  else await generate();
}

if (resolve(process.argv[1] ?? '') === fileURLToPath(import.meta.url)) await main();
