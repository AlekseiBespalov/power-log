import { presentationDuration } from '../../shared/js';

export function formatDuration(seconds: number | null): string {
  return presentationDuration(seconds);
}

export type RideDateOptions = { now?: Date; twentyFourHour?: boolean | null; locale?: string };

export function formatRideDate(iso: string, { now = new Date(), twentyFourHour = null, locale }: RideDateOptions = {}) {
  const value = new Date(iso);
  const date = value.toLocaleDateString(locale, {
    weekday: 'short',
    month: 'short',
    day: 'numeric',
    ...(value.getFullYear() === now.getFullYear() ? {} : { year: 'numeric' }),
  });
  const clock: Intl.DateTimeFormatOptions =
    twentyFourHour === null ? {} : twentyFourHour ? { hourCycle: 'h23' } : { hour12: true };
  return `${date} · ${value.toLocaleTimeString(locale, { hour: 'numeric', minute: '2-digit', ...clock })}`;
}
