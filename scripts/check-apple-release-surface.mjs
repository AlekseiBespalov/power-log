import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { readFileSync, readdirSync, realpathSync, statSync } from 'node:fs';
import { join, relative, resolve } from 'node:path';

const forbidden =
  /WorkoutLabCrash|labBeginCapture|labReceive|PowerLogWatchLab|PowerLogLab|AppleHealthLab|LabHealthReadback|AppleCrash(?:Lab|Export|Originals|Seconds|Restart|Settle)|PowerLogCrashRide|Power Log Lab|POWER_LOG_LAB|crash-(?:phone|watch|sdk)-|lab-results|lab-state|nativeAppleCrashExport/;
export function releaseText(source) {
  const stack = [];
  const condition = expression => {
    if (/^POWER_LOG_LAB(?:\s*&&[^|]+)?$/.test(expression.trim())) return false;
    if (expression.trim() === '!POWER_LOG_LAB') return true;
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

export function checkSources() {
  const visit = directory => {
    for (const entry of readdirSync(directory, { withFileTypes: true })) {
      const path = join(directory, entry.name);
      if (entry.isDirectory() && entry.name !== 'Tests') visit(path);
      else if (entry.isFile() && path.endsWith('.swift'))
        assert.doesNotMatch(releaseText(readFileSync(path, 'utf8')), forbidden, path);
    }
  };
  for (const directory of ['modules/power-log-bridge/ios', 'apple/WatchApp', 'apple/LiveActivity']) visit(directory);
  for (const file of ['modules/power-log-bridge/ios/PowerLogBridgeModule.swift', 'modules/power-log-bridge/index.ts'])
    assert.doesNotMatch(readFileSync(file, 'utf8'), forbidden, 'The bridge must not expose Lab entry points');
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
  checkInfoPlist(
    JSON.parse(
      execFileSync('/usr/bin/plutil', ['-convert', 'json', '-o', '-', join(root, 'Info.plist')], { encoding: 'utf8' }),
    ),
  );
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
        assert.doesNotMatch(bytes.toString('latin1'), forbidden, path);
        binaries.push(path);
      }
    }
  }
  visit(root);
  return binaries;
}

if (process.argv[1]?.endsWith('check-apple-release-surface.mjs')) {
  assert.equal(process.argv.length, 3, 'Supply the final .app bundle');
  checkSources();
  const binaries = checkBundle(process.argv[2]);
  console.log(
    `Apple release surface: ${binaries.length} Mach-O executables contain no Lab hooks or entry points, and Info.plist has no development client entries`,
  );
}
