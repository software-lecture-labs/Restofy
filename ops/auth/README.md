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

Üretilenler:

- `AUTH_JWT_PRIVATE_KEY`: 3072-bit RSA, Base64 PKCS#8.
- `MAIL_AUTH_PUBLIC_KEY`: aynı çiftin Base64 X.509/SPKI public anahtarı.
- `AUTH_CSRF_SECRET`, `AUTH_MFA_ENCRYPTION_KEY`, `AUTH_RESET_HMAC_KEY`: birbirinden bağımsız 32 rastgele bayt, Base64.

Bu eşleşme mevcut `dev` sürümünün Auth→Mail sözleşmesidir. Ayrı Mail signer kullanan,
henüz birleşmemiş geliştirmelerin anahtar sözleşmesiyle karıştırılmamalıdır.

Araç **yeni kurulum** içindir; anahtar rotasyonu yapmaz. Mevcut MFA anahtarını değiştirmek
kayıtlı sırları okunamaz hâle getirebilir. Var olan sırları koruyun; yalnız ihtiyaç duyulan
alanları kendi Git dışı ENV dosyanıza güvenli biçimde aktarın. Paylaşılan restoran UUID'si,
bootstrap parolaları ve SMTP ayarları üretilmez; mevcut kurulum kararlarına göre ayrıca belirlenir.
