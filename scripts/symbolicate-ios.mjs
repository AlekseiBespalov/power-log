import { execFileSync } from 'node:child_process';
import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { dirname, join } from 'node:path';

const LOG_ENTRY = 'PowerLog-diagnostics/connection-log.jsonl';
const dwarf = /^UUID: ([0-9A-F-]{36}) \((\S+)\) (.+)$/;

function insideFolder(folder, path) {
  const parts = path.split('/');
  const bundle = parts.findLastIndex(part => part.endsWith('.dSYM'));
  return bundle < 0 ? path : join(folder, ...parts.slice(bundle));
}

export function archivedBinaries(uuidListings) {
  const binaries = new Map();
  for (const { folder, text } of uuidListings)
    for (const line of text.split('\n')) {
      const match = dwarf.exec(line.trim());
      if (match) binaries.set(match[1], { arch: match[2], path: insideFolder(folder, match[3]) });
    }
  return binaries;
}

export function symbolicationPlan(log, binaries) {
  const plan = [];
  for (const text of log.split('\n')) {
    let line;
    try {
      line = JSON.parse(text);
    } catch {
      continue;
    }
    if (line?.event !== 'crash' && line?.event !== 'unexpected') continue;
    if (typeof line.image !== 'string' || typeof line.load !== 'string' || !Array.isArray(line.frames)) continue;
    const binary = binaries.get(line.image.toUpperCase());
    plan.push({
      title: [line.event, line.exception, line.component ?? line.thread].filter(Boolean).join(' '),
      image: line.image,
      atos: binary ? ['atos', '-arch', binary.arch, '-o', binary.path, '-l', line.load, ...line.frames] : null,
    });
  }
  return plan;
}

function logText(input) {
  if (input.endsWith('.zip')) return execFileSync('unzip', ['-p', input, LOG_ENTRY], { encoding: 'utf8' });
  return readFileSync(input, 'utf8');
}

function uuidListings(releases) {
  return readdirSync(releases)
    .map(name => join(releases, name, 'ios/dSYMs/uuids.txt'))
    .filter(existsSync)
    .map(path => ({ folder: dirname(path), text: readFileSync(path, 'utf8') }));
}

if (process.argv[1]?.endsWith('symbolicate-ios.mjs')) {
  const [input, releases] = process.argv.slice(2);
  for (const entry of symbolicationPlan(logText(input), archivedBinaries(uuidListings(releases)))) {
    console.log(entry.title);
    if (entry.atos) console.log(execFileSync('xcrun', entry.atos, { encoding: 'utf8' }).trimEnd());
    else console.log(`  no archived dSYM has image ${entry.image}`);
  }
}
