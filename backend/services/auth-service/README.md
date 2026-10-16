# Auth Service — AUTH-01

AUTH-01; tek restoran kurulumu ve personel başına tek rol için personel/parola altyapısıdır.
Kullanılabilir HTTP login, oturum veya MFA sağlamaz. ADMIN dahil tüm iş HTTP yolları kapalıdır.
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
Yalnız mevcut GET health/liveness/readiness yolları anonim açıktır; diğer tüm HTTP yolları
ADMIN dahil reddedilir. Basic/form login kapalıdır, varsayılan Spring kullanıcısıyla
geçici giriş yoktur. Sunucu HTTP oturumu oluşturmaz veya mevcut oturum kimliğini yüklemez.
CSRF varsayılan koruması açıktır. İstemci rol/kullanıcı/restoran header'ları işlenmez.
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

Kullanıcının önceki kontrolüne göre yerel `restofy_auth` Flyway geçmişi boş ve
`staff_users` yoktur. Bu bilgi her ortam için geçerli kabul edilmez.
Normal PowerShell'de önce aşağıdaki salt okunur kontrolü çalıştırın:

```powershell
docker compose --env-file .env.dev exec -T postgres sh -c 'test "$DB_AUTH_NAME" = restofy_auth && psql -X -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$DB_AUTH_NAME" -c "SELECT current_database(); SELECT installed_rank,version,description,success FROM flyway_schema_history ORDER BY installed_rank; SELECT to_regclass(''public.staff_users'');"'
```

Sonuçta hedef `restofy_auth` olmalı ve V1 henüz uygulanmamış olmalıdır. Uygulanacak
değişiklik yalnız bu tabloda CREATE TABLE ve kısıtlardır; veri silmez. Auth'un kendi
datasource hedefini de doğrulamadan servisi başlatmayın. Hedef doğrulandıktan ve
testler geçtikten sonra yalnız Auth servisini build/yeniden oluşturmak Flyway V1'i
uygular; bu README herhangi bir DB'ye migration uygulanmış olduğu iddiası değildir.
`clean`, `repair`, `baseline`, veri/volume silme yapılmaz.
