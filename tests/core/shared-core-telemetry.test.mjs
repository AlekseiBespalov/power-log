import assert from 'node:assert/strict';
import { Buffer } from 'node:buffer';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import * as core from '../../shared/generated/js/kit/power-log-kit.mjs';

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
