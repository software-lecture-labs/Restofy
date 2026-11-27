// CI check: fail when a tracked file looks like a secret (.env, private keys, credentials).
// Same rule as the local pre-push check. Only filenames are inspected and printed, never contents.
import { execFileSync } from 'node:child_process';
import { pathToFileURL } from 'node:url';
import { sensitiveFile } from './runtime-checks.mjs';

export function trackedSensitiveFiles(lsFilesOutput) {
  return lsFilesOutput.split('\0').filter(Boolean).filter(sensitiveFile);
}

export function checkTrackedSecrets(listTracked, log) {
  const forbidden = trackedSensitiveFiles(listTracked());
  // JSON.stringify keeps odd filenames on one line, so they cannot inject workflow commands.
  for (const filename of forbidden) log(`::error::Sensitive file is tracked in Git: ${JSON.stringify(filename)}`);
  if (forbidden.length === 0) log('No tracked sensitive files.');
  return forbidden.length === 0;
}

if (import.meta.url === pathToFileURL(process.argv[1]).href) {
  const listTracked = () => execFileSync('git', ['ls-files', '--cached', '-z'], { encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 });
  process.exitCode = checkTrackedSecrets(listTracked, console.log) ? 0 : 1;
}
