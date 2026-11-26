# Kitchen ↔ Order entegrasyon rehberi

Kitchen hazırlık durumunun, Order sipariş ve kesin iptal kararının sahibidir. Bu rehber iki servisin mevcut iş kurallarını ve Kitchen tarafında tamamlanacak bağlantıları açıklar. [Order entegrasyon sözleşmesi](INTEGRATION_CONTRACTS.md)

**Devreye alma bekliyor.** Kitchen PR #77 yalnız sipariş kabulünü işler. Kabul için gerçek `tableNumber` ister ve dört iptal olayına `422 UNSUPPORTED_EVENT_TYPE` döner. Ortak ortamda Kitchen'ın HTTPS dinleyicisi de yok. Kabul, iptal ve iki yönlü HTTPS doğrulanana kadar `order-kitchen` profilini açmayın. [Kitchen PR #77](https://github.com/software-lecture-labs/Restofy/pull/77)

## Kitchen tarafında tamamlanacak işler

1. **Sipariş kabulünü gerçek Order ile doğrula.** Önce Order'ın nullable `tableNumber` desteği, sonra QR PR #78 birleşir. QR'dan gelen gerçek numaralı bir siparişin Kitchen kuyruğunda tek kayıt oluşturduğunu doğrula. Aynı olayın tekrarında ikinci sipariş oluşmamalı ve masa numarası değişmemeli.
2. **Dört Order iptal olayını işle.** `OrderCancellationRequested` için mutfak onay akışını aç ve `cancellationId` ile kararı Order’a ilet. `OrderCancelled` geldiğinde yerel Kitchen talebi bulunmasa bile kesinleşmiş tam iptali işle. `OrderItemsCancelled` için kalan adetlerin ekrana etkisini D-02 kapsamında kararlaştır. `OrderCancellationRejected` ilgili açık talebi ret sonucuyla kapatmalı. Olayı aynı restoran, sipariş ve `eventId` kapsamında tekilleştir. Kalıcı geçiş veya doğrulanmış tekrar `204` döner. Henüz işlenemeyen olay `422` kalır.
3. **Kitchen → Order göndericisini bağla.** Outbox'a gerçek HTTPS istemcisi, zamanlayıcı ve RS256 imzalayıcı ekle. `KitchenPreparationStarted` ve `KitchenOrderReady` olaylarını `kitchenStatus: PREPARING` ve `READY` olarak sırayla gönder. Kitchen’ın kendi iptal talebinde `cancelAll: true` kullan, `OUT_OF_STOCK` kodunu `ITEM_UNAVAILABLE` olarak eşle ve `OTHER` açıklamasını ilet. Tam karar makbuzunu yerelde uyguladıktan sonra teslimi onayla. Eksik makbuzu uzlaştırmaya bırak.
4. **Ortak ortamı ve hata senaryolarını doğrula.** İki yönün açık anahtar ve `kid` değerlerini karşılıklı tanımla. Sertifika ve hostname doğrulamasıyla gerçek servisleri bağla. Tam iptal, mutfak onayı, ret, kaybolan yanıt ve aynı olayın yeniden gönderimini birlikte dene. Önceden başarısız bir Order teslimini Kitchen destek ekledikten sonra Order operatörü yeniden sıraya alır.

## Kitchen’ın mevcut yüzeyi

