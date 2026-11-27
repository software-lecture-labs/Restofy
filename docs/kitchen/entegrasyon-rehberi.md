# Kitchen entegrasyon rehberi — Order

Order ekibi için. Yalnız PR #47'deki Order taleplerine (`6d4fdb3`, `docs/order-service/kitchen-entegrasyon-rehberi.md`) Kitchen tarafında yapılan hazırlığı anlatır. Order sorumlusunun sonradan verdiği yanıtlar işlenmiştir; yanıttaki bir davranış PR #47 kodunda henüz yoksa ayrıca belirtilir.

> **Order–Kitchen entegrasyonu tamamlanmadı ve etkinleştirilmedi.** Order'ın bugünkü kabul mesajı Kitchen tarafından **reddedilir** (`tableNumber` yok). Ortak ortamda Kitchen için HTTPS de yoktur. Bu iki konu çözülmeden Order'da `order-kitchen` profili Kitchen'a karşı açılmamalıdır: reddedilen olay Order outbox'ında kalıcı hataya düşer ve aynı siparişin sonraki olaylarını durdurur.

## 1. Taleplerin durumu

Beklenen yanıtların ayrıntısı Order sorumlusuyla ayrıca paylaşılır; `KO2-xx` numaraları o listedeki maddelerdir. "Ekip kararı", yalnız Order'ın veya yalnız Kitchen'ın veremeyeceği kararları gösterir.

| Order talebi | Kitchen tarafında hazır | Açık kalan | Sonraki gerçek entegrasyona kaldı |
|---|---|---|---|
| Olay alım ucu | Uç var; yalnız sipariş kabulünü işler; varsayılan kapalı | Ortak ortamda HTTPS (ekip kararı, KO2-07) | Gerçek bağlantı ve etkinleştirme |
| Servis imzası, restoran kapsamı, HTTPS | Doğrulama hazır; anahtar rotasyonu yerelde denendi | `kid` ve açık anahtar paylaşımı (KO2-07); HTTPS | Ortak ortam denemesi |
| `eventType: "OrderAccepted"` | Hazır | – | – |
| Zorunlu `tableId`, isteğe bağlı `tableNumber` | `tableId` kabul ediliyor. `tableNumber` **zorunlu kaldı** | Order'ın numarayı sipariş anının snapshot'ı olarak göndermesi; QR teyidi (KO2-01) | – |
| Fiyat ve misafir bilgisi kabul etmeme | Zaten vardı | – | – |
| Seçenek, alan ve boyut sınırları | Hazır; saklanamayan metin açıkça reddediliyor | Order'ın kalem adedi ayarı, ortak sınır belirlenmeden 999 üstüne açılmamalı (KO2-09) | – |
| Tekrar teslim ve yeniden gönderim | Reddedilen veya geçici hata alan olay iz bırakmıyor; aynı gövde yeniden değerlendiriliyor. Yalnız `tableId`'si farklı tekrar ayırt edilemiyor | Order'da başarısız teslimlerin görünürlüğü ve yeniden gönderimi (KO2-04, henüz yok) | – |
| Giden olayların taşınması (`OrderEventSink`, çalıştırıcı) | Olaylar sabit `eventId` ile outbox'ta bekliyor | – | İstemci, zamanlayıcı, gerçek gönderim |
| `kitchenStatus` eşlemesi, `PREPARING` → `READY` sırası | Yerel veri ve kuyruk kuralı hazır | – | Eşleme ve gönderim |
| Kitchen iptal talebinin Order'a eşlenmesi | Talep sabit `eventId` ile outbox'ta | – | `cancelAll`, neden kodu eşlemesi, gönderim |
| `OTHER` açıklaması | Hazır: personel API'si 1–300 karakter zorunlu tutuyor; yerel olay yükü `reasonText`'i yalnız `OTHER`'da taşıyor | – | Gönderim |
| `APPLIED` / `REJECTED` yanıtını karara çevirme | Yerel yanıt doğrulaması ve karar servisi hazır; eksik yanıt uygulanmıyor (KO2-08) | Eksik yanıtın uzlaştırılması | İstemci ve yanıtın okunması |
| Order kaynaklı iptal onayı | **Hazır değil;** olay reddedilir | Kitchen iş akışı ve şema kararı (KO2-05) | Alıcı, karar ve gönderim |
| Yerel talebi olmayan kesin tam iptal | **Hazır değil;** olay reddedilir | Neden biçimi belli (KO2-03). `READY` davranışı, açık yerel talep ve şema kararı | Uygulama |
| Kısmi iptal tüm siparişi iptal etmesin, yanlış başarı dönmesin | Hazır: `422`, hiçbir veri değişmez | Mutfak ekranındaki davranış ekip kararı (D-02) | Kısmi iptalin uygulanması |
| Dört iptal olayı, commit sonrası `204` | **Kısmen:** `204` yalnız sipariş kabulünde ve commit sonrası; dört iptal olayı `422` | KO2-03 – KO2-05 | İptal olaylarının uygulanması |
| Kitchen'ın giden isteklerini imzalaması | Yok | – | Anahtar, imzalama, güven ilişkisi |
| İki yönlü uçtan uca deneme | Yok | – | Tamamı |

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

