import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { mavenUsesJava25, prepareBackendMaven } from './backend-tools.mjs';

test('Java 25 detection accepts bare, patch and ANSI versions and rejects other majors', () => {
  for (const version of ['25', '25.0.1', '25, vendor: test', '\u001b[1m25.0.1\u001b[0m']) {
    assert.equal(mavenUsesJava25('Java version: ' + version), true);
  }
  for (const version of ['21.0.1', '27', '250.0.1', 'unknown']) {
    assert.equal(mavenUsesJava25('Java version: ' + version), false);
  }
});

test('Maven selection preserves caller env and uses the same JDK and root for verify and analysis', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'restofy-sonar-java-'));
  try {
    const home = path.join(root, 'JDK 25');
    fs.mkdirSync(path.join(home, 'bin'), { recursive: true });
    for (const name of ['java', 'javac']) fs.writeFileSync(path.join(home, 'bin', name), 'fixture');
    const env = { PATH: '/original', JAVA_HOME: '/jdk27' };
    const calls = [];
    const runCommand = async (command, args, settings) => {
      calls.push({ command, args, ...settings, env: { ...settings.env } });
      return { code: 0, output: 'Java version: ' + (settings.env.JAVA_HOME === home ? '25' : '27') };
    };
    const maven = await prepareBackendMaven({ root, env, runCommand, platform: 'darwin', candidates: [home] });
    await maven.run(['-f', 'backend/pom.xml', 'verify']);
    await maven.run(['-f', 'backend/pom.xml', 'sonar:sonar']);
    assert.deepEqual(env, { PATH: '/original', JAVA_HOME: '/jdk27' });
    assert.ok(calls.every(call => call.cwd === root));
    assert.ok(calls.slice(1).every(call => call.env.JAVA_HOME === home && call.env.PATH.startsWith(path.join(home, 'bin'))));
  } finally { fs.rmSync(root, { recursive: true, force: true }); }
});

test('POSIX wrapper fallback keeps the parent reactor and handles paths containing spaces', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'restofy sonar wrapper '));
  try {
    const wrapper = path.join(root, 'backend/services/menu-service/mvnw');
    fs.mkdirSync(path.dirname(wrapper), { recursive: true });
    fs.writeFileSync(wrapper, 'fixture');
    const calls = [];
    const maven = await prepareBackendMaven({ root, env: {}, platform: 'darwin', candidates: [],
      runCommand: async (command, args, settings) => {
        calls.push({ command, args, cwd: settings.cwd });
        return command === 'mvn' ? { code: 1, output: 'spawn mvn ENOENT' } : { code: 0, output: 'Java version: 25.0.1' };
      },
    });
    await maven.run(['-f', 'backend/pom.xml', 'verify']);
    assert.deepEqual(calls.at(-1), { command: 'sh', args: [wrapper, '-f', 'backend/pom.xml', 'verify'], cwd: root });
  } finally { fs.rmSync(root, { recursive: true, force: true }); }
});

test('unavailable Maven or wrong Java cannot pass validation', async () => {
  for (const result of [{ code: 1, output: 'Maven unavailable' }, { code: 0, output: 'Java version: 21.0.1' }]) {
    await assert.rejects(prepareBackendMaven({ root: '/nonexistent', env: {}, candidates: [], platform: 'linux',
      runCommand: async () => result,
    }), /Maven/);
  }
});