- Mutfak kuyruğu ve sipariş detayı `GET /api/v1/kitchen/orders` ve `GET /api/v1/kitchen/orders/{orderId}` ile açılır. Kuyruk FIFO sıralıdır ve `WAITING`, `PREPARING`, `READY`, `CANCELLED`, `ACTIVE` filtrelerini destekler. Sipariş kimliği Order’a aittir.
- `PATCH /api/v1/kitchen/orders/{orderId}/start-preparation` ve `PATCH /api/v1/kitchen/orders/{orderId}/mark-ready` idempotency anahtarıyla sipariş bazlı hazırlığı yürütür. Geçişler `WAITING → PREPARING → READY` şeklindedir. Garson teslimi Kitchen’daki `READY` durumunu değiştirmez.
- `POST` ve `GET /api/v1/kitchen/orders/{orderId}/cancellation-requests` Kitchen’ın kendi iptal taleplerini açar ve listeler. Kitchen kesin iptali yalnız Order kararından sonra işler.
- `PATCH /api/v1/kitchen/products/{productId}/availability` ve `GET /api/v1/kitchen/products/closed` Menu portlarını kullanır. Mevcut varsayılan Menu adaptörü `MENU_NOT_INTEGRATED` döndürür.
- `GET` ve `POST /api/v1/kitchen/waste` fire kayıtlarını yönetir. `GET /api/v1/kitchen/reports/daily` günlük sipariş ve fire özetini verir. Bu uçlar Order’a çağrı yapmaz.
- Kitchen personel uçları Gateway üzerinden erişilecek şekilde tanımlı. Okuma için `KITCHEN` ve `ADMIN`, yazma için `KITCHEN` gerekir. Mevcut üretim zinciri bu principal’ı oluşturan Auth adaptörünü içermiyor. Sağlık ucu bağımsız erişilebilir.

Kitchen’ın V81–V89 migration’ları sipariş ve kalem snapshot’ı, durum geçmişi, outbox ve inbox, iptal talepleri, fire ve erişilebilirlik komutlarını kendi veritabanında tutar. Sipariş ve oturum kimlikleri servisler arasında mantıksal referanstır.

## 1. Order’dan sipariş kabul et

- Kitchen PR #77, `POST /api/v1/internal/kitchen/order-events` alıcısını ve katı JSON doğrulamasını ekledi. İstek doğrulanmış Order kimliğiyle yalnız aynı restoran kapsamında kabul edilir. Yol Gateway dışında kalır.
- Kitchen iş kaydını ve ilk durum geçmişini commit ettikten sonra `204` döndürmeli. Aynı `eventId` tekrar geldiğinde ikinci sipariş oluşmamalı. Bilinen aynı olayın tekrarını doğrulayıp `204` döndür. Daha yeni sürüm uygulanamadığında veya aynı kimlik farklı içerikle geldiğinde başarılı teslim onayı verme.
- Kitchen PR #77, `eventType: "OrderAccepted"`, `tableId`, `tableSessionId` ve boş olmayan, en çok 40 karakterlik gerçek `tableNumber` bekliyor. QR PR #78, Order'ın imzalı misafir doğrulama yanıtına masa numarasını ekler. Order, checkout sırasında QR'dan aldığı değeri `OrderPlaced` outbox kaydında saklar ve `OrderAccepted` mesajına aktarır. Tekrar teslim ve checkout tekrarı ilk numarayı korur.
- **Merge sırası.** Önce Order'ın `tableNumber` alanını kabul eden değişikliği, ardından QR PR #78. Geçişte eksik veya `null` alan eski QR yanıtı olarak kabul edilir. Büyük, boş veya metin olmayan bir değer QR sözleşme hatasıdır. Masa numarası olmadan kaydedilmiş olaylarda alan üretilmez. Bu olayların sipariş anına ait numarası doğrulanamıyorsa açılıştan önce nasıl uzlaştırılacakları belirlenmeli. [QR PR #78](https://github.com/software-lecture-labs/Restofy/pull/78)
- Sipariş mesajında fiyat, vergi, ödeme durumu ve misafir oturum kimliği Kitchen’a gitmemeli. Sipariş numarası, kalem adı, miktarı, kalem notu ve eklenen/çıkarılan seçenekler kabul anındaki Order snapshot’ından gelmeli.
- Kabul gövdesi `eventId`, `eventType`, `schemaVersion`, `occurredAt`, `correlationId`, `restaurantId`, `orderId`, `aggregateVersion`, `orderNumber`, `tableId`, `tableSessionId`, `tableNumber` ve `items` taşır. Seçenekler `action`, `optionId`, `name` ve `quantity` alanlarıyla gelir. Kitchen’ın seçenek üst sınırını Order’ın ekleme ve çıkarma listelerinin birleşik boyutuyla karşılaştırın. Alıcı limiti nedeniyle bir sipariş reddediliyorsa seçenekler sessizce kesilmemeli.
- Kitchen sipariş başına en çok 200 satır, kalem başına en çok 999 adet ve seçenek başına en çok 99 adet kabul eder. Order'ın satır ve kalem adedi varsayılanları 50'dir, fakat yapılandırmayla alıcı sınırları aşılabilir. Ortak ortamda satır ve kalem sınırlarını yükseltmeden önce bu değerleri, katalogdan gelebilen seçenek adetlerini ve azami olay boyutunu birlikte doğrulayın.

