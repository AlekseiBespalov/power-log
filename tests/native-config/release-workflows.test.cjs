const assert = require('node:assert/strict');
const fs = require('node:fs');
const test = require('node:test');
const { parse } = require('yaml');
const workflow = name => parse(fs.readFileSync(`.github/workflows/${name}.yml`, 'utf8'));

test('publishing callers run the complete check workflow at their own commit without duplicate standalone runs', () => {
  const check = workflow('check'), pages = workflow('pages'), android = workflow('android');
  assert.ok(check.on.workflow_call);
  assert.deepEqual(check.on.push, { 'branches-ignore': ['main'], 'tags-ignore': ['v*'] });
  assert.ok(Object.hasOwn(check.on, 'pull_request'));
  assert.deepEqual(pages.on.push.branches, ['main']);
  assert.deepEqual(android.on.push.tags, ['v*']);
  for (const caller of [pages, android]) {
    assert.equal(caller.jobs.checks.uses, './.github/workflows/check.yml');
    assert.equal(caller.jobs.checks.secrets, undefined);
  }
  assert.equal(pages.jobs.checks.if, undefined);
  assert.equal(android.jobs.checks.if, "startsWith(github.ref, 'refs/tags/v')");
  for (const [name, commands] of Object.entries({
    'shared-and-web': ['npm audit --audit-level=moderate', 'npm run check', 'npm run test:e2e', 'npm run test:browser', 'npm run test:public-web'],
    'swift-protocol': ['bash scripts/test-swift.sh'],
    'workout-fit': ['sh tests/workout-native/run.sh'],
  })) {
    const job = check.jobs[name];
    assert.equal(job.if, undefined);
    assert.equal(job['continue-on-error'], undefined);
    for (const command of commands) {
      const step = job.steps.find(step => step.run === command);
      assert.ok(step, command);
      assert.equal(step.if, undefined);
      assert.equal(step['continue-on-error'], undefined);
    }
    const checkout = job.steps.find(step => step.uses?.startsWith('actions/checkout@'));
    assert.ok(checkout);
    assert.equal(checkout.with?.ref, undefined);
  }
});

test('Pages deploys the tested artifact only after every check job succeeds', () => {
  const check = workflow('check'), pages = workflow('pages');
  assert.equal(check.on.workflow_call.inputs['upload-pages-artifact'].default, false);
  assert.equal(pages.jobs.checks.with['upload-pages-artifact'], true);
  assert.equal(pages.jobs.deploy.needs, 'checks');
  assert.equal(pages.jobs.deploy.if, "github.ref == 'refs/heads/main'");
  const steps = check.jobs['shared-and-web'].steps;
  const upload = steps.findIndex(step => step.uses?.startsWith('actions/upload-pages-artifact@'));
  assert.ok(upload > steps.findIndex(step => step.run === 'npm run test:public-web'));
  assert.equal(steps[upload].if, 'inputs.upload-pages-artifact');
  assert.equal(steps[upload].with.path, 'dist');
});

test('Android releases require both full checks and a signed build and never replace assets', () => {
  const { jobs } = workflow('android');
  assert.deepEqual(jobs.release.needs, ['build', 'checks']);
  assert.equal(jobs.release.if, "startsWith(github.ref, 'refs/tags/v')");
  assert.equal(jobs.build['continue-on-error'], undefined);
  assert.equal(jobs.checks['continue-on-error'], undefined);
  const checkout = jobs.build.steps.find(step => step.uses?.startsWith('actions/checkout@'));
  assert.equal(checkout.with?.ref, undefined);
  const signing = jobs.build.steps.find(step => step.run === 'npm run build:android');
  assert.equal(signing.if, jobs.release.if);
  const upload = jobs.release.steps.find(step => step.run?.includes('gh release upload')).run;
  assert.match(upload, /gh release upload "\$RELEASE_TAG" power-log\.apk SHA256SUMS\.txt\s*$/);
  assert.doesNotMatch(upload, /--clobber|gh release (delete|delete-asset)/);
});

test('builds, checks and pull requests have no publishing write permissions', () => {
  for (const name of ['check', 'pages', 'android']) {
    const config = workflow(name);
    assert.deepEqual(config.permissions, { contents: 'read' });
    for (const [id, job] of Object.entries(config.jobs)) {
      if (name === 'pages' && id === 'deploy') assert.deepEqual(job.permissions, { pages: 'write', 'id-token': 'write' });
      else if (name === 'android' && id === 'release') assert.deepEqual(job.permissions, { contents: 'write' });
      else assert.deepEqual(job.permissions ?? config.permissions, { contents: 'read' });
    }
    assert.equal(config.on.pull_request_target, undefined);
  }
});

test('local tooling guidance names the supported Android prerequisites', () => {
  const script = fs.readFileSync('scripts/check-tools.mjs', 'utf8');
  assert.match(script, /JDK 17/);
  assert.match(script, /SDK\/platform tools/);
  assert.match(script, /JAVA_HOME and ANDROID_HOME/);
  assert.doesNotMatch(script, /Android tooling is deferred/);
});
