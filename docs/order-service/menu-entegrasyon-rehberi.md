# Order → Menu ürün kataloğu entegrasyonu

Order PR #79’daki adaptör ile Menu PR #49’daki iç katalog ucu aynı V1 sözleşmesini kullanır. Menu uçta RS256 imzasını doğrular, `kid` bazında restoran iznini denetler ve toplu sorguyu yürütür. Menu ekibi Order `d973223` ve Menu ile yerel HTTPS testinde sepete eklemeyi, fiyat değişikliğini, satılamayan ürünleri, restoran izolasyonunu ve bağlantı hatalarını doğruladı. Bu testte QR ve Auth için yerel test adaptörleri kullanıldı. [Order PR #79](https://github.com/software-lecture-labs/Restofy/pull/79) · [Menu PR #49](https://github.com/software-lecture-labs/Restofy/pull/49) · [Menu ADR-009](https://github.com/software-lecture-labs/Restofy/blob/dbbb99ef3b970283266373bc8afe3c741fc0b0e5/docs/decisions/ADR-MENU-009-order-katalog-http-ucu.md)

## Katalog isteği

- `POST /api/v1/internal/menu/catalog/products:lookup` ile bir restoranda en fazla 50 benzersiz ürün tek istekte sorgulanır. Tekrar eden kimlikler ilk göründükleri sırada tekilleştirilir. Boş ürün kümesinde Order yerel olarak boş sonuç döndürür.
- `restaurantId`, QR tarafından doğrulanmış Order bağlamından gelir. Önerilen istek gövdesi `{"restaurantId":"<uuid>","productIds":["<uuid>"]}`. Menu, gövdedeki restoranı imzalı restoran kimliğiyle karşılaştırır. Bilinmeyen ve başka restorana ait ürünler yanıtta yer almaz.
- Sepet gösterimi ve checkout mevcut `MenuCatalogPort` üzerinden güncel kataloğu okur. Checkout ürünleri bir defada sorgular. Menu yanıtı tek veritabanı snapshot’ından üretir. V1’de stok ayırma, süreli fiyat garantisi ve önbellek bu akışın kapsamı dışındadır.

## Fiyat ve ürün sözleşmesi

- Para birimi büyük harfli ISO 4217 kodu. Fiyat ve seçenek farkı JSON tamsayısı olarak KDV dahil minor biriminde, KDV oranı tam sayı `taxRateBps` olarak iletilir.
- Order katalog yanıtındaki kesirli veya metin biçimindeki tam sayı alanlarını `503 DEPENDENCY_UNAVAILABLE` ile reddeder. Menu bu alanları JSON tam sayısı olarak üretir. Sözleşmede izin verilen `null` değerleri fiyatın kesin olarak hesaplanamadığını belirtmeye devam eder.
- KDV hariç fiyat, minor birime tam sığmayan tutar ve kesirli baz puan yuvarlanmaz. Kesin brüt fiyat yoksa `basePriceGrossMinor: null` ve `priceIssue` gönder. KDV oranı tam baz puan değilse `taxRateBps: null` gönder. Order ürünü satılamaz sayar ve sipariş kabul etmez.
- Menu V1’de `priceVersion` göndermez. Order bu alanı isteğe bağlı okur ve güncel tutar, KDV ve seçenekleri karşılaştırır.
- `published` için aktif ürün, yayımlanmış ürün ve aktif kategori zinciri gerekir. `available` yalnız `AVAILABLE` kaydında doğrudur. Arşivli ürün aynı restoranda `published=false` dönebilir. V1’de `availabilityStatus` veya kapanma açıklaması müşteriye taşınmaz.
- Seçenekler `optionId`, `name`, `type`, `priceDeltaMinor`, `minQuantity`, `maxQuantity` ve `active` taşır. Pasif seçenek `active=false` döner. Aktif bir seçeneğin fiyatı kesin değilse Order ürünü satılamaz sayar. `sortOrder` ve varyasyon grupları Order V1 sözleşmesine alınmaz.
- Order `200` ve `{"products":[...]}` zarfını bekler. Eşleşme yoksa `{"products":[]}` dön. Menu'nun iç modelindeki `grossPriceMinor` alanını telde `basePriceGrossMinor` olarak yaz.

```json
{
  "products": [{
    "productId": "11111111-1111-4111-8111-111111111111",
    "name": "Salata",
    "published": true,
    "available": true,
    "currency": "TRY",
    "basePriceGrossMinor": 1300,
    "taxRateBps": 1000,
    "options": []
  }]
}
```

