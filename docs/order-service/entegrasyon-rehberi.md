# Waiter → Order entegrasyon rehberi

Order, WAITER-01 masa ve sipariş okumaları ile WAITER-03 “servis edildi” komutunun uçlarını sağlıyor. Waiter’ın `OrderClient` istemcisi hâlâ taslak yolları ve DTO’ları kullanıyor. [Order API sözleşmesi](INTEGRATION_CONTRACTS.md)

## 1. Masa ve sipariş ekranlarını Order okumalarına bağla

| Waiter işlemi | Kullanılacak Order ucu | İstemci değişikliği |
| --- | --- | --- |
| Masaların aktif sipariş sayısı | `POST /api/v1/orders/session-summaries` | Yalnız `tableSessionIds` gönder. Yanıttaki `items[].activeOrderCount` değerlerini `tableSessionId` ile eşle. |
| Sipariş listesi, hazır kuyruk ve geçmiş | `POST /api/v1/orders/search` | `tableId`, `tableSessionIds` ve ekranın filtrelerini gönder. Sayfa boyutunu `limit` alanına taşı. |
| Tek oturumun siparişleri | `GET /api/v1/orders?tableSessionId={id}` | Tek oturum filtresi yeterliyse mevcut liste ucunu kullan. |
| Sipariş detayı ve güncel sürüm | `GET /api/v1/orders/{orderId}` | Taslak `/internal/orders/{orderId}` yolunu değiştir. Servis komutu için detay yanıtındaki `version` değerini al. |
| İptal kayıtları | `GET /api/v1/orders/{orderId}/cancellations` | Ekran gerektirdiğinde ayrı oku ve sipariş kimliğiyle eşle. |

- `activeOrderCounts`, `search` ve `findOrder` çağrılarında `restaurantId` gönderimini kaldır. Order restoranı doğrulanmış personel kimliğinden alır. `restaurantId` POST gövdesinde bilinmeyen alan olarak `400` döner.
- Masa listesinde QR’dan alınan canlı **oturum** kimliklerini tekilleştir ve en çok 500 kimliği bir istekte gönder. Oturum listesi boşsa Order çağrısı yapma. Yanıtta yer almayan oturumu **0** olarak göster. Sayım `RECEIVED`, `PREPARING`, `READY` ve `SERVED` durumlarını kapsar. Ödeme durumu sayımı etkilemez.
- Canlı masaya ait siparişleri göstermek için geçerli `tableSessionId` değerini kullan. `tableId` filtresi aynı masanın farklı oturumlarını da kapsayabilir. Arama gövdesine her iki alan girilirse Order ikisini birlikte uygular.
- Waiter görünümlerini şimdi kullanılan Order alanlarına eşle. `ACTIVE` için `view: "active"`, `CANCELLED` için `view: "cancelled"`, `AWAITING_DELIVERY` için `fulfillmentStatuses: ["READY"]`, `READY_HISTORY` için `fulfillmentStatuses: ["READY", "SERVED", "COMPLETED"]` gönder. `view` ile `fulfillmentStatuses` aynı istekte `422` döner.
- `OrderPageDto` eşlemesinde `size` yerine `limit`, `totalElements` yerine `totalItems` oku. `page` 0 tabanlı, `limit` 1–100 aralığındadır. Açık durum listesiyle aramada `view` değeri `null` olabilir. Filtreyi sayfalamadan önce Order uygular. Waiter gelen sayfayı tekrar filtreleyerek toplamı türetmemeli.
- Liste ve detay için Order’ın DTO’larına uygun ayrı eşlemeler kullan. `orderId`, `orderNo`, `fulfillmentStatus` ve `paymentStatus` alanlarını oku. Kalemler ve `version` detay yanıtından gelir. Tutarlar para biriminin minor birimindedir. Taslak `businessStatus`, `preparation` ve `deliveryStatus` alanlarına bağımlı kontrolleri güncelle. Yanıtın sipariş, masa ve oturum kimliklerini istekle karşılaştır.

