# Menu Service entegrasyon test planı (MENU-INT-06)

- **Tarih:** 25 Kasım 2026
- **Temel alınan kod:** `origin/dev` @ `ea70de0`
- **Rehber:** [Entegrasyon rehberi](integration-guide.md)

Bu belge hedef akışları, bugün gerçekten çalıştırılabilenleri ve bloke olanları ayırır. **Hiçbir senaryo, çalıştırılmadıysa geçmiş
gibi gösterilmez.**

## 1. Adlandırma kuralı: neye ne denir

| Ad | Anlamı | Bu planda kullanılan test |
|---|---|---|
| **Gerçek E2E** | Birden çok **gerçek** servis süreci, gerçek ağ ve gerçek servis kimliği | **Hiçbiri.** `dev`'de mümkün değil (bkz. bölüm 4) |
| **Açılış (boot) testi** | Gerçek sunucu ve gerçek PostgreSQL, üretim bağlaması, başka servis yok | `ProductionWiringHttpSmokeTests` |
| **Yüzeyler arası yolculuk (stand-in'li)** | Menu gerçek; Auth/QR/Kitchen kimlikleri `src/test` içindeki sahte sağlayıcılar | `CrossSurfaceAvailabilityJourneyTests` |
| **Tek yüzey testi** | Belirli bir özelliğin kendi paketi | Mevcut 1350 test |

Sahte kimlik sağlayıcılar (`TestActorProvider`, `TestKitchenCallerProvider`, `TestPublicScopeConfiguration`) paketlenmez ve gerçek
doğrulamanın modeli değildir. Onlarla yapılan testler **Menu'nun** davranışını sınar; Auth, Kitchen veya QR'ın davranışını sınamaz.

## 2. Durum etiketleri

| Etiket | Anlamı |
|---|---|
| **ÇALIŞTI** | Bu çalışmada `dev` üzerinde çalıştırıldı ve geçti |
| **STAND-IN** | Çalıştı, ama karşı servis sahte sağlayıcıdır; gerçek entegrasyon kanıtı değildir |
| **PR'DA** | Kod ve testleri açık bir PR'da; `dev`'de yok, bu çalışmada çalıştırılmadı |
| **BLOKE** | Karar, uç veya servis kimliği olmadığı için yazılamaz/çalıştırılamaz |

## 3. Senaryo matrisi

### Auth → Menu

| # | Senaryo | Etiket | Kanıt / neden |
|---|---|---|---|
| A-01 | Anahtarsız (bugünkü üretim) hiçbir yönetim isteği geçmez; sahte `X-Role`/`X-Restaurant-Id`/`Service` başlıkları hiçbir şey açmaz | **ÇALIŞTI** | `ProductionWiringHttpSmokeTests.everyStaffRouteRefuses…` (gerçek HTTP), mevcut `FailClosedProductionWiringTests` |
| A-02 | Personel (ADMIN) ile müşteri/servis kimliği birbirinin yerine geçmez | **STAND-IN** | `CrossSurfaceAvailabilityJourneyTests` (J-03) |
| A-03 | WAITER/KITCHEN rolü yönetim ucundan availability değiştiremez | **STAND-IN** | J-03; rol kontrolü Menu'dadır, rolün kaynağı sahtedir |
| A-04 | Başka restoranın ADMIN'i ürünü göremez/değiştiremez | **STAND-IN** | J-04 |
| A-05 | Gerçek Auth token'ı ile yönetim uçları, CSRF/Origin, sahte token'lar | **PR'DA** | Doğrulayıcı `feature/menu-service` dalında (MENU-INT-01); 24 Kasım'da izole ortamda gerçek Auth koduyla denendi (ADR-MENU-005). `dev`'de çalıştırılamaz |
| A-06 | Paylaşılan Docker ortamında Auth → Menu | **BLOKE** | `MENU_AUTH_STAFF_PUBLIC_KEY` ENV/Compose onayı yok; ortamdaki Auth imajı eski ve MFA zorunlu |
| A-07 | Anlık oturum iptali | **BLOKE** | Auth sözleşmesinde yok. Gerçek Auth'la ölçüldü: logout sonrası Menu en çok 300 sn kabul eder |

### Order → Menu

| # | Senaryo | Etiket | Kanıt / neden |
|---|---|---|---|
| O-01 | Order'a ait hiçbir iç yol Menu'da yoktur ve sahte servis başlıkları bir şey işlemez | **ÇALIŞTI** | `ProductionWiringHttpSmokeTests.noInternalRoute…` (önerilen yol dahil) |
| O-02 | Toplu ürün/fiyat/KDV sorgusu, KDV hariç ve ondalık fiyat kuralları, restoran izolasyonu | **PR'DA** | PR #49 (`OrderCatalogService`, kendi testleri). `dev`'de kod yok |
| O-03 | Order → Menu gerçek çağrı | **BLOKE** | Uç biçimi ve D-09 kararı yok; Order'da gerçek adaptör yok (üretimde fail-closed, dev'de bellek içi sahte katalog) |
| O-04 | Order'ın sahte kataloğuyla yapılan akış | Sayılmaz | Menu'yu sınamaz; E2E olarak adlandırılmaz |

