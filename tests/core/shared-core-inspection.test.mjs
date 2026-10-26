import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import * as core from '../../shared/js/power-log-core.mjs';

const fixture = name => JSON.parse(readFileSync(new URL(`../fixtures/contract/${name}.json`, import.meta.url), 'utf8'));
const stamp = '2026-01-01T00:00:00.123456789Z';
function item(row, metric, index) {
  const present = row.value !== null && row.value !== undefined;
  return new core.AnalyticsItem(
    row.timeUs,
    row.elapsedSeconds ?? Number(row.timeUs) / 1_000_000,
    row.id,
    row.id,
    row.producer ?? 'controller',
    row.sequence ?? String(index + 1),
    row.kind ?? 'telemetry',
    'controller',
    stamp,
    present ? [metric] : [],
    present ? [typeof row.value === 'string' ? 'integer64' : 'real64'] : [],
    present ? [String(row.value)] : [],
    null,
    null,
    row.clock ?? null,
    row.connection ?? null,
    Number(row.interruptionIndex ?? 0),
    row.action ?? null,
    false,
    row.interrupted ?? false,
    row.controllerSequence ?? null,
    true,
    null,
    [metric],
  );
}

test('generated inspection query preserves raw barriers and exact original anchors', () => {
  const cases = fixture('inspection/statistics').cases.filter(row => row.id === 'missing-power-barrier');
  for (const row of cases) {
    const query = new core.InspectionMetricQuery(
      row.metric,
      row.startUs,
      row.endUs,
      row.rideEndUs,
      [],
      new core.InspectionTargetInput('1000000', 'c', '1000000'),
      null,
      false,
    );
    row.points.forEach((point, index) => query.append(item(point, row.metric, index), true));
    const result = query.result();
    assert.equal(result.outcome, 'complete');
    assert.equal(result.statistics.count, row.count);
    assert.equal(result.statistics.coveredUs, row.coveredUs);
    assert.equal(result.statistics.integral, row.integral);
    assert.equal(result.selection.observationId, 'c');
    assert.equal(result.selection.timestamp, stamp);
  }
});

test('generated inspection plot keeps original extrema and course segments', () => {
  for (const row of fixture('inspection/plot').cases) {
    const query = new core.InspectionPlotQuery(row.metric, row.startUs ?? '0', row.endUs ?? '20000000', 1, 16384);
    row.points.forEach((point, index) => query.append(item(point, row.metric, index), true, point.boundary ?? false));
    const result = query.result();
    assert.deepEqual(
      result.map(point => point.original.observationId),
      row.expected,
    );
    assert.deepEqual(
      result.map(point => point.segment),
      row.segments,
    );
  }
});

test('generated inspection gate rejects A to B to A and epoch-only refreshes', () => {
  const gate = new core.InspectionRequestGate();
  const tickets = [];
  for (const row of fixture('inspection/acceptance').requests) {
    const ticket = gate.begin(new core.InspectionRequestContext(row.source, '5', row.selection, row.epoch, '4'));
    tickets.forEach(old => assert.equal(gate.accepts(old), false));
    assert.equal(gate.accepts(ticket), true);
    tickets.push(ticket);
  }
  gate.cancel();
  tickets.forEach(ticket => assert.equal(gate.accepts(ticket), false));
});

test('generated held tail expires at six seconds without replacing the original', () => {
  for (const row of fixture('inspection/tail').cases)
    assert.equal(
      core.inspectionHeldTail('humanPowerW', 'a', 10, 200, row.evidence, row.acquired, row.now, row.elapsed),
      row.expected,
      row.id,
    );
  const policy = new core.InspectionMetricPolicy('humanPowerW');
  assert.equal(policy.plotGapUs, '6000000');
  assert.equal(policy.integrationGapUs, '2500000');
});

test('generated GPS clock admits the fifteen-second boundary and deduplicates replay', () => {
  const clock = new core.GPSAcquisitionClock();
  const timeline = new core.GPSTimelineInput(
    'epoch',
    core.protocolTimestampMicroseconds('2026-01-01T00:00:00Z'),
    0,
    null,
    null,
    false,
  );
  const fix = new core.GPSLocationInput('2026-01-01T00:16:25Z', 40, 20, 5);
  const receipt = core.protocolTimestampMicroseconds('2026-01-01T00:16:40Z');
  const batch = clock.mapBatch([fix], receipt, 1000, timeline);
  assert.equal(batch.discontinuity, false);
  assert.equal(batch.fixes.length, 1);
  assert.equal(batch.fixes[0].acquisitionMonotonic, 985);
  assert.equal(batch.fixes[0].elapsedUs, '985000000');
  assert.equal(clock.admit(batch.fixes[0]), true);
  assert.equal(clock.admit(clock.mapBatch([fix], receipt, 1000, timeline).fixes[0]), false);
  const anotherRide = new core.GPSAcquisitionClock();
  const freshBatch = anotherRide.mapBatch([fix], receipt, 1000, timeline);
  assert.equal(anotherRide.admit(batch.fixes[0]), false, 'a mapped fix belongs to its creating ride clock');
  assert.equal(anotherRide.admit(freshBatch.fixes[0]), true);
});

