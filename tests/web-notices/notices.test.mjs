import assert from 'node:assert/strict';
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import test from 'node:test';
import { generateWebNotices, removeSourceMaps } from '../../scripts/web-notices.mjs';

function fixture(t) {
  const root = mkdtempSync(join(tmpdir(), 'power-log-notices-test-'));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  const output = join(root, 'export');
  const sources = [], contents = [];
  const write = (file, value) => {
    mkdirSync(dirname(join(root, file)), { recursive: true });
    writeFileSync(join(root, file), typeof value === 'string' ? value : JSON.stringify(value));
  };
  const add = (path, { version = '1.0.0', license = 'MIT', licenseFile = 'Copyright Example author\nExample licence terms', header = '', file = 'index.js' } = {}) => {
    write(`${path}/package.json`, { name: path.split('node_modules/').at(-1), version, license });
    if (licenseFile) write(`${path}/LICENSE`, licenseFile);
    sources.push(`/${path}/${file}`); contents.push(`${header}\nexport default 1;`);
  };
  const finish = () => write('export/bundle.js.map', { sources, sourcesContent: contents });
  write('LICENSE', 'Power Log Apache-2.0 licence');
  return { root, output, write, add, finish };
}

test('inventory covers nested versions, assets and embedded notices, and omits packages outside the bundle', t => {
  const { root, output, write, add, finish } = fixture(t);
  add('node_modules/lib', { header: '/* Copyright Embedded Author <example> */' });
  add('node_modules/parent/node_modules/lib', { version: '2.0.0', license: 'BSD-3-Clause' });
  finish();
  write('node_modules/build-only/package.json', { name: 'build-only', version: '1.0.0', license: 'MPL-2.0' });
  write('node_modules/icons/package.json', { name: 'icons', version: '1.0.0', license: 'MIT' });
  write('node_modules/icons/LICENSE', 'Copyright Icon Author\nMIT terms');
  write('export/assets/node_modules/icons/icon.123.png', 'image');
  const { notices, packageCount, missingLicenseFile } = generateWebNotices(root, output);
  assert.equal(packageCount, 3);
  assert.deepEqual(missingLicenseFile, []);
  for (const text of ['lib@1.0.0 — MIT', 'lib@2.0.0 — BSD-3-Clause', 'icons@1.0.0', 'Copyright Embedded Author <example>', 'Copyright Icon Author']) assert.ok(notices.includes(text), text);
  assert.ok(!notices.includes('build-only'));
  assert.equal(readFileSync(join(output, 'LICENSE.txt'), 'utf8'), 'Power Log Apache-2.0 licence');
  assert.equal(readFileSync(join(output, 'THIRD_PARTY_NOTICES.txt'), 'utf8'), notices);
});

test('packages without a licence file are reported rather than rejected, and their source headers are kept', t => {
  const { root, output, add, finish } = fixture(t);
  add('node_modules/headed', { licenseFile: '', header: '/**\n * Copyright (c) Header Owner.\n */' });
  add('node_modules/bare', { licenseFile: '' });
  add('node_modules/undeclared', { licenseFile: '', license: null });
  finish();
  const { notices, missingLicenseFile, missingCopyright } = generateWebNotices(root, output);
  assert.deepEqual(missingLicenseFile, ['bare@1.0.0', 'headed@1.0.0', 'undeclared@1.0.0']);
  assert.deepEqual(missingCopyright, ['bare@1.0.0', 'undeclared@1.0.0']);
  assert.ok(notices.includes('Packages that publish no licence file of their own: bare@1.0.0, headed@1.0.0, undeclared@1.0.0.'));
  assert.ok(notices.includes('Packages where no copyright notice was found either: bare@1.0.0, undeclared@1.0.0.'));
  assert.ok(notices.includes('Copyright (c) Header Owner.'));
  assert.ok(notices.includes('bare@1.0.0 — MIT\n\nThis package publishes no licence file of its own. Its declared licence is MIT.\n\nNo copyright notice was found in its bundled source.'));
  assert.ok(notices.includes('undeclared@1.0.0 — no licence declared in package.json'));
});

test('licence files beside vendored code inside a package are attributed to that package', t => {
  const { root, output, write, add, finish } = fixture(t);
  add('node_modules/router', { file: 'vendor/helmet/index.js' });
  write('node_modules/router/vendor/helmet/LICENSE', 'Vendored Apache licence, copyright Vendor Inc.');
  add('node_modules/shell', { licenseFile: '', file: 'vendor/lib/index.js' });
  write('node_modules/shell/vendor/lib/LICENSE', 'Vendored MIT, copyright Lib Author.');
  finish();
  const { notices, missingLicenseFile, missingCopyright } = generateWebNotices(root, output);
  assert.ok(notices.includes('--- vendor/helmet/LICENSE ---\nVendored Apache licence, copyright Vendor Inc.'));
  assert.ok(notices.includes('--- LICENSE ---\nCopyright Example author'));
  assert.deepEqual(missingLicenseFile, ['shell@1.0.0']);
  assert.deepEqual(missingCopyright, ['shell@1.0.0']);
  assert.ok(notices.includes('shell@1.0.0 — MIT\n\nThis package publishes no licence file of its own. Its declared licence is MIT.\n\nNo copyright notice was found in its bundled source.\n\n--- vendor/lib/LICENSE ---\nVendored MIT, copyright Lib Author.'));
});

test('public files exclude maps and drop map references from scripts and styles', t => {
  const { output, write } = fixture(t);
  write('export/bundle.js', '"use strict";\n//# sourceMappingURL=bundle.js.map\n');
  write('export/bundle.js.map', '{"sourcesContent":["private build input"]}');
  write('export/style.css', 'body{}\n/*# sourceMappingURL=style.css.map */');
  write('export/style.css.map', '{}');
  const files = removeSourceMaps(output);
  assert.equal(files.length, 2);
  for (const file of files) {
    assert.ok(!file.endsWith('.map'));
    assert.ok(!readFileSync(file, 'utf8').includes('sourceMappingURL'));
  }
});
