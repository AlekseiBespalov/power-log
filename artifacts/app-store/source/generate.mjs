import { spawnSync } from 'node:child_process';
import { existsSync, mkdirSync } from 'node:fs';
import { basename, dirname, isAbsolute, resolve } from 'node:path';
import { SwiftHost } from '../../../scripts/swift-suites.mjs';

const [output, mode, ...extra] = process.argv.slice(2);
if (!output || !isAbsolute(output) || basename(output) !== 'PowerLog' || extra.length || (mode && mode !== '--smoke')) {
  throw new Error('Usage: generate.sh /new/absolute/output/PowerLog [--smoke]');
}
if (existsSync(output)) throw new Error('Output already exists; choose a new directory.');
const root = resolve(import.meta.dirname, '../../..');
process.chdir(root);
const env = {
  ...process.env,
  DEVELOPER_DIR: process.env.DEVELOPER_DIR ?? '/Applications/Xcode.app/Contents/Developer',
};
function exec(program, args) {
  const result = spawnSync(program, args, { cwd: root, env, stdio: 'inherit' });
  if (result.error) throw result.error;
  if (result.status !== 0) throw new Error(`${program} exited with ${result.status ?? result.signal}`);
}
const host = new SwiftHost({ root, env });
mkdirSync(host.cache, { recursive: true });
const binary = await host.binary(
  {
    id: 'screenshot-examples',
    kind: 'suite',
    optimize: true,
    sources: [
      'modules/power-log-bridge/ios/WorkoutExampleRides.swift',
      'modules/power-log-bridge/ios/ExportSource.swift',
    ],
    main: 'artifacts/app-store/source/main.swift',
  },
  { exec, write: console.log },
);
exec(binary, [output, ...(mode ? [mode] : [])]);
exec(process.execPath, [
  'artifacts/app-store/source/check-iphone-examples.mjs',
  resolve(dirname(output), 'example-values.json'),
]);
if (mode === '--smoke') {
  const repeated = spawnSync(binary, [output, mode], { cwd: root, env, encoding: 'utf8' });
  if (repeated.status !== 1 || !repeated.stderr.includes('Output must be new')) {
    throw new Error('The example writer did not refuse an existing output directory.');
  }
  console.log('Screenshot generator smoke check passed, including overwrite refusal.');
}
