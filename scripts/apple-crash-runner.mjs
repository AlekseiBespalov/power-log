import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { homedir } from 'node:os';
import { setTimeout } from 'node:timers/promises';
import { clearTimeout, setTimeout as schedule } from 'node:timers';

const bundles = { phone: 'app.powerlog.lab', watch: 'app.powerlog.lab.watchkitapp' };

export function simulatorPair(env, devices, pairs) {
  const selected = { phone: env.POWER_LOG_LAB_PHONE_UDID, watch: env.POWER_LOG_LAB_WATCH_UDID };
  for (const side of ['phone', 'watch']) {
    assert.match(
      selected[side] ?? '',
      /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i,
      `Set POWER_LOG_LAB_${side.toUpperCase()}_UDID`,
    );
    const runtime = side === 'phone' ? '.iOS-' : '.watchOS-';
    const device = Object.entries(devices.devices)
      .filter(([key]) => key.includes(runtime))
      .flatMap(([, values]) => values)
      .find(value => value.udid.toLowerCase() === selected[side].toLowerCase());
    assert.ok(device?.isAvailable && device.state === 'Booted', `${side} simulator must be available and booted`);
    if (side === 'phone')
      assert.ok(device.deviceTypeIdentifier?.includes('.iPhone-'), 'The phone simulator must be an iPhone');
    selected[side] = device.udid;
  }
  assert.ok(
    Object.values(pairs.pairs).some(pair => pair.phone?.udid === selected.phone && pair.watch?.udid === selected.watch),
    'Selected phone and Watch simulators are not paired',
  );
  return selected;
}

export function launchResult(name, run, results, digest, status, pid) {
  const result = results.find(value => value.case === name && value.run === run);
  assert.ok(result, `No current-run result from ${name}: ${run}`);
  assert.equal(result.status, status, JSON.stringify(result));
  assert.equal(result.sourceDigest, digest, 'Installed Lab belongs to another source build');
  assert.ok(Number.isInteger(result.pid) && result.pid > 0, 'Lab result has no process identity');
  assert.equal(result.pid, pid, 'Lab result belongs to another process');
  return result;
}

export function checkpointResult(initial, results, digest, status) {
  const result = results.find(value => value.run === initial.run && value.case === initial.case);
  assert.ok(result, 'The current Lab result disappeared');
  assert.equal(result.sourceDigest, digest);
  assert.equal(result.pid, initial.pid, 'The armed process changed before its checkpoint');
  assert.notEqual(result.status, 'error', JSON.stringify(result));
  return result.status === status ? result : null;
}

export function idleTermination(error) {
  return error.status === 3 && /found nothing to terminate/.test(String(error.stderr));
}

function executeCommand(command, args, { cwd, env, timeout, stdio }) {
  return new Promise((resolveCommand, rejectCommand) => {
    const child = spawn(command, args, { cwd, env, detached: true, stdio: stdio ?? ['ignore', 'pipe', 'pipe'] });
    let stdout = '';
    let stderr = '';
    let closed = false;
    let status;
    let signal;
    let expired = false;
    let finished = false;
    let deadline;
    let escalation;
    let poll;
    child.stdout?.setEncoding('utf8').on('data', data => {
      stdout += data;
    });
    child.stderr?.setEncoding('utf8').on('data', data => {
      stderr += data;
    });
    const signalGroup = requested => {
      if (!child.pid) return false;
      try {
        process.kill(-child.pid, requested);
        return true;
      } catch (error) {
        if (error.code === 'ESRCH') return false;
        throw error;
      }
    };
    const finish = error => {
      if (finished) return;
      finished = true;
      clearTimeout(deadline);
      clearTimeout(escalation);
      clearTimeout(poll);
      if (error) rejectCommand(error);
      else resolveCommand(stdout);
    };
    const inspect = () => {
      if (finished) return;
      clearTimeout(poll);
      try {
        if (!closed || signalGroup(0)) {
          poll = schedule(inspect, 25);
          return;
        }
        if (expired || status !== 0) {
          finish(
            Object.assign(new Error(`${command} ${expired ? 'timed out' : `exited with ${signal ?? status}`}`), {
              code: expired ? 'ETIMEDOUT' : undefined,
              status,
              signal,
              stdout,
              stderr,
            }),
          );
        } else finish();
      } catch (error) {
        finish(error);
      }
    };
    child.once('error', finish);
    child.once('close', (code, exitSignal) => {
      closed = true;
      status = code;
      signal = exitSignal;
      inspect();
    });
    deadline = schedule(() => {
      expired = true;
      try {
        signalGroup('SIGTERM');
        escalation = schedule(() => {
          try {
            signalGroup('SIGKILL');
            inspect();
          } catch (error) {
            finish(error);
          }
        }, 2000);
        inspect();
      } catch (error) {
        finish(error);
      }
    }, timeout);
  });
}

