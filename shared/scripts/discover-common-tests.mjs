import { readFileSync, readdirSync } from 'node:fs';
import { resolve } from 'node:path';

export const commonTestRoot = resolve(import.meta.dirname, '../src/commonTest');

function tokenize(source, file) {
  const tokens = [];
  let offset = 0;
  let line = 1;
  const fail = message => {
    throw new Error(`${file}:${line}: ${message}`);
  };
  const advance = end => {
    line += source.slice(offset, end).split('\n').length - 1;
    offset = end;
  };
  while (offset < source.length) {
    const rest = source.slice(offset);
    const whitespace = /^\s+/.exec(rest);
    if (whitespace) {
      advance(offset + whitespace[0].length);
      continue;
    }
    if (rest.startsWith('//')) {
      const end = source.indexOf('\n', offset);
      advance(end < 0 ? source.length : end);
      continue;
    }
    if (rest.startsWith('/*')) {
      let depth = 1;
      let end = offset + 2;
      while (depth && end < source.length) {
        if (source.startsWith('/*', end)) {
          depth++;
          end += 2;
        } else if (source.startsWith('*/', end)) {
          depth--;
          end += 2;
        } else end++;
      }
      if (depth) fail('Unterminated Kotlin comment');
      advance(end);
      continue;
    }
    const startLine = line;
    if (rest.startsWith('"""')) {
      const end = source.indexOf('"""', offset + 3);
      if (end < 0) fail('Unterminated Kotlin raw string');
      tokens.push({ value: source.slice(offset, end + 3), kind: 'string', line: startLine });
      advance(end + 3);
      continue;
    }
    const quote = source[offset];
    if (quote === '"' || quote === "'" || quote === '`') {
      let end = offset + 1;
      while (end < source.length && source[end] !== quote) {
        if (source[end] === '\n') fail('Unterminated Kotlin literal');
        end += source[end] === '\\' && quote !== '`' ? 2 : 1;
      }
      if (end >= source.length) fail('Unterminated Kotlin literal');
      tokens.push({
        value: quote === '`' ? source.slice(offset + 1, end) : source.slice(offset, end + 1),
        kind: quote === '`' ? 'identifier' : 'string',
        line: startLine,
      });
      advance(end + 1);
      continue;
    }
    const identifier = /^[\p{L}_][\p{L}\p{N}_]*/u.exec(rest);
    const value = identifier?.[0] ?? source[offset];
    tokens.push({ value, kind: identifier ? 'word' : 'symbol', line: startLine });
    advance(offset + value.length);
  }
  return tokens;
}

