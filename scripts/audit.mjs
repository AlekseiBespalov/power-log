import { spawnSync } from 'node:child_process';

const accepted = new Map([
  ['GHSA-vfj7-8cjw-p6xm', 'braces only expands the project’s own file patterns in Metro'],
  ['GHSA-86w9-cpqp-85rv', 'node-forge only verifies Expo Updates code signing, which Power Log does not use'],
]);
const report = JSON.parse(spawnSync('npm', ['audit', '--json'], { encoding: 'utf8' }).stdout);
if (!report.vulnerabilities) {
  console.error(report.error?.summary ?? 'npm audit returned no report.');
  process.exit(1);
}
const advisories = new Map();
for (const { via } of Object.values(report.vulnerabilities))
  for (const source of via) if (typeof source === 'object') advisories.set(source.url.split('/').pop(), source);
let blocked = false;
for (const [id, advisory] of advisories) {
  if (accepted.has(id)) console.log(`Accepted ${advisory.name} ${id}: ${accepted.get(id)}.`);
  else if (advisory.severity !== 'low' && advisory.severity !== 'info') {
    console.error(`${advisory.severity} ${advisory.name}: ${advisory.title} ${advisory.url}`);
    blocked = true;
  }
}
process.exit(blocked ? 1 : 0);
