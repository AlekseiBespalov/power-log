import ts from 'typescript';

export function normalizeJsModule(raw) {
  const original = ts.createSourceFile('generated.mjs', raw, ts.ScriptTarget.Latest, true, ts.ScriptKind.JS);
  const comments = new Map();
  const visit = node => {
    for (const range of ts.getLeadingCommentRanges(raw, node.pos) ?? []) {
      if (
        range.kind === ts.SyntaxKind.SingleLineCommentTrivia &&
        /^\/\/# sourceMappingURL=/.test(raw.slice(range.pos, range.end))
      )
        comments.set(range.pos, range.end);
    }
    for (const range of ts.getTrailingCommentRanges(raw, node.end) ?? []) {
      if (
        range.kind === ts.SyntaxKind.SingleLineCommentTrivia &&
        /^\/\/# sourceMappingURL=/.test(raw.slice(range.pos, range.end))
      )
        comments.set(range.pos, range.end);
    }
    for (const child of node.getChildren(original)) visit(child);
  };
  visit(original);
  let source = raw;
  for (const [start, end] of [...comments].sort((a, b) => b[0] - a[0]))
    source = source.slice(0, start) + source.slice(end);
  const parsed = ts.createSourceFile('generated.mjs', source, ts.ScriptTarget.Latest, true, ts.ScriptKind.JS);
  if (parsed.parseDiagnostics.length) throw new Error('Invalid generated Kotlin/JS module.');
  const replacements = parsed.statements
    .filter(
      statement =>
        ts.isExportDeclaration(statement) && statement.exportClause && ts.isNamedExports(statement.exportClause),
    )
    .map(statement => ({
      start: statement.exportClause.getStart(parsed),
      end: statement.exportClause.end,
      text: `{\n${statement.exportClause.elements
        .map(element => element.getText(parsed))
        .sort()
        .map(element => `  ${element},\n`)
        .join('')}}`,
    }));
  let normalized = source;
  for (const replacement of replacements.reverse())
    normalized = normalized.slice(0, replacement.start) + replacement.text + normalized.slice(replacement.end);
  return normalized;
}

export function unorderedInterfaces(source) {
  const parsed = ts.createSourceFile('generated.mjs', source, ts.ScriptTarget.Latest, true, ts.ScriptKind.JS);
  const replacements = [];
  const visit = node => {
    if (
      ts.isCallExpression(node) &&
      ts.isIdentifier(node.expression) &&
      node.expression.text.startsWith('initMetadataFor')
    )
      for (const argument of node.arguments)
        if (ts.isArrayLiteralExpression(argument) && argument.elements.every(element => ts.isIdentifier(element)))
          replacements.push({
            start: argument.getStart(parsed),
            end: argument.end,
            text: `[${argument.elements
              .map(element => element.text)
              .sort()
              .join(', ')}]`,
          });
    ts.forEachChild(node, visit);
  };
  visit(parsed);
  let text = source;
  for (const replacement of replacements.sort((a, b) => b.start - a.start))
    text = text.slice(0, replacement.start) + replacement.text + text.slice(replacement.end);
  // A rebuild may also reorder the import lines and change the compiler's mangled member suffixes.
  const lines = text.replace(/_[0-9a-z]{6}_k\$/g, '_k$').split('\n');
  const imports = lines.filter(line => line.startsWith('import ')).sort();
  return lines.map(line => (line.startsWith('import ') ? imports.shift() : line)).join('\n');
}
