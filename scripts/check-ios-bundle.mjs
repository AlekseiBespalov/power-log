import { execFileSync } from 'node:child_process';
import { existsSync, readdirSync } from 'node:fs';
import path from 'node:path';

// Code signing does not prove that dyld can resolve a framework at launch.
const root = process.argv[2] && path.resolve(process.argv[2]);
if (!root || !root.endsWith('.app') || !existsSync(root)) {
  console.error('Usage: node scripts/check-ios-bundle.mjs /path/to/PowerLog.app');
  process.exit(1);
}

const bundles = [];
function collect(directory) {
  if (/\.(app|appex|framework)$/.test(directory)) bundles.push(directory);
  for (const item of readdirSync(directory, { withFileTypes: true })) {
    if (item.isDirectory()) collect(path.join(directory, item.name));
  }
}
collect(root);

function executable(bundle) {
  const name = execFileSync('/usr/bin/plutil', ['-extract', 'CFBundleExecutable', 'raw', '-o', '-', path.join(bundle, 'Info.plist')], { encoding: 'utf8' }).trim();
  return path.join(bundle, name);
}
function loadCommands(binary) {
  return execFileSync('/usr/bin/xcrun', ['otool', '-l', binary], { encoding: 'utf8' }).split(/Load command \d+\n/);
}
function runPaths(commands) {
  return commands.filter(command => /cmd LC_RPATH\b/.test(command)).map(command => command.match(/\n\s+path (.+) \(offset/)[1]);
}
const failures = [];
let checked = 0;
for (const bundle of bundles) {
  const binary = executable(bundle);
  const commands = loadCommands(binary);
  // A Watch companion/extension has its own executable and framework search root.
  let owner = bundle;
  while (!/\.(app|appex)$/.test(owner)) owner = path.dirname(owner);
  const ownerBinary = executable(owner);
  const expand = value => value.replace('@executable_path', path.dirname(ownerBinary)).replace('@loader_path', path.dirname(binary));
  const searchPaths = [...runPaths(commands).map(expand), ...runPaths(loadCommands(ownerBinary)).map(value => value.replace('@executable_path', path.dirname(ownerBinary)).replace('@loader_path', path.dirname(ownerBinary)))];
  for (const command of commands) {
    if (!/cmd LC_(LOAD_DYLIB|REEXPORT_DYLIB|LOAD_UPWARD_DYLIB)\b/.test(command)) continue;
    const dependency = command.match(/\n\s+name (.+) \(offset/)?.[1];
    if (!dependency || !dependency.startsWith('@') || !dependency.includes('.framework/')) continue;
    checked++;
    const candidates = dependency.startsWith('@rpath/')
      ? searchPaths.map(directory => path.join(directory, dependency.slice('@rpath/'.length)))
      : [expand(dependency)];
    if (!candidates.some(candidate => existsSync(candidate))) {
      failures.push(`${path.relative(root, binary)} requires missing ${dependency}`);
    }
  }
}
if (failures.length) {
  console.error(failures.join('\n'));
  process.exit(1);
}
console.log(`Bundle framework dependencies passed: ${bundles.length} executables, ${checked} required framework references. Device launch remains a separate check.`);
