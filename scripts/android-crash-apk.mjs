import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { basename } from 'node:path';

export function validateCrashApk(apk, expectedDigest) {
  const read = spawnSync(
    'python3',
    ['-c', "import sys,zipfile;print(zipfile.ZipFile(sys.argv[1]).read('assets/crash-build.json').decode())", apk],
    { encoding: 'utf8' },
  );
  if (read.error) throw read.error;
  assert.equal(read.status, 0, `Android crash APK has no build provenance: ${read.stderr}`);
  const stamp = JSON.parse(read.stdout);
  assert.equal(stamp.sourceDigest, expectedDigest, 'Stale Android crash APK: rebuild assembleDebugAndroidTest');
  return {
    sourceDigest: stamp.sourceDigest,
    apk: { file: basename(apk), sha256: createHash('sha256').update(readFileSync(apk)).digest('hex') },
  };
}
