# ADR-MENU-006 — Order katalog sorgusu: güvenilir çağıran, tam doğru fiyat ve açık kararlar

- **Durum:** Menu tarafı iç uygulama katmanı kabul edildi; HTTP ucu, tel biçimi ve servis kimliği **Order (Hamza) ve Auth (Kerem) kararlarını bekliyor**
- **Tarih:** 2026-11-24
- **Sahip:** Menu (Merve); Order ve Auth ile ortak karar gerektirir
- **İlgili talep:** [`order-request-01.md`](../menu-service/requests/order-request-01.md) (PR #49)
- **İlgili kod:** `OrderCatalogService`, `OrderCatalogProduct`, `OrderCatalogOption`, `PriceIssue`, `CatalogMoney`,
  `TrustedOrderCallerProvider`, `DenyAllOrderCallerProvider`

## Bağlam

Order, `MenuCatalogPort.findProducts` ile ürünün güncel adını, KDV dahil brüt fiyatını (minor birim), KDV oranını
(baz puan), seçeneklerini ve satışa uygunluğunu Menu'den ister. Menu'de Order için bugün bir uç, bir servis kimliği ve
bir tel sözleşmesi yoktur. Talep belgesi hangi noktaların koddan doğrulandığını, hangilerinin Order ile kararlaştırılacağını
ayırır. Bu ADR yalnız Menu'nun kararlardan **bağımsız** ve güvenle yapabildiği kısmı kaydeder.

## Karar

1. **Yalnız iç uygulama katmanı; HTTP ucu yok.** `OrderCatalogService.lookup(restaurantId, productIds)` birden çok ürünü tek
   çağrıda yanıtlar. Servis kimliği (D-09) olmadan korunmasız iç uç açılmaz. Yol, yöntem ve JSON biçimi talep belgesindeki
   açık sorulara bağlıdır; bu yüzden denetleyici eklenmedi. Üretimde `DenyAllOrderCallerProvider` hiçbir çağıranı
   doğrulamaz; her sorgu `UnauthenticatedException` ile reddedilir. Kitchen için ADR-MENU-002'nin aynı modeli.
2. **Sıra:** doğrulanmış çağıran → istek doğrulaması → restoranın çağıranınkiyle eşitliği (`Forbidden`) → sorgu.
   Kimlik hiçbir başlık, parametre veya gövdeden okunmaz.
3. **Restoran izolasyonu.** Her sorgu `restaurant_id` taşır. Bilinmeyen id ile başka restoranın id'si aynıdır: yanıtta yoktur,
   hata yoktur. Boş, `null` içeren veya 50'den çok farklı id `InvalidProductException` (400) alır; tekrarlanan id bir kez
   yanıtlanır. **50 geçicidir** (Order sepeti 50 satır), son sınır sözleşmenin parçasıdır.
4. **Ürün durumları.** Arşivli, taslak, kategorisi kapalı ve availability'si kapalı veya kaydı olmayan ürünler **yanıtta
   yer alır, işaretli** (`published=false` / `available=false`) ve hiçbir zaman sipariş edilebilir değildir
   (`published && available` = `ProductEligibility.canOrder`). Arşivlilerin yanıttan çıkarılması Order sorusudur ve güvenlik
   özelliğini değiştirmez.
5. **Fiyat tam doğru ya da yok.** Ham değerler (`basePrice`, `priceIncludesVat`, `vatRatePercent`) her zaman tam verilir.
   Türetilen `grossPriceMinor` yalnız fiyat KDV dahilse, para biriminin kendi minor hane sayısında tam ifade ediliyorsa ve
   Order'ın 10¹² sınırı içindeyse verilir; aksi hâlde `null` ve `priceIssue` nedeni (`VAT_EXCLUSIVE`,
   `PRECISION_EXCEEDS_CURRENCY`, `UNSUPPORTED_CURRENCY`, `AMOUNT_OUT_OF_RANGE`). **KDV hariç fiyattan brüt üretilmez,
   yuvarlama yapılmaz (D-08 açık).** `taxRateBps` yalnız yüzde tam sayı baz puansa verilir (8,25 → 825), kesirli ise `null`.
   Seçenek farkı aynı kuralları izler; sıfır fark her zaman `0`.
6. **`priceVersion` üretilmez.** Mantığı kararlaştırılmadı; `null` kalması Order'ın mevcut davranışıdır.
7. **Seçenekler.** Aktif olmayanlar `active=false` ile listelenir (Order sorusu 4.3); sıra `sortOrder`, ad, id.
8. **Tek anlık görüntü.** Sorgu `REPEATABLE_READ` salt okunur transaction'dadır; bir yanıttaki ürünler aynı veritabanı
   anındandır. Bu bir rezervasyon değildir: yanıttan sonra kapanan ürünün siparişi geri alınmaz (`architecture.md` §5.2).
   Önbellek yoktur; fiyat veya availability değişikliğinden hemen sonra yeni değer döner.

## Beklenen kararlar

Ayrıntı ve gerekçe: talep belgesinin 6. bölümü (15 madde). Özet: uç biçimi ve sınırlar, KDV hariç ve fazla ondalıklı fiyat,
KDV ve tutar tel biçimi, `priceVersion`, arşivli ürün ve pasif seçenek, `availabilityStatus`, **servis kimliği (D-09,
Auth)**, ENV ve Compose (Alper). Bunlar verilene kadar Menu HTTP ucu açmaz ve ENV eklemez.

## Sonuçlar

- Order, kararlar netleşince yalnız ince bir HTTP katmanı ve kimlik sağlayıcısı bekler; iş kuralları ve testleri hazırdır.
- Menu veritabanında, migration'da, ENV'de ve başka serviste değişiklik yoktur.
- Kapsam dışı: Menu → Order olayları (D-10), stok ayırma, varyasyon grupları, Kitchen servis kimliği uygulaması.
