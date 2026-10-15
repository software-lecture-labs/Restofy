import http from 'node:http';
import { setTimeout as delay } from 'node:timers/promises';

// Check committed/staged filenames without reading or printing secret values.
export function sensitiveFile(filename) {
  const normalized = filename.replaceAll('\\', '/');
  const leaf = normalized.split('/').at(-1);
  if (/^\.env(?:\..+)?$/i.test(leaf) && !/^\.env(?:\..+)?\.example$/i.test(leaf)) return true;
  return /\.(pem|key|p12|pfx|jks|keystore)$/i.test(leaf)
    || /(?:^|\/)(secrets|\.aws)(?:\/|$)/i.test(normalized)
    || /^(credentials|service-account)\.json$/i.test(leaf);
}

export async function checkGitSecrets(run, log) {
  const results = await Promise.all([
    run('git', ['ls-files', '--cached', '-z'], undefined, true),
    run('git', ['diff', '--cached', '--name-only', '--diff-filter=ACMR', '-z'], undefined, true),
    run('git', ['diff', '--check'], undefined, true),
    run('git', ['diff', '--cached', '--check'], undefined, true),
  ]);
  if (results.some(result => result.code !== 0)) throw new Error('Git checks failed; inspect git diff --check and git diff --cached --check locally.');
  const forbidden = [...new Set(results.slice(0, 2).flatMap(result => result.output.split('\0')).filter(Boolean).filter(sensitiveFile))];
  for (const filename of forbidden) log('[SENSITIVE FILE] ' + JSON.stringify(filename));
  if (forbidden.length) throw new Error('Sensitive files are tracked or staged in Git.');
  log('Git whitespace and tracked/staged sensitive-file checks passed.');
  return true;
}

// Node fetch blocks some valid local service ports (5060/5061); use node:http.
export function readHealth(url, timeoutMs = 3000) {
  return new Promise((resolve, reject) => {
    const parsed = new URL(url);
    if (parsed.protocol !== 'http:' || parsed.hostname !== '127.0.0.1' || parsed.username || parsed.password) {
      reject(new Error('Health requests must use loopback HTTP without credentials.'));
      return;
    }
    const request = http.get(parsed, response => {
      let body = '';
      response.setEncoding('utf8');
      response.on('data', chunk => {
        body += chunk;
        if (body.length > 64 * 1024) request.destroy(new Error('Health response too large.'));
      });
      response.on('error', reject);
      response.on('end', () => {
        try {
          if (response.statusCode !== 200) throw new Error('HTTP ' + response.statusCode);
          let health;
          try { health = JSON.parse(body); }
          catch { throw new Error('Health response is not valid JSON.'); }
          if (health?.status !== 'UP') throw new Error('Health status is not UP.');
          resolve(true);
        } catch (error) { reject(error); }
      });
    });
    const timer = setTimeout(() => request.destroy(new Error('Health request timed out.')), timeoutMs);
    request.on('close', () => clearTimeout(timer));
    request.on('error', reject);
  });
}

export async function checkBackendHealth({ run, profile, modules, probe = readHealth, log, timeoutMs = 30_000 }) {
  const base = ['compose', '--env-file', `.env.${profile}`, '-f', 'docker-compose.yml'];
  const configResult = await run('docker', [...base, 'config', '--format', 'json'], undefined, true);
  if (configResult.code !== 0) throw new Error('Cannot resolve local Compose health endpoints.');
  let model;
  try { model = JSON.parse(configResult.output); }
  catch { throw new Error('Cannot parse the resolved Compose model.'); }
  // Do not log the resolved model: it contains environment secrets.
  const ps = await run('docker', [...base, 'ps', '--all', '--format', 'json'], undefined, true);
  if (ps.code !== 0) throw new Error('Cannot inspect running services. Start the selected backend environment with Docker Compose.');
  const trimmed = ps.output.trim();
  let containers;
  try { containers = !trimmed ? [] : trimmed.startsWith('[') ? JSON.parse(trimmed) : trimmed.split(/\r?\n/).filter(Boolean).map(line => JSON.parse(line)); }
  catch { throw new Error('Cannot parse running Compose services.'); }
  const results = await Promise.all(modules.map(async module => {
    const service = module.split('/')[1];
    const row = { service, status: 'FAIL' };
    try {
      if (!containers.some(container => container.Service === service && container.State === 'running' && container.Project === model.name)) {
        throw new Error('Service is not running in the selected Compose project.');
      }
      const port = model.services[service]?.ports?.find(item => Number(item.target) === Number(model.services[service].environment.BACKEND_INTERNAL_PORT ?? 8080));
      if (!port || !/^\d+$/.test(String(port.published)) || Number(port.published) < 1 || Number(port.published) > 65535) throw new Error('Missing valid local HTTP port.');
      const url = `http://127.0.0.1:${port.published}/actuator/health`;
      const deadline = Date.now() + timeoutMs;
      while (true) {
        try { await probe(url, Math.min(3000, Math.max(1, deadline - Date.now()))); break; }
        catch (error) {
          if (Date.now() >= deadline) throw error;
          await delay(Math.min(500, deadline - Date.now()));
        }
      }
      row.status = 'PASS';
    } catch (error) { row.error = error.message; }
    return row;
  }));
  for (const row of results) log(`${row.service.padEnd(24)} HTTP ${row.status}${row.error ? ': ' + row.error : ''}`);
  return { passed: results.every(row => row.status === 'PASS'), services: results };
}

export function playwrightResults(report) {
  const tests = [];
  const visit = suites => {
    for (const suite of suites ?? []) {
      for (const spec of suite.specs ?? []) for (const test of spec.tests ?? []) tests.push(test);
      visit(suite.suites);
    }
  };
  visit(report.suites);
  const group = project => {
    const selected = tests.filter(test => test.projectName === project);
    return {
      tests: selected.length,
      passed: selected.length > 0 && selected.every(test => test.status === 'expected' && test.results?.length === 1 && test.results[0].status === 'passed'),
    };
  };
  const errors = (report.errors ?? []).length;
  return { http: group('http'), browser: group('chromium'), errors };
}