export class AppleCrashRunner {
  constructor({
    digest,
    root = '.',
    env = process.env,
    exec = executeCommand,
    now = () => performance.now(),
    sleep = setTimeout,
    uuid = randomUUID,
    log = console.log,
  }) {
    Object.assign(this, {
      digest,
      root: resolve(root),
      env: { ...env, BUNDLE_PATH: env.BUNDLE_PATH ?? join(homedir(), '.cache/power-log/bundle') },
      exec,
      now,
      sleep,
      uuid,
      log,
    });
    this.devices = undefined;
  }

  async command(command, args, timeout = 60000, options = {}) {
    assert.ok(timeout > 0, `${command} deadline expired`);
    return String(
      (await this.exec(command, args, { cwd: this.root, env: this.env, encoding: 'utf8', timeout, ...options })) ?? '',
    ).trim();
  }

  simctl(args, timeout) {
    return this.command('xcrun', ['simctl', ...args], timeout);
  }

  async prepare({ build = true } = {}) {
    this.devices = simulatorPair(
      this.env,
      JSON.parse(await this.simctl(['list', 'devices', '--json'])),
      JSON.parse(await this.simctl(['list', 'pairs', '--json'])),
    );
    for (const side of ['phone', 'watch']) await this.simctl(['bootstatus', this.devices[side], '-b'], 180000);
    if (build)
      await this.command('sh', ['scripts/build-ios-simulator.sh'], 35 * 60 * 1000, {
        env: {
          ...this.env,
          POWER_LOG_LAB: '1',
          GRADLE_OPTS: `${this.env.GRADLE_OPTS ?? ''} -Dorg.gradle.daemon=false`,
        },
        stdio: 'inherit',
      });
    const phone = build
      ? join(this.root, 'ios/build/Build/Products/Debug-iphonesimulator/PowerLog.app')
      : await this.container('phone', 'app');
    const watchApps = readdirSync(join(phone, 'Watch')).filter(file => file.endsWith('.app'));
    assert.equal(watchApps.length, 1, 'Expected exactly one embedded Watch Lab app');
    const watch = build ? join(phone, 'Watch', watchApps[0]) : await this.container('watch', 'app');
    await this.command('npm', ['run', 'test:ios-bundle', '--', phone], 60000, { stdio: 'inherit' });
    for (const [side, app] of [
      ['phone', phone],
      ['watch', watch],
    ]) {
      assert.equal(
        await this.command('plutil', ['-extract', 'CFBundleIdentifier', 'raw', join(app, 'Info.plist')]),
        bundles[side],
        'Refusing a non-Lab bundle',
      );
      await this.command('codesign', ['--verify', '--deep', '--strict', app]);
      const manifest = JSON.parse(
        readFileSync(join(app, side === 'phone' ? 'build.json' : 'watch-build.json'), 'utf8'),
      );
      assert.equal(manifest.sourceDigest, this.digest, 'Lab bundle belongs to another source build');
    }
    if (build) {
      await this.simctl(['install', this.devices.phone, phone], 180000);
      await this.simctl(['install', this.devices.watch, watch], 180000);
    }
  }

