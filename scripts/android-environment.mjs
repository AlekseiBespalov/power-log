import { spawnSync } from 'node:child_process';
import { existsSync } from 'node:fs';
import { resolve, join } from 'node:path';
import { homedir } from 'node:os';

export const root = resolve(import.meta.dirname, '..');

export function androidEnvironment(overrides) {
  const env = { ...process.env, EXPO_NO_DOTENV: '1', ...overrides };
  if (!env.JAVA_HOME && process.platform === 'darwin') {
    const installed = spawnSync('/usr/libexec/java_home', ['-v', '17'], { encoding: 'utf8' });
    if (installed.status === 0) env.JAVA_HOME = installed.stdout.trim();
  }
  if (!env.JAVA_HOME && process.platform === 'darwin') {
    env.JAVA_HOME = [
      '/Applications/Android Studio.app/Contents/jbr/Contents/Home',
      join(homedir(), 'Applications/Android Studio.app/Contents/jbr/Contents/Home'),
    ].find(existsSync);
  }
  if (!env.ANDROID_HOME && process.platform === 'darwin') env.ANDROID_HOME = join(homedir(), 'Library/Android/sdk');
  return env;
}

export function runner(env) {
  return (command, args, cwd = root) => {
    const result = spawnSync(command, args, { cwd, env, stdio: 'inherit' });
    if (result.error) throw result.error;
    if (result.status !== 0) process.exit(result.status ?? 1);
  };
}
