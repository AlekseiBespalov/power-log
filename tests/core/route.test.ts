import { describe, expect, it } from 'vitest';
import { routePaths } from '../../src/core/route';
describe('private route preview', () => {
  it('does not join separate recorded GPS segments', () => {
    const paths = routePaths([{ latitude: 0, longitude: 0, segment: 0 }, { latitude: 0.1, longitude: 0.1, segment: 0 }, { latitude: 1, longitude: 1, segment: 1 }, { latitude: 1.1, longitude: 1.1, segment: 1 }], 640, 260);
    expect(paths).toHaveLength(2);
    expect(paths.every(path => path.startsWith('M') && path.includes(' L'))).toBe(true);
  });
  it('handles the date line and identical points without invalid coordinates', () => {
    const paths = routePaths([{ latitude: 0, longitude: 179.9 }, { latitude: 0.1, longitude: -179.9 }], 640, 260);
    expect(paths.join('')).not.toMatch(/NaN|Infinity/);
    expect(routePaths([{ latitude: 0, longitude: 0 }, { latitude: 0, longitude: 0 }], 640, 260)).toEqual(['M320.00,130.00 L320.00,130.00']);
  });
  it('rejects missing or invalid fixes', () => {
    expect(routePaths([{ latitude: NaN, longitude: 0 }, { latitude: 91, longitude: 0 }], 640, 260)).toEqual([]);
  });
});
