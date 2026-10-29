# Personel yönetimi ve Notification alıcı sözleşmesi

Durum: **UYGULANDI** — Auth yerel teslimi. Diğer servislerin adapterları ve gerçek ortam bağlantısı ayrı doğrulanır.
Sahip: Kerem. Mevcut personel JWT/cookie protokolü [staff-v1](staff-contract.md) olarak korunur.

## 1. Admin ekranı → Auth

Admin ekranı aşağıdaki uçları doğrudan veri sahibi Auth'a çağırır; Admin servisi Auth DB'sini sorgulamaz.
Gateway'in mevcut `/api/v1/auth/**` rotası kullanılır. Yeni Gateway/Compose değişikliği yoktur.
Tamamlanmış MFA dahil `STAFF_SESSION` + `ADMIN` gerekir. Personel JWT'si cookie ile taşınır.
Her POST'ta izinli tek Origin ve credential'a bağlı `X-CSRF-TOKEN` gerekir; frontend `credentials: include` kullanır.

| Yöntem ve uç | Girdi | Sonuç |
|---|---|---|
| `GET /api/v1/auth/staff?page=0&size=50` | page: 0–10000; size: 1–100 | `{items, page, size, hasNext}` |
| `GET /api/v1/auth/staff/{id}` | Personel UUID | Personel görünümü; yoksa 404 |
| `POST /api/v1/auth/staff` | `{email, password, role}` | 201 ve personel görünümü |
| `POST /api/v1/auth/staff/{id}` | `{email, role, active, version}` | 200 ve güncel personel görünümü |

Personel görünümü: `{id, restaurantId, email, role, active, version}`. Liste UUID'ye göre sıralıdır.
Rol yalnız `ADMIN`, `WAITER`, `KITCHEN`. E-posta mevcut Auth normalizasyonuyla benzersizdir.
İlk parola en az 12 karakter, en çok 72 UTF-8 bayt; yalnız mevcut BCrypt cost 12 hash'i saklanır.
İstek parolası yanıta veya `toString()` çıktısına girmez; credential taşıyan POST otomatik tekrar edilmez.

Güncelleme tüm alanları ve okunan `version` değerini gerektirir. Stale version veya tekrar e-posta **409**;
frontend veriyi yeniden okur. Aynı içerik/version ile no-op, oturumları ve version'ı değiştirmez.
E-posta/rol/aktiflik değişince hedefin bütün Auth oturumları ve refresh tokenları aynı transaction'da iptal edilir.
Bekleyen veya doğrulanmış parola sıfırlama yetkileri de iptal edilir. Reset isteği kimlik kilidini aldıktan
sonra güncel e-postayı yeniden kontrol eder; eşzamanlı hesap değişikliği eski adrese yeni reset yetkisi üretmez.
Güncelleme çakışmayla geri alınırsa reset/oturum iptalleri de geri alınır.
Pasifleştirme `active=false`; yeniden etkinleştirme `active=true`. Fiziksel DELETE yoktur; kimlik/audit referansları korunur.
Yönetici kendi hesabının e-posta/rol/aktifliğini bu API'de değiştiremez (**409**); başka bir aktif yönetici kullanılır.
Bu kural ve sıralı kimlik kilitleri, iki yöneticinin birbirini eşzamanlı kapatıp son aktif ADMIN'i kaybetmesini önler.
Yeni ADMIN sonraki normal girişte mevcut zorunlu MFA akışına girer; bu API MFA'yı atlatmaz veya sıfırlamaz.

401: geçersiz/iptal edilmiş oturum. 403: yanlış rol, MFA_PENDING, Origin/CSRF.
400: geçersiz alan/UUID/sayfalama. 404: bulunamayan personel. 409: çakışma veya self değişikliği.
Hata gövdesi mevcut ProblemDetail/code biçimidir; e-posta/parola/SQL hata detayı döndürülmez. Yanıtlar `no-store`.
Oluşturma/güncelleme audit'i yalnız transaction commit'inden sonra olay türü, actor/hedef UUID,
rol/aktiflik ve zamanla yazılır; e-posta, parola, cookie veya token kaydedilmez. Log bir outbox/audit DB garantisi değildir.

## 2. Notification → Auth: aktif alıcı kimlikleri

`GET /api/v1/internal/auth/staff-recipients?restaurantId=<UUID>&role=WAITER&page=0&size=100`

