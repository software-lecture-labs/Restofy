import { test } from 'node:test';
import assert from 'node:assert/strict';
import { jdk25Candidates, findJdk25, requireMaven25 } from './backend-check.mjs';

const MAVEN_ON_27 = { code: 0, output: 'Apache Maven 3.10.0\nJava version: 27, vendor: Homebrew, runtime: /opt/homebrew/Cellar/openjdk/27' };
const MAVEN_ON_25 = { code: 0, output: 'Apache Maven 3.10.0\nJava version: 25.0.4.1, vendor: Homebrew' };

// A fake file system: directory listings and the files that "exist".
const readDirFrom = (listings) => (dir) => {
  if (!(dir in listings)) throw new Error('ENOENT ' + dir);
  return listings[dir];
};

test('macOS lists the keg-only Homebrew JDK first and only JDK 25 directories from the JVM folders', () => {
  const candidates = jdk25Candidates({
    platform: 'darwin', homeDir: '/Users/dev',
    readDir: readDirFrom({
      '/Library/Java/JavaVirtualMachines': ['temurin-21.jdk', 'temurin-25.jdk', 'jdk-125.jdk', 'zulu-2025.jdk'],
      '/Users/dev/Library/Java/JavaVirtualMachines': ['corretto-25.jdk'],
    }),
  });
  assert.equal(candidates[0], '/opt/homebrew/opt/openjdk@25');
  assert.ok(candidates.includes('/usr/local/opt/openjdk@25'));
  assert.ok(candidates.some((home) => home.endsWith('temurin-25.jdk/Contents/Home')));
  assert.ok(candidates.some((home) => home.endsWith('corretto-25.jdk/Contents/Home')));
  assert.ok(!candidates.some((home) => /temurin-21|jdk-125|2025/.test(home)));
});

test('Linux scans /usr/lib/jvm and ignores other Java versions', () => {
  const candidates = jdk25Candidates({
    platform: 'linux',
    readDir: readDirFrom({ '/usr/lib/jvm': ['java-21-openjdk-amd64', 'java-25-openjdk-amd64', 'temurin-25-jdk-amd64'] }),
  });
  assert.equal(candidates.length, 2);
  assert.ok(candidates.every((home) => home.includes('25') && home.startsWith('/usr/lib/jvm')));
});

test('Windows scans the vendor folders under Program Files and checks java.exe', () => {
  const candidates = jdk25Candidates({
    platform: 'win32', env: { ProgramFiles: 'D:\\Programs' },
    readDir: (dir) => (dir.includes('Eclipse Adoptium') ? ['jdk-25.0.1.8-hotspot', 'jdk-21.0.5.11-hotspot'] : (() => { throw new Error('ENOENT'); })()),
  });
  assert.equal(candidates.length, 1);
  assert.ok(candidates[0].includes('jdk-25.0.1.8-hotspot'));
  assert.ok(candidates[0].includes('D:'));
  const probed = [];
  findJdk25({ platform: 'win32', env: { ProgramFiles: 'D:\\Programs' }, readDir: () => ['jdk-25'], exists: (file) => { probed.push(file); return false; } });
  assert.ok(probed.length > 0 && probed.every((file) => file.endsWith('java.exe')));
});

test('findJdk25 skips candidates without bin/java and returns null when nothing matches', () => {
  const options = { platform: 'darwin', homeDir: '/h', readDir: readDirFrom({ '/Library/Java/JavaVirtualMachines': ['temurin-25.jdk'] }) };
  assert.equal(findJdk25({ ...options, exists: (file) => file === '/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home/bin/java' }),
    '/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home');
  assert.equal(findJdk25({ ...options, exists: () => false }), null);
});

test('Maven on Java 27 is re-run with a detected JDK 25 for this check only', async () => {
  const env = { PATH: '/usr/bin', SONAR_TOKEN: undefined };
  const seenJavaHome = [];
  const logs = [];
  const run = async (command, args, cwd, capture) => {
    assert.deepEqual([command, args, capture], ['mvn', ['-version'], true]);
    seenJavaHome.push(env.JAVA_HOME);
    return env.JAVA_HOME === '/jdk/25' ? MAVEN_ON_25 : MAVEN_ON_27;
  };
  await requireMaven25(run, 'mvn', '/repo', { env, log: (line) => logs.push(line), findJdk: () => '/jdk/25' });
  assert.deepEqual(seenJavaHome, [undefined, '/jdk/25']);
  assert.equal(env.JAVA_HOME, '/jdk/25');
  assert.ok(logs.some((line) => line.includes('Java 27') && line.includes('/jdk/25')));
});

test('a wrong JAVA_HOME from the shell is replaced by a JDK 25 for the check', async () => {
  const env = { JAVA_HOME: '/jdk/27' };
  const run = async () => (env.JAVA_HOME === '/jdk/25' ? MAVEN_ON_25 : MAVEN_ON_27);
  await requireMaven25(run, 'mvn', '/repo', { env, findJdk: () => '/jdk/25' });
  assert.equal(env.JAVA_HOME, '/jdk/25');
});

test('Maven already on Java 25 is accepted without touching JAVA_HOME or searching', async () => {
  const env = { JAVA_HOME: '/user/choice' };
  let runs = 0;
  await requireMaven25(async () => { runs++; return MAVEN_ON_25; }, 'mvn', '/repo', { env, findJdk: () => assert.fail('must not search') });
  assert.equal(runs, 1);
  assert.equal(env.JAVA_HOME, '/user/choice');
});

test('no JDK 25 anywhere is still a clear failure that names the Java Maven uses', async () => {
  const env = {};
  await assert.rejects(
    requireMaven25(async () => MAVEN_ON_27, 'mvn', '/repo', { env, findJdk: () => null }),
    /Maven must use JDK 25 but reports Java 27/);
  assert.equal(env.JAVA_HOME, undefined);
});

test('without an environment to adjust the original strict check is kept', async () => {
  await assert.rejects(requireMaven25(async () => MAVEN_ON_27, 'mvn', '/repo'), /Maven must use JDK 25/);
});

test('a missing Maven reports how to install it and never searches for a JDK', async () => {
  await assert.rejects(
    requireMaven25(async () => ({ code: 1, output: 'spawn mvn ENOENT' }), 'mvn', '/repo', { env: {}, findJdk: () => assert.fail('must not search') }),
    /spawn mvn ENOENT.*brew install maven.*winget install Apache\.Maven/s);
});

test('a JDK 25 that still does not make Maven report Java 25 fails instead of looping', async () => {
  let runs = 0;
  await assert.rejects(
    requireMaven25(async () => { runs++; return MAVEN_ON_27; }, 'mvn', '/repo', { env: {}, findJdk: () => '/jdk/broken' }),
    /Maven must use JDK 25/);
  assert.equal(runs, 2);
});
