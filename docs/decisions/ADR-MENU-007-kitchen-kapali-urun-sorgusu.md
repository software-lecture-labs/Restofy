# ADR-MENU-007 — Kitchen kapalı ürün sorgusu: salt okunur, güvenilir çağıran, ham bayraklar

- **Durum:** Menu tarafı iç uygulama katmanı kabul edildi; HTTP ucu, tel biçimi ve servis kimliği **Kitchen (Nisa) ve Auth (Kerem) kararlarını bekliyor**
- **Tarih:** 2026-11-24
- **Sahip:** Menu (Merve); Kitchen ve Auth ile ortak karar gerektirir
- **İlgili talep:** [`kitchen-request-01.md`](../menu-service/requests/kitchen-request-01.md) (PR #50); komut tarafı için [ADR-MENU-002](ADR-MENU-002-kitchen-availability-komutlari.md)
- **İlgili kod:** `KitchenClosedProductsService`, `KitchenClosedProduct`, `KitchenClosedProductsPage`,
  `ProductAvailabilityRepository.findByStatuses`, `ClosedProductRow`, `TrustedKitchenCallerProvider`

## Bağlam

Kitchen kapalı ürünler listesini Menu'den okur ve kendi kopyasını tutmaz (`MenuClosedProductsPort`). Komut tarafı
(`KitchenAvailabilityCommandService`) ADR-MENU-002 ile hazırdır; okuma tarafının Menu'de karşılığı yoktu (yönetim listesi
yalnız ADMIN'e açıktır ve availability filtresi yoktur). Bu ADR yalnız Menu'nun kararlardan **bağımsız** ve güvenle
yapabildiği okuma tarafını kaydeder. Komut tarafında hiçbir davranış değişmedi.

## Karar

1. **Yalnız iç uygulama katmanı; HTTP ucu yok.** `KitchenClosedProductsService.list(statuses, page, size)`. Servis kimliği
   (D-09) olmadan korunmasız iç uç açılmaz; yol ve JSON biçimi Kitchen sorularına bağlıdır. Üretimde
   `DenyAllKitchenCallerProvider` hiçbir çağıranı doğrulamaz, her sorgu `UnauthenticatedException` ile reddedilir.
2. **Restoran yalnız doğrulanmış çağırandan** (komutlarla aynı `TrustedKitchenCaller`); istekte restoran alanı yoktur.
   Sıra: çağıran doğrulaması → istek doğrulaması → veri.
3. **Süzgeç ve sıra.** Yalnız çağıranın restoranı ve istenen kapalı durumlar (`OUT_OF_STOCK`, `TEMPORARILY_CLOSED`);
   `AVAILABLE`, boş ve `null` durum, negatif sayfa, boyut 1–100 dışı `InvalidProductException` (400). Sıra
   `changedAt DESC, productId ASC` (PostgreSQL `uuid` sırası), toplam aynı süzgeçle sayılır. 100 geçicidir (Kitchen'ın kendi
   sınırı); son değer sözleşmenin parçasıdır.
4. **Menu'nün kendi kaydı, uydurma yok.** Ürün kimliği ve adı, durum, neden kodu/metni, `changedAt`, `changedByUserId` ve
   availability `version` aynen döner. `version`, komutun `expectedVersion` alanının karşılaştırıldığı değerdir
   (testle doğrulandı: listelenen sürüm komutta eşleşir, bir fazlası çakışmadır).
5. **`active` ve `published` ham bayraktır, yorumlanmaz.** Arşivli ve yayınsız kapalı ürünler listeden çıkarılmaz.
   Kitchen'ın "published" ile ne kastettiği (ham bayrak mı, müşteriye görünürlük mü) ve arşivli ürünün listede yer alıp
   almayacağı açık sorudur (talep 6. bölüm, madde 5 ve 6); birini seçmek ayrı iş kuralı olurdu.
6. **Tek anlık görüntü, önbellek yok.** Sayfa ve toplam aynı `REPEATABLE_READ` salt okunur transaction'dan gelir. Bir komut
   uygulandıktan hemen sonraki sorgu yeni durumu döner. Menu bir olay yayınlamaz (D-10).

## Bilerek yapılmayanlar (kararlar bekliyor)

Kapatma nedeninin isteğe bağlı olması, makbuza `changedAt`/`productId` eklenmesi, `NO_CHANGE`/`replayed` anlamı,
`expectedVersion` zorunluluğu, `PRODUCT_INACTIVE` kuralı, HTTP ucu ve servis kimliği: hiçbiri değiştirilmedi veya
uydurulmadı. Ayrıntı ve seçenekler talep belgesinin 6. bölümündedir.

## Sonuçlar

- Kitchen, kararlar netleşince yalnız ince bir HTTP katmanı ve kimlik sağlayıcısı bekler; süzgeç, sıra, sayfalama ve testleri hazırdır.
- Menu veritabanında, migration'da, ENV'de ve başka serviste değişiklik yoktur (`idx_product_availability_restaurant_status` mevcut).
- Kapsam dışı: Menu → Kitchen olayları (D-10), Order'ın katalog okuması, Kitchen tarafı uyarlaması.
