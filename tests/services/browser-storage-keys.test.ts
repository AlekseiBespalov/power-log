import { expect, it } from 'vitest';
import { browserIndexKey, browserIndexValue, timestampUs } from '../../src/services/browser-storage-keys';
import fixture from '../fixtures/contract/storage/exact-index-keys.json';

it.each(fixture.cases)('encodes and restores exact Int64 $input', ({ input, expectedKey }) => {
  expect(browserIndexKey(input)).toBe(expectedKey);
  expect(browserIndexValue(expectedKey)).toBe(input);
});
it.each(fixture.invalidInputs)('rejects noncanonical or overflowing Int64 %s', input => {
  expect(() => browserIndexKey(input)).toThrow();
});
it('preserves the original UTC string while flooring nine fractional digits once', () => {
  expect(timestampUs('2026-09-09T00:00:00.123456789Z')).toBe('1788912000123456');
  expect(() => timestampUs('2026-02-30T00:00:00Z')).toThrow();
  expect(() => timestampUs('2026-09-09T00:00:00.1234567890Z')).toThrow();
});
