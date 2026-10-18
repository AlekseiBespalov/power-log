import assert from 'node:assert/strict';
import { test } from 'node:test';
import * as core from '../../shared/js/power-log-core.mjs';

const stamp = '2026-01-01T00:00:00.000000Z';

function reading(reducer, timeUs, value) {
  reducer.append(
    String(timeUs),
    `sample:${timeUs}`,
    `event:${timeUs}`,
    stamp,
    timeUs / 1_000_000,
    'real64',
    String(value),
    0,
    null,
    null,
    0,
    true,
    null,
  );
}
function origin(id, packageName = 'app.synthetic', provider = 'healthConnect') {
  return new core.AnalyticsOrigin(provider, packageName, id, id, 'phone', '1', null);
}
function interval(metric, kcal, provider = 'healthConnect') {
  return new core.AnalyticsEnergy(
    metric,
    'intervalRecord',
    'phone',
    origin(metric, 'app.synthetic', provider),
    '1',
    '1000000',
    '0',
    '1000000',
    stamp,
    stamp,
    kcal,
  );
}

test('JS admission retains original values and emits C15 totals', () => {
  assert.equal(core.analyticsTimeUs(2.5000005), '2500000');
  assert.equal(core.analyticsQuantize('humanPowerW', 'real64', '0.1875'), '188');
  assert.equal(core.analyticsDivide('9007199254740993', '3'), 3002399751580330.5);
  const reducer = new core.AnalyticsMetricReducer('humanPowerW', '0', '1000000');
  reading(reducer, 0, 0.1875);
  reading(reducer, 1000000, 0.1875);
  assert.equal(reducer.outcome(), 'complete');
  assert.equal(reducer.result().integral, 0.188);
  assert.equal(reducer.result().max.value, 0.1875);
  assert.equal(reducer.result().sumQ, '376');
  const zero = new core.AnalyticsMetricReducer('humanPowerW', '0', '1000000');
  reading(zero, 0, '-0.0');
  reading(zero, 1000000, 0);
  assert.equal(zero.result().min.observationId, 'sample:0');
  assert.equal(zero.result().max.observationId, 'sample:0');
  assert.equal(Object.is(zero.result().max.value, -0), true);
});

test('JS reducers return numeric overflow without publishing a partial result', () => {
  const reducer = new core.AnalyticsMetricReducer('motorInputPowerW', '0', '1000000');
  reading(reducer, 0, 1e30);
  assert.equal(reducer.outcome(), 'numericOverflow:motorInputPowerW:sumQ');
  assert.equal(reducer.result(), null);
});