### Okuma isteklerinde personel kimliği

Yerel Order `dev/test` testlerinde mevcut `Authorization: Staff <test kimliği>` sözleşmesi kullanılır. Gerçek Auth entegrasyonunda Order PR #33’ün `restofy_access` personel oturumu sözleşmesini kullan.

Order tarafıyla birleşme sonrası `POST /api/v1/orders/search` ve `POST /api/v1/orders/session-summaries` yollarının personel çerezi eşlemesine alındığını doğrula. İki yol POST olduğu için `order-auth` profilinde geçerli CSRF ve Origin davranışını da birlikte test et. Bu iki uç için gerçek Auth ile çalışabilen taşıma sözleşmesi doğrulanana kadar yerel test başarısını uçtan uca doğrulama olarak kabul etme.

## 2. “Servis edildi” komutunu imzalı gönder

Waiter’ın `OrderServeService` akışında komutu Order’a çağrıdan önce kaydet. `commandId` UUID’sini bir kez üret. `tableSessionId` ve `expectedVersion` değerleri personelin gördüğü siparişe ait olmalı. Komut hazırlanırken `restaurantId` ve `requestedByUserId` değerlerini Waiter’da doğrulanmış aktif personel bağlamından al.

Örnek `POST /api/v1/internal/orders/{orderId}/serve` gövdesi

~~~json
{
  "commandId": "2f82e52b-5db9-4d2b-8eff-150b120a6731",
  "restaurantId": "d51abfce-3d70-472b-8f1c-acaa39f8581c",
  "tableSessionId": "0d2152e2-c917-415f-a41d-964846c95baf",
  "expectedVersion": 3,
  "requestedByUserId": "9337b3b0-52dd-48a2-93b2-179632885948",
  "requestedAt": "2026-11-23T11:20:00Z"
}
~~~

`Idempotency-Key` başlığı POST gövdesindeki `commandId` ile birebir aynı olmalı. Hem POST hem sonuç sorgusu, Gateway dışından doğrulanmış HTTPS Order adresine yapılır. Mevcut `ORDER_SERVICE_URL` HTTPS kabul ediyor. Gerçek entegrasyonda sertifika doğrulamasını koru ve bu çağrılarda HTTP’ye düşme.

### İmza ve personel delegasyonu

1. Waiter tarafında kullanılacak imzalama anahtarını ve `kid` değerini güvenli yapılandırmadan al. Açık anahtarı ve yetkili restoranları Order tarafıyla paylaş. Order bunları `ORDER_WAITER_SERVICE_PUBLIC_KEYS` ve `ORDER_WAITER_SERVICE_RESTAURANTS` üzerinden alır. Waiter anahtar ayarları için isim ve dağıtım biçimini ayrı koordine et.
2. `Authorization: Service <JWT>` başlığını RS256 ile imzala. İmza claim’leri `iss=restofy-waiter`, `sub=waiter-service`, `aud=restofy-order`, `token_type=service`, `scope=order:serve`, UUID `jti` ve `iat = nbf < exp` değerlerini içerir. İmzanın geçerlilik süresi en fazla 30 saniyedir.
3. Aktif personelin Auth erişim JWT’sini yalnızca istek süresince `X-Restofy-Staff-Delegation` başlığıyla ilet. Order oturumu her POST ve GET isteğinde yeniden doğrular. Bu iç çağrılarda tarayıcı `Cookie` ve `Origin` başlıkları reddedilir. Personel JWT’sini komut tablosuna, cache’e veya loglara yazma.
4. İmzaya doğrulanmış restoranın `restaurant_id` değerini, HTTP `method` değerini ve gerçekten gönderilen ham yol ile sorgu dizesini `target` olarak bağla. `body_sha256` için gönderilecek gövde baytlarının, `staff_sha256` için delegasyon başlığının SHA-256 özetini küçük harfli hex biçiminde kullan. POST gövdesini bir kez serileştir ve imzalanan baytları gönder. GET’in gövdesi boştur.

