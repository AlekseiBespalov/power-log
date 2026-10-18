import { RideFailureError, failureCode } from '../core/failures';

export type BrowserFailureStage = 'storage' | 'bluetooth' | 'location';

const domException = (error: unknown): error is DOMException =>
  typeof DOMException !== 'undefined' && error instanceof DOMException;
// GeolocationPositionError.PERMISSION_DENIED
const locationDenied = (error: unknown) =>
  typeof error === 'object' && error !== null && 'code' in error && error.code === 1;

export function browserFailureCode(error: unknown, stage: BrowserFailureStage): string {
  const code = failureCode(error);
  if (code !== 'unknown') return code;
  if (domException(error) && error.name === 'QuotaExceededError') return 'storageFull';
  switch (stage) {
    case 'storage':
      return domException(error) ? 'storageFailed' : 'unknown';
    case 'bluetooth':
      return domException(error) ? 'bikeUnavailable' : 'unknown';
    case 'location':
      return locationDenied(error) ? 'locationDenied' : 'unknown';
  }
}

export function browserFailure(error: unknown, stage: BrowserFailureStage): RideFailureError {
  return error instanceof RideFailureError ? error : new RideFailureError(browserFailureCode(error, stage));
}