Kalan risk: süre içinde aynı isteğin çok kez gönderilmesi yinelenen sorgu, log ve sayaç yükü üretir; hız sınırı yoktur. Bu gerekçe **yalnız** bugünkü davranış için geçerlidir. Yeni bir olay türü (ör. iptal) uygulanmadan önce `eventId` ile aynı kalıcılıkta tekilleştirilmelidir.

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

**`204` yalnız** olay kalıcı olarak saklandığında döner: yeni sipariş commit edildiğinde veya **aynı `eventId`** aynı içerikle tekrar geldiğinde. Diğer yanıtların hiçbirinde saklanan veri değişmez.

| Durum | `code` | Ne zaman | PR #47 göndericisinin davranışı |
|---|---|---|---|
| `204` | – | Saklandı veya aynı olayın tekrarı | Teslim edildi |
| `400` | `INVALID_ORDER_EVENT` | Sözleşmeye uymayan gövde veya bilinmeyen olay türü | Kalıcı hata |
| `401` | `UNAUTHENTICATED` | Uç kapalı veya kimlik geçersiz | Sınırlı tekrar |
| `403` | `HTTPS_REQUIRED`, `REQUEST_NOT_SIGNED`, `RESTAURANT_MISMATCH` | Güvenli bağlantı yok, istek imzalananla aynı değil veya restoran farklı | Sınırlı tekrar |
| `409` | `CONTENT_CONFLICT` | `eventId` başka içerik, sipariş veya restoranda; ya da kalem kimliği başka siparişte | Kalıcı hata |
| `409` | `OTHER_EVENT_STORED` | Aynı sipariş ve sürüm farklı `eventId` ile saklı | Kalıcı hata |
| `409` | `STALE_VERSION` | Saklanandan eski sürüm | Kalıcı hata |
| `409` | `NEWER_VERSION_NOT_APPLIED` | Saklanandan yeni sürüm (D-02 açık) | Kalıcı hata |
| `413` | `BODY_TOO_LARGE` | Gövde sınırı aşıldı | Kalıcı hata |
| `415` | – | İçerik türü JSON değil | Kalıcı hata |
| `422` | `UNSUPPORTED_EVENT_TYPE` | Dört iptal olayından biri | Kalıcı hata |
| `503` | `TEMPORARILY_UNAVAILABLE`, `AUTHENTICATION_UNAVAILABLE` | Beklenmeyen hata | Sınırlı tekrar |

PR #47'de bir olay en çok 5 kez denenir ve teslim edilmeyen olay aynı siparişin sonraki olaylarını bekletir. Başarısız teslimleri görme ve aynı `eventId` ve gövdeyle yeniden gönderme Order tarafında planlanıyor; **bugün yok**.

**Yeniden gönderim:** `204` dışında yanıt alan olaydan Kitchen'da kayıt kalmaz ve `eventId` kullanılmış sayılmaz. Aynı gövde her seferinde yeniden değerlendirilir: sözleşmeye uymayan gövde yine `400`, desteklenmeyen olay yine `422` alır. Geçici hatadan (`503`) sonra aynı gövde siparişi bir kez oluşturur. Desteklenmeyen olay ancak Kitchen o türü desteklediğinde uygulanabilir.

Örnek hata gövdesi (`application/problem+json`):

```json
{
  "title": "Bad Request",
  "status": 400,
  "detail": "The event does not match the contract; nothing was changed",
  "instance": "/api/v1/internal/kitchen/order-events",
  "code": "INVALID_ORDER_EVENT",
  "violations": ["tableNumber must not be blank"]
}
```

`violations` alan adını ve kuralı yazar; gönderilen değeri yazmaz. Kimlik, HTTPS, imza ve boyut redlerinde `instance` ve `violations` bulunmaz.

### Tekrar teslim

İçerik karşılaştırması sipariş sürümünü, masa oturumunu, masa ve sipariş numarasını ve bütün kalemleri kapsar. Sipariş numarası, masa numarası ve ürün adının baş ve son boşlukları yok sayılır. `occurredAt` ve `correlationId` saklanmadığı için karşılaştırılmaz. `tableId` de saklanmaz: **yalnız `tableId` değeri farklı olan bir tekrar `204` alır.** İlk kabul olayı, sipariş hazırlanmaya başladıktan veya iptal edildikten sonra da aynı olay sayılır.

## 5. İptal olayları

`OrderCancellationRequested`, `OrderCancelled`, `OrderItemsCancelled` ve `OrderCancellationRejected` için Kitchen yalnız kimliği ve restoran kapsamını doğrular, sonra `422` döner. Olayın diğer alanları işlenmez, hiçbir kayıt oluşmaz. Hiçbir olay kısmi iptali tüm siparişin iptaline çevirmez. Uygulanmayan olaya `204` dönülmez.

Kitchen'ın mevcut karar servisi yalnız **kendi açtığı** iptal talebinin kararını uygular. Bu servis `requestId` (Kitchen'ın kimliği), `orderVersion` ve `decidedAt` ister; Kitchen bunların hiçbirini uydurmaz.

### 5.1 Kesin tam iptal (henüz uygulanmıyor)