## 2. Hazırlık olaylarını Order’a gönder

- Üretim `OrderEventSink` adaptörü ve `OutboxDeliveryService.deliverDue` çalıştırıcısı eklenmeli. `KitchenPreparationStarted` ve `KitchenOrderReady` mevcut Kitchen outbox’ında kalıcı olarak oluşuyor. Order’a mevcut korumalı `POST /api/v1/internal/order-events/kitchen` yolu üzerinden iletilebilir.
- Kitchen zarfını Order DTO’suna eşle. `eventId`, `restaurantId`, `orderId` ve `occurredAt` korunur. `payload.preparationStatus` Order isteğinde üst seviye `kitchenStatus` alanına taşınır. Hazırlık olayları için sırasıyla `PREPARING` ve `READY` gönder.
- Aynı siparişte önce `PREPARING` teslim edilmeli. Önce gelen `READY` Order’da `409 INVALID_STATUS_TRANSITION` üretir. Eksik hazırlık olayı teslim edilene kadar READY’ye başarı onayı verme. Tekrar denemelerinde ilk `eventId` ve olay içeriğini koru.
- İletim sırasında Kitchen DB satır kilidi açık tutulmamalı. Zaman aşımı veya belirsiz yanıt, hazırlık durumunu geri almamalı. Aynı siparişin güncel durumu `READY` olsa da önceki teslimatın sonucu ayrıca takip edilmeli.

## 3. İptal talebi ile iptal kararını ayır

**Kitchen’ın kendi talebi.** `KitchenCancellationRequested` olayını `POST /api/v1/internal/order-events/kitchen-cancellation-request` isteğine eşle. Outbox `eventId` değerini sabit tut. Kitchen V1 talebi bütün siparişi kapsar, bu yüzden `cancelAll: true` gönder. `OUT_OF_STOCK` kodunu `ITEM_UNAVAILABLE` olarak eşle. `OTHER` için Order açıklama bekler. Mevcut Kitchen olay gövdesi açıklama taşımadığı için bu senaryoda gerekli açıklama sözleşmesi tamamlanmalı.

- Order’ın bu POST yanıtındaki `APPLIED` veya `REJECTED` değeri iş kararıdır. Yalnız HTTP 200 görmek, siparişin iptal olduğu anlamına gelmez.
- İsteğin yerel `requestId` değeri Kitchen’a aittir. Order kararındaki `cancellationId` ayrı bir kimliktir. Yanıtı ilk Kitchen komutuyla eşleştirip `OrderDecisionService` akışına doğrulanmış Order kararı olarak aktar.
- `orderVersion` ve `decidedAt` Order’ın ilk kararına aittir. Yeni Kitchen taleplerinin karar eşlemesi Order inbox saklama süresiyle aynı süre için yazılır (varsayılan 90 gün). Daha önceki 72 saatlik kuralla yazılmış ve eşlemesi artık bulunamayan kayıtlarda makbuz eksik kalabilir. Böyle bir yanıtta yeni zaman veya sürüm üretme ve talebi uzlaştırma için açık tut.

**Order’dan gelen talep.** Order, `PREPARING` bir sipariş için kendi iptal talebinde Kitchen onayı bekler. Kitchen’ın mevcut karar uygulama servisi kendi yerel talebini arar. Order kaynaklı onay isteği için alıcı ve ayrı iş akışı gerekir. Talep değerlendirildikten sonra karar `POST /api/v1/internal/order-events/kitchen-cancellation` ile Order’a iletilir. İstekte Order’ın `cancellationId` alanı kullanılır. Kitchen siparişini sonlandırmadan önce Order’ın kesin kararını işle.