test('JS Health streams preserve actual Apple resting energy in the summary', () => {
  const basal = interval('basal', 2, 'appleHealth');
  const active = interval('active', 1);
  const coverage = new core.AnalyticsEnergyOrigins('phone', '0', '1000000');
  coverage.append('active', active);
  assert.equal(coverage.selected().dataOrigin, 'app.synthetic');
  assert.equal(coverage.selected().coveredUs, '1000000');
  const selection = new core.AnalyticsHealthSelection(
    'basal',
    'phone',
    'app.synthetic',
    '1000000',
    [origin('basal', 'app.synthetic', 'appleHealth')],
    '0',
    '1000000',
    null,
  );
  const sources = [new core.AnalyticsSource('phone', 'iphone', 'appleHealth', 'app.synthetic')];
  const metadata = new core.AnalyticsMetadata(
    '00000000-0000-0000-0000-000000000001',
    stamp,
    1,
    false,
    false,
    false,
    true,
    8,
    stamp,
    1,
    sources,
    '{"synthetic":true}',
  );
  const pin = new core.AnalyticsPin(metadata, '1', 'auto');
  const preparation = new core.AnalyticsPreparation(pin);
  preparation.energy(basal);
  const hrRows = [
    [0, 'app.synthetic', 'heartRateBpm', 100],
    [0, 'app.other', 'heartRateBpm', 200],
    [500000, 'app.synthetic', 'respiratoryRate', 15],
    [1000000, 'app.synthetic', 'heartRateBpm', 100],
    [1000000, 'app.other', 'heartRateBpm', 200],
  ].map(([timeUs, packageName, metric, value], index) => {
    const id = `health:${index}`;
    return new core.AnalyticsItem(
      String(timeUs),
      timeUs / 1_000_000,
      id,
      id,
      'phone',
      String(index + 1),
      'health',
      'phone',
      stamp,
      [metric],
      ['real64'],
      [String(value)],
      null,
      packageName === 'app.synthetic' ? 'builderSnapshot' : 'rawQuantity',
      null,
      null,
      0,
      null,
      false,
      false,
      null,
      true,
      origin(id, packageName),
      [metric],
    );
  });
  hrRows.forEach(row => preparation.append(row));
  const hrSelection = new core.AnalyticsHealthSelection(
    'heartRateBpm',
    'phone',
    'app.synthetic',
    '1000000',
    [],
    '0',
    '1000000',
    null,
  );
  const outsideEnergy = new core.AnalyticsEnergy(
    'basal',
    'finalWorkoutTotal',
    'phone',
    origin('outside'),
    '1',
    '2678400000001',
    '0',
    '1000000',
    stamp,
    stamp,
    1,
  );
  preparation.energy(outsideEnergy);
  const prepared = preparation.finish('auto', []);
  assert.equal(prepared.timerUs, '1000000');
  assert.deepEqual(prepared.lapEndsUs, ['1000000']);
  const composer = new core.AnalyticsComposition(prepared, 'auto', [], [selection, hrSelection]);
  hrRows.forEach(row => composer.append(row));
  composer.energy(basal);
  composer.energy(outsideEnergy);
  assert.equal(composer.outcome(), 'complete');
  const summary = JSON.parse(composer.summaryJson('3'));
  assert.equal(summary.basalEnergyKcal, 2);
  assert.equal(summary.riderWorkJoules, undefined);
  assert.equal(summary.averageHeartRateBpm, 100);
  assert.equal(summary.maximumHeartRateBpm, 100);
  assert.equal(summary.heartRateCoveredSeconds, 1);
  assert.equal(summary.healthCount, '5');
  assert.equal(summary.lifecycleCount, '3');
  assert.equal(summary.distance.selection, 'auto');
  composer.cancel();
  assert.equal(composer.outcome(), 'cancelled');
  assert.equal(composer.summaryJson('3'), null);
});

test('JS controller checkpoints and seeded distance totals preserve the raw reduction', () => {
  const item = (seconds, speed = 5) =>
    new core.AnalyticsItem(
      String(seconds * 1000000),
      seconds,
      `event-${seconds}`,
      `original-${seconds}`,
      'controller',
      String(seconds + 1),
      'telemetry',
      'controller',
      stamp,
      ['controllerSpeedMps'],
      ['real64'],
      [String(speed)],
      null,
      null,
      null,
      'connection',
      0,
      null,
      false,
      false,
      null,
      true,
    );
  const kernel = new core.AnalyticsControllerKernel();
  kernel.append(item(0), 0, 'X6', '5.3', 'bike', 'run', 10, '100');
  const first = kernel.append(item(1), 0, 'X6', '5.3', 'bike', 'run', 11, '101');
  const restored = new core.AnalyticsControllerKernel();
  restored.restore(item(1), 0, 'X6', '5.3', 'bike', 'run', kernel.segment(), 11, '101');
  assert.equal(restored.previousObservationId(), 'original-1');
  const second = restored.append(item(2), 0, 'X6', '5.3', 'bike', 'run', 12, '102');
  const totals = new core.AnalyticsDistanceReducer('0', '2000000', first.distanceQ, '1000000');
  totals.append(second);
  assert.equal(totals.result('2000000').distanceQ, '10000000');
  assert.equal(totals.result('2000000').coveredUs, '2000000');
  for (const [monotonic, counter] of [
    [10, '102'],
    [12, '100'],
  ]) {
    const raw = new core.AnalyticsControllerKernel();
    raw.append(item(1), 0, 'X6', '5.3', 'bike', 'run', 11, '101');
    const resumed = new core.AnalyticsControllerKernel();
    resumed.restore(item(1), 0, 'X6', '5.3', 'bike', 'run', raw.segment(), 11, '101');
    assert.equal(raw.append(item(2), 0, 'X6', '5.3', 'bike', 'run', monotonic, counter), null);
    assert.equal(resumed.append(item(2), 0, 'X6', '5.3', 'bike', 'run', monotonic, counter), null);
  }
  const sources = [new core.AnalyticsSource('phone', 'browser', null, null)];
  const profile = core.analyticsProfileTotals('gps:phone', '10000000', '2000000', '2000000', sources);
  assert.equal(profile.label, 'GPS · Browser');
  assert.equal(core.analyticsDistanceSelection('auto', [profile], false).selected.source, 'gps:phone');
  assert.equal(core.analyticsDistanceSelection('auto', [profile], true).selected, null);
  const hydrated = core.analyticsStoredInterval(
    second.startUs,
    second.endUs,
    second.distanceQ,
    second.segment,
    second.activeInterval,
    item(1),
    item(2),
    second.speed0Q,
    second.speed1Q,
    second.indivisible,
  );
  const clipped = new core.AnalyticsDistanceReducer('1250000', '1750000');
  clipped.append(hydrated);
  assert.equal(clipped.result('500000').distanceQ, '2500000');
});

