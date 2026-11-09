import nativeModule from './native-module';
import type { Transport } from './transport';

const missing = (): Promise<never> => Promise.reject(new Error('The PowerLog native module is missing.'));

export function platformTransport(): Transport {
  const powerLog = nativeModule;
  if (!powerLog) return { call: missing, next: missing, exportPage: missing };
  return {
    call: async (op, request) => powerLog.call(op, request),
    next: async (stream, request) => powerLog.next(stream, request),
    exportPage: async request => powerLog.exportPage(request),
  };
}
