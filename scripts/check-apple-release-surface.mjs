import assert from 'node:assert/strict';
import { Buffer } from 'node:buffer';
import { execFileSync } from 'node:child_process';
import { readFileSync, readdirSync, realpathSync, statSync } from 'node:fs';
import { join, relative, resolve } from 'node:path';

const expoImport =
  /^\s*(?:@\w+(?:\([^)]*\))?\s+)*(?:(?:internal|public|private|fileprivate|package)\s+)?import\s+ExpoModulesCore\b/m;
const devTools = /\bDevToolsFactory\b|\bDevSwitches\b|\bPowerLogDev\b|kill\(getpid\(/;
const devToolsMarker = 'power-log-dev-tools';
const devToolsMarkers = [Buffer.from(devToolsMarker, 'utf8'), Buffer.from(devToolsMarker, 'utf16le')];
const runtime = /\b(?:AppleRuntime|HostFacade|BridgeSession|PowerLogHost)\b/;

export function releaseText(source, flag) {
  const stack = [];
  const enabled = new RegExp(`^${flag}(?:\\s*&&[^|]+)?$`);
  const condition = expression => {
    if (enabled.test(expression.trim())) return false;
    if (expression.trim() === `!${flag}`) return true;
    return null;
  };
  return source
    .split('\n')
    .filter(line => {
      const branch = /^\s*#(if|elseif)\s+(.+)$/.exec(line);
      if (branch?.[1] === 'if') {
        const active = condition(branch[2]);
        stack.push({ active, matched: active });
        return false;
      }
      if (branch?.[1] === 'elseif') {
        const frame = stack.at(-1);
        frame.active = frame.matched === true ? false : condition(branch[2]);
        frame.matched = frame.matched === true || frame.active === true ? true : null;
        return false;
      }
      if (/^\s*#endif\b/.test(line)) {
        stack.pop();
        return false;
      }
      if (/^\s*#else\b/.test(line)) {
        const frame = stack.at(-1);
        frame.active = frame.matched === null ? null : !frame.matched;
        return false;
      }
      return !stack.some(frame => frame.active === false);
    })
    .join('\n');
}

function swiftSources(root, directory) {
  const found = [];
  const visit = path => {
    for (const entry of readdirSync(join(root, path), { withFileTypes: true })) {
      const child = `${path}/${entry.name}`;
      if (entry.isDirectory() && !['Tests', 'Frameworks'].includes(entry.name)) visit(child);
      else if (entry.isFile() && child.endsWith('.swift')) found.push(child);
    }
  };
  visit(directory);
  return found.sort();
}

export function checkSwiftBoundaries(root) {
  const module = 'modules/power-log/ios';
  for (const file of swiftSources(root, module)) {
    const source = readFileSync(join(root, file), 'utf8');
    const inside = folder => file.startsWith(`${module}/${folder}/`);
    if (!inside('host') && !inside('charts'))
      assert.doesNotMatch(source, expoImport, `${file} imports ExpoModulesCore outside host/ and charts/`);
    if (!inside('host'))
      assert.doesNotMatch(source, runtime, `${file} reaches the runtime or the bridge outside host/`);
  }
  for (const file of [module, 'targets/watch', 'targets/live-activity'].flatMap(path => swiftSources(root, path)))
    assert.doesNotMatch(
      releaseText(readFileSync(join(root, file), 'utf8'), 'POWER_LOG_DEV_TOOLS'),
      devTools,
      `${file} names the dev tools outside #if POWER_LOG_DEV_TOOLS`,
    );
}

export function checkInfoPlist(info) {
  assert.deepEqual(
    [
      ...['NSLocalNetworkUsageDescription', 'NSBonjourServices'].filter(key => key in info),
      ...(info.NSAppTransportSecurity?.NSAllowsLocalNetworking ? ['NSAllowsLocalNetworking'] : []),
      ...(info.CFBundleURLTypes ?? [])
        .flatMap(type => type.CFBundleURLSchemes ?? [])
        .filter(scheme => scheme.startsWith('exp+')),
    ],
    [],
    'Store builds declare no local network access and no development client URL scheme',
  );
}

export function checkBundle(bundle) {
  assert.ok(bundle?.endsWith('.app'), 'The final .app bundle is required');
  const root = realpathSync(resolve(bundle));
  assert.ok(statSync(root).isDirectory(), 'The final .app bundle must be a directory');
  const executable = execFileSync(
    '/usr/bin/plutil',
    ['-extract', 'CFBundleExecutable', 'raw', '-o', '-', join(root, 'Info.plist')],
    { encoding: 'utf8' },
  ).trim();
  assert.ok(executable && !/[\/\\]/.test(executable), 'The main executable must be inside the final app bundle');
  const main = join(root, executable);
  const isMachO = bytes =>
    bytes.length >= 4 &&
    [0xfeedface, 0xcefaedfe, 0xfeedfacf, 0xcffaedfe, 0xcafebabe, 0xbebafeca, 0xcafebabf, 0xbfbafeca].includes(
      bytes.readUInt32BE(0),
    );
  assert.ok(isMachO(readFileSync(main)), 'The bundle main executable must be Mach-O');
  const seen = new Set();
  const binaries = [];
  function visit(path) {
    const actual = realpathSync(path);
    assert.ok(!relative(root, actual).startsWith('..'), `Bundle link escapes the final bundle: ${path}`);
    if (seen.has(actual)) return;
    seen.add(actual);
    if (statSync(actual).isDirectory()) {
      for (const name of readdirSync(actual)) visit(join(actual, name));
    } else {
      const bytes = readFileSync(actual);
      if (isMachO(bytes)) {
        assert.ok(!devToolsMarkers.some(marker => bytes.includes(marker)), `${path} carries the dev tools`);
        binaries.push(path);
      }
    }
  }
  visit(root);
  checkInfoPlist(
    JSON.parse(
      execFileSync('/usr/bin/plutil', ['-convert', 'json', '-o', '-', join(root, 'Info.plist')], { encoding: 'utf8' }),
    ),
  );
  return binaries;
}

if (process.argv[1]?.endsWith('check-apple-release-surface.mjs')) {
  assert.equal(process.argv.length, 3, 'Supply the final .app bundle');
  checkSwiftBoundaries('.');
  const binaries = checkBundle(process.argv[2]);
  console.log(
    `Apple release surface: ${binaries.length} Mach-O executables carry no dev tools, and Info.plist has no development client entries`,
  );
}
