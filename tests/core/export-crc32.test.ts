import { crc32 as zlibCrc32 } from 'node:zlib';
import { describe, expect, it } from 'vitest';
import { crc32 } from '../../src/core/export/crc32';

const ascii = (text: string) => new TextEncoder().encode(text);
function pseudoRandom(length: number, seed: number): Uint8Array {
  const bytes = new Uint8Array(length);
  let state = seed >>> 0;
  for (let i = 0; i < length; i++) {
    state ^= state << 13;
    state ^= state >>> 17;
    state ^= state << 5;
    bytes[i] = state & 0xff;
  }
  return bytes;
}

describe('export CRC-32', () => {
  it.each([
    ['', 0x00000000],
    ['a', 0xe8b7be43],
    ['abc', 0x352441c2],
    ['123456789', 0xcbf43926],
    ['message digest', 0x20159d7f],
    ['abcdefghijklmnopqrstuvwxyz', 0x4c2750bd],
    ['The quick brown fox jumps over the lazy dog', 0x414fa339],
  ])('matches the published check value of %j', (text, expected) => {
    expect(crc32(ascii(text))).toBe(expected);
  });

  it('matches the published byte-pattern vectors', () => {
    expect(crc32(new Uint8Array(32))).toBe(0x190a55ad);
    expect(crc32(new Uint8Array(32).fill(0xff))).toBe(0xff6cab0b);
    expect(crc32(Uint8Array.from({ length: 32 }, (_, i) => i))).toBe(0x91267e8a);
    expect(crc32(Uint8Array.from({ length: 256 }, (_, i) => i))).toBe(0x29058c73);
  });

  it('agrees with zlib for every length around the eight-byte blocks and for unaligned views', () => {
    const source = pseudoRandom(4096 + 7, 1);
    for (let length = 0; length <= 64; length++) {
      for (const offset of [0, 1, 3, 7]) {
        const view = source.subarray(offset, offset + length);
        expect(crc32(view), `length ${length} at offset ${offset}`).toBe(zlibCrc32(view));
      }
    }
    expect(crc32(source.subarray(5))).toBe(zlibCrc32(source.subarray(5)));
  });

  it('agrees with zlib on a 64 MiB input', () => {
    const large = pseudoRandom(64 * 1024 * 1024, 2);
    expect(crc32(large)).toBe(zlibCrc32(large));
    expect(crc32(new Uint8Array(1 << 20))).toBe(0xa738ea1c);
  });

  it('continues across arbitrary splits exactly like one pass', () => {
    const bytes = pseudoRandom(1_000_003, 3);
    const whole = zlibCrc32(bytes);
    for (const cuts of [[0], [1], [7, 8, 9], [4095, 4096, 4097], [17, 1000, 65_537, 999_999], [1_000_003]]) {
      let crc = 0;
      let start = 0;
      for (const cut of [...cuts, bytes.length]) {
        crc = crc32(bytes.subarray(start, cut), crc);
        start = cut;
      }
      expect(crc, `cuts ${cuts.join(',')}`).toBe(whole);
    }
    let byByte = 0;
    for (let i = 0; i < 4096; i++) byByte = crc32(bytes.subarray(i, i + 1), byByte);
    expect(byByte).toBe(zlibCrc32(bytes.subarray(0, 4096)));
  });

  it('continues from a previous value as zlib does', () => {
    const head = ascii('Power Log ');
    const tail = ascii('ride data');
    expect(crc32(tail, crc32(head))).toBe(crc32(ascii('Power Log ride data')));
    expect(crc32(tail, 0xdeadbeef)).toBe(zlibCrc32(tail, 0xdeadbeef));
    expect(crc32(new Uint8Array(0), 0x12345678)).toBe(0x12345678);
  });
});
