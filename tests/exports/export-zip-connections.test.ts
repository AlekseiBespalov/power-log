import { describe, expect, it } from 'vitest';
import type { ExportConnection, ExportSessionView } from '../../shared/generated/ts/api';
import type { ExportPageReader, ReaderPage } from '../../modules/power-log/src/exports/reader';
import { createSlicer } from '../../modules/power-log/src/exports/slicer';
import { TextChunks, type ByteOutput } from '../../modules/power-log/src/exports/text';
import { telemetryTable } from '../../modules/power-log/src/exports/zip-export';

const identity = (token: string, model: string): ExportConnection => ({
  token,
  vendor: 'controller',
  model,
  firmware: null,
  protocol: null,
});

const page = (tokens: (string | null)[], connections: ExportConnection[], done: boolean): ReaderPage => ({
  rows: tokens.length,
  next: [tokens.length],
  done,
  connections,
  columns: {
    elapsedSeconds: Float64Array.from(tokens, (_, i) => i),
    timestamp: tokens.map(() => '2026-01-01T00:00:00.000Z'),
    connection: tokens,
  },
});

function reader(pages: ReaderPage[]): ExportPageReader {
  return {
    async *pages() {
      yield* pages;
    },
  } as unknown as ExportPageReader;
}

class Collector implements ByteOutput {
  text = '';
  async write(bytes: Uint8Array): Promise<void> {
    this.text += new TextDecoder().decode(bytes);
  }
}

async function table(pages: ReaderPage[], imported = false) {
  const output = new Collector();
  const out = new TextChunks(output);
  const connections = await telemetryTable(reader(pages), {} as ExportSessionView, out, createSlicer(), imported);
  await out.end();
  const lines = output.text.trim().split('\n');
  const column = lines[0]!.split(',').indexOf('connection');
  return { connections, printed: lines.slice(1).map(line => line.split(',')[column]) };
}

describe('the ZIP telemetry table', () => {
  it('numbers connections 1, 2, … by first appearance, whatever their stored session numbers', async () => {
    const { connections, printed } = await table([
      page(['7', null, '7'], [identity('7', 'X6')], false),
      page(['3', '7'], [identity('3', 'X7')], true),
    ]);
    expect(printed).toEqual(['1', '', '1', '2', '1']);
    expect(connections.map(entry => [entry.connection, entry.model])).toEqual([
      [1, 'X6'],
      [2, 'X7'],
    ]);
  });

  it("prints an imported file's connection numbers as the file had them", async () => {
    const { connections, printed } = await table([page(['4', '2'], [], true)], true);
    expect(printed).toEqual(['4', '2']);
    expect(connections).toEqual([]);
  });
});
