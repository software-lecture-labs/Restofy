# Kitchen ↔ Order entegrasyon rehberi

Kitchen hazırlık durumunun, Order sipariş ve kesin iptal kararının sahibidir. Bu rehber iki servisin mevcut iş kurallarını ve Kitchen tarafında tamamlanacak bağlantıları açıklar. [Order entegrasyon sözleşmesi](INTEGRATION_CONTRACTS.md)

## Kitchen’ın mevcut yüzeyi

- Mutfak kuyruğu ve sipariş detayı `GET /api/v1/kitchen/orders` ve `GET /api/v1/kitchen/orders/{orderId}` ile açılır. Kuyruk FIFO sıralıdır ve `WAITING`, `PREPARING`, `READY`, `CANCELLED`, `ACTIVE` filtrelerini destekler. Sipariş kimliği Order’a aittir.
- `PATCH /api/v1/kitchen/orders/{orderId}/start-preparation` ve `PATCH /api/v1/kitchen/orders/{orderId}/mark-ready` idempotency anahtarıyla sipariş bazlı hazırlığı yürütür. Geçişler `WAITING → PREPARING → READY` şeklindedir. Garson teslimi Kitchen’daki `READY` durumunu değiştirmez.
- `POST` ve `GET /api/v1/kitchen/orders/{orderId}/cancellation-requests` Kitchen’ın kendi iptal taleplerini açar ve listeler. Kitchen kesin iptali yalnız Order kararından sonra işler.
- `PATCH /api/v1/kitchen/products/{productId}/availability` ve `GET /api/v1/kitchen/products/closed` Menu portlarını kullanır. Mevcut varsayılan Menu adaptörü `MENU_NOT_INTEGRATED` döndürür.
- `GET` ve `POST /api/v1/kitchen/waste` fire kayıtlarını yönetir. `GET /api/v1/kitchen/reports/daily` günlük sipariş ve fire özetini verir. Bu uçlar Order’a çağrı yapmaz.
- Kitchen personel uçları Gateway üzerinden erişilecek şekilde tanımlı. Okuma için `KITCHEN` ve `ADMIN`, yazma için `KITCHEN` gerekir. Mevcut üretim zinciri bu principal’ı oluşturan Auth adaptörünü içermiyor. Sağlık ucu bağımsız erişilebilir.

Kitchen’ın V81–V89 migration’ları sipariş ve kalem snapshot’ı, durum geçmişi, outbox ve inbox, iptal talepleri, fire ve erişilebilirlik komutlarını kendi veritabanında tutar. Sipariş ve oturum kimlikleri servisler arasında mantıksal referanstır.

## 1. Order’dan sipariş kabul et

- Kitchen’da `AcceptedOrderIngestService` ve katı JSON ayrıştırıcısı `OrderAcceptedMessageReader` mevcut. `POST /api/v1/internal/kitchen/order-events` alıcısını ekle. İsteği doğrulanmış Order kimliğiyle ve yalnız aynı restoran kapsamında kabul et. Yol Gateway dışında kalmalı.
- Kitchen iş kaydını ve ilk durum geçmişini commit ettikten sonra `204` döndürmeli. Aynı `eventId` tekrar geldiğinde ikinci sipariş oluşmamalı. Bilinen aynı olayın tekrarını doğrulayıp `204` döndür. Daha yeni sürüm uygulanamadığında veya aynı kimlik farklı içerikle geldiğinde başarılı teslim onayı verme.
- `OrderAcceptedMessage` ve katı ayrıştırıcıyı `eventType: "OrderAccepted"`, zorunlu `tableId` ve isteğe bağlı `tableNumber` alanlarıyla hizala. Order, `tableId` ve `tableSessionId` gönderiyor. Bu dalın QR guest doğrulama yanıtı masa numarası içermediğinden Order `tableNumber` alanını göndermez. Kitchen’daki masa snapshot saklama ve gösterim kurallarını buna göre uyarlayın. Gerçek masa numarası gerektiğinde QR’ın yetkili verisini kullanın.
- Sipariş mesajında fiyat, vergi, ödeme durumu ve misafir oturum kimliği Kitchen’a gitmemeli. Sipariş numarası, kalem adı, miktarı, kalem notu ve eklenen/çıkarılan seçenekler kabul anındaki Order snapshot’ından gelmeli.
- Kabul gövdesi `eventId`, `eventType`, `schemaVersion`, `occurredAt`, `correlationId`, `restaurantId`, `orderId`, `aggregateVersion`, `orderNumber`, `tableId`, `tableSessionId` ve `items` taşır. Seçenekler `action`, `optionId`, `name` ve `quantity` alanlarıyla gelir. Kitchen’ın seçenek üst sınırını Order’ın ekleme ve çıkarma listelerinin birleşik boyutuyla karşılaştırın. Alıcı limiti nedeniyle bir sipariş reddediliyorsa seçenekler sessizce kesilmemeli.

