import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';

export function checkApk(apk) {
  const names = execFileSync('unzip', ['-Z1', apk], { encoding: 'utf8' })
    .split('\n')
    .filter(name => /^classes\d*\.dex$/.test(name));
  assert.ok(names.length, `${apk} holds no dex files`);
  const dexes = names.map(name => execFileSync('unzip', ['-p', apk, name], { maxBuffer: 1 << 30 }));
  for (const [index, dex] of dexes.entries())
    assert.ok(
      !dex.includes('~~R8{'),
      `${names[index]} of ${apk} was minified by R8, which renames Lapp/powerlog/runtime/dev/ past this scan`,
    );
  assert.ok(
    dexes.some(dex => dex.includes('Lapp/powerlog/runtime/host/HostFacade;')),
    `${apk} carries no runtime host`,
  );
  for (const [index, dex] of dexes.entries())
    for (const marker of ['Lapp/powerlog/runtime/dev/', 'power-log-dev-tools'])
      assert.ok(!dex.includes(marker), `${names[index]} of ${apk} carries the dev tools (${marker})`);
  return names.length;
}

if (process.argv[1]?.endsWith('check-android-release-surface.mjs')) {
  const apk = process.argv.indexOf('--apk');
  if (apk < 0) throw new Error('Usage: node scripts/check-android-release-surface.mjs --apk <release apk>');
  const dexes = checkApk(process.argv[apk + 1]);
  console.log(`Release APK surface: ${dexes} dex files carry the runtime host and no dev tools.`);
}
