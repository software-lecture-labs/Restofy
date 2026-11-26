import { test } from 'node:test';
import assert from 'node:assert/strict';
import { trackedSensitiveFiles, checkTrackedSecrets } from './ci-secrets.mjs';

test('only sensitive tracked paths are reported; example templates pass', () => {
  const output = ['README.md', '.env.example', 'frontend/.env.local', 'ops/key.pem', 'src/app.ts', ''].join('\0');
  assert.deepEqual(trackedSensitiveFiles(output), ['frontend/.env.local', 'ops/key.pem']);
});

test('a clean tree passes and logs no error', () => {
  const lines = [];
  assert.equal(checkTrackedSecrets(() => 'README.md\0.env.example\0', line => lines.push(line)), true);
  assert.deepEqual(lines, ['No tracked sensitive files.']);
});

test('a tracked secret fails with one quoted error line per file and no contents', () => {
  const lines = [];
  const name = 'odd\n::warning::x/.env';
  assert.equal(checkTrackedSecrets(() => `.env\0secrets/db.txt\0${name}\0`, line => lines.push(line)), false);
  assert.deepEqual(lines, [
    '::error::Sensitive file is tracked in Git: ".env"',
    '::error::Sensitive file is tracked in Git: "secrets/db.txt"',
    '::error::Sensitive file is tracked in Git: "odd\\n::warning::x/.env"',
  ]);
  assert.ok(lines.every(line => !line.includes('\n')));
});