Belirlenen neden biçimi: `"reasons": [ { "code": "CHANGED_MIND" } ]`; neden yoksa `"reasons": []`. Serbest metin gönderilmez; sonradan eklenen neden ayrı olay üretmez. PR #47'nin Kitchen mesajında `reasons` alanı **henüz yoktur**. Bu biçimdeki mesaj `WAITING`, `PREPARING` ve `READY` durumlarının hepsinde `422` alır.

Bugünkü sonuçları:

- Order'da iptal kesinleşse de sipariş mutfak kuyruğunda kalır ve mutfak hazırlamaya devam edebilir.
- Bu siparişe sonradan gönderilecek hazırlık bilgisine Order `IGNORED_ORDER_CLOSED`, yeni bir Kitchen iptal talebine `ORDER_ALREADY_CANCELLED` döner. Daha önce işlenmiş aynı talebin tekrarında ilk karar geçerlidir.
- Uygulanmadan önce karar gereken konular: `READY` siparişin durumu (D-02), o sırada açık Kitchen iptal talebinin nasıl kapanacağı ve yerel talebi olmayan iptalin kalıcı tekilleştirilmesi için şema değişikliği.

### 5.2 Mutfak onayı bekleyen iptal (henüz uygulanmıyor)

Order bu talebi süre dolunca kendiliğinden reddetmez; 15 dakika yalnız yönetici görünümündeki dikkat süresidir. Talep açıkken hazırlık `READY`'ye ilerleyebilir ve `READY` talebi kapatmaz. Sonradan verilen onayı Order yeniden denetler ve `ORDER_ALREADY_PREPARED` ile reddedebilir. Açık talep varken Kitchen'ın yeni talebine `409 CANCELLATION_ALREADY_PENDING` ve mevcut `cancellationId` döner; karar o talep üzerinden beklenir.

Kitchen bugün Order'ın açık talebini **bilemez** (olay `422` alır). Bu yüzden aynı sipariş için yeni yerel talep kabul edebilir (`202`). Gönderim kurulduğunda Order bunu `409` ile reddeder ve yerel talebi kapatacak bir yol bugün yoktur. Bu akış kurulmadan Kitchen iptal talebi gönderimi açılmamalıdır.

## 6. Kitchen → Order

**Gönderim yoktur.** İstemci, zamanlayıcı, imzalama ve Kitchen özel anahtarı eklenmedi; olaylar Kitchen outbox'ında bekler ve teslim edilmiş işaretlenmez.

| Outbox olayı | Gövdede hazır olan |
|---|---|
| `KitchenPreparationStarted` | `preparationStatus: PREPARING`, `startedAt` |
| `KitchenOrderReady` | `preparationStatus: READY`, `startedAt`, `readyAt` |
| `KitchenCancellationRequested` | `requestId`, `orderId`, `reasonCode` (`OUT_OF_STOCK`, `CUSTOMER_REQUEST`, `KITCHEN_ERROR`, `OTHER`), stok nedeninde `productId`, `orderItemId`; **yalnız `OTHER`'da** `reasonText` (diğer kodlarda alan hiç yoktur) |

Her olayın `eventId` değeri saklanır; tekrar denemede aynı kalır. İptal talebi bütün siparişi kapsar. Order'a eşlemede `cancelAll: true` ve `OUT_OF_STOCK` → `ITEM_UNAVAILABLE` gönderim geliştirilirken uygulanacaktır; diğer üç kod Order kataloğunda aynı adla vardır.

**`OTHER` açıklaması.** Personel API'sinde (`POST /api/v1/kitchen/orders/{orderId}/cancellation-requests`) `OTHER` için `reasonText` zorunludur. Baştaki ve sondaki boşluklar atıldıktan sonra 1–300 karakter olmalıdır; uzunluk Order'daki gibi sayılır (bir emoji 2). Metin eksikse, boşsa, yalnız boşluksa (bölünmez boşluk dahil), 300'ü aşıyorsa ya da ASCII dışı kontrol karakteri veya eşsiz surrogate içeriyorsa yanıt `400 INVALID_REASON_TEXT` olur. ASCII kontrol karakteri ve 500'ü aşan metin genel doğrulamada `code` alanı olmayan `400` alır. İki durumda da kayıt oluşmaz. Diğer kodlarda metin isteğe bağlıdır (en çok 500) ve Kitchen'da kalır. (5.1'deki "serbest metin gönderilmez" kuralı yalnız Order → Kitchen tam iptal mesajı içindir.)

> **Metni misafir okuyabilir.** Gönderim kurulduğunda `OTHER` açıklamasını Order'daki iptal ayrıntısında **siparişin sahibi olan misafir** ile aynı restoranın yetkili garsonu ve yöneticisi okuyabilir. Metin için ayrı bir otomatik silme süresi görülmemiştir. Açıklamaya kişisel bilgi veya mutfak içi not yazılmamalıdır. Personel ekranında bu uyarı henüz yoktur; bu çalışmada frontend değiştirilmedi.

Bu kuraldan önce açıklamasız kaydedilmiş bir `OTHER` talebinin olayında metin yoktur ve Order onu reddeder; sonradan metin üretilmez. Aynı `Idempotency-Key` ile açıklamasız tekrar artık `400` alır.

