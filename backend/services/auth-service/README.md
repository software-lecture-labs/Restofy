# Auth Service — AUTH-01 / AUTH-02

AUTH-01; tek restoran kurulumu ve personel başına tek rol için personel/parola altyapısıdır.
AUTH-02 aşağıdaki HTTP oturum sözleşmesini ekler. AUTH-03 ADMIN MFA sözleşmesi aşağıda açıklanmıştır.
Parola doğrulaması yalnız `PasswordProof` döndürür; `Authentication`, cookie veya token üretmez,
SecurityContext'e kimlik yerleştirmez. Bu sonuç tam giriş/MFA kanıtı olarak kullanılamaz.

## Veri ve parola

`V1__create_staff_users.sql` yalnız Auth DB'sinde `staff_users` tablosunu oluşturur:
`id` (UUID), `email_normalized`, `password_hash`, `role`, `is_active`,
`created_at`, `updated_at`, `version`. E-posta UNIQUE, rol/hash/normalizasyon/zaman/version
CHECK kısıtları PostgreSQL'dedir. JPA `ddl-auto=validate` korunur.

E-posta baş/son boşlukları kaldırılır, `Locale.ROOT` ile küçük harfe çevrilir.
Normalize edilmiş ASCII e-posta kullanılır: toplam en fazla 254, yerel bölüm en fazla 64 karakter.
Parola normalize edilmez veya kesilmez. Yeni parola en az 12 Unicode kod noktası,
en fazla 72 UTF-8 bayt olmalıdır; boş veya bozuk UTF-16 girdisi reddedilir.
Bu ilk politika ve BCrypt maliyeti 12, AUTH-01 kapsamındaki uygulama seçimleridir.
BCrypt her hash için rastgele salt kullanır. Hash ve parolalar loglanmaz.

Eksik kullanıcı, pasif hesap ve yanlış parola aynı `Invalid credentials` hatasını verir.
Eksik/geçersiz hesap için de cost-12 BCrypt karşılaştırması yapılır. Bu, tam sabit zaman
garantisi değildir; HTTP girişindeki hız sınırı ve kötüye kullanım koruması AUTH-02 kapsamındadır.

## Bootstrap ENV sözleşmesi

Kök `.env.example` şablondur. Gerçek değerleri yalnız Git dışında kalan `.env.dev` /
`.env.prod` veya güvenli süreç ortamında tanımlayın. Compose yalnız Auth container'ına aktarır.
Dosyayı kopyalamak tek başına host Java sürecine ENV yüklemez.

| Anahtar | Varsayılan / amaç |
| --- | --- |
| `AUTH_DEV_USERS_ENABLED` | `false`; üç geliştirme hesabını oluşturur |
| `AUTH_DEV_ADMIN_EMAIL`, `AUTH_DEV_ADMIN_PASSWORD` | Boş; ADMIN hesabı |
| `AUTH_DEV_WAITER_EMAIL`, `AUTH_DEV_WAITER_PASSWORD` | Boş; WAITER hesabı |
| `AUTH_DEV_KITCHEN_EMAIL`, `AUTH_DEV_KITCHEN_PASSWORD` | Boş; KITCHEN hesabı |
| `AUTH_INITIAL_ADMIN_ENABLED` | `false`; geliştirmeden bağımsız ilk ADMIN |
| `AUTH_INITIAL_ADMIN_EMAIL`, `AUTH_INITIAL_ADMIN_PASSWORD` | Boş; ilk kurulum kimlik bilgileri |

Geliştirme bootstrap'ı yalnız aktif profillerin tamamı `dev`, `local`, `test` kümesindeyse
çalışır; en az bir aktif profil gerekir. `APP_ENV` verilmişse o da bu kümede olmalıdır.
`prod`, `production`, karma `dev,prod` ve bilinmeyen profillerde etkinleştirme uygulamayı
güvenli şekilde durdurur; hesap oluşturulmaz. Eksik/geçersiz parola veya e-posta ve
tekrarlı geliştirme e-postaları reddedilir. Varsayılan parola yoktur.

İki bootstrap aynı anda etkinleştirilemez. Mevcut e-posta ve rol eşleşiyorsa kimlik,
parola ve aktif/pasif durum korunur. Rol çakışması yükseltme veya üzerine yazma yapmaz;
başlangıç başarısız olur. İlk ADMIN farklı bir e-postayla zaten varsa ikinci ilk ADMIN
oluşturulmaz. Mevcut pasif ADMIN de otomatik etkinleştirilmez.

Her bootstrap tek yerel transaction'dır. Auth DB'sine özgü transaction advisory lock
eşzamanlı başlangıçları sıralar; e-posta UNIQUE kısıtı son korumadır. Bir çakışmada tüm
bootstrap geri alınır. Uygulama DB kimliğinin kendi DB'sinde bu kilidi alabilmesi gerekir.

İlk ADMIN kurulunca `AUTH_INITIAL_ADMIN_ENABLED=false` yapın, başlangıç e-posta/parolasını
ENV/secret kaynağından kaldırın ve Auth sürecini/container'ını yeniden oluşturun; eski
container ortamında sır kalabilir. Geliştirme bootstrap sırlarını da kullanımdan sonra kaldırın.
Başlangıç ADMIN'i MFA olmadan yönetim erişimi kazanmaz. MFA kurulumu AUTH-03'tedir.

## Güvenlik sınırı

Spring method security etkindir; roller hiyerarşik değildir. Henüz iş endpoint'i yoktur.
GET health/liveness/readiness ve aşağıda belirtilen Auth yolları dışında HTTP yolları
reddedilir. Basic/form login kapalıdır, varsayılan Spring kullanıcısıyla
geçici giriş yoktur. Sunucu HTTP oturumu oluşturmaz veya mevcut oturum kimliğini yüklemez.
CSRF koruması aşağıdaki imzalı cookie sözleşmesini kullanır. İstemci rol/kullanıcı/restoran header'ları işlenmez.
Swagger ENV'si açık olsa bile bu aşamada dokümantasyon HTTP erişimi verilmez.

