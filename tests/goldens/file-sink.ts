import { closeSync, mkdirSync, openSync, readFileSync, rmSync, writeSync } from 'node:fs';
import { dirname } from 'node:path';
import { deflateRawSync } from 'node:zlib';
import { crc32 } from '../../modules/power-log/src/exports/crc32';
import type { DeflateResult, ExportSink, SharedFile } from '../../modules/power-log/src/exports/types';

export class FileSink implements ExportSink {
  private readonly descriptor: number;
  private length = 0;
  private stage: Buffer[] | null = null;

  constructor(private readonly path: string) {
    mkdirSync(dirname(path), { recursive: true });
    this.descriptor = openSync(path, 'w+');
  }

  get file(): Uint8Array {
    closeSync(this.descriptor);
    return new Uint8Array(readFileSync(this.path));
  }

  async write(bytes: Uint8Array): Promise<void> {
    if (this.stage) this.stage.push(Buffer.from(bytes));
    else this.append(bytes);
  }

  async writeAt(offset: number, bytes: Uint8Array): Promise<void> {
    writeSync(this.descriptor, bytes, 0, bytes.length, offset);
  }

  async beginDeflate(): Promise<void> {
    this.stage = [];
  }

  async endDeflate(): Promise<DeflateResult> {
    const input = Buffer.concat(this.stage ?? []);
    this.stage = null;
    const output = deflateRawSync(input);
    this.append(output);
    return { crc32: crc32(input), inputBytes: input.length, outputBytes: output.length };
  }

  async commit(name: string): Promise<SharedFile> {
    return { uri: `file://${this.path}#${name}`, name, mimeType: 'application/octet-stream', sizeBytes: this.length };
  }

  async abort(): Promise<void> {
    closeSync(this.descriptor);
    rmSync(this.path, { force: true });
  }

  private append(bytes: Uint8Array): void {
    writeSync(this.descriptor, bytes, 0, bytes.length, this.length);
    this.length += bytes.length;
  }
}