Sıra kuralı hazırdır:

- Bir siparişin `KitchenOrderReady` kaydı, aynı siparişin `KitchenPreparationStarted` kaydı Order hedefine **teslim edilmeden** gönderime alınmaz.
- Hazırlık olayı tekrar beklerken veya kalıcı olarak başarısızken `READY` bekler ve deneme hakkı harcamaz. Mutfaktaki `READY` durumu değişmez.
- Başka siparişler, başka restoranlar ve bildirim teslimi etkilenmez.

### 6.1 Order'ın iptal talebi yanıtı

PR #47, Kitchen'ın **kendi** iptal talebinin sonucunu Kitchen'a sonradan olay olarak göndermez; sonuç yalnız HTTP yanıtında gelir. Bu yanıt için yerel doğrulama hazırdır (`OrderCancellationReceipt`); onu çağıran bir istemci yoktur.

| Yanıt | Yerel sonuç |
|---|---|
| `outcome` (`APPLIED` veya `REJECTED`), eşleşen `orderId`, `cancellationId`, `scope: FULL`, `orderVersion`, `decidedAt`; rette `rejectionCode` | Karar mesajı oluşur; karar servisinin kuralları uygulanır (eski sürüm uygulanmaz; `READY` sipariş iptal edilmez, uzlaştırma kaydı oluşur) |
| Bu alanlardan biri eksik (72 saat sonrasındaki tekrar yanıtı dahil) | **Eksik:** karar oluşmaz, hiçbir kayıt değişmez, talep `REQUESTED` kalır |
| Başka sipariş, kısmi kapsam, bilinmeyen sonuç veya onayda `rejectionCode` | **Uyuşmazlık:** hiçbir şey uygulanmaz |

Yanıtın kendi olay kimliği olmadığı için karar, gönderilen Kitchen olayının `eventId` değeriyle tekilleştirilir; aynı yanıtın tekrarı ikinci kez uygulanmaz.

**Kalan risk:** Eksik yanıtta `outcome: APPLIED` ise Order siparişi iptal etmiştir, Kitchen ise siparişi aktif görmeye devam eder. Bu durum yalnız talebin `REQUESTED` kalmasından anlaşılır; uzlaştırmayı kimin nasıl yapacağı belirlenmemiştir. Order'ın `eventId` ile ilk karar bağını inbox saklama süresi boyunca koruma önerisi uygulanırsa geç tekrar da eksiksiz olabilir; bu öneri henüz uygulanmamıştır.

### 6.2 Gönderim geliştirilirken yanıtların eşlenmesi (öneri, uygulanmadı)

| Order'ın yanıtı | Kitchen teslim kaydı |
|---|---|
| Hazırlık olayına `200`: `APPLIED`, `IGNORED_STALE`, `IGNORED_ORDER_CLOSED` | Teslim edildi |
| İptal talebine eksiksiz `200` | Karar önce yerelde uygulanır, sonra teslim edildi |
| İptal talebine eksik veya uyuşmayan `200` | Teslim edildi sayılmaz; görünür bir hata koduyla (ör. `ORDER_RECEIPT_INCOMPLETE`) uzlaştırma bekler |
| `409 ORDER_ALREADY_CANCELLED`, `409 CANCELLATION_ALREADY_PENDING` | Başarısız; hata kodu saklanır. Yerel talebin kapanışı 5.1 ve 5.2'deki kararlara bağlıdır |
| Zaman aşımı, bağlantı hatası, `5xx` | Sonuç belirsiz; aynı `eventId` ile yeniden denenir |

## 7. Doğrulamanın sınırı

Davranış Kitchen'ın yerel testleriyle doğrulandı: test amaçlı imzalayıcı, test sırasında üretilen anahtarlar ve gerçek sunucuda geçici sertifikalı TLS. Order yanıtları testlerde PR #47'nin yanıt biçimine göre elle yazıldı. PR #47'nin imzalayıcı sınıfının ürettiği kimlik, önceki bir yerel denemede Kitchen doğrulayıcısından geçti. **Çalışan bir Order servisine bağlanılmadı;** gerçek mesaj akışı, anahtar dağıtımı ve ortak ortam doğrulanmadı.

Yukarıdaki `OTHER` kuralı dışında personel ekranı uçları bu belgenin kapsamında değildir.

---

## Kitchen → Order olay teslimatı (KITCHEN-INT-03)

