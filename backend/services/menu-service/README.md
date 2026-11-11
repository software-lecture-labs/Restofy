# Menu Service — MENU-01 … MENU-04

Menu Service, restoranın kategori, ürün, içerik, seçenek ve satışa uygunluk bilgisinin **tek sahibidir** ve müşteri menüsünü salt
okunur sunar. Java 25, Spring Boot 4, PostgreSQL 17 (Flyway V61–V67), kendi veritabanı `DB_MENU_NAME`.

## Durum özeti (gerçek durum)

| Alan | Durum |
|---|---|
| Kategori, ürün, içerik metni, görsel referansı, seçenekler, kalori/gramaj/hazırlık süresi, availability (ADMIN uçları) | Uygulandı ve testli. **Üretimde 401**: Auth entegrasyonu (D-09) gelene kadar ADMIN uçları fail-closed |
| Müşteri menüsü (kategori ağacı, ürün listesi, filtre, arama, ürün detayı) | Uygulandı ve testli. **Üretimde 404**: QR/Admin restoran doğrulaması yok, çözümleyici fail-closed |
| Kitchen availability komutları (kalıcı idempotency, V67) | **Sözleşme ve işleyici hazır, HTTP ucu ve gerçek Kitchen bağlantısı yok** |
| Rating özeti | **Yalnız port ve varsayılan**. Rating Service yok, üretimde puan her zaman `UNAVAILABLE` |

"Uygulandı" burada Menu'nun kendi kodunu kapsar. QR, Admin, Auth, Kitchen veya Rating ile **gerçek bir bağlantı kurulmamıştır**.

## Katmanlar

`api` (controller, DTO, hata sözleşmesi) → `application` (servisler, mapper, port arayüzleri) → `domain` (entity, enum, kural) →
`infrastructure` (JPA depoları, güvenlik, istemci varsayılanları). Servisler arası veri paylaşımı yoktur; başka servisin veritabanına
erişilmez. `restaurant_id`, `*_user_id` alanları mantıksal referanstır (fiziksel FK yok).

## API

Tüm yollar `/api/v1/menu` altındadır; hata gövdesi RFC 9457 `ProblemDetail`'dır.

### Yönetim (yalnız ADMIN, kendi restoranı)

| Yol | Amaç |
|---|---|
| `GET/POST /categories`, `GET/PATCH /categories/{id}`, `GET/POST /categories/{id}/subcategories`, `GET/PATCH /subcategories/{id}` | Ana ve alt kategori |
| `GET/POST /products`, `GET/PATCH /products/{id}` | Ürün (fiyat, KDV, içerik, görsel, kalori/gramaj/hazırlık, kısmi güncelleme) |
| `PATCH /products/{id}/active`, `/publication`, `/availability` | Arşiv, yayın ve satışa uygunluk (üç bağımsız eksen) |
| `GET/POST /products/{productId}/options`, `GET/PATCH …/{optionId}` | INCLUDED / ADD seçenekleri (silinmez, pasifleştirilir) |

Güncellemeler `version` ile optimistic locking kullanır (eski sürüm 409). PATCH'te alan yok = korunur, açık `null` = temizlenir
(kalori, gramaj, hazırlık süresi). Yönetim yanıtında `isVisibleToCustomer`, `isListedPublicly` ve `canOrder` türetilmiş değerlerdir.

### Müşteri (salt okunur, oturum gerektirmez, `Cache-Control: no-store`)

| Yol | Yanıt |
|---|---|
| `GET /public/restaurants/{restaurantRef}/categories` | Aktif ana/alt kategoriler |
| `GET /public/restaurants/{restaurantRef}/products?categoryId&subcategoryId&q&page&size&sort` | Ürün kartları (sayfalı) |
| `GET /public/restaurants/{restaurantRef}/products/{productId}` | Ürün detayı (seçenekler ve rating özeti dahil) |

- `categoryId` ana kategoridir (alt kategorilerini kapsar), `subcategoryId` yalnız o alt kategoriyi süzer; uyuşmazlık 400, bulunamayan,
  başka restoranın veya pasif kategori 404. `q` yalnız ürün adında, 2–50 karakter, Türkçe karakter ve harf büyüklüğü duyarsız.
  `size` varsayılan 20, en çok 50; `sort` yalnız `name` (varsayılan), `basePrice`, `createdAt` + `,asc|desc`.
- Gizli, bilinmeyen, başka restoranın ürünü ve doğrulanmamış restoran **aynı 404**; geçersiz UUID 400.
- Müşteri DTO'ları yönetim DTO'larından ayrıdır: restoran kimliği, sürüm, audit, durum bayrakları, availability nedeni ve KDV oranı
  yoktur. `restaurantRef` istemci girdisidir; restoran yalnız doğrulayan çözümleyiciden gelir (aşağıda).

