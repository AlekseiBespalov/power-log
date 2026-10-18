import { expect, it } from 'vitest';
import { capturePrefixAcknowledged } from '../helpers/capture-prefix';

it('does not arm the next crash while a committed prefix still awaits its owner receipt', () => {
  const prefix = { telemetryCount: 16, revision: '17' };
  expect(capturePrefixAcknowledged(prefix, { collectionRevision: '16' }, 16)).toBe(false);
  expect(capturePrefixAcknowledged(prefix, { collectionRevision: '17' }, 16)).toBe(true);
});

it('requires the complete prefix as well as its acknowledged revision', () => {
  expect(capturePrefixAcknowledged({ telemetryCount: 15, revision: '17' }, { collectionRevision: '17' }, 16)).toBe(
    false,
  );
  expect(capturePrefixAcknowledged(undefined, { collectionRevision: null }, 16)).toBe(false);
});
