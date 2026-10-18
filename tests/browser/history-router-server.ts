import { spawn, type ChildProcess } from 'node:child_process';
import { mkdtempSync, writeFileSync, symlinkSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';

export async function startHistoryRouterServer(port: number): Promise<() => void> {
  const project = mkdtempSync(path.join(tmpdir(), 'power-log-history-router-'));
  const workspace = process.cwd();
  symlinkSync(path.join(workspace, 'node_modules'), path.join(project, 'node_modules'));
  const manifest = JSON.parse(readFileSync(path.join(workspace, 'package.json'), 'utf8'));

  writeFileSync(
    path.join(project, 'package.json'),
    JSON.stringify({
      name: 'history-router-tests',
      version: '1.0.0',
      main: 'entry.js',
      dependencies: manifest.dependencies,
    }),
  );
  writeFileSync(
    path.join(project, 'app.json'),
    JSON.stringify({ expo: { name: 'History router tests', slug: 'history-router-tests', web: { bundler: 'metro' } } }),
  );
  writeFileSync(
    path.join(project, 'entry.js'),
    `import ${JSON.stringify(path.join(workspace, 'tests/browser/history-router-entry.tsx'))};`,
  );
  const backend = path.join(workspace, 'tests/fixtures/history-deletion-backend.ts');
  const platform = path.join(workspace, 'tests/fixtures/history-deletion-platform.tsx');
  const aliases: Record<string, string> = {};
  for (const name of ['src/services/workouts', 'src/services/ride-export'])
    aliases[path.join(workspace, name)] = backend;
  for (const name of [
    'src/services/session-context',
    'src/services/device',
    'src/services/monitor-preferences',
    'src/services/workout-monitor-source',
    'src/services/files',
    'src/components/route-preview',
    'src/features/monitor/monitor-panel',
  ])
    aliases[path.join(workspace, name)] = platform;
  writeFileSync(
    path.join(project, 'metro.config.js'),
    `const { getDefaultConfig } = require('expo/metro-config');
const path = require('path');
const config = getDefaultConfig(__dirname);
config.watchFolders = [${JSON.stringify(workspace)}];
config.resolver.nodeModulesPaths = [${JSON.stringify(path.join(workspace, 'node_modules'))}];
config.resolver.useWatchman = false;
const aliases = ${JSON.stringify(aliases, null, 2)};
config.resolver.resolveRequest = (context, name, platform) => {
  const requested = name.startsWith('.') ? path.resolve(path.dirname(context.originModulePath), name) : name;
  return context.resolveRequest(context, aliases[requested] || name, platform);
};
module.exports = config;`,
  );
  let output = '';
  const child: ChildProcess = spawn(
    process.execPath,
    [path.join(workspace, 'node_modules/expo/bin/cli'), 'start', project, '--web', '--port', String(port)],
    {
      env: { ...process.env, CI: '1', NODE_ENV: 'development', EXPO_NO_TELEMETRY: '1' },
      stdio: ['ignore', 'pipe', 'pipe'],
    },
  );
  child.once('exit', () => rmSync(project, { recursive: true, force: true }));
  child.stdout?.on('data', bytes => {
    output += String(bytes);
  });
  child.stderr?.on('data', bytes => {
    output += String(bytes);
  });
  const stop = () => {
    child.kill('SIGTERM');
  };
  try {
    const deadline = Date.now() + 110000;
    while (Date.now() < deadline) {
      if (child.exitCode !== null) throw new Error(output);
      try {
        const response = await fetch(`http://127.0.0.1:${port}/entry.bundle?platform=web&dev=false&minify=false`);
        if (response.ok) return stop;
        const error = await response.text();
        if (error) throw new Error(error);
      } catch (error) {
        if (!(error instanceof TypeError)) throw error;
      }
      await new Promise(resolve => setTimeout(resolve, 250));
    }
    throw new Error(`History router Metro timed out: ${output}`);
  } catch (error) {
    stop();
    throw error;
  }
}
