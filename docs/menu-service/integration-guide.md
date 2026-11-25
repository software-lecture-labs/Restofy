# Menu Service entegrasyon rehberi (MENU-INT-06)

- **Sahip:** Menu (Merve)
- **Tarih:** 25 Kasım 2026
- **Temel alınan kod:** `origin/dev` @ `ea70de0`. Bu belgedeki "bugün" ifadeleri `dev` içindir.
- **Kardeş belge:** [Entegrasyon test planı](integration-test-plan.md) (hangi senaryo çalışıyor, hangisi bloke)

Bu rehber, Menu Service ile Auth, Order, Kitchen ve QR arasındaki entegrasyonların **gerçekte** nerede olduğunu anlatır ve her ekibin
Menu'dan ne bekleyebileceğini, Menu'nun onlardan neyi beklediğini toplar. Bir şeyin belgelenmiş olması çalıştığı anlamına gelmez; her
bölüm neyin çalıştığını, neyin yalnız bir PR'da bulunduğunu ve neyin bir karara bağlı olduğunu ayrı yazar.

> **Okuma notu.** Aşağıdaki ADR ve talep belgeleri (ADR-MENU-005…009, `docs/menu-service/requests/*`) henüz `dev`'de değildir; açık
> PR'lardadır. Bu yüzden onlara bağlantı değil **PR numarasıyla** atıf yapılır. PR'lar birleşince bağlantıya çevrilebilir.

## 1. Bir bakışta durum

| Entegrasyon | Menu tarafı (`dev`) | Açık PR | Karşı servis (`dev`) | Gerçek bağlantı | Durum |
|---|---|---|---|---|---|
| **Auth → Menu** (personel kimliği) | Fail-closed: her yönetim isteği 401 | Doğrulayıcı kodu `feature/menu-service` dalında (`64a4a90`); talep belgesi PR #46 | Auth `staff-v1` token üretir | İzole ortamda gerçek Auth koduyla 24 Kasım'da denendi (dalda); `dev`'de ve paylaşılan ortamda yok | **KISMİ** |
| **Order → Menu** (ürün, fiyat, KDV) | Yok | PR #49 (iç uygulama katmanı, HTTP yok) | `MenuCatalogPort`; üretimde fail-closed adaptör, dev/test'te bellek içi sahte katalog | Yok | **BLOKE** (uç biçimi, D-09) |
| **Kitchen → Menu** (availability komutu) | Komut işleyicisi hazır ve testli, **HTTP ucu yok** | — | `MenuAvailabilityPort`; varsayılan `MENU_NOT_INTEGRATED` | Yok | **BLOKE** (D-09, tüketici uyarlaması) |
| **Kitchen → Menu** (kapalı ürün listesi) | Yok | PR #50 (iç uygulama katmanı, HTTP yok) | `MenuClosedProductsPort` | Yok | **BLOKE** |
| **QR → Menu** (müşteri restoranı) | Fail-closed: müşteri uçları 404 | PR #55 (guest doğrulama altyapısı, bağlı değil) | QR guest doğrulama ucu Menu için tanımlı değil | Yok | **BLOKE** (QR sözleşmesi, D-09) |
| **Hız sınırı** | Yok | PR #56 (PostgreSQL sayaç, 429/503) | — | — | PR'da, `dev`'de yok |

Gerçek bir çok servisli uçtan uca (E2E) akış **bugün çalıştırılamaz**: Menu'dan hiçbir servise giden bir istemci yoktur ve hiçbir
servisin Menu'ya erişebildiği korumalı bir uç yoktur. Ayrıntı: [test planı](integration-test-plan.md).

## 2. Her entegrasyon için geçerli ilkeler

Bunlar bir ekibin tek tek onayına bağlı değildir; `docs/SECURITY.md` ve ADR-MENU-001/002 kararlarıdır.

1. **Kimlik yalnız doğrulanmış kaynaktan.** Restoran, kullanıcı ve rol `X-Restaurant-Id`, `X-Role`, `X-User-Id`, `X-Service-Name`,
   `Authorization`, sorgu veya gövdeden **okunmaz**. Bu başlıkları göndermek hiçbir şeyi açmaz (testle kilitli).