test('generated distance inspection chooses supported originals without interpolation', () => {
  for (const row of fixture('inspection/distance').cases) {
    const boundary = point =>
      point
        ? new core.InspectionDistanceBoundary(
            item(point, 'distanceMeters', 0),
            point.segment,
            point.startUs,
            point.endUs,
            point.tieKey,
          )
        : null;
    const result = core.inspectionDistanceOriginal(
      new core.InspectionTargetInput(row.timeUs, row.anchor ?? null, row.anchor ? row.timeUs : null),
      boundary(row.before),
      boundary(row.after),
      boundary(row.anchored),
    );
    assert.equal(result?.observationId ?? null, row.expected, row.id);
  }
});

function lifecycle(row, metric) {
  return (row.lifecycle ?? []).map((event, index) =>
    item({ ...event, id: `event-${index}`, kind: 'lifecycle', producer: 'phone' }, metric, index),
  );
}

test('generated statistics consume persisted owned checkpoints and raw changed-range boundaries', () => {
  for (const row of fixture('inspection/accelerators').statistics) {
    const events = lifecycle(row, row.metric);
    const originals = row.points.map((point, index) => item(point, row.metric, index));
    const query = new core.InspectionMetricQuery(
      row.metric,
      row.startUs,
      row.endUs,
      row.rideEndUs,
      events,
      null,
      null,
      false,
    );
    for (const [start, end] of row.buckets) {
      const bucket = new core.InspectionMetricBucket(
        row.metric,
        start,
        end,
        row.rideEndUs,
        events,
        originals.findLast(point => BigInt(point.collectionUs) < BigInt(start)) ?? null,
      );
      const owned = originals.filter(
        point => BigInt(point.collectionUs) >= BigInt(start) && BigInt(point.collectionUs) < BigInt(end),
      );
      owned.forEach(point => bucket.append(point, true));
      const checkpoint = new core.InspectionMetricCheckpoint(bucket.checkpoint().payload);
      if (query.canMerge(checkpoint)) query.mergeOwned(checkpoint);
      else owned.forEach(point => query.append(point, true));
    }
    const stats = query.result().statistics;
    assert.equal(stats.count, row.count, row.id);
    assert.equal(stats.coveredUs, row.coveredUs, row.id);
    assert.equal(stats.integral, row.integral, row.id);
    assert.equal(stats.sampleMean, row.mean, row.id);
    const restored = new core.InspectionMetricQuery(
      row.metric,
      row.startUs,
      row.endUs,
      row.rideEndUs,
      events,
      null,
      null,
      false,
    );
    restored.restore(new core.InspectionMetricCheckpoint(query.checkpoint().payload));
    assert.equal(restored.result().statistics.coveredUs, row.coveredUs, row.id);
  }
});

test('generated plot aggregates preserve original extrema and raw barriers across cache cuts', () => {
  for (const row of fixture('inspection/accelerators').plot) {
    const query = new core.InspectionPlotQuery(row.metric, row.startUs, row.endUs, 1, 16384);
    for (const [start, end] of row.buckets) {
      const bucket = new core.InspectionPlotBucket(row.metric, start, end);
      const prepared = new core.InspectionPlotBucket(row.metric, start, end);
      row.points.forEach((point, index) => {
        const original = item(point, row.metric, index);
        bucket.append(original, true);
        prepared.appendOriginal(new core.InspectionOriginalInput(original), true);
      });
      assert.equal(prepared.aggregate().payload, bucket.aggregate().payload, row.id);
      const aggregate = new core.InspectionPlotCache(bucket.aggregate().payload);
      assert.equal(query.canMerge(aggregate), true, row.id);
      query.merge(aggregate);
    }
    const result = query.result();
    assert.deepEqual(
      result.map(point => point.original.observationId),
      row.expected,
      row.id,
    );
    assert.deepEqual(
      result.map(point => point.segment),
      row.segments,
      row.id,
    );
  }
});