### Kitchen → Menu

| # | Senaryo | Etiket | Kanıt / neden |
|---|---|---|---|
| K-01 | Komut işleyicisi: geçişler, replay, çakışma, sürüm, tenant, atomiklik, eşzamanlılık | **STAND-IN** | `KitchenAvailabilityCommandServiceTests` (mevcut, ~30 test, gerçek PostgreSQL) |
| K-02 | Kitchen kapatır → müşteri menüsü ve personel API'si aynı durumu gösterir; yeniden açma | **STAND-IN** | J-01 |
| K-03 | Kitchen'ın nedeni, çağıran kimliği ve komut kimliği müşteriye sızmaz | **STAND-IN** | J-01 |
| K-04 | Replay tarihsel makbuzdur; sonradan personelin yaptığı değişikliği ezmez | **STAND-IN** | J-02 |
| K-05 | Personel değişikliğini aşmış `expectedVersion` çakışmadır ve kayıt bırakmaz; aynı komut kimliği farklı içerikle çakışma | **STAND-IN** | J-02 |
| K-06 | Aynı komut kimliği iki restoranda ayrı komuttur | **STAND-IN** | J-04 |
| K-07 | Personel ve Kitchen aynı ürünü aynı anda değiştirir: sürüm = geçmiş satırı, son durum = son kayıt | **STAND-IN** | J-05 |
| K-08 | Üretimde komut işlenmez, HTTP yolu yok | **ÇALIŞTI** | `KitchenCommandProductionWiringTests` (mevcut), `ProductionWiringHttpSmokeTests` |
| K-09 | Kitchen → Menu gerçek çağrı (HTTP) | **BLOKE** | Uç yok, D-09 yok; Kitchen'da `MENU_NOT_INTEGRATED` |
| K-10 | Kapalı ürün listesi | **PR'DA** | PR #50 (`KitchenClosedProductsService`) |
| K-11 | Kitchen tüketici uyarlamaları (zorunlu neden, makbuz alanları, `outcome`/`replayed`) | **BLOKE** | Kitchen ve Menu ortak karar bekliyor |

### QR → Menu

| # | Senaryo | Etiket | Kanıt / neden |
|---|---|---|---|
| Q-01 | Müşteri menüsü üretimde kapalıdır: gerçek restoran UUID'si, çerez ve sahte başlıkla bile 404; var olan ve olmayan restoran aynı gövde | **ÇALIŞTI** | `ProductionWiringHttpSmokeTests.theCustomerMenuStaysClosed…` |
| Q-02 | Müşteri yalnız okur (POST/PUT/PATCH/DELETE 4xx) | **ÇALIŞTI** | `ProductionWiringHttpSmokeTests.theCustomerRoutesAcceptNothingButReads` |
| Q-03 | Müşteri B'nin A'nın ürününü görememesi, var olmayan ürünle aynı yanıt | **STAND-IN** | J-04 (+ mevcut `PublicMenu*` testleri) |
| Q-04 | Guest doğrulama altyapısı (üç sonuç, 503 ayrımı, çerez kuralları) | **PR'DA** | PR #55 (sahte doğrulayıcıyla; QR'a gerçek çağrı değil) |
| Q-05 | QR'a gerçek çağrı ile müşteri menüsü | **BLOKE** | QR sözleşmesi ve D-09 yok; QR'da Menu için uç tanımlı değil |

