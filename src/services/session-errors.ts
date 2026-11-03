import { presentFailure } from '../core/failures';

export type SessionErrors = {
  operation: string | null;
  operationCode: string | null;
  native: string | null;
  nativeCode: string | null;
  nativeDismissed: boolean;
};

export type SessionErrorAction =
  | { type: 'native'; error: string | null | undefined; code?: string | null }
  | { type: 'operation'; error: unknown; platform?: string }
  | { type: 'dismiss' };

export type SessionError = { message: string; code: string | null };

export const initialSessionErrors: SessionErrors = {
  operation: null,
  operationCode: null,
  native: null,
  nativeCode: null,
  nativeDismissed: false,
};

export function sessionErrorReducer(current: SessionErrors, action: SessionErrorAction): SessionErrors {
  switch (action.type) {
    case 'native': {
      const error = action.error || null;
      // Repeated native state snapshots belong to one occurrence. Clearing or changing
      // its message starts a new occurrence without erasing a failed export/action.
      return error === current.native
        ? current
        : {
            ...current,
            native: error,
            nativeCode: error === null ? null : (action.code ?? null),
            nativeDismissed: false,
          };
    }
    case 'operation': {
      if (action.error == null) return { ...current, operation: null, operationCode: null };
      const failure = presentFailure(action.error, action.platform);
      return { ...current, operation: failure.text, operationCode: failure.code };
    }
    case 'dismiss':
      return { ...current, operation: null, operationCode: null, nativeDismissed: current.native !== null };
  }
}

export function sessionError(errors: SessionErrors, options: { deferNative?: boolean } = {}): SessionError | null {
  if (errors.operation !== null) return { message: errors.operation, code: errors.operationCode };
  if (errors.native === null || errors.nativeDismissed || options.deferNative) return null;
  return { message: errors.native, code: errors.nativeCode };
}