## 2. Hazırlık olaylarını Order’a gönder

- Üretim `OrderEventSink` adaptörü ve `OutboxDeliveryService.deliverDue` çalıştırıcısı eklenmeli. `KitchenPreparationStarted` ve `KitchenOrderReady` mevcut Kitchen outbox’ında kalıcı olarak oluşuyor. Order’a mevcut korumalı `POST /api/v1/internal/order-events/kitchen` yolu üzerinden iletilebilir.
- Kitchen zarfını Order DTO’suna eşle. `eventId`, `restaurantId`, `orderId` ve `occurredAt` korunur. `payload.preparationStatus` Order isteğinde üst seviye `kitchenStatus` alanına taşınır. Hazırlık olayları için sırasıyla `PREPARING` ve `READY` gönder.
- Aynı siparişte önce `PREPARING` teslim edilmeli. Önce gelen `READY` Order’da `409 INVALID_STATUS_TRANSITION` üretir. Eksik hazırlık olayı teslim edilene kadar READY’ye başarı onayı verme. Tekrar denemelerinde ilk `eventId` ve olay içeriğini koru.
- İletim sırasında Kitchen DB satır kilidi açık tutulmamalı. Zaman aşımı veya belirsiz yanıt, hazırlık durumunu geri almamalı. Aynı siparişin güncel durumu `READY` olsa da önceki teslimatın sonucu ayrıca takip edilmeli.

## 3. İptal talebi ile iptal kararını ayır

**Kitchen’ın kendi talebi.** `KitchenCancellationRequested` olayını `POST /api/v1/internal/order-events/kitchen-cancellation-request` isteğine eşle. Outbox `eventId` değerini sabit tut. Kitchen V1 talebi bütün siparişi kapsar, bu yüzden `cancelAll: true` gönder. `OUT_OF_STOCK` kodunu `ITEM_UNAVAILABLE` olarak eşle. `OTHER` için Order açıklama bekler. Mevcut Kitchen olay gövdesi açıklama taşımadığı için bu senaryoda gerekli açıklama sözleşmesi tamamlanmalı.

- Order’ın bu POST yanıtındaki `APPLIED` veya `REJECTED` değeri iş kararıdır. Yalnız HTTP 200 görmek, siparişin iptal olduğu anlamına gelmez.
- İsteğin yerel `requestId` değeri Kitchen’a aittir. Order kararındaki `cancellationId` ayrı bir kimliktir. Yanıtı ilk Kitchen komutuyla eşleştirip `OrderDecisionService` akışına doğrulanmış Order kararı olarak aktar.
- `orderVersion` ve `decidedAt` Order’ın ilk kararına aittir. Bunlar eski bir tekrar yanıtında boşsa yeni zaman veya sürüm üretme. Sonucu uzlaştırma için görünür bırak.

**Order’dan gelen talep.** Order, `PREPARING` bir sipariş için kendi iptal talebinde Kitchen onayı bekler. Kitchen’ın mevcut karar uygulama servisi kendi yerel talebini arar. Order kaynaklı onay isteği için alıcı ve ayrı iş akışı gerekir. Talep değerlendirildikten sonra karar `POST /api/v1/internal/order-events/kitchen-cancellation` ile Order’a iletilir. İstekte Order’ın `cancellationId` alanı kullanılır. Kitchen siparişini sonlandırmadan önce Order’ın kesin kararını işle.

**Kesinleşmiş iptal.** Order’dan gelen tam iptal olayını Kitchen’ın kendi talep sonucu ile karıştırma. Order kaynaklı tam iptal, yerel Kitchen talebi olmadan da işlenebilmelidir. Kalem bazlı iptalin Kitchen ekranına yansıması D-02 kapsamında ekip kararı gerektirir. Tek bir kalemin iptali mutfak siparişinin tamamını `CANCELLED` yapmamalıdır.

