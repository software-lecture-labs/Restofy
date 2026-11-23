# REQUEST-01 · Order → Menu ürün kataloğu entegrasyon ihtiyaçları

**24 Kasım 2026 · Merve (Menu) → Hamza (Order) · bilgi: Kerem (Auth, servis kimliği), Alper (ortak Compose/ENV)**

| | |
|---|---|
| **Talep ID** | ORDER-REQUEST-01 (Menu'nun Order ile ilk sözleşme talebi) |
| **Talep eden** | Menu Service (Merve) |
| **İlgili servis ve ekip** | Order Service (Hamza). Madde 4.7 servis kimliği olduğu için Auth (Kerem); madde 6 soru 12 ortak ENV/Compose olduğu için Alper |
| **Bağlı PDA görevi** | MENU-INT-02 — Order → Menu için gerekli API'lerin sağlanması |
| **Durum** | **Karar bekliyor.** MENU-INT-02 **KISMİ**: Menu iç uygulama katmanı hazır ([ADR-MENU-006](../../decisions/ADR-MENU-006-order-katalog-sorgusu.md)); HTTP ucu **açılmadı**, servis kimliği ve tel biçimi kararları bekleniyor |

Bu belge bir taleptir. Order, Auth, Kitchen veya QR kodunda değişiklik içermez. İlk commit'te yalnız belgeydi; MENU-INT-02 ile Menu tarafında kararlardan bağımsız iç katman eklendi (`OrderCatalogService`, kimliği doğrulanmış çağıran kapısı, DTO'lar, testler). Aşağıdaki "Mevcut" bölümleri bu eklemeden **önceki** `dev` durumunu anlatır; "İstenen" ve "Öneri" başlıklarından HTTP ucu ve servis kimliği **uygulanmamıştır**. Neyin yapıldığı ve neyin beklediği: bölüm 9.

## Dayanak

- Kod tabanı: `origin/dev` (`ca9ab1a`).
- Order: `MenuCatalogPort`, `ProductSnapshot`, `CartLinePricer`, `PriceFingerprint`, `CartService`, `OrderCheckoutService`, `PriceCalculator`, `MoneyConverter`, `UnavailableMenuCatalogAdapter`, `InMemoryMenuCatalogAdapter`, `OrderQrRequestSigner`, `CartProperties`, `ErrorCode`; `docs/order-service/INTEGRATION_CONTRACTS.md`.
- Menu: `V62__create_menu_products.sql`, `V63__create_product_availability.sql`, `V65__create_product_options.sql`, `Product`, `ProductOption`, `ProductEligibility`, `AvailabilityStatus`, `TrustedKitchenCallerProvider`, `MenuSecurityConfiguration`, controller'lar; ADR-MENU-001…004.
- Mimari: `docs/architecture.md` §5 (sipariş akışı) ve §5.2 (menü değişimi ve checkout yarışı); `docs/requirements.md` D-08, D-09, D-10.
- MENU-INT-01 (Auth personel güvenliği) `dev`'de değil, PR #46 branch'inde. Bu talep ona bağımlı değildir; personel cookie'si Order → Menu çağrısında kullanılmaz (madde 4.7).

## 1. Entegrasyonun amacı

Order sepete ürün eklerken, sepeti gösterirken ve siparişi oluştururken ürünün **güncel** adını, fiyatını, KDV oranını, seçeneklerini ve satışa uygunluğunu Menu'den almak zorundadır. Menu bu bilgilerin tek yetkili kaynağıdır (`docs/architecture.md` Menu bölümü). Order bugün bu bilgiyi gerçek Menu'den alamıyor:

- `dev`/`test` profilinde `InMemoryMenuCatalogAdapter` sabit fixture döner (gerçek katalog değil).
- Diğer profillerde `UnavailableMenuCatalogAdapter` her çağrıda `503 DEPENDENCY_UNAVAILABLE` fırlatır (fail-closed).

Amaç, `MenuCatalogPort.findProducts` için gerçek bir Menu HTTP ucu ve iki tarafın birlikte kabul ettiği sürümlü bir sözleşme tanımlamaktır.

## 2. Mevcut durum

### 2.1 Order tarafı (mevcut kod)

