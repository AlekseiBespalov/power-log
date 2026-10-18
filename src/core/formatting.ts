import { presentationDuration } from '../../shared/js';

export function formatDuration(seconds: number | null): string {
  return presentationDuration(seconds);
}
