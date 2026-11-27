# Kitchen entegrasyon rehberi

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