```java
// com.restofy.order.application.port.MenuCatalogPort
Map<UUID, ProductSnapshot> findProducts(UUID restaurantId, Collection<UUID> productIds);
```

- Sonuç verilen restorana göre filtrelenir: başka restoranın ürünü veya bilinmeyen id **sonuçta yer almaz** (hata değil).
- Kullanıldığı yerler: `CartService` (ürün ekleme — tek id; sepet görünümü — sepetteki tüm id'ler) ve `OrderCheckoutService.price` (siparişte tüm sepet id'leri, tek çağrı).
- `ProductSnapshot` alanları ve kısıtları:

| Alan | Tip | Order'daki kural |
|---|---|---|
| `productId` | UUID | zorunlu |
| `name` | String | 1–200 karakter |
| `published` | boolean | menüde görünür |
| `available` | boolean | şu an satılabilir |
| `currency` | String | büyük harfli, desteklenen ISO 4217 kodu |
| `basePriceGrossMinor` | long | **KDV dahil** brüt, para biriminin minor biriminde (TRY 2 hane); 0…1 000 000 000 000 |
| `taxRateBps` | Integer, null olabilir | baz puan (1000 = %10); 0…10 000. `null` ise sepet gösterilir ama **sipariş verilmez** (`409 TAX_DATA_MISSING`) |
| `options` | liste | aşağıdaki seçenek tablosu |
| `priceVersion` | String, null olabilir | 1–64 karakter opak sürüm; Order değişikliği fiyat değişikliği sayar. Port Javadoc'u: "Menu Service does not yet" |

- Seçenek (`ProductOption`) alanları: `optionId`, `name` (1–150), `type` (`INCLUDED`/`ADD`), `priceDeltaMinor` (≥ 0), `minQuantity`, `maxQuantity` (≤ 1000), `active`.
- `sellable() = published && available`.
- Port Javadoc'u: net fiyatlı ürün gibi brüt minor tutar veremeyen bir implementasyon **tahmin etmemeli, hata vermelidir**.
- Fiyat hesabı (`PriceCalculator`): satır başına `net = ROUND_HALF_UP(brüt × 10000 / (10000 + bps))`, `KDV = brüt − net`.
- Sepet sınırı: en çok 50 satır (`restofy.cart.max-lines`), satır başına en çok 50 adet.
- Order → QR için var olan servis kimliği örneği: `OrderQrRequestSigner`, `Authorization: Service <RS256 JWT>` (iss `restofy-order`, aud `restofy-qr`, `restaurant_id`, `method`, `target`, `body_sha256`, 30 sn ömür, `kid`). `order-qr` profiline bağlı.

### 2.2 Menu tarafı (mevcut kod)

| Konu | Mevcut |
|---|---|
| Fiyat | `products.base_price numeric(19,4)`, `currency char(3)`, `price_includes_vat boolean` (ürün bazında **KDV dahil veya hariç** olabilir) |
| KDV | `products.vat_rate numeric(7,4)`, **yüzde** olarak 0–100 (ör. `10.0000`) |
| Durum eksenleri | `is_active` (arşiv), `is_published` (taslak/yayında), kategori zincirinin etkin olması, `product_availability.status` (`AVAILABLE`, `OUT_OF_STOCK`, `TEMPORARILY_CLOSED`). Availability kaydı olmayan ürün satılamaz |
| Sipariş edilebilirlik | `ProductEligibility.canOrder = aktif ∧ yayında ∧ kategori zinciri etkin ∧ availability = AVAILABLE` |
| Seçenekler | `product_options`: `INCLUDED` (fiyat farkı 0, min 0 = çıkarılabilir / 1 = zorunlu, max 1) ve `ADD` (fiyat farkı ≥ 0, min 0, max 1–10). `price_delta numeric(19,4)`, ürünün para birimi ve KDV ayarıyla yorumlanır. Seçenek silinmez, `is_active=false` olur. Ürün başına en çok 50 aktif seçenek |
| Sürüm | `products.version` ve `product_options.version` (iyimser kilit sayacı; fiyat dışı değişikliklerde de artar) |
| Uçlar | Yönetim: `/api/v1/menu/categories…`, `/api/v1/menu/products…` (ADMIN). Herkese açık: `/api/v1/menu/public/restaurants/{restaurantRef}/…` (GET). **Order için iç uç yok**; toplu ürün sorgusu yok |
| Servis kimliği | **Yok.** Kitchen için `TrustedKitchenCallerProvider` fail-closed (`DenyAllKitchenCallerProvider`); başlıktan kimlik okunmaz |

