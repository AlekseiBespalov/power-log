import { exportFit } from '../../src/core/export/fit-export';
import { exportZip } from '../../src/core/export/zip-export';
import { fromNativePage } from '../../src/core/export/pages';
import type {
  ExportSource,
  ExportSink,
  NativeExportPage,
  ExportPageRequest,
  ExportPage,
  ProjectionName,
} from '../../src/core/export/types';

declare global {
  interface Window {
    webkit: { messageHandlers: { crash: { postMessage(value: unknown): void } } };
    nativeAppleCrashExport: { call(id: number, operation: string, payload: string): void; done(result: string): void };
  }
}
let sequence = 0;
const pending = new Map<number, { resolve(value: unknown): void; reject(error: Error): void }>();
function call<T>(operation: string, payload: unknown): Promise<T> {
  return new Promise((resolve, reject) => {
    const id = ++sequence;
    pending.set(id, { resolve: value => resolve(value as T), reject });
    window.nativeAppleCrashExport.call(id, operation, JSON.stringify(payload));
  });
}
export function reply(id: number, result: unknown, error: string | null) {
  const promise = pending.get(id)!;
  pending.delete(id);
  if (error) promise.reject(new Error(error));
  else promise.resolve(result);
}
const base64 = (bytes: Uint8Array) => btoa(Array.from(bytes, byte => String.fromCharCode(byte)).join(''));
export async function run(rideId: string) {
  window.nativeAppleCrashExport = {
    call: (id, operation, payload) => window.webkit.messageHandlers.crash.postMessage({ id, operation, payload }),
    done: result => window.webkit.messageHandlers.crash.postMessage({ operation: 'done', payload: result }),
  };
  try {
    const source: ExportSource = {
      open: request => call('open', request),
      async page<P extends ProjectionName>(request: ExportPageRequest<P>): Promise<ExportPage<P>> {
        const native = await call<NativeExportPage<P>>('page', request);
        const columns = Object.fromEntries(
          Object.entries(native.columns).map(([name, value]) => [
            name,
            Array.isArray(value)
              ? value
              : Uint8Array.from(atob((value as unknown as { base64: string }).base64), byte => byte.charCodeAt(0)),
          ]),
        );
        return fromNativePage({ ...native, columns } as NativeExportPage<P>, request.projection);
      },
      close: session => call('close', { session }),
    };
    const files: Record<string, string> = {};
    for (const kind of ['fit', 'zip'] as const) {
      const context = { platform: 'ios' as const, exportedAt: '2026-01-01T00:00:12.000Z' };
      const factory = async (): Promise<ExportSink> => {
        const id = await call<string>('sinkOpen', { kind, context });
        return {
          write: bytes => call('write', { id, bytes: base64(bytes) }),
          writeAt: (offset, bytes) => call('writeAt', { id, offset, bytes: base64(bytes) }),
          beginDeflate: () => call('beginDeflate', { id }),
          endDeflate: () => call('endDeflate', { id }),
          commit: name => call('commit', { id, name }),
          abort: () => call('abort', { id }),
        };
      };
      const request = { rideId, context };
      files[kind] = (
        kind === 'fit' ? await exportFit(source, factory, request) : await exportZip(source, factory, request)
      ).uri;
    }
    window.nativeAppleCrashExport.done(JSON.stringify({ files }));
  } catch (error) {
    window.nativeAppleCrashExport.done(JSON.stringify({ error: String(error) }));
  }
}
