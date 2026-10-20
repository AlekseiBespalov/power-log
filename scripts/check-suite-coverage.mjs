import { existsSync, readdirSync } from 'node:fs';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

export function swiftSuiteInventory(root = '.') {
  const directories = [
    'modules/power-log-bridge/ios/Tests',
    'apple/WatchApp/Tests',
    'tests/catalog-native',
    'tests/lab-apple',
  ];
  return directories
    .filter(directory => existsSync(resolve(root, directory)))
    .flatMap(directory =>
      readdirSync(resolve(root, directory), { recursive: true })
        .filter(
          file =>
            (file === 'main.swift' || file.endsWith('/main.swift')) &&
            !file.split('/').some(part => ['build', 'node_modules', '.gradle', '.kotlin'].includes(part)),
        )
        .map(file => `${directory}/${file}`),
    )
    .sort();
}

export function gateSwiftCoverage(tasks, inventory) {
  const claimed = new Set(tasks.flatMap(task => task.entrypoints ?? []));
  return inventory.filter(suite => !claimed.has(suite)).map(suite => `${suite} has no gate check`);
}

async function main() {
  if (process.argv.length > 2) throw new Error('Usage: node scripts/check-suite-coverage.mjs');
  const { checkTasks } = await import('./check-tasks.mjs');
  const inventory = swiftSuiteInventory();
  const problems = gateSwiftCoverage(checkTasks({ root: resolve('.') }), inventory);
  if (problems.length) throw new Error(`Swift suites without a gate check:\n${problems.join('\n')}`);
  console.log(`All ${inventory.length} Swift suites have a gate check.`);
}

if (resolve(process.argv[1] ?? '') === fileURLToPath(import.meta.url)) await main();
