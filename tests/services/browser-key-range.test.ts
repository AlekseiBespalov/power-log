import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { BrowserRideStore } from '../../src/services/browser-ride-store';
import { browserKeyRange } from '../../src/services/browser-storage-keys';

const compare = vi.fn<(lower: IDBValidKey, upper: IDBValidKey) => number>();
const bound = vi.fn((lower: IDBValidKey, upper: IDBValidKey, lowerOpen = false, upperOpen = false) => {
  const order = compare(lower, upper);
  if (order > 0 || (order === 0 && (lowerOpen || upperOpen))) throw new DOMException('Empty range', 'DataError');
  return { lower, upper, lowerOpen, upperOpen };
});
const only = vi.fn((key: IDBValidKey) => ({ lower: key, upper: key, lowerOpen: false, upperOpen: false }));

beforeEach(() => {
  vi.clearAllMocks();
  compare.mockReturnValue(0);
  vi.stubGlobal('indexedDB', { cmp: compare });
  vi.stubGlobal('IDBKeyRange', { bound, only });
});
afterEach(() => vi.unstubAllGlobals());

it.each([
  [true, false],
  [false, true],
  [true, true],
])('returns no range for equal compound keys with open bounds %s/%s', (lowerOpen, upperOpen) => {
  const lower = ['ride', '09223372036854775808', []];
  const upper = structuredClone(lower);
  expect(browserKeyRange(lower, upper, lowerOpen, upperOpen)).toBeUndefined();
  expect(compare).toHaveBeenCalledWith(lower, upper);
  expect(bound).not.toHaveBeenCalled();
  expect(only).not.toHaveBeenCalled();
});

it('uses only for equal compound keys with both bounds closed', () => {
  const key = ['ride', '09223372036854775808', []];
  expect(browserKeyRange(key, structuredClone(key))).toEqual({
    lower: key,
    upper: key,
    lowerOpen: false,
    upperOpen: false,
  });
  expect(only).toHaveBeenCalledWith(key);
  expect(bound).not.toHaveBeenCalled();
});

it('returns no range when the lower key sorts after the upper key', () => {
  compare.mockReturnValue(1);
  expect(browserKeyRange(['ride', 2], ['ride', 1])).toBeUndefined();
  expect(bound).not.toHaveBeenCalled();
  expect(only).not.toHaveBeenCalled();
});

it.each([
  [false, false],
  [false, true],
  [true, false],
  [true, true],
])('retains the openness of ordered bounds %s/%s', (lowerOpen, upperOpen) => {
  compare.mockReturnValue(-1);
  const lower = ['ride', 1],
    upper = ['ride', 2];
  expect(browserKeyRange(lower, upper, lowerOpen, upperOpen)).toEqual({ lower, upper, lowerOpen, upperOpen });
  expect(bound).toHaveBeenCalledWith(lower, upper, lowerOpen, upperOpen);
  expect(only).not.toHaveBeenCalled();
});

it('does not turn invalid keys into an empty result', () => {
  compare.mockImplementationOnce(() => {
    throw new DOMException('Invalid key', 'DataError');
  });
  expect(() => browserKeyRange(NaN, 1)).toThrow('Invalid key');
});

it.each(['telemetry', 'gps'])('returns an empty %s page without opening storage for inverted bounds', async kind => {
  compare.mockReturnValue(1);
  const store = new BrowserRideStore();
  const result =
    kind === 'gps'
      ? store.locationPage('ride', '2000000', '1000000', null, '1')
      : store.pageExact('ride', '2000000', '1000000');
  await expect(result).resolves.toEqual([]);
  expect(bound).not.toHaveBeenCalled();
});