### 2.3 Tespit edilen uyumsuzluklar

1. **KDV hariç fiyat:** Menu `price_includes_vat=false` ürüne izin veriyor; Order yalnız KDV dahil brüt kabul ediyor ve "tahmin etme, hata ver" diyor.
2. **Ondalık hassasiyet:** Menu fiyatı 4 ondalık saklıyor (ör. `12.3450 TRY`); Order minor birim (TRY için 2 hane) istiyor. Order'ın `MoneyConverter.toMinor` metodu fazla ondalığı **yuvarlamadan reddediyor**.
3. **KDV birimi:** Menu yüzde (`numeric(7,4)`), Order tam sayı baz puan istiyor. `%8,125` gibi bir oran 812,5 bps eder ve tam sayıya kayıpsız çevrilemez.
4. **Fiyat sürümü:** Order `priceVersion` alanını destekliyor, Menu üretmiyor.
5. **Görünürlük eşlemesi:** Order'da iki bayrak (`published`, `available`), Menu'de dört eksen var.

## 3. Order'ın Menu'den ihtiyaç duyduğu ürün bilgileri

`ProductSnapshot` alanlarının tamamı (§2.1). Menu'deki karşılıkları:

| Order alanı | Menu kaynağı | Not |
|---|---|---|
| `productId` | `products.id` | |
| `name` | `products.name` (≤ 200) | Uyumlu |
| `published` | `aktif ∧ is_published ∧ kategori zinciri etkin` (`isVisibleToCustomer`) | Öneri; madde 6 soru 8 |
| `available` | `product_availability.status = AVAILABLE` | Kayıt yoksa `false` |
| `currency` | `products.currency` | Menu yalnız gerçek ISO 4217 kodlarını kabul ediyor |
| `basePriceGrossMinor` | `base_price` (+ `price_includes_vat`) | Madde 4.2 |
| `taxRateBps` | `vat_rate × 100` | Madde 4.2 |
| `options[]` | `product_options` | Madde 4.3 |
| `priceVersion` | henüz yok | Madde 4.2 |

Order'a **gönderilmeyecekler:** açıklama, içerik metni, besin değerleri, görsel, kapanma nedeni (`reason_code/reason_text`), kim/ne zaman değiştirdi bilgisi, puan özeti. Bunlar sipariş fiyatlamasına gerekmez ve veri yüzeyini büyütür.

## 4. İstenen kararlar ve geliştirmeler

### 4.1 Toplu ürün sorgulama

**Mevcut:** Order tek çağrıda birden çok id istiyor (sepet görünümü ve checkout). Menu'de böyle bir uç yok; herkese açık detay ucu tek ürün döner ve `restaurantRef` ister.

**Öneri (Menu tarafı, onay bekliyor):**

```
POST /api/v1/internal/menu/catalog/products:lookup
Authorization: Service <token>          (madde 4.7)
Content-Type: application/json

{ "restaurantId": "<uuid>", "productIds": ["<uuid>", ...] }
```