- **Tarih:** 27 Kasım 2026
- **Dayanak:** `dev` `49aa16a` üzerindeki Order uçları. Servis kimliği ve Order'ın Kitchen'a gönderdiği iptal isteği yalnız birleşmemiş [PR #47](https://github.com/software-lecture-labs/Restofy/pull/47)'de yazılı; bu iki konu için o sözleşme kullanıldı.
- **Durum:** Kitchen tarafı yazıldı ve yerel bir HTTP test sunucusuyla test edildi. İmza, PR #47'nin Order doğrulayıcısıyla izole bir çalışma alanında denendi. **Çalışan bir Order servisiyle doğrulanmadı.**
- **Order'dan beklenenler:** [kitchen-order-requests-01.md](integration/kitchen-order-requests-01.md)

### 1. Akışların durumu

| Akış | Kitchen'da | Açılması için kalan |
|---|---|---|
| A. Hazırlık başladı (`PREPARING`) | Gönderim ve imza hazır | Order doğrulayıcısı `dev`'de (KO3-01), anahtar değişimi, HTTPS |
| B. Hazır (`READY`) | Gönderim ve imza hazır; **sıra koruması yok** | A'dakiler; `READY`'nin `PREPARING` teslimine kadar bekletilmesi (Kitchen PR #77) |
| C. Kitchen'ın kendi iptal talebi | Gövde eşlemesi hazır, ama **talepler gönderilmez, bekletilir** (§4) | Order cevabının doğrulanıp uygulanması (doğrulama Kitchen PR #77'de); `OTHER` açıklaması (PR #77); A'dakiler |
| D. Order isteğine Kitchen'ın onay/ret cevabı | Kayıt, personel ucu, cevap olayı ve gönderim hazır | Order'ın isteği Kitchen'a göndermesi (KO3-02); isteği alan Kitchen ucu (PR #77); A'dakiler |

Order'ın kesinleşmiş iptal olaylarının (`OrderCancelled`, `OrderItemsCancelled`, `OrderCancellationRejected`) Kitchen'da işlenmesi bu işin kapsamında değildir (Order → Kitchen yönü). Bu olayların alıcısı PR #77'de; işlenmeleri sonraki bir Kitchen işidir ve `READY` + iptal durumu için D-02 kararı gerektirir.

### 2. Olay → uç → gövde eşlemesi

Kaynak, Kitchen outbox'ındaki `ORDER` hedefli teslimatlardır. `NOTIFICATION` teslimatlarına dokunulmaz.

| Kitchen işlemi | Outbox olayı | Order ucu (`/api/v1/internal/order-events`) | Gönderilen alanlar |
|---|---|---|---|
| Hazırlığı başlat | `KitchenPreparationStarted` | `POST /kitchen` | `eventId`, `restaurantId`, `orderId`, `kitchenStatus: "PREPARING"`, `occurredAt` |
| Hazır işaretle | `KitchenOrderReady` | `POST /kitchen` | aynı alanlar, `kitchenStatus: "READY"` |
| İptal talebi (**bekletilir**) | `KitchenCancellationRequested` | `POST /kitchen-cancellation-request` | `eventId`, `restaurantId`, `orderId`, `cancelAll: true`, `reasonCodes` (tek kod) |
| Order isteğine cevap | `KitchenCancellationDecided` | `POST /kitchen-cancellation` | `eventId`, `restaurantId`, `orderId`, `cancellationId`, `decision` (`APPROVED`/`REJECTED`), rette `rejectionCode: "KITCHEN_DECLINED"` |

Neden kodları: `OUT_OF_STOCK` → `ITEM_UNAVAILABLE`; `CUSTOMER_REQUEST` ve `KITCHEN_ERROR` aynen. `OTHER` için Order açıklama zorunlu tutar ve kayıtlı Kitchen olayı açıklama taşımaz; bekletme kalktığında `OTHER` talebi gönderilmez ve `FAILED` (`REASON_TEXT_MISSING`) olur.

Ret kodu: Order bir kod zorunlu tutar ve sözleşmesinde örnek olarak `KITCHEN_DECLINED` verir. Kitchen'da ret nedeni kataloğu yoktur; personel yalnız evet/hayır seçer. Ret notu (`rejectionNote`) gönderilmez.

Order bilinmeyen alanı reddettiği için gövdeye başka alan eklenmez. Ürün, personel kimliği, iç not ve fiyat gönderilmez.

Başlıklar: `Authorization: Service <token>`, `Content-Type: application/json`, `Accept: application/json` ve olayda varsa `X-Correlation-Id`. Order tekrar eden teslimi gövdedeki `eventId` ile tanır.

### 3. Güvenlik

| Konu | Uygulanan |
|---|---|
| Servis kimliği | `OrderRequestSigner`: her denemede yeni bir RS256 token. Claim'ler PR #47'nin Kitchen sözleşmesindeki değerler: `iss=restofy-kitchen`, `sub=kitchen-service`, `aud=restofy-order`, `scope=order:kitchen-events`, `token_type=service`, UUID `jti`, `iat = nbf`, `exp = iat + 30 sn`. İsteğe bağlanan claim'ler: `restaurant_id`, `method=POST`, `target`, `body_sha256`. Token başka restoran, yol veya gövde için kullanılamaz. |
| Anahtar | Yalnız Kitchen'da: `KITCHEN_ORDER_SIGNING_PRIVATE_KEY` (Base64 PKCS#8 RSA, en az 2048 bit) ve `KITCHEN_ORDER_SIGNING_KEY_ID`. Eksik veya geçersizse profil açılmaz; hata mesajı değeri tekrar etmez. Order'a yalnız açık anahtar ve `kid` verilir. |
| Taşıma | `ORDER_SERVICE_URL` yalnız yolsuz bir HTTPS adresi olabilir. Düz HTTP yalnız `localhost`, `127.0.0.1` ve `::1` için kabul edilir. Sertifika ve hostname doğrulaması Java varsayılanıdır, kapatılmaz. PR #47'deki Order doğrulayıcısı da Kitchen isteklerini yalnız HTTPS ile kabul eder. |
| Yönlendirme, yanıt | Yönlendirme izlenmez. Yanıtın en çok 16 KiB'ı okunur. |
| Loglar | Anahtar, token, istek gövdesi ve yanıt gövdesi loglanmaz. Saklanan hata kodu yalnız HTTP durumu ve Order'ın büyük harfli hata kodudur. |
| CSRF, çerez, `Origin` | Uygulanmaz: tarayıcı oturumu kullanılmayan servis çağrısıdır; çerez ve `Origin` gönderilmez. |
| Personel ucu (D) | `GET` KITCHEN ve ADMIN, `POST` yalnız KITCHEN. Mevcut güvenlik zinciri değişmedi. Restoran ve kullanıcı doğrulanmış kimlikten alınır; başka restoranın isteği `404` döner. `Idempotency-Key` zorunlu. |
| Restoran sınırı | Giden isteklerde `restaurantId` kayıtlı outbox olayından gelir ve imzaya bağlıdır. |

### 4. Teslim onayı ile iş kararı ayrımı

- Teslimat yalnız `200` **ve** sözleşmeye uyan gövde ile `DELIVERED` olur. Gövdedeki `orderId` gönderilenle aynı olmalı, `outcome` da ucun tanımladığı bir değer olmalı:
  - `/kitchen`: `APPLIED`, `IGNORED_STALE`, `IGNORED_ORDER_CLOSED`.
  - `/kitchen-cancellation`: `APPLIED`, `IGNORED_STALE`; ayrıca gövdedeki `cancellationId` aynı olmalı.
- Teslimat durumu yalnız `outbox_deliveries` satırını değiştirir. Hazırlık durumu, geçmiş, talepler ve inbox değişmez.
- **Kitchen talepleri bekletilir (kod koruması).** Order, Kitchen'ın kendi talebini aldığı anda karara bağlar ve karar yalnız o yanıttadır. Kitchen bu yanıtı henüz uygulayamadığı için HTTP göndericisi `KitchenCancellationRequested` olaylarını almaz (`heldEventTypes`):
  - Bu teslimatlar seçilmez ve sahiplenilmez; `PENDING` kalır ve deneme harcamaz.
  - Talep Kitchen'da `REQUESTED` görünür.
  - Bekletme ancak yanıt doğrulanıp `OrderDecisionService.apply` ile aynı akışta uygulandığında kaldırılmalı. Kaldırmadan önce birikmiş talepler incelenmeli.
- **D cevabı iptal değildir.** `APPROVED`, mutfağın kabul ettiğini söyler; Order siparişi o anki durumuna göre yeniden değerlendirir ve kendisi de reddedebilir. Kitchen siparişi değişmez. Kesin sonuç Order'ın sonraki olayıyla gelir; o olayın işlenmesi sonraki bir iştir (§1).
- Kitchen'da bir siparişi `CANCELLED` yapan tek yol `OrderDecisionService.apply` olarak kalır.

### 5. Yanıt sınıflandırması

| Order'ın cevabı | Teslimat |
|---|---|
| `200` + uygun gövde | `DELIVERED` |
| `400 INVALID_REQUEST`, `415 UNSUPPORTED_MEDIA_TYPE`, `422 VALIDATION_ERROR` / `UNKNOWN_ORDER_ITEM` / `CANCEL_QUANTITY_EXCEEDED`, `409 IDEMPOTENCY_CONFLICT` / `ORDER_ALREADY_CANCELLED` | Hemen `FAILED` |
| `409 INVALID_STATUS_TRANSITION` (eksik adım; Order bunu hatırlamaz) | Yeniden denenir |
| `404 ORDER_NOT_FOUND` / `CANCELLATION_NOT_FOUND`, diğer `409`, `401`, `403`, `408`, `429`, `5xx`, `3xx` | Yeniden denenir |
| Order'ın hata kodunu taşımayan hata gövdesi | Yeniden denenir (cevap aradaki bir bileşenden gelmiş olabilir) |
| `200` ama boş, okunamayan, büyük veya uyumsuz gövde; `200` dışındaki `2xx` | Yeniden denenir |
| Zaman aşımı, bağlantı hatası, yanıtsız kapanan bağlantı | Yeniden denenir |
| Okunamayan kayıtlı olay (bilinmeyen tür, bozuk gövde) | Gönderilmez; hemen `FAILED` (`UNREADABLE_EVENT`) |

### 6. Idempotency ve sıralama

- Teslim **en az bir kez** modelidir; "tam bir kez" garantisi yoktur. Cevap kaybolursa veya gönderici sonucu yazmadan durursa aynı olay yeniden gider.
- Yeniden denemede ve elle yeniden sıraya almada `eventId` ve gövde baytları değişmez (testli). Token her denemede yeniden üretilir.
- Order'ın aynı `eventId`'yi tek kez işlediği **çalışan Order ile doğrulanmadı**.
- Aynı komutun tekrarı yeni olay üretmez:
  - Hazırlık komutları: mevcut davranış.
  - D cevabı: aynı `Idempotency-Key` ve aynı cevap `200` döner ve ikinci olay üretmez. Aynı anahtar başka bir cevap için `409 COMMAND_ID_CONFLICT`, cevaplanmış istek `409 CANCELLATION_ALREADY_ANSWERED` döner.
  - Veritabanı her isteğe tek cevap olayı izin verir.
- Order'ın aynı isteği yeniden göndermesi (D kaydı) `eventId` ile tanınır: aynı içerik tekrar sayılır, farklı içerik çakışmadır.
- **Sıra:** Gönderici vadesi gelenleri en eski vade önce gönderir.
  - `PREPARING` yeniden deneme beklerken `READY` denenebilir. Order eksik adımı `409 INVALID_STATUS_TRANSITION` ile reddettiği için durum atlanmaz, ama `READY` her ret alışında bir deneme harcar ve `PREPARING` uzun süre ulaşamazsa `FAILED` olabilir.
  - Koruma PR #77'de; bu branch'e alınmadı. O zamana kadar `FAILED` bir `READY`, §7'deki betikle `PREPARING`'den sonra yeniden sıraya alınabilir.
  - D cevabının diğer olaylarla bir sıra bağı yoktur; Order cevabı siparişin o anki durumuyla değerlendirir.
- Bir siparişin teslim hatası başka siparişleri durdurmaz.

### 7. Yeniden deneme, zaman aşımı ve yeniden sıraya alma

- Gecikmeler: 5, 10, 20, 40, 80, 160, 320 saniye. 8. deneme de başarısız olursa teslimat `FAILED` (`MAX_ATTEMPTS_EXCEEDED`).
- **Sınır:** Bekleme sürelerinin toplamı **10 dakika 35 saniyedir** (istek süreleri ve zamanlayıcı aralığı hariç). Order bundan uzun süre kapalı kalırsa bekleyen teslimatlar `FAILED` olur. Kayıt ve olay silinmez.
- Başarısızları görmek (Kitchen veritabanında, salt okunur):

  ```sql
  SELECT d.id, d.restaurant_id, e.event_type, e.aggregate_id, e.id AS event_id,
         d.attempt_count, d.last_error_code, d.last_attempt_at
  FROM outbox_deliveries d JOIN outbox_events e ON e.id = d.event_id
  WHERE d.destination = 'ORDER' AND d.status = 'FAILED'
  ORDER BY e.restaurant_id, e.occurred_at;
  ```

- **Yeniden sıraya alma:** Neden giderildikten sonra Kitchen veritabanında yazma yetkisi olan operatör, tek bir teslimatı [`requeue-order-delivery.sql`](../../backend/services/kitchen-service/ops/requeue-order-delivery.sql) ile `PENDING`'e döndürür:

  ```text
  psql -X -v ON_ERROR_STOP=1 -d <KITCHEN_DATABASE> -v restaurant_id=<RESTAURANT_UUID> -v event_id=<EVENT_UUID> -f backend/services/kitchen-service/ops/requeue-order-delivery.sql
  ```

  - **Kapsam:** yalnız verilen restorana ait, `ORDER` hedefli ve `FAILED` durumundaki teslimat değişir. Olay, `eventId` ve gövde aynı kalır.
  - **Sonuç:** önceki deneme sayısı, hata kodu ve işlemi yapan veritabanı kullanıcısı döner; bunları olay kaydına ekleyin. Satır dönmezse yeniden sıraya alınacak bir teslimat yoktur.
  - **Sıra:** aynı siparişte önce `PREPARING`, sonra `READY` yeniden sıraya alınır.
- Her denemeden önce teslimat satır kilidiyle 60 saniyeliğine sahiplenilir. HTTP çağrısı sırasında transaction ve kilit açık değildir; bu yüzden birden çok örnek aynı anda çalışabilir ve duran bir göndericinin işi süre dolunca yeniden alınır.
- İstemci kendi başına yeniden denemez. Okuma zaman aşımı yanıt gövdesini de kapsar.
- Kitchen komutları Order'ı beklemez; Order kapalıyken de işlemler başarılı olur ve olay outbox'ta kalır.

### 8. D akışı: Order isteğine mutfak cevabı

1. **İsteğin kaydı:** Order, `PREPARING` bir siparişin iptalini mutfağa sorduğunda (`kitchenConfirmationRequired=true`) istek `OrderCancellationConfirmationService.register` ile kaydedilir (`V90__create_order_cancellation_confirmations.sql`). Bu çağrıyı yapacak gelen uç PR #77'de; bu yüzden **üretimde henüz çağıranı yok**. Kurallar:
   - Önce sipariş satırı kilitlenir.
   - Biçim (tür, kapsam, kalemler), sipariş ve kalemlerin bu siparişe ait olması denetlenir.
   - Aynı olay tekrarı yeni kayıt açmaz; aynı kimlikle farklı içerik çakışmadır.
   - İptal edilmiş sipariş kaydedilmez.
   - Bir siparişte aynı anda tek bekleyen istek olabilir.
   - Reddedilen istek hiçbir şey yazmaz.
2. **Görüntüleme:** `GET /api/v1/kitchen/orders/{orderId}/order-cancellations` siparişin isteklerini en yeniden eskiye listeler: kapsam (`FULL`/`PARTIAL`), istenen kalem ve adetler, durum ve cevap.
3. **Cevap:** `POST /api/v1/kitchen/orders/{orderId}/order-cancellations/{cancellationId}/answer` ile `{"decision":"APPROVED"}` veya `{"decision":"REJECTED"}` gönderilir; `Idempotency-Key` zorunlu. İlk cevap `202`, aynı cevabın tekrarı `200` döner. Cevap ve `KitchenCancellationDecided` olayı aynı transaction'da yazılır.
4. **Kalıcılık:** İstenen alanlar değişmez, cevap kesindir, satır silinmez (veritabanı tetikleyicileri). Mutfak siparişinin durumu değişmez.

Kısmi (`PARTIAL`) bir isteğe verilen cevap da yalnız bir cevaptır. Kısmi iptalin mutfak ekranına yansıması D-02'ye bağlıdır ve bu işte yapılmadı.

### 9. Yapılandırma

| Ayar | Tür | Not |
|---|---|---|
| `kitchen-order` Spring profili | `SPRING_PROFILES_ACTIVE` | Gönderici, imzalayıcı ve zamanlayıcı yalnız bu profille oluşur |
| `KITCHEN_ORDER_SIGNING_KEY_ID`, `KITCHEN_ORDER_SIGNING_PRIVATE_KEY` | **yeni ENV** | Kitchen'ın imza anahtarı. `.env.example`'da değersiz. `docker-compose` bu değerleri geçirmez, çünkü oradaki Order adresi HTTP'dir ve bu profil orada çalışamaz. |
| `ORDER_SERVICE_URL` | mevcut ENV | HTTPS gerekir. `.env.example` içindeki yerel değer `http://order-service:8080` bu profille reddedilir. |
| `REST_CLIENT_CONNECT_TIMEOUT_MS`, `REST_CLIENT_READ_TIMEOUT_MS` | mevcut ENV | Varsayılan 3000 / 5000. Pozitif ve toplamı 60 saniyeden kısa olmalı. |
| `restofy.kitchen.order-delivery.interval` | düz uygulama ayarı | Varsayılan `PT2S`; her turda en çok 20 teslimat |

Yeni bağımlılık yok. Migration: `V90` (Kitchen aralığının son numarası; bundan sonra Kitchen migration'ı için aralık kararı gerekir).

### 10. Açmadan önce

1. Order'da Kitchen doğrulayıcısı `dev`'de, Kitchen'ın açık anahtarı ve `kid` Order'a tanımlı (KO3-01).
2. `READY` sıra koruması (PR #77).
3. Order için HTTPS adresi ve iki servis arasında saat eşitlemesi.
4. Birikmiş `PENDING` olaylar incelendi; profil açılınca hepsi gönderilmeye başlar.

D akışı için ayrıca KO3-02 ve PR #77'deki alıcı gerekir. Kitchen talepleri (C) için §4'teki koşullar gerekir. Gerçek siparişlerle uçtan uca deneme için sipariş kabulünün Kitchen'a ulaşması (PR #77) ve personel oturumu ([PR #61](https://github.com/software-lecture-labs/Restofy/pull/61)) da gerekir. PR #61 birleştiğinde yeni `GET .../order-cancellations` ucu, oradaki personel okuma listesine eklenmelidir; eklenmezse yazma gibi oturum teyidine girer.

### 11. Doğrulama

`backend/` klasöründe (Docker çalışırken):

```text
.\services\kitchen-service\mvnw.cmd -B -ntp -f pom.xml -pl services/kitchen-service -am clean test
```

| Test sınıfı | Gösterdiği |
|---|---|
| `HttpOrderEventSinkTests` | Gerçek `RestClient` ile uç, başlık, gövde, yanıt sınıflandırması, zaman aşımı, bağlantı hatası, yönlendirme; D cevabının eşlemesi |
| `OrderRequestSignerTests` | Token alanları, süre, isteğe bağlama, anahtar ayarı hataları |
| `OrderHttpDeliveryIntegrationTests` | PostgreSQL üzerinde komut → outbox → HTTP: onay, kayıp cevap, kesinti, deneme sınırı, yeniden sıraya alma betiği, talep bekletme, okunamayan olay, paralel gönderici, duran gönderici, D cevabının teslimi |
| `OrderDeliveryDispatcherTests` | `kitchen-order` profiliyle zamanlanmış teslim, gerçek imzalayıcı, her denemede yeni token |
| `OrderCancellationConfirmationServiceTests`, `KitchenOrderCancellationApiTests` | D kaydı kuralları, personel ucu, yetki, idempotency, veritabanı korumaları |
| `OrderDeliveryConfigurationTests` | Profil, anahtar, adres ve zaman aşımı kuralları |

**Geçici çapraz doğrulama (commit edilmedi):** Kitchen'ın derlenmiş imzalayıcısı, PR #47'nin Order `KitchenServiceIdentityVerifier` ve `ServiceAuthenticationFilter` sınıflarıyla izole bir çalışma alanında denendi:

- Doğru restoran: kabul edildi.
- İzin verilmeyen restoran ve değiştirilmiş gövde: reddedildi.

Test sunucusu ve bu deneme çalışan Order Service değildir. Gerçek Order'ın idempotent işleyişi ve durum sıralaması doğrulanmadı.
