import { NativeModule, requireOptionalNativeModule } from 'expo-modules-core';
import type { BinaryPage } from './transport';

export type FileKind = 'fit' | 'csv' | 'zip' | 'json';

export type SharedFile = { uri: string; name: string; mimeType: string; sizeBytes: number };

export interface ExportByteFunctions {
  sinkOpen(kind: FileKind): Promise<string>;
  sinkWrite(id: string, bytes: Uint8Array): Promise<void>;
  sinkWriteAt(id: string, offset: number, bytes: Uint8Array): Promise<void>;
  sinkBeginDeflate(id: string): Promise<void>;
  sinkEndDeflate(id: string): Promise<{ crc32: number; inputBytes: number; outputBytes: number }>;
  sinkCommit(id: string, name: string): Promise<SharedFile>;
  sinkAbort(id: string): Promise<void>;
  sinkPurge(kind: FileKind): Promise<void>;
  share(file: SharedFile): Promise<'shared' | 'dismissed'>;
}

declare class PowerLogNativeModule extends NativeModule<Record<never, never>> {
  call(op: string, request: string): Promise<string>;
  next(stream: string, request: string): Promise<string>;
  exportPage(request: string): Promise<BinaryPage>;
}

export default requireOptionalNativeModule<PowerLogNativeModule & ExportByteFunctions>('PowerLog');
