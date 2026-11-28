import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const root = fileURLToPath(new URL('../../../', import.meta.url));
const keys = [
  'ORDER_QR_SERVICE_PUBLIC_KEYS',
  'ORDER_QR_SERVICE_RESTAURANTS',
  'ORDER_QR_CALLER_KEY_ID',
  'ORDER_QR_CALLER_PRIVATE_KEY',
  'ORDER_QR_CALLER_RESTAURANT_ID',
  'ORDER_MENU_CALLER_KEY_ID',
  'ORDER_MENU_CALLER_PRIVATE_KEY',
  'ORDER_MENU_CALLER_RESTAURANT_ID',
];
const systemEnv = Object.fromEntries(['PATH', 'PATHEXT', 'SystemRoot', 'ComSpec', 'USERPROFILE', 'HOME',
  'APPDATA', 'LOCALAPPDATA', 'ProgramFiles', 'ProgramFiles(x86)', 'ProgramData', 'ProgramW6432', 'TEMP', 'TMP']
  .filter((key) => process.env[key] !== undefined)
  .map((key) => [key, process.env[key]]));

test('rendered Compose forwards Order integration configuration only to Order', () => {
  const fixture = Object.fromEntries(keys.map((key, index) => [key, `order-integration-fixture-${index}`]));
  const { services } = JSON.parse(execFileSync('docker', [
    'compose', '--env-file', '.env.example', 'config', '--format', 'json',
  ], {
    cwd: root,
    encoding: 'utf8',
    env: {
      ...systemEnv,
      APP_ENV: 'prod',
      POSTGRES_PASSWORD: 'compose-fixture',
      AUTH_COOKIE_SECURE: 'true',
      AUTH_CSRF_SECRET: 'compose-fixture',
      AUTH_JWT_PRIVATE_KEY: 'compose-fixture',
      AUTH_MFA_ENCRYPTION_KEY: 'compose-fixture',
      AUTH_RESET_HMAC_KEY: 'compose-fixture',
      AUTH_RESTAURANT_ID: '00000000-0000-4000-8000-000000000001',
      MAIL_AUTH_PUBLIC_KEY: 'compose-fixture',
      MAIL_FROM_ADDRESS: 'fixture@example.invalid',
      MAIL_SMTP_HOST: 'fixture.invalid',
      MAIL_SMTP_PORT: '2525',
      MAIL_SMTP_TLS_MODE: 'starttls',
      ...fixture,
    },
  }));
  for (const [name, service] of Object.entries(services)) {
    const env = service.environment ?? {};
    for (const key of keys) {
      if (name === 'order-service') assert.equal(env[key], fixture[key], key);
      else assert.equal(Object.hasOwn(env, key), false, `${name} received ${key}`);
    }
  }
  assert.equal(services['order-service'].environment.APP_ENV, 'prod');
});
