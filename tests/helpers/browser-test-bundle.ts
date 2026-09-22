import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import ts from 'typescript';

/** Bundle only local test dependencies; no browser test hook enters the app. */
export function browserTestBundle(entry: string, globalName: string, aliases: Record<string, string> = {}, options: { resolvePackages?: boolean } = {}): string {
  const modules = new Map<string, string>();
  function resolve(file: string) {
    const candidate = [file, `${file}.ts`, `${file}.tsx`, `${file}.js`, path.join(file, 'index.js')].find(value => fs.existsSync(value) && fs.statSync(value).isFile());
    if (!candidate) throw new Error(`Browser test module not found: ${file}`);
    return path.resolve(candidate);
  }
  function visit(file: string) {
    file = resolve(file); if (modules.has(file)) return file;
    modules.set(file, '');
    const contents = fs.readFileSync(file, 'utf8');
    const compiled = file.endsWith('.json') ? `module.exports=${contents};` : ts.transpileModule(contents, { fileName: file, compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022, jsx: ts.JsxEmit.ReactJSX } }).outputText;
    const source = compiled.replace(/require\((["'])([^"'\n]+)\1\)/g, (_match, _quote: string, name: string) => {
      const requested = name.startsWith('.') ? path.resolve(path.dirname(file), name) : name;
      const target = aliases[requested] ?? aliases[name] ?? (name.startsWith('.') ? requested : options.resolvePackages ? createRequire(file).resolve(name) : undefined);
      if (!target) throw new Error(`Unexpected browser test dependency: ${name}`);
      return `require(${JSON.stringify(visit(target))})`;
    });
    modules.set(file, source); return file;
  }
  const id = visit(entry);
  return `(function(){const process={env:{NODE_ENV:'production'}};const modules={${[...modules].map(([key, source]) => `${JSON.stringify(key)}:function(module,exports,require){${source}\n}`).join(',')}};const cache={};function require(id){if(cache[id])return cache[id].exports;const module=cache[id]={exports:{}};modules[id](module,module.exports,require);return module.exports;}window[${JSON.stringify(globalName)}]=require(${JSON.stringify(id)});})();`;
}
