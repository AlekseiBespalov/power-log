import { readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

type Json = null | boolean | number | string | Json[] | { [key: string]: Json };
type JsonObject = { [key: string]: Json };
const object = (value: Json): value is JsonObject =>
  value !== null && typeof value === 'object' && !Array.isArray(value);
const identity = (value: Json): string =>
  JSON.stringify(value, (_, child) =>
    object(child) ? Object.fromEntries(Object.entries(child).sort(([a], [b]) => a.localeCompare(b))) : child,
  );

// These are literal substitutions, not inheritance: shared values contain complete captured objects.
export function readNativeFixture(path: string): unknown {
  const { shared = {}, ...cases } = JSON.parse(readFileSync(path, 'utf8')) as JsonObject;
  if (!object(shared)) throw new Error(`Invalid shared captures in ${path}`);
  const definitions = shared;
  function expand(value: Json): Json {
    if (Array.isArray(value)) return value.map(expand);
    if (!object(value)) return value;
    if ('$ref' in value) {
      if (Object.keys(value).length !== 1 || typeof value.$ref !== 'string' || !(value.$ref in definitions))
        throw new Error(`Invalid captured reference in ${path}: ${JSON.stringify(value)}`);
      return structuredClone(definitions[value.$ref]!);
    }
    return Object.fromEntries(Object.entries(value).map(([key, child]) => [key, expand(child)]));
  }
  return expand(cases) as JsonObject;
}

export function compactNativeFixture(cases: JsonObject): JsonObject {
  const counts = new Map<string, number>();
  function count(value: Json) {
    if (object(value)) {
      const key = identity(value);
      counts.set(key, (counts.get(key) ?? 0) + 1);
    }
    if (value && typeof value === 'object') Object.values(value).forEach(count);
  }
  count(cases);
  const shared: JsonObject = {};
  const names = new Map<string, string>();
  const suffixes = new Map<string, number>();
  function compact(value: Json, label: string): Json {
    if (Array.isArray(value)) return value.map(child => compact(child, label));
    if (!object(value)) return value;
    const key = identity(value);
    if (counts.get(key)! > 1 && JSON.stringify(value, null, 2).split('\n').length >= 12) {
      let name = names.get(key);
      if (!name) {
        const suffix = (suffixes.get(label) ?? 0) + 1;
        suffixes.set(label, suffix);
        name = `${label}${suffix}`;
        names.set(key, name);
        shared[name] = value;
      }
      return { $ref: name };
    }
    return Object.fromEntries(Object.entries(value).map(([key, child]) => [key, compact(child, key)]));
  }
  const compacted = compact(cases, 'capture') as JsonObject;
  return { shared, ...compacted };
}

// Native fixture producers use the same reader/factoring operation as the TypeScript consumers.
if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const [, , command, path] = process.argv;
  if (!path || !['read', 'write'].includes(command!)) throw new Error('Usage: native.ts read|write fixture.json');
  if (command === 'write') {
    const cases = JSON.parse(readFileSync(0, 'utf8')) as JsonObject;
    writeFileSync(path, JSON.stringify(compactNativeFixture(cases), null, 2) + '\n');
  }
  process.stdout.write(JSON.stringify(readNativeFixture(path)));
}
