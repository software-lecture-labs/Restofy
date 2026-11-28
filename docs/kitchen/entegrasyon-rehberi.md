# Kitchen entegrasyon rehberi — Order

Order ekibi ve inceleyenler için. Kitchen ile Order arasındaki iki yönü anlatır: Order → Kitchen olayları (Kitchen PR #77'nin alıcısı) ve Kitchen → Order olayları (KITCHEN-INT-03, Kitchen PR #80). Bu belge, #77 ile #80'i birleştirip bağlayan `chore/kitchen-int-03-wiring` branch'inin durumudur. Order tarafı `dev`'deki kodla (`f8b2c17`: Order PR #47 ve QR PR #78 birleşmiş) karşılaştırıldı.

> **Entegrasyon ortak bir ortamda etkinleştirilmedi.** İki yön, gerçek Order ve Kitchen uygulamalarıyla geçici ve izole bir ortamda gerçek HTTPS üzerinden doğrulandı (§8); Menu, QR ve personel kimliği orada test karşılıklarıydı. Açmadan önce §9'daki koşullar ve Order tarafındaki açık konular tamamlanmalı.

## 1. Durum

| Akış | Kitchen'da | Doğrulama |
|---|---|---|
| Sipariş kabulü (`OrderAccepted`) | Uygulandı (#77) | Kitchen testleri; gerçek iki servis (§8) |
| `PREPARING` / `READY` gönderimi | Uygulandı; `READY`, `PREPARING` teslim edilmeden seçilmez | Kitchen testleri; gerçek iki servis |
| Kitchen'ın kendi iptal talebi | Uygulandı; çakışmada deneme harcamadan bekler (§6.6) | Kitchen testleri; gerçek iki servis |
| Order'ın onay isteği → mutfak cevabı → Order'ın sonucu | Uygulandı; cevap ve sonuç ayrı saklanır (§6.5) | Kitchen testleri; gerçek iki servis |
| Order'ın kesin tam iptali (`OrderCancelled`) | `WAITING`/`PREPARING` sipariş iptal edilir ve açık kayıtlar kapanır; `READY` için uygulanmaz (D-02) | Kitchen testleri; gerçek iki servis |
| Order'ın reddi (`OrderCancellationRejected`) | İlgili isteğe sonuç ve Order'ın kodu yazılır | Kitchen testleri; gerçek iki servis (ödeme durumu elle ayarlandı) |
| İptal ve "Hazır" yarışı | Karara bağlanmamış tam iptal varken `READY` engellenir (§6.7) | Kitchen testleri (eşzamanlı denemeler dahil); gerçek iki servis |
| Kısmi iptal (`OrderItemsCancelled`) | **Uygulanmaz:** `422` (D-02 açık) | Gerçek iki servisle etkisi gözlendi (§5) |
| İki yönlü imza ve HTTPS | Uygulandı | Gerçek iki servis; sertifika ve host adı doğrulaması açık |

**Uygulanmayan ürün kararları:** `READY` bir siparişe gelen kesin iptal ve kısmi iptalin mutfağa etkisi D-02'dir. Kitchen bu iki durumda başarı dönmez. Order'da başarısız teslim aynı siparişin sonraki olaylarını beklettiği için sonuçları ciddidir; ayrıntı §5'te.

## 2. Uç ve güvenlik koşulları

`POST /api/v1/internal/kitchen/order-events`, `Content-Type: application/json`. Doğrudan `kitchen-service`'e gider; API gateway bu yolu yönlendirmez.

### 2.1 Yapılandırma

| Ayar | Anlamı |
|---|---|
| `KITCHEN_ORDER_CALLER_PUBLIC_KEYS` | JSON nesnesi: `kid` → Base64 X.509 RSA açık anahtar (en az 2048 bit). Order'ın `ORDER_KITCHEN_CALLER_PRIVATE_KEY` anahtarının açık yarısı; `kid`, `ORDER_KITCHEN_CALLER_KEY_ID` ile aynı olmalı |
| `ORDER_KITCHEN_SERVICE_RESTAURANTS` | Order'ın olay gönderebileceği restoran UUID'leri, virgülle ayrılmış, küçük harfli standart biçim. PR #47 aynı adı Order'da da kullanır |

- İkisi birlikte dolu değilse uç **kapalıdır**; her istek `401` alır.
- İkisi dolu ve biri kullanılamaz haldeyse Kitchen başlamaz; hata iletisi değeri yazmaz.
- Compose bu iki ayarı yalnız `kitchen-service`'e verir. `.env.example` içinde boşturlar. Özel anahtar Kitchen'a verilmez.
- Her ortamda ayrı anahtar çifti kullanılmalıdır.

**Anahtar değiştirme sırası:** (1) yeni açık anahtar, yeni `kid` ile Kitchen haritasında eskisinin yanına eklenir ve Kitchen yeniden başlatılır; (2) Order yeni `kid` ile imzalamaya geçer; (3) eski anahtar haritadan çıkarılır ve Kitchen yeniden başlatılır. İmzalayan önce değiştirilirse istekler `401` alır.

`ORDER_KITCHEN_SERVICE_RESTAURANTS` ortak `.env.example` dosyasında **bir kez** bulunmalıdır. PR #47 ile Kitchen değişikliği dosyanın aynı yerine ekleme yaptığı için ikinci birleşen tarafta çakışma çıkar; çözümde iki tarafın diğer satırları korunur, bu satır tek kalır.

### 2.2 HTTPS

Kitchen yalnız **sunucunun kendisinin güvenli gördüğü** bağlantıyı kabul eder; `X-Forwarded-Proto` ve `Forwarded` başlıklarına güvenmez. Bu depodaki compose düzeninde Kitchen için TLS dinleyicisi yoktur. Bu nedenle ortak ortamda imzalı istek de `403 HTTPS_REQUIRED` alır. TLS'i bir vekilde sonlandırmak, ayrıca kararlaştırılması gereken bir güven ayarı ister (KO2-07).

### 2.3 İmzalı kimlik

`Authorization: Service <JWT>`; `RS256` ve `kid`; en çok 4096 karakter.

| Claim | Kural |
|---|---|
| `iss` / `sub` | `restofy-order` / `order-service` |
| `aud` | `restofy-kitchen` |
| `scope` / `token_type` | `kitchen:order-events` / `service` |
| `jti` | Küçük harfli standart biçimde UUID |
| `iat`, `nbf`, `exp` | `iat = nbf`; `iat` gelecekte olamaz; süre dolmamış ve `exp ≤ iat + 30` |
| `restaurant_id` | İzinli listede; gövdedeki `restaurantId` ile aynı |
| `method` / `target` | `POST` / `/api/v1/internal/kitchen/order-events` |
| `body_sha256` | Gönderilen baytların küçük harfli onaltılık SHA-256 özeti |

Saat farkı toleransı yoktur; iki servisin saati eşitlenmiş olmalıdır.

Kontrol sırası: kimlik (`401`) → HTTPS (`403`) → istek imzalananla aynı mı: yöntem, ham yol, sorgu yok, `Cookie`, `Origin` ve `Content-Encoding` yok, tek `Authorization` (`403`) → gövde en çok 262.144 bayt (`413`) → gövde özeti (`403`) → gövdedeki restoran (`403`). Bu yolda CSRF koruması kapalıdır: oturum ve çerez yoktur, `Cookie` veya `Origin` taşıyan istek reddedilir. Diğer yolların güvenlik kuralları değişmedi.

### 2.4 Kimliğin yeniden kullanılması (`jti`)

Kitchen `jti` zorunlu tutar, fakat **saklamaz**. Gerekçe:

- İmza yöntemi, yolu, restoranı ve gövdenin özetini bağlar. Ele geçirilen bir kimlikle yalnız **aynı gövde**, aynı uca, en çok 30 saniye içinde gönderilebilir.
- Uygulanan tek olay sipariş kabulüdür. `eventId` ve restoran–sipariş çifti veritabanında kalıcı olarak tekildir; Kitchen sipariş silmez. Aynı gövde ikinci sipariş oluşturmaz ve hazırlık ya da iptal durumunu geri almaz.
- İlk isteği Kitchen'a ulaşmadan yakalayıp sonradan gönderen biri, yalnız Order'ın imzaladığı gerçek siparişi oluşturabilir.

Kalan risk: süre içinde aynı isteğin çok kez gönderilmesi yinelenen sorgu, log ve sayaç yükü üretir; hız sınırı yoktur. Bu gerekçe her uygulanan olay türü için `eventId` ile kalıcı tekilleştirmeye dayanır; iptal olayları için bkz. §5.

## 3. Sipariş kabul sözleşmesi (`schemaVersion` 1)

Bilinmeyen alan (fiyat, vergi, ödeme durumu ve misafir oturumu dahil), yinelenen anahtar, tip dönüştürme, fazladan içerik ve UTF-8 olmayan bayt reddedilir.

| Alan | Kural |
|---|---|
| `eventId` | UUID, zorunlu |
| `eventType` | Zorunlu, yalnız `OrderAccepted` |
| `schemaVersion` | `1` |
| `occurredAt` | ISO-8601 an, zorunlu |
| `correlationId` | İsteğe bağlı, en çok 64, `A-Z a-z 0-9 . _ -` |
| `restaurantId`, `orderId`, `tableSessionId` | UUID, zorunlu |
| `tableId` | UUID, zorunlu. Doğrulanır, **saklanmaz** |
| `tableNumber` | **Zorunlu**, boş olamaz, en çok 40. Sipariş anındaki masa numarası; mutfak ekranında gösterilir |
| `aggregateVersion` | 0 veya büyük |
| `orderNumber` | Boş olamaz, en çok 40 |
| `items` | 1–200; `orderItemId` sipariş içinde ve restoranda tekil |
| `items[].productId`, `productName` | UUID; ad boş olamaz, en çok 200 |
| `items[].quantity`, `note` | 1–999; not isteğe bağlı, en çok 1000 |
| `items[].options` | En çok 100 (50 çıkarılan + 50 eklenen); aynı `optionId` bir kalemde bir kez |
| `options[]` | `action` (`ADD`/`REMOVE`), `optionId`, `name` (en çok 150), `quantity` (1–99), `note` (isteğe bağlı, en çok 500) |

Metinlerde NUL karakteri ve eşsiz surrogate bulunamaz. Sınırı aşan mesaj bütünüyle reddedilir; hiçbir şey kesilmez.

Order'da kabul olayı checkout sırasında sipariş başına bir kez oluşur ve tekrarında aynı `eventId` kullanılır.

**`tableId`'nin saklanmaması.** Yalnız `tableId`'si değişen bir tekrar `204` alır. Bunu kabul edilebilir kılan, Kitchen'ın **kendi** davranışıdır: tekrar teslim saklanan hiçbir veriyi değiştirmez; Kitchen `tableId`'yi saklamaz, göstermez ve hiçbir karar veya yetki için kullanmaz. Kitchen, `tableId`'nin masa oturumuna ait olduğunu **denetlemez**; tutarsız bir değeri ilk teslimde de tekrarda da fark etmez.

Doğru değerin gelmesi diğer servislere dayanır (QR ve Order: `dev` `00c0ff4`; Order mesajı: PR #47):

| Halka | Kaynak | Dayanağın gücü |
|---|---|---|
| Bir QR masa oturumu tek bir masaya bağlıdır | QR `V26__create_table_sessions.sql`: `table_id NOT NULL` ve `(restaurant_id, table_id)` → `dining_tables` yabancı anahtarı | Veritabanı kısıtı |
| Oturumun masası sonradan değişmez | `TableSession.java:19` `updatable = false`; QR kodunda oturumun `table_id`'sini güncelleyen sorgu görülmedi | **Esas olarak uygulama kodu.** `table_sessions` üzerinde bunu engelleyen trigger yok. Tarama ve kapanış olayı kayıtlarının üçlü yabancı anahtarı yalnız bağlı kayıt varken değişikliği engeller |
| `tableId` ve `tableSessionId` aynı oturumdan döner | `GuestSessionContextReader.java:66` masa kimliğini oturumdan bulur; `GuestSessionValidationService.java:103` ikisini birlikte döndürür | Uygulama kodu |
| Order ikisini aynı QR yanıtından alır ve siparişte değiştirmez | `OrderCheckoutService.java:260`; Order `V42__create_order_tables.sql:118` güncelleme trigger'ı | Kopyalama uygulama kodu; sipariş satırının değişmezliği veritabanı trigger'ı |
| Yeniden gönderim aynı değerleri taşır | PR #47 `KitchenEventProjection` mesajı saklı outbox gövdesinden üretir; gövdeyi güncelleyen kod görülmedi | Uygulama kodu; outbox için veritabanı koruması görülmedi |

Bu **uçtan uca bir garanti değildir.** Karşılaştırılan `tableSessionId` ve `tableNumber` da `tableId`'nin eşitliğini kanıtlamaz. Farklı bir `tableId`'nin tespit edilmesi istenirse alanın saklanması, yani migration gerekir. Bugün bunu gerektiren bir Kitchen davranışı olmadığı için şema değiştirilmedi.

Örnek gövde. `tableNumber`, Order'ın QR yanıtından sipariş anında aldığı değerdir (QR PR #78). Bu alan olmadan kaydedilmiş eski bir Order olayı `400` alır.

```json
{
  "eventId": "11111111-1111-4111-8111-111111111111",
  "eventType": "OrderAccepted",
  "schemaVersion": 1,
  "occurredAt": "2026-11-26T09:00:00Z",
  "correlationId": "corr-1",
  "restaurantId": "22222222-2222-4222-8222-222222222222",
  "orderId": "33333333-3333-4333-8333-333333333333",
  "aggregateVersion": 0,
  "orderNumber": "A-100",
  "tableId": "88888888-8888-4888-8888-888888888888",
  "tableSessionId": "44444444-4444-4444-8444-444444444444",
  "tableNumber": "12",
  "items": [
    {
      "orderItemId": "55555555-5555-4555-8555-555555555555",
      "productId": "66666666-6666-4666-8666-666666666666",
      "productName": "Burger",
      "quantity": 2,
      "note": "az pişmiş",
      "options": [
        { "action": "REMOVE", "optionId": "77777777-7777-4777-8777-777777777777", "name": "Soğan", "quantity": 1 }
      ]
    }
  ]
}
```

## 4. Yanıtlar

**`204` yalnız** olay kalıcı olarak uygulandığında veya aynı `eventId` aynı içerikle tekrar geldiğinde döner. Diğer yanıtların hiçbirinde saklanan veri değişmez. PR #47'de `400`, `409`, `422` ilk denemede kalıcı hatadır; `401`, `403`, `404`, `408`, `429`, `5xx` sınırlı sayıda yeniden denenir.

| Durum | `code` | Ne zaman |
|---|---|---|
| `204` | – | Uygulandı veya aynı olayın tekrarı |
| `400` | `INVALID_ORDER_EVENT` | Sözleşmeye uymayan gövde, bilinmeyen tür veya alan |
| `401` | `UNAUTHENTICATED` | Uç kapalı veya kimlik geçersiz |
| `403` | `HTTPS_REQUIRED`, `REQUEST_NOT_SIGNED`, `RESTAURANT_MISMATCH` | Güvenli bağlantı yok, istek imzalananla aynı değil veya restoran farklı |
| `409` | `CONTENT_CONFLICT` | `eventId` başka içerikle; iptal isteğinde çakışan kimlik ya da başka siparişin kalemi; reddedildiği kayıtlı bir iptalin uygulanması veya uygulandığı kayıtlı birinin reddi |
| `409` | `OTHER_EVENT_STORED` | Sipariş başka bir olayla saklı ya da başka bir kararla iptal edilmiş; reddedilmiş istek için ikinci bir karar olayı |
| `409` | `STALE_VERSION` | Saklanandan eski sipariş sürümü |
| `409` | `NEWER_VERSION_NOT_APPLIED` | Kabul olayında saklanandan yeni sürüm (D-02) |
| `409` | `UNKNOWN_ORDER` | İptal olayı Kitchen'ın tanımadığı bir sipariş için |
| `409` | `NEEDS_RECONCILIATION` | `READY` siparişe kesin iptal (D-02) ya da iptal edilmiş siparişe onay isteği |
| `413` | `BODY_TOO_LARGE` | Gövde sınırı aşıldı |
| `415` | – | İçerik türü JSON değil |
| `422` | `UNSUPPORTED_EVENT_TYPE` | Yalnız `OrderItemsCancelled` |
| `503` | `TEMPORARILY_UNAVAILABLE`, `AUTHENTICATION_UNAVAILABLE` | Beklenmeyen hata |

**Yeniden gönderim:** `204` dışında yanıt alan olaydan Kitchen'da kayıt kalmaz; aynı gövde her seferinde yeniden değerlendirilir.

### Tekrar teslim

İçerik karşılaştırması sipariş sürümünü, masa oturumunu, masa ve sipariş numarasını ve bütün kalemleri kapsar. Sipariş numarası, masa numarası ve ürün adının baş ve son boşlukları yok sayılır. `occurredAt` ve `correlationId` saklanmadığı için karşılaştırılmaz. `tableId` de saklanmaz: **yalnız `tableId` değeri farklı olan bir tekrar `204` alır.** İlk kabul olayı, sipariş hazırlanmaya başladıktan veya iptal edildikten sonra da aynı olay sayılır.

## 5. Order → Kitchen iptal olayları

Gövde Order'ın Kitchen projeksiyonundaki alanlardır ve kabul mesajıyla aynı katılıkta okunur: `eventId`, `eventType`, `schemaVersion` (1), `occurredAt`, `correlationId`, `restaurantId`, `orderId`, `orderVersion`, `cancellationId`, `scope` (`FULL`/`PARTIAL`), `kitchenConfirmationRequired`, `items[{orderItemId, quantity, remainingQuantity}]`, `decidedAt`, `rejectionCode`, `reasons[{code}]` (yalnız `OrderCancelled`). Tutar, serbest metin ve kişi bilgisi gelmez; bilinmeyen alan reddedilir.

| Olay | Kitchen'ın yaptığı | Tekilleştirme |
|---|---|---|
| `OrderCancellationRequested` (`kitchenConfirmationRequired=true`) | İsteği personelin cevabı için kaydeder (`order_cancellation_confirmations`); sipariş değişmez. Siparişte aynı anda tek bekleyen istek olur. | `(restaurant_id, request_event_id)` ve `(restaurant_id, cancellation_id)` benzersiz |
| `OrderCancelled` (`FULL`, tüm kalemlerde `remainingQuantity=0`) | Sürüm Kitchen'ın bildiğinden büyükse `WAITING`/`PREPARING` sipariş `CANCELLED` olur ve aşağıdaki kayıtlar aynı transaction'da kapanır. `READY` sipariş iptal edilmez (`409 NEEDS_RECONCILIATION`). | Durum geçmişindeki `source_event_id` |
| `OrderCancellationRejected` | Sipariş değişmez. İstek kaydı varsa `order_outcome=REJECTED`, Order'ın ret kodu, karar zamanı ve olay kimliği yazılır (mutfak onaylamış olsa da). Kayıt yoksa yalnız onaylanır. | Karar olayının kimliği kayıtta |
| `OrderItemsCancelled` | Uygulanmaz: `422` | – |

**`OrderCancelled` ile kapanan kayıtlar:**
- Aynı `cancellationId`'li Order isteği `APPLIED` olur.
- Cevaplanmamış başka bir istek `SUPERSEDED` olur; artık cevaplanamaz.
- Cevaplanmış ama sonucu bilinmeyen istek, kendi cevap tesliminin yanıtını bekler.
- Mutfağın açık kendi talebi `REJECTED` olur; kod `ORDER_ALREADY_CANCELLED`, zaman, sürüm ve olay kimliği bu olaydandır. Bu olay mutfağın kendi talebinden doğmuş olamaz: Order, Kitchen'ın kendi iptalini Kitchen'a göndermez. Bu yüzden talep artık uygulanamaz.

Mutfağın kendi talebi siparişi iptal ettiğinde, cevaplanmamış bir Order isteği de `SUPERSEDED` olur.

#77'nin §2.4 koşulu (yeni olay türü `eventId` ile kalıcı tekilleştirilmeden uygulanmaz) bu üç türde sağlanır: aynı gövdenin 30 saniye içinde tekrar gönderilmesi ikinci bir değişiklik yapamaz.

Kitchen yalnız Order'ın kararıyla iptal eder. Kitchen'ın kendi talebinin sonucu için Order olay göndermez (§6.4).

**Açık ürün kararları (D-02, uygulanmadı).** Gerçek iki servisle gözlenen etkileri:
- **Kısmi iptal:** `OrderItemsCancelled` `422` alır ve Order'da `FAILED` olur. Order bir siparişin sonraki olaylarını bu teslimden sonra göndermediği için **aynı siparişin sonraki tam iptali de Kitchen'a ulaşmaz**: Order'da `CANCELLED` olan sipariş mutfakta `WAITING` görünmeye devam eder.
- **Gecikmiş kesin iptal:** Order siparişi `RECEIVED` iken anında iptal eder, ama mutfak hazırlayıp `READY` yapmışsa `OrderCancelled` `409` alır ve Order'da `FAILED` olur. Order `CANCELLED`, mutfak `READY` kalır. Bu durumda `PREPARING`/`READY` teslimleri `IGNORED_ORDER_CLOSED` ile onaylanır; Kitchen uyarı loglar.
- **`READY` siparişe gelen onay isteği:** mutfak yalnız reddedebilir. Cevaplanmayan istek Order'da açık kalır.

## 6. Kitchen → Order (KITCHEN-INT-03)

Kaynak, Kitchen outbox'ındaki `ORDER` hedefli teslimatlardır; yalnız `kitchen-order` profiliyle gönderilir.

### 6.1 Olay → uç → gövde

| Kitchen işlemi | Outbox olayı | Order ucu (`/api/v1/internal/order-events`) | Gönderilen alanlar |
|---|---|---|---|
| Hazırlığı başlat | `KitchenPreparationStarted` | `POST /kitchen` | `eventId`, `restaurantId`, `orderId`, `kitchenStatus: "PREPARING"`, `occurredAt` |
| Hazır işaretle | `KitchenOrderReady` | `POST /kitchen` | aynı alanlar, `kitchenStatus: "READY"` |
| Kitchen iptal talebi | `KitchenCancellationRequested` | `POST /kitchen-cancellation-request` | `eventId`, `restaurantId`, `orderId`, `cancelAll: true`, `reasonCodes` (tek kod); yalnız `OTHER`'da `reasonText` |
| Order isteğine cevap | `KitchenCancellationDecided` | `POST /kitchen-cancellation` | `eventId`, `restaurantId`, `orderId`, `cancellationId`, `decision`; rette `rejectionCode: "KITCHEN_DECLINED"` |

Neden kodları: `OUT_OF_STOCK` → `ITEM_UNAVAILABLE`; `CUSTOMER_REQUEST`, `KITCHEN_ERROR`, `OTHER` aynen. Açıklaması olmayan eski bir `OTHER` kaydı gönderilmez (`FAILED`, `REASON_TEXT_MISSING`). Ret kodu Order sözleşmesindeki örnektir; Kitchen'da ret nedeni kataloğu yoktur. Personel bilgisi, iç not ve fiyat gönderilmez. Başlıklar: `Authorization: Service <token>`, `Content-Type`, `Accept` ve varsa `X-Correlation-Id`.

### 6.2 İmza ve taşıma

- Her denemede yeni RS256 token: `iss=restofy-kitchen`, `sub=kitchen-service`, `aud=restofy-order`, `scope=order:kitchen-events`, `token_type=service`, UUID `jti`, `iat = nbf`, `exp = iat + 30 sn`, `restaurant_id`, `method=POST`, `target`, `body_sha256`.
- Anahtar yalnız Kitchen'da: `KITCHEN_ORDER_SIGNING_KEY_ID`, `KITCHEN_ORDER_SIGNING_PRIVATE_KEY` (Base64 PKCS#8 RSA, en az 2048 bit). Eksik veya geçersizse profil açılmaz; hata mesajı değeri yazmaz. Anahtar ve token loglanmaz.
- `ORDER_SERVICE_URL` yalnız yolsuz HTTPS adresi olabilir (düz HTTP yalnız loopback). Sertifika ve hostname doğrulaması kapatılmaz; yönlendirme izlenmez; yanıtın en çok 16 KiB'ı okunur.

### 6.3 Teslim onayı

- Teslimat yalnız `200` ve sözleşmeye uyan gövdeyle `DELIVERED` olur: `orderId` aynı, `outcome` ucun değeri (`/kitchen`: `APPLIED`, `IGNORED_STALE`, `IGNORED_ORDER_CLOSED`; `/kitchen-cancellation`: `APPLIED`, `IGNORED_STALE` ve aynı `cancellationId`).
- `/kitchen-cancellation` yanıtında ayrıca `cancellationStatus` `APPLIED` veya `REJECTED` olmalıdır; başka değer onay sayılmaz. Bu değer Order'ın nihai sonucu olarak kaydedilmeden teslimat `DELIVERED` olmaz; kayıttakiyle çelişirse `FAILED` (`DECISION_CONFLICT`).
- Kesin retler (`400`, `415`, `422 VALIDATION_ERROR` / `UNKNOWN_ORDER_ITEM` / `CANCEL_QUANTITY_EXCEEDED`, `409 IDEMPOTENCY_CONFLICT`) hemen `FAILED`; `409 INVALID_STATUS_TRANSITION`, diğer `409`, `401`, `403`, `404`, `408`, `429`, `5xx`, `3xx`, zaman aşımı, bağlantı hatası ve sözleşmeye uymayan `200` yeniden denenir. Mutfağın kendi talebine özel iki yanıt bekleme sayılır (§6.6).
- **Teslim, uygulama değildir.** `/kitchen` için `IGNORED_ORDER_CLOSED` teslimi tamamlar, ama durum Order'da uygulanmamıştır; Kitchen bunu uyarı olarak loglar.

### 6.4 Kitchen talebinin kararı (ACK ile karar ayrı)

Order, Kitchen'ın kendi talebini aldığı anda karara bağlar (`APPLIED` = iptal edildi, `REJECTED` = edilmedi). Karar yalnız bu yanıttadır.

1. Yanıt, #77'nin `OrderCancellationReceipt` sınıfıyla **gönderilen talebe göre** okunur: restoran, sipariş ve talep kimliği yanıttan alınmaz.
2. Tam karar `OrderDecisionService` ile kaydedilir: inbox, sürüm ve zaman kuralları geçerlidir. `APPLIED` → Kitchen siparişi `CANCELLED`, talep `APPROVED`; `REJECTED` → talep `REJECTED`, sipariş aynı.
3. Teslimat ancak karar kaydedilip **Kitchen kayıtları o kararla uyuştuğunda** `DELIVERED` olur. Kayıt sırasında hata → yeniden deneme; Order aynı `eventId` için ilk kararı tekrar döner ve inbox kararı bir kez uygular. Alınan ama uygulanamayan karar `FAILED` kalır: `DECISION_STALE`, `DECISION_NEEDS_RECONCILIATION`, ya da talep başka türlü karara bağlanmışsa `DECISION_CONFLICT`.
4. İlk kararın değerleri olmayan geç yanıt (`DECISION_INCOMPLETE`) ya da başka talebe ait yanıt (`DECISION_MISMATCH`) hiçbir şey uygulamadan `FAILED` olur ve uzlaştırma ister.
5. Order'ın karar saati Kitchen'ın sipariş kabul saatinden en çok 5 dakika geride, şimdiden en çok 5 dakika ileride olabilir.

Yanıt yapılandırılmış Order adresine TLS ile kurulan bağlantıdan gelir; gönderici Order kabul edilir.

Order'ın kendi isteği mutfak cevabı beklerken Kitchen yeni bir Kitchen talebi açmaz (`409 ORDER_CANCELLATION_AWAITING_ANSWER`): Order bu talebi `CANCELLATION_ALREADY_PENDING` ile reddederdi. Order isteği, Kitchen talebi açıldıktan **sonra** gelirse bkz. §6.6.

### 6.5 Mutfak cevabı (personel) ve Order'ın sonucu

- `GET /api/v1/kitchen/orders/{orderId}/order-cancellations`: KITCHEN ve ADMIN. Her istekte mutfağın cevabı (`status`) ve **ayrı olarak** Order'ın sonucu (`orderOutcome`: `APPLIED`, `REJECTED`, `SUPERSEDED` veya `null`), varsa `orderDecidedAt` ve `orderRejectionCode` bulunur. `status=APPROVED`, `orderOutcome=REJECTED` geçerli bir sonuçtur: mutfak onayladı, Order iptal etmedi. Zaman ve kod yalnız Order olayından gelir; boş olabilir.
- `POST .../order-cancellations/{cancellationId}/answer`: yalnız KITCHEN, `Idempotency-Key` zorunlu, gövde `{"decision":"APPROVED"|"REJECTED"}`. İlk cevap `202`, aynı cevabın tekrarı `200`.
- Retler: aynı anahtar başka cevap için `409 COMMAND_ID_CONFLICT`; cevaplanmış istek `409 CANCELLATION_ALREADY_ANSWERED`; Order'ın karara bağladığı istek `409 ORDER_CANCELLATION_CLOSED`; `READY` veya `CANCELLED` siparişe `APPROVED` `409 ORDER_STATE_CONFLICT`. `REJECTED` her sipariş durumunda verilebilir.
- Cevap ve `KitchenCancellationDecided` olayı aynı transaction'da yazılır; mutfak siparişi değişmez. Siparişi yalnız Order'ın `OrderCancelled` olayı iptal eder.
- Cevap ve sonuç birer kez yazılır; satır silinmez (veritabanı tetikleyicileri).

### 6.6 Sıra, bekleme, idempotency ve yeniden deneme

- Teslim **en az bir kez**dir; "tam bir kez" garantisi yoktur. Yeniden denemede `eventId` ve gövde baytları değişmez; token her denemede yenilenir.
- **Bekleyen teslim deneme harcamaz:**
  - `READY`, aynı siparişin `PREPARING` teslimi `DELIVERED` olmadan seçilmez (`PREPARING` `FAILED` olsa da).
  - Mutfağın kendi talebi, o siparişte Order'ın sonucu bilinmeyen bir isteği varken seçilmez.
  - Order, Kitchen'a henüz ulaşmamış bir isteği yüzünden `409 CANCELLATION_ALREADY_PENDING` dönerse, ya da iptal olayı henüz gelmeden `409 ORDER_ALREADY_CANCELLED` dönerse, teslim `PENDING` kalır, deneme sayılmaz ve 15 saniye sonra yeniden sorulur. İkincisinde iptal olayı talebi kapattıktan sonra aynı yanıt teslimi `DELIVERED` yapar.
  - Bekleme süresizdir. Takılan bir teslim `last_error_code` ile görünür; neden genellikle Order'ın aynı siparişteki `FAILED` bir `KITCHEN` teslimidir.
- Diğer geçici hatalarda gecikmeler 5, 10, 20, 40, 80, 160, 320 sn; 8. denemeden sonra `FAILED`. Bekleme sürelerinin toplamı 10 dk 35 sn'dir.
- Her deneme satır kilidiyle 60 sn sahiplenilir; HTTP çağrısı sırasında transaction ve kilit açık değildir. Birden çok örnek aynı anda çalışabilir; duran göndericinin işi süre dolunca yeniden alınır. Okunamayan kayıtlı olay bir kez `FAILED` (`UNREADABLE_EVENT`) olur.
- **Yeniden sıraya alma:** neden giderildikten sonra Kitchen veritabanında yetkili operatör tek bir `FAILED` teslimatı [`requeue-order-delivery.sql`](../../backend/services/kitchen-service/ops/requeue-order-delivery.sql) ile aynı olay ve gövdeyle `PENDING`'e döndürür; önceki deneme sayısını, hata kodunu ve işlemi yapanı döner. Aynı siparişte önce `PREPARING`, sonra `READY`. Talebi sonradan kapanmış bir `FAILED` teslim de yeniden sıraya alınınca `DELIVERED` olur.

  ```text
  psql -X -v ON_ERROR_STOP=1 -d <KITCHEN_DATABASE> -v restaurant_id=<RESTAURANT_UUID> -v event_id=<EVENT_UUID> -f backend/services/kitchen-service/ops/requeue-order-delivery.sql
  ```

### 6.7 "Hazır" koruması

`mark-ready`, siparişin tamamı için bir iptal Kitchen ile Order arasında açıkken `409 ORDER_CANCELLATION_UNDECIDED` döner (`retryable: true`):
- mutfağın karara bağlanmamış kendi talebi (`REQUESTED`), ya da
- mutfağın onayladığı `FULL` Order isteği; Order sonucu boş veya `APPLIED`.

Sebebi: Order bu iptali uygulayabilir; mutfak o arada `READY` olursa iki servis farklı sonuca varır. Koruma, sipariş satırı kilidi altında okunur; onay, talep ve `mark-ready` birbirini geçemez.

- **Koruma kalkar:** ilgili teslim, Order'ın hiç kabul etmediği biçimde `FAILED` olduysa (`HTTP_4xx` kesin ret, `409 IDEMPOTENCY_CONFLICT` hariç; `UNREADABLE_EVENT`; `REASON_TEXT_MISSING`; `UNMAPPED_REASON`). Talep veya istek kayıtta açık kalır; neden giderilince teslim yeniden sıraya alınır.
- **Koruma sürer:** sonuç bilinmiyorsa (`MAX_ATTEMPTS_EXCEEDED`, `IDEMPOTENCY_CONFLICT`, `DECISION_*`). Order 10 dakikadan uzun kapalı kalırsa operatör teslimi yeniden sıraya almadan sipariş `READY` yapılamaz.
- `kitchen-order` profili kapalıyken olaylar gönderilmez ve koruma sürer: kuyruktaki olay profil açılınca Order'a gidecektir.
- Kısmi (`PARTIAL`) onay ve cevaplanmamış Order isteği `READY`'yi engellemez.

## 7. Yapılandırma

| Ayar | Yön | Not |
|---|---|---|
| `KITCHEN_ORDER_CALLER_PUBLIC_KEYS`, `ORDER_KITCHEN_SERVICE_RESTAURANTS` | Order → Kitchen | §2.1 |
| `kitchen-order` Spring profili | Kitchen → Order | Gönderici, imzalayıcı ve zamanlayıcı yalnız bu profille oluşur |
| `KITCHEN_ORDER_SIGNING_KEY_ID`, `KITCHEN_ORDER_SIGNING_PRIVATE_KEY` | Kitchen → Order | `.env.example`'da boş; `docker-compose` geçirmez |
| `ORDER_SERVICE_URL` | Kitchen → Order | HTTPS; yerel değer `http://order-service:8080` bu profille reddedilir |
| `REST_CLIENT_CONNECT_TIMEOUT_MS`, `REST_CLIENT_READ_TIMEOUT_MS` | Kitchen → Order | Pozitif, toplamı 60 sn'den kısa |
| `restofy.kitchen.order-delivery.interval` | Kitchen → Order | Varsayılan `PT2S`, turda en çok 20 teslimat |

Yeni ortam değişkeni yoktur.

**Migration `V90`** (Kitchen aralığının son numarası): `order_cancellation_confirmations`. Order'ın sonucunu tutan sütunlar, `V90` `dev`'e girmeden ve ortak bir ortamda uygulanmadan önce aynı dosyaya eklendi. `V90`'ın önceki halini yerel veritabanında çalıştıran biri Flyway sağlama toplamı hatası alır; yerel Kitchen veritabanı yeniden oluşturulmalıdır. Bundan sonraki Kitchen şema değişikliği için ekipten yeni aralık istenmelidir.

## 8. Doğrulama

| Kontrol | Sonuç |
|---|---|
| Kitchen testleri | Gerçek PostgreSQL (Testcontainers); imzalı alıcı, HTTP gönderici, karar kaydı, sıra, bekleme, yeniden başlatma, yeniden sıraya alma. Yaşam döngüsü matrisi: `OrderCancellationMatrixTests`, `OrderCancellationLifecycleTests`. Order burada bir taklittir |
| Gerçek iki servis (geçici, commit edilmedi) | `dev`'deki Order ve bu branch'teki Kitchen iki ayrı uygulama olarak, ayrı PostgreSQL veritabanları ve gerçek `order-kitchen` / `kitchen-order` profilleriyle. İki yönde gerçek HTTPS (sertifika ve host adı doğrulaması açık) ve gerçek RS256 imzaları |

Gerçek iki servisle geçen senaryolar:
- Sipariş kabulü (`tableNumber` dahil), `PREPARING` → `READY`.
- Mutfağın kendi talebi (`WAITING` ve `PREPARING`, `OTHER` açıklamasıyla) ve karar beklerken "Hazır" engeli.
- Order isteği: ret; onay ve iptalin mutfağa yansıması; `READY` siparişte onayın reddi.
- Mutfak onayından sonra Order'ın kendi reddi (`ORDER_ALREADY_PAID`; Payment entegre olmadığı için ödeme durumu Order veritabanında elle ayarlandı).
- Kuyruktaki mutfak talebi varken anında iptal; kesişen iki talep; Order isteği Kitchen'a henüz ulaşmamışken `409 CANCELLATION_ALREADY_PENDING` (deneme harcanmadı).
- İki yönde onayın kaybolması ve yeniden teslim (ikinci uygulama olmadı); Order kapalıyken hazırlık ve iki servisin yeniden başlatılması.
- İmzasız ve sahte imzalı istekler `401`.
- Gözlem: §5'teki D-02 etkileri.
- Gözlem (Order tarafı): Kitchen yaklaşık 79 sn kapalıyken Order `OrderPlaced` teslimini `FAILED` yaptı; Kitchen açılınca sipariş operatör yeniden sıraya almadan gelmedi.

**Bu denemede test karşılığı olanlar:** Menu, QR ve Order'ın personel kimliği (projenin bellek içi adaptörleri) ile Kitchen personel kimliği (geçici güvenlik zinciri).

**Doğrulanmadı:** gerçek Auth, QR ve Menu ile birlikte çalışma; Kitchen personel uçlarının #61 kimliğiyle kullanımı; ortak ortam, anahtar dağıtımı ve `kid` değişimi; saat farkı.

## 9. Açmadan önce

1. #77 ve #80'in `dev`'e girmesi; ardından bu bağlantı branch'i.
2. Anahtar değişimi: Kitchen'ın açık anahtarı ve `kid` → Order `ORDER_KITCHEN_SERVICE_PUBLIC_KEYS`; Order'ın açık anahtarı ve `kid` → Kitchen `KITCHEN_ORDER_CALLER_PUBLIC_KEYS`; iki tarafta `ORDER_KITCHEN_SERVICE_RESTAURANTS`. Özel anahtarlar paylaşılmaz.
3. İki servis için HTTPS adresi ve sertifika güveni; Kitchen'ın kendi TLS dinleyicisi (§2.2); saat eşitlemesi (iki yönde de ileri tarihli `iat` reddedilir).
4. Order'da birikmiş `KITCHEN` teslimleri: Order bunları profil kapalıyken de yazar; açılınca eski siparişler Kitchen'a gelir. Açmadan önce nasıl ayıklanacağı Order ile kararlaştırılmalı.
5. Kısa kesintide kaybolan teslimler: Order'ın Kitchen'a deneme süresi (yaklaşık 75 sn) Order ile ele alınmalı.
6. D-02: kısmi iptal ve gecikmiş kesin iptal kararı (§5).
7. #61 birleşince `GET .../order-cancellations` ucu personel okuma listesine (`StaffReadEndpoints`) eklenmeli.
8. Profil ilk açıldığında Kitchen'da birikmiş `PENDING` olaylar da gönderilmeye başlar; önce incelenmeli.
