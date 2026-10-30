# Auth/Mail yeni anahtarlarının kurulumu

Bu sayfa operatör içindir; komutlar bu çalışma sırasında çalıştırılmadı. Gerçek ENV dosyası
okunmadı/değiştirilmedi. Anahtarları sohbet, terminal çıktısı veya Git üzerinden paylaşmayın.

Yeni ayarlar:

| Ayar | Biçim / amaç |
|---|---|
| AUTH_MAIL_PRIVATE_KEY | Base64 PKCS#8 RSA, en az 2048 bit; session RSA'sından ayrı signer |
| MAIL_AUTH_PUBLIC_KEY | Aynı signer'ın Base64 X.509/SPKI public anahtarı; Mail doğrular |
| AUTH_MAIL_OUTBOX_KEY | Base64, 32 rastgele bayt; Auth outbox AES-256-GCM |
| MAIL_PAYLOAD_ENCRYPTION_KEY | Başka 32 rastgele baytın Base64 karşılığı; Mail payload AES-256-GCM |

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

Dosyaların içeriğini güvenli yerel yöntemle, aynı adlı gerçek ENV alanlarına **operatör** aktarır;
uygulama `.key` dosyalarını otomatik okumaz. `git check-ignore secrets/*.key` ile dışlama doğrulanır.
Önceden üretilmiş anahtarlar tekrar üretilmez; aktif şifreli işler bitmeden AES anahtarı değiştirilmez.

Yeni üç zorunlu sır Compose'ta `${VAR:-}` ile aktarılır. Eksik olmaları başka bir servisin Compose
ayrıştırmasını durdurmaz; **Auth/Mail uygulamasının başlangıç doğrulaması** eksik/boş/zayıf anahtarı
reddeder. Önceden var olan diğer `${VAR:?}` gereksinimleri bu dar düzeltmede değiştirilmedi.
Testler gerçek ENV yerine JVM içinde ürettikleri anahtarları kullanır.

Eski Mail işleri boşaltılmadan süreç kapatılmaz; ayrı signer geçişi koordineli yapılır.
[Geçiş ve rollback sınırları](../decisions/ADR-AUTH-001-mail-gonderimi.md).