  async container(side, kind = 'data') {
    assert.ok(bundles[side] && this.devices?.[side], 'Unknown or unprepared simulator side');
    const result = await this.simctl(['get_app_container', this.devices[side], bundles[side], kind]);
    assert.ok(result.startsWith('/'), 'Simulator returned an invalid container path');
    return result;
  }

  async resultFile(side, run) {
    assert.match(run, /^[a-zA-Z0-9_-]{1,120}$/);
    return join(await this.container(side), 'Documents/lab-results', `${run}.json`);
  }

  async poll(read, timeout, description) {
    const deadline = this.now() + timeout;
    for (;;) {
      assert.ok(this.now() < deadline, `${description} timed out`);
      const result = await read(Math.max(1, Math.ceil(deadline - this.now())));
      assert.ok(this.now() < deadline, `${description} timed out`);
      if (result) return result;
      await this.sleep(Math.min(1000, deadline - this.now()));
    }
  }

  async assertAlive(side, pid, name, run) {
    const command = await this.command('ps', ['-o', 'command=', '-p', String(pid)]);
    assert.ok(
      command.includes(`-PowerLogLabCase ${name}`) && command.includes(`-PowerLogLabRun ${run}`),
      `${side} process did not retain the Lab launch arguments`,
    );
  }

  async waitForExit(pid) {
    await this.poll(
      async remaining => {
        try {
          return (await this.command('ps', ['-o', 'pid=', '-p', String(pid)], remaining)) === '';
        } catch (error) {
          if (error.status === 1 && !String(error.stdout ?? '').trim() && !String(error.stderr ?? '').trim())
            return true;
          throw error;
        }
      },
      30000,
      `Process ${pid} exit`,
    );
  }

  async launch(side, name, status) {
    assert.ok(bundles[side] && this.devices?.[side], 'Unknown or unprepared simulator side');
    assert.match(name, /^[a-z0-9-]+$/);
    const run = this.uuid();
    const file = await this.resultFile(side, run);
    assert.ok(!existsSync(file), 'Lab run identifier already exists');
    this.log(`${side}: ${name}, run ${run}`);
    const output = await this.simctl([
      'launch',
      '--terminate-running-process',
      this.devices[side],
      bundles[side],
      '-PowerLogLabCase',
      name,
      '-PowerLogLabRun',
      run,
    ]);
    const pid = Number(/: (\d+)\s*$/.exec(output)?.[1]);
    assert.ok(Number.isSafeInteger(pid) && pid > 0, 'simctl launch did not return a process identity');
    const result = await this.poll(
      async () => {
        if (existsSync(file)) return JSON.parse(readFileSync(file, 'utf8'));
        await this.assertAlive(side, pid, name, run);
        return null;
      },
      15 * 60 * 1000,
      `${side} ${name} (${run})`,
    );
    if (side === 'phone' && name === 'authorize') {
      assert.equal(result.case, name);
      assert.equal(result.run, run);
      assert.equal(result.status, 'complete', JSON.stringify(result));
    } else launchResult(name, run, [result], this.digest, status, pid);
    if (status === 'killed') await this.waitForExit(pid);
    if (!['killed', 'captured'].includes(status)) await this.assertAlive(side, pid, name, run);
    return result;
  }

  async waitForResult(side, initial, status) {
    const file = await this.resultFile(side, initial.run);
    const result = await this.poll(
      () => checkpointResult(initial, [JSON.parse(readFileSync(file, 'utf8'))], this.digest, status),
      180000,
      `${side} ${initial.case} reaching ${status}`,
    );
    if (status === 'killed') await this.waitForExit(result.pid);
    return result;
  }

  async terminate(side) {
    assert.ok(bundles[side] && this.devices?.[side], 'Unknown or unprepared simulator side');
    try {
      await this.simctl(['terminate', this.devices[side], bundles[side]]);
    } catch (error) {
      if (!idleTermination(error)) throw error;
    }
  }

  async terminateAll() {
    if (!this.devices) return;
    const errors = [];
    for (const side of ['phone', 'watch']) {
      try {
        await this.terminate(side);
      } catch (error) {
        errors.push(error);
      }
    }
    if (errors.length) throw new AggregateError(errors, 'Lab termination failed');
  }
}
