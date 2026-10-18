import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import * as core from '../../shared/js/power-log-core.mjs';

const fixture = name =>
  JSON.parse(readFileSync(new URL(`../fixtures/contract/presentation/${name}.json`, import.meta.url), 'utf8'));
const number = value => (value == null ? null : Number(value));
const capabilities = () => new core.PresentationCapabilities(true, true, true, true, true, 'appleHealth', false);
const startContext = (row = {}) =>
  new core.PresentationStartContext(
    row.surface ?? 'rider',
    row.hasSession ?? false,
    row.endingOwner ?? false,
    row.recoveredForeignOwner ?? false,
    row.unresolvedOwner ?? false,
    row.finishing ?? false,
    row.busy ?? false,
    row.recovering ?? false,
    row.deletionProbeInFlight ?? false,
  );

test('generated connection preview retains raw ordering and applies mapped-clock deadlines', () => {
  const preview = new core.ControllerPreviewPresentation();
  assert.equal(preview.observe('connection', 2, 101, 9, null, 10), true);
  assert.equal(preview.observe('connection', 1, 100, 7, null, 10), false);
  assert.equal(preview.view('connected', 10, 10).faultCode, 9);
  assert.equal(preview.view('connected', null, 10).faultCode, null);
  for (const row of fixture('freshness').connection) {
    const view = preview.view(row.connection, row.acquired, row.now);
    assert.equal(view.display, row.display);
    assert.equal(view.nextDeadline, row.deadline);
    assert.equal(core.presentationTransportDisplay(row.connection, row.acquired, row.now), row.display);
  }
});

test('generated formatting preserves exact integers, speed units and distance provenance', () => {
  const f = fixture('formatting');
  for (const row of f.metrics)
    assert.equal(core.presentationFormatMetric(row.id, number(row.value), row.unit), row.text);
  for (const row of f.differences)
    assert.equal(core.presentationFormatDifference(row.id, row.a, row.b, row.aExact, row.bExact, 'km/h'), row.text);
  for (const row of f.duration) assert.equal(core.presentationDuration(row.seconds), row.text);
  assert.equal(core.presentationMetric('distanceMeters', 'mph').unit, 'km');
});

test('generated options freeze the single admitted rate and require a bike for Health-off Start with GPS', () => {
  const f = fixture('options');
  for (const row of f.options) {
    const options = core.presentationResolveOptions(
      new core.PresentationOptions(row.indoor, row.watch, row.health, row.recordGPS, row.sampleHz),
      capabilities(),
    );
    assert.equal(options.sampleHz, row.expectedRate);
    assert.equal(options.recordGPS, row.expectedGPS);
    assert.equal(options.saveToHealth, row.expectedHealth);
    assert.equal(options.useWatch, row.expectedWatch);
  }
  assert.deepEqual(Array.from(core.presentationSampleRates()), f.validRates);
  assert.throws(() => new core.PresentationOptions('outdoor', null, null, null, null));
  assert.throws(() => new core.PresentationOptions(false, 'watch', null, null, null));
  assert.throws(() => new core.PresentationOptions(false, null, null, null, '8'));
  assert.throws(() =>
    core.presentationResolveOptions(new core.PresentationOptions(false, null, null, null, NaN), capabilities()),
  );
  const options = core.presentationResolveOptions(
    new core.PresentationOptions(false, false, false, true, 8),
    capabilities(),
  );
  assert.equal(
    core.presentationStartReadiness(options, capabilities(), false, false, null, startContext(), null).error,
    'bikeRequired',
  );
  assert.equal(
    core.presentationStartReadiness(options, capabilities(), true, false, null, startContext(), null).available,
    true,
  );
  assert.equal(options.sampleHz, 8);
});

