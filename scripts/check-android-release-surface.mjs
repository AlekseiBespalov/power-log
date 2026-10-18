import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { join } from 'node:path';
import { androidEnvironment, root } from './android-environment.mjs';

const env = androidEnvironment();
const classes = join(root, 'modules/power-log-bridge/android/build/tmp/kotlin-classes/release');
for (const name of ['RecordingEngine', 'RideStore', 'RecordingIdentities', 'RecordingTransactions']) {
  const surface = execFileSync(
    join(env.JAVA_HOME, 'bin/javap'),
    ['-p', '-classpath', classes, `app.powerlog.bridge.${name}`],
    {
      encoding: 'utf8',
    },
  );
  assert.doesNotMatch(
    surface,
    /telemetryEventId|beforeTransactionEnd|afterTransactionEnd|setTelemetryId|setCommandIds|setRideId|setEpoch/,
  );
  if (name === 'RecordingIdentities' || name === 'RecordingTransactions')
    assert.doesNotMatch(surface, /Function\d|WeakHashMap|Map</);
}
console.log('Release class surface: identity callbacks and mutable transaction observers are absent.');
