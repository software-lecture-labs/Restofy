# Auth Service — AUTH-01 / AUTH-02

AUTH-01; tek restoran kurulumu ve personel başına tek rol için personel/parola altyapısıdır.
AUTH-02 aşağıdaki HTTP oturum sözleşmesini ekler. TOTP ve tam ADMIN girişi AUTH-03 kapsamındadır.
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
| GET `/api/v1/auth/session` | Tam veya MFA_PENDING session | Session özeti |
| POST `/api/v1/auth/activity` | Tam session + CSRF + Origin | Gövde yok / 204 |

Session özeti yalnız `status,role,idleExpiresAt,absoluteExpiresAt` içerir.
Kimlik tokenları JSON gövdesinde dönmez. Doğrulama 400, geçersiz kimlik 401, CSRF/yetki
403, eşzamanlı sürüm çatışması 409, login limiti 429 (`Retry-After`) üretir.
Hatalar hassas veri içermeyen `application/problem+json`; cevaplar `no-store` kullanır.

Access JWT tek aktif RSA anahtarıyla RS256 imzalanır; `kid` ve anahtar halkası yoktur.
Claim'ler: `sub,iss,aud,iat,nbf,exp,jti,role,sid,token_type,auth_state,restaurant_id`.
Normal audience `restofy-staff-api`, pending audience `restofy-auth-mfa`.
JJWT 0.13.0 builder/parser Clock sözleşmesi `java.util.Date` gerektirir. Zaman hesabı ve
karşılaştırmalar `Instant` ve enjekte `Clock` ile yapılır; `Date` yalnız kütüphane
sınırındaki dönüşümlerde kullanılır. Sonar'ın dosya düzeyindeki S2143 bulgusu bu zorunlu
uyumluluk nedeniyle açık kalır; Sonar'da kabul kararı ayrıca onay gerektirir. Kural
bastırma anotasyonu, profil değişikliği veya bulgu kapatma işlemi uygulanmaz.
İmza/algoritma, issuer, audience, tür, zaman ve restoran doğrulanır; sonra Auth'un kendi
DB'sinden kullanıcı aktifliği, rol ve session durumu kontrol edilir.
Diğer servislerin JWT/session doğrulaması, introspection ve servis kimliği bu teslimde yoktur.
Tek anahtarı değiştirmek mevcut access JWT'lerini geçersiz kılar; planlı rotasyon uygulanmadı.

`restofy_access`: HttpOnly, Path=/api/v1, en fazla 900 sn.
`restofy_refresh`: HttpOnly, Path=/api/v1/auth, session mutlak son tarihine kadar.
`restofy_mfa`: HttpOnly, Path=/api/v1/auth, en fazla 300 sn; refresh üretilmez.
`restofy_csrf`: JS okunabilir, Path=/api/v1, 900 sn. Bütün cookie'ler host-only,
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
İki sekmenin eşzamanlı refresh'i de reuse sayılabilir; frontend sekmeler arası koordinasyon
sağlamalıdır. Ağda kaybolan rotation cevabı sonrası tekrar giriş gerekebilir.

Session mutlak üst sınırı 604800 sn'dir; refresh bunu uzatmaz. Idle ADMIN/WAITER 900 sn,
KITCHEN 3600 sn. Pending ADMIN ayrıca 300 sn mutlak sınırla ve iş authority'si olmadan
kısıtlanır. Yalnız başarılı `/activity`, sunucu saatiyle last_activity_at günceller;
session okuma, polling, heartbeat ve refresh güncellemez. Frontend activity'yi yalnız
kullanıcı etkileşiminde göndermelidir; sunucu insan etkileşimini kriptografik kanıtlayamaz.
`StaffSessionService.deactivate` kullanıcı ve bütün session'ları aynı transaction'da
kapatır. Doğrudan pasifleştirme de sonraki Auth kontrolünde erişimi reddeder ve iptali tamamlar.

Login limiti normalize hesap başına 15 dakikada 10 denemedir; başarılı denemeler de sayılır.
En fazla 10000 hesap penceresi tutulur; kapasite dolunca yeni hesaplar 429 alır.
Bu limit yalnız tek süreç içindir; yeniden başlatmada sıfırlanır. IP/Gateway/distributed
rate limit ve refresh rate limit bu kapsamda uygulanmadı.

## AUTH-02 zorunlu ayarlar ve işletim sınırı

Yeni ENV değerleri `AUTH_RESTAURANT_ID`, `AUTH_JWT_PRIVATE_KEY`, `AUTH_CSRF_SECRET`,
`AUTH_COOKIE_SECURE` olarak application.properties üzerinden varsayılansız beklenir.
RSA anahtarı Base64 PKCS#8, en az 2048 bit; CSRF sırrı ayrı Base64, en az 32 rastgele bayt
olmalıdır. Gerçek değerler dosyalara/loga/sohbete yazılmaz. Issuer ve CORS allowlist boş
olamaz; mevcut TTL anahtarları bu sözleşmede 900/604800 olmalıdır.
Secure=false yalnız salt dev/local/test profillerinde kabul edilir; production origin'leri
HTTPS olmalıdır. Eksik/geçersiz ayarlar uygulamanın başlamasını durdurur.

Kök `.env.example` ve Compose bu görevde değiştirilmedi. Alper onayından sonra yeni dört
anahtarın şablonu ve yalnız Auth container'ına aktarımı hazırlanmalıdır; mevcut container
henüz bu kodun çalıştığını kanıtlamaz. Testler kendi JVM-içi anahtarlarını üretir.
V2 yalnız `user_sessions` ve `refresh_tokens` ekler. Canlı V2 uygulaması/rebuild ayrı onay
gerektirir; bu teslim bu işlemlere izin vermez. Audit tablosu, session listesi/silme API'si,
MFA doğrulaması ve frontend ekranları kapsam dışındadır.
