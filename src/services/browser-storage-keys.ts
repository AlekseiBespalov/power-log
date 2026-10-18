const BIAS = 1n << 63n;
const MAX = BIAS - 1n;

export function browserKeyRange(
  lower: IDBValidKey,
  upper: IDBValidKey,
  lowerOpen = false,
  upperOpen = false,
): IDBKeyRange | undefined {
  const order = indexedDB.cmp(lower, upper);
  if (order > 0 || (order === 0 && (lowerOpen || upperOpen))) return;
  if (order === 0) return IDBKeyRange.only(lower);
  return IDBKeyRange.bound(lower, upper, lowerOpen, upperOpen);
}

export function int64(value: string): bigint {
  if (typeof value !== 'string' || !/^(0|-?[1-9]\d*)$/.test(value)) throw new Error('Invalid canonical Int64');
  const parsed = BigInt(value);
  if (parsed < -BIAS || parsed > MAX) throw new Error('Int64 overflow');
  return parsed;
}
// IndexedDB number keys are binary64, so an Int64 key is a biased, zero-padded decimal that sorts as text.
export function browserIndexKey(value: string): string {
  return (int64(value) + BIAS).toString().padStart(20, '0');
}
export function browserIndexValue(key: string): string {
  if (!/^\d{20}$/.test(key)) throw new Error('Invalid IndexedDB order key');
  const value = (BigInt(key) - BIAS).toString();
  int64(value);
  return value;
}
export function secondsUs(seconds: number): string {
  if (!Number.isFinite(seconds)) throw new Error('Invalid observation time');
  const value = BigInt(Math.floor(seconds * 1_000_000)).toString();
  int64(value);
  return value;
}
export function timestampUs(timestamp: string): string {
  const match = /^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.(\d{1,9}))?Z$/.exec(timestamp);
  if (!match) throw new Error('Invalid exact UTC timestamp');
  const milliseconds = Date.parse(`${match[1]}Z`);
  if (!Number.isFinite(milliseconds) || new Date(milliseconds).toISOString().slice(0, 19) !== match[1])
    throw new Error('Invalid exact UTC timestamp');
  return (BigInt(milliseconds) * 1000n + BigInt((match[2] ?? '').padEnd(6, '0').slice(0, 6))).toString();
}
export function secondsKey(seconds: number): string {
  if (seconds === Infinity) return browserIndexKey(MAX.toString());
  if (seconds === -Infinity) return browserIndexKey((-BIAS).toString());
  return browserIndexKey(secondsUs(seconds));
}
