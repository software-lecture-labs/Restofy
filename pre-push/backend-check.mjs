import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { repositoryRoot, execute, backendTests, backendResults, readModules, clearBackendReports } from '../lib/quality-tools.mjs';

const JAVA_MAJOR = '25';
const MAVEN_HINT = 'Install Maven 3.9+ and put mvn on PATH (macOS: brew install maven; Windows: winget install Apache.Maven or choco install maven).';
const hasJavaMajor = (name) => new RegExp(`(^|[^0-9])${JAVA_MAJOR}([^0-9]|$)`).test(name);
const mavenJava = (output) => output.match(/Java version:\s*([^,\s]+)/)?.[1];

/** Directories that may hold a JDK 25, most specific first. Candidates are validated by findJdk25. */
export function jdk25Candidates({ platform = process.platform, env = process.env, homeDir = os.homedir(), readDir = (dir) => fs.readdirSync(dir) } = {}) {
  const scan = (dir, suffix = '') => {
    try { return readDir(dir).filter(hasJavaMajor).sort().reverse().map((name) => path.join(dir, name, suffix)); }
    catch { return []; }
  };
  if (platform === 'darwin') {
    return [
      // Homebrew installs openjdk@25 "keg-only", so neither java_home nor PATH sees it.
      '/opt/homebrew/opt/openjdk@25', '/usr/local/opt/openjdk@25',
      ...scan('/Library/Java/JavaVirtualMachines', 'Contents/Home'),
      ...scan(path.join(homeDir, 'Library/Java/JavaVirtualMachines'), 'Contents/Home'),
    ];
  }
  if (platform === 'win32') {
    const programFiles = env.ProgramFiles || 'C:\\Program Files';
    return ['Eclipse Adoptium', 'Java', 'Microsoft', 'Amazon Corretto', 'Zulu', 'BellSoft'].flatMap((vendor) => scan(path.join(programFiles, vendor)));
  }
  return scan('/usr/lib/jvm');
}

export function findJdk25(options = {}) {
  const exists = options.exists ?? fs.existsSync;
  const executable = (options.platform ?? process.platform) === 'win32' ? 'java.exe' : 'java';
  return jdk25Candidates(options).find((home) => exists(path.join(home, 'bin', executable))) ?? null;
}

/**
 * Maven must run on JDK 25. When it does not (typically JAVA_HOME is unset and the newest Homebrew
 * JDK is picked), a locally installed JDK 25 is used for the child processes of this check only:
 * the caller's shell, JAVA_HOME and profile files are never modified.
 */
export async function requireMaven25(run, mvn, root, { env, log = () => {}, findJdk = findJdk25 } = {}) {
  let version = await run(mvn, ['-version'], root, true);
  if (version.code !== 0) throw new Error('Maven/Java unavailable: ' + version.output + ' ' + MAVEN_HINT);
  if (!/Java version:\s*25[.,]/.test(version.output) && env) {
    const home = findJdk({ env });
    if (home) {
      log(`[INFO] Maven runs on Java ${mavenJava(version.output) ?? 'unknown'}; using JDK 25 from ${home} for this check.`);
      env.JAVA_HOME = home;
      version = await run(mvn, ['-version'], root, true);
      if (version.code !== 0) throw new Error('Maven/Java unavailable: ' + version.output);
    }
  }
  if (!/Java version:\s*25[.,]/.test(version.output)) {
    throw new Error(`Maven must use JDK 25 but reports Java ${mavenJava(version.output) ?? 'unknown'}; install a JDK 25 or set JAVA_HOME to it`);
  }
}

export async function runBackendChecks({ root = repositoryRoot, service = 'all', ambient = process.env, runCommand = execute, log = console.log } = {}) {
  const services = {};
  const started = Date.now();
  let buildPassed = false;
  try {
    const available = readModules(root);
    const selected = service === 'all' ? available : available.filter(module => module === `services/${service}`);
    if (!selected.length) throw new Error('Unknown backend service; use a service listed in the menu');
    for (const module of selected) services[module.split('/')[1]] = { tests: 'NOT RUN', coverage: 'NOT RUN', testCount: 0, lineCoverage: null };
    log('[SCOPE] ' + (service === 'all' ? 'Tüm backend servisleri' : service));
    const env = Object.fromEntries(Object.entries(ambient).filter(([name]) => !name.startsWith('SONAR')));
    const run = (command, args, cwd = root, capture = false) => runCommand(command, args, { cwd, env, capture, log });
    const mvn = process.platform === 'win32' ? 'mvn.cmd' : 'mvn';
    await requireMaven25(run, mvn, root, { env, log });
    clearBackendReports(root, selected);
    const args = ['-B', '-ntp', '-f', 'backend/pom.xml', '--fail-at-end'];
    if (service !== 'all') args.push('-pl', selected[0]);
    args.push('clean', 'verify');
    const result = await run(mvn, args);
    buildPassed = result.code === 0;
    if (!buildPassed) log('[ERROR] Maven verify failed with exit code ' + result.code);
    for (const module of selected) {
      const row = services[module.split('/')[1]];
      try {
        row.testCount = backendTests(root, [module], started);
        row.tests = 'PASS';
      } catch (error) {
        row.tests = 'FAIL';
        row.error = error.message;
        log('[ERROR] ' + module + ': ' + error.message);
        continue;
      }
      try {
        const report = backendResults(root, [module], started);
        row.lineCoverage = report.lineCoverage;
        row.coverage = report.lineCoverage >= 80 ? 'PASS' : 'FAIL';
      } catch (error) {
        row.coverage = 'FAIL';
        row.error = error.message;
        log('[ERROR] ' + module + ': ' + error.message);
      }
    }
  } catch (error) {
    log('[ERROR] ' + error.message);
  }
  const passed = buildPassed && Object.keys(services).length > 0 && Object.values(services).every(row => row.tests === 'PASS' && row.coverage === 'PASS');
  log('\n========================================\nRESTOFY BACKEND TEST CHECK\n========================================');
  log('Service'.padEnd(24) + 'Tests       Coverage    Count    Lines (target 80%)');
  for (const [name, row] of Object.entries(services)) {
    log(name.padEnd(24) + row.tests.padEnd(12) + row.coverage.padEnd(12) + String(row.testCount).padEnd(9) + (row.lineCoverage === null ? 'N/A' : row.lineCoverage.toFixed(2) + '%'));
  }
  log('Maven Verify             ' + (buildPassed ? 'PASS' : 'FAIL'));
  log(passed ? 'BACKEND TEST CHECK PASSED' : 'BACKEND TEST CHECK FAILED');
  log("Bu sonuç seçilen backend kapsamına aittir. Tam kalite kontrolü için 'full' kullanın.");
  return { exitCode: passed ? 0 : 1, services, buildPassed };
}