İç ağda Auth'un doğrudan URL'sine çağrılır; public Gateway rotası eklenmedi.
Yanıt: `{restaurantId, role, staffUserIds: [UUID], page, size, hasNext}`. E-posta, hash, oturum, MFA verisi yoktur.
Yalnız istenen roldeki **aktif** hesaplar döner. Sonraki sayfalar `hasNext` ile alınır; bu canlı sorgu değişmez snapshot değildir.
Mesai/çevrimiçi kullanıcı seçimi burada yapılmaz; Notification'ın teslim/okundu/event idempotency sorumluluğu korunur.
Alıcı sorgusu başarısızsa Notification boş listeyi başarı gibi kaydetmez; kendi tekrar/uzlaştırma politikasını uygular.

### Ayrı servis kimliği

`Authorization: Service <JWT>`; yalnız Notification'ın RSA özel anahtarıyla imzalanmış RS256 kabul edilir.
Notification özel anahtarını **Auth'a veya başka servise vermez**. Auth'a yalnız Base64 X.509 public key tanıtılır:
Spring property `auth.services.notification-public-key` veya `AUTH_NOTIFICATION_PUBLIC_KEY`.
Bu opsiyonel yeni ayar mevcut `.env`, Compose veya ortak yapılandırmada otomatik değiştirilmedi.
Yoksa iç uç kapalıdır (401); bozuk/zayıf anahtar başlangıcı durdurur. RSA en az 2048 bit;
Auth signing anahtarının yeniden kullanılması da başlangıçta reddedilir.

| Claim | Zorunlu değer |
|---|---|
| `iss`, `sub` | `notification-service` |
| `aud` | `restofy-auth` içerir |
| `token_type` | `service` |
| `scope` | `staff:recipients:read` |
| `restaurant_id` | Auth'un yapılandırılmış restoran UUID'si |
| `jti` | Yeni UUID |
| `iat`, `nbf`, `exp` | Zorunlu; gelecek iat/nbf reddedilir, exp anında geçersiz, exp > iat, ömür en çok 60 sn |

JWT en çok 4096 karakter. Staff/MFA/guest, Mail Bearer, yanlış servis/anahtar/audience/scope/tenant reddedilir.
Cookie, Origin veya birden çok Authorization taşıyan iç istek reddedilir. Bu zincirde browser CSRF yoktur;
yalnız imzalı servis kimliği kabul edildiği için dış personel API'sinin CSRF koruması değişmez.
İç kimlik yalnız bu GET'e yetki verir; personel yazma veya başka iç uçları açmaz.
GET salt okunurdur; aynı geçerli tokenla tekrarı kabul edilir. `jti` için tüketilen-once/replay deposu yoktur.
Yanlış claim/kimlik 401; geçerli kimlikle başka restoran sorgusu 403; eksik/geçersiz parametre 400.

## 3. Teslim sınırı ve kabul

Mevcut DB tek yapılandırılmış `AUTH_RESTAURANT_ID` bağlamındadır; restaurant_id istemciden atanmaz.
Aynı Auth DB'sini başka restoran ayarına bağlamak veya çok restoranlı üyelik varsaymak desteklenmez.
Üyelik şeması/migration eklenmedi. Consumer'ın yerel JWT doğrulamasında logout/pasifleştirme/rol değişikliği
access token ömrü kadar gecikebilir; yeni yönetim uçları anlık dağıtık revoke garantisi vermez.

Auth'ta yeni Saga koordinatörü veya servisler için token dağıtan merkezi uç yoktur. Notification kendi kimliğini üretir;
iş Saga'ları kaynak servislerdedir. Mevcut Auth→Mail sözleşmesi ayrı kalır.

Kanıt: `StaffDirectoryHttpTests`, `StaffRecipientHttpTests`, `NotificationServiceTokensTests`.
Kabul: doğru/yanlış rol ve MFA; Origin/CSRF; duplicate/stale/no-op; session/refresh iptali;
eşzamanlı iki ADMIN değişikliği; aktif rol bazlı, PII içermeyen alıcılar; yanlış claim/imza/algoritma/tenant;
eksik/zayıf/yanlış anahtar ve yanlış iç yol/yöntem. Güncel sonuç [teslim raporunda](status.md).
Reset kanıtı: `PasswordResetIdentityRaceTests` deterministik lookup→lock birim testi ve personel HTTP testlerindeki CODE_PENDING/CODE_VERIFIED
iptal/rollback senaryoları. Aynı üç test düzeltmeden önce başarısız, düzeltmeyle başarılıdır.

**Karşı tarafın kalan işleri:** Umut Admin UI çağrılarını; Hamza Notification imzalayıcısı/client'ı ve anahtar
yapılandırmasını bağlar. Bu çalışma o servislerin kaynak kodunu veya canlı ayarlarını değiştirmez.
