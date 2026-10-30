import { generateKeyPairSync, randomBytes } from 'node:crypto';
import { mkdir, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { parseArgs } from 'node:util';

const RSA_BITS = 3072;
const SECRET_BYTES = 32;
const newSecret = () => randomBytes(SECRET_BYTES).toString('base64');

try {
  const { values } = parseArgs({
    options: {
      output: { type: 'string', short: 'o' },
      help: { type: 'boolean', short: 'h' },
    },
  });

  if (values.help) {
    console.log('Kullanım: node ops/auth/generate-keys.mjs [--output <dosya>]');
    console.log('Varsayılan: secrets/auth-mail-keys.env. Mevcut dosya ve ENV ayarları değiştirilmez.');
  } else {
    const output = values.output
      ? resolve(values.output)
      : fileURLToPath(new URL('../../secrets/auth-mail-keys.env', import.meta.url));
    const { privateKey, publicKey } = generateKeyPairSync('rsa', {
      modulusLength: RSA_BITS,
      privateKeyEncoding: { type: 'pkcs8', format: 'der' },
      publicKeyEncoding: { type: 'spki', format: 'der' },
    });
    const content = [
      '# Yeni Auth/Mail kurulumu için anahtarlar. Git veya sohbet üzerinden paylaşmayın.',
      '# Mevcut anahtarları değiştirmek oturumları ve kayıtlı MFA verisini etkileyebilir.',
      `AUTH_JWT_PRIVATE_KEY=${privateKey.toString('base64')}`,
      `MAIL_AUTH_PUBLIC_KEY=${publicKey.toString('base64')}`,
      `AUTH_CSRF_SECRET=${newSecret()}`,
      `AUTH_MFA_ENCRYPTION_KEY=${newSecret()}`,
      `AUTH_RESET_HMAC_KEY=${newSecret()}`,
      '',
    ].join('\n');

    await mkdir(dirname(output), { recursive: true, mode: 0o700 });
    await writeFile(output, content, { encoding: 'utf8', flag: 'wx', mode: 0o600 });
    console.log('Anahtar dosyası oluşturuldu. Sırlar terminale yazılmadı; mevcut ENV dosyaları değiştirilmedi.');
  }
} catch (error) {
  console.error(error.code === 'EEXIST'
    ? 'Hedef dosya zaten var; üzerine yazılmadı. Başka bir --output yolu seçin.'
    : 'Anahtar üretilemedi. Argümanları ve hedef klasör izinlerini kontrol edin.');
  process.exitCode = 1;
}