test('generated statistics decoding rejects shifted and out-of-range retained originals', () => {
  for (const row of fixture('inspection/accelerators').invalidStatistics) {
    const bucket = new core.InspectionMetricBucket(row.metric, row.startUs, row.endUs, row.endUs, [], null);
    row.points.forEach((point, index) => bucket.append(item(point, row.metric, index), true));
    const payload = JSON.parse(bucket.checkpoint().payload);
    if (!row.field) payload.range = { start: row.shiftedStartUs, end: row.shiftedEndUs };
    else if (row.field === 'originalElapsed') payload.firstRaw.original.elapsed = Number(row.outsideUs) / 1e6;
    else if (['minimum', 'maximum', 'previous'].includes(row.field)) payload[row.field].reading.time = row.outsideUs;
    else payload[row.field].time = row.outsideUs;
    assert.throws(() => new core.InspectionMetricCheckpoint(JSON.stringify(payload)), undefined, row.id);
  }
});

test('generated GPS admission retains raw fix and receipt precision', () => {
  for (const row of fixture('gps/raw-time').cases) {
    const clock = new core.GPSAcquisitionClock();
    const timeline = new core.GPSTimelineInput(
      'epoch',
      core.protocolTimestampMicroseconds('2026-01-01T00:00:00Z'),
      0,
      null,
      null,
      false,
    );
    if (row.prime)
      clock.mapBatchAt([new core.GPSLocationInput(row.prime, 40, 20, 5)], row.prime, row.primeUptime, timeline);
    const batch = clock.mapBatchAt([new core.GPSLocationInput(row.fix, 40, 20, 5)], row.receipt, row.uptime, timeline);
    assert.equal(batch.fixes.length > 0, row.mapped, row.id);
    if (row.mapped) {
      assert.ok(Math.abs(batch.fixes[0].acquisitionMonotonic - row.acquisition) < 1e-12, row.id);
      assert.equal(batch.fixes[0].elapsedUs, row.elapsedUs, row.id);
      assert.equal(batch.fixes[0].uncertain, row.uncertain, row.id);
    }
  }
});

test('generated rendered adapters retain coarse continuity and original timestamps', () => {
  const first = item({ id: 'a', timeUs: '0', value: 100 }, 'humanPowerW', 0);
  const last = item({ id: 'b', timeUs: '100000000', value: 200 }, 'humanPowerW', 1);
  const prepared = [first, last].map(item => new core.InspectionOriginalInput(item));
  assert.equal(core.inspectionRenderedSegment('humanPowerW', 0, 100, 100, 200, false), false);
  assert.equal(core.inspectionRenderedSegment('courseDegrees', 0, 350, 1, 10, false), true);
  const selected = core.inspectionRenderedOriginal('humanPowerW', prepared, new Int32Array([1, 0]), '50000000');
  assert.equal(selected.observationId, 'a');
  assert.equal(selected.elapsedSeconds, 0);
  assert.equal(selected.timestamp, stamp);
  assert.equal(core.inspectionRenderedOriginal('humanPowerW', prepared, new Int32Array([1, 1]), '50000000'), null);
});

test('prepared targets retain the core one-microsecond anchor and explicit identity', () => {
  const original = new core.InspectionOriginalInput(
    item({ id: 'edge', timeUs: '8000000', value: 160 }, 'humanPowerW', 0),
  );
  const target = new core.InspectionTargetInput('7999999', null, null);
  const anchored = target.withNearbyOriginal('humanPowerW', [original]);
  assert.equal(anchored.timeUs, '7999999');
  assert.equal(anchored.observationId, 'edge');
  assert.equal(anchored.anchorTimeUs, '8000000');
  const outside = new core.InspectionTargetInput('7999990', null, null);
  assert.equal(outside.withNearbyOriginal('humanPowerW', [original]), outside);
  const explicit = new core.InspectionTargetInput('7999999', 'other', '7999999');
  assert.equal(explicit.withNearbyOriginal('humanPowerW', [original]), explicit);
});

test('prepared statistics bucket originals have the same canonical checkpoint as ordinary inputs', () => {
  for (const row of fixture('inspection/statistics').cases.filter(
    row => row.metric === 'humanPowerW' && row.points.length,
  )) {
    const ordinary = new core.InspectionMetricBucket(
      row.metric,
      row.startUs,
      row.endUs,
      row.rideEndUs,
      lifecycle(row, row.metric),
      null,
    );
    const prepared = new core.InspectionMetricBucket(
      row.metric,
      row.startUs,
      row.endUs,
      row.rideEndUs,
      lifecycle(row, row.metric),
      null,
    );
    row.points.forEach((point, index) => {
      const input = item(point, row.metric, index);
      ordinary.append(input, point.selected ?? true);
      prepared.appendPrepared(new core.InspectionOriginalInput(input), point.selected ?? true);
    });
    assert.equal(prepared.checkpoint()?.payload, ordinary.checkpoint()?.payload, row.id);
  }
});

