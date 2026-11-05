# Auth/Mail DEV anahtar kurulumu

Proje kökünde Node.js 20+ ile Windows / macOS / Linux üzerinde aynı komutu kullanın:

```sh
node ops/auth/generate-keys.mjs
```

Komut `.env.dev` yoksa `.env.example` üzerinden oluşturur; boş Auth/Mail anahtar alanlarını
doldurur ve DEV profilini `.env` olarak etkinleştirir. Ayrıca `env.ps1` çalıştırılmaz.
Başka çalışma dizininden çağrılsa da kendi repository'sini hedefler. PROD seçeneği yoktur.

Personel JWT için `AUTH_JWT_PRIVATE_KEY`, CSRF için `AUTH_CSRF_SECRET`, MFA için
`AUTH_MFA_ENCRYPTION_KEY`, reset için `AUTH_RESET_HMAC_KEY` doldurulur. Personel RSA 3072 bit,
simetrik sırlar birbirinden farklı 32 rastgele baytın Base64 karşılığıdır. Boş
`AUTH_COOKIE_SECURE` yalnız DEV'de `false` yapılır; mevcut `true` ve MFA ayarı korunur.

Diğer servislere verilecek personel public anahtarı `secrets/auth-staff-public.key` olarak
yazılır. Bu yeni bir ENV alanı değildir. Consumer'a yalnız public anahtar verilir;
`AUTH_JWT_PRIVATE_KEY` ve aşağıdaki Mail signer'ı paylaşılmaz. Mail ayrı çift kullanır:

| Ayar | Biçim / amaç |
|---|---|
| AUTH_MAIL_PRIVATE_KEY | Base64 PKCS#8 RSA, en az 2048 bit; session RSA'sından ayrı signer |
| MAIL_AUTH_PUBLIC_KEY | Aynı signer'ın Base64 X.509/SPKI public anahtarı; Mail doğrular |
| AUTH_MAIL_OUTBOX_KEY | Base64, 32 rastgele bayt; Auth outbox AES-256-GCM |
| MAIL_PAYLOAD_ENCRYPTION_KEY | Başka 32 rastgele baytın Base64 karşılığı; Mail payload AES-256-GCM |

## Mevcut kurulum ve tekrar çalıştırma

- Dolu ve geçerli anahtarlar korunur; komut rotasyon yapmaz. Eksik Mail public anahtarı
  mevcut Mail private anahtarından türetilir. Public tek başına varsa private yeniden üretilmez.
- Uyumsuz RSA çifti, ortak personel/Mail RSA anahtarı, zayıf/geçersiz sır, tekrarlı ENV alanı
  veya ortak simetrik sır varsa dosyalara yazmadan hata verir; değerleri sessizce değiştirmez.
- Aktif `.env` doğrulanmış DEV profiline aitse anahtar dışı farklar kurulumu durdurmaz:
  eski aktif dosya `.env.auth-setup.<benzersiz-id>.bak` olarak yedeklenir ve `.env.dev` esas
  alınarak eşitlenir. Aktif dosyada bulunan, profilde boş olan Auth/Mail anahtarları korunarak
  profile aktarılır. İki dosyada dolu fakat farklı Auth/Mail anahtarları varsa işlem durur.
  Başka profile ait veya işaretsiz `.env` korunur; sessiz profil geçişi yapılmaz.
- Mevcut DEV profili değişirse önce `.env.dev.auth-setup.<benzersiz-id>.bak` oluşturulur.
  Her dosya geçici dosyadan atomik yayımlanır; iki ENV tek filesystem transaction'ı değildir.
  Disk/izin hatasından sonra `.env.dev` ve `ops/env status` kontrol edilir.
- Eşzamanlı kurulum kilitle engellenir. Süreç zorla kapanırsa `.env.auth-setup.lock` kalabilir;
  başka kurulum çalışmadığı doğrulanmadan bu kilit kaldırılmaz.
- Sırlar terminale yazılmaz. ENV, yedekler ve `secrets/` Git dışındadır. Unix'te yeni dosyalar
  `0600`, secrets klasörü `0700`; Windows'ta klasörün ACL izinleri geçerlidir.

Mevcut/paylaşılan DB için o kurulumun mevcut sırları ekipten güvenli kanalla alınır. Eksik
MFA/AES anahtarını yeniden üretmek eski şifreli veriyi açmaz. Aktif işler bitmeden rotasyon yapılmaz.

## Uygulamayı başlatmadan önce