## 3. Makbuzu işle ve belirsiz sonucu sorgula

- `ServeResponseDto` ve `ServeStatusDto` içindeki tam `OrderDto` beklentisini kaldır. POST `200 APPLIED` veya `200 DUPLICATE` ile `commandId`, `orderId`, `tableSessionId`, `orderVersion`, `servedAt` ve `outcome` döndürür. Her iki değer de komutun başarıyla uygulandığını gösterir. Sonuçtaki kimlikleri kayıtlı komutla karşılaştır. Güncel sipariş detayı gerekiyorsa ayrı GET çağrısı yap.
- Order’ın iş kuralı için döndürdüğü `409` kodunu kullan. Uygulanan kodlar `ORDER_SESSION_MISMATCH`, `ORDER_VERSION_CONFLICT`, `ORDER_NOT_READY`, `ORDER_CANCELLED`, `ORDER_ALREADY_SERVED` ve `ORDER_COMMAND_CONFLICT`. Sürüm çakışmasında sipariş detayını yenile. Aynı komutun gövdesindeki `expectedVersion` dahil hiçbir alanı değiştirme.
- POST yanıtı kaybolursa, zaman aşımı olursa veya yanıt güvenilir biçimde okunamazsa yerel komutu belirsiz durumda tut. `GET /api/v1/internal/orders/{orderId}/serve-commands/{commandId}?restaurantId={id}` ile sorgula. Bu çağrı da geçerli servis imzası ve aktif personel delegasyonu ister.
- GET `status: "APPLIED"` dönerse komutu onayla. `status: "REJECTED"` dönerse `rejectionCode` ile kaydet. Her iki yanıtta `orderVersion` ve `decidedAt` ilk karara aittir. `APPLIED` ayrıca `servedAt` taşır. Sonuç sorgusu `404` dönerse durum belirsiz kalır.
- Yetkili bir personel isteğiyle tekrar gönderim gerekirse aynı `commandId`, aynı `Idempotency-Key` ve ilk gövdeyi kullan. Yeni servis imzası ve güncel Auth delegasyonu üretilebilir. POST tekrarı için delegasyon, ilk `requestedByUserId` ile eşleşmeli. `401/403` veya `503` alan sonuç sorgusunu kesin ret olarak kaydetme.

## 4. Waiter testleri ve uçtan uca kontrol

- [ ] Masa listesi boş oturum listesini çağrısız işler. Aktif siparişi olmayan oturum 0 görünür. Başka restorana ait sipariş ve oturum verileri ekrana gelmez.
- [ ] Hazır kuyruk ve geçmiş doğru Order filtreleriyle çağrılır. İkinci sayfada da `page`, `limit` ve filtreli `totalItems` doğru eşlenir. İptal kayıtları ayrı uçtan okunur.
- [ ] Testler yeni POST/GET yollarını, imzalanan baytların iletilen gövdeyle aynı olduğunu ve delegasyon başlığının imzaya bağlı olduğunu kontrol eder.
- [ ] Gerçek personel oturumuyla bir READY sipariş SERVED olur. Aynı komut tekrarlandığında ilk sürüm ve servis zamanı döner. Eski sürüm, yanlış oturum ve geçersiz imza reddedilir.
- [ ] Order komutu uyguladıktan sonra POST yanıtı kaybolduğunda Waiter GET ile ilk kararı bulur. Kayıt görünmüyorsa belirsiz durumu korur.

Yerel Order testleri imzalı isteği, kalıcı komut sonucunu ve eşzamanlılık davranışını doğruluyor. Gerçek uçtan uca kontrol için Order PR #33 Auth adaptörü, Waiter istemci uyarlaması, V132 ve HTTPS servis bağlantıları birlikte hazır olmalı. [Order entegrasyon sözleşmesi](INTEGRATION_CONTRACTS.md)