function discoverFile(source, file) {
  const tokens = tokenize(source, file);
  const fail = (token, message) => {
    throw new Error(`${file}:${token?.line ?? 1}: ${message}`);
  };
  const pairs = new Map();
  const stack = [];
  for (const [index, token] of tokens.entries()) {
    if (token.kind !== 'symbol') continue;
    if ('([{'.includes(token.value)) stack.push(index);
    if (')]}'.includes(token.value)) {
      const opening = stack.pop();
      if (tokens[opening]?.value !== { ')': '(', ']': '[', '}': '{' }[token.value])
        fail(token, 'Unbalanced Kotlin delimiters');
      pairs.set(opening, index);
    }
  }
  if (stack.length) fail(tokens[stack.at(-1)], 'Unbalanced Kotlin delimiters');
  const qualifiedName = start => {
    const parts = [];
    let end = start;
    while (tokens[end]?.kind === 'word' || tokens[end]?.kind === 'identifier' || tokens[end]?.value === '*') {
      parts.push(tokens[end++].value);
      if (tokens[end]?.value !== '.') break;
      end++;
    }
    return { name: parts.join('.'), end };
  };
  let packageName = '';
  const imports = new Map();
  let wildcard = false;
  for (let index = 0; index < tokens.length; index++) {
    const token = tokens[index];
    if (token.kind !== 'word' || !['package', 'import'].includes(token.value)) continue;
    const { name, end } = qualifiedName(index + 1);
    if (token.value === 'package') packageName = name;
    else {
      const alias = tokens[end]?.value === 'as' ? tokens[end + 1]?.value : name.split('.').at(-1);
      imports.set(alias, name);
      if (name === 'kotlin.test.*') wildcard = true;
    }
  }
  const annotation = start => {
    let index = start + 1;
    let useSite;
    if (tokens[index + 1]?.value === ':') {
      useSite = tokens[index].value;
      index += 2;
    }
    const { name, end } = qualifiedName(index);
    if (!name) fail(tokens[start], 'Unsupported Kotlin annotation');
    index = end;
    let args = [];
    if (tokens[index]?.value === '(') {
      args = tokens.slice(index + 1, pairs.get(index));
      index = pairs.get(index) + 1;
    }
    const canonical =
      imports.get(name) ?? (wildcard && ['Test', 'Ignore'].includes(name) ? `kotlin.test.${name}` : name);
    if (name.split('.').at(-1) === 'Test' && canonical !== 'kotlin.test.Test')
      fail(tokens[start], `Unresolved test annotation ${name}`);
    if (canonical === 'kotlin.test.Test' && (useSite || args.length))
      fail(tokens[start], 'Unsupported @Test annotation');
    return { name: canonical, useSite, end: index };
  };
  const modifiers = new Set([
    'public',
    'internal',
    'private',
    'protected',
    'open',
    'final',
    'abstract',
    'override',
    'inner',
    'data',
    'sealed',
    'enum',
    'annotation',
    'expect',
    'actual',
    'suspend',
    'inline',
    'tailrec',
    'operator',
    'infix',
  ]);
  const bodies = new Map();
  const scopes = [];
  const found = [];
  let annotations = [];
  let declarationModifiers = [];
  const requireConsumed = token => {
    if (annotations.some(item => item.name === 'kotlin.test.Test'))
      fail(token, 'Cannot parse @Test function declaration');
    annotations = [];
    declarationModifiers = [];
  };
  for (let index = 0; index < tokens.length; index++) {
    const token = tokens[index];
    if (token.value === '@' && token.kind === 'symbol') {
      const item = annotation(index);
      index = item.end - 1;
      if (item.name === 'kotlin.test.Ignore') fail(token, '@Ignore cannot waive failed or skipped tests');
      if (item.useSite === 'file') continue;
      annotations.push(item);
      continue;
    }
    if (token.kind === 'word' && modifiers.has(token.value)) {
      declarationModifiers.push(token.value);
      continue;
    }
    if (
      token.kind === 'word' &&
      ['class', 'interface', 'object'].includes(token.value) &&
      tokens[index - 1]?.value !== ':'
    ) {
      if (annotations.some(item => item.name === 'kotlin.test.Test')) fail(token, '@Test must annotate a function');
      const name = tokens[index + 1];
      if (name?.kind === 'word' || name?.kind === 'identifier') {
        let body = index + 2;
        while (
          body < tokens.length &&
          !['{', '}', ';', '@', 'class', 'interface', 'object', 'fun', 'val', 'var', 'typealias'].includes(
            tokens[body].value,
          )
        ) {
          if (['(', '['].includes(tokens[body].value)) body = pairs.get(body);
          body++;
        }
        if (tokens[body]?.value === '{') {
          bodies.set(body, {
            name: name.value,
            unsupported:
              token.value !== 'class' ||
              declarationModifiers.some(value => ['inner', 'abstract', 'expect'].includes(value)),
          });
        }
      }
      annotations = [];
      declarationModifiers = [];
      continue;
    }
    if (token.kind === 'word' && token.value === 'fun' && annotations.some(item => item.name === 'kotlin.test.Test')) {
      const name = tokens[index + 1];
      const opening = index + 2;
      if (
        !name ||
        !['word', 'identifier'].includes(name.kind) ||
        tokens[opening]?.value !== '(' ||
        pairs.get(opening) !== opening + 1
      )
        fail(token, 'Unsupported @Test signature; expected a named function with no parameters');
      let body = opening + 2;
      if (tokens[body]?.value === ':') {
        const result = qualifiedName(body + 1);
        if (!['Unit', 'kotlin.Unit'].includes(result.name)) fail(token, '@Test must return Unit');
        body = result.end;
      }
      if (!['{', '='].includes(tokens[body]?.value)) fail(token, 'Cannot parse @Test function body');
      if (!scopes.length || scopes.some(scope => !scope || scope.unsupported))
        fail(token, '@Test must belong directly to concrete named classes');
      if (declarationModifiers.some(value => !['public', 'internal', 'final'].includes(value)))
        fail(token, 'Unsupported @Test function modifiers');
      if (annotations.filter(item => item.name === 'kotlin.test.Test').length !== 1)
        fail(token, 'Duplicate @Test annotation');
      const classname = [packageName, ...scopes.map(scope => scope.name)].filter(Boolean).join('.');
      found.push({ identity: `${classname}#${name.value}`, file, line: name.line });
      annotations = [];
      declarationModifiers = [];
      index = opening + 1;
      continue;
    }
    requireConsumed(token);
    if (token.kind === 'symbol' && token.value === '{') scopes.push(bodies.get(index));
    if (token.kind === 'symbol' && token.value === '}') scopes.pop();
  }
  requireConsumed(tokens.at(-1));
  return found;
}

export function discoverCommonTests(root = commonTestRoot) {
  const tests = readdirSync(root, { recursive: true })
    .filter(file => file.endsWith('.kt'))
    .sort()
    .flatMap(file => discoverFile(readFileSync(resolve(root, file), 'utf8'), resolve(root, file)));
  const identities = new Set();
  for (const row of tests) {
    if (identities.has(row.identity))
      throw new Error(`${row.file}:${row.line}: Duplicate declared test ${row.identity}`);
    identities.add(row.identity);
  }
  if (!tests.length) throw new Error(`${root}: No common tests discovered`);
  return tests;
}