MVP tek restoranlıdır. Ortak `AUTH_RESTAURANT_ID`, `POSTGRES_PASSWORD`, SMTP hesabı ve gerekiyorsa
bootstrap kullanıcı ayarlarını `.env.dev` içinde tamamlayın. Araç bunları rastgele belirlemez;
eksik temel alanları adlarıyla gösterir. SMTP kimlik doğrulama ve kullanıcı oluşturma ayarları
ortama göre ayrıca doldurulur. Komutun tamamlanması çalışan DB/SMTP testi veya bütün kurulumun
tamamlandığı anlamına gelmez.

Bu alanları sonradan düzenlediğinizde profili yeniden etkinleştirin:

```powershell
.\ops\env.cmd use dev -Force
```

```sh
sh ops/env.sh use dev --force
```

`-Force` eski `.env` kopyasını `.env.bak` olarak yedekler. Docker otomatik başlatılmaz;
çalışan servisler yeni ENV için normal Compose akışıyla yeniden oluşturulur.

Aracın testleri gerçek ENV/DB yerine geçici klasörlerde çalışır:

```sh
node --test ops/auth/generate-keys.test.mjs
```

## Elle Mail anahtarı üretimi — alternatif operatör yöntemi

Otomatik DEV aracı kullanıldıysa bu bölüm tekrar çalıştırılmaz. Production kurulumu/rotasyonu
ayrı operatör sürecidir; production için secret manager ve uygun dosya izinleri kullanılır.

PowerShell 7 ile geliştirme anahtarlarını repoda Git tarafından dışlanan `secrets/` altında
üretmek için aşağıdaki örnek kullanılabilir. Komutlar değerleri terminale yazmaz; mevcut dosyayı
ezmemek için önce dört hedefin de bulunmadığını kontrol eder. Üretimde secret manager ve
kuruma uygun dosya izinleri kullanılır; anahtarlar container imajına kopyalanmaz.

```powershell
$names = @('AUTH_MAIL_PRIVATE_KEY', 'MAIL_AUTH_PUBLIC_KEY', 'AUTH_MAIL_OUTBOX_KEY', 'MAIL_PAYLOAD_ENCRYPTION_KEY')
foreach ($name in $names) {
    if (Test-Path -LiteralPath "secrets/$name.key") { throw "Hedef zaten var: $name" }
}
New-Item -ItemType Directory -Path secrets -Force | Out-Null
$secretDir = (Resolve-Path secrets).Path
$mailRsa = [Security.Cryptography.RSA]::Create(3072)
try {
    [IO.File]::WriteAllText((Join-Path $secretDir 'AUTH_MAIL_PRIVATE_KEY.key'), [Convert]::ToBase64String($mailRsa.ExportPkcs8PrivateKey()))
    [IO.File]::WriteAllText((Join-Path $secretDir 'MAIL_AUTH_PUBLIC_KEY.key'), [Convert]::ToBase64String($mailRsa.ExportSubjectPublicKeyInfo()))
    foreach ($name in @('AUTH_MAIL_OUTBOX_KEY', 'MAIL_PAYLOAD_ENCRYPTION_KEY')) {
        $keyBytes = [Security.Cryptography.RandomNumberGenerator]::GetBytes(32)
        [IO.File]::WriteAllText((Join-Path $secretDir "$name.key"), [Convert]::ToBase64String($keyBytes))
        [Security.Cryptography.CryptographicOperations]::ZeroMemory($keyBytes)
    }
} finally { $mailRsa.Dispose() }
```

Bu alternatif yöntemde dosyaların içeriğini aynı adlı gerçek ENV alanlarına **operatör** aktarır;
uygulama `.key` dosyalarını otomatik okumaz. `git check-ignore secrets/*.key` ile dışlama doğrulanır.
Önceden üretilmiş anahtarlar tekrar üretilmez; aktif şifreli işler bitmeden AES anahtarı değiştirilmez.

Yeni üç zorunlu sır Compose'ta `${VAR:-}` ile aktarılır. Eksik olmaları başka bir servisin Compose
ayrıştırmasını durdurmaz; **Auth/Mail uygulamasının başlangıç doğrulaması** eksik/boş/zayıf anahtarı
reddeder. Önceden var olan diğer `${VAR:?}` gereksinimleri bu dar düzeltmede değiştirilmedi.
Testler gerçek ENV yerine JVM içinde ürettikleri anahtarları kullanır.

Eski Mail işleri boşaltılmadan süreç kapatılmaz; ayrı signer geçişi koordineli yapılır.
[Geçiş ve rollback sınırları](../decisions/ADR-AUTH-001-mail-gonderimi.md).