test('timeline metadata admission matches full originals through pause and resume', () => {
  const metadata = new core.AnalyticsMetadata(
    '00000000-0000-4000-8000-000000000001',
    stamp,
    3,
    true,
    false,
    false,
    false,
    8,
    '2026-01-01T00:00:03Z',
    3,
    [],
    '{}',
  );
  const preparation = new core.AnalyticsPreparation(new core.AnalyticsPin(metadata, '1', 'auto'));
  ['begin', 'pause', 'resume', 'stop'].forEach((action, index) =>
    preparation.append(
      item(
        { id: `life-${index}`, timeUs: String(index * 1000000), kind: 'lifecycle', producer: 'phone', action },
        'humanPowerW',
        index,
      ),
    ),
  );
  const prepared = preparation.finish('auto', []);
  const observations = [0, 1, 2, 3].map((time, index) =>
    item({ id: `reading-${index}`, timeUs: String(time * 1000000), value: 100 }, 'humanPowerW', index),
  );
  assert.deepEqual(
    observations.map(value => prepared.activeInterval(value)),
    [0, null, 1, 1],
  );
  observations.forEach(value =>
    assert.equal(
      prepared.activeIntervalAt(value.collectionUs, value.producer, value.sequence, value.clockEpoch),
      prepared.activeInterval(value),
    ),
  );
});

test('retained cache encoding preserves canonical bytes and original times across eviction', () => {
  const encoder = new core.InspectionCacheEncoder();
  for (let index = 0; index < 160; index++) {
    const timeUs = String(1123456 + index * 17000);
    const original = item({ timeUs, id: `original-${index}`, value: 50 }, 'humanPowerW', index);
    const bucket = new core.InspectionMetricBucket('humanPowerW', '0', '16000000', '16000000', [], null);
    bucket.append(original, true);
    const checkpoint = bucket.checkpoint();
    const plot = new core.InspectionPlotBucket('humanPowerW', '0', '16000000');
    plot.appendOriginal(new core.InspectionOriginalInput(original), true);
    const cache = plot.aggregate();
    for (let repeat = 0; repeat < 2; repeat++) {
      assert.equal(encoder.statistics(checkpoint), checkpoint.payload);
      assert.equal(encoder.plot(cache), cache.payload);
      const query = new core.InspectionPlotQuery('humanPowerW', '0', '16000000', 1, 16);
      query.merge(new core.InspectionPlotCache(encoder.plot(cache)));
      assert.equal(query.result()[0].original.elapsedSeconds, Number(timeUs) / 1000000);
      assert.equal(query.result()[0].original.timestamp, stamp);
    }
  }
});

test('generated projected boundaries retain the selected metric without a distance alias', () => {
  for (const metric of [
    'gpsDistanceMeters',
    'controllerDistanceMeters',
    'controllerChargeAh',
    'controllerEnergyWh',
    'calculatedChargeAh',
    'calculatedEnergyWh',
  ]) {
    const original = item({ timeUs: '1000000', id: `${metric}-point`, value: 12.5 }, metric, 0);
    const boundary = new core.InspectionDistanceBoundary(original, 0, '0', '1000000', '1', 0, metric);
    const selected = core.inspectionDistanceOriginal(
      new core.InspectionTargetInput('1000000', null, null),
      boundary,
      null,
      null,
    );
    assert.equal(selected.value, 12.5);
    assert.equal(selected.observationId, `${metric}-point`);
    const plot = new core.InspectionPlotQuery(metric, '0', '1000000', 1, 16);
    plot.appendDistance(boundary);
    assert.equal(plot.result()[0].original.value, 12.5);
  }
});

test('generated aligned slots and bucket budgets match the shared fixture', () => {
  const rule = fixture('inspection/aligned-slots');
  for (const row of rule.slots) {
    if (row.invalid) assert.throws(() => core.inspectionPlotSlotUs(row.startUs, row.endUs, row.buckets), row.id);
    else assert.equal(core.inspectionPlotSlotUs(row.startUs, row.endUs, row.buckets), row.slotUs, row.id);
  }
  for (const row of rule.budgets) {
    if (row.invalid) {
      assert.throws(() => core.inspectionPlotBuckets(row.requested, row.metrics), row.id);
      continue;
    }
    assert.equal(core.inspectionPlotBuckets(row.requested, row.metrics), row.buckets, row.id);
    assert.equal(core.inspectionPlotPointLimit(row.metrics), row.pointLimit, row.id);
  }
});