## Servis kimliği ve hata davranışı

- Order, `Authorization: Service <JWT>` başlığında RS256, `kid`, UUID `jti`, `iat = nbf` ve en fazla 30 saniye ömür kullanır. Claim'ler `iss=restofy-order`, `sub=order-service`, `aud=restofy-menu`, `token_type=service` ve `scope=menu:catalog-read` içerir. İmza `restaurant_id`, `method`, `target` ve `body_sha256` ile isteğe bağlanır. Çağrı HTTPS üzerinden ve Gateway dışında yapılır. Tarayıcı çerezleri ve Origin gönderilmez.
- Order'da `order-menu` profili varsayılan olarak kapalıdır. Açıldığında `MENU_SERVICE_URL` bir HTTPS origin olmalı ve `restofy.cart.max-lines` değeri 50'yi aşmamalı. `ORDER_MENU_CALLER_KEY_ID`, Base64 PKCS#8 `ORDER_MENU_CALLER_PRIVATE_KEY` ve `ORDER_MENU_CALLER_RESTAURANT_ID` yalnız Order konteynerine aktarılır. Menu, `kid` ve karşılık gelen açık anahtarı kendi doğrulayıcısında yapılandırır. QR ve Auth servislerinde değişiklik gerekmez.
- Menu → QR için bu düzenin ayrı anahtar ve yerel imza doğrulaması kısmı #65 ile `dev`'e girdi. Menu, restoranı öğrenmek için QR'a sorduğundan o çağrıda `restaurant_id` yoktur. Order katalog çağrısında restoran zaten QR tarafından doğrulanmış bağlamdan gelir. Menu, bu yüzden imzadaki restoranı istek gövdesiyle eşleştirebilir. Kerem, #65 yorumunda Auth tarafında bu kimlik modeline itiraz etmediğini ve D-09'un son kararının hocaya ait olduğunu belirtti. [QR PR #65](https://github.com/software-lecture-labs/Restofy/pull/65) · [Auth incelemesi](https://github.com/software-lecture-labs/Restofy/pull/65#issuecomment-7198312437)
- Menu `400`, `401`, `403`, `413`, `415` veya `5xx` döndürürse, bağlantı veya zaman aşımı olursa ya da yanıt katalog şemasına uymazsa Order `503 DEPENDENCY_UNAVAILABLE` döndürür. Tanınmayan veya başka restorana ait ürünün yanıttaki yokluğu geçerli bir katalog sonucudur. Order mevcut ürün hatalarını kullanır.
- Sürüm `v1`. Aynı sürümde geriye uyumlu alan eklenebilir. Zorunlu alanı kaldırma veya bir alanın anlamını değiştirme yeni sözleşme sürümü gerektirir. Yanıt alan listesi ve tüketicinin isteğe bağlı ek alanları nasıl işleyeceği iki tarafın sözleşme testinde doğrulanmalı.

## Ortak ortamda devreye alma

1. Order #79 ve Menu #49 ilgili ortama alınır. D-09 onayı ve ortak TLS yapılandırması tamamlanır. `MENU_SERVICE_URL`, sertifikası Order tarafından doğrulanabilen HTTPS adresini göstermeli.
2. **Menu tarafı.** Merve, Order’dan aldığı açık anahtarı ve `kid` değerini ortak ortamda `MENU_ORDER_CALLER_PUBLIC_KEYS` ayarına ekler. Bu ayar `kid → Base64 X.509 RSA açık anahtar` JSON eşlemesidir. Aynı `kid` için `MENU_ORDER_CALLER_RESTAURANTS` listesine Order’ın `ORDER_MENU_CALLER_RESTAURANT_ID` değerini ekler. Menu açık anahtarı tanımazsa `401`, anahtarı tanıyıp restoran izni bulamazsa `403` döndürür. Order her iki durumda da `503 DEPENDENCY_UNAVAILABLE` verir. Özel anahtar yalnız Order’da kalır. Bu ayarlar Menu #49’da mevcut.
3. Ayarlar hazır olduğunda Order’da `order-menu` profili etkinleştirilir. Ortak ortamda gerçek QR oturumuyla sepete ürün ekleme, sepeti görüntüleme ve checkout doğrulanır. Fiyatı değişen veya satışa kapanan ürünün checkout davranışı, restoran yetkisi ve Menu kesintisinden geri dönüş de denenir.
