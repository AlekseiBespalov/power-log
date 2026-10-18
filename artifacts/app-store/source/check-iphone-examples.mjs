import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createServer } from 'vite';

const reports = JSON.parse(readFileSync(process.argv[2], 'utf8'));
assert.equal(reports.length, 3, 'all three example rides are present');
const server = await createServer({
  configFile: false,
  appType: 'custom',
  server: { middlewareMode: true, hmr: false, ws: false, watch: null },
});
try {
  const { normalizeWorkoutMetadata } = await server.ssrLoadModule('/src/core/workout-payload.ts');
  const { workoutExportReady } = await server.ssrLoadModule('/src/core/workouts.ts');
  const { workoutHealthResultPresentation } = await server.ssrLoadModule('/src/core/workout-presentation.ts');
  for (const report of reports) {
    assert.equal(report.fictional, true, report.id);
    assert.equal(report.exportReady, true, report.id);
    assert.ok(report.summary.averageHeartRateBpm > 0, report.id);
    assert.ok(report.summary.heartRateCoveredSeconds > 0, report.id);
    assert.equal(String(report.verifiedHealthOriginCount), report.summary.healthCount, report.id);
    const record = normalizeWorkoutMetadata(report.metadata);
    assert.equal(record.phase, 'completed', report.id);
    assert.equal(record.example, true, report.id);
    assert.equal(record.watchEnabled, true, report.id);
    assert.equal(record.recordingOwner, 'phone', report.id);
    assert.equal(record.healthPublisher, 'watch', report.id);
    assert.equal(record.saveToHealth, false, report.id);
    assert.equal(record.healthKitState, 'notRequested', report.id);
    assert.equal(workoutExportReady(record), true, report.id);
    assert.deepEqual(workoutHealthResultPresentation(record), { label: 'Saved only in Power Log', details: [] });
  }
  console.log(
    `History presentation: ${reports.length} passed, 0 failed; local phone rides with Watch Health observations.`,
  );
} finally {
  await server.close();
}
