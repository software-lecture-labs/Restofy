import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { execute, withRunLog } from './quality-tools.mjs';

async function scratch(action) {
  const prefix = path.join(os.tmpdir(), 'restofy-log-test-');
  const root = fs.mkdtempSync(prefix);
  try {
    await action(root);
  } finally {
    if (!path.resolve(root).startsWith(path.resolve(prefix))) throw new Error('Invalid scratch path');
    fs.rmSync(root, { recursive: true, force: true });
  }
}

test('child stdout/stderr are mirrored and redacted while exit code and previous logs survive', async () => {
  await scratch(async root => {
    const terminal = [];
    const secret = 'fixture-token-no-account';
    const result = await withRunLog(root, 'pre-push', async log => {
      return execute(process.execPath, ['-e', `process.stdout.write('stdout ${secret}\\n'); process.stderr.write('stderr ${secret}'); process.exitCode=2;`], {
        cwd: root, env: process.env, log, secrets: [secret],
      });
    }, { log: line => terminal.push(line), secrets: [secret] });
    assert.equal(result.code, 2);
    const [firstName] = fs.readdirSync(path.join(root, 'logs'));
    const firstPath = path.join(root, 'logs', firstName);
    const firstText = fs.readFileSync(firstPath, 'utf8');
    assert.match(firstText, /stdout \[REDACTED\]/);
    assert.match(firstText, /stderr \[REDACTED\]/);
    assert.ok(!firstText.includes(secret));
    assert.equal(firstText, terminal.join('\n') + '\n');
    const second = await withRunLog(root, 'pre-push', async log => {
      log('second run');
      return { exitCode: 1 };
    }, { log: () => {} });
    assert.equal(second.exitCode, 1);
    assert.equal(fs.readdirSync(path.join(root, 'logs')).length, 2);
    assert.equal(fs.readFileSync(firstPath, 'utf8'), firstText);
  });
});

test('unexpected failure is recorded safely and still rejects the command', async () => {
  await scratch(async root => {
    const secret = 'fixture-private-value';
    await assert.rejects(withRunLog(root, 'sonarqube-scan', async () => {
      throw new Error(`failed ${secret}`);
    }, { log: () => {}, secrets: [secret] }), /failed/);
    const [filename] = fs.readdirSync(path.join(root, 'logs'));
    const text = fs.readFileSync(path.join(root, 'logs', filename), 'utf8');
    assert.match(text, /\[ERROR\] failed \[REDACTED\]/);
    assert.ok(!text.includes(secret));
  });
});
