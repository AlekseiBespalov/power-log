import { mkdirSync, writeFileSync } from 'node:fs';
import { sourceDigest } from './crash-proof.cjs';
const directory = 'modules/power-log-bridge/android/build/generated/crash-test-assets';
mkdirSync(directory, { recursive: true });
writeFileSync(`${directory}/crash-build.json`, JSON.stringify({ sourceDigest: sourceDigest() }) + '\n');
