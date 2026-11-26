# Auth/Mail anahtar üretimi

Node.js 20+ ile, ek paket kurmadan repository kökünden çalıştırın:

```sh
node ops/auth/generate-keys.mjs
```

Anahtarlar Git dışında tutulan `secrets/auth-mail-keys.env` dosyasına yazılır.
Başka bir dosya için `--output <dosya>` kullanın; göreli yol çalışma dizinine göredir.
Mevcut dosya ezilmez. Sırlar terminale yazılmaz; `.env` / `.env.dev` otomatik düzenlenmez.
Unix'te dosya `0600`, yeni klasörler `0700` izinleriyle oluşturulur; Windows'ta klasörün ACL izinleri geçerlidir.
Özel hedef seçerseniz dosyayı Git dışında, erişimi sınırlı bir yerde tutun.

Çıktı, servis ve amaç açıklamalarıyla başlıklar altında düzenlenir:

| Alan | Kullanan servis | Amaç / biçim |
|---|---|---|
| `AUTH_JWT_PRIVATE_KEY` | Yalnız Auth | JWT imzalama; 3072-bit RSA, Base64 PKCS#8 |
| `AUTH_CSRF_SECRET` | Yalnız Auth | CSRF koruması; Base64, 32 rastgele bayt |
| `AUTH_MFA_ENCRYPTION_KEY` | Yalnız Auth | TOTP sırlarını AES-256-GCM ile şifreleme; Base64, 32 rastgele bayt |
| `AUTH_RESET_HMAC_KEY` | Yalnız Auth | Parola sıfırlama kod/cookie doğrulaması; Base64, 32 rastgele bayt |
| `MAIL_AUTH_PUBLIC_KEY` | Mail | Auth gönderim tokenını doğrulama; aynı RSA çiftinin Base64 X.509/SPKI public anahtarı |

Simetrik sırlar, Auth'un mevcut en az 16 farklı bayt kontrolünü de karşılar.

Bu eşleşme mevcut `dev` sürümünün Auth→Mail sözleşmesidir. Ayrı Mail signer kullanan,
henüz birleşmemiş geliştirmelerin anahtar sözleşmesiyle karıştırılmamalıdır.

Araç **yeni kurulum** içindir; anahtar rotasyonu yapmaz. Mevcut MFA anahtarını değiştirmek
kayıtlı sırları okunamaz hâle getirebilir. Var olan sırları koruyun; yalnız ihtiyaç duyulan
alanları kendi Git dışı ENV dosyanıza güvenli biçimde aktarın. Paylaşılan restoran UUID'si,
bootstrap parolaları ve SMTP ayarları üretilmez; mevcut kurulum kararlarına göre ayrıca belirlenir.

## Gateway adresi (AUTH_TRUSTED_PROXY_CIDRS)

Auth, istemci adresini yalnız güvenilen Gateway'den gelen `X-Forwarded-For` ile okur. Liste boşsa
başlık yok sayılır ve herkes Gateway adresi olarak görünür; rate limit bu yüzden tek kovaya düşer. Girişteki
adres başına toplam bütçe (100/15 dk) bu durumda herkesi birlikte kilitleyeceği için uygulanmaz.

Docker Compose'ta ek ayar gerekmez: `restofy_network` sabit `10.213.0.0/24` subnet'ini kullanır,
Gateway'in adresi `10.213.0.10`'dur ve `AUTH_TRUSTED_PROXY_CIDRS` varsayılan olarak `10.213.0.10/32`'dir.

- ENV dosyanızda eski bir `AUTH_TRUSTED_PROXY_CIDRS=...` satırı varsa varsayılanı ezer; silin.
- Ağ tanımı değiştiği için ilk seferde bir kez `docker compose down` (**`-v` olmadan**, veriler kalır) ve
  ardından `docker compose up -d` gerekir; Docker ağı yeni subnet'le yeniden oluşturur.
- Yalnız Gateway'in tek adresi yazılmalıdır, tüm Docker ağı değil: ağdaki her container güvenilir olurdu.
  `0.0.0.0/0` ve `::/0` Auth tarafından reddedilir. PROD'da da sabit bir Gateway adresi kullanın.
