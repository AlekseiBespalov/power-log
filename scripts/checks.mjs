import { spawn, spawnSync } from 'node:child_process';
import { createHash, randomUUID } from 'node:crypto';
import {
  closeSync,
  existsSync,
  lstatSync,
  mkdirSync,
  openSync,
  readFileSync,
  readdirSync,
  readlinkSync,
  renameSync,
  rmSync,
  writeFileSync,
  writeSync,
} from 'node:fs';
import { availableParallelism, loadavg, tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const literal = text => text.replace(/[.+^$()|[\]\\]/g, '\\$&');

export function globExpression(glob) {
  let source = '';
  for (let index = 0; index < glob.length; index++) {
    const char = glob[index];
    if (char === '*' && glob[index + 1] === '*') {
      index++;
      if (glob[index + 1] === '/') {
        index++;
        source += '(?:.*/)?';
      } else source += '.*';
    } else if (char === '*') source += '[^/]*';
    else if (char === '?') source += '[^/]';
    else if (char === '{') {
      const end = glob.indexOf('}', index);
      source += `(?:${glob
        .slice(index + 1, end)
        .split(',')
        .map(literal)
        .join('|')})`;
      index = end;
    } else source += literal(char);
  }
  return new RegExp(`^${source}$`);
}

export function inputMatcher(patterns) {
  const include = patterns.filter(pattern => !pattern.startsWith('!')).map(globExpression);
  const exclude = patterns.filter(pattern => pattern.startsWith('!')).map(pattern => globExpression(pattern.slice(1)));
  return name => include.some(pattern => pattern.test(name)) && !exclude.some(pattern => pattern.test(name));
}

function digestFile(path) {
  try {
    const stat = lstatSync(path);
    if (stat.isDirectory()) return undefined;
    const content = stat.isSymbolicLink() ? `link:${readlinkSync(path)}` : readFileSync(path);
    return createHash('sha1')
      .update(stat.mode & 0o111 ? 'x' : '-')
      .update(content)
      .digest('hex');
  } catch (error) {
    if (error.code === 'ENOENT') return 'absent';
    throw error;
  }
}

function workingFiles(root) {
  const listed = spawnSync('git', ['ls-files', '-z', '--cached', '--others', '--exclude-standard'], {
    cwd: root,
    maxBuffer: 64 * 1024 * 1024,
  });
  if (listed.error) throw listed.error;
  if (listed.status !== 0) throw new Error(`git ls-files failed: ${listed.stderr}`);
  const files = new Map();
  for (const name of [...new Set(listed.stdout.toString('utf8').split('\0'))].filter(Boolean).sort()) {
    const digest = digestFile(join(root, name));
    if (digest) files.set(name, digest);
  }
  return files;
}

function version(command, args, env) {
  const result = spawnSync(command, args, { encoding: 'utf8', env, timeout: 60000 });
  return result.error ? `unavailable: ${result.error.code}` : `${result.stdout}${result.stderr}`.trim();
}

const toolchains = {
  node: () => process.version,
  swift: env => version('xcrun', ['swiftc', '--version'], env),
  java: env => version(env.JAVA_HOME ? join(env.JAVA_HOME, 'bin/java') : 'java', ['-version'], env),
  python: env => version('python3', ['--version'], env),
  fit: env =>
    env.FIT_PYTHON && existsSync(env.FIT_PYTHON)
      ? version(env.FIT_PYTHON, ['-c', 'from importlib.metadata import version; print(version("garmin-fit-sdk"))'], env)
      : 'absent',
  android: env =>
    ['build-tools', 'platforms']
      .map(folder => {
        try {
          return readdirSync(join(env.ANDROID_HOME ?? '', folder))
            .sort()
            .join(',');
        } catch {
          return 'absent';
        }
      })
      .join(' '),
};

function readJson(path, fallback) {
  try {
    return JSON.parse(readFileSync(path, 'utf8'));
  } catch {
    return fallback;
  }
}

function writeJson(path, value) {
  const temporary = `${path}.${process.pid}`;
  writeFileSync(temporary, `${JSON.stringify(value)}\n`);
  renameSync(temporary, path);
}

const safeName = id => id.replace(/[^A-Za-z0-9_.-]+/g, '-');
const seconds = milliseconds => `${(milliseconds / 1000).toFixed(1)}s`;

export function emulatorAvailable(env) {
  if (env.POWER_LOG_SKIP_CONNECTED === '1') return false;
  const adb = join(env.ANDROID_HOME ?? '', 'platform-tools/adb');
  const result = spawnSync(adb, ['-s', 'emulator-5554', 'get-state'], { encoding: 'utf8', env, timeout: 20000 });
  return result.status === 0 && result.stdout.trim() === 'device';
}

function tail(path, lines) {
  try {
    return readFileSync(path, 'utf8').split('\n').slice(-lines).join('\n');
  } catch {
    return '';
  }
}

export function selects(task, only) {
  return (
    !only.length || only.some(name => task.id === name || task.id.startsWith(`${name}:`) || task.groups?.includes(name))
  );
}

export function planChecks({
  tasks,
  files,
  records,
  snapshots = () => undefined,
  tools = {},
  full,
  force,
  only = [],
  skip = [],
  env,
}) {
  const byId = new Map(tasks.map(task => [task.id, task]));
  if (byId.size !== tasks.length) throw new Error('Check identifiers must be unique.');
  for (const task of tasks)
    for (const need of [...(task.needs ?? []), ...(task.after ?? [])])
      if (!byId.has(need)) throw new Error(`${task.id} refers to unknown check ${need}`);
  const ordered = [];
  const visiting = new Set();
  const visit = task => {
    if (ordered.includes(task)) return;
    if (visiting.has(task.id)) throw new Error(`Check dependency cycle at ${task.id}`);
    visiting.add(task.id);
    for (const need of [...(task.needs ?? []), ...(task.after ?? [])]) visit(byId.get(need));
    visiting.delete(task.id);
    ordered.push(task);
  };
  tasks.forEach(visit);
  const keys = new Map();
  const matchers = new Map();
  const names = [...files.keys()];
  for (const task of ordered) {
    const accepts = inputMatcher(task.inputs ?? []);
    matchers.set(task.id, accepts);
    const hash = createHash('sha256').update(
      JSON.stringify({
        id: task.id,
        definition: task.definition ?? (Array.isArray(task.run) ? task.run : null),
        env: Object.fromEntries((task.env ?? []).map(name => [name, env[name] ?? null])),
        tools: Object.fromEntries((task.tools ?? []).map(name => [name, tools[name] ?? null])),
        extra: (task.files ?? []).map(name => [name, digestFile(name)]),
        needs: (task.needs ?? []).map(need => [need, keys.get(need)]),
      }),
    );
    for (const name of names) if (accepts(name)) hash.update(`${name}\0${files.get(name)}\n`);
    keys.set(task.id, hash.digest('hex'));
  }
  const eligible = task =>
    selects(task, only) &&
    !(skip.length && selects(task, skip)) &&
    (full || task.tier !== 'release') &&
    (!task.when || task.when(env, full));
  if (only.length && !tasks.some(task => selects(task, only))) throw new Error(`No check matches ${only.join(', ')}`);
  if (skip.length && !tasks.some(task => selects(task, skip))) throw new Error(`No check matches ${skip.join(', ')}`);
  const reasons = new Map();
  for (const task of ordered) {
    if (!eligible(task)) continue;
    const record = records[task.id];
    if (full) reasons.set(task.id, 'full tier');
    else if (force) reasons.set(task.id, 'forced');
    else if (!record) reasons.set(task.id, 'no earlier pass');
    else if (record.key !== keys.get(task.id)) {
      const accepts = matchers.get(task.id);
      const before = snapshots(record.snapshot);
      const changed = before
        ? [
            ...names.filter(name => accepts(name) && before[name] !== files.get(name)),
            ...Object.keys(before).filter(name => accepts(name) && !files.has(name)),
          ]
        : [];
      const upstream = (task.needs ?? []).filter(need => reasons.has(need) || records[need]?.key !== keys.get(need));
      reasons.set(
        task.id,
        changed.length
          ? `changed ${changed.slice(0, 3).join(', ')}${changed.length > 3 ? ` and ${changed.length - 3} more` : ''}`
          : upstream.length
            ? `inputs from ${upstream.join(', ')} changed`
            : 'changed toolchain, environment or check definition',
      );
    }
  }
  for (const task of [...ordered].reverse()) {
    if (!reasons.has(task.id)) continue;
    for (const need of task.needs ?? []) {
      const producer = byId.get(need);
      if (reasons.has(need) || !producer.outputs) continue;
      if (records[need]?.key !== keys.get(need) || producer.outputs.some(output => !existsSync(output)))
        reasons.set(need, `produces inputs for ${task.id}`);
    }
  }
  return {
    ordered,
    keys,
    reasons,
    unchanged: ordered.filter(task => eligible(task) && !reasons.has(task.id)).map(task => task.id),
  };
}

function criticalPaths(ordered, expected) {
  const priority = new Map();
  for (const task of [...ordered].reverse()) {
    const own = task.alone ? 0 : expected(task);
    const dependents = ordered
      .filter(other => other.needs?.includes(task.id) || other.after?.includes(task.id))
      .map(other => priority.get(other.id) ?? 0);
    priority.set(task.id, own + Math.max(0, ...dependents));
  }
  return priority;
}

function acquireRunLock(lock) {
  const occupied = () => new Error(`Another check run owns ${lock}, or its owner cannot be read safely.`);
  try {
    return openSync(lock, 'wx');
  } catch (error) {
    if (error.code !== 'EEXIST') throw error;
  }
  const recovery = spawnSync('python3', [resolve(import.meta.dirname, 'reap-check-lock.py'), lock], {
    encoding: 'utf8',
    timeout: 10000,
  });
  if (recovery.error) throw recovery.error;
  if (recovery.status === 1) throw occupied();
  if (recovery.status !== 0) throw new Error(`Cannot recover check lock: ${recovery.stderr}`);
  try {
    return openSync(lock, 'wx');
  } catch (error) {
    if (error.code === 'EEXIST') throw occupied();
    throw error;
  }
}

export async function runChecks({
  root,
  tasks,
  full = false,
  force = false,
  only = [],
  skip = [],
  jobs = availableParallelism(),
  stateDirectory = resolve(root, 'test-results/gates'),
  keepRecords = true,
  explain = false,
  echo = false,
  env = process.env,
  print = line => console.log(line),
  listFiles = workingFiles,
}) {
  const started = Date.now();
  mkdirSync(join(stateDirectory, 'logs'), { recursive: true });
  mkdirSync(join(stateDirectory, 'snapshots'), { recursive: true });
  const lock = join(stateDirectory, 'run.lock');
  const token = randomUUID();
  const descriptor = acquireRunLock(lock);
  try {
    writeFileSync(descriptor, JSON.stringify({ pid: process.pid, token }));
  } finally {
    closeSync(descriptor);
  }
  try {
    const recordsPath = join(stateDirectory, 'passed.json');
    const records = keepRecords ? readJson(recordsPath, {}) : {};
    const files = listFiles(root);
    const tools = Object.fromEntries(
      [...new Set(tasks.flatMap(task => task.tools ?? []))].map(name => [name, toolchains[name](env)]),
    );
    const loaded = new Map();
    const snapshots = id => {
      if (!id) return undefined;
      if (!loaded.has(id)) loaded.set(id, readJson(join(stateDirectory, 'snapshots', `${id}.json`), undefined));
      return loaded.get(id);
    };
    const { ordered, keys, reasons, unchanged } = planChecks({
      tasks,
      files,
      records,
      snapshots,
      tools,
      full,
      force,
      only,
      skip,
      env,
    });
    const byId = new Map(tasks.map(task => [task.id, task]));
    const queue = ordered.filter(task => reasons.has(task.id));
    print(
      `Checks (${full ? 'full release tier' : 'everyday tier'}): ${queue.length} to run, ${unchanged.length} unchanged since their last pass, ${jobs} parallel slots.`,
    );
    for (const task of queue) print(`  ${task.id}: ${reasons.get(task.id)}`);
    if (explain) {
      return { failed: [], passed: [], pending: [], unchanged, total: 0 };
    }
    const snapshot = `${Date.now().toString(36)}-${process.pid}`;
    writeJson(join(stateDirectory, 'snapshots', `${snapshot}.json`), Object.fromEntries(files));
    const expected = task => Math.max(records[task.id]?.seconds ?? 0, task.estimate ?? 10);
    const priority = criticalPaths(ordered, expected);
    const status = new Map();
    const running = new Map();
    const timings = [];
    const children = new Set();
    const runDirectory = join(tmpdir(), `power-log-checks-${process.pid}`);
    let used = 0;
    let emulator;
    let interrupted = false;
    const stop = signal => {
      interrupted = true;
      for (const child of children) {
        try {
          process.kill(-child.pid, 'SIGTERM');
        } catch {
          child.kill('SIGTERM');
        }
      }
      print(`Stopping after ${signal}.`);
    };
    process.once('SIGINT', stop);
    process.once('SIGTERM', stop);
    const saveRecords = () => {
      if (!keepRecords) return;
      const known = new Set(tasks.map(task => task.id));
      for (const id of Object.keys(records)) if (!known.has(id)) delete records[id];
      writeJson(recordsPath, records);
    };
    const execute = async task => {
      const log = join(stateDirectory, 'logs', `${safeName(task.id)}.log`);
      const descriptor = openSync(log, 'w');
      const scratch = join(runDirectory, String(ordered.indexOf(task)));
      mkdirSync(scratch, { recursive: true });
      const context = {
        root,
        full,
        env,
        scratch,
        log,
        write: text => writeSync(descriptor, text.endsWith('\n') ? text : `${text}\n`),
        exec: (command, args = [], options = {}) =>
          new Promise((resolveRun, reject) => {
            const begin = Date.now();
            writeSync(descriptor, `$ ${[command, ...args].join(' ')}\n`);
            const child = spawn(command, args, {
              cwd: options.cwd ?? root,
              env: {
                ...env,
                POWER_LOG_PREBUILD_LOCK: join(runDirectory, 'prebuild.lock'),
                ...(task.isolate ? { TMPDIR: `${scratch}/` } : {}),
                ...options.env,
              },
              stdio: ['ignore', options.stdout ?? descriptor, options.stderr ?? descriptor],
              detached: true,
            });
            children.add(child);
            child.on('error', error => {
              children.delete(child);
              reject(error);
            });
            child.on('close', (code, signal) => {
              children.delete(child);
              writeSync(
                descriptor,
                `[${command.split('/').pop()} exited with ${signal ?? code} after ${seconds(Date.now() - begin)}]\n`,
              );
              if (code === 0) resolveRun();
              else reject(new Error(`${[command, ...args].join(' ')} exited with ${signal ?? code}`));
            });
          }),
      };
      try {
        if (Array.isArray(task.run)) await context.exec(task.run[0], task.run.slice(1));
        else await task.run(context);
      } finally {
        closeSync(descriptor);
        rmSync(scratch, { recursive: true, force: true });
      }
    };
    const ready = task =>
      (task.needs ?? []).every(need => !reasons.has(need) || status.get(need) === 'passed') &&
      (task.after ?? []).every(other => !reasons.has(other) || status.has(other));
    const blockedBy = task => {
      const states = (task.needs ?? []).filter(need => reasons.has(need)).map(need => status.get(need));
      if (states.some(state => ['failed', 'blocked', 'interrupted'].includes(state))) return 'blocked';
      if (states.includes('pending')) return 'pending';
      return undefined;
    };
    const reserve = weight => {
      let free = jobs - used;
      const finishing = [...running.values()]
        .map(entry => ({ end: entry.begin + expected(entry.task) * 1000, weight: entry.weight }))
        .sort((a, b) => a.end - b.end);
      for (const entry of finishing) {
        free += entry.weight;
        if (free >= weight) return { time: entry.end, spare: free - weight };
      }
      return { time: Infinity, spare: 0 };
    };
    await new Promise(done => {
      const finish = (task, result, begin, load, weight, error) => {
        const elapsed = Date.now() - begin;
        used -= weight;
        running.delete(task.id);
        status.set(task.id, interrupted && result === 'failed' ? 'interrupted' : result);
        timings.push([task.id, Math.round(elapsed / 1000), status.get(task.id), load]);
        if (result === 'passed')
          records[task.id] = { key: keys.get(task.id), seconds: Math.round(elapsed / 100) / 10, snapshot };
        else delete records[task.id];
        saveRecords();
        if (echo && result === 'passed')
          print(readFileSync(join(stateDirectory, 'logs', `${safeName(task.id)}.log`), 'utf8'));
        print(
          `[${seconds(Date.now() - started).padStart(7)}] ${task.id} ${status.get(task.id)} after ${seconds(elapsed)}${error ? `: ${error.message}` : ''}`,
        );
        pump();
      };
      const pump = () => {
        for (const task of queue) {
          if (status.has(task.id) || running.has(task.id)) continue;
          const outcome = interrupted ? 'interrupted' : blockedBy(task);
          if (outcome) {
            status.set(task.id, outcome);
            timings.push([task.id, 0, outcome, '']);
          }
        }
        const candidates = queue
          .filter(task => !status.has(task.id) && !running.has(task.id) && ready(task))
          .sort((a, b) => priority.get(b.id) - priority.get(a.id));
        let reservation;
        for (const task of candidates) {
          if ([...running.values()].some(entry => entry.task.alone)) break;
          if (task.alone && running.size) continue;
          if (
            (task.resources ?? []).some(resource =>
              [...running.values()].some(entry => entry.task.resources?.includes(resource)),
            )
          )
            continue;
          const weight = Math.min(task.weight ?? 1, jobs);
          if (running.size && used + weight > jobs) {
            reservation ??= reserve(weight);
            continue;
          }
          if (reservation && Date.now() + expected(task) * 1000 > reservation.time) {
            if (weight > reservation.spare) continue;
            reservation.spare -= weight;
          }
          if (task.requires === 'emulator') {
            emulator ??= emulatorAvailable(env);
            if (!emulator) {
              const required = full && env.POWER_LOG_SKIP_CONNECTED !== '1';
              status.set(task.id, required ? 'failed' : 'pending');
              timings.push([task.id, 0, status.get(task.id), '']);
              print(
                required
                  ? `${task.id} failed: emulator-5554 is required. Start it with: $ANDROID_HOME/emulator/emulator -avd PowerLog_API36 -port 5554`
                  : `${task.id} not run: emulator-5554 is not available.`,
              );
              continue;
            }
          }
          const load = loadavg()
            .map(value => value.toFixed(2))
            .join(' ');
          const begin = Date.now();
          used += weight;
          running.set(task.id, { task, begin, weight });
          print(`[${seconds(begin - started).padStart(7)}] ${task.id} started`);
          execute(task).then(
            () => finish(task, 'passed', begin, load, weight),
            error => finish(task, 'failed', begin, load, weight, error),
          );
        }
        if (running.size) return;
        for (const task of queue)
          if (!status.has(task.id)) {
            status.set(task.id, blockedBy(task) ?? 'blocked');
            timings.push([task.id, 0, status.get(task.id), '']);
          }
        done();
      };
      pump();
    });
    process.removeListener('SIGINT', stop);
    process.removeListener('SIGTERM', stop);
    rmSync(runDirectory, { recursive: true, force: true });
    if (keepRecords) {
      const referenced = new Set(Object.values(records).map(record => record.snapshot));
      for (const name of readdirSync(join(stateDirectory, 'snapshots')))
        if (!referenced.has(name.replace(/\.json$/, '')))
          rmSync(join(stateDirectory, 'snapshots', name), { force: true });
    }
    const total = Math.round((Date.now() - started) / 1000);
    writeFileSync(
      join(stateDirectory, 'timing.tsv'),
      [
        'check\tseconds\tstatus\tload_at_start',
        ...timings.map(row => row.join('\t')),
        `total\t${total}\t\t${loadavg()
          .map(value => value.toFixed(2))
          .join(' ')}`,
      ].join('\n') + '\n',
    );
    const outcome = name => queue.filter(task => status.get(task.id) === name).map(task => task.id);
    const failed = outcome('failed');
    for (const id of failed) {
      const log = join(stateDirectory, 'logs', `${safeName(id)}.log`);
      print(`\n--- ${id} failed; full log: ${log}\n${tail(log, byId.get(id).tailLines ?? 80)}`);
    }
    const pending = outcome('pending');
    const notRun = [...outcome('blocked'), ...outcome('interrupted')];
    print(
      `\n${outcome('passed').length} passed, ${failed.length} failed, ${notRun.length} blocked or interrupted, ${pending.length} not run, ${unchanged.length} unchanged; ${total} s wall time.`,
    );
    if (notRun.length) print(`Blocked or interrupted: ${notRun.join(', ')}.`);
    if (pending.length) print(`Not run without emulator-5554: ${pending.join(', ')}.`);
    return { failed: [...failed, ...notRun], passed: outcome('passed'), pending, unchanged, total, status };
  } finally {
    if (readJson(lock, undefined)?.token === token) rmSync(lock, { force: true });
  }
}

export function parseArguments(argv) {
  const options = { full: false, force: false, only: [], skip: [], explain: false, list: false };
  for (let index = 0; index < argv.length; index++) {
    const argument = argv[index];
    if (argument === '--full') options.full = true;
    else if (argument === '--force') options.force = true;
    else if (argument === '--explain') options.explain = true;
    else if (argument === '--list') options.list = true;
    else if (argument === '--only')
      options.only.push(
        ...String(argv[++index] ?? '')
          .split(',')
          .filter(Boolean),
      );
    else if (argument === '--skip')
      options.skip.push(
        ...String(argv[++index] ?? '')
          .split(',')
          .filter(Boolean),
      );
    else if (argument === '--jobs') options.jobs = Number(argv[++index]);
    else
      throw new Error(
        'Usage: node scripts/checks.mjs [--full] [--force] [--only check[,check]] [--skip check[,check]] [--jobs n] [--explain] [--list]',
      );
  }
  if (options.jobs !== undefined && !(Number.isInteger(options.jobs) && options.jobs > 0))
    throw new Error('--jobs needs a positive integer.');
  return options;
}

async function main() {
  const root = resolve(import.meta.dirname, '..');
  const { list, ...options } = parseArguments(process.argv.slice(2));
  const { checkTasks } = await import('./check-tasks.mjs');
  const tasks = checkTasks({ root, env: process.env });
  if (list) {
    for (const task of tasks) console.log([task.id, task.tier, task.title, (task.inputs ?? []).join(' ')].join('\t'));
    return;
  }
  const result = await runChecks({ root, tasks, ...options });
  if (result.failed.length) process.exitCode = 1;
}

if (resolve(process.argv[1] ?? '') === fileURLToPath(import.meta.url)) await main();
