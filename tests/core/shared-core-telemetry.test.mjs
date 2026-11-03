import assert from 'node:assert/strict';
import { Buffer } from 'node:buffer';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import * as core from '../../shared/js/power-log-core.mjs';

const fixture = name => JSON.parse(readFileSync(new URL(`../fixtures/${name}`, import.meta.url), 'utf8'));
const bytes = hex => new Int8Array(Buffer.from(hex, 'hex'));
const hex = array => Buffer.from(array).toString('hex');
const protocol = fixture('protocol.json');

test('Controller bindings expose one notification decoder boundary', () => {
  for (const name of [
    'ControllerFrames',
    'controllerRequestFrame',
    'controllerDecodeIdentity',
    'controllerDecodeReading',
    'controllerPacketKind',
    'controllerReplyMatches',
    'controllerAttachIdentity',
  ])
    assert.equal(Reflect.get(core, name), undefined, name);
});

const frame = payload => {
  const data = bytes(payload);
  const crc = core.controllerCrc(data);
  return new Int8Array([2, data.length, ...data, crc >> 8, crc, 3]);
};
const readyReading = () => {
  const session = new core.TelemetryTransportSession(core.controllerProtocol());
  assert.equal(hex(session.requestFrame('identity')), protocol.requests.identity);
  assert.equal(session.begin(0, false), true);
  const generation = session.generation;
  for (const [from, to] of [
    ['connecting', 'services'],
    ['services', 'characteristics'],
    ['characteristics', 'notifications'],
    ['notifications', 'identity'],
  ])
    assert.equal(session.advance(generation, from, to, 0), 'advanced');
  const identified = session.notifications(generation, 'identity', frame(protocol.identity.payloadHex), 0, 0.1);
  assert.equal(identified.action, 'replyAccepted');
  assert.equal(session.identity.controllerModel, 'X6');
  assert.equal(session.advance(generation, 'identity', 'ready', 0.2), 'advanced');
  const data = bytes(protocol.telemetry.at(-1).frameHex);
  assert.equal(session.notifications(generation, 'telemetry', data.slice(0, 3), 0.2, 0.3).replies.length, 0);
  const measured = session.notifications(generation, 'telemetry', data.slice(3), 0.2, 0.4);
  assert.equal(measured.action, 'replyAccepted');
  return { session, reading: measured.replies[0].reading };
};

test('Controller decoder carries discarded and pending bytes through the JS seam', () => {
  const decoder = core.controllerProtocol();
  const data = frame(protocol.identity.payloadHex);
  const partial = decoder.feed(new Int8Array([9, 8, 7, ...data.slice(0, 3)]), null);
  assert.equal(partial.discardedBytes, '3');
  assert.equal(partial.pendingBytes, 3);
  const reset = decoder.reset();
  assert.equal(reset.discardedBytes, '6');
  assert.equal(reset.pendingBytes, 0);
});

test('Controller generated bindings decode production protocol fixtures', () => {
  assert.equal(core.controllerCrc(new TextEncoder().encode(protocol.crc.ascii)), protocol.crc.expected);
  const decoder = core.controllerProtocol();
  for (const [name, expected] of Object.entries(protocol.requests))
    assert.equal(hex(decoder.requestFrame(name === 'selective' ? 'telemetry' : name)), expected);
  assert.throws(() => decoder.requestFrame('drive'));
  const identityFrame = frame(protocol.identity.payloadHex);
  assert.equal(decoder.feed(identityFrame.slice(0, 5), null).replies.length, 0);
  const identified = decoder.feed(identityFrame.slice(5), null).replies[0];
  assert.equal(identified.kind, 'identity');
  const identity = identified.identity;
  assert.equal(identity.controllerModel, 'X6');
  assert.equal(identity.hasKnownSpeedUnit, true);
  const data = bytes(protocol.telemetry.at(-1).frameHex);
  assert.equal(decoder.feed(data.slice(0, 3), identity).replies.length, 0);
  const measured = decoder.feed(data.slice(3), identity).replies[0];
  assert.equal(measured.kind, 'telemetry');
  const reading = measured.reading;
  assert.equal(reading.identity.controllerModel, identity.controllerModel);
  assert.equal(
    core.controllerWireFields().reduce((mask, field) => mask | (1 << field.bit), 0),
    core.controllerSelectedMask(),
  );
  assert.equal(
    Object.keys(identity).some(key => /serial/i.test(key)),
    false,
  );
  for (const [key, value] of Object.entries(protocol.telemetry.at(-1).expected))
    assert.equal(reading.value(key), value);
  assert.equal(reading.value('controllerSpeedMps'), 3.77 / 3.6);
  for (const row of protocol.telemetry.slice(0, 2))
    assert.equal(decoder.feed(frame(row.payloadHex), identity).replies[0].rejectionReason, 'telemetryCommandOrLength');
  assert.equal(decoder.feed(frame('04'), identity).replies.length, 0);
  assert.equal(decoder.feed(bytes(protocol.framing.corrupted), identity).replies.length, 0);
  const zero = bytes(protocol.identity.payloadHex);
  zero[0] = 0;
  assert.equal(decoder.feed(frame(hex(zero)), null).replies[0].kind, 'identity');
  assert.equal(decoder.feed(data.slice(0, 3), identity).replies.length, 0);
  decoder.reset();
  assert.equal(decoder.feed(data.slice(3), identity).replies.length, 0);
  assert.equal(decoder.feed(data, identity).replies.length, 1);
});

