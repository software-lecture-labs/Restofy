# Auth/Mail sağlamlaştırma — 30 Ekim 2026

Durum: yerel uygulama ve son kalite kapısı tamamlandı. Uzak işlem yapılmadı.
Sonraki kontrol: [30 Ekim PR denetimi ve ek düzeltmeler](pre-pr-review.md).
Kullanıcının açık onayı: Gateway forwarding, ortak test modülü, eklemeli migration ve örnek ENV/Compose.

## Değişenler

| İstek | Yerel karşılık |
|---|---|
| Güvenilir proxy | Gateway başlıkları temizler, tek socket peer gönderir; Auth yalnız dar allowlist'e güvenir. Varsayılan boş/kapalı. |
| Ortak limiter | Login/MFA peer/reset PostgreSQL ortak depoda; bütçeler, HMAC ve mevcut reset sayacı korunur. |
| Auth→Mail kaybı | Auth business transaction'ıyla şifreli outbox; lease/CAS/idempotent HTTP retry; Mail'de şifreli payload ve restart recovery. |
| Ortak RSA | Ayrı Auth→Mail signer; session RSA modulus'unun tekrar kullanımı başlangıçta reddedilir. |
| Okuma yazma kilidi | GET snapshot doğrulaması; değişiklikler kilit altında yeniden doğrulanır. Gerçek PostgreSQL lock regression testi var. |
| Kopya test altyapısı | Test-scope libs/test-support; 37 koruma testi tek yerde, servis başına küçük adapter/smoke test. |
| Tam sınıf adı/uzun satır | Importlar düzeltildi; Mail Java üretim+test satır taraması 140 üstü 0. Auth kalıcı kalite kuralları korunur. |
| Genel ENV hatası | Yalnız ayar adı+beklenen biçim; değer/cause sızdırmayan MailSettings ve yeni crypto ayarları. |
| Bağımsız inceleme | MFA peer transaction'ının iç içe ikinci connection istemesi bulundu; peer bütçesi account transaction'ından önce ayrıldı. |

## Son doğrulama

Son tam `clean verify`: **BUILD SUCCESS** (29 Ekim 2026, 19:23).

| Modül | Test | Hata / atlama |
|---|---:|---:|
| Ortak test desteği | 37 | 0 / 0 |
| Gateway | 55 | 0 / 0 |
| Auth | 323 | 0 / 0 |
| Mail | 69 | 0 / 0 |
| Toplam | **484** | **0 / 0** |

Auth: PMD 0, Checkstyle 0; ArchUnit 1 test geçti. JaCoCo satır kapsamı:
Auth %97,31; Mail %91,61. Auth/Mail uygulama JAR'larında test-support ve
Testcontainers bağımlılığı yok. Eski migration dosyalarında değişiklik yok.
Workflow/Compose YAML ve workflow rapor kontrolünün Python sözdizimi yerelde doğrulandı.

Odaklı ilk Auth koşusu 69 testte eski migration beklentisi nedeniyle 1 FAIL; Mail odaklı22 PASS.
İlk geniş koşu: common37/Gateway55 PASS, Auth322'de iki eski migration sayısı beklentisi FAIL;
Mail o koşuda SKIPPED. Sayılar/target beklentileri yeni eklemeli şemaya göre düzeltildi.
MFA havuz hatası tek bağlantılı çalışan havuzda CannotCreateTransactionException ile kanıtlandı;
sonraki odaklı koşu 26/26, son tam kapı da başarılı. İlk fixture denemesi startup connection ihtiyacı
nedeniyle ERROR verdi; bu ürün hatasının kanıtı sayılmadı. Geçerli red koşusu 1 FAIL/0 ERROR.

Testler yalnız izole Testcontainers PostgreSQL ve sahte SMTP kullanır. Gerçek ENV/DB/SMTP okunmadı
veya değiştirilmedi. GitHub workflow yerelde Auth/Mail/Gateway/common testlerini verify edecek
şekilde hazırlandı; push/dispatch yok, uzak CI sonucu DOĞRULANMADI.
Windows Türkçe yol workaround'ı yalnız CLI'da tek koşuya özel ASCII JaCoCo exec yoludur;
kapılar, coverage eşiği ve güvenlik kuralları gevşetilmedi.

## Kim kimi bekliyor / kalanlar

Auth tarafında bildirilen staff-v1, personel yönetimi ve Notification alıcı sözleşmeleri korunur.
Order/Kitchen/Menu/QR/Waiter kendi doğrulanmış staff adapterlarını; Admin/Notification kendi
clientlarını uygular. Yeni varsayımsal Saga/public Auth endpoint'i eklenmedi; bütün gelecek iş
uçları için sınırsız uyumluluk iddia edilmez.

Yerelde çözülmüş #9/#10 için GitHub durumu değiştirilmedi. Gerçek ağ CIDR seçimi, ayrı production
anahtarları ve koordineli geçiş operatör işidir; otomatik uygulanmaz. SMTP→inbox garantisi yok;
SMTP kabul/DB commit belirsizliğinde UNKNOWN kör resend edilmez. Anahtar kaybı/online rotasyon,
backup retention, yoğun yük ölçümü ve kabul edilen hesap50 DoS ödünleşimi kalan işletim sınırlarıdır.
BCrypt/hash geçişi, ortak persistence standardı, çok restoranlı üyelik, guest browser politikası,
kayıp MFA prosedürü ve gerçek servisler arası E2E bu kapsamda uygulanmadı.

## Şema / ayar / geri dönüş

Yeni Auth V5/V6 ve Mail V12 yalnız izole test DB'lerine uygulandı; eski migration'lar değişmedi.
Yeni zorunlu ayarlar: AUTH_MAIL_PRIVATE_KEY, AUTH_MAIL_OUTBOX_KEY, MAIL_PAYLOAD_ENCRYPTION_KEY.
MAIL_AUTH_PUBLIC_KEY artık ayrı servis signer'ının public kısmıdır. AUTH_TRUSTED_PROXY_CIDRS
opsiyonel ve varsayılan boş; AUTH_NOTIFICATION_PUBLIC_KEY opsiyonel aktarımı tamamlandı.
[Mail geçişi ve rollback sınırları](../decisions/ADR-AUTH-001-mail-gonderimi.md),
[proxy/limiter/kilit kararları](../decisions/ADR-AUTH-002-rate-limit-ve-kilitler.md).

## Yerel commitler

- d39d4e5 — Auth/Mail importları ve uzun test satırları.
- 4373e2e — Mail ayar hatalarında güvenli değişken adı.
- 2ec748c — İzole PostgreSQL test desteğini ortak test modülüne taşıma.
- 02035fa — Salt-okunur kimlik kontrolü ve gerçek row-lock testi.
- 563b13f — Gateway istemci başlıklarının socket peer üzerinden güvenli aktarımı.
- 98b88d8 — Ortak limiter, MFA havuz düzeltmesi, şifreli Auth/Mail kalıcılığı ve örnek ayarlar.
- Bu rapor ve yerel CI genişletmesi ayrı son dokümantasyon commitinde saklanır.

Push, PR, issue, yorum, merge, deploy, gerçek ENV/çalışan DB veya uzak ayar değişikliği yapılmadı.
