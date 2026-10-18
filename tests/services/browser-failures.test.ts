import { expect, it } from 'vitest';
import { presentationFailure } from '../../shared/js';
import failures from '../fixtures/contract/presentation/failures.json';
import { RideFailureError, failureCode } from '../../src/core/failures';
import { browserFailure, browserFailureCode, type BrowserFailureStage } from '../../src/services/browser-failures';

it.each([
  [new DOMException('Quota exceeded', 'QuotaExceededError'), 'storage', 'storageFull'],
  [new DOMException('Quota exceeded', 'QuotaExceededError'), 'bluetooth', 'storageFull'],
  [new DOMException('The transaction was aborted.', 'AbortError'), 'storage', 'storageFailed'],
  [new DOMException('GATT operation failed for unknown reason.', 'NetworkError'), 'bluetooth', 'bikeUnavailable'],
  [new DOMException('User denied', 'NotAllowedError'), 'bluetooth', 'bikeUnavailable'],
  [{ code: 1, message: 'User denied Geolocation', PERMISSION_DENIED: 1 }, 'location', 'locationDenied'],
  [{ code: 2, message: 'Position unavailable', PERMISSION_DENIED: 1 }, 'location', 'unknown'],
  [Object.assign(new Error('NullPointerException'), { name: 'NullPointerException' }), 'storage', 'unknown'],
  [new RideFailureError('ownerForeign'), 'storage', 'ownerForeign'],
] as const)('classifies %s at the %s stage as %s', (error, stage, code) => {
  expect(browserFailureCode(error, stage as BrowserFailureStage)).toBe(code);
  const failure = browserFailure(error, stage as BrowserFailureStage);
  expect(failure).toMatchObject({ code, message: presentationFailure(code).text });
  for (const forbidden of failures.forbidden) expect(failure.message).not.toContain(forbidden);
});

it.each(failures.unknownInputs)('reads %j only as the unknown failure', input => {
  expect(failureCode(input)).toBe('unknown');
  expect(failureCode({ code: input })).toBe('unknown');
});

it('maps the bridge read codes onto the shared vocabulary', () => {
  expect(failureCode({ code: 'ERR_RIDE_DELETED' })).toBe('rideDeleted');
  for (const code of ['ERR_STORAGE_BUSY', 'ERR_READ_DEFERRED', 'ERR_READ_CONTENTION'])
    expect(failureCode({ code })).toBe('storageBusy');
  expect(failureCode({ code: 'ERR_NUMERIC_OVERFLOW' })).toBe('unknown');
  expect(failureCode(new DOMException('Not found', 'NotFoundError'))).toBe('unknown');
});
