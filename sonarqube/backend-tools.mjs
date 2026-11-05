import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { execute } from '../lib/quality-tools.mjs';

// Maven's JVM is independent of the scanner's provisioned runtime.
export function mavenUsesJava25(output) {
  const plain = output.replace(/\u001b\[[0-9;]*m/g, '');
  return /Java version:\s*25(?=[.,\s]|$)/.test(plain);
}

function jdkCandidates(platform, env) {
  const scan = (dir, suffix = '') => {
    try {
      return fs.readdirSync(dir).filter(name => /(^|\D)25(\D|$)/.test(name))
        .sort().reverse().map(name => path.join(dir, name, suffix));
    } catch { return []; }
  };
  if (platform === 'darwin') return [
    '/opt/homebrew/opt/openjdk@25', '/usr/local/opt/openjdk@25',
    ...scan('/Library/Java/JavaVirtualMachines', 'Contents/Home'),
    ...scan(path.join(os.homedir(), 'Library/Java/JavaVirtualMachines'), 'Contents/Home'),
  ];
  if (platform === 'win32') return ['Eclipse Adoptium', 'Java', 'Microsoft', 'Amazon Corretto', 'Zulu', 'BellSoft']
    .flatMap(vendor => scan(path.join(env.ProgramFiles || 'C:\\Program Files', vendor)));
  return scan('/usr/lib/jvm');
}

export async function prepareBackendMaven({ root, env, runCommand = execute, log = () => {},
  platform = process.platform, candidates = jdkCandidates(platform, env) }) {
  // No mutation of the caller's environment or frontend/scanner runtime settings.
  const backendEnv = { ...env };
  const invoke = (command, args, capture = false) => runCommand(command, args, {
    cwd: root, env: backendEnv, capture, secrets: [env.SONAR_TOKEN], log,
  });
  let command = platform === 'win32' ? 'mvn.cmd' : 'mvn';
  let prefix = [];
  let version = await invoke(command, ['-version'], true);
  if (version.code !== 0 && platform !== 'win32') {
    // Existing service wrapper bootstraps Maven; -f still selects the entire parent reactor.
    const wrapper = path.join(root, 'backend/services/menu-service/mvnw');
    if (fs.existsSync(wrapper)) {
      command = 'sh';
      prefix = [wrapper];
      log('[INFO] Maven unavailable; trying the existing backend Maven Wrapper.');
      version = await invoke(command, [...prefix, '-version'], true);
    }
  }
  if (version.code !== 0 || !mavenUsesJava25(version.output)) {
    for (const home of candidates) {
      const executable = platform === 'win32' ? '.exe' : '';
      if (!['java', 'javac'].every(name => fs.existsSync(path.join(home, 'bin', name + executable)))) continue;
      backendEnv.JAVA_HOME = home;
      backendEnv.PATH = path.join(home, 'bin') + path.delimiter + (env.PATH || '');
      version = await invoke(command, [...prefix, '-version'], true);
      if (version.code === 0 && mavenUsesJava25(version.output)) {
        log('[INFO] Backend Maven uses JDK 25 from ' + home);
        break;
      }
    }
  }
  if (version.code !== 0) throw new Error('Backend Maven/Java unavailable: ' + version.output);
  if (!mavenUsesJava25(version.output)) {
    const actual = version.output.match(/Java version:\s*([^,\s]+)/)?.[1] || 'unknown';
    throw new Error('Backend Maven must use JDK 25; detected Java ' + actual + '. Install JDK 25 or set JAVA_HOME to it.');
  }
  return { run: (args, capture = false) => invoke(command, [...prefix, ...args], capture) };
}