2. **Varsayılan reddet (fail-closed).** Menu'da dört port vardır ve üretim varsayılanlarının hiçbiri bir şeyi doğrulamaz:

   | Port | Üretim varsayılanı | Gerçek kaynak |
   |---|---|---|
   | `CurrentActorProvider` (yönetim aktörü) | Aktör yok → 401 | Auth personel token'ı |
   | `PublicRestaurantScopeResolver` (müşteri restoranı) | Doğrulanan restoran yok → 404 | QR guest doğrulaması |
   | `TrustedKitchenCallerProvider` (Kitchen komutu) | Çağıran yok → komut işlenmez | Servis kimliği (D-09) |
   | `RatingSummaryPort` (puan özeti) | `UNAVAILABLE`, ağ çağrısı yok | Rating Service |

3. **Personel kimliği servis kimliği değildir.** Doğrulanmış bir ADMIN/KITCHEN personeli, Kitchen **servisinin** komut yetkisini
   vermez; tersi de geçerlidir (testle kilitli: `CrossSurfaceAvailabilityJourneyTests`).
4. **Restoran izolasyonu.** Menu verisi `restaurant_id` ile bölünür; restoran her zaman doğrulanmış bağlamdan gelir. Başka
   restoranın nesnesi ile var olmayan nesne **aynı yanıtı** verir (404; ayrım yapılmaz).
5. **Hata gövdesi** RFC 9457 `ProblemDetail`'dır ve sabit metin taşır; neden ayrımı (imza, süre, tenant) sızdırılmaz. `instance` alanı
   isteğin kendi yolunu yansıtır. Beklenmeyen hata 500 ve bir `incidentId` döner.
6. **Müşteri yanıtları** `Cache-Control: no-store` taşır ve yönetim DTO'larından ayrı bir alan allowlist'i kullanır (restoran kimliği,
   sürüm, availability nedeni, KDV oranı, komut kimliği yoktur).
7. **Gateway** bugün `/api/v1/menu/**` yolunu kimlik doğrulamadan Menu'ya iletir; yetki kararı Menu'dadır.

## 3. Auth → Menu: personel kimliği ve yetkilendirme

**Bugün (`dev`):** her yönetim isteği 401'dir. Müşteri uçları (açık) ve `actuator/health` kimlik istemez.

**Hazır kod (`feature/menu-service` dalı, MENU-INT-01):** Menu Auth'un `staff-v1` token'ını Auth'a sormadan yerel doğrular.