**Kesinleşmiş iptal.** Order kaynaklı tam iptal, yerel Kitchen talebi olmadan da işlenebilmelidir. `OrderCancelled` mesajı `"reasons": [{"code":"CHANGED_MIND"}]` biçiminde katalog kodlarını taşır. Kod yoksa `"reasons": []` gelir. Sonradan eklenen neden ayrı bir olay üretmez. Kalem bazlı iptalin Kitchen ekranına ve `READY` durumuna etkisi D-02 kapsamında ekip kararı gerektirir.

Order aynı alıcıya `OrderCancellationRequested`, `OrderCancelled`, `OrderItemsCancelled` ve `OrderCancellationRejected` gönderir. Gövdede olay kimliği ve restoran/sipariş kapsamıyla birlikte `orderVersion`, Order’a ait `cancellationId`, `scope`, `kitchenConfirmationRequired` ve `items` bulunur. Kalemlerde `orderItemId`, `quantity`, `remainingQuantity` yer alır. Kesinleşmiş kararda `decidedAt`, ret varsa `rejectionCode` alanı bulunur. Kitchen kesin uygulamayı commit ettikten sonra `204` verir. D-02 kararı gerektiren bir sonuç güvenle uygulanamıyorsa yanlış başarı onayı yerine görünür uzlaştırma gerekir.

## 4. İmza, teslim ve devreye alma

- Her iki yön için `Authorization: Service <JWT>` kullan. JWT, RS256 ve `kid` ile doğrulanır. `token_type=service`, UUID `jti`, `iat = nbf < exp` ve en fazla 30 saniyelik süre kullanılır. `restaurant_id`, `method`, `target` ve `body_sha256` imzaya bağlıdır. `target` gönderilen ham yol, `body_sha256` gönderilen gövde baytlarının küçük harfli hex SHA-256 özetidir.
- Kitchen → Order için `iss=restofy-kitchen`, `sub=kitchen-service`, `aud=restofy-order`, `scope=order:kitchen-events` kullan. İmza yalnız rehberdeki üç Kitchen POST yolunda kabul edilir.
- Order → Kitchen için `iss=restofy-order`, `sub=order-service`, `aud=restofy-kitchen`, `scope=kitchen:order-events` ve `target=/api/v1/internal/kitchen/order-events` doğrula. Order’dan gelen gövde restoranıyla imzalı kapsamı karşılaştır.
- Kitchen imzalama anahtarının açık karşılığını Order için `ORDER_KITCHEN_SERVICE_PUBLIC_KEYS` haritasına sağla. Bu ayar `kid → Base64 X.509 RSA açık anahtar` JSON eşlemesidir. Order kendi çıkış imzası için `ORDER_KITCHEN_CALLER_KEY_ID` ve Base64 PKCS#8 biçiminde `ORDER_KITCHEN_CALLER_PRIVATE_KEY` kullanır. Açık karşılığını Kitchen alıcısında yetkilendir. Özel anahtarlar sahibi olan serviste kalır. Order çıkış kapsamı ve Kitchen giriş kapsamı `ORDER_KITCHEN_SERVICE_RESTAURANTS` ile sınırlandırılır.
- Order’da `order-kitchen` profilini ve HTTPS `KITCHEN_SERVICE_URL` değerini, Kitchen tarafında HTTPS `ORDER_SERVICE_URL` değerini kullan. Sertifika ve hostname doğrulamasını koru. Bu iç çağrılar için tarayıcı çerezi veya Origin gönderme. Her denemede imza yenilenebilir. Olay kimliği ve imzalanan iş gövdesi korunur.
- Order’da bir siparişin önceki Kitchen teslimi başarıyla onaylanmadan sonraki teslimi gönderilmez. Geçici hatalar sınırlı aralıklarla tekrar denenir. Kalıcı hata veya tükendiğinde belirsiz kalan teslim `FAILED` olarak saklanır ve aynı siparişin sıradaki teslimini bekletir. Başka siparişler devam eder. Alıcı ve kimlik ayarları hazır olduktan sonra bu profili açın.
- Order’ın imzalı HTTP gövde sınırı 256 KiB’dir. Kitchen alıcısının JSON alan ve boyut sınırlarını Order’ın üretebildiği örneklerle test et. Hata gövdesine veya loglara sipariş içeriğini, anahtarı veya tokenı yazma.