### Hız sınırı

| # | Senaryo | Etiket | Kanıt / neden |
|---|---|---|---|
| R-01 | 429/503, `Retry-After`, restoran ve kimlik izolasyonu, paralel istek, idempotent tekrar | **PR'DA** | PR #56 (44 test). `dev`'de kod yok |
| R-02 | PR #55 + #56 + MENU-INT-01 birleşik dalda birlikte çalışma, interceptor sırası | **BLOKE** | Birleşik dal yok; PR #56 metin çakışması var (rehber, bölüm 12) |
| R-03 | İki gerçek JVM instance ve yük testi | **BLOKE** | Yapılmadı; ortak altyapı ve onay yok |

### Genel

| # | Senaryo | Etiket | Kanıt / neden |
|---|---|---|---|
| X-01 | Sunucu ayakta, health yalnız `UP`, hata ve kapalı araçlar (Swagger, `env`, `beans`) bir şey sızdırmaz | **ÇALIŞTI** | `ProductionWiringHttpSmokeTests` |
| X-02 | Üretim bağlaması fail-closed sınıflardır, `src/test` sahtesi değildir | **ÇALIŞTI** | `ProductionWiringHttpSmokeTests.theWiringThatShipsTodayIsTheFailClosedOne` |
| X-03 | Auth + Order + Kitchen + QR birlikte, gerçek servis süreçleriyle | **BLOKE** | Bölüm 4 |

## 4. Neden gerçek E2E bugün mümkün değil

`dev` üzerinde şunlar doğrulandı (kod okunarak):

- Menu'dan **hiçbir servise** giden istemci yoktur; hiçbir servisin Menu'ya erişebildiği korumalı bir uç yoktur.
- Menu'da servisten servise kimlik doğrulayan mekanizma yoktur (D-09 açık) ve uydurulmamıştır.
- Order'ın Menu adaptörü üretimde hep reddeder; Kitchen'ın Menu portu `MENU_NOT_INTEGRATED` döndürür; QR'da Menu için tanımlı uç yoktur.
- Auth doğrulayıcısı (MENU-INT-01) `dev`'de değildir.

Açık PR'lardaki Menu kodu bu çalışmaya **taşınmamıştır**. Bunlar birleştiğinde bile D-09 kararı olmadan akışlar kapalı kalır.
Bu yüzden bu çalışma gerçek E2E yerine (a) Menu'nun kendi yüzeyleri arasındaki tutarlılığı ve (b) kapalı kapıların gerçekten kapalı
olduğunu sınar.

## 5. Eklenen testler

`backend/services/menu-service/src/test/java/com/restofy/menu/integration/`

### `CrossSurfaceAvailabilityJourneyTests` (11 test, STAND-IN)

| Grup | Test |
|---|---|
| J-01 | Kitchen kapatması ve yeniden açması müşteri menüsünde ve personel API'sinde aynı görünür; nedeni, çağıran kimliğini, komut kimliğini ve restoran kimliğini müşteri görmez |
| J-02 | Replay özgün makbuzu döndürür ve sonraki personel değişikliğini ezmez; personelin geçtiği sürüme dayalı komut çakışmadır ve kayıt bırakmaz; aynı komut kimliği farklı içerikle çakışmadır |
| J-03 | Personel Kitchen servisi yerine geçmez; Kitchen çağıranı personel yerine geçmez (sahte `X-Role: ADMIN` ile bile); WAITER/KITCHEN rolü availability değiştiremez |
| J-04 | Başka restoran ürünü Kitchen'dan, personel API'sinden ve müşteri menüsünden ne değiştirebilir ne görebilir ve yanıt var olmayan ürünle aynıdır; aynı komut kimliği restoranlar arasında paylaşılmaz |
| J-05 | Personel ve Kitchen aynı ürünü aynı anda değiştirir: sürüm, geçmiş ve son durum tutarlı |