- Gövdede en çok **50** benzersiz id (Order'ın `max-lines` sınırı); fazlası `400`. Tekrarlanan id tek sayılır mı, `400` mü — **açık soru**.
- Okuma işlemidir ama id listesi URL'ye sığmayabileceği ve log'larda görünmemesi için `POST` önerilir. `GET ?ids=` tercih edilirse Order söylesin.
- Yanıt yalnız bulunan ürünleri içerir; bilinmeyen veya başka restorana ait id **sessizce atlanır** (Order portunun bugünkü sözleşmesi). Bu, var olup olmadığını sızdırmaz.
- Gateway bu yolu **yönlendirmez**; yalnız iç ağdan erişilir (Order'ın `/api/v1/internal/**` kuralıyla aynı). Ağ izolasyonu ayrıca doğrulanmalı.
- `restaurantId` gövdede değil, doğrulanmış servis token'ındaki claim'den alınabilir (madde 4.7). Hangisi esas — **açık soru**.

**Kabul:** Yol, yöntem, istek/yanıt şeması, sınırlar ve sürüm (`v1`) iki ekibin onayladığı bir sözleşme dosyasında yazılı.

### 4.2 Güncel fiyat ve KDV

**İstenen kararlar:**

1. **KDV hariç ürünler.** Seçenekler:
   - (a) Menu `price_includes_vat=false` ürünü Order'a **göndermez** ya da "fiyatlanamaz" olarak işaretler; Order bunu `PRODUCT_UNAVAILABLE` sayar.
   - (b) Menu brüt fiyatı hesaplayıp gönderir. Bu durumda yuvarlama kuralı (hangi taraf, `HALF_UP` mi) yazılmalı. Bu, D-08 (vergi/yuvarlama) ile ilişkilidir.
   - (c) Menu yönetiminde KDV hariç fiyat yasaklanır (Menu ürün kuralı değişikliği; ayrı karar).

   Karar verilene kadar Menu tarafı **fail-closed** davranır: brüt minor tutarı kesin olarak üretemediği ürünü sipariş edilebilir göstermez.
2. **Ondalık hassasiyet.** Para biriminin minor hanesinden fazla ondalığı olan fiyat (ör. `12.345 TRY`): Menu yuvarlamaz; ürünü fiyatlanamaz sayar. Order bunu kabul ediyor mu? Uzun vadede Menu girişte ondalığı para birimine göre sınırlamalı mı (Menu tarafı ayrı karar)?
3. **KDV birimi.** Tel üzerinde tam sayı `taxRateBps` mı, yoksa ondalık yüzde (`"10.00"`) mı? Bps seçilirse yarım baz puanlı oranlar (ör. %8,125) için Menu ne yapmalı: ürünü fiyatlanamaz saymak mı?
4. **Para tutarı biçimi.** Tel üzerinde `long` minor birim mi (Order'ın iç modeli), yoksa ondalık string + para birimi mi? Menu minor birim önerir; JSON'da sayı mı string mi olacağı netleşmeli.
5. **Fiyat sürümü (`priceVersion`).** Menu bir sürüm üretebilir. Seçenekler:
   - `products.version` ve ilgili seçeneklerin sürümlerinden türetilen opak değer: kolay ama ad değişikliği gibi fiyat dışı güncellemelerde de değişir. Order bunu "fiyat değişti" diye müşteriye gösterir (`PRICE_VERSION`).
   - Yalnız fiyat, KDV, para birimi ve seçenek fiyatlarından hesaplanan özet: yanlış alarm üretmez, şema değişikliği gerektirmez.

   Hangisi? Ya da bu ilk sürümde `null` mı kalsın?
6. **Fiyat geçerlilik süresi / teklif.** `docs/architecture.md` §5.2 "sürümlü/süreli teklif" öneriyor; Menu yanıtına `asOf` zaman damgası ya da geçerlilik süresi eklenmeli mi? Kapanma anından sonra hiç sipariş kabul edilmemesi isteniyorsa ayrıca reserve/confirm protokolü gerekir. Menu bunu bu sürümde **önermiyor**.

### 4.3 Ürün seçenekleri ve varyasyonlar

**Mevcut uyum:** Menu `INCLUDED`/`ADD` tipleri, min/max ve aktiflik alanları Order'ın `ProductOption` modeliyle bire bir örtüşüyor. Menu sınırları (ADD en çok 10, INCLUDED en çok 1) Order'ın 1000 üst sınırının içinde.

**İstenen kararlar:**

- **Pasif seçenekler** yanıtta `active=false` ile mi yer alsın, yoksa hiç gelmesin mi? Order iki durumda da seçimi reddediyor ama mesajı farklı ("artık yok" / "artık eklenemez"). Menu `active=false` ile göndermeyi öneriyor.
- `sortOrder` Order'a gerekli mi? (Order modelinde yok.)
- `priceDeltaMinor` için 4.2'deki ondalık ve KDV kuralları aynen geçerli (seçeneğin kendi para birimi/KDV'si yok, ürünününkini kullanır).
- **Varyasyon** (boy/porsiyon, "birini seç" grupları) Menu'de **yok**, Order'da da yok. Bu sürümün kapsamı dışında; ihtiyaç varsa ayrı talep.

### 4.4 Ürünün satışa açık/kapalı durumu

- Menu üç durum tutar: `AVAILABLE`, `OUT_OF_STOCK`, `TEMPORARILY_CLOSED`. Order yalnız `available` bayrağına bakıyor.
- **Açık soru:** Order müşteriye "stokta yok" ile "geçici kapalı" ayrımını göstermek istiyor mu? İstiyorsa yanıta `availabilityStatus` eklenir; kapanma nedeni metni yine gönderilmez.
- Kitchen'ın kapatma komutu Menu'ye gelir (ADR-MENU-002). Order'a olay/push yoktur (D-10 açık). Order her sepet görünümü ve checkout'ta Menu'ye yeniden sorar.

### 4.5 Sipariş oluşturulurken doğrulama

**Mevcut Order davranışı (koddan):**

- Checkout'ta sepetteki tüm ürünler **tek çağrıyla** alınır. Her satır güncel katalogla yeniden fiyatlanır. Ürün yoksa veya satılamıyorsa, para birimi değiştiyse, seçenek artık yoksa ya da eklenemiyorsa, KDV bilinmiyorsa sipariş reddedilir.
- Müşterinin onayladığı toplam ve `PriceFingerprint` (fiyat, KDV, `priceVersion`, seçenek fiyatları) uyuşmazsa `409 PRICE_CHANGED`.
- Menu çağrısı Order'ın yazma transaction'ının **dışında** yapılır; servisler arası atomiklik yoktur.

**Menu'nün taahhüdü (öneri):**

- Yanıt, aynı istek için tek bir veritabanı okumasından (tek snapshot) üretilir; aynı yanıtta bir ürün eski, diğeri yeni fiyatla gelmez.
- Menu yanıtı "o anki" durumdur; checkout sonrası kapanan ürün için sipariş geri alınmaz. Kitchen iptal talebi Order'a gider (`docs/architecture.md` §5.2).
- Menu'de sipariş, stok ayırma veya rezervasyon **tutulmaz**.

### 4.6 Restoran izolasyonu

- Menu her sorguda `restaurant_id` ile filtreler. Başka restoranın ürünü yanıtta hiç görünmez (Order'ın port kuralıyla aynı).
- `restaurantId` hiçbir zaman tarayıcıdan gelmez. Order bu değeri QR'ın doğruladığı guest bağlamından (`GuestSessionContext.restaurantId`) alıyor. Menu ise yalnız **doğrulanmış servis kimliğine** güvenir.
- **Açık soru:** Servis token'ı tek restorana mı bağlı (Order → QR token'ında olduğu gibi `restaurant_id` claim'i, `ORDER_QR_CALLER_RESTAURANT_ID`), yoksa Order tüm restoranlar adına mı çağırır? Tek restoranlı bağlama Menu gövdedeki `restaurantId` ile claim'i karşılaştırır, uyuşmazsa `403`. Çok restoranlıda gövdedeki değer esas alınır ve güven tamamen servis kimliğine dayanır.

### 4.7 Güvenli servis kimliği (D-09)

**Mevcut:** Menu'de servisler arası kimlik doğrulama yok. Order'ın QR'a giden çağrısı için RS256 imzalı `Service` token örneği var (`OrderQrRequestSigner`), ama bu QR'a özeldir ve `order-qr` profiline bağlıdır. D-09 (servis kimliği sözleşmesi) açık.

**İstenen:**

- Order → Menu için de aynı model mi kullanılacak (Order özel anahtarla imzalar, Menu açık anahtarla yerel doğrular; `iss=restofy-order`, `aud=restofy-menu`, kısa ömür, `kid`, `jti`)? Yoksa Auth'un merkezi servis kimliği mi bekleniyor?
- `method`, `target`, `body_sha256` bağlaması Menu için de zorunlu mu? Menu zorunlu tutmayı önerir (okuma olsa bile yanıt fiyat otoritesidir).
- `scope` adı (ör. `menu:catalog-read`).
- Kitchen → Menu komutları için gelecekte de aynı model kullanılacaksa ortak kararı Auth (Kerem) ile birlikte vermek gerekir.
- Personel cookie'si (`restofy_access`) ya da guest cookie'si bu çağrıda **kullanılmaz**; misafir veya personel adına Menu'ye çağrı yapılmaz.

Karar verilene kadar Menu'deki uç (yazılırsa) **fail-closed** olur: doğrulanmış servis kimliği yoksa her istek `401`.

## 5. Hata senaryoları

| Durum | Menu yanıtı (öneri) | Order'daki mevcut karşılık |
|---|---|---|
| Bilinmeyen id / başka restoranın ürünü | Yanıtta yok (200) | Ekleme: `404 PRODUCT_NOT_FOUND`; checkout: `409 PRODUCT_UNAVAILABLE` |
| Arşivlenmiş ürün (`is_active=false`) | **Açık soru:** yanıtta yok mu, `published=false` mu? | Aynı |
| Taslak / kategori kapalı | `published=false` | `409 PRODUCT_UNAVAILABLE` |
| `OUT_OF_STOCK` / `TEMPORARILY_CLOSED` / availability kaydı yok | `available=false` | `409 PRODUCT_UNAVAILABLE` |
| KDV hariç veya ondalığı fazla fiyat | Madde 4.2 kararına göre; varsayılan: satılamaz | `409 PRODUCT_UNAVAILABLE` ya da `TAX_DATA_MISSING` |
| Seçenek pasif / silinmiş gibi | `active=false` / yok | `409 PRODUCT_UNAVAILABLE` |
| Para birimi değişti | Yeni para birimi | `422 CURRENCY_MISMATCH` |
| Geçersiz gövde, > 50 id, UUID değil | `400` (RFC 9457 problem) | Order'da eşleme yok (yazılmalı) |
| Servis kimliği yok / geçersiz | `401` | `503 DEPENDENCY_UNAVAILABLE` önerilir |
| Kimlik geçerli, restoran uyuşmuyor | `403` | `503` (iç hata; müşteriye ayrıntı verilmez) |
| Menu kapalı, zaman aşımı, 5xx | — | `503 DEPENDENCY_UNAVAILABLE`; Order bağlantı zaman aşımı 3 sn, okuma 5 sn (`REST_CLIENT_*`) |
| Yanıt Order'ın `ProductSnapshot` kısıtlarına uymuyor | — | **Açık soru:** Order tüm yanıtı mı reddeder (`503`), yalnız o ürünü mü? Menu tüm yanıtı reddetmeyi önerir (fail-closed) |

Yeniden deneme: okuma idempotent olduğu için Order kısa, sınırlı yeniden deneme yapabilir. Menu tarafında idempotency anahtarı gerekmez. Rate limit — **açık soru**.

## 6. İki ekip arasında netleştirilmesi gereken API sözleşmesi

Order'dan (Hamza) beklenen kararlar:

1. **Uç ve yöntem:** `POST /api/v1/internal/menu/catalog/products:lookup` uygun mu? `GET ?ids=` mi tercih edilir?
2. **Sınırlar:** En çok 50 id yeterli mi? Tekrarlanan id `400` mü, tekilleştirme mi?
3. **Tutar biçimi:** Minor birim `long` mu, ondalık string mi? JSON'da sayı mı, string mi?
4. **KDV biçimi:** `taxRateBps` tam sayı mı? Kesirli baz puan çıkan oranlar ne olacak?
5. **KDV hariç ürün:** 4.2'deki (a), (b), (c) seçeneklerinden hangisi? (b) ise yuvarlama kuralı; D-08 ile ilişkisi.
6. **Fazla ondalıklı fiyat:** Satılamaz saymak kabul mü?
7. **`priceVersion`:** Üretilsin mi; üretilecekse yalnız fiyat alanlarından mı türetilsin?
8. **`published`/`available` eşlemesi:** Madde 3'teki eşleme kabul mü? Arşivlenmiş ürün yanıtta yer almalı mı?
9. **`availabilityStatus`:** Müşteriye "stokta yok" ile "geçici kapalı" ayrımı gösterilecek mi?
10. **Pasif seçenekler:** `active=false` ile mi gelsin, hiç gelmesin mi? `sortOrder` gerekli mi?
11. **Servis kimliği (D-09, Auth ile):** Order → QR modelinin Menu için kopyası mı, merkezi Auth çözümü mü? `scope`, `aud`, ömür, `kid`, istek bağlama. Token tek restorana mı bağlı?
12. **ENV ve dağıtım (Alper ile):** Menu'nün Order açık anahtarını okuyacağı ENV adı ve Compose aktarımı. `docs/SECURITY.md` gereği yeni ENV açık onay ister; bu belge yeni ENV **eklemez**.
13. **Hata eşlemesi:** Menu `400/401/403/5xx` → Order kodları (bölüm 5) kabul mü? Sözleşmeye uymayan yanıtta tüm yanıt mı reddedilir?
14. **Sürümleme:** Sözleşme `v1`; alan ekleme geriye uyumlu, alan çıkarma/anlam değişikliği yeni sürüm. Kabul mü?
15. **Sözleşme dosyasının yeri:** `docs/order-service/INTEGRATION_CONTRACTS.md` içinde mi, ayrı bir Menu sözleşme belgesinde mi?

## 7. Bu talebin kapsamı dışında

- Menu → Order olay yayını (`ProductAvailabilityChanged`, D-10) ve önbellekleme.
- Stok ayırma, rezervasyon, reserve/confirm protokolü.
- Varyasyon/seçenek grupları, kampanya, indirim, servis ücreti, bahşiş (D-08).
- Kitchen → Menu servis kimliğinin uygulanması (ADR-MENU-002; aynı karara bağlı ama ayrı iş).
- Order veya diğer servislerde kod değişikliği.

## 8. Sonraki adım

Kararlar bu belgeye yanıt olarak (PR yorumu veya ayrı yanıt belgesi) geldikten sonra MENU-INT-02 kapsamında Menu tarafında iç uç, servis kimliği doğrulaması (fail-closed) ve sözleşme testleri ayrı bir PR'da geliştirilecek. Order'daki gerçek HTTP adaptörü Order ekibinin işidir.

## 9. MENU-INT-02 uygulama durumu (Menu tarafı)

| Madde | Durum |
|---|---|
| Birden çok ürünü tek çağrıda sorgulama (`OrderCatalogService.lookup`, en çok 50 farklı id, geçici) | **UYGULANDI** (iç katman) |
| Restoran izolasyonu: başka restoranın veya bilinmeyen id'nin yanıtta olmaması; çağıranın restoranı dışında sorgu `Forbidden` | **UYGULANDI** |
| DTO: ad, para birimi, ham fiyat/KDV, türetilmiş brüt minor ve baz puan, `published`/`available`/`availabilityStatus`, seçenekler | **UYGULANDI** (iç kayıtlar; tel biçimi **kararlaştırılmadı**) |
| KDV hariç, fazla ondalıklı veya aralık dışı fiyat: brüt üretilmez, yuvarlanmaz, `priceIssue` ile belirtilir | **UYGULANDI** (varsayılan fail-closed; 4.2 kararı bekliyor) |
| Arşivli/taslak/kapalı ürün: yanıtta işaretli, sipariş edilemez | **UYGULANDI** (arşivlinin çıkarılması açık soru) |
| Fiyat veya availability değişikliğinden sonra güncel veri | **UYGULANDI** (önbellek yok, tek anlık görüntü) |
| Doğrulanmış servis çağıranı kapısı | **UYGULANDI, fail-closed**: üretimde hiçbir çağıran doğrulanmaz, her sorgu 401 |
| HTTP ucu | **UYGULANMADI.** D-09 ve yol/biçim kararları olmadan açılmaz |
| Servis kimliği doğrulayıcı (Order → Menu) | **UYGULANMADI** (Auth/Order kararı) |
| `priceVersion` | **UYGULANMADI** (karar yok; üretilmez) |
| Gerçek Order → Menu uçtan uca testi | **DOĞRULANMADI** (Order'ın gerçek adaptörü yok) |
