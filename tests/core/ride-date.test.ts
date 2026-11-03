import { expect, it } from 'vitest';
import { formatRideDate, type RideDateOptions } from '../../src/core/formatting';

const now = new Date(2026, 10, 1, 9, 30);
const afternoon = new Date(2026, 2, 5, 15, 7).toISOString();
const midnight = new Date(2026, 2, 5, 0, 5).toISOString();
const lastYear = new Date(2025, 11, 31, 15, 7).toISOString();
const text = (iso: string, options: RideDateOptions) => formatRideDate(iso, { now, ...options }).replace(/\s/g, ' ');

it('adds the year only to rides from an earlier year', () => {
  expect(text(afternoon, { locale: 'en-US' })).toBe('Thu, Mar 5 · 3:07 PM');
  expect(text(lastYear, { locale: 'en-US' })).toBe('Wed, Dec 31, 2025 · 3:07 PM');
  expect(text(afternoon, { locale: 'en-GB' })).not.toContain('2026');
  expect(text(lastYear, { locale: 'en-GB' })).toContain('2025');
  expect(text(lastYear, { locale: 'en-US', now: new Date(2025, 11, 31, 23, 0) })).not.toContain('2025');
});

it('follows the device 12- or 24-hour clock instead of the language default', () => {
  expect(text(afternoon, { locale: 'en-US', twentyFourHour: true })).toBe('Thu, Mar 5 · 15:07');
  expect(text(midnight, { locale: 'en-US', twentyFourHour: true })).toBe('Thu, Mar 5 · 00:05');
  expect(text(afternoon, { locale: 'en-US', twentyFourHour: false })).toBe('Thu, Mar 5 · 3:07 PM');
  expect(text(afternoon, { locale: 'en-GB', twentyFourHour: false })).toMatch(/ · 3:07 pm$/i);
  expect(text(afternoon, { locale: 'en-GB', twentyFourHour: true })).toMatch(/ · 15:07$/);
});

it('uses the language default when the device clock preference is unknown', () => {
  expect(text(afternoon, { locale: 'en-US' })).toMatch(/ · 3:07 PM$/);
  expect(text(afternoon, { locale: 'en-GB' })).toMatch(/ · 15:07$/);
});