| Konu | Sözleşme |
|---|---|
| Taşıyıcı | HttpOnly `restofy_access` çerezi (Path `/api/v1`, SameSite=Lax). Başlıkla gelen token reddedilir |
| Token | RS256; `iss` yapılandırılmış değer; `aud` tek ve `restofy-staff-api`; `token_type=access`; `auth_state=AUTHENTICATED`; `auth_policy=q5-idle-v1`; ömür ≤ 300 sn |
| Kimlik | `sub` (kullanıcı), `restaurant_id`, `role` imzalı claim'lerden gelir |
| Rol | Yönetim uçları yalnız **ADMIN** (WAITER/KITCHEN → 403) |
| Mutasyon | `restofy_csrf` çerezi + `X-CSRF-TOKEN` birebir eşit, tek ve izinli `Origin`, içerik türü `application/json`; yoksa 403 ve hiçbir şey yazılmaz |
| Anahtar | `MENU_AUTH_STAFF_PUBLIC_KEY` (Auth'un X.509 **açık** anahtarı). Boşsa personel kimliği kapalı (401) |

**Bilinen sınırlar:** iptal (logout, pasifleştirme, rol değişikliği) eski token için en çok 300 sn gecikir; gerçek Auth ile ölçüldü
(logout sonrası Auth 401, Menu süre dolana kadar 200). CSRF imzası doğrulanmaz (HMAC anahtarı Auth'ta). CORS yoktur.

**Beklenen kararlar:** anahtar sağlama ve Compose/ENV aktarımı (Kerem, Alper), anlık iptal (Auth + Menu), servis kimliği (bkz. 8).

## 4. Order → Menu: ürün, fiyat ve KDV sorgusu

**Bugün:** Menu'da Order için ne uç ne servis kimliği ne tel sözleşmesi vardır. Order tarafında `MenuCatalogPort.findProducts` vardır;
üretim profilinde `UnavailableMenuCatalogAdapter` (hep `DEPENDENCY_UNAVAILABLE`), dev/test'te bellek içi **sahte** katalog çalışır.
Sahte katalogla yapılan bir test Menu'yu sınamaz.

**Hazır kod (PR #49):** `OrderCatalogService.lookup(restaurantId, productIds)`.

- Sıra: doğrulanmış çağıran → istek doğrulaması → restoranın çağıranınkiyle eşitliği → sorgu. Üretimde çağıran yok → reddedilir.
- Boş, `null` içeren veya 50'den çok farklı kimlik 400; tekrarlanan kimlik bir kez yanıtlanır. Bilinmeyen ile başka restoranın kimliği
  yanıtta yoktur (hata yok).
- Arşivli, taslak, kategorisi kapalı ve availability'si kapalı/yok ürünler **işaretli** döner; `published && available` sipariş
  edilebilirliktir. Rezervasyon değildir: Order sipariş anında yeniden doğrulamalıdır.
- Fiyat **tam doğru ya da yok**: `grossPriceMinor` yalnız KDV dahil ve para biriminin minor hanesinde tam ifade ediliyorsa verilir;
  aksi hâlde `null` ve `priceIssue` (`VAT_EXCLUSIVE`, `PRECISION_EXCEEDS_CURRENCY`, `UNSUPPORTED_CURRENCY`, `AMOUNT_OUT_OF_RANGE`).
  Yuvarlama yapılmaz (D-08 açık). `priceVersion` üretilmez.

**Beklenen kararlar (Order talebi, 15 madde):** uç ve yöntem, 50 sınırı, tutar ve KDV tel biçimi, KDV hariç ve fazla ondalıklı fiyat,
`priceVersion`, arşivli ürün/pasif seçenek, servis kimliği, ENV.

## 5. Kitchen → Menu: availability komutları ve kapalı ürün listesi

**Bugün (`dev`):** `KitchenAvailabilityCommandService` hazır ve gerçek PostgreSQL'e karşı testlidir; **HTTP ucu yoktur**. Üretimde
`DenyAllKitchenCallerProvider` hiçbir çağıranı doğrulamaz, komut işlenmez.

### Komut (canonical v1; tel biçimi değil, veri sözleşmesi)

| Alan | Kural |
|---|---|
| `commandId` | Zorunlu UUID; deneme ve tekrarlarda aynı |
| `productId` | Zorunlu; yalnız çağıranın restoranında aranır |
| `targetStatus` | `AVAILABLE`, `OUT_OF_STOCK`, `TEMPORARILY_CLOSED` |
| `reasonCode` / `reasonText` | Kapalı durumda kod zorunlu (1–40, `[A-Z][A-Z0-9_]{0,39}`); metin en çok 500 karakter |
| `expectedVersion` | İsteğe bağlı **availability** sürümü |

Restoran ve adına işlem yapılan kullanıcı gövdeden değil, doğrulanmış çağıran bağlamından gelir. Makbuz:
`{outcome: APPLIED|NO_CHANGE, replayed, status, version}`. Replay **tarihsel makbuzdur, canlı durum değildir**.

### Hata eşlemesi (işleyici davranışı; HTTP kodları yalnız genel `ApiExceptionHandler` eşlemesidir)

| Senaryo | Sonuç | HTTP |
|---|---|---|
| Aynı komut kimliği, farklı içerik | `CommandIdConflictException` | 409 |
| Beklenen sürüm geçmiş | `ProductAvailabilityVersionConflictException` | 409 |
| Ürün yok veya başka restoranda | `ProductNotFoundException` (ayrım yok) | 404 |
| Availability kaydı yok | `ProductAvailabilityMissingException` | 409 |
| Geçersiz komut / eksik kapatma nedeni | `InvalidProductException` | 400 |
| Doğrulanmış çağıran yok | `UnauthenticatedException`, hiçbir şey yazılmaz | 401 |

### İdempotency ve sıra

- Kapsam `(restaurantId, commandId)`; aynı kimlik farklı restoranda ayrı komuttur. Aynı kimlik + aynı içerik ilk makbuzu döndürür,
  farklı içerik çakışmadır. Reddedilen komut kayıt bırakmaz. Güncelleme, geçmiş ve komut kaydı tek işlemdedir.
- Ürün başına `PESSIMISTIC_WRITE` kilidi komutları kilidi alma sırasına göre serileştirir. **`expectedVersion` olmayan gecikmiş eski
  komut yeni durumu ezebilir**; gerçek entegrasyonda sıra/tazelik gerekir (ADR-MENU-002).
- Kapalı ürün kendiliğinden açılmaz; yalnız açık bir `AVAILABLE` komutu açar.

### Kapalı ürün listesi (PR #50)

`KitchenClosedProductsService.list(statuses, page, size)`: yalnız çağıranın restoranı, yalnız kapalı durumlar, boyut 1–100, sıra
`changedAt DESC, productId ASC`. `active` ve `published` ham bayraktır, yorumlanmaz. HTTP ucu yok.

**Beklenen kararlar (Kitchen talebi, 12 madde):** kapatma nedeninin zorunluluğu (Kitchen'da isteğe bağlı), makbuza `changedAt`/`productId`,
`outcome`/`replayed` taşınması, `expectedVersion` zorunluluğu, `PRODUCT_INACTIVE` kuralı, `published` anlamı, ayrık hata kodları,
servis kimliği, yeniden deneme ve zaman aşımı.

## 6. QR → Menu: müşteri restoranı doğrulaması

**Bugün (`dev`):** `DenyAllPublicRestaurantScopeResolver`; her müşteri isteği 404'tür (gerçek restoran UUID'siyle bile). Müşteri uçları
yalnız `GET`'tir: `/api/v1/menu/public/restaurants/{restaurantRef}/categories|products|products/{id}`.

**Hazır kod (PR #55):** guest kimliği (`restofy_guest`, tek ve 43 karakterlik çerez) web katmanında, transaction'dan **önce**
`GuestSessionVerifier`'a sorulur. Üç sonuç: `Verified(restaurantId)`, `Rejected`, `Unavailable`; hata veya `null` hep `Unavailable`'dır.

| Sonuç | HTTP |
|---|---|
| Doğrulanmış + URL referansı o restoranın UUID'si | 200 |
| Reddedildi, kimlik yok, referans eşleşmiyor | 404 (ADR-MENU-001'deki aynı gövde) |
| Karar verilemedi (QR yok, zaman aşımı, kimlik reddedildi, hız sınırı) | 503, menü verisi yok, önbellek yok |

Üretim doğrulayıcısı bağlı değildir (`NotConnectedGuestSessionVerifier`: her şeyi reddeder), yani davranış bugünküyle aynıdır.

**Beklenen kararlar (QR talebi, 11 madde):** model (guest çerezi + iç doğrulama), ortak uç, servis kimliği, restoran semantiği, yanıt
kapsamı, hata ve hız sınırı sözleşmesi, zaman aşımı/yeniden deneme, rotate sonrası guest, `restofy_guest` çerezinin tüketimi.

## 7. Hız sınırı davranışı (PR #56; `dev`'de yok)

PostgreSQL tabanlı sabit pencere sayaçları (V68), tüm instance'lar aynı sayacı paylaşır. Redis veya Gateway çözümü yoktur ve üretimde
çalışıyormuş gibi gösterilmez. Sayaç anahtarı yalnız doğrulanmış restoran, doğrulanmış aktör veya TCP eş adresidir; başlıklar okunmaz.

| Sınıf | Anahtar | Varsayılan (öneri, onay bekliyor) |
|---|---|---|
| Müşteri menüsü | doğrulanmış restoran / doğrulanmamış istek (eş adresi) | 600 / 60 sn ve 30 / 60 sn |
| Yönetim okuma | kullanıcı / restoran | 120 ve 600 / 60 sn |
| Yönetim yazma | kullanıcı / restoran | 30 ve 150 / 60 sn |
| Yönetim, aktör yok | eş adresi | 20 / 60 sn |
| İç servis | doğrulanmış restoran | 600 / 60 sn (hazır, hiçbir uca bağlı değil) |

Aşımda `429` + `Retry-After` + `Cache-Control: no-store`; sayaç sayamazsa `503` + `Retry-After` (fail-closed). Reddedilen istek işlenmez,
yani idempotent bir yeniden deneme güvenlidir. Sayım güvenlik zincirinden sonra, controller ve DB transaction'ından önce yapılır;
401/403 istekler sayılmaz. İç servis ucu eklendiğinde çağıran doğrulandıktan **sonra** ve iş yapılmadan **önce** `requireServiceCall`
çağrılmalıdır.

## 8. Servis kimliği sözleşmesi (D-09) — açık karar

Order, Kitchen ve QR entegrasyonlarının üçü de buna bağlıdır. Menu'da bugün servisten servise kimlik doğrulayan hiçbir mekanizma yoktur
ve bir tane **uydurulmamıştır**. Menu'nun beklediği asgari sözleşme (öneri; Auth ve QR ile onaylanmadı):

- İmzalı, kısa ömürlü, **tek restorana** bağlı ve **tek servise** (`aud`) ayrılmış kimlik; çağıranın yetki alanı (`scope`) sınırlı.
- Adına işlem yapılan personel imzalı claim olarak taşınır (komutun denetim kaydı için). Başlık veya gövde değildir.
- İstek bağlama (yöntem, hedef, gövde özeti) ve anahtar sürümü (`kid`) kararı.
- Ayrı, Gateway'in yönlendirmediği iç yol öneki (öneri: `/internal/v1/...`) ve **ayrı güvenlik zinciri**.
- Menu'da yeni ENV (açık anahtar) ve Compose aktarımı `docs/SECURITY.md` gereği açık onay ister; bu rehber ENV eklemez.

Servis kimliği gelene kadar iç ucun açılması yarar getirmez ve açılmaz. Gelince her iç uç şu sırayı izler: çağıran doğrulaması → hız
sınırı → istek doğrulaması → tenant eşitliği → iş.

## 9. Hata kodları, idempotency ve tenant izolasyonu: özet

| Durum | Kod | Not |
|---|---|---|
| Kimlik yok / geçersiz | 401 | Sabit gövde; neden yok |
| Yanlış rol, CSRF/Origin hatası | 403 | Hiçbir şey yazılmaz |
| Yok, gizli, başka restoranın nesnesi, doğrulanmamış müşteri restoranı | 404 | Hepsi aynı gövde |
| Sürüm / komut kimliği çakışması, availability kaydı yok | 409 | Makine okunur ayrık kod henüz yok (Kitchen talebi, madde 7) |
| Doğrulama hatası | 400 | Yetki kontrolünden **sonra** |
| Hız sınırı aşıldı | 429 + `Retry-After` | PR #56 |
| Karar verilemedi (QR yok, sayaç yok) | 503 + `Retry-After` | Asla "izin ver" sayılmaz |

- **İdempotency:** komutlarda `(restaurantId, commandId)`; yönetim güncellemelerinde `version` ile optimistic locking (eski sürüm 409).
  Reddedilen istek (hız sınırı dahil) bir şey yazmaz.
- **Tenant:** her sorgu ve yazma `restaurant_id` taşır; servisler arası kimlikler mantıksal referanstır (fiziksel FK yok).

## 10. Yerel test ve doğrulama komutları

Java 25 ve çalışan Docker gerekir (PostgreSQL 17 Testcontainers ile açılır).

```sh
cd backend
export JAVA_HOME=<JDK 25>

# Menu'nun tüm testleri, kapsam raporuyla (CI ile aynı Maven hedefi)
mvn -pl services/menu-service clean verify

# Yalnız MENU-INT-06 testleri
mvn -pl services/menu-service test -Dtest='CrossSurfaceAvailabilityJourneyTests,ProductionWiringHttpSmokeTests'

# Projenin yerel kapısı: yalnız Menu (testler + boş/atlanan rapor + %80 satır kapsamı)
sh ./pre-push/pre-push.sh menu-service dev
```

Yeni bir `@SpringBootTest` bağlamı kendi bağlantı havuzunu açar ve tek test PostgreSQL'inin bağlantı sınırı sonludur: yeni testler
mevcut bağlam anahtarını (`@Import({TestActorConfiguration.class, TestPublicScopeConfiguration.class})`) paylaşmalı ya da küçük
bir havuz boyutu vermelidir (`ProductionWiringHttpSmokeTests` örneği).

## 11. Ekiplerden beklenen kararlar

| # | Karar | Sahip | Bloke ettiği akış | Kaynak |
|---|---|---|---|---|
| 1 | **D-09 servis kimliği** (mekanizma, `aud`, `scope`, ömür, anahtar, istek bağlama, tek restoran) | Auth (Kerem) + QR | Order, Kitchen, QR → Menu | PR #46 madde 5; #49 madde 11; #50 madde 8; #55 madde 3 |
| 2 | Auth açık anahtarının Menu'ya sağlanması, `MENU_AUTH_STAFF_PUBLIC_KEY` ENV'i ve Compose | Auth (Kerem), Compose (Alper) | Auth → Menu paylaşılan ortamda | PR #46 madde 1, 7 |
| 3 | Anlık iptal gerekli mi (introspection ya da Menu'yu iptal hedefi yapma) | Auth + Menu | Auth → Menu güvence düzeyi | ADR-MENU-005 |
| 4 | Order uç biçimi, tutar/KDV tel biçimi, KDV hariç ve fazla ondalıklı fiyat, `priceVersion` | Order (Hamza) | Order → Menu | PR #49 madde 6 |
| 5 | Kitchen: kapatma nedeni zorunlu mu, makbuz alanları, `expectedVersion`, `PRODUCT_INACTIVE`, hata kodları | Kitchen (Nisa) | Kitchen → Menu | PR #50 madde 6 |
| 6 | QR: model, ortak uç, restoran semantiği, hata ve hız sınırı sözleşmesi, `restofy_guest` tüketimi | QR (Alper) | QR → Menu | PR #55 madde 9 |
| 7 | Hız sınırı varsayılan değerleri ve güvenilir proxy/istemci adresi kararı (Gateway PR #54) | Ekip | Hız sınırı değerleri | ADR-MENU-009 |
| 8 | QR'sız menü ve restoran açık/kapalı durumu (Admin) | Ürün + Admin | Müşteri menüsünün gerçek restoranla açılması | PR #55 madde 6 |

## 12. Birleştirme sırası ve bilinen çakışmalar

Açık PR'lar `dev` üzerinde ayrı ayrı temizdir ve CI'ları başarılıdır; ama birlikte birleştirilirken **metin çakışması** vardır
(`git merge-tree` ile doğrulandı, hiçbir PR'a dokunulmadan):

| Birleşim | Çakışan dosya |
|---|---|
| PR #56, `feature/menu-service` (MENU-INT-01) sonrası | `application.properties` |
| PR #56, PR #49 veya #50 sonrası | `src/test/resources/config/application-test.properties` (üç PR de bu dosyayı ekliyor) |
| PR #56, PR #55 sonrası | `ApiExceptionHandler.java` |

Bunlar mekanik çözümlerdir ama el ile yapılmalıdır. Davranış açısından iki uyarı:

- **Interceptor sırası:** PR #55'in guest doğrulama interceptor'ı, PR #56'nın hız sınırı interceptor'larından **önce** çalışmalıdır
  (hız sınırı sırası bilerek `100`); doğrulayıcı bu çalışmada ağ çağrısı yapmamalıdır.
- **Birleşik dal denenmedi.** Bu PR'ların hepsi birlikte tek bir dalda derlenip test edilmedi; her biri kendi dalında başarılıdır.

## 13. Bilinen sınırlar (gizlenmiyor)

- Gerçek çok servisli E2E yok; sahte servisle yapılan test E2E değildir ve öyle adlandırılmaz.
- İki gerçek JVM instance ile çoklu instance denemesi ve yük testi yapılmadı.
- Paylaşılan Docker ortamında Menu'ya anahtar aktarılmadı; oradaki Auth imajı eski/MFA zorunludur. Orada deneme yapılmadı.
- Rating puan ölçeği (1–5) geçici varsayımdır; Rating Service yok.
- Restoranın açık/kapalı durumu (Admin) Menu'ya yansımaz.