test('FIT timeline accessors preserve ordinary pause, interruption and lap boundaries', () => {
  const item = (time, action, interrupted = false) =>
    new core.AnalyticsItem(
      String(time * 1e6),
      time,
      `event:${time}:${action}`,
      `event:${time}:${action}`,
      'phone',
      '10',
      'lifecycle',
      'phone',
      stamp,
      [],
      [],
      [],
      null,
      null,
      'epoch',
      null,
      0,
      action,
      false,
      interrupted,
      interrupted ? '20' : null,
      true,
    );
  const ordinary = new core.AnalyticsTimeline('3000000', [item(1, 'lap'), item(2, 'pause')]);
  assert.deepEqual(ordinary.startsUs, ['0']);
  assert.deepEqual(ordinary.endsUs, ['2000000']);
  assert.deepEqual(ordinary.lapEndsUs, ['1000000', '3000000']);
  assert.equal(ordinary.activeThroughEnd, false);
  assert.equal(ordinary.rangeTimerUs('500000', '2500000'), '1500000');
  assert.deepEqual([...ordinary.assign(Float64Array.of(1.9, 2, 3), 'telemetry', null, null, null).interval], [1, 0, 0]);
  const interrupted = new core.AnalyticsTimeline('3000000', [item(2, 'pause', true), item(2, 'resume')]);
  const assignment = interrupted.assign(Float64Array.of(2, 2, 3), 'telemetry', null, Float64Array.of(20, 21, 22), null);
  assert.deepEqual([...assignment.interval], [1, 2, 2]);
  assert.deepEqual([...assignment.interruption], [0, 1, 1]);
  assert.equal(interrupted.timerUs, '3000000');
});

test('FIT bulk statistics expose summary kernels and missing-reading barriers', () => {
  const reducer = new core.AnalyticsFitStatistics('3000000');
  reducer.telemetry(
    Float64Array.of(0.1, 2.6000000000000005),
    Float64Array.of(100, 100),
    Float64Array.of(80, 80),
    Float64Array.of(-100, -100),
    Int32Array.of(1, 1),
    Int32Array.of(0, 0),
  );
  assert.equal(reducer.powerResult().integral, 250);
  assert.equal(reducer.powerResult().coveredUs, '2500000');
  assert.equal(reducer.cadenceResult().average, 80);
  assert.equal(reducer.motorResult().integral, -250);
  reducer.gpsSpeed(0, 3, 1);
  reducer.gpsSpeed(1, 40, 1);
  reducer.gpsSpeed(2, 41, 1);
  assert.equal(reducer.maximumSpeed(), 40);
  reducer.heartRate(0, 100, 1, 0, null, null);
  reducer.heartRate(1, NaN, 1, 0, null, null);
  reducer.heartRate(2, 100, 1, 0, null, null);
  assert.equal(reducer.heartRateResult().average, null);
  assert.equal(reducer.heartRateResult().coveredUs, '0');
  const empty = new core.AnalyticsFitStatistics('0').powerResult();
  assert.equal(empty.integral, 0);
  assert.equal(empty.coveredUs, '0');
});

test('FIT uses core Health owner qualification and energy precedence', () => {
  assert.equal(core.analyticsHealthSource('watch', ['phone', 'watch']), 'watch');
  assert.equal(core.analyticsHealthSource('watch', ['phone']), 'phone');
  assert.equal(core.analyticsValid('verticalAccuracyM', 0, null), true);
  assert.equal(core.analyticsValid('verticalAccuracyM', 21, null), false);
  const reducer = new core.HealthEnergyConsumption('phone', '1000000', null);
  reducer.append(interval('active', 1));
  assert.equal(reducer.value(), 1);
  const final = new core.AnalyticsEnergy(
    'active',
    'finalWorkoutTotal',
    'phone',
    origin('final'),
    '1',
    '2000000',
    '0',
    '1000000',
    stamp,
    stamp,
    0,
  );
  assert.equal(core.analyticsEnergyAvailable(final), true);
  reducer.append(final);
  assert.equal(reducer.value(), 0);
});