### `ProductionWiringHttpSmokeTests` (7 test, açılış testi)

Gerçek gömülü sunucu + PostgreSQL, hiçbir sahte sağlayıcı yok, gerçek `java.net.http` istemcisi: health, üretim bağlamasının sınıfları,
12 yönetim rotası × sahte başlıklar, müşteri menüsünün kapalılığı (gerçek veriyle), yalnız-okuma, dokuz iç yol önerisi × üç yöntem,
hata/araç sızıntısı.

**Doğrulama:** iki bilinçli bozma denendi ve geri alındı: müşteri çözümleyicisini "referans = restoran kimliği" yapmak iki testi,
yönetim sağlayıcısını sabit bir ADMIN yapmak bir testi kırdı (`theWiringThatShipsTodayIsTheFailClosedOne`). Yönetim rotası testi bu
ikinci bozmayı **yakalamaz**: Spring Security, çerçeve kimliği olmayan isteği sağlayıcıya sormadan reddeder; sağlayıcıyı bağ testi yakalar.

## 6. Çalıştırılan komutlar ve sonuçlar (25 Kasım 2026)

| Komut | Sonuç |
|---|---|
| `mvn -pl services/menu-service test -Dtest='CrossSurfaceAvailabilityJourneyTests,ProductionWiringHttpSmokeTests'` (3 ardışık koşu) | 18 test, 0 hata, 0 atlanan, her koşuda |
| `sh ./pre-push/pre-push.sh menu-service dev` (içinde `mvn clean verify`, JUnit raporu kontrolü, %80 kapsam eşiği; Java 25) | **1368 test, 0 hata, 0 atlanan; satır kapsamı %98,01; `BACKEND TEST CHECK PASSED`** |

1368 = `dev`'deki 1350 + yeni 18. İlk koşuda tek hata test yazımıma aitti (problem gövdesindeki `instance` alanı isteğin URL'sini
yansıtır; karşılaştırmadan çıkarıldı). Üretim koduna dokunulmadı.

## 7. CI, pre-push ve SonarQube değerlendirmesi

| Kontrol | Durum |
|---|---|
| **GitHub Actions** (`.github/workflows/backend-verify.yml`) | `backend/**`, `pre-push/**` vb. değişince `push` ve `dev`'e `pull_request` ile çalışır: `qr-tools` ve `verify` (`sh ./pre-push/pre-push.sh backend dev`, 13 servisin tamamı). Bu PR'ın sonucu PR'da görülür; **bu belge yazılırken henüz bilinmiyor** |
| **pre-push** | Manuel; bu çalışmada yalnız Menu için çalıştırıldı (yukarıda). `SAFE TO COMMIT` yalnız `full` kipinde verilir ve burada **çalıştırılmadı** (frontend, audit, Compose, HTTP sağlık kontrolü gerekir); bu yüzden bu belge `SAFE TO COMMIT` iddia etmez |
| **SonarQube** | **Çalıştırılmadı.** Pre-push'a gömülü değildir; yerel bir SonarQube sunucusu ve kişisel `SONAR_TOKEN` (ignore edilen `.env.dev`) gerektirir. Token'a dokunulmadı. Kalite kapısı sonucu bilinmiyor. Yeni testlerde her test bir doğrulama içerir, atlanan test yoktur |

## 8. Yeni bir entegrasyon açıldığında

Bir karar verilip uç eklendiğinde: (1) `ProductionWiringHttpSmokeTests.noInternalRoute…` yalnız **korumasız** bir ucu (2xx veya 5xx
döndüren) yakalar; doğru korunan bir uç forged kimlikle 401/403 verip bu testten geçer, bu yüzden her yeni uç kendi olumlu ve olumsuz
sözleşme testini taşımalıdır; `theWiringThatShipsTodayIsTheFailClosedOne` ise bir doğrulayıcı bağlandığında kırılır ve o satırın
bilerek güncellenmesini zorlar; (2) A/O/K/Q satırı BLOKE'den çıkarılır ve karşı servisin gerçek istemcisiyle yazılan sözleşme testi
eklenir; (3) iç uç hız sınırı (`requireServiceCall`) ve D-09 doğrulamasından sonra çağrılır.
