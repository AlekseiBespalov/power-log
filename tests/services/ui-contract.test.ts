import { readdirSync, readFileSync } from 'node:fs';
import path from 'node:path';
import { ESLint } from 'eslint';
import ts from 'typescript';
import { expect, it } from 'vitest';

function sourceFiles(directory: string): string[] {
  return readdirSync(directory, { withFileTypes: true }).flatMap(entry => {
    const file = path.join(directory, entry.name);
    return entry.isDirectory() ? sourceFiles(file) : /\.tsx?$/.test(file) ? [file] : [];
  });
}

it('rejects numeric font sizes through the production ESLint config, including SVG and expressions', async () => {
  const eslint = new ESLint();
  const result = await eslint.lintText(
    'const styles = { fontSize: 14 }; const choice = { fontSize: large ? 12 : 18 }; const label = <Text fontSize={10} />;',
    { filePath: 'src/components/font-rule-probe.tsx' },
  );
  expect(result[0]!.messages.filter(message => message.ruleId === 'ui/no-numeric-font-size')).toHaveLength(4);
  const allowed = await eslint.lintText('export const type = { body: 14 }; const style = { fontSize: 14 };', {
    filePath: 'src/components/ui.tsx',
  });
  expect(allowed[0]!.messages.filter(message => message.ruleId === 'ui/no-numeric-font-size')).toEqual([]);
});

it('routes every source pressable through the shared minimum target', () => {
  const violations: string[] = [];
  for (const file of sourceFiles('src')) {
    if (file === 'src/components/ui.tsx') continue;
    const source = ts.createSourceFile(
      file,
      readFileSync(file, 'utf8'),
      ts.ScriptTarget.Latest,
      true,
      ts.ScriptKind.TSX,
    );
    for (const statement of source.statements) {
      if (
        !ts.isImportDeclaration(statement) ||
        !ts.isStringLiteral(statement.moduleSpecifier) ||
        statement.moduleSpecifier.text !== 'react-native'
      )
        continue;
      const bindings = statement.importClause?.namedBindings;
      if (bindings && ts.isNamedImports(bindings))
        for (const binding of bindings.elements)
          if (
            ['Pressable', 'TouchableOpacity', 'TouchableHighlight', 'TouchableWithoutFeedback'].includes(
              (binding.propertyName ?? binding.name).text,
            )
          )
            violations.push(file);
    }
  }
  expect(violations).toEqual([]);
});
