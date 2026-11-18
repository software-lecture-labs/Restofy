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
