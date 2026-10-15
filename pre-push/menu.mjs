import path from 'node:path';
import { createInterface } from 'node:readline';
import { fileURLToPath } from 'node:url';
import { repositoryRoot, readModules, withReportLock, withRunLog } from '../lib/quality-tools.mjs';
import { runQualityChecks, formatQualityStatus } from './quality.mjs';
import { runBackendChecks } from './backend-check.mjs';

const usage = 'Usage: pre-push.cmd [menu|full|backend|service-name] [dev|prod]';

export function parseArguments(args, modules) {
  if (args.length === 0) return { mode: 'menu', profile: 'dev' };
  if (args.length === 1 && ['--help', '-h'].includes(args[0])) return { mode: 'help', profile: 'dev' };
  // Preserve the existing non-interactive dev/prod commands.
  if (args.length === 1 && ['dev', 'prod'].includes(args[0])) return { mode: 'full', profile: args[0] };
  const profile = args[1] ?? 'dev';
  if (args.length > 2 || !['dev', 'prod'].includes(profile)) throw new Error(usage);
  if (['menu', 'full', 'backend'].includes(args[0])) return { mode: args[0], profile };
  if (modules.includes(`services/${args[0]}`)) return { mode: 'backend', service: args[0], profile };
  throw new Error(usage);
}

export async function selectFromMenu(modules, ask, log = console.log) {
  log('\n========================================\nRESTOFY TEST MENÜSÜ\n========================================');
  modules.forEach((module, index) => log(String(index + 1).padStart(2) + '. ' + module.split('/')[1]));
  log(`${modules.length + 1}. Tüm servislerin testleri (backend)`);
  log(`${modules.length + 2}. Tam kalite kontrolü (backend + frontend + Playwright + secret + HTTP + audit + Docker)`);
  log(' 0. Çıkış');
  while (true) {
    const answer = await ask('Seçiminiz (numara veya servis adı): ');
    if (answer === null) return { mode: 'cancel' };
    const value = answer.trim();
    if (['0', 'q', 'exit'].includes(value)) return { mode: 'cancel' };
    if (modules.includes(`services/${value}`)) return { mode: 'backend', service: value };
    if (/^\d+$/.test(value)) {
      const number = Number(value);
      if (number >= 1 && number <= modules.length) return { mode: 'backend', service: modules[number - 1].split('/')[1] };
      if (number === modules.length + 1) return { mode: 'backend' };
      if (number === modules.length + 2) return { mode: 'full' };
    }
    log('[ERROR] Geçerli bir menü numarası veya listelenen servis adını girin.');
  }
}

export async function runPrePush({ args = process.argv.slice(2), root = repositoryRoot, ask, interactive = process.stdin.isTTY, log = console.log, ambient = process.env, runCommand, runFull = runQualityChecks, runBackend = runBackendChecks } = {}) {
  let readline;
  try {
    const modules = readModules(root);
    let choice = parseArguments(args, modules);
    if (choice.mode === 'help') {
      log(usage);
      log('Argümansız çalıştırma menüyü açar. Servisler: ' + modules.map(module => module.split('/')[1]).join(', '));
      return { exitCode: 0 };
    }
    if (choice.mode === 'menu') {
      if (!ask && !interactive) throw new Error('Menü için etkileşimli terminal gerekir. Doğrudan auth-service, backend veya full argümanını kullanın.');
      if (!ask) {
        readline = createInterface({ input: process.stdin, output: process.stdout });
        readline.on('SIGINT', () => readline.close());
        ask = prompt => new Promise(resolve => {
          const closed = () => resolve(null);
          readline.once('close', closed);
          readline.question(prompt, answer => {
            readline.removeListener('close', closed);
            resolve(answer);
          });
        });
      }
      choice = { ...choice, ...await selectFromMenu(modules, ask, log) };
      readline?.close();
    }
    if (choice.mode === 'cancel') {
      log('İşlem iptal edildi; test çalıştırılmadı.');
      return { exitCode: 0 };
    }
    log('[SELECTED] ' + (choice.mode === 'full' ? 'Tam kalite kontrolü' : choice.service ?? 'Tüm backend servisleri'));
    const settings = { root, profile: choice.profile, ambient, runCommand, log };
    return await withReportLock(root, () => choice.mode === 'full'
      ? runFull(settings)
      : runBackend({ ...settings, service: choice.service ?? 'all' }));
  } catch (error) {
    log('[ERROR] ' + (error.code === 'EEXIST' ? 'Başka bir test veya analiz çalışması devam ediyor. Bitmesini bekleyin.' : error.message));
    return { exitCode: 1 };
  } finally {
    readline?.close();
  }
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    process.exitCode = (await withRunLog(repositoryRoot, 'pre-push', log => runPrePush({ log }),
      {log: value => console.log(formatQualityStatus(value))})).exitCode;
  } catch (error) {
    console.error('Pre-push runner could not start: ' + error.message);
    process.exitCode = 1;
  }
}