test('generated plot summary merges like direct aligned reduction of a growing ride', () => {
  const metric = 'humanPowerW';
  const rows = Array.from({ length: 400 }, (_, index) => ({
    id: `p${String((index * 7919) % 100003).padStart(6, '0')}`,
    timeUs: String(Math.floor(index / 2) * 5_000_000 + (index >= 200 ? 30_000_000 : 0)),
    value: index % 11 === 0 ? null : (index * 37) % 400,
  }));
  const selected = index => index % 9 !== 0;
  const view = query => query.result().map(point => [point.original.observationId, point.timeUs, point.segment]);
  const summary = new core.InspectionPlotSlotSummary(metric);
  rows.forEach((row, index) => {
    summary.append(item(row, metric, index), selected(index), false);
    if (index % 50 !== 49) return;
    for (const buckets of [64, 161, 512]) {
      const slot = core.inspectionPlotSlotUs('0', row.timeUs, buckets);
      if (slot === null) {
        assert.equal(
          summary.canMergeInto(new core.InspectionPlotQuery(metric, '0', row.timeUs, buckets, 16384)),
          false,
        );
        continue;
      }
      const direct = new core.InspectionPlotQuery(metric, '0', row.timeUs, buckets, 16384, slot);
      rows.slice(0, index + 1).forEach((fed, i) => direct.append(item(fed, metric, i), selected(i), false));
      const merged = new core.InspectionPlotQuery(metric, '0', row.timeUs, buckets, 16384, slot);
      assert.equal(summary.canMergeInto(merged), true, `${index} ${buckets}`);
      summary.mergeInto(merged);
      assert.deepEqual(view(merged), view(direct), `${index} ${buckets}`);
    }
  });
  assert.equal(summary.overflowed, false);
  assert.equal(summary.rawInputs, String(rows.filter((_, index) => selected(index)).length));
  assert.equal(summary.slotUs, '4000000');
  assert.equal(summary.lastRawUs, rows.at(-1).timeUs);
  assert.ok(summary.retainedPoints > 0);
  const prepared = new core.InspectionPlotSlotSummary(metric);
  rows.forEach((row, index) =>
    prepared.appendOriginal(new core.InspectionOriginalInput(item(row, metric, index)), selected(index)),
  );
  const whole = core.inspectionPlotSlotUs('0', rows.at(-1).timeUs, 161);
  const fromItems = new core.InspectionPlotQuery(metric, '0', rows.at(-1).timeUs, 161, 16384, whole);
  summary.mergeInto(fromItems);
  const fromOriginals = new core.InspectionPlotQuery(metric, '0', rows.at(-1).timeUs, 161, 16384, whole);
  prepared.mergeInto(fromOriginals);
  assert.deepEqual(view(fromOriginals), view(fromItems));
  const coarse = new core.InspectionPlotSlotSummary(metric, 16);
  rows.forEach((row, index) => coarse.append(item(row, metric, index), selected(index), false));
  const end = rows.at(-1).timeUs;
  const finer = new core.InspectionPlotQuery(metric, '0', end, 512, 16384, core.inspectionPlotSlotUs('0', end, 512));
  assert.equal(coarse.canMergeInto(finer), false);
  assert.throws(() => coarse.mergeInto(finer));
});

test('generated plot summary merges projected boundaries like the aligned reducer', () => {
  const metric = 'distanceMeters';
  const boundaries = Array.from({ length: 120 }, (_, index) => {
    const timeUs = String(Math.floor(index / 3) * 4_000_000);
    const original = item({ id: `d${index}`, timeUs, value: Math.floor(index / 2) * 2.5 }, metric, index);
    const segment = Math.floor(index / 25);
    return new core.InspectionDistanceBoundary(original, segment, timeUs, timeUs, String(index + 1), segment, metric);
  });
  const end = '200000000';
  const slot = core.inspectionPlotSlotUs('0', end, 64);
  const summary = new core.InspectionPlotSlotSummary(metric, 512, 16384);
  boundaries.forEach(boundary => summary.appendDistance(boundary));
  const direct = new core.InspectionPlotQuery(metric, '0', end, 64, 16384, slot);
  boundaries.forEach(boundary => direct.appendDistance(boundary));
  const merged = new core.InspectionPlotQuery(metric, '0', end, 64, 16384, slot);
  summary.mergeInto(merged);
  const view = query => query.result().map(point => [point.original.observationId, point.timeUs, point.segment]);
  assert.deepEqual(view(merged), view(direct));
});
