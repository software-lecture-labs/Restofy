import { createPrivateKey, createPublicKey, generateKeyPairSync, randomBytes, randomUUID } from 'node:crypto';
import { lstat, mkdir, open, readFile, rename, unlink, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { parseArgs } from 'node:util';

const MARKER = '# RESTOFY-ACTIVE-PROFILE=dev';
const SECRET_NAMES = ['AUTH_CSRF_SECRET', 'AUTH_MFA_ENCRYPTION_KEY', 'AUTH_RESET_HMAC_KEY',
  'AUTH_MAIL_OUTBOX_KEY', 'MAIL_PAYLOAD_ENCRYPTION_KEY'];

async function readOptional(path) {
  try {
    const stat = await lstat(path);
    if (!stat.isFile() || stat.nlink !== 1) throw new Error('Hedef normal, bağlantısız bir dosya olmalı.');
    return await readFile(path, 'utf8');
  } catch (error) {
    if (error.code === 'ENOENT') return null;
    throw error;
  }
}

function parseEnv(text) {
  const values = new Map();
  for (const line of text.split(/\r?\n/)) {
    if (!line.trim() || line.trimStart().startsWith('#')) continue;
    const match = line.match(/^\s*([A-Za-z_][A-Za-z_0-9]*)\s*=\s*(.*?)\s*$/);
    if (!match || values.has(match[1])) throw new Error('ENV biçimi geçersiz veya aynı alan birden fazla tanımlı.');
    let value = match[2];
    if (value.startsWith('"') || value.startsWith("'")) {
      const quote = value[0];
      const end = value.indexOf(quote, 1);
      if (end < 0 || !/^\s*(#.*)?$/.test(value.slice(end + 1))) throw new Error('ENV tırnak biçimi geçersiz.');
      value = value.slice(1, end);
    } else {
      value = value.replace(/\s+#.*$/, '').trim();
    }
    values.set(match[1], value);
  }
  return values;
}

function rsaPrivate(value, name) {
  try {
    const key = createPrivateKey({ key: decodeBase64(value), format: 'der', type: 'pkcs8' });
    if (key.asymmetricKeyType !== 'rsa' || key.asymmetricKeyDetails.modulusLength < 2048) throw new Error();
    return key;
  } catch {
    throw new Error(`${name} geçersiz; mevcut anahtar değiştirilmedi.`);
  }
}

function decodeBase64(value) {
  const bytes = Buffer.from(value, 'base64');
  if (!value || bytes.toString('base64') !== value) throw new Error('Geçersiz Base64.');
  return bytes;
}

function publicBase64(key) {
  return createPublicKey(key).export({ format: 'der', type: 'spki' }).toString('base64');
}

function newPrivate() {
  return generateKeyPairSync('rsa', { modulusLength: 3072 }).privateKey
    .export({ format: 'der', type: 'pkcs8' }).toString('base64');
}

function newSecret() {
  let bytes;
  do { bytes = randomBytes(32); } while (new Set(bytes).size < 16);
  return bytes.toString('base64');
}

function normalized(text) {
  return text.replace(/\r\n/g, '\n');
}

async function atomicWrite(path, text) {
  const temporary = `${path}.${randomUUID()}.tmp`;
  try {
    await writeFile(temporary, text, { flag: 'wx', mode: 0o600 });
    await rename(temporary, path);
  } finally {
    await unlink(temporary).catch(error => { if (error.code !== 'ENOENT') throw error; });
  }
}

/** DEV only. Tests supply an isolated fixture directory; CLI always targets this repository. */
export async function setupDev(root) {
  const profilePath = resolve(root, '.env.dev');
  const activePath = resolve(root, '.env');
  const publicPath = resolve(root, 'secrets/auth-staff-public.key');
  const lockPath = resolve(root, '.env.auth-setup.lock');
  let lock;
  try {
    lock = await open(lockPath, 'wx', 0o600);
  } catch (error) {
    if (error.code === 'EEXIST') throw new Error('Anahtar kurulumu kilitli. Başka kurulumun bitmesini bekleyin.');
    throw error;
  }
  try {
    const previous = await readOptional(profilePath);
    const source = previous ?? await readFile(resolve(root, '.env.example'), 'utf8');
    const active = await readOptional(activePath);
    const values = parseEnv(source);
    if (values.get('APP_ENV') !== 'dev' || values.get('SPRING_PROFILES_ACTIVE') !== 'dev') {
      throw new Error('Bu araç yalnız APP_ENV=dev ve SPRING_PROFILES_ACTIVE=dev ile çalışır.');
    }
    const updates = new Map();
    const activeDiffers = active !== null && normalized(active) !== `${MARKER}\n${normalized(source)}`;
    if (activeDiffers) {
      if (previous === null || !normalized(active).startsWith(`${MARKER}\n`)) {
        throw new Error('Aktif .env doğrulanmış DEV profiline ait değil; dosya korunuyor. Önce ops/env status ile kontrol edin.');
      }
      const activeValues = parseEnv(active);
      if (activeValues.get('APP_ENV') !== 'dev' || activeValues.get('SPRING_PROFILES_ACTIVE') !== 'dev') {
        throw new Error('Aktif .env doğrulanmış DEV profiline ait değil; dosya korunuyor.');
      }
      for (const name of [...SECRET_NAMES, 'AUTH_JWT_PRIVATE_KEY', 'AUTH_MAIL_PRIVATE_KEY', 'MAIL_AUTH_PUBLIC_KEY']) {
        const current = activeValues.get(name);
        const configured = values.get(name);
        if (current && configured && current !== configured) {
          throw new Error(`${name}, .env ve .env.dev arasında farklı. Mevcut anahtarlar korunuyor; doğru kurulumun anahtarını belirleyin.`);
        }
        if (current && !configured) {
          values.set(name, current);
          updates.set(name, current);
        }
      }
    }
    const fill = (name, generate) => {
      if (!values.get(name)) {
        const value = generate();
        values.set(name, value);
        updates.set(name, value);
      }
    };
    if (!values.get('AUTH_MAIL_PRIVATE_KEY') && values.get('MAIL_AUTH_PUBLIC_KEY')) {
      throw new Error('MAIL_AUTH_PUBLIC_KEY var fakat AUTH_MAIL_PRIVATE_KEY eksik. Aynı çiftin mevcut özel anahtarını geri yükleyin.');
    }
    fill('AUTH_JWT_PRIVATE_KEY', newPrivate);
    fill('AUTH_MAIL_PRIVATE_KEY', newPrivate);
    const sessionKey = rsaPrivate(values.get('AUTH_JWT_PRIVATE_KEY'), 'AUTH_JWT_PRIVATE_KEY');
    const mailKey = rsaPrivate(values.get('AUTH_MAIL_PRIVATE_KEY'), 'AUTH_MAIL_PRIVATE_KEY');
    const staffPublic = publicBase64(sessionKey);
    const mailPublic = publicBase64(mailKey);
    if (staffPublic === mailPublic) throw new Error('Personel ve Mail imzalama anahtarları ayrı olmalı.');
    fill('MAIL_AUTH_PUBLIC_KEY', () => mailPublic);
    if (values.get('MAIL_AUTH_PUBLIC_KEY') !== mailPublic) {
      throw new Error('MAIL_AUTH_PUBLIC_KEY, mevcut AUTH_MAIL_PRIVATE_KEY ile eşleşmiyor. Hiçbir anahtar değiştirilmedi.');
    }
    for (const name of SECRET_NAMES) {
      fill(name, newSecret);
      let bytes;
      try { bytes = decodeBase64(values.get(name)); } catch { throw new Error(`${name} geçersiz Base64.`); }
      const exact = ['AUTH_MFA_ENCRYPTION_KEY', 'AUTH_MAIL_OUTBOX_KEY', 'MAIL_PAYLOAD_ENCRYPTION_KEY'].includes(name);
      if (bytes.length < 32 || (exact && bytes.length !== 32) || new Set(bytes).size < 16) {
        throw new Error(`${name} geçersiz uzunlukta veya zayıf; mevcut değer değiştirilmedi.`);
      }
    }
    if (new Set(SECRET_NAMES.map(name => values.get(name))).size !== SECRET_NAMES.length) {
      throw new Error('CSRF, MFA, reset, Auth outbox ve Mail payload sırları birbirinden farklı olmalı.');
    }
    fill('AUTH_COOKIE_SECURE', () => 'false');
    if (!['true', 'false'].includes(values.get('AUTH_COOKIE_SECURE'))) throw new Error('AUTH_COOKIE_SECURE true veya false olmalı.');
    const oldPublic = await readOptional(publicPath);
    if (oldPublic !== null && oldPublic.trim() !== staffPublic) {
      throw new Error('secrets/auth-staff-public.key mevcut personel anahtarıyla eşleşmiyor; dosya korunuyor.');
    }
    const eol = source.includes('\r\n') ? '\r\n' : '\n';
    const seen = new Set();
    let content = source.split(/\r?\n/).map(line => {
      const name = line.match(/^\s*([A-Za-z_][A-Za-z_0-9]*)\s*=/)?.[1];
      if (!updates.has(name)) return line;
      seen.add(name);
      return `${name}=${updates.get(name)}`;
    }).join(eol);
    for (const [name, value] of updates) {
      if (!seen.has(name)) content += `${content.endsWith(eol) ? '' : eol}${name}=${value}${eol}`;
    }
    // Recheck snapshots before publishing; concurrent/manual changes must never be silently overwritten.
    if (await readOptional(profilePath) !== previous || await readOptional(activePath) !== active) {
      throw new Error('ENV dosyaları işlem sırasında değişti. Yeniden çalıştırın.');
    }
    let backup = null;
    let activeBackup = null;
    if (previous !== null && content !== previous) {
      backup = `.env.dev.auth-setup.${randomUUID()}.bak`;
      await writeFile(resolve(root, backup), previous, { flag: 'wx', mode: 0o600 });
    }
    if (activeDiffers) {
      activeBackup = `.env.auth-setup.${randomUUID()}.bak`;
      await writeFile(resolve(root, activeBackup), active, { flag: 'wx', mode: 0o600 });
    }
    await mkdir(dirname(publicPath), { recursive: true, mode: 0o700 });
    if (oldPublic === null) await writeFile(publicPath, `${staffPublic}\n`, { flag: 'wx', mode: 0o600 });
    if (content !== previous) await atomicWrite(profilePath, content);
    const activated = `${MARKER}${eol}${content}`;
    if (active !== activated) await atomicWrite(activePath, activated);
    const missing = ['POSTGRES_PASSWORD', 'AUTH_RESTAURANT_ID', 'MAIL_FROM_ADDRESS', 'MAIL_SMTP_HOST',
      'MAIL_SMTP_PORT', 'MAIL_SMTP_TLS_MODE'].filter(name => !values.get(name));
    return { updated: [...updates.keys()], backup, activeBackup, missing };
  } finally {
    await lock.close();
    await unlink(lockPath);
  }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    const { values } = parseArgs({ options: { help: { type: 'boolean', short: 'h' } } });
    if (values.help) {
      console.log('Kullanım: node ops/auth/generate-keys.mjs');
      console.log('DEV: eksik Auth/Mail anahtarlarını .env.dev içine doldurur ve .env olarak etkinleştirir. Dolu anahtarları korur.');
    } else {
      const result = await setupDev(fileURLToPath(new URL('../../', import.meta.url)));
      console.log(`DEV anahtar kurulumu tamamlandı; .env.dev ve aktif .env eşitlendi. ${result.updated.length} boş alan dolduruldu.`);
      console.log('Personel JWT public anahtarı: secrets/auth-staff-public.key (diğer servislere yalnız bu public anahtar verilir).');
      if (result.backup) console.log(`Önceki DEV profilinin Git dışı yedeği: ${result.backup}`);
      if (result.activeBackup) console.log(`Önceki aktif ENV'nin Git dışı yedeği: ${result.activeBackup}. Anahtar dışındaki ayarlarda .env.dev esas alındı.`);
      if (result.missing.length) console.log(`Uygulamayı başlatmadan önce .env.dev içinde tamamlayın: ${result.missing.join(', ')}. Ardından Windows: .\\ops\\env.cmd use dev -Force; Linux/macOS: sh ops/env.sh use dev --force (eski .env yedeklenir).`);
      console.log('Ortak restoran UUID’si, hesap parolaları ve SMTP hesabı otomatik üretilmez. Çalışan servisler yeni ENV için yeniden oluşturulmalı.');
    }
  } catch (error) {
    const known = error.constructor === Error && !error.code;
    console.error(known ? error.message : 'Kurulum tamamlanamadı. Argümanları, dosya izinlerini ve .env.dev / .env durumunu kontrol edin; sırları paylaşmayın.');
    process.exitCode = 1;
  }
}