## Güvenlik sınırı

Menu'da dört **fail-closed port** vardır. Üretim varsayılanlarının hiçbiri bir şeyi doğrulamaz; gerçek uygulamalar ilgili servis
hazır olunca ayrı görevlerde eklenir. `X-Restaurant-Id`, `X-Role`, `X-User-Id` gibi istemci başlıkları hiçbir yerde yetki kaynağı
değildir.

| Port | Üretim varsayılanı | Gerçek kaynak |
|---|---|---|
| `CurrentActorProvider` (yönetim aktörü) | Aktör yok → 401 | Auth personel JWT'si (D-09) |
| `PublicRestaurantScopeResolver` (müşteri restoranı) | Doğrulanan restoran yok → 404 | QR / Admin doğrulaması |
| `TrustedKitchenCallerProvider` (Kitchen komutu) | Çağıran yok → komut işlenmez | Servisten servise kimlik (D-09) |
| `RatingSummaryPort` (puan özeti) | `UNAVAILABLE`, ağ çağrısı yok | Rating Service |

Test çözümleyicileri ve test çağıranları yalnız `src/test` içindedir ve paketlenmez. `SecurityFilterChain` iki parçadır: yalnız
`GET /api/v1/menu/public/**` açıktır; diğer her yol Spring Boot'un önceki varsayılanıyla aynıdır (kimlik zorunlu, CSRF açık, yalnız
`health` açık). Hata yanıtları sabit metin taşır; beklenmeyen hata 500 ve bir `incidentId` döner, sunucu logu yalnız istisna sınıfı,
rota şablonu ve uygulama kare bilgisi içerir (mesaj veya gerçek yol yok).

## Görünürlük ve sipariş uygunluğu

| Ürün durumu | Müşteri menüsünde | `canOrder` |
|---|---|---|
| Aktif + yayınlı + aktif kategori zinciri + availability kaydı + `AVAILABLE` | Görünür | `true` |
| `OUT_OF_STOCK` / `TEMPORARILY_CLOSED` | Görünür | `false` |
| Arşivli, taslak, pasif kategori, pasif üst kategori, availability kaydı yok | Gizli (404) | `false` |

Kural tek yerdedir (`ProductEligibility.isListedPublicly()`) ve SQL filtresiyle 80 durum kombinasyonunda aynı sonucu verdiği testle
doğrulanır. `canOrder` bir **gösterim bilgisidir**; hiçbir şey rezerve edilmez, Order sipariş anında availability'yi yeniden
doğrulamak zorundadır. Müşteri okumaları tek salt okunur `REPEATABLE READ` işlemidir (anlık görüntü; canlı değil).

## Kitchen availability komutları

**Sözleşme sahibi ve availability verisinin tek otoritesi Menu Service'tir; Kitchen tüketicidir.**
`KitchenAvailabilityCommandService` mevcut Menu işleyicisidir. Kitchen PR #16'da istemci portu ve uygulama akışı vardır,
ancak iki tarafın gerçek üretim bağlantısı yoktur. Aşağıdaki v1 veri sözleşmesi mevcut Menu modellerini belgeler;
Kitchen tarafında uyarlanması beklenen sağlayıcı sözleşmesidir, iki ekibin tamamlanmış entegrasyon onayı değildir.
**Yeni internal HTTP endpoint veya HTTP adaptörü uygulanmamıştır.** JSON örnekleri bir HTTP method/path ilan etmez.

### Canonical request v1

```json
{
  "commandId": "11111111-1111-4111-8111-111111111111",
  "productId": "22222222-2222-4222-8222-222222222222",
  "targetStatus": "OUT_OF_STOCK",
  "reasonCode": "OUT_OF_STOCK",
  "reasonText": "Malzeme tükendi",
  "expectedVersion": null
}
```

| Alan | Kural |
|---|---|
| `commandId` | Zorunlu UUID; ilk deneme ve tekrarlarında aynı kimlik kullanılır; yetki kanıtı değildir |
| `productId` | Zorunlu UUID; yalnız doğrulanmış çağıranın restoranında aranır |
| `targetStatus` | Zorunlu: `AVAILABLE`, `OUT_OF_STOCK`, `TEMPORARILY_CLOSED` |
| `reasonCode` | Kapalı durumlarda zorunlu; normalize edilince 1–40 karakter, `[A-Z][A-Z0-9_]{0,39}`; `AVAILABLE` için isteğe bağlı |
| `reasonText` | İsteğe bağlı, normalize edilince en çok 500 karakter; mevcut güvenli metin doğrulaması uygulanır |
| `expectedVersion` | İsteğe bağlı (`null` olabilir), verilirse negatif olmayan **availability** sürümü; ürün sürümü değildir |