Order aynı alıcıya `OrderCancellationRequested`, `OrderCancelled`, `OrderItemsCancelled` ve `OrderCancellationRejected` gönderir. Gövdede olay kimliği ve restoran/sipariş kapsamıyla birlikte `orderVersion`, Order’a ait `cancellationId`, `scope`, `kitchenConfirmationRequired` ve `items` bulunur. Kalemlerde `orderItemId`, `quantity`, `remainingQuantity` yer alır. Kesinleşmiş kararda `decidedAt`, ret varsa `rejectionCode` alanı bulunur. Kitchen kesin uygulamayı commit ettikten sonra `204` verir. D-02 kararı gerektiren bir sonuç güvenle uygulanamıyorsa yanlış başarı onayı yerine görünür uzlaştırma gerekir.

## 4. İmza, teslim ve devreye alma

- Her iki yön için `Authorization: Service <JWT>` kullan. JWT, RS256 ve `kid` ile doğrulanır. `token_type=service`, UUID `jti`, `iat = nbf < exp` ve en fazla 30 saniyelik süre kullanılır. `restaurant_id`, `method`, `target` ve `body_sha256` imzaya bağlıdır. `target` gönderilen ham yol, `body_sha256` gönderilen gövde baytlarının küçük harfli hex SHA-256 özetidir.
- Kitchen → Order için `iss=restofy-kitchen`, `sub=kitchen-service`, `aud=restofy-order`, `scope=order:kitchen-events` kullan. İmza yalnız rehberdeki üç Kitchen POST yolunda kabul edilir.
- Order → Kitchen için `iss=restofy-order`, `sub=order-service`, `aud=restofy-kitchen`, `scope=kitchen:order-events` ve `target=/api/v1/internal/kitchen/order-events` doğrula. Order’dan gelen gövde restoranıyla imzalı kapsamı karşılaştır.
- Kitchen imzalama anahtarının açık karşılığını Order için `ORDER_KITCHEN_SERVICE_PUBLIC_KEYS` haritasına sağla. Bu ayar `kid → Base64 X.509 RSA açık anahtar` JSON eşlemesidir. Order kendi çıkış imzası için `ORDER_KITCHEN_CALLER_KEY_ID` ve Base64 PKCS#8 biçiminde `ORDER_KITCHEN_CALLER_PRIVATE_KEY` kullanır. Açık karşılığını Kitchen alıcısında yetkilendir. Özel anahtarlar sahibi olan serviste kalır. Order çıkış kapsamı ve Kitchen giriş kapsamı `ORDER_KITCHEN_SERVICE_RESTAURANTS` ile sınırlandırılır.
- Order’da `order-kitchen` profilini ve HTTPS `KITCHEN_SERVICE_URL` değerini, Kitchen tarafında HTTPS `ORDER_SERVICE_URL` değerini kullan. Sertifika ve hostname doğrulamasını koru. Bu iç çağrılar için tarayıcı çerezi veya Origin gönderme. Her denemede imza yenilenebilir. Olay kimliği ve imzalanan iş gövdesi korunur.
- Order’da bir siparişin önceki Kitchen teslimi başarıyla onaylanmadan sonraki teslimi gönderilmez. Geçici hatalar sınırlı aralıklarla tekrar denenir. Kalıcı hata veya tükendiğinde belirsiz kalan teslim `FAILED` olarak saklanır ve aynı siparişin sıradaki teslimini bekletir. Başka siparişler devam eder. Alıcı ve kimlik ayarları hazır olduktan sonra bu profili açın.
- Order’ın imzalı HTTP gövde sınırı 256 KiB’dir. Kitchen alıcısının JSON alan ve boyut sınırlarını Order’ın üretebildiği örneklerle test et. Hata gövdesine veya loglara sipariş içeriğini, anahtarı veya tokenı yazma.

## 5. Entegrasyon kabulü

- [ ] Order’da oluşturulan sipariş Kitchen kuyruğunda tek bir `WAITING` kaydı oluşturur. Aynı olay tekrar gelirse ikinci kayıt oluşmaz.
- [ ] Kitchen önce hazırlığı başlatır, sonra hazır yapar. Order’da `PREPARING` ve `READY` sırasıyla görülür. Order’daki servis teslimi Kitchen üzerindeki `READY` durumunu korur.
- [ ] Kitchen kaynaklı ve Order kaynaklı iptaller ayrı senaryolarda test edilir. Ret, yanıt kaybı ve tekrarlı teslim sırasında kayıtlar tutarlı kalır.
- [ ] Yetkisiz servis ve başka restoran kapsamıyla gelen istek işlenmez. Gerçek servis kimliği ve gerçek HTTP adaptörleriyle iki yönlü doğrulama yapılır.
