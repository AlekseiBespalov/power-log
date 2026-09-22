export type SessionErrors = {
  operation: string | null;
  native: string | null;
  nativeDismissed: boolean;
};

export type SessionErrorAction =
  | { type: 'native'; error: string | null | undefined }
  | { type: 'operation'; error: string | null }
  | { type: 'dismiss' };

export const initialSessionErrors: SessionErrors = { operation: null, native: null, nativeDismissed: false };

export function sessionErrorReducer(current: SessionErrors, action: SessionErrorAction): SessionErrors {
  switch (action.type) {
    case 'native': {
      const error = action.error || null;
      // Repeated native state snapshots belong to one occurrence. Clearing or changing
      // its message starts a new occurrence without erasing a failed export/action.
      return error === current.native ? current : { ...current, native: error, nativeDismissed: false };
    }
    case 'operation': return { ...current, operation: action.error };
    case 'dismiss': return { ...current, operation: null, nativeDismissed: current.native !== null };
  }
}

export function sessionErrorMessage(errors: SessionErrors, options: { deferNative?: boolean } = {}): string | null {
  return errors.operation ?? (errors.nativeDismissed || options.deferNative ? null : errors.native);
}