Restoran ve `onBehalfOfUserId`, `TrustedKitchenCallerProvider` tarafından doğrulanmış servis bağlamından gelir; request
alanları değildir. İstemcinin restoran/kullanıcı/rol bildirmesi yetki sağlamaz. Kitchen bu bağlamı gerçek D-09 servis kimliği
ile aktaracak adaptörü henüz geliştirmemiştir; Menu'nun üretim varsayılanı çağıran döndürmez ve işlem reddedilir.
Eksik kapatma nedeni adaptörde uydurulmaz. Availability komutu fiyatı, aktifliği veya yayın durumunu değiştirmez.

### Canonical response v1 — işlem makbuzu

```json
{
  "outcome": "APPLIED",
  "replayed": false,
  "status": "OUT_OF_STOCK",
  "version": 8
}
```

| Sonuç | Anlam |
|---|---|
| `APPLIED`, `replayed=false` | Availability değişti; sürüm ve geçmiş güncellendi |
| `NO_CHANGE`, `replayed=false` | Durum ve normalize neden kodu/metni aynı; yeni geçmiş veya sürüm yok, komut yine kaydedilir |
| `replayed=true` | Önceki kabulün `outcome`, `status`, `version` değerleri döner; tekrar uygulama yapılmaz |

Aynı durum fakat farklı neden yeni değişiklik sayılabilir. **Replay tarihsel makbuzdur, canlı ürün durumu değildir.**
Makbuzda `changedAt`, `changedByUserId`, `published` veya `productId` yoktur; Kitchen'ın ayrıntılı durum modeli için
bu alanlar uydurulamaz. Güncel ürün görünümünün yetkili bir okuma ile nasıl sağlanacağı ayrı entegrasyon kararıdır.

V67 idempotency kapsamı `(restaurantId, commandId)`'dir. Aynı kimlik ve aynı normalize içerik ilk sonucu döndürür;
farklı içerik çakışmadır. İçerik; ürün, kaynak, hedef durum, nedenler, beklenen sürüm ve adına işlem yapılan personeli
kapsar. Reddedilen işlem kayıt bırakmaz. Güncelleme, history ve komut kaydı tek yerel transaction'dadır.
`expectedVersion` kontrolü, ürün başına `PESSIMISTIC_WRITE` kilidi ve optimistic locking korunur. Satır kilidi komutları
kilidi elde etme sırasına göre serileştirir; **sürümsüz gecikmiş eski komut daha yeni durumu ezebilir**.

### Hata ve tüketici eşlemesi

Bu tablo işleyicinin mevcut sonuç/exception davranışını gösterir. Parantezdeki HTTP kodları yalnız mevcut genel
`ApiExceptionHandler` eşlemesidir; henüz bulunmayan internal HTTP transport'un uygulanmış sözleşmesi değildir.

| Senaryo | Mevcut Menu sonucu | Kitchen uyarlaması / önerilen anlam |
|---|---|---|
| Başarılı değişiklik | `APPLIED` | Onay; `outcome` ve replay bilgisi korunur |
| Etkisiz komut | `NO_CHANGE` | Başarılı fakat değişiklik yok; yeni işlemle karıştırılmaz |
| Aynı komutun tekrarı | `replayed=true`, ilk sonuç | Tarihsel makbuz; canlı durum olarak sunulmaz |
| Aynı kimlik, farklı içerik | `CommandIdConflictException` (409) | `COMMAND_CONFLICT` |
| Beklenen sürüm farklı | `ProductAvailabilityVersionConflictException` (409) | `VERSION_CONFLICT` |
| Ürün yok veya başka restoranda | `ProductNotFoundException` (404) | Aynı `PRODUCT_NOT_FOUND`; tenant bilgisi sızdırılmaz |
| Availability kaydı yok | `ProductAvailabilityMissingException` (409) | Ayrı `AVAILABILITY_MISSING` önerisi; sürüm çakışması değildir |
| Geçersiz komut / eksik kapatma nedeni | `InvalidProductException` (400) | `INVALID_COMMAND` |
| Doğrulanmış çağıran yok | `UnauthenticatedException` (401), işlem yapılmaz | Kimlik hatası; Kitchen eşlemesi ortaklaştırılacak |
| Servise erişilemiyor / timeout | Mevcut ağ adaptörü yok | Gelecek adaptörde `UNAVAILABLE`; sonuç belirsiz olabilir, başarı sayılmaz |

### Kitchen tarafında bekleyen uyarlamalar

