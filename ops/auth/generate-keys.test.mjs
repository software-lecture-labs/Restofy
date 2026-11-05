import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { createPrivateKey, createPublicKey, generateKeyPairSync, randomBytes, sign, verify } from 'node:crypto';
import { mkdtemp, mkdir, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { setupDev } from './generate-keys.mjs';

const template = await readFile(new URL('../../.env.example', import.meta.url), 'utf8');
const marker = '# RESTOFY-ACTIVE-PROFILE=dev\n';
const pair = () => generateKeyPairSync('rsa', { modulusLength: 2048 });
const staff = pair();
const mail = pair();
const encodePrivate = key => key.export({ format: 'der', type: 'pkcs8' }).toString('base64');
const encodePublic = key => key.export({ format: 'der', type: 'spki' }).toString('base64');
const secrets = ['AUTH_CSRF_SECRET', 'AUTH_MFA_ENCRYPTION_KEY', 'AUTH_RESET_HMAC_KEY',
  'AUTH_MAIL_OUTBOX_KEY', 'MAIL_PAYLOAD_ENCRYPTION_KEY'];
const existing = {
  AUTH_JWT_PRIVATE_KEY: encodePrivate(staff.privateKey),
  AUTH_MAIL_PRIVATE_KEY: encodePrivate(mail.privateKey),
  MAIL_AUTH_PUBLIC_KEY: encodePublic(mail.publicKey),
  ...Object.fromEntries(secrets.map(name => [name, randomBytes(32).toString('base64')])),
  AUTH_COOKIE_SECURE: 'false',
};

function profile(updates = existing, base = template) {
  return base.split(/\r?\n/).map(line => {
    const name = line.match(/^([A-Z_]+)=/)?.[1];
    return Object.hasOwn(updates, name) ? `${name}=${updates[name]}` : line;
  }).join('\n');
}

function values(text) {
  return Object.fromEntries(text.split(/\r?\n/).filter(line => /^[A-Z_]+=/.test(line))
    .map(line => [line.slice(0, line.indexOf('=')), line.slice(line.indexOf('=') + 1)]));
}

async function fixture(run, initial = null, active = initial === null ? null : marker + initial) {
  const root = await mkdtemp(join(tmpdir(), 'restofy-dev-keys-'));
  try {
    await writeFile(join(root, '.env.example'), template);
    if (initial !== null) await writeFile(join(root, '.env.dev'), initial);
    if (active !== null) await writeFile(join(root, '.env'), active);
    await run(root);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
}

test('fresh DEV setup generates separate signing pairs and secrets, activates profile, reports remaining settings', async () => {
  await fixture(async root => {
    const result = await setupDev(root);
    const text = await readFile(join(root, '.env.dev'), 'utf8');
    assert.equal(await readFile(join(root, '.env'), 'utf8'), marker + text);
    const generated = values(text);
    const sessionPrivate = createPrivateKey({ key: Buffer.from(generated.AUTH_JWT_PRIVATE_KEY, 'base64'), format: 'der', type: 'pkcs8' });
    const mailPrivate = createPrivateKey({ key: Buffer.from(generated.AUTH_MAIL_PRIVATE_KEY, 'base64'), format: 'der', type: 'pkcs8' });
    const mailPublic = createPublicKey({ key: Buffer.from(generated.MAIL_AUTH_PUBLIC_KEY, 'base64'), format: 'der', type: 'spki' });
    const message = Buffer.from('Auth-to-Mail contract');
    assert.equal(verify('sha256', message, mailPublic, sign('sha256', message, mailPrivate)), true);
    assert.equal(verify('sha256', message, mailPublic, sign('sha256', message, sessionPrivate)), false);
    assert.equal(sessionPrivate.asymmetricKeyDetails.modulusLength, 3072);
    assert.equal((await readFile(join(root, 'secrets/auth-staff-public.key'), 'utf8')).trim(), encodePublic(createPublicKey(sessionPrivate)));
    assert.equal(new Set(secrets.map(name => generated[name])).size, 5);
    for (const name of secrets) {
      const bytes = Buffer.from(generated[name], 'base64');
      assert.equal(bytes.length, 32);
      assert.ok(new Set(bytes).size >= 16);
    }
    assert.equal(generated.AUTH_MFA_REQUIRED, 'true');
    assert.equal(generated.AUTH_RESTAURANT_ID, '');
    assert.ok(result.missing.includes('POSTGRES_PASSWORD'));
    assert.ok(result.missing.includes('AUTH_RESTAURANT_ID'));
  });
});

test('repeat setup preserves existing credentials and unrelated settings byte-for-byte', async () => {
  const initial = profile({ ...existing, POSTGRES_PASSWORD: 'fixture-password-only' });
  await fixture(async root => {
    assert.deepEqual((await setupDev(root)).updated, []);
    assert.equal(await readFile(join(root, '.env.dev'), 'utf8'), initial);
    assert.deepEqual((await setupDev(root)).updated, []);
    assert.equal(await readFile(join(root, '.env'), 'utf8'), marker + initial);
  }, initial);
});

test('partial setup derives missing Mail public key, preserves existing private keys and backs up profile', async () => {
  const initial = profile({ ...existing, MAIL_AUTH_PUBLIC_KEY: '', AUTH_CSRF_SECRET: '' });
  await fixture(async root => {
    const result = await setupDev(root);
    const actual = values(await readFile(join(root, '.env.dev'), 'utf8'));
    assert.equal(actual.AUTH_JWT_PRIVATE_KEY, existing.AUTH_JWT_PRIVATE_KEY);
    assert.equal(actual.AUTH_MAIL_PRIVATE_KEY, existing.AUTH_MAIL_PRIVATE_KEY);
    assert.equal(actual.MAIL_AUTH_PUBLIC_KEY, existing.MAIL_AUTH_PUBLIC_KEY);
    assert.equal(await readFile(join(root, result.backup), 'utf8'), initial);
  }, initial);
});

for (const [description, changes, pattern] of [
  ['production profile', { APP_ENV: 'prod' }, /yalnız/],
  ['mixed profiles', { SPRING_PROFILES_ACTIVE: 'dev,prod' }, /yalnız/],
  ['wrong Mail public key', { MAIL_AUTH_PUBLIC_KEY: encodePublic(staff.publicKey) }, /eşleşmiyor/],
  ['orphan Mail public key', { AUTH_MAIL_PRIVATE_KEY: '' }, /özel anahtarını/],
  ['reused RSA pair', { AUTH_MAIL_PRIVATE_KEY: existing.AUTH_JWT_PRIVATE_KEY, MAIL_AUTH_PUBLIC_KEY: encodePublic(staff.publicKey) }, /ayrı/],
  ['invalid private key', { AUTH_JWT_PRIVATE_KEY: 'invalid' }, /geçersiz/],
  ['weak MFA key', { AUTH_MFA_ENCRYPTION_KEY: Buffer.alloc(32).toString('base64') }, /zayıf/],
  ['reused symmetric key', { AUTH_RESET_HMAC_KEY: existing.AUTH_CSRF_SECRET }, /farklı/],
]) {
  test(`rejects ${description} before modifying either ENV`, async () => {
    const initial = profile({ ...existing, ...changes });
    await fixture(async root => {
      await assert.rejects(setupDev(root), pattern);
      assert.equal(await readFile(join(root, '.env.dev'), 'utf8'), initial);
      assert.equal(await readFile(join(root, '.env'), 'utf8'), marker + initial);
    }, initial);
  });
}

test('protects a divergent active ENV and never switches a production profile implicitly', async () => {
  for (const active of ['# RESTOFY-ACTIVE-PROFILE=prod\nAPP_ENV=prod\n', 'manual-env=preserve\n']) {
    await fixture(async root => {
      await assert.rejects(setupDev(root), /DEV profiline ait değil/);
      assert.equal(await readFile(join(root, '.env'), 'utf8'), active);
    }, profile(), active);
  }
});

test('a non-key DEV difference is backed up and synchronized in one command', async () => {
  const initial = profile();
  const active = marker + initial.replace('SONAR_TOKEN=', 'SONAR_TOKEN=fixture-active-token');
  await fixture(async root => {
    const result = await setupDev(root);
    assert.equal(await readFile(join(root, result.activeBackup), 'utf8'), active);
    assert.equal(await readFile(join(root, '.env.dev'), 'utf8'), initial);
    assert.equal(await readFile(join(root, '.env'), 'utf8'), marker + initial);
    assert.deepEqual(result.updated, []);
  }, initial, active);
});

test('existing active keys fill empty profile fields without rotation', async () => {
  const initial = profile({ ...existing, AUTH_MFA_ENCRYPTION_KEY: '', AUTH_MAIL_PRIVATE_KEY: '' });
  const active = marker + profile();
  await fixture(async root => {
    const result = await setupDev(root);
    const actual = values(await readFile(join(root, '.env.dev'), 'utf8'));
    assert.equal(actual.AUTH_MFA_ENCRYPTION_KEY, existing.AUTH_MFA_ENCRYPTION_KEY);
    assert.equal(actual.AUTH_MAIL_PRIVATE_KEY, existing.AUTH_MAIL_PRIVATE_KEY);
    assert.equal(await readFile(join(root, result.activeBackup), 'utf8'), active);
    assert.equal(await readFile(join(root, '.env'), 'utf8'), marker + await readFile(join(root, '.env.dev'), 'utf8'));
  }, initial, active);
});

test('conflicting populated DEV keys are never replaced or synchronized', async () => {
  const initial = profile();
  const active = marker + profile({ ...existing, AUTH_MFA_ENCRYPTION_KEY: randomBytes(32).toString('base64') });
  await fixture(async root => {
    await assert.rejects(setupDev(root), /AUTH_MFA_ENCRYPTION_KEY.*farklı/);
    assert.equal(await readFile(join(root, '.env.dev'), 'utf8'), initial);
    assert.equal(await readFile(join(root, '.env'), 'utf8'), active);
  }, initial, active);
});

test('rejects duplicate ENV fields and leaves content unchanged', async () => {
  const initial = `${profile()}\nAUTH_CSRF_SECRET=\n`;
  await fixture(async root => {
    await assert.rejects(setupDev(root), /birden fazla/);
    assert.equal(await readFile(join(root, '.env.dev'), 'utf8'), initial);
  }, initial);
});

test('serializes concurrent setup and protects an existing public-key export', async () => {
  await fixture(async root => {
    await writeFile(join(root, '.env.auth-setup.lock'), '');
    await assert.rejects(setupDev(root), /kilitli/);
  }, profile());
  await fixture(async root => {
    await mkdir(join(root, 'secrets'));
    await writeFile(join(root, 'secrets/auth-staff-public.key'), 'preserve-existing-export');
    await assert.rejects(setupDev(root), /dosya korunuyor/);
    assert.equal(await readFile(join(root, 'secrets/auth-staff-public.key'), 'utf8'), 'preserve-existing-export');
  }, profile());
});

test('supports quoted keys and CRLF profiles without changing existing values', async () => {
  const initial = profile().replace(`AUTH_MAIL_PRIVATE_KEY=${existing.AUTH_MAIL_PRIVATE_KEY}`,
    `AUTH_MAIL_PRIVATE_KEY="${existing.AUTH_MAIL_PRIVATE_KEY}"`).replaceAll('\n', '\r\n');
  await fixture(async root => {
    await setupDev(root);
    assert.equal(await readFile(join(root, '.env.dev'), 'utf8'), initial);
    assert.equal(await readFile(join(root, '.env'), 'utf8'), marker.replace('\n', '\r\n') + initial);
  }, initial, marker.replace('\n', '\r\n') + initial);
});

test('CLI works from another directory, reports activation and never prints credentials', async () => {
  await fixture(async root => {
    const scripts = join(root, 'ops/auth');
    await mkdir(scripts, { recursive: true });
    const script = join(scripts, 'generate-keys.mjs');
    await writeFile(script, await readFile(new URL('./generate-keys.mjs', import.meta.url), 'utf8'));
    const result = spawnSync(process.execPath, [script], { cwd: tmpdir(), encoding: 'utf8' });
    assert.equal(result.status, 0);
    assert.match(result.stdout, /aktif .env eşitlendi/);
    assert.match(result.stdout, /AUTH_RESTAURANT_ID/);
    for (const value of Object.values(existing)) {
      if (value.length > 20) assert.equal((result.stdout + result.stderr).includes(value), false);
    }
    const text = await readFile(join(root, '.env.dev'), 'utf8');
    assert.equal(await readFile(join(root, '.env'), 'utf8'), marker + text);
    const rejected = spawnSync(process.execPath, [script, '--profile', 'prod'], { cwd: root, encoding: 'utf8' });
    assert.equal(rejected.status, 1);
    assert.equal(await readFile(join(root, '.env.dev'), 'utf8'), text);
    assert.equal((rejected.stdout + rejected.stderr).includes(existing.AUTH_JWT_PRIVATE_KEY), false);
  }, profile());
});
