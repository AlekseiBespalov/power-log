const assert = require('node:assert/strict');
const { spawnSync } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const test = require('node:test');
const { configureCoreSettings } = require('../../plugins/with-power-log-core');

test('Android keeps included build histories independent and core artifacts outside standalone outputs', t => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'power-log-android-core-'));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  const write = (file, content) => {
    const target = path.join(root, file);
    fs.mkdirSync(path.dirname(target), { recursive: true });
    fs.writeFileSync(target, content);
  };
  for (const file of [
    'settings.gradle.kts',
    'build.gradle.kts',
    'gradle.properties',
    'gradlew',
    'gradle/wrapper/gradle-wrapper.jar',
    'gradle/wrapper/gradle-wrapper.properties',
  ])
    write(`shared/${file}`, fs.readFileSync(`shared/${file}`));
  fs.chmodSync(path.join(root, 'shared/gradlew'), 0o755);
  write('shared/src/commonMain/kotlin/Core.kt', 'class Core\n');
  for (const name of ['First', 'Second']) {
    write(`${name}/settings.gradle`, `rootProject.name = '${name}'\n`);
    write(
      `${name}/build.gradle`,
      `plugins { id 'java-gradle-plugin' }
gradlePlugin {
    plugins {
        fixture {
            id = 'fixture.${name}'
            implementationClass = 'fixture.${name}'
        }
    }
}
`,
    );
    write(
      `${name}/src/main/java/fixture/${name}.java`,
      `package fixture;
public class ${name} implements org.gradle.api.Plugin<org.gradle.api.Project> {
    public void apply(org.gradle.api.Project project) {}
}
`,
    );
  }
  write(
    'android/settings.gradle',
    configureCoreSettings("rootProject.name = 'android'\nincludeBuild('../First')\nincludeBuild('../Second')\n"),
  );
  write(
    'android/build.gradle',
    `plugins { id 'java'; id 'fixture.First'; id 'fixture.Second' }
repositories { mavenCentral() }
dependencies { implementation 'app.powerlog:power-log-core:0.3.0' }
def coreFiles = configurations.runtimeClasspath
tasks.register('checkCoreArtifact') {
    inputs.files(coreFiles)
    doLast {
        def core = coreFiles.files.find { it.name.startsWith('power-log-core-jvm-') }
        assert core != null : 'Missing substituted core artifact'
        assert core.canonicalFile.parentFile == file('build/power-log-core/libs').canonicalFile : core
        println 'Resolved Android core: ' + core
    }
}
`,
  );
  const args = [
    '--no-daemon',
    '--console=plain',
    ...(process.env.POWER_LOG_GRADLE_OFFLINE === '1' ? ['--offline'] : []),
  ];
  const run = (project, tasks) => {
    const result = spawnSync(path.join(root, 'shared/gradlew'), ['-p', path.join(root, project), ...tasks, ...args], {
      encoding: 'utf8',
      timeout: 180000,
      env: { ...process.env, GRADLE_OPTS: `${process.env.GRADLE_OPTS ?? ''} -Dorg.gradle.daemon=false` },
    });
    assert.equal(result.status, 0, `${result.error ?? ''}\n${result.stdout}\n${result.stderr}`);
    return result.stdout;
  };
  const output = run('android', ['checkCoreArtifact']);
  assert.match(output, /Resolved Android core:/);
  // The next invocation used to delete another included plugin's classes while rebuilding its jar.
  assert.match(run('android', ['checkCoreArtifact']), /Resolved Android core:/);
  for (const name of ['First', 'Second']) {
    assert.ok(fs.existsSync(path.join(root, `${name}/.gradle`)));
    assert.ok(fs.existsSync(path.join(root, `${name}/build/classes/java/main/fixture/${name}.class`)));
  }
  for (const directory of ['shared/build', 'shared/.kotlin'])
    assert.equal(fs.existsSync(path.join(root, directory)), false, directory);
  assert.ok(fs.existsSync(path.join(root, 'shared/.gradle')));
  assert.ok(fs.existsSync(path.join(root, 'android/.gradle')));
  assert.ok(fs.existsSync(path.join(root, 'android/build/power-log-core/libs/power-log-core-jvm-0.3.0.jar')));
  run('shared', ['jvmJar']);
  assert.ok(fs.existsSync(path.join(root, 'shared/build/libs/power-log-core-jvm-0.3.0.jar')));
  assert.ok(fs.existsSync(path.join(root, 'shared/.gradle')));
});