PR #16'nın `MenuAvailabilityPort`'u bir istemci portudur; Menu domain sınıflarının paylaşılması gerekmez.
`MenuAvailabilityCommand`'da `expectedVersion` yoktur; isteğe bağlı alan eklenmesi tüketici uyarlamasıdır. Kitchen'ın
kapatmayı nedensiz kabul etmesi, ayrıntılı `ProductAvailabilityState` beklemesi ve `NO_CHANGE`/replay ayrımını taşımaması
bu sözleşmeyle henüz uyumlu değildir. Kitchen `PRODUCT_INACTIVE` reddi öngörürken mevcut Menu komut işleyicisinde pasif
veya yayınsız ürün için ayrı ret yoktur; availability ile yayın/aktiflik bağımsızdır. Ek yasak kendiliğinden uygulanmaz.
Gerçek servis kimliği, sıralama/tazelik, canlı durum okuması ve hata eşlemeleri Kitchen/Auth ile ortaklaştırılacaktır.
Yeni HTTP adaptörü bu dokümantasyon görevinin dışındadır. Kapalı ürün yalnız açık bir `AVAILABLE` komutuyla açılır.

Ayrıntılı karşılaştırma, alternatifler ve açık kararlar:
[ADR-MENU-002](../../../docs/decisions/ADR-MENU-002-kitchen-availability-komutlari.md).

## Veri

`V61` kategoriler, `V62` ürünler, `V63` availability ve geçmiş, `V64` içerik metni, `V65` seçenekler, `V66` kalori/gramaj/süre
sınırları, `V67` komut kayıtları. Sıradaki boş sürüm V68 (Menu aralığı V61–V70). `ddl-auto=validate`; uygulanmış migration
değiştirilmez.

## Yapılandırma

Standart veritabanı ENV'lerinin (`POSTGRES_*`, `DB_MENU_NAME`) yanında tek Menu'ya özel anahtar vardır:

| Anahtar | Anlam |
|---|---|
| `MENU_IMAGE_ALLOWED_ORIGINS` | Ürün görseli URL'sinin işaret edebileceği tam HTTPS origin'leri (virgülle). **Boş = görseller kapalı**: URL kaldırılabilir, ayarlanamaz. Geçersiz giriş uygulamayı başlatmaz ve hata iletisi anahtar adını verir (değeri vermez) |

Menu görseli indirmez, saklamaz, yönlendirmez; yalnız doğrulanmış bir referans tutar. Anahtarı `.env.example` ve `docker-compose.yml`
içinde bulabilirsiniz (isteğe bağlı, boş varsayılan).

## Java 25 ile test

```sh
cd backend
JAVA_HOME=<JDK 25> mvn -pl services/menu-service clean verify
```

Testler gerçek PostgreSQL 17 (Testcontainers) kullanır; JaCoCo satır kapsamı eşiği %80'dir. Docker çalışıyor olmalıdır. Test
veritabanının bağlantı sınırı sonludur ve her farklı Spring bağlamı kendi bağlantı havuzunu tutar: yeni testler mevcut bağlam
anahtarını (aynı `@Import` kümesi) paylaşmalıdır, aksi hâlde `too many clients` hatası çıkabilir.

## Bilinen sınırlar ve bekleyen bağımlılıklar

- Gerçek QR/Admin doğrulaması ve Auth servis kimliği yok; Kitchen'da istemci portu/akışı var fakat gerçek Menu adaptörü
  yok; Menu'nun gerçek Rating bağlantısı da yok (yukarıdaki tablo).
- **Hız sınırı yok** (müşteri uçları açılmadan önce gerekir); restoran açık/kapalı kontrolü yok (Admin yok).
- `expectedVersion` olmayan gecikmiş Kitchen komutu yeni durumu ezebilir; gerçek entegrasyonda sıralama/tazelik gerekir.
- Rating puan ölçeği (1–5) geçici bir varsayımdır; tazelik bilgisi sözleşmede yoktur.
- Seçenek fiyat farkı ürünün para birimiyle okunur; ürün para birimi değişirse aynı sayı yeni birimde okunur (belgelenmiş açık karar).
- Ürün araması için indeks yok (ölçülmedi); ürün adı sıralaması katlanmış anahtarladır, Türkçe sözlük sırasıyla birebir aynı değildir.
- Kilitler **ürün başınadır** (availability ve seçenek sınırı); servis genelinde global bir kilit yoktur.

## Kararlar

[ADR-MENU-001](../../../docs/decisions/ADR-MENU-001-musteri-menusu-guven-modeli.md) müşteri menüsü güven modeli ·
[ADR-MENU-002](../../../docs/decisions/ADR-MENU-002-kitchen-availability-komutlari.md) Kitchen komutları ·
[ADR-MENU-003](../../../docs/decisions/ADR-MENU-003-rating-ozeti-sozlesmesi.md) Rating özeti ·
[ADR-MENU-004](../../../docs/decisions/ADR-MENU-004-kalicilik-erisimi-ve-arama.md) kalıcılık erişimi ve arama.
