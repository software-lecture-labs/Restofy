# Kitchen entegrasyon rehberi — Order

Order ekibi ve inceleyenler için. Kitchen ile Order arasındaki iki yönü anlatır: Order → Kitchen olayları (Kitchen PR #77'nin alıcısı) ve Kitchen → Order olayları (KITCHEN-INT-03, Kitchen PR #80). Bu belge, #77 ile #80'i birleştirip bağlayan yerel `chore/kitchen-int-03-wiring` branch'inin durumudur; Order sözleşmesi birleşmemiş Order PR #47 (`00bb2aa`) ile karşılaştırıldı.

> **Entegrasyon etkinleştirilmedi ve çalışan iki servisle uçtan uca doğrulanmadı.** Kod tarafı iki yönde tamam; açılması için §9'daki ortam hazırlığı ve #47, #77, #80'in `dev`'e girmesi gerekir. Order'ın bugünkü kabul mesajı `tableNumber` taşımadığı için Kitchen tarafından reddedilir; sıra #47 → QR #78 → Order'da zorunluluk.

## 1. Durum

| Akış | Kitchen'da | Doğrulama |
|---|---|---|
| Sipariş kabulü (`OrderAccepted`) | Uygulandı (#77) | Kitchen testleri; `tableNumber` sırası yukarıda |
| `PREPARING` / `READY` gönderimi | Uygulandı; `READY`, `PREPARING` teslim edilmeden seçilmez (#77 koruması) | Kitchen testleri; gerçek Order koduyla geçici deneme (§8) |
| Kitchen'ın kendi iptal talebi | Uygulandı: `OTHER` açıklamasıyla gönderim, Order yanıtının doğrulanıp karar olarak kaydı | Kitchen testleri; gerçek Order koduyla geçici deneme |
| Order'ın onay isteği → mutfak cevabı | Uygulandı: istek kaydı, personel cevabı, cevabın gönderimi | Kitchen testleri; gerçek Order koduyla geçici deneme |
| Order'ın kesin tam iptali (`OrderCancelled`) | `WAITING`/`PREPARING` sipariş iptal edilir; `READY` için uygulanmaz (D-02) | Kitchen testleri; gövde gerçek Order projeksiyonuyla denendi |
| Order'ın reddi (`OrderCancellationRejected`) | Onaylanır; mutfak siparişi değişmez | Kitchen testleri; gövde gerçek Order projeksiyonuyla denendi |
| Kısmi iptal (`OrderItemsCancelled`) | **Uygulanmaz:** `422` (D-02 açık) | Kitchen testleri |
| İki yönlü imza ve HTTPS | Doğrulama (#77) ve imzalama (#80) hazır | İmza #47 doğrulayıcısıyla denendi; gerçek TLS ile iki servis denenmedi |

**Uygulanmayan ürün kararları:** `READY` bir siparişin iptali ve kısmi iptalin mutfağa etkisi D-02'dir. Kitchen bu iki durumda başarı dönmez (§4). Order'da başarısız teslim aynı siparişin sonraki olaylarını beklettiği için bu durum Order'ın başarısız teslim listesinde görünür ve elle uzlaştırılır.

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

Örnek gövde. `tableNumber` bugünkü Order mesajında **yoktur**; engel budur.

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
| `409` | `CONTENT_CONFLICT` | `eventId` başka içerikle; iptal isteğinde çakışan kimlik ya da başka siparişin kalemi |
| `409` | `OTHER_EVENT_STORED` | Sipariş başka bir olayla saklı ya da başka bir kararla iptal edilmiş |
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

Gövde PR #47'nin Kitchen projeksiyonundaki alanlardır ve kabul mesajıyla aynı katılıkta okunur: `eventId`, `eventType`, `schemaVersion` (1), `occurredAt`, `correlationId`, `restaurantId`, `orderId`, `orderVersion`, `cancellationId`, `scope` (`FULL`/`PARTIAL`), `kitchenConfirmationRequired`, `items[{orderItemId, quantity, remainingQuantity}]`, `decidedAt`, `rejectionCode`, `reasons[{code}]` (yalnız `OrderCancelled`). Tutar, serbest metin ve kişi bilgisi gelmez; bilinmeyen alan reddedilir.

| Olay | Kitchen'ın yaptığı | Tekilleştirme |
|---|---|---|
| `OrderCancellationRequested` (`kitchenConfirmationRequired=true`) | İsteği personelin cevabı için kaydeder (`order_cancellation_confirmations`); sipariş değişmez. Siparişte aynı anda tek bekleyen istek olur. | `(restaurant_id, request_event_id)` ve `(restaurant_id, cancellation_id)` benzersiz |
| `OrderCancelled` (`FULL`, tüm kalemlerde `remainingQuantity=0`) | Sürüm Kitchen'ın bildiğinden büyükse `WAITING`/`PREPARING` sipariş `CANCELLED` olur; neden kodları siparişte virgülle saklanır. `READY` sipariş iptal edilmez (`409 NEEDS_RECONCILIATION`). | Durum geçmişindeki `source_event_id` |
| `OrderCancellationRejected` | Hiçbir şey değişmez; onaylanır. | Değişiklik olmadığı için gerekmez |
| `OrderItemsCancelled` | Uygulanmaz: `422` | – |

#77'nin §2.4 koşulu (yeni olay türü `eventId` ile kalıcı tekilleştirilmeden uygulanmaz) bu üç türde sağlanır: aynı gövdenin 30 saniye içinde tekrar gönderilmesi ikinci bir değişiklik yapamaz.

Kitchen yalnız Order'ın kararıyla iptal eder. Kitchen'ın kendi talebinin sonucu için Order olay göndermez (§6.4); personelin onay verdiği Order isteğinin sonucu ise `OrderCancelled` ya da `OrderCancellationRejected` ile gelir.

**Açık ürün kararları (uygulanmadı):** `READY` siparişin iptali ve kısmi iptal (D-02); Order bir siparişi kendi kararıyla iptal ettiğinde Kitchen'da açık kalan Kitchen talebinin gösterimi (talep `REQUESTED` kalır; gönderilirse Order `409 ORDER_ALREADY_CANCELLED` ile reddeder ve teslim `FAILED` olur).

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
- Kesin retler (`400`, `415`, `422 VALIDATION_ERROR` / `UNKNOWN_ORDER_ITEM` / `CANCEL_QUANTITY_EXCEEDED`, `409 IDEMPOTENCY_CONFLICT` / `ORDER_ALREADY_CANCELLED`) hemen `FAILED`; `409 INVALID_STATUS_TRANSITION`, diğer `409`, `401`, `403`, `404`, `408`, `429`, `5xx`, `3xx`, zaman aşımı, bağlantı hatası ve sözleşmeye uymayan `200` yeniden denenir.

### 6.4 Kitchen talebinin kararı (ACK ile karar ayrı)

Order, Kitchen'ın kendi talebini aldığı anda karara bağlar (`APPLIED` = iptal edildi, `REJECTED` = edilmedi). Karar yalnız bu yanıttadır.

1. Yanıt, #77'nin `OrderCancellationReceipt` sınıfıyla **gönderilen talebe göre** okunur: restoran, sipariş ve talep kimliği yanıttan alınmaz.
2. Tam karar `OrderDecisionService` ile kaydedilir: inbox, sürüm ve zaman kuralları geçerlidir. `APPLIED` → Kitchen siparişi `CANCELLED`, talep `APPROVED`; `REJECTED` → talep `REJECTED`, sipariş aynı.
3. Teslimat ancak karar kaydedildikten sonra `DELIVERED` olur. Kayıt sırasında hata → yeniden deneme; Order aynı `eventId` için ilk kararı tekrar döner ve inbox kararı bir kez uygular.
4. İlk kararın değerleri olmayan geç yanıt (`DECISION_INCOMPLETE`) ya da başka talebe ait yanıt (`DECISION_MISMATCH`) hiçbir şey uygulamadan `FAILED` olur ve uzlaştırma ister.

Yanıt yapılandırılmış Order adresine TLS ile kurulan bağlantıdan gelir; gönderici Order kabul edilir.

Order'ın kendi isteği mutfak cevabı beklerken Kitchen yeni bir Kitchen talebi açmaz (`409 ORDER_CANCELLATION_AWAITING_ANSWER`): Order bu talebi `CANCELLATION_ALREADY_PENDING` ile reddederdi.

### 6.5 Mutfak cevabı (personel)

- `GET /api/v1/kitchen/orders/{orderId}/order-cancellations`: KITCHEN ve ADMIN; istekleri, istenen kalemleri ve cevabı listeler.
- `POST /api/v1/kitchen/orders/{orderId}/order-cancellations/{cancellationId}/answer`: yalnız KITCHEN, `Idempotency-Key` zorunlu, gövde `{"decision":"APPROVED"|"REJECTED"}`. İlk cevap `202`, aynı cevabın tekrarı `200`, aynı anahtar başka cevap için `409 COMMAND_ID_CONFLICT`, cevaplanmış istek `409 CANCELLATION_ALREADY_ANSWERED`.
- Cevap ve `KitchenCancellationDecided` olayı aynı transaction'da yazılır; mutfak siparişi değişmez. Kesin sonuç Order'ın olayıyla gelir (§5).

### 6.6 Sıra, idempotency ve yeniden deneme

- Teslim **en az bir kez**dir; "tam bir kez" garantisi yoktur. Yeniden denemede `eventId` ve gövde baytları değişmez; token her denemede yenilenir.
- `READY`, aynı siparişin `PREPARING` teslimi `DELIVERED` olmadan seçilmez ve deneme harcamaz; `PREPARING` `FAILED` olursa da bekler (#77 koruması).
- Gecikmeler 5, 10, 20, 40, 80, 160, 320 sn; 8. denemeden sonra `FAILED`. Bekleme sürelerinin toplamı 10 dk 35 sn'dir.
- Her deneme satır kilidiyle 60 sn sahiplenilir; HTTP çağrısı sırasında transaction ve kilit açık değildir. Birden çok örnek aynı anda çalışabilir; duran göndericinin işi süre dolunca yeniden alınır. Okunamayan kayıtlı olay bir kez `FAILED` (`UNREADABLE_EVENT`) olur.
- **Yeniden sıraya alma:** neden giderildikten sonra Kitchen veritabanında yetkili operatör tek bir `FAILED` teslimatı [`requeue-order-delivery.sql`](../../backend/services/kitchen-service/ops/requeue-order-delivery.sql) ile aynı olay ve gövdeyle `PENDING`'e döndürür; önceki deneme sayısını, hata kodunu ve işlemi yapanı döner. Aynı siparişte önce `PREPARING`, sonra `READY`.

  ```text
  psql -X -v ON_ERROR_STOP=1 -d <KITCHEN_DATABASE> -v restaurant_id=<RESTAURANT_UUID> -v event_id=<EVENT_UUID> -f backend/services/kitchen-service/ops/requeue-order-delivery.sql
  ```

## 7. Yapılandırma

| Ayar | Yön | Not |
|---|---|---|
| `KITCHEN_ORDER_CALLER_PUBLIC_KEYS`, `ORDER_KITCHEN_SERVICE_RESTAURANTS` | Order → Kitchen | §2.1 |
| `kitchen-order` Spring profili | Kitchen → Order | Gönderici, imzalayıcı ve zamanlayıcı yalnız bu profille oluşur |
| `KITCHEN_ORDER_SIGNING_KEY_ID`, `KITCHEN_ORDER_SIGNING_PRIVATE_KEY` | Kitchen → Order | `.env.example`'da boş; `docker-compose` geçirmez |
| `ORDER_SERVICE_URL` | Kitchen → Order | HTTPS; yerel değer `http://order-service:8080` bu profille reddedilir |
| `REST_CLIENT_CONNECT_TIMEOUT_MS`, `REST_CLIENT_READ_TIMEOUT_MS` | Kitchen → Order | Pozitif, toplamı 60 sn'den kısa |
| `restofy.kitchen.order-delivery.interval` | Kitchen → Order | Varsayılan `PT2S`, turda en çok 20 teslimat |

Migration: `V90` (Kitchen aralığının son numarası).

## 8. Doğrulama

| Kontrol | Sonuç |
|---|---|
| Kitchen testleri (#77 + #80 + bağlantı) | Gerçek PostgreSQL; imzalı alıcı, HTTP gönderici, karar kaydı, sıra, yeniden sıraya alma |
| Geçici çapraz deneme (commit edilmedi) | Gerçek Order kodu (#47) gerçek bir yerel portta ve PostgreSQL ile; Kitchen'ın derlenmiş göndericisi `PREPARING`/`READY`, Kitchen talebi (`OTHER` dahil) ve mutfak cevabını gönderdi. Order siparişi ilerletti, iptal etti veya reddetti. Order'ın kendi projeksiyonunun ürettiği iptal gövdeleri Kitchen'ın okuyucusundan geçti. Bu denemede Order'ın test kimliği kullanıldı; HTTPS yoktu. |
| İmza | Kitchen'ın imzası #47'nin `KitchenServiceIdentityVerifier` ve `ServiceAuthenticationFilter` sınıflarıyla denendi; başka restoran ve değiştirilmiş gövde reddedildi |

**Doğrulanmadı:** iki servisin uygulama olarak gerçek HTTPS ile birlikte çalışması, anahtar dağıtımı ve ortak ortam. Engeller: Kitchen personel uçları kimlik doğrulaması ister (#61 ve HTTPS'li Auth); Order'ın `order-kitchen` profili `dev`/`test` ile başlatılamaz, bu yüzden sipariş oluşturmak için gerçek QR ve Auth da gerekir.

## 9. Açmadan önce

1. #47, #77 ve #80'in `dev`'e girmesi; ardından bu bağlantı branch'i.
2. Anahtar değişimi: Kitchen'ın açık anahtarı ve `kid` → Order `ORDER_KITCHEN_SERVICE_PUBLIC_KEYS`; Order'ın açık anahtarı ve `kid` → Kitchen `KITCHEN_ORDER_CALLER_PUBLIC_KEYS`; iki tarafta `ORDER_KITCHEN_SERVICE_RESTAURANTS`. Özel anahtarlar paylaşılmaz.
3. İki servis için HTTPS adresi ve sertifika güveni; Kitchen'ın kendi TLS dinleyicisi (§2.2); saat eşitlemesi (iki yönde de ileri tarihli `iat` reddedilir).
4. `tableNumber` sırası: #47 → QR #78 → Order'da zorunluluk.
5. #61 birleşince yeni `GET .../order-cancellations` ucu personel okuma listesine eklenmeli.
6. Profil ilk açıldığında birikmiş `PENDING` olaylar gönderilmeye başlar; önce incelenmeli.
