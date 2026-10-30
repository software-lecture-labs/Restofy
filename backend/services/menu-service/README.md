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

`KitchenAvailabilityCommandService`: `commandId` ile kalıcı idempotency (V67), üç durum arasında tüm geçişler, isteğe bağlı
`expectedVersion`, geçmiş + komut kaydı + güncelleme tek işlemde, otomatik yeniden açılma yok. **HTTP ucu yoktur.**
Ayrıntılar: [ADR-MENU-002](../../../docs/decisions/ADR-MENU-002-kitchen-availability-komutlari.md).

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

- Gerçek QR/Admin doğrulaması, Auth servis kimliği, Kitchen istemcisi ve Rating Service yok (yukarıdaki tablo).
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
