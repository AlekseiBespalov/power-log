import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, join, relative, resolve, sep } from 'node:path';
import ts from 'typescript';
import { PROJECTIONS } from '../../src/core/export/catalog';
import { browserLocationMetrics } from '../../src/services/browser-analytics';
import { coreMetricDeclarations } from '../../shared/js';
import storageColumns from '../fixtures/contract/storage/catalog-columns.json';
import { expect, it } from 'vitest';

const paths = [
  'src/core/monitor.ts',
  'src/core/types.ts',
  'src/core/recordings.ts',
  'src/core/export/catalog.ts',
  'src/services/browser-export-source.ts',
  'modules/power-log-bridge/ios/PowerLogStore.swift',
  'modules/power-log-bridge/ios/ExportSource.swift',
  'modules/power-log-bridge/android/src/main/java/app/powerlog/bridge/RideStore.kt',
  'modules/power-log-bridge/android/src/main/java/app/powerlog/bridge/RideRecord.kt',
  'modules/power-log-bridge/android/src/main/java/app/powerlog/bridge/BridgeInputs.kt',
  'modules/power-log-bridge/android/src/main/java/app/powerlog/bridge/ExportSource.kt',
];
const channels = new Set([
  ...Object.values(PROJECTIONS).flatMap(projection => projection.columns.map(column => column.name)),
  ...coreMetricDeclarations().map(metric => metric.id),
]);
const smallerTables = [
  ...Object.values(storageColumns),
  PROJECTIONS.distance.columns.filter(column => column.type === 'number').map(column => column.name),
];
function repeatedNames(names: string[]): boolean {
  const values = new Set(names);
  return (
    names.filter(name => channels.has(name)).length >= 8 ||
    smallerTables.some(table => table.every(name => values.has(name)))
  );
}
const tableBindings = new Set([
  'MONITOR_METRICS',
  'REQUIRED_SAMPLE_COLUMNS',
  'SAMPLE_IDENTITY_COLUMNS',
  'PROJECTIONS',
  'TABLES',
  'CSV_SAMPLE_SHAPE',
]);