test('generated preferences are preset keyed, independent and refuse unreadable writes', () => {
  const defaults = core.presentationDefaultPreferences();
  assert.deepEqual(JSON.parse(defaults.storedJson), fixture('preferences').defaults);
  const changed = defaults
    .withPreset('battery', new core.PresentationPreset(['batteryVoltageV'], defaults.view('battery').charts, '30', 2))
    .withSelections('temperature', 'battery', 'all');
  const reopened = core.presentationParsePreferences(changed.storedJson);
  assert.equal(reopened.activeView, 'temperature');
  assert.equal(reopened.historyView, 'battery');
  assert.deepEqual(Array.from(reopened.view('battery').numbers), ['batteryVoltageV']);
  assert.equal(reopened.view('ride').range, '120');
  const f = fixture('preferences').loadFailure;
  const failed = core.presentationLoadPreferences(changed, null, f.error);
  const edited = failed.change(
    defaults.withSelections(f.edit.activeView, f.edit.historyView, String(f.edit.historyRange)),
  );
  assert.equal(edited.preferences.activeView, f.edit.activeView);
  assert.equal(edited.preferences.historyView, f.edit.historyView);
  assert.equal(edited.preferences.historyRange, String(f.edit.historyRange));
  assert.equal(edited.writable, f.writable);
  assert.equal(edited.shouldSave, f.shouldSave);
  assert.equal(edited.error, failed.error);
  assert.ok(edited.error.includes(f.error));
  assert.ok(edited.error.includes(f.notice));
  const reloaded = core.presentationLoadPreferences(edited.preferences, defaults.storedJson, null);
  assert.equal(reloaded.preferences.storedJson, defaults.storedJson);
  assert.equal(reloaded.error, null);
  assert.equal(reloaded.writable, true);
  assert.equal(reloaded.shouldSave, false);
  assert.equal(reloaded.change(changed).shouldSave, true);
  const indoor = defaults.withOptions(new core.PresentationOptions(true, false, true, null, null), 2);
  assert.equal(indoor.options.recordGPS, null);
  assert.equal(core.presentationResolveOptions(indoor.startOptions, capabilities()).recordGPS, false);
  assert.throws(() =>
    core.presentationParsePreferences(JSON.stringify({ ...fixture('preferences').defaults, sampleHz: 3 })),
  );
});

test('generated native Start context replaces Watch guards and shares readiness admission', () => {
  const options = core.presentationResolveOptions(
    new core.PresentationOptions(false, true, true, true, 2),
    capabilities(),
  );
  for (const row of fixture('start').cases) {
    const context = startContext(row);
    const action = new core.RideActionPresentation().admit(
      'a',
      null,
      'start',
      row.phase,
      null,
      false,
      null,
      null,
      context,
    );
    const readiness = core.presentationStartReadiness(options, capabilities(), true, true, row.phase, context, null);
    assert.equal(action.admitted, row.admitted, row.name);
    assert.equal(readiness.available, row.admitted, row.name);
    assert.equal(action.error, readiness.error, row.name);
  }
});

test('generated provider names and preference units follow shared declarations', () => {
  const f = fixture('formatting');
  for (const row of f.providers) assert.equal(core.presentationProvider(row.provider), row.label);
  const defaults = core.presentationDefaultPreferences();
  for (const row of f.speedUnits) {
    if (row.accepted) {
      assert.equal(defaults.withDisplay(row.unit, 'auto').speedUnit, row.unit);
      assert.equal(core.presentationMetric('speedMps', row.unit).unit, row.unit);
    } else {
      assert.throws(() => defaults.withDisplay(row.unit, 'auto'));
      assert.throws(() => core.presentationMetric('speedMps', row.unit));
    }
  }
});

test('presentation projects frozen source facts without a rate', () => {
  const view = core.presentationStateSources(
    'running',
    false,
    true,
    false,
    true,
    true,
    'stale',
    'receiving',
    'receiving',
    'watch',
    5,
    'appleHealth',
    'held',
  );
  assert.equal(view.bike.label, 'Receiving samples');
  assert.equal(view.gps.detail, 'Watch · ±5 m');
  for (const row of fixture('timeouts').cases) {
    const deadline = core.presentationWaitDeadline(row.kind, '10');
    assert.equal(core.presentationWait(row.kind, '10', String(BigInt(deadline) - 1n)).pending, true);
    assert.equal(core.presentationWait(row.kind, '10', deadline).pending, false);
  }
  assert.throws(() => core.presentationWaitDeadline('start', '9223372036854775807'));
});