Çok restoranlı dokümanlarla tek restoran hedefinin uyumu açık ekip kararıdır.
Bu çalışma başka servislerin tenant kontrollerini değiştirmez; `ADMIN` global platform
yöneticisi değildir. Servisler arası kimlik, tenant taşıma, cookie/session ve CSRF
sözleşmesinin sonraki aşaması Alper'le kesinleştirilecektir.

AUTH-02: HTTP giriş, cookie/token/session, iptal/çıkış, hız sınırları ve CSRF sözleşmesi.
AUTH-03: zorunlu ADMIN TOTP, ilk MFA kurulumu, kurtarma ve replay kontrolü.
AUTH-04: admin parola sıfırlama ve Mail entegrasyonu.

### curl, Postman ve `Origin` kuralı

Durum değiştiren (POST) her istek `CORS_ALLOWED_ORIGINS` listesinde bulunan bir `Origin`
başlığı ister; başlık yoksa ya da listede değilse yanıt **403**'tür. Bu bilinçli bir savunmadır
(tarayıcı `Origin`'i her zaman gönderir, bir saldırgan sayfası ise izinli bir değer gönderemez).
Sonuç olarak curl, Postman ve ileride servisler arası çağrılar da bu uçlarda `Origin` olmadan
reddedilir. Elle denemek için izinli bir origin ve CSRF çifti gerekir:

```bash
curl -si -c jar.txt http://localhost:5051/api/v1/auth/csrf           # restofy_csrf cookie'si ve token
curl -si -b jar.txt -c jar.txt -H 'Origin: http://localhost:5000' \
  -H "X-CSRF-TOKEN: $(grep restofy_csrf jar.txt | awk '{print $7}')" \
  -H 'Content-Type: application/json' \
  -d '{"email":"...","password":"..."}' http://localhost:5051/api/v1/auth/login
```

Servisler arası kimlik bu uçlardan geçmez; o, ayrı bir `Authorization: Service` sözleşmesidir.

### Geliştirme anahtarı `AUTH_MFA_REQUIRED`

Varsayılan `true`'dur. Yerel geliştirmede `false` yapılırsa ADMIN, MFA kurmadan/kod girmeden
parolayla tam oturum alır. Değer yalnız `dev`/`local`/`test` profilinde **ve** `APP_ENV`
boş ya da dev/local/test iken kabul edilir; aksi halde Auth `AUTH_MFA_REQUIRED` adını
belirten bir hatayla başlamaz. Başlangıçta WARN logu yazılır. Karar tek yerde verilir
(`EnvironmentPolicy`); bir mimari test, profil okumasının başka yerde yapılmasını engeller.

### Ölçek ve işletim notları

- Parola sıfırlama limitleri `pg_advisory_xact_lock` ile **global** serileştirilir: farklı
  kullanıcıların sıfırlama istekleri bu kısa kilitte sıraya girer. Mevcut yük için sorun
  değildir; yatay ölçekte darboğaz olabilir. Mail servisindeki iş kabulü de aynıdır.
  Kilit numaraları `AdvisoryLocks` sınıfında toplanmıştır (servis başına ayrı veritabanı
  olduğu için servisler arası çakışma olmaz).
- `MailClient` Mail'e bellek içi kuyrukla, commit sonrası ve **yeniden denemesiz** gönderir;
  Auth yeniden başlarsa ya da Mail kısa süre kesilirse mesaj kaybolur, kullanıcı yeni kod ister.
  Bu ödünleşim ve transactional outbox alternatifi ADR-AUTH-001'de kayıtlıdır.
- Kararların gerekçesi: `docs/decisions/ADR-AUTH-001-mail-gonderimi.md` ve
  `docs/decisions/ADR-AUTH-002-rate-limit-ve-kilitler.md`.

## Java 25 ve test

Yeni bir PowerShell'de (yalnız o süreç için, kalıcı Windows ayarı değiştirilmez):

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-25.0.4.1'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
java -version
javac -version
mvn -version
mvn -B -ntp -f backend/pom.xml -pl services/auth-service clean verify
.\pre-push\pre-push.cmd full dev
```

Test profili `backend/test-support/application-test.properties` içindeki
`jdbc:tc:postgresql:17-alpine:///restofy_test` ile ayrı, geçici PostgreSQL kullanır.
Geliştirme DB'si test hedefi değildir. PostgreSQL testlerini geçebilmek için Docker
çalışmalı ve test sürecinden erişilebilir olmalıdır. Docker izin hatası varsa izin
kısıtını aşmadan bu komutları normal PowerShell'de çalıştırın. Testleri atlamak veya
uygulama DB'sine yönlendirmek çözüm değildir.

`AuthTestDatabaseInitializer`, DB kullanan bütün Auth uygulama testlerinde context refresh'ten
önce çalışır; henüz datasource/Flyway bileşenleri oluşturulmadan yanlış ayarı reddeder.
Yalnız `test` profili, yukarıdaki tam Testcontainers URL'si ve `ContainerDatabaseDriver`
kabul edilir. Bağımsız Flyway bağlantısı, alternatif datasource/JNDI ve Hikari bağlantı
override'ları reddedilir; yalnız DB adının `restofy_test` olması yeterli değildir.
Ortam değişkenleri ve sistem özellikleri de etkin yapılandırma üzerinden denetlenir.
Ret mesajları yalnız ayar adını içerir; kimlik bilgilerini veya binding hatasını taşımaz.
Bu koruma yalnız test kaynaklarındadır; normal Auth başlangıcını değiştirmez.

`AuthTestDatabaseInitializerTests`, gerçek Spring Boot başlangıcı ve Boot'un
`FlywayMigrationInitializer` bileşeniyle sıralamayı doğrular. Yanlış hedef senaryolarında
datasource/Flyway oluşturulmadığı, bağlantı ve migration çağrılarının sıfır kaldığı
kontrol edilir. Bu senaryolarda bütün DB işlemleri mock'tur; gerçek DB'ye bağlanılmaz.
`StaffPersistenceTests.setup()` içindeki mevcut kontrol ikinci savunma olarak korunur.

`StaffPersistenceTests`: V1/Flyway/Hibernate uyumu, PostgreSQL kısıtları, eşzamanlı
benzersizlik/bootstrap, transaction rollback ve optimistic locking.
Diğer testler: e-posta, BCrypt 72 bayt sınırı, genel hata, pasif hesap, bootstrap profil
koruması/idempotency, rol ayrımı, anonim/başlıkla yetki yükseltme reddi ve parola
kontrolünün ADMIN oturumu oluşturmaması. Rol testlerindeki kimlikler test fixture'ıdır;
AUTH-02/03 oturumu veya MFA doğrulanmış sayılmaz.
BCrypt testi yalnız süreyi raporlar; tek örnek üretim kapasite garantisi değildir.

Full pre-push diğer servisleri/frontend'i de denetler; başarısız veya çalıştırılmamış
adımlar ayrıca raporlanır. SonarQube ayrı ve isteğe bağlıdır. Commit/push otomatik yapılmaz.

## Yerel geliştirme DB'sine uygulamadan önce

AŞAMA A salt okunur kontrolünde yerel `restofy_auth` üzerinde başarılı V1 ve
`staff_users` görüldü; V2 henüz yoktu. Bu bilgi her ortam için geçerli kabul edilmez.
Normal PowerShell'de önce aşağıdaki salt okunur kontrolü çalıştırın:

```powershell
docker compose --env-file .env.dev exec -T postgres sh -c 'test "$DB_AUTH_NAME" = restofy_auth && psql -X -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$DB_AUTH_NAME" -c "SELECT current_database(); SELECT installed_rank,version,description,success FROM flyway_schema_history ORDER BY installed_rank; SELECT to_regclass(''public.staff_users'');"'
```

Sonuçta hedef `restofy_auth` olmalı ve V1 başarılı, V2 uygulanmamış olmalıdır. Uygulanacak
V2 değişikliği iki session tablosunu ve kısıtlarını oluşturur; veri silmez. Auth'un kendi
datasource hedefini de doğrulamadan servisi başlatmayın. Hedef doğrulandıktan ve
testler geçtikten ve ayrıca açık uygulama onayı alındıktan sonra yalnız Auth servisini build/yeniden oluşturmak Flyway V2'yi
uygular; bu README herhangi bir DB'ye migration uygulanmış olduğu iddiası değildir.
`clean`, `repair`, `baseline`, veri/volume silme yapılmaz.

## AUTH-02 sözleşmesi

| Method / path | Yetki | Request / response |
| --- | --- | --- |
| GET `/api/v1/auth/csrf` | Anonim | `token` alanında yalnız CSRF değeri |
| POST `/api/v1/auth/login` | Anonim + CSRF + izinli Origin | `email,password,deviceLabel?` / session özeti |
| POST `/api/v1/auth/refresh` | Refresh cookie + CSRF + Origin | Gövde yok / session özeti |
| POST `/api/v1/auth/logout` | Kendi credential'ı + CSRF + Origin | Gövde yok / 204; idempotent |
| GET `/api/v1/auth/session` | Tam, MFA_PENDING veya PASSWORD_PENDING session | Session özeti |
| POST `/api/v1/auth/activity` | Tam session + CSRF + Origin | Gövde yok / 204 |
| POST `/api/v1/auth/password/change` | Tam veya PASSWORD_PENDING session + CSRF + Origin | `currentPassword,newPassword,locale?` / session özeti (AUTH-06) |

Session özeti yalnız `status,role,idleExpiresAt,absoluteExpiresAt` içerir.
Kimlik tokenları JSON gövdesinde dönmez. Doğrulama 400, geçersiz kimlik 401, CSRF/yetki
403, eşzamanlı sürüm çatışması 409, login limiti 429 (`Retry-After`) üretir.
Hatalar hassas veri içermeyen `application/problem+json`; cevaplar `no-store` kullanır.

Access JWT tek aktif RSA anahtarıyla RS256 imzalanır; `kid` ve anahtar halkası yoktur.
Claim'ler: `sub,iss,aud,iat,nbf,exp,jti,role,sid,token_type,auth_state,restaurant_id`.
Normal audience `restofy-staff-api`, MFA pending audience `restofy-auth-mfa`, parola pending
audience `restofy-auth-password` (`token_type=password_change`). Pending token'lar yalnız Auth içindir.
JJWT 0.13.0 builder/parser Clock sözleşmesi `java.util.Date` gerektirir. Zaman hesabı ve
karşılaştırmalar `Instant` ve enjekte `Clock` ile yapılır; `Date` yalnız kütüphane
sınırındaki dönüşümlerde kullanılır. Sonar'ın dosya düzeyindeki S2143 bulgusu bu zorunlu
uyumluluk nedeniyle açık kalır; Sonar'da kabul kararı ayrıca onay gerektirir. Kural
bastırma anotasyonu, profil değişikliği veya bulgu kapatma işlemi uygulanmaz.
İmza/algoritma, issuer, audience, tür, zaman ve restoran doğrulanır; sonra Auth'un kendi
DB'sinden kullanıcı aktifliği, rol ve session durumu kontrol edilir.
Diğer servislerin JWT/session doğrulaması, introspection ve servis kimliği bu teslimde yoktur.
Tek anahtarı değiştirmek mevcut access JWT'lerini geçersiz kılar; planlı rotasyon uygulanmadı.

`restofy_access`: HttpOnly, Path=/api/v1, en fazla 300 sn.
`restofy_refresh`: HttpOnly, Path=/api/v1/auth, session mutlak son tarihine kadar.
`restofy_mfa`: HttpOnly, Path=/api/v1/auth, en fazla 300 sn; refresh üretilmez.
`restofy_csrf`: JS okunabilir, Path=/api/v1, 300 sn. Bütün cookie'ler host-only,
SameSite=Lax; production'da Secure zorunludur. Cookie'ler portla ayrılmaz.
Logout sunucu iptaliyle birlikte aynı path'lerde cookie temizler.

CSRF, rastgele nonce + son tarih + HMAC içerir; refresh veya pending credential digest'ine
bağlanır. Giriş öncesi anonim bağlam kullanılır. POST isteklerinde `Origin` tam allowlist
eşleşmesi ve cookie ile aynı `X-CSRF-TOKEN` gerekir. Login ve refresh CSRF cookie'sini de
yeniler. UI sayfası `/api/v1` dışında olduğunda dar path'li CSRF cookie'si
`document.cookie` ile okunamaz; istemci ilk login öncesinde ve her başarılı login/refresh
sonrasında GET `/api/v1/auth/csrf` yanıtındaki yeni değeri alır ve sonraki POST'ta
`X-CSRF-TOKEN` olarak gönderir. Cross-origin fetch çağrıları `credentials: include`
kullanır. CSRF GET'i etkinlik sayılmaz; kimlik tokenları JavaScript'e açılmaz.
İzinli origin'lerde credential CORS açıktır; GET/POST/OPTIONS ve Content-Type/X-CSRF-TOKEN
dışında izin eklenmez. Farklı site deployment'ı ayrıca tasarlanmalıdır.

Her login ayrı session açar. Refresh 32 rastgele bayttan üretilir; DB yalnız SHA-256
digest saklar. Tek kullanım/rotation kullanıcı satırı kilidi altında atomiktir.
Reuse ilgili session ve bütün refresh geçmişini iptal eder; diğer cihazlar etkilenmez.
Bir refresh token'ın ilk kullanımından sonraki `REFRESH_REUSE_GRACE_SECONDS` (10 sn)
içinde gelen ikinci istek, aynı istemcinin eşzamanlı yenilemesi (iki sekme) sayılır:
session iptal edilmez ve istek **200** döner, ama **yeni kimlik bilgisi (cookie) yazılmaz**;
kazanan isteğin cevabı yeni çifti paylaşılan cookie kabına zaten yazmıştır. Böylece oturum
başına tek canlı refresh token değişmezi (`uq_refresh_current`) korunur ve çalıntı bir token
tekrar oynatıldığında yeni token almaz. Pencere ilk kullanımdan ölçülür ve tekrarlarla uzamaz;
süre dolduktan sonra aynı token reuse sayılır ve oturum iptal edilir. Ödünleşim: çalıntı bir
token ilk kullanımdan sonraki 10 sn içinde oturumu iptal ettirmez. Frontend yine de sekmeler
arası tek uçuşlu (single-flight) refresh yapmalıdır. Kazanan isteğin cevabı ağda kaybolursa
istemci eski token'la kalır ve pencere dolduktan sonraki ilk yenileme tekrar giriş gerektirir.

Session mutlak üst sınırı 604800 sn'dir; refresh bunu uzatmaz. Idle ADMIN/WAITER 900 sn,
KITCHEN 3600 sn. Pending ADMIN ayrıca 300 sn mutlak sınırla ve iş authority'si olmadan
kısıtlanır. Yalnız başarılı `/activity`, sunucu saatiyle last_activity_at günceller;
session okuma, polling, heartbeat ve refresh güncellemez. Frontend activity'yi yalnız
kullanıcı etkileşiminde göndermelidir; sunucu insan etkileşimini kriptografik kanıtlayamaz.
Pasifleştirme ve rol değişikliği Q5 onaylı iptal işlemiyle yapılır (AUTH-05); kullanıcı satırı
QR onayından sonra değişir. Doğrudan pasifleştirme de sonraki Auth kontrolünde erişimi reddeder ve iptali tamamlar.

Login limiti üç katmanlıdır (`LoginRateLimiter`, 15 dakikalık pencere; başarılı denemeler de
sayılır):

1. İstemci adresi başına, hesaptan bağımsız toplam 100 deneme. Yalnız `AUTH_TRUSTED_PROXY_CIDRS` ayarlıyken
   uygulanır; ayar yoksa herkes Gateway'in adresinden görünür ve bu bütçe herkesi birlikte kilitlerdi.
2. Normalize hesap + adres başına 10 deneme.
3. Hesap başına toplam 50 deneme.

E-postayı bilen biri kendi adresinin bütçesini tüketebilir, ama gerçek sahibini başka bir adresten
kilitleyemez. Birçok adrese yayılmış tahmin hesap tavanında durur; tavana ulaşmak kabul edilen erişilebilirlik
ödünleşimidir. Sayaçlar, bütün Auth örneklerinin paylaştığı PostgreSQL `rate_limits` tablosundadır. Tablo
dolunca uygulanan kural ve istemci adresinin Gateway'den nasıl alındığı ADR-AUTH-002'de anlatılır.
Refresh için rate limit bu kapsamda uygulanmadı.

## AUTH-02 zorunlu ayarlar ve işletim sınırı

Yeni ENV değerleri `AUTH_RESTAURANT_ID`, `AUTH_JWT_PRIVATE_KEY`, `AUTH_CSRF_SECRET`,
`AUTH_COOKIE_SECURE` olarak application.properties üzerinden varsayılansız beklenir.
RSA anahtarı Base64 PKCS#8, en az 2048 bit; CSRF sırrı ayrı Base64, en az 32 rastgele bayt
olmalıdır. Gerçek değerler dosyalara/loga/sohbete yazılmaz. Issuer ve CORS allowlist boş
olamaz. Q5 sözleşmesi gereği `JWT_ACCESS_TTL_SECONDS` tam 300 sn olmalıdır;
QR da aynı süreyi zorunlu tutar. 900/3600 dahil farklı değerler başlangıçta reddedilir.
`JWT_REFRESH_TTL_SECONDS` 86400–2592000 sn (1–30 gün) aralığında olmalıdır;
geliştirme örneği 604800'dür. Geçersiz ayar hatasında yalnızca ENV adı belirtilir,
gerçek değer yazılmaz.
Secure=false yalnız salt dev/local/test profillerinde kabul edilir; production origin'leri
HTTPS olmalıdır. Eksik/geçersiz ayarlar uygulamanın başlamasını durdurur.

Kök `.env.example` ve `docker-compose.yml` bu anahtarlarla güncellenmiştir (Alper onayıyla);
anahtarlar yalnız Auth container'ına aktarılır. Gerçek değerler Git dışındaki ENV
dosyalarında tutulur. Testler kendi JVM-içi anahtarlarını üretir.
V2 yalnız `user_sessions` ve `refresh_tokens` ekler. Canlı migration/rebuild ayrı onay
gerektirir. Audit tablosu, session listesi/silme API'si ve frontend ekranları kapsam dışındadır.

## AUTH-03 ADMIN MFA

MFA yalnız ADMIN içindir. Parola doğrulaması her zaman 300 saniyelik `MFA_PENDING`
session üretir; kayıtlı MFA yoksa bu session kendi hesabında enrollment başlatabilir.
MFA zorunludur; devre dışı bırakma, TOTP değiştirme ve otomatik sıfırlama endpoint'i yoktur.
İlk ADMIN bootstrap aynı şekilde enrollment'a ulaşır; MFA atlama yetkisi sağlamaz.

Backend `secret` ve `otpauthUri` döndürür; QR görselini frontend `otpauthUri`'den üretir. Gizli anahtar ve URI yalnız enrollment yanıtında bir kez gösterilir.

| Method / path | Scope | Request / response |
| --- | --- | --- |
| POST `/api/v1/auth/mfa/enroll` | Pending ADMIN, henüz etkin MFA yok | Gövde yok / `secret,otpauthUri,expiresAt` |
| POST `/api/v1/auth/mfa/enroll/confirm` | Enrollment'a bağlı pending ADMIN | `code` / `session,recoveryCodes` |
| POST `/api/v1/auth/mfa/verify` | Pending ADMIN, etkin MFA | `code` / session özeti |
| POST `/api/v1/auth/mfa/recover` | Pending ADMIN, etkin MFA | `code` / session özeti |
| POST `/api/v1/auth/mfa/recovery-codes/regenerate` | Tam ADMIN + parola + yeni faktör | `password,totpCode` veya `password,recoveryCode` / `recoveryCodes` |

Yeni anonim endpoint yoktur. Her POST mevcut izinli Origin ve `X-CSRF-TOKEN` gerektirir.
`GET /api/v1/auth/session`, yalnız kendi pending kimliği için `mfaSetupRequired` döndürür;
ayrı MFA status endpoint'i yoktur. Hatalar mevcut ProblemDetail şemasını kullanır:
400 biçim, 401 kimlik/faktör, 403 scope/CSRF/Origin, 409 kurulum çakışması, 429 limit
(`Retry-After`), 503 şifreleme altyapısı hatası. Hata detaylarında sır veya girdi yoktur.

TOTP: HMAC-SHA1, 6 hane, 30 saniye, ±1 adım, 160 bit rastgele sır ve padding'siz Base32.
Issuer `Restofy`, label `Restofy:<normalized-email>`; URI yalnız kurulum yanıtında döner.
Sunucu UTC saati güvenilir biçimde senkron tutulmalıdır. Kabul edilen counter hesap
genelinde monoton artar; başka cihazda aynı adım tekrar kullanılamaz. İleri adım kabul
edildiyse daha eski adım da reddedilir. Kurulum onayı ilk counter'ı tüketir.

Bekleyen sır, bağlı session'ın son tarihini aşmadan en fazla 300 saniye tutulur.
Başlatmalar arasında 60 saniye gerekir. Aynı session yeniden başlatırsa eski sır
geçersizleşir; başka session devam eden kurulumu devralamaz. URI/sır için tekrar okuma
endpoint'i yoktur; yanıt kaybolursa kontrollü yeniden başlatma gerekir.

TOTP sırları AES-256-GCM ile, her yazmada yeni 96 bit nonce ve 128 bit tag kullanılarak
şifrelenir. AAD, amaç etiketi ve kullanıcı kimliğine bağlıdır. V3 ciphertext'i `BYTEA`
olarak tutar; anahtar DB'de bulunmaz. Yeni zorunlu ayar:
`auth.mfa.encryption-key=${AUTH_MFA_ENCRYPTION_KEY}`. Değer Base64 kodlu 32 rastgele bayt
olmalıdır; signing/CSRF anahtarı yeniden kullanılmamalıdır. Eksik, bozuk, yanlış uzunlukta
veya açıkça zayıf anahtar başlangıcı durdurur. Biçimce geçerli fakat yanlış anahtar,
AAD uyuşmazlığı veya bozuk ciphertext doğrulamada 503 ile fail closed olur; sır açıklanmaz.
Anahtar rotasyonu/halkası yoktur. Anahtar ve DB yedekleri ayrı korunmalı ve birlikte
geri yüklenebilmelidir; kayıp anahtar eski TOTP sırlarının geri alınamaması demektir.

Recovery seti 10 adet, her biri 100 bit entropili 20 Base32 karakterinden oluşur;
gösterim 5×4 gruptur. DB yalnız kullanıcı/amaçla ayrıştırılmış SHA-256 digest tutar.
Karşılaştırmalar sabit uzunluklu digest'lerle yapılır; tüketim koşullu DB UPDATE'tir.
Kodlar yalnız üretim/yenileme yanıtında bir kez gösterilir, sunucudan tekrar okunamaz.
Yanıt kaybolursa güncel parola ve kullanılmamış faktörle yeni set üretilmelidir.
Recovery kullanımı ilgili pending session'ı yükseltir; TOTP sırrını otomatik değiştirmez.
Yenileme eski kodları ve diğer cihaz session'larını iptal eder; işlemin session'ı korunur.

Yükseltme aynı session kimliğini korur, yeni access/ilk refresh çifti verir; pending
cookie temizlenir ve CSRF yeni refresh'e bağlanır. Eski pending token okuma, yükseltme
ve logout üzerinden tam session'a etki edemez. Mutlak ömür parola girişinden 604800 sn,
ADMIN idle MFA tamamlanmasından itibaren 900 sn'dir. MFA gerçek kullanıcı etkinliğidir;
polling/refresh/okuma idle süresini yenilemez. Başarılı yükseltmeden sonra istemci CSRF
token'ını yeniden almalıdır. Access/refresh tokenları yanıtta veya JS store'da tutulmaz.

TOTP, recovery ve enrollment onayı aynı DB başarısızlık sayacını paylaşır. Kontrol
kod doğrulamadan önce yapılır. Beş dakikada beş yanlış deneme beş dakikalık geçici
kilit ve 429 üretir; yeni parola girişi ya da enrollment başlatma sayacı sıfırlamaz.
Başarılı MFA sayacı temizler. `MfaAttemptLimiter.attempt`, Spring proxy üzerinden
`REQUIRES_NEW` transaction açar; 401/429 için rollback yapılmaz. MFA giriş servisleri
transaction başlatmadan bu sınıra girer; çağıran transaction'ın rollback'i sayacı geri
almaz. Kullanıcı satır kilidi account genelinde yarışları sıralar; başarılı faktör
tüketimi ve yükseltme aynı transaction'dadır. Teknik hatalar transaction'ı geri alır.
Bu sınır, aynı kullanıcı/MFA satırını kilitlemiş dış transaction içinden çağrılmamalıdır.

Adres limiti (`MfaPeerLimiter`) istemci adresi başına 60 saniyede 30 istektir. Sayaç, bütün Auth
örneklerinin paylaştığı PostgreSQL `rate_limits` tablosundadır; yeniden başlatmada kaybolmaz.
İstemci adresi `ClientAddressResolver` ile bulunur: `X-Forwarded-For` yalnız bağlantı
`AUTH_TRUSTED_PROXY_CIDRS` içindeki Gateway'den geliyorsa okunur. Ayar boşsa Gateway arkasındaki
herkes Gateway'in adresiyle sayılır. Tablo dolunca kuralı ADR-AUTH-002 anlatır.
Hesap kilidi mevcut doğrulanmış diğer cihazların yetkisini kendiliğinden kaldırmaz.

### Bilinen güvenlik ve işletim sınırları

- İlk enrollment'ı ADMIN parolasını bilen biri başlatabilir. Henüz kayıtlı ikinci
  faktör olmadığından, çalınmış parola ile ilk faktörün saldırgan tarafından kurulması
  riski vardır. İlk ADMIN parolası ve ilk kurulum güvenilir kanalda korunmalıdır.
- Audit yapılandırılmış uygulama logudur; dayanıklı/transactional audit deposu değildir.
  Başarı olayları commit sonrasında yazılır; commit ile log yazımı arasında süreç
  kapanırsa olay kaybolabilir. Şifreleme arızası güvenli operasyonel olay olarak yazılır.
  Log taşıma, erişim ve retention ayrı işletim sorumluluğudur.
- Parola, OTP, TOTP sırrı, recovery kodu, URI, token ve cookie loglanmaz. Hassas DTO'ların
  `toString` çıktıları içerik taşımaz; hassas HTTP yanıtları `Cache-Control: no-store` kullanır.
- TOTP ve recovery birlikte kaybolursa otomatik kurtarma yoktur; tek ADMIN kilitlenebilir.
  Kimlik doğrulamalı, kayıtlı ve işlem bazında onaylı operasyonel sıfırlama prosedürü
  ayrıca kararlaştırılmalıdır. Geliştirici sessiz DB değişikliği yapmamalıdır.
- V3 uygulanmış V1/V2'yi değiştirmez; iki MFA tablosu ekler ve ADMIN'in iki auth durumunu
  kabul eden CHECK oluşturur. Migration mevcut pending session'ları yükseltmez veya
  sürelerini yenilemez. Tam ADMIN satırları varken eski V2 CHECK'ine geri dönülemez.
- Kök ENV şablonu ve Compose, MFA anahtarı için güncellenmiştir (Alper onayıyla).
  Gerçek MFA anahtarını işletici hazırlamalıdır. Canlı V3/rebuild ve dev ADMIN enrollment
  ayrıca izin gerektirir. DB/migration/HTTP entegrasyon testleri Docker ile doğrulanmalıdır.

## AUTH-05 ADMIN personel yönetimi

ADMIN kendi restoranının personelini yönetir (F-AD-01). Tek restoran kurulumunda bütün personel
`AUTH_RESTAURANT_ID` restoranına aittir; başka restoranın personeli Auth'ta bulunmaz. Uçlar yalnız
ön yüz içindir, diğer servisler kullanmaz. Hepsi MFA'sı tamamlanmış tam ADMIN oturumu ister; POST'lar
ayrıca izinli `Origin` ve `X-CSRF-TOKEN` ister. Yetki yalnız oturumdan alınır, istemcinin rol veya
kullanıcı başlıkları dikkate alınmaz. Migration ve yeni ENV yoktur.

| Method / path | Gövde | Başarılı cevap | Uca özgü hatalar |
| --- | --- | --- | --- |
| GET `/api/v1/auth/staff?page=0&size=50` | Yok | 200 `items,page,size,hasNext` | 400 (`size` 1–100, `page` 0–10000) |
| POST `/api/v1/auth/staff` | `email,password,role` (ADMIN için ek `confirmation`) | 201 personel | 400, 401 `STEP_UP_REJECTED`, 409, 429 |
| POST `/api/v1/auth/staff/{id}/role` | `role,version,operationId` (ADMIN için ek `confirmation`) | 200 uygulandı, 202 QR onayı bekliyor | 400, 401 `STEP_UP_REJECTED`, 404, 409, 429, 503 |
| POST `/api/v1/auth/staff/{id}/deactivate` | `version,operationId` | 200 uygulandı, 202 QR onayı bekliyor | 400, 404, 409, 429, 503 |
| POST `/api/v1/auth/staff/{id}/password` | `password,version` | 200 personel (AUTH-06 geçici parola) | 400, 404, 409, 429, 503 |

Personel cevabı: `id,email,role,active,version,createdAt,pendingChange,passwordChangeRequired`; parola ya da hash hiçbir
cevapta yoktur. Bütün uçlarda `reason` taşımayan 401 oturum yok/geçersiz, 403 rol, eksik MFA, CSRF veya
Origin demektir. Liste e-postaya göre sıralıdır.

**Neden kodları.** Bu uçların kendi retleri problem gövdesinde `reason` taşır; ön yüz tepkisini buna göre seçer:

| `reason` | Durum | Anlamı ve ön yüzün tepkisi |
| --- | --- | --- |
| `EMAIL_TAKEN` | 409 | Adres başka bir hesapta |
| `SELF_CHANGE` | 409 | ADMIN kendi rolünü değiştiremez, kendini pasifleştiremez |
| `STALE_VERSION` | 409 | Kişi bu arada değişti; listeyi yeniden yükle |
| `NOT_ACTIVE` | 409 | Kişi zaten pasif |
| `ADMINISTRATOR_TARGET` | 409 | ADMIN'e geçici parola verilemez; ADMIN kendi parolasını değiştirir ya da sıfırlar |
| `NO_CHANGE` | 409 | Kişi zaten bu rolde |
| `TRANSITION_PENDING` | 409 | Kişinin önceki değişikliği QR onayını bekliyor; bitmesini bekle |
| `LAST_ADMINISTRATOR` | 409 | Aktif ADMIN kalmaz (bugünkü akışta oluşmaz, ek savunma) |
| `OPERATION_MISMATCH` | 409 | `operationId` başka kişi ya da değişiklik için kullanılmış; yeni UUID üret |
| `STEP_UP_REQUIRED` | 400 | ADMIN yetkisi veren istekte `confirmation` yok |
| `STEP_UP_REJECTED` | 401 | `confirmation` yanlış; **oturum geçerli**, kullanıcıdan tekrar iste |
| `BUSY` | 503 ve `Retry-After: 1` | Başka bir personel değişikliği kaydediliyor; hiçbir şey yapılmadı, aynı isteği tekrar gönder |

`reason` taşımayan 503 (Q5 kaynağı hazır değil, ya da işlemi yapanın oturumu bekleyen bir değişiklik
yüzünden kapalı) de aynı istekle güvenle tekrar denenir.

- **ADMIN yetkisi vermek ek doğrulama ister** (`docs/SECURITY.md` §3: kritik yönetici eylemleri için
  yeniden doğrulama). ADMIN oluşturma ve ADMIN'e yükseltme isteği, işlemi yapanın kendi parolasını ve
  tek bir ikinci faktörü taşır: `"confirmation":{"password":"...","totpCode":"123456"}` ya da
  `totpCode` yerine `recoveryCode`. Biçim kurtarma kodu yenilemeyle aynıdır. Böylece çalınmış bir oturum
  çerezi tek başına kalıcı bir yönetici hesabı açamaz. Yanlış denemeler girişle aynı MFA kilidine sayılır
  (5 hatada 5 dk, 429). Kod bu istekte harcanır: değişiklik sonradan reddedilse bile (ör. `STALE_VERSION`)
  yeni bir kod gerekir. Zaten kayıtlı bir ADMIN yükseltmesinin aynı `operationId` ile tekrarı yeni bir şey
  yaratmadığı için ek doğrulama istemez. Rol düşürme ve pasifleştirme yetki vermez, ek doğrulama istemez.
- **İlk parola:** ADMIN belirler; AUTH-01 parola kuralı geçerlidir. ADMIN rolündeki personel ilk
  girişte MFA kurar (`mfaSetupRequired=true`; kurulana kadar personel uçları 403). Yeni personel ilk
  girişte kendi parolasını seçer (AUTH-06, `passwordChangeRequired=true`); böylece ADMIN'in bildiği
  parola kullanılabilir bir oturum açmaz. Bekleyen bir ADMIN sıfırlaması, kişi pasifleşir ya da ADMIN
  olmaktan çıkarsa tamamlanamaz (onay adımı aktif ADMIN arar).
- **Rol değişikliği ve pasifleştirme** Q5 `ACK_REQUIRED` işlemidir. Auth işlemi kaydeder; QR kişinin
  oturumlarını kapattığını onaylayınca rol ya da aktiflik yazılır ve oturumlar iptal edilir. Oturumu
  olmayan kişide değişiklik hemen uygulanır (200). QR'a ulaşılamazsa cevap 202 ve
  `pendingChange=true` olur. Bu sürede kişi Auth'ta oturum kullanamaz (503) ve ona yeni bir değişiklik
  kabul edilmez (`TRANSITION_PENDING`). Retry worker teslimi sürdürür; QR hiç onay vermezse işlem, hedef
  token'ların süresi dolunca (en çok 300 sn) tamamlanır.
- **`operationId`:** istemcinin her yeni değişiklik için ürettiği UUID'dir. Tekrar denemede aynı istek
  aynı `operationId` ile gönderilir; 200 dönünce değişiklik tamamlanmıştır. Tekrarı başka bir ADMIN de
  gönderebilir (denetimde `retry=true` ile görünür). Uzun süre önce tamamlanmış bir `operationId` tekrar
  gönderilirse yeni bir şey yapılmaz ve cevap kişinin **bugünkü** halidir, o işlemin hemen sonraki hali
  değildir.
- **`version`:** listede görülen değerdir; uyuşmazsa `STALE_VERSION` (başkası önce değiştirdi).
- **Son ADMIN:** ADMIN kendi rolünü değiştiremez, kendini pasifleştiremez. Bütün rol ve pasifleştirme
  istekleri `AdvisoryLocks.STAFF_ADMINISTRATION` kilidiyle sıraya girer; kilit doluysa istek beklemez,
  `BUSY` döner. Bir ADMIN'i çıkaran istek, aktif ve bekleyen değişikliği olmayan başka bir ADMIN
  kalmıyorsa reddedilir. Bugünkü akışta bu ret oluşmaz (işlemi yapan kendisi aktif ADMIN'dir ve hedef
  olamaz); ileride personeli başka yoldan değiştiren bir akış eklenirse değişmezi korur. Çıkışta olan
  ADMIN'in oturumu 503 aldığı için karşıdaki ADMIN'i de çıkaramaz.
- **Limit:** ADMIN başına 15 dakikada 30 değişiklik isteği (`StaffAdministrationLimiter`, ortak
  `rate_limits` tablosu); aşılınca 429 ve `Retry-After`. Liste okuma limitsizdir.
- **Denetim:** `StaffAuditLogger` işlemi yapan, hedef, rol, işlem kimliği ve `retry` alanını yazar;
  e-posta ve parola yazılmaz. `CREATED` commit sonrası yazılır. `ROLE_CHANGE_REQUESTED` ve
  `DEACTIVATION_REQUESTED`, Q5 işlemi kendi transaction'ında kaydedilir kaydedilmez ve tekrarlar dahil her
  istek için yazılır. Ek doğrulama `mfa_event=STEP_UP_VERIFIED` olarak görünür. Tamamlanma Q5 logunda
  aynı işlem kimliğiyle görünür. Dayanıklı bir audit deposu değildir (AUTH-03 sınırı).
- **Veritabanını elle değiştirmeyin.** Bu uçlardan önce pasifleştirme ve rol değişikliği veritabanından
  deneniyordu; artık uçlar kullanılmalı. Elle yapılan değişiklik Q5 iptalini atlar. Kişinin bekleyen bir
  Q5 işlemi varken sürümü elle değiştirilirse o işlem "reconciliation" hatasıyla tamamlanamaz ve kişi elle
  düzeltilene kadar kilitli kalır. Uygulama kodunda bunu yapan bir yol yoktur (parola sıfırlama, bekleyen
  işlem varken 503 döner).
- **Bağlantı havuzu:** bir değişiklik isteği, dış transaction açıkken Q5 kaydı için kısa süre ikinci bir
  bağlantı kullanır. Kilit beklenmeyip yalnız denendiği için aynı anda en çok bir istek bunu yapar.
- **Diğer servisler:** değişiklik `/session` kullanan servislere anında, yalnız yerel token doğrulayanlara
  en geç 300 sn içinde yansır (`docs/auth-service/staff-contract.md`).

Kapsam dışı, ayrı PR: e-posta değiştirme, yeniden aktifleştirme, personelin MFA'sını sıfırlama ve
personel yönetimi ön yüz ekranı.

## AUTH-06 parola değiştirme ve geçici parola

Migration `V9`: `staff_users.password_change_required` (varsayılan `false`), `user_sessions.auth_state`
kısıtına `PASSWORD_PENDING`, ve `rate_limits` tahliye indeksinin taradığı sıraya göre yeniden kurulması.
Yeni ENV yoktur.

- **Kendi parolası:** her rol `POST /api/v1/auth/password/change` ile mevcut parolasını ve yeni parolasını
  gönderir. AUTH-01 parola kuralı geçerlidir. Başarılıysa kişinin **diğer** oturumları kapanır (Q5
  `LOCAL_FIRST`), bu oturum açık kalır, çerezleri değişmez. Kişiye `PASSWORD_CHANGED` e-postası gider
  (commit sonrası, en iyi çaba; gönderilemezse yalnız WARN loglanır).
- **Retler** 400 ve `reason` taşır: `CURRENT_PASSWORD_REJECTED` (mevcut parola yanlış),
  `WEAK_PASSWORD` (kurala uymuyor), `SAME_PASSWORD` (yeni parola eskisiyle aynı). Oturum başına 15 dakikada
  5 deneme, başarılılar dahil (`PasswordChangeLimiter`, ortak `rate_limits` tablosu); aşılınca 429 ve `Retry-After`.
  Oturum başına olması bilinçlidir: yeni oturum açmak parolayı bilmeyi gerektirir, ve geçici parola eski
  oturumları kapattığı için kişi yeni girişte kilitli kalmaz.
- **Zorunlu değiştirme (`PASSWORD_PENDING`):** ADMIN'in oluşturduğu personel ve geçici parola verilen
  personel, girişte tam oturum yerine 600 sn'lik `PASSWORD_PENDING` oturumu alır. Bu oturum yalnız
  `restofy_mfa` çerezini (`Path=/api/v1/auth`) taşır; yalnız `/session`, `/password/change` ve `/logout`
  kullanılabilir, diğer uçlar 401/403 döner. Token'ı access çerezine konursa reddedilir. Parola
  değiştirilince oturum `AUTHENTICATED` olur ve access/refresh çerezleri bu cevapta yazılır. ADMIN'de
  sıra MFA → parola: MFA tamamlanınca durum `PASSWORD_PENDING` olur.
- **Geçici parola:** ADMIN, aktif WAITER veya KITCHEN personele `POST /api/v1/auth/staff/{id}/password`
  ile geçici parola verir (`version` listede görülen değerdir). Kişinin bütün oturumları aynı
  transaction'da kapanır (Q5 `LOCAL_FIRST`, `ALL`) ve sonraki girişte parola değiştirmesi zorunludur.
  Cevap doğrudan 200'dür; aynı isteğin tekrarı `STALE_VERSION` alır. ADMIN hedef `ADMINISTRATOR_TARGET`,
  kendisi `SELF_CHANGE`, pasif kişi `NOT_ACTIVE`, bekleyen değişiklik `TRANSITION_PENDING` alır. Limit
  ve kilit AUTH-05 ile ortaktır.
- **Denetim:** `staff_event=TEMPORARY_PASSWORD_ASSIGNED actor_id= target_id=` ve
  `password_event=CHANGED user_id= required=` commit sonrası yazılır; parola ve e-posta yazılmaz.
- **Sınır:** tam oturumda parola değiştirmek bu oturumun token'larını yenilemez; mevcut access token
  süresi dolana kadar geçerlidir (oturum başına tek canlı refresh token kuralı korunur).
