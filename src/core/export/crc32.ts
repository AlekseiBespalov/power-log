// Slicing-by-8 over the reflected IEEE polynomial; TABLE holds eight 256-entry tables back to back.
const TABLE = new Int32Array(8 * 256);
for (let n = 0; n < 256; n++) {
  let c = n;
  for (let bit = 0; bit < 8; bit++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
  TABLE[n] = c;
}
for (let n = 0; n < 256; n++) {
  let c = TABLE[n]!;
  for (let table = 1; table < 8; table++) {
    c = TABLE[c & 0xff]! ^ (c >>> 8);
    TABLE[table * 256 + n] = c;
  }
}

// `previous` is the CRC of the preceding bytes, as in zlib's crc32(crc, buf).
export function crc32(bytes: Uint8Array, previous = 0): number {
  let crc = ~previous;
  const end = bytes.length;
  const blocks = end - (end % 8);
  let i = 0;
  for (; i < blocks; i += 8) {
    crc ^= bytes[i]! | (bytes[i + 1]! << 8) | (bytes[i + 2]! << 16) | (bytes[i + 3]! << 24);
    crc =
      TABLE[1792 + (crc & 0xff)]! ^
      TABLE[1536 + ((crc >>> 8) & 0xff)]! ^
      TABLE[1280 + ((crc >>> 16) & 0xff)]! ^
      TABLE[1024 + (crc >>> 24)]! ^
      TABLE[768 + bytes[i + 4]!]! ^
      TABLE[512 + bytes[i + 5]!]! ^
      TABLE[256 + bytes[i + 6]!]! ^
      TABLE[bytes[i + 7]!]!;
  }
  for (; i < end; i++) crc = TABLE[(crc ^ bytes[i]!) & 0xff]! ^ (crc >>> 8);
  return ~crc >>> 0;
}