### Bekleyen teslimlerin uzlaştırılması

- Order sorumlusu, başarısız teslimi logdaki restoran, sipariş ve olay kimliğiyle takip eder. Order veritabanındaki `outbox_deliveries` ve `outbox_events` kayıtlarını olay kimliği üzerinden eşleyip ilgili restoran ve siparişin yalnız `KITCHEN` teslimlerini inceler. `FAILED` olan öncülün hata kodu ve ardından bekleyen `PENDING` olaylar burada görünür.
- `422` için Kitchen sorumlusu ile olay türünün desteğini doğrula. `204` yalnız uygulama veya doğrulanmış tekrar sonucunda başarı kabul edilir. Önceki olayı atlamak, olay gövdesini değiştirmek veya sonraki tam iptali sıranın dışına çıkarmak yerine teslimleri incelemede tut.
- Hata giderildiğinde mevcut veritabanı yetkisine sahip Order operatörü, aşağıdaki komutla yalnız belirtilen restoranın `FAILED` durumundaki Kitchen teslimini tekrar sıraya alabilir. Olay kimliği ve kayıtlı gövde korunur. Teslim sıradaki normal gönderim döngüsüne girer. Komutun döndürdüğü operatör, zaman ve önceki hata bilgilerini ilgili takip kaydına ekleyin. Satır dönmezse verilen kimliklerle yeniden sıraya alınabilecek bir teslim bulunmamıştır.

```text
psql -X -v ON_ERROR_STOP=1 -d <ORDER_DATABASE> -v restaurant_id=<RESTAURANT_UUID> -v event_id=<EVENT_UUID> -f backend/services/order-service/ops/requeue-kitchen-delivery.sql
```

Yetkili Order veritabanı erişimiyle şu salt okunur sorgu siparişin teslim sırasını ve hata kodunu gösterir. İki UUID'yi restoran ve sipariş kayıtlarından alın.

```sql
SELECT e.id AS event_id, e.event_type, e.aggregate_version, e.occurred_at,
       d.status, d.attempt_count, d.next_attempt_at, d.last_error_code
FROM outbox_events e
JOIN outbox_deliveries d ON d.event_id = e.id
WHERE e.restaurant_id = :restaurant_id
  AND e.aggregate_type = 'ORDER' AND e.aggregate_id = :order_id
  AND d.destination = 'KITCHEN'
ORDER BY e.aggregate_version, e.occurred_at,
         CASE e.event_type WHEN 'OrderPlaced' THEN 0 WHEN 'OrderCancellationRequested' THEN 1 ELSE 2 END,
         e.id;
```

## 5. Entegrasyon kabulü

- [ ] Order’da oluşturulan sipariş Kitchen kuyruğunda tek bir `WAITING` kaydı oluşturur. Aynı olay tekrar gelirse ikinci kayıt oluşmaz.
- [ ] Kitchen önce hazırlığı başlatır, sonra hazır yapar. Order’da `PREPARING` ve `READY` sırasıyla görülür. Order’daki servis teslimi Kitchen üzerindeki `READY` durumunu korur.
- [ ] Kitchen kaynaklı ve Order kaynaklı iptaller ayrı senaryolarda test edilir. Ret, yanıt kaybı ve tekrarlı teslim sırasında kayıtlar tutarlı kalır.
- [ ] Yetkisiz servis ve başka restoran kapsamıyla gelen istek işlenmez. Gerçek servis kimliği ve gerçek HTTP adaptörleriyle iki yönlü doğrulama yapılır.