test('JS FIT consumption preserves Apple interval and final resting energy', () => {
  const consumption = new core.HealthEnergyConsumption('phone', '1000000', 'app.synthetic', 'basal');
  assert.equal(consumption.value(), null);
  consumption.append(interval('basal', 1, 'appleHealth'));
  assert.equal(consumption.value(), 1);
  consumption.append(
    new core.AnalyticsEnergy(
      'basal',
      'finalWorkoutTotal',
      'phone',
      origin('final', 'app.synthetic', 'appleHealth'),
      '2',
      '2000000',
      '0',
      '1000000',
      stamp,
      stamp,
      0,
    ),
  );
  assert.equal(consumption.value(), 0);
});

test('JS energy accessors preserve preparation and consumption collection guards', () => {
  const row = (collectionUs, kind = 'cumulativeSnapshot') =>
    new core.AnalyticsEnergy(
      'active',
      kind,
      'phone',
      origin('guard'),
      '1',
      collectionUs,
      '0',
      '10000000',
      stamp,
      stamp,
      80,
    );
  const consumer = new core.HealthEnergyConsumption('phone', '10000000', null);
  for (const time of ['-2000000', '2678400000001']) {
    assert.equal(core.analyticsEnergyAvailable(row(time)), false);
    consumer.append(row(time));
  }
  assert.equal(consumer.value(), null);
  assert.equal(core.analyticsEnergyAvailable(row('-1000000')), true);
  consumer.append(row('-1000000'));
  assert.equal(consumer.value(), 80);
});

test('JS heart-rate origin accessor delegates coverage and UTF-8 ties to the core', () => {
  const reducer = new core.AnalyticsHeartRateOrigins('phone', '0', '10000000');
  for (const [time, packageName, value] of [
    [0, 'app.a', 100],
    [1000000, 'app.b', 200],
    [2000000, 'app.a', 100],
    [3000000, 'app.b', 200],
  ]) {
    reducer.append(String(time), origin(`${packageName}:${time}`, packageName), value, null, null);
  }
  assert.equal(reducer.selected(), 'app.a');
});

test('FIT timeline retains its normalized end without a seconds round trip', () => {
  const timeline = new core.AnalyticsTimeline(core.analyticsTimeUs(0.512500125), []);
  assert.equal(timeline.endUs, '512500');
  assert.equal(timeline.timerUs, timeline.endUs);
  assert.equal(timeline.rangeTimerUs('0', timeline.endUs), timeline.endUs);
});

test('energy collection time is interval end while snapshots retain their own time', () => {
  const consumer = new core.HealthEnergyConsumption('phone', '10000000', null);
  for (const [start, end, kcal] of [
    [-2, 2, 4],
    [8, 12, 6],
  ]) {
    const row = new core.AnalyticsEnergy(
      'active',
      'intervalRecord',
      'phone',
      origin(`interval:${start}`),
      '1',
      String(start * 1e6),
      String(start * 1e6),
      String(end * 1e6),
      stamp,
      stamp,
      kcal,
    );
    assert.equal(row.collectionUs, String(end * 1e6));
    assert.equal(core.analyticsEnergyAvailable(row), true);
    consumer.append(row);
  }
  assert.equal(consumer.value(), 5);
  for (const end of ['-2000000', '2678400000001']) {
    const row = new core.AnalyticsEnergy(
      'active',
      'intervalRecord',
      'phone',
      origin(`outside:${end}`),
      '1',
      '0',
      (BigInt(end) - 1000000n).toString(),
      end,
      stamp,
      stamp,
      80,
    );
    assert.equal(row.collectionUs, end);
    assert.equal(core.analyticsEnergyAvailable(row), false);
    consumer.append(row);
  }
  assert.equal(consumer.value(), 5);
  for (const kind of ['cumulativeSnapshot', 'finalWorkoutTotal']) {
    const row = new core.AnalyticsEnergy(
      'active',
      kind,
      'phone',
      origin(kind),
      '1',
      '-2000000',
      '0',
      '10000000',
      stamp,
      stamp,
      80,
    );
    assert.equal(row.collectionUs, '-2000000');
    assert.equal(core.analyticsEnergyAvailable(row), false);
    consumer.append(row);
  }
  assert.equal(consumer.value(), 5);
});
