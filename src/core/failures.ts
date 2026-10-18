import { presentationFailure, presentationFailureCodes } from '../../shared/js';

export type FailurePresentation = { code: string; message: string; nextStep: string; text: string };
export type RideFailureState = { code: string; message: string };

const vocabulary = new Set(presentationFailureCodes());
const bridgeCodes: Readonly<Record<string, string>> = {
  ERR_RIDE_DELETED: 'rideDeleted',
  ERR_STORAGE_BUSY: 'storageBusy',
  ERR_READ_DEFERRED: 'storageBusy',
  ERR_READ_CONTENTION: 'storageBusy',
  ERR_CHART_CAPACITY: 'chartCapacity',
};

export function failurePresentation(code: string | null | undefined): FailurePresentation {
  const failure = presentationFailure(code ?? null);
  return { code: failure.code, message: failure.message, nextStep: failure.nextStep, text: failure.text };
}

export class RideFailureError extends Error {
  readonly code: string;
  constructor(code: string | null | undefined) {
    const failure = presentationFailure(code ?? null);
    super(failure.text);
    this.name = 'RideFailureError';
    this.code = failure.code;
  }
}

export function failureCode(error: unknown): string {
  const code = typeof error === 'object' && error !== null && 'code' in error ? error.code : null;
  if (typeof code !== 'string') return 'unknown';
  if (vocabulary.has(code)) return code;
  return bridgeCodes[code] ?? 'unknown';
}

export function presentFailure(error: unknown): FailurePresentation {
  if (isChartCapacity(error))
    return {
      code: 'chartCapacity',
      message: 'Choose a smaller range with fewer chart discontinuities.',
      nextStep: '',
      text: 'Choose a smaller range with fewer chart discontinuities.',
    };
  return failurePresentation(failureCode(error));
}

export const isChartCapacity = (error: unknown): boolean =>
  (error instanceof Error && error.name === 'InspectionCapacityException') || failureCode(error) === 'chartCapacity';

export function optionalChartCache<T>(build: () => T): T | undefined {
  try {
    return build();
  } catch (error) {
    if (!isChartCapacity(error)) throw error;
    return undefined;
  }
}

export function rideFailure(code: string | null | undefined): RideFailureState | null {
  if (code == null) return null;
  const failure = failurePresentation(code);
  return { code: failure.code, message: failure.text };
}