function nativeLists(text: string): string[] {
  return [...text.matchAll(/\[|(?:listOf|setOf|mapOf|real|typed)\s*\(/g)].flatMap(match => {
    const start = match.index;
    const stack: string[] = [];
    let quote = false;
    for (let index = start; index < text.length; index++) {
      const character = text[index]!;
      if (character === '\\' && quote) {
        index++;
        continue;
      }
      if (character === '"') quote = !quote;
      if (quote) continue;
      if (character === '[') stack.push(']');
      else if (character === '(') stack.push(')');
      else if (character === stack.at(-1)) {
        stack.pop();
        if (stack.length === 0) return [text.slice(start, index + 1)];
      }
    }
    return [];
  });
}

function copies(path: string, text: string): string[] {
  if (!/\.[cm]?[jt]sx?$/.test(path)) {
    return nativeLists(text).filter(list => repeatedNames([...list.matchAll(/"(\w+)"/g)].map(([, name]) => name!)));
  }
  const file = ts.createSourceFile(path, text, ts.ScriptTarget.Latest, true);
  const found: string[] = [];
  function visit(node: ts.Node) {
    if (ts.isVariableDeclaration(node) && tableBindings.has(node.name.getText(file)) && node.initializer) {
      let value = node.initializer;
      while (ts.isAsExpression(value) || ts.isSatisfiesExpression(value) || ts.isParenthesizedExpression(value))
        value = value.expression;
      if (ts.isArrayLiteralExpression(value) || ts.isObjectLiteralExpression(value)) found.push(value.getText(file));
    }
    if (ts.isArrayLiteralExpression(node)) {
      let context: ts.Node = node;
      while (context.parent && (ts.isAsExpression(context.parent) || ts.isParenthesizedExpression(context.parent)))
        context = context.parent;
      if (context.parent && ts.isForOfStatement(context.parent) && context.parent.expression === context) return;
      const names: string[] = [];
      function namesInArray(child: ts.Node) {
        if (ts.isStringLiteral(child)) {
          const parent = child.parent;
          if (
            !ts.isPropertyAssignment(parent) ||
            (parent.initializer === child &&
              ['id', 'name'].includes(ts.isStringLiteral(parent.name) ? parent.name.text : parent.name.getText(file)))
          )
            names.push(child.text);
        }
        ts.forEachChild(child, namesInArray);
      }
      namesInArray(node);
      if (repeatedNames(names)) found.push(node.getText(file));
    }
    if (ts.isObjectLiteralExpression(node)) {
      const names = node.properties.flatMap(p =>
        ts.isPropertyAssignment(p) ? [ts.isStringLiteral(p.name) ? p.name.text : p.name.getText(file)] : [],
      );
      if (
        repeatedNames(names) &&
        node.properties.every(
          p =>
            ts.isPropertyAssignment(p) &&
            (ts.isArrowFunction(p.initializer) ||
              ts.isFunctionExpression(p.initializer) ||
              ts.isLiteralExpression(p.initializer) ||
              ts.isArrayLiteralExpression(p.initializer) ||
              ts.isObjectLiteralExpression(p.initializer)),
        )
      )
        found.push(node.getText(file));
    }
    ts.forEachChild(node, visit);
  }
  visit(file);
  return found;
}

const compilerOptions = ts.parseJsonConfigFileContent(
  ts.readConfigFile('tsconfig.json', ts.sys.readFile).config,
  ts.sys,
  process.cwd(),
).options;

function consumerCopies(roots: string[] = paths): Record<string, string[]> {
  const pending = roots.map(path => resolve(path));
  const visited = new Set<string>();
  const found: Record<string, string[]> = {};
  while (pending.length) {
    const path = pending.pop()!;
    if (visited.has(path)) continue;
    visited.add(path);
    const text = readFileSync(path, 'utf8');
    const tables = copies(path, text);
    if (tables.length) found[path] = tables;
    if (!/\.[cm]?[jt]sx?$/.test(path)) continue;
    const file = ts.createSourceFile(path, text, ts.ScriptTarget.Latest, true);
    function follow(node: ts.Node) {
      const specifier =
        ts.isImportDeclaration(node) || ts.isExportDeclaration(node)
          ? node.moduleSpecifier
          : ts.isCallExpression(node) && node.expression.kind === ts.SyntaxKind.ImportKeyword
            ? node.arguments[0]
            : undefined;
      if (specifier && ts.isStringLiteral(specifier)) {
        const module = ts.resolveModuleName(specifier.text, path, compilerOptions, ts.sys).resolvedModule;
        if (
          module &&
          !module.isExternalLibraryImport &&
          !module.resolvedFileName.startsWith(resolve('shared/js') + sep) &&
          !module.resolvedFileName.endsWith('.d.ts')
        )
          pending.push(module.resolvedFileName);
      }
      ts.forEachChild(node, follow);
    }
    follow(file);
  }
  return found;
}

it.each(['declarations.ts', 'shared/declarations.ts', 'shared/declarations.js', 'aliased/declarations.ts'])(
  'finds a handwritten table moved into the newly imported %s module',
  file => {
    mkdirSync('test-results/catalog-authority', { recursive: true });
    const root = mkdtempSync(resolve('test-results/catalog-authority/module-'));
    try {
      const consumer = join(root, 'consumer.ts');
      const table = join(root, file);
      mkdirSync(dirname(table), { recursive: true });
      const specifier = file.startsWith('aliased/')
        ? `@/../${relative(process.cwd(), table).replace(/\.[jt]s$/, '')}`
        : `./${file.replace(/\.[jt]s$/, '')}`;
      writeFileSync(consumer, `import { fields } from '${specifier}';\nexport const count = fields.length;\n`);
      writeFileSync(table, `export const fields = ${JSON.stringify([...channels].slice(0, 8))};\n`);
      expect(Object.keys(consumerCopies([consumer]))).toContain(table);
    } finally {
      rmSync(root, { recursive: true });
    }
  },
);
it('retains the browser canonical location query fields from the core table', () => {
  expect([...browserLocationMetrics].sort()).toEqual(
    storageColumns.appleLocation.filter(name => !name.startsWith('original')).sort(),
  );
});
it('does not retain the unused AnyTableDefinition export', () => {
  expect(readFileSync('src/core/export/types.ts', 'utf8')).not.toMatch(/export type AnyTableDefinition\b/);
});

it.each(paths)('%s consumes catalog tables without handwritten channel copies', path => {
  expect(consumerCopies([path]), path).toEqual({});
});
it('rejects reintroduced arrays, monitor declarations, CSV shapes and browser accessors', () => {
  const names = [...channels].slice(0, 8);
  for (const text of [
    `const fields = ${JSON.stringify(names)};`,
    "const SAMPLE_IDENTITY_COLUMNS = ['controllerModel', 'firmwareLabel', 'controllerProtocol'] as const;",
    `const metrics = ${JSON.stringify(names.map(id => ({ id })))};`,
    `const sample = ${JSON.stringify(Object.fromEntries(names.map(name => [name, 0])))};`,
    `const accessors = { ${names.map(name => `${name}: row => row.${name}`).join(',')} };`,
  ])
    expect(copies('adapter.ts', text).length).toBeGreaterThan(0);
  for (const table of smallerTables) {
    expect(copies('adapter.ts', `const fields = ${JSON.stringify(table)};`).length).toBeGreaterThan(0);
    expect(
      copies('adapter.ts', `const fields = [${names.map(name => `[${JSON.stringify(name)}, 0]`).join(',')}];`).length,
    ).toBeGreaterThan(0);
    expect(
      copies('Adapter.kt', `listOf(${names.map(name => `Column(${JSON.stringify(name)})`).join(',')})`).length,
    ).toBeGreaterThan(0);
    expect(
      copies('Adapter.swift', `[${names.map(name => `Column(name: ${JSON.stringify(name)})`).join(',')}]`).length,
    ).toBeGreaterThan(0);
    expect(copies('Adapter.kt', `listOf(${table.map(name => JSON.stringify(name)).join(',')})`)).toHaveLength(1);
  }
  expect(copies('Adapter.kt', `listOf(${names.map(name => JSON.stringify(name)).join(',')})`)).toHaveLength(1);
});