test('Telemetry JS session fails matching malformed replies with their role and reason', () => {
  const malformed = bytes(protocol.telemetry.at(-1).payloadHex);
  malformed[1] = 0;
  const data = bytes(protocol.telemetry.at(-1).frameHex);
  const { session } = readyReading();
  const generation = session.generation;
  const rejected = session.notifications(
    generation,
    'telemetry',
    new Int8Array([...frame(hex(malformed)), ...data]),
    1,
    1.1,
  );
  assert.equal(rejected.action, 'replyRejected');
  assert.equal(rejected.replies.length, 1);
  assert.equal(rejected.replies[0].kind, 'telemetry');
  assert.equal(rejected.replies[0].rejectionReason, 'telemetryMask');
  assert.equal(rejected.replies[0].reading, null);
  assert.equal(session.stage, 'idle');
  assert.equal(session.identity, null);
  assert.equal(session.begin(1.2, false), false);
  assert.equal(session.notifications(generation, 'telemetry', data, 1, 1.2).replies.length, 0);
  const unsupported = fixture('contract/telemetry/cyc/identity.json').cases.find(row => !row.valid);
  const decoder = core.controllerProtocol();
  const identity = decoder.feed(frame(unsupported.payloadHex), null).replies[0];
  assert.equal(identity.kind, 'identity');
  assert.equal(identity.rejectionReason, 'identity');
  assert.equal(identity.identity, null);
});

test('Telemetry JS session consumes one outstanding request and drains coalesced replies', () => {
  const { session } = readyReading();
  const generation = session.generation;
  const data = bytes(protocol.telemetry.at(-1).frameHex);
  const first = session.notifications(generation, 'telemetry', new Int8Array([...data, ...data]), 1, 1.1);
  assert.equal(first.action, 'replyAccepted');
  assert.equal(first.replies.length, 1);
  assert.equal(first.pendingBytes, 0);
  assert.equal(first.discardedBytes, '0');
  assert.equal(session.notifications(generation, 'telemetry', data, 1, 1.2).replies.length, 0);
  const next = session.notifications(generation, 'telemetry', new Int8Array([9, 8, 7, ...data.slice(0, 3)]), 2, 2.1);
  assert.equal(next.pendingBytes, 3);
  assert.equal(next.discardedBytes, '3');
  assert.equal(session.pendingBytes, 3);
  const completed = session.notifications(generation, 'telemetry', data.slice(3), 2, 2.2);
  assert.equal(completed.replies.length, 1);
  assert.equal(completed.pendingBytes, 0);
  assert.equal(completed.discardedBytes, '3');
  assert.equal(session.discardedBytes, '3');
});

test('Telemetry generated transport bindings retain one setup budget and cancellation fence', () => {
  const session = new core.TelemetryTransportSession(core.controllerProtocol());
  assert.equal(hex(session.requestFrame('identity')), protocol.requests.identity);
  assert.equal(session.begin(0, false), true);
  const generation = session.generation;
  assert.equal(session.advance(generation, 'connecting', 'services', 10), 'advanced');
  assert.equal(session.advance(generation, 'services', 'characteristics', 19), 'advanced');
  assert.equal(session.attemptDeadline, 20);
  assert.equal(session.advance(generation, 'characteristics', 'notifications', 20), 'attemptTimeout');
  const recovery = core.telemetryRecoveryPlan(1, false, false, false, null, false, false, false);
  assert.equal(recovery.cancelBeforeRetry, true);
  session.scheduleRetry(recovery, 20);
  assert.equal(session.reconnectDue, 22);
  assert.equal(session.cancellationDeadline, 27);
  assert.equal(session.begin(21, false), false);
  session.disconnected(generation, 22);
  assert.equal(session.begin(22, false), true);
  assert.equal(session.advance(generation, 'connecting', 'services', 30), 'none');
  const ready = new core.TelemetryTransportSession(core.controllerProtocol());
  ready.begin(0, false);
  for (const [expected, next] of [
    ['connecting', 'services'],
    ['services', 'characteristics'],
    ['characteristics', 'notifications'],
    ['notifications', 'identity'],
    ['identity', 'ready'],
  ])
    assert.equal(ready.advance(ready.generation, expected, next, 0), 'advanced');
  assert.equal(ready.responseDeadline(1, 0), 2);
  assert.equal(ready.check(2, 1, null, false, 0), 'resendRequest');
  assert.equal(ready.check(3, 2, null, false, 0), 'responseTimeout');
  const window = new core.TelemetryDiscoveryWindow('9007199254740993', 0);
  assert.equal(window.accepts('9007199254740993', 19.999999), true);
  assert.equal(window.accepts('9007199254740993', 20), false);
  assert.equal(core.telemetryReplyAdmissible(0, 2.5), true);
  assert.equal(core.telemetryRecoveryPlan(6, false, true, true, null, false, false, true), null);
  for (const invalid of [0, 1.5, NaN, Infinity])
    assert.throws(() => core.telemetryRecoveryPlan(invalid, false, true, true, null, false, false, true));
  assert.equal(core.telemetryRecoveryPlan(6, true, true, true, null, false, false, true).delaySeconds, 30);
});
