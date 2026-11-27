# Order → Menu ürün kataloğu entegrasyonu

Bu belge [Menu REQUEST-01](https://github.com/software-lecture-labs/Restofy/pull/49) için Order tarafının yanıtıdır. Order adaptörü aşağıdaki sözleşmeyi uygular. Menu dalındaki `OrderCatalogService` toplu sorguyu ve restoran filtresini uyguluyor. Gerçek HTTP alıcısı ve servis kimliğini doğrulayan Menu adaptörü henüz yok. JSON sözleşmesi ve anahtar ayarları Menu ile ortak doğrulanmalı.

## Katalog isteği

- `POST /api/v1/internal/menu/catalog/products:lookup` ile bir restoranda en fazla 50 benzersiz ürün tek istekte sorgulanır. Tekrar eden kimlikler ilk göründükleri sırada tekilleştirilir. Boş ürün kümesinde Order yerel olarak boş sonuç döndürür.
- `restaurantId`, QR tarafından doğrulanmış Order bağlamından gelir. Önerilen istek gövdesi `{"restaurantId":"<uuid>","productIds":["<uuid>"]}`. Menu, gövdedeki restoranı imzalı restoran kimliğiyle karşılaştırır. Bilinmeyen ve başka restorana ait ürünler yanıtta yer almaz.
- Sepet gösterimi ve checkout mevcut `MenuCatalogPort` üzerinden güncel kataloğu okur. Checkout ürünleri bir defada sorgular. Menu yanıtı tek veritabanı snapshot’ından üretir. V1’de stok ayırma, süreli fiyat garantisi ve önbellek bu akışın kapsamı dışındadır.

## Fiyat ve ürün sözleşmesi

- Para birimi büyük harfli ISO 4217 kodu. Fiyat ve seçenek farkı JSON tamsayısı olarak KDV dahil minor biriminde, KDV oranı tam sayı `taxRateBps` olarak iletilir.
- KDV hariç fiyat, minor birime tam sığmayan tutar ve kesirli baz puan yuvarlanmaz. Kesin brüt fiyat yoksa `basePriceGrossMinor: null` ve `priceIssue` gönder. KDV oranı tam baz puan değilse `taxRateBps: null` gönder. Order ürünü satılamaz sayar ve sipariş kabul etmez.
- `priceVersion` V1’de isteğe bağlıdır. Menu daha sonra sürüm üretirse fiyatı etkileyen alanlardan türetilen opak bir değer tercih edilir. Order mevcut tutar, KDV ve seçenek karşılaştırmalarına devam eder.
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
- Menu `400/401/403` veya `5xx` döndürürse, bağlantı veya zaman aşımı olursa ya da yanıt imzalı isteğin kapsamına ve katalog şemasına uymazsa Order `503 DEPENDENCY_UNAVAILABLE` döndürür. Tanınmayan veya başka restorana ait ürünün yanıttaki yokluğu, geçerli bir katalog sonucudur ve Order mevcut ürün hatalarını kullanır.
- Sürüm `v1`. Aynı sürümde geriye uyumlu alan eklenebilir. Zorunlu alanı kaldırma veya bir alanın anlamını değiştirme yeni sözleşme sürümü gerektirir. Yanıt alan listesi ve tüketicinin isteğe bağlı ek alanları nasıl işleyeceği iki tarafın sözleşme testinde doğrulanmalı.

## Sıradaki işler

- **Order:** `MenuCatalogPort` için HTTP adaptörü mevcut. Ortak ortamda açmadan önce Menu alıcısı ve anahtar yapılandırmasıyla doğrula.
- **Menu:** İç katalog servisine imzalı HTTP alıcısını bağla. Auth çerezi kullanmadan Order'ın açık anahtarıyla imzayı doğrula, restoranı gövdeyle karşılaştır ve yukarıdaki katalog yanıtını üret.
- **Ortak:** İki servisin yapılandırmasını ve gerçek Order → Menu çağrısını doğrula. Sepete ürün ekleme, sepeti görüntüleme ve checkout sırasında fiyat veya stok değişikliğini birlikte dene.
