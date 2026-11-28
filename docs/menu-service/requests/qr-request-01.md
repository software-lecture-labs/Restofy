# REQUEST-01 · Public Menu → QR restoran doğrulama ihtiyaçları

**25 Kasım 2026 · Merve (Menu) → Alper (QR) · bilgi: Umut (Admin), Kerem (Auth, servis kimliği), Hamza (Order)**

| | |
|---|---|
| **Talep ID** | QR-REQUEST-01 (Menu'nun QR ile ilk sözleşme talebi) |
| **Talep eden** | Menu Service (Merve) |
| **İlgili servis ve ekip** | QR Service (Alper). Madde 4 servis kimliği olduğu için Auth (Kerem); madde 6 restoran kaydı olduğu için Admin (Umut) |
| **Bağlı PDA görevi** | MENU-INT-04 — Public Menu restoran doğrulama entegrasyonu |
| **Durum** | **QR yanıtladı (PR #65, `dev`'de); Menu tarafındaki gerçek istemci MENU-INT-04 ile eklendi** ([ADR-MENU-008](../../decisions/ADR-MENU-008-public-menu-guest-oturum-baglami.md)). Üretimde bağlantı, Menu'nun kendi anahtarı tanımlanana kadar **kapalıdır** (her müşteri isteği 404); ENV ve Compose bağlantısı ayrıca onay bekliyor |

Bu belge bir taleptir. QR, Admin ve Auth kodunda değişiklik içermez. Aynı PR'da Menu'da yalnız karardan bağımsız altyapı vardır (ADR-MENU-008); QR çağrısı, servis kimliği ve QR'sız menü eklenmemiştir. Etiketler: **[DOĞRULANDI]** koddan veya belgeden okunan gerçek, **[ÖNERİ]** Menu'nun teklifi (onaylı değil), **[AÇIK]** karar bekleyen konu.

## Dayanak

- Kod tabanı: `origin/dev` (`ea70de0`).
- Menu: `PublicMenuController`, `PublicMenuService`, `PublicRestaurantScopeResolver`, [ADR-MENU-001](../../decisions/ADR-MENU-001-musteri-menusu-guven-modeli.md).
- QR: `QrValidationController`, `QrCodeValidationService`, `TableSecurityConfiguration`, `GuestRequestFilter`, `GuestCookies`, `QrEntryUrl`, `OrderGuestSessionController`, `OrderCallerFilter`, `OrderCallerSecurity`, `GuestSessionValidationService`; [QR entegrasyon rehberi](../../qr/entegrasyon-rehberi.md) §2 ve §13, [ADR-QR-002](../../decisions/ADR-QR-002-security.md) K3–K8 ve §3, [Waiter → QR talepleri](../../qr/waiter-entegrasyon-talepleri.md) (WAITER-02).
- Admin: `admin-service` ve Gateway: `api-gateway` `application.properties` (rota 4 ve 5).
- Güvenlik ve karar kayıtları: `docs/SECURITY.md`, `docs/requirements.md` F-QR-01/02, D-09, D-14.
- MENU-INT-01 (Auth personel güvenliği) `dev`'de değil. Bu talep ona bağımlı değildir: müşteri uçları personel cookie'si kullanmaz.

## 1. Entegrasyonun amacı

Müşteri menüyü QR okutarak açar (`docs/requirements.md`, giriş; ADR-MENU-001). Menu, **hangi restoranın menüsünü göstereceğini** istemcinin söylediği değerden öğrenemez: URL'deki `restaurantRef`, başlıklar, sorgu ve gövde istemci girdisidir (`docs/SECURITY.md`, ADR-MENU-001 karar 2). Restoran kimliğini güvenilir biçimde **QR bilir**: QR kaydı sunucu tarafında restoran ve masaya bağlıdır.

Amaç, Menu'nun müşteri isteğinde restoranı yalnız QR'ın doğruladığı bağlamdan almasını sağlayan, iki tarafın kabul ettiği bir sözleşme tanımlamaktır. Menu, bu doğrulanmış restoran dışındaki hiçbir restoranın ürününü döndürmemelidir. Menüyü görmek sipariş, masa oturumu veya personel yetkisi vermez (`docs/SECURITY.md`: okuma ve yazma yetkileri ayrıdır).

## 2. Mevcut durum

### 2.1 Menu tarafı [DOĞRULANDI]

- Müşteri uçları yalnız `GET`: `/api/v1/menu/public/restaurants/{restaurantRef}/categories`, `/products`, `/products/{productId}`. Oturumsuz ve çerezsiz açıktır. Yanıtlar `Cache-Control: no-store`.
- Restoran tek bir porttan çözülür: `PublicRestaurantScopeResolver.resolve(String restaurantRef) → Optional<UUID>`. Bu talebin yazıldığı sırada tek üretim uygulaması her şeyi reddeden bir çözümleyiciydi; şimdi `GuestSessionRestaurantScopeResolver`: restoranı yalnız QR guest doğrulamasının onayladığı ve URL'nin aynen adlandırdığı durumda güvenilir sayar. Doğrulayıcı (`GuestSessionVerifier`) üretimde `NotConnectedGuestSessionVerifier`'dır ve her kimliği reddeder, yani her müşteri isteği hâlâ 404. Test doğrulayıcısı yalnız `src/test`'tedir.
- Doğrulanmamış restoran, gizli/bilinmeyen/başka restoranın ürünü ve kategorisi **aynı 404 gövdesini** döndürür. Sorgular çözülen `restaurantId` ile kapsamlanır. Yanıt DTO'larında restoran kimliği yoktur.
- **Menu'da hazır (karardan bağımsız):** guest çerezi (`restofy_guest`) tek ve biçimli ise `GuestSessionVerifier` portuna, transaction'dan önce web katmanında bir kez sorulur. Sonuç `Verified(restaurantId)`, `Rejected` veya `Unavailable`'dır; `Unavailable` 503, diğerleri 404 olur ve menü verisi dönmez. Port imzası değişmedi, guest bağlamı istek özniteliğiyle taşınır (ayrıntı ADR-MENU-008).
- **Hazır olmayan:** QR'a gerçek HTTP istemcisi. Madde 4'teki sözleşme kabul edilene ve servis kimliği (D-09) belli olana kadar yazılmayacak.

### 2.2 QR tarafı [DOĞRULANDI]

| Konu | Gerçek durum |
|---|---|
| QR içeriği | `QR_PUBLIC_ENTRY_URL#token=<43 karakter URL-safe token>`. Token **fragment**'tedir, HTTP isteğiyle sunucuya gitmez. Giriş URL'si ayarlı değilse QR görseli üretilmez. Bu URL'nin müşteri sayfasına ve Menu'ya nasıl bağlanacağı henüz tanımlı değildir |
| `POST /api/v1/qr/validate` | Gövde `{ "qrToken": "…" }`. Başarıda `qrId, restaurantId, tableId, tableNumber, displayName, checkedAt`. Geçersiz, pasif, süresi dolmuş veya pasif masadaki QR için 404. Oturum açmaz |
| `POST /api/v1/qr/table-sessions/open` | Her kabul edilen taramada yeni guest oturumu ve `restofy_guest` (HttpOnly) ile `restofy_guest_csrf` çerezleri |
| Guest çerezi | `restofy_guest`: HttpOnly, `Path=/api/v1`, `SameSite=Strict`, 12 saat, değer 43 karakter. QR her kullanımda oturumu yeniden doğrular (ADR-QR-002 K5) |
| Müşteri uçlarının erişimi | `validate`, `table-sessions/open` ve `guest-sessions/activity` QR'ın güvenlik zincirinde **misafir yoludur ve `denyAll()`** ile kapalıdır. Kodda açıklama: "hız sınırı değişikliği açar (ayrı public endpoint onayı)". Yani bugün dışarıdan **çalışmaz** |
| Misafir yollarının koşulları | Tek ve izinli `Origin`, JSON içerik türü, `Authorization` başlığı **yok**. Bu, tarayıcı kanıtıdır, servisten servise çağrıya uygun değildir |
| İç guest doğrulama | `POST /api/v1/internal/qr/guest-sessions/validate`, gövde `{ "guestToken": "…" }`. Yalnız `qr-order-inbound` profilinde var. Yalnız **Order'ın** servis kimliğini kabul eder: `Authorization: Service <JWT>`, `iss=restofy-order`, `sub=order-service`, `aud=restofy-qr`, `scope=qr:guest-session`, `restaurant_id`, `method/target/body_sha256`, `jti`, en çok 30 sn. `Cookie` ve `Origin` varsa reddeder |
| İç doğrulama yanıtı | `200`: `restaurantId, tableId, tableSessionId, guestSessionId, state=ACTIVE, checkedAt`. Tanınmayan/kullanılamayan guest 404. Doğrulama oturum aktivitesini **yenilemez** |
| Restoran kontrolü | İç uç, çağıranın `restaurant_id` claim'ini QR'ın `AUTH_RESTAURANT_ID` değeriyle karşılaştırır ve uyuşmayan guest'i 404 yapar (`validateForOrder`). Restoranı **öğrenmek** için değil, **doğrulamak** için tasarlanmıştır. Token-only `validate(token)` metodu serviste vardır ama HTTP ucuna bağlı değildir |
| Order bağımlılığı | `validateForOrder`, yerel süreler ACTIVE'i kanıtlayamazsa Order'a sorabilir. Salt okunur menü için istenmeyen bir bağımlılık olabilir |
| Gateway | `/api/v1/qr/**` ve `/api/v1/menu/**` yönlendirilir. `/api/v1/internal/**` **yönlendirilmez** |
| Hız sınırı | QR'da henüz yok (ADR-QR-002 K7); misafir yolları bu yüzden kapalı |

### 2.3 Admin tarafı [DOĞRULANDI]

- `admin-service` yalnız `AdminServiceApplication` sınıfından oluşur: restoran kaydı, açık/kapalı durumu, slug veya doğrulama ucu **yoktur**; migration da yoktur.
- Auth ve QR tek sabit restoran kimliğiyle (`AUTH_RESTAURANT_ID`) çalışır. Çok restoranlı bir kayıt henüz yoktur.

## 3. Önerilen akış [ÖNERİ]

1. Müşteri QR'ı okutur; frontend fragment'teki token'ı okur ve QR'a gönderir (`table-sessions/open`). QR guest çerezini yazar. Ham QR token'ı Menu'ya hiç gelmez.
2. Müşteri menüyü açar: tarayıcı Gateway üzerinden `GET /api/v1/menu/public/...` yapar, `restofy_guest` çerezi `Path=/api/v1` olduğu için Menu'ya da gider.
3. Menu çerezdeki guest token'ını QR'a sunucudan sunucuya doğrulatır. QR `restaurantId` ve `ACTIVE` durumunu döner.
4. Menu yalnız o `restaurantId` ile sorgular. URL'deki `restaurantRef` kaldırılır veya doğrulanan restoranla birebir eşleşmek zorunda olur, eşleşmezse 404 (güven kaynağı olmaz).

Neden bu model:

- QR token'ı uzun ömürlü ve masaya basılıdır. Menu'nun isteğinde, logunda ve önbelleğinde dolaşması ADR-QR-002 K8'e aykırıdır (ham token URL, query, log ve olaylara yazılmaz).
- Guest çerezi QR'ın zaten sahip olduğu ve her kullanımda yeniden doğruladığı kimliktir. Kapanmış veya süresi dolmuş oturum menüyü de kapatır.
- Yeni bir uç gerekmez: Waiter da aynı ortak uca bağlanmayı istiyor (WAITER-02).

**Seçenekler**

| Seçenek | Artı | Eksi | Sonuç |
|---|---|---|---|
| **A. Guest çerezi + iç guest doğrulama** | Ham QR token'ı Menu'ya girmez; sunucu tarafı restoran; QR yeniden doğrular; yeni uç yok | Her okuma bir QR çağrısı (gecikme, QR kesintisi menüyü durdurur); Menu'nun servis kimliği gerekir; tüketicinin guest çerezini okuması ADR-QR-002 §3'te hâlâ açık | **Önerilen** |
| B. Menu'nun `validate` ile QR token'ı doğrulaması | Basit, menü için guest oturumu gerekmez | `validate` kapalı ve tarayıcı kanıtına bağlı (Origin zorunlu, `Authorization` yasak); ham token Menu'ya girer (K8); rotate sonrası eski oturumlar için ayrım yok | Önerilmez |
| C. İstemci `restaurantRef` gönderir, Menu inanır | Hemen çalışır | Doğrulanmamış değer yetki olur (ADR-MENU-001 A1, `docs/SECURITY.md`) | Reddedildi |
| D. QR, menü için kısa ömürlü imzalı bağlam üretir | Okuma başına QR çağrısı yok | Yeni imza sözleşmesi, anahtar yönetimi, Auth/QR kararı gerekir | Alternatif; ancak A'nın maliyeti kabul edilemezse düşünülür |

## 4. QR Service ile Menu arasındaki sözleşme ihtiyacı

Menu için **yeni uç istenmiyor**; mevcut `POST /api/v1/internal/qr/guest-sessions/validate` için aşağıdaki değişiklikleri istiyoruz.

**4.1 Servis kimliği [AÇIK — Auth + QR, D-09].** Uç bugün yalnız Order'ı tanır (`ORDER_QR_GUEST_SESSION`). Menu'nun kimliği için kabul edilmiş bir mekanizma yoktur ve Menu bir tanesini kendiliğinden uydurmaz. Order ile Waiter aynı ihtiyacı yaşıyor. Üç tüketici için ayrı ayrı çözüm yerine **tek ortak servis kimliği kararı** öneriyoruz. Mevcut Order kalıbını model olarak sunuyoruz (kabul edilmiş değildir): kısa ömürlü RS256 JWT, `kid`, tüketiciye özgü `iss/sub`, `aud=restofy-qr`, kapsam, `jti`, `method/target/body_sha256`, çağrıda `Cookie/Origin` yok. Menu'nun özel anahtarı yalnız Menu'da kalır, QR açık anahtarı alır.

**4.2 Restoran semantiği [AÇIK — QR].** İç uç bugün çağıranın `restaurant_id` claim'ini doğrular. Menu'nun amacı restoranı **öğrenmek**. Seçenekler: (a) QR, Menu kimliği için claim kontrolünü atlayıp token-only doğrulama döner (serviste var, HTTP'ye bağlı değil); (b) tek restoranlı MVP'de Menu da sabit `restaurant_id` taşır (yeni ENV) ve QR doğrular, çok restoranlı aşamada yine (a) gerekir. Menu (a)'yı öneriyor.

**4.3 Yanıt kapsamı [ÖNERİ].** Menu yalnız `restaurantId` ve `state=ACTIVE` kullanır; `tableId`, `tableSessionId`, `guestSessionId` kullanmaz, saklamaz, loglamaz. QR daha dar bir "menü kapsamı" yanıtı verebilirse (yalnız `restaurantId`, `state`) tercih ederiz; vermezse mevcut yanıtı olduğu gibi okuruz.

**4.4 Order bağımlılığı [AÇIK — QR].** Menu okuması Order'a bağımlı olmamalıdır. `validateForOrder` Order kanıtı isteyebilir. Menu kimliği için yalnız yerel doğrulama yapılması mümkün mü?

**4.5 Hata sözleşmesi [ÖNERİ].**

| QR yanıtı | Anlamı | Menu müşteriye |
|---|---|---|
| 200, `state=ACTIVE`, `restaurantId` var | Doğrulandı | Menü |
| 404 (ProblemDetail gövdeli) | Guest tanınmıyor, süresi dolmuş, kapalı veya başka restoran | Doğrulanmamış restoranla aynı 404 |
| 401 / 403 | Menu'nun servis kimliği reddedildi | 503, neden gösterilmez; Menu logunda yalnız durum |
| 429 | QR hız sınırı | 503 (+ `Retry-After` varsa) |
| 5xx, zaman aşımı, bağlantı hatası | QR yok | 503, **önbellekten menü verilmez** |
| 200 ama `state≠ACTIVE` veya alan eksik | Sözleşme ihlali | 503 |

Waiter belgesindeki ayrım korunur: ProblemDetail gövdesi taşımayan 404 (örneğin kapalı profil veya eksik rota) guest reddi sayılmaz, bağımlılık hatasıdır.

**4.6 Zaman aşımı ve yeniden deneme [AÇIK].** `REST_CLIENT_CONNECT_TIMEOUT_MS` / `REST_CLIENT_READ_TIMEOUT_MS` değerleri kullanılacak. Salt okunur isteği Menu **bir kez** tekrar denemeli mi, hiç denememeli mi? Menu: hiç yeniden deneme yok, hızlı 503.

**4.7 Önbellek [AÇIK].** Menu okuması her istekte QR çağrısı demektir. Başarılı çözümü çok kısa süre (örneğin ≤ 15 sn, anahtar: token özeti, ham token değil) önbelleğe almak QR yükünü azaltır ama kapanmış oturumu o süre kadar geçerli bırakır. Menü verisi herkese açık ve salt okunur olduğundan etkisi sınırlıdır. Başlangıçta **önbellek yok** öneriyoruz; QR hız sınırı belli olunca yeniden değerlendirilir.

## 5. Güvenlik ve tenant izolasyonu senaryoları

Hiçbirinde menü verisi dönmez. Doğrulama reddi olan 404 senaryolarında aynı gövde kullanılır (ayırt edilebilir neden sızmaz); QR'a ulaşılamayan senaryolar 503'tür (madde 4.5). Kabul ölçütü olarak yazılmıştır.

- [ ] Guest çerezi yok: 404.
- [ ] Çerez biçimsiz (43 karakter URL-safe değil) veya aynı isimli iki çerez: 404, QR'a çağrı yapılmaz.
- [ ] Tanınmayan guest token'ı: QR 404 → Menu 404.
- [ ] Süresi dolmuş veya kapanmış (`EXPIRED/CLOSED`) oturum: 404.
- [ ] Pasife alınmış masa veya pasif QR'dan gelen oturum: QR'ın kararına göre; **normal rotate mevcut guest'leri kapatmaz** (D-14), yani eski QR'dan açılmış oturum menüyü görmeye devam edebilir. Bu kabul edilebilir mi? **[AÇIK]**
- [ ] Başka restoranın guest'i: 404 (QR'da çağıran restoranıyla karşılaştırma var).
- [ ] URL'deki `restaurantRef` doğrulanan restoranla eşleşmiyor: 404, başka restoranın menüsü hiçbir koşulda dönmez.
- [ ] Sahte `X-Restaurant-Id`, `X-Role`, `X-User-Id`, sorgu veya gövde alanı: yok sayılır.
- [ ] Personel (`restofy_access`) cookie'si müşteri menüsünde kimlik sayılmaz; guest kimliği personel yetkisi vermez.
- [ ] Başka restoranın ürün/kategori kimliği: 404; aynı restoranda gizli ürün: 404.
- [ ] QR kapalı, yavaş veya 401/403 dönüyor: fail-closed 503; yanıt eski verilerden üretilmez.
- [ ] QR yanıtı bozuk (`state≠ACTIVE`, `restaurantId` eksik): 503.
- [ ] Ham guest token'ı URL, log, hata yanıtı, olay veya Menu önbelleğinde yok (ADR-QR-002 K8); Menu çerezi yalnız QR çağrısı için kullanır.
- [ ] Menü okuması QR oturum aktivitesini yenilemez (F-QR-02: yalnız gerçek menü/sepet hareketi). Doğrulama ucu yenilemiyor; bunun Menu için de böyle kalması gerekir.

## 6. QR olmadan menü ve restoran kaydı [AÇIK — Ürün + Admin]

- Gereksinimler müşterinin menüye **masaya bağlı QR ile** ulaştığını söyler. `web-rules.md` işletme tercihine göre herkese açık menü sayfalarını SEO kapsamında anabilir; bu bir karar değildir.
- **[ÖNERİ]** İlk sürümde QR'sız menü **yok**: her müşteri okuması doğrulanmış guest bağlamı ister. Sipariş zaten QR oturumu gerektirir (F-QR-02). Bu en basit ve en güvenli yoldur.
- QR'sız bir menü (restoranın web sitesi, paylaşılabilir bağlantı, SEO) istenirse restoranı doğrulayacak bir kayıt gerekir. Admin Service'te böyle bir kayıt, slug veya açık/kapalı durumu **yoktur** (madde 2.3). O durumda ayrı bir Admin talebi ve ayrı bir güvenlik kararı gerekir. Menu, "referans = restoran kimliği" gibi doğrulanmamış bir kısa yol eklemez.
- Restoranın açık/kapalı durumu (F-AD-01) şimdilik hiçbir yerden okunamaz; müşteri menüsü bunu yansıtmaz.

## 7. Sorumluluk sınırları

| Konu | Sahip |
|---|---|
| QR token'ının ve guest oturumunun doğrulanması, masa/QR durumu | QR (Alper) |
| İç guest doğrulama ucunda Menu çağıranını kabul etme, restoran semantiği, hız sınırı | QR (Alper) |
| Servis kimliği mekanizması (Order, Waiter, Menu için ortak) ve anahtar sağlama | Auth (Kerem) + QR (Alper); D-09 |
| Müşteri okumasının kapsamı, `restaurantId` ile sorgulama, aynı 404/503 sözleşmesi, portun guest bağlamı alacak biçimde değişmesi | Menu (Merve) |
| Guest çerezinin tarayıcıdan Gateway üzerinden Menu'ya taşınması, aynı kökenli çağrı, QR akışı | Frontend + Gateway |
| Restoran kaydı ve açık/kapalı durumu (QR'sız menü için) | Admin (Umut) |
| `.env.example`/Compose (Menu → QR URL'si, servis kimliği anahtarları) | Alper (ortak dosyalar); ENV onayı |

## 8. Bağımlılıklar

- QR'ın misafir yollarının hız sınırıyla açılması (ADR-QR-002 K7) ve tüketicinin guest çerezini okuma/CSRF ayrıntıları (ADR-QR-002 §3, "Cookie ve CSRF ayrıntıları"). Salt okunur `GET` için çerez yeterli olsa da bu karar açıktır.
- D-09 servis kimliği (Auth). Çözülene kadar Menu'nun QR çağrısı yapacak bir kimliği yoktur.
- Aynı ortak uca bağlanmak isteyen Waiter (WAITER-02) ve Order ile **tek** servis kimliği kararı.

## 9. QR ekibinden beklenen kararlar

1. **Model:** Menu müşteri okumasında restoranı guest çerezi + iç guest doğrulama ile mi almalı (A), başka bir model mi? (madde 3)
2. **Ortak uç:** `POST /api/v1/internal/qr/guest-sessions/validate` Menu için de kullanılabilir mi, yoksa Menu'ya ayrı bir uç mu açılacak? (madde 4)
3. **Servis kimliği:** Menu'nun QR'a hangi kimlikle gideceği; Order, Waiter ve Menu için tek mekanizma, kapsam adı ve anahtar dağıtımı. Karar sahibi Auth + QR. (madde 4.1)
4. **Restoran semantiği:** Menu için çağıran restoranı doğrulaması mı, yoksa restoranı dönen token-only doğrulama mı? (madde 4.2)
5. **Yanıt kapsamı:** Yalnız `restaurantId` + `state` dönen dar bir yanıt mümkün mü? (madde 4.3)
6. **Order bağımlılığı:** Menu kimliği için Order'a sormayan yerel doğrulama mümkün mü? (madde 4.4)
7. **Hata ve hız sınırı:** 404/401/403/429/5xx sözleşmesi sabit mi, `Retry-After` var mı, Menu için ayrı bütçe? (madde 4.5)
8. **Zaman aşımı, yeniden deneme ve önbellek:** Menu'nun beklenen yaklaşımı kabul edilebilir mi? (madde 4.6, 4.7)
9. **Rotate ve pasif masa:** Normal rotate sonrası mevcut guest'in menüyü görmesi kabul edilebilir mi? (D-14, madde 5)
10. **Çerez tüketimi:** Menu'nun `restofy_guest` çerezini salt okunur `GET` için okuyup QR'a iletmesi ADR-QR-002 §3 açık sorusunda onaylanıyor mu?
11. **Açılış zamanı:** Misafir yolları ve iç uç Menu'nun çağırabileceği biçimde ne zaman hazır olacak? Menu o güne kadar fail-closed (404) kalır.

**Diğer ekiplerden:** Kerem: madde 3. Umut: QR'sız menü istenirse restoran kaydı (madde 6). Hamza: bilgi (aynı ortak uç ve kimlik). Ürün: QR'sız menü gerekli mi (madde 6).

## 10. Açık konular ve sınırlar

- Bu talepte yazılanların hiçbiri uygulanmış değildir; Menu'nun müşteri uçları bugün üretimde 404 döner ve öyle kalır. Sahte doğrulama eklenmeyecektir.
- QR'ın müşteri yolları kapalıdır; ortak ortamda uçtan uca deneme yapılamaz.
- Çok restoranlı çalışma için restoran kaydı yoktur.
- Menu'nun doğrulayıcı portu ve fail-closed altyapısı hazırdır (ADR-MENU-008). QR'a gerçek istemci kararlardan sonra yazılır; ADR-MENU-001 o zaman güncellenir.

## 11. QR yanıtı ve MENU-INT-04 sonucu (27 Kasım 2026)

Kaynak: Alper'in PR #55 yorumları, PR #65 (`dev`, `68059b3`), `docs/qr/entegrasyon-rehberi.md` "Menu guest doğrulaması" ve QR kodu
(`MenuCallerFilter`, `MenuGuestSessionController`). Kerem (Auth) PR #65'te "itirazım yok" yazdı; **D-09'un son kararı hocadadır**.

| # | Soru (9. bölüm) | Yanıt ve Menu'nun uygulaması |
|---|---|---|
| 1 | Model | Guest çerezi + iç doğrulama (A) benimsendi. Menu çerezi **yalnız QR'a sormak için** okur |
| 2 | Ortak uç | Menu'ya **ayrı** uç: `POST /api/v1/internal/qr/menu/guest-sessions/validate`. Order/Waiter ucu değişmedi |
| 3 | Servis kimliği | Menu kendi RSA anahtarıyla imzalar (`iss=restofy-menu`, `sub=menu-service`, `scope=qr:menu-guest-validate`); Auth araya girmez. Menu'da uygulandı |
| 4 | Restoran semantiği | Token-only: **`restaurant_id` claim'i yoktur** (varsa 403). QR, guest kaydının restoranını döner ve kendi `AUTH_RESTAURANT_ID` değeriyle eşleştirir. Menu yalnız bu yanıttan restoran alır |
| 5 | Yanıt kapsamı | Dar: yalnız `restaurantId` ve `state`, `Cache-Control: no-store`. Menu fazladan alanları yok sayar |
| 6 | Order bağımlılığı | **Var, bağımsız değil:** yerel süreler ACTIVE'i kanıtlayamazsa QR Order'a sorar; kanıt yoksa 503. Gerçek QR'da Order bağlı değilken süresi dolmuş oturum **503** verdi (Menu 503, fail-closed) |
| 7 | Hata ve hız sınırı | 404 `GUEST_SESSION_UNAVAILABLE`; 401/403 kimlik; 429 K7 ile gelecek; 5xx/503. Menu: yalnız **kodlu 404** ret, geri kalan her şey 503. `Retry-After` kullanılmaz |
| 8 | Zaman aşımı, yeniden deneme, önbellek | Menu: bağlantı 2 sn, okuma 3 sn, **yeniden deneme ve önbellek yok** (QR rehberiyle uyumlu) |
| 9 | Rotate | Mevcut davranış: rotate açık oturumları kapatmaz; eski QR'dan açılmış canlı oturum menüyü görmeye devam eder |
| 10 | Çerez tüketimi | QR rehberi (§ Çerez) Menu'nun çerezi **yalnız bu çağrı için** kullanmasını, loglamamasını, saklamamasını ve önbelleğe almamasını ister. Menu böyle yapar (testli); ADR-QR-002 §3'ün resmi onayı QR'dadır |
| 11 | Açılış zamanı | Uç hazır. Tarayıcıdan uçtan uca akış, QR misafir yollarının K7 hız sınırıyla açılmasına bağlı |

**Tamamlananlar (PR #55 incelemesi sonrası):** `MENU_QR_CALLER_KEY_ID` ve `MENU_QR_CALLER_PRIVATE_KEY` `.env.example`'da belgelendi ve `docker-compose.yml`'de yalnız `menu-service`'e aktarıldı; `QR_SERVICE_URL` zaten mevcuttu. Menu→QR servis kimliği gerçek bir RSA anahtar çiftiyle yerelde gerçek QR ve Menu süreçleri arasında doğrulandı (anahtarlar yalnız ignore edilen `.env.dev`'de).

**Hâlâ açık:** (a) D-09'un hoca onayı; (b) Compose ağında bağlantıyı açmak için **HTTPS** `QR_SERVICE_URL` (varsayılan `http://qr-service:8080` düz HTTP; Menu anahtar tanımlıyken HTTPS ister, düz HTTP yalnız `localhost`) ve TLS kararı; (c) açık anahtarın ortak ortamda `QR_MENU_CALLER_PUBLIC_KEYS` ile QR'a verilmesi ve rotasyon süreci; (d) **tarayıcı E2E bloke**: guest rotalarını içeren PR #71 (K7 hız sınırı) henüz merge edilmedi, Gateway'in `restofy_guest` çerezini Menu'ya taşıması da ayrıca doğrulanacak; (e) Menu→QR çağrısı için hız sınırı bütçesi (QR 429).
