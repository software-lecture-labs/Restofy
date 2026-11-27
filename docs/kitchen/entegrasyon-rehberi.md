# Kitchen entegrasyon rehberi

## Kitchen → Order olay teslimatı (KITCHEN-INT-03)

- **Tarih:** 27 Kasım 2026
- **Dayanak:** `dev` `49aa16a` üzerindeki Order sözleşmesi
- **Durum:** Kitchen göndericisi yazıldı ve yerel bir HTTP test sunucusuyla test edildi. **Hiçbir ortamda açılamaz ve gerçek Order Service ile doğrulanmadı.**
- **Order'dan beklenenler:** [kitchen-order-requests-01.md](integration/kitchen-order-requests-01.md)

### 1. Akışların durumu

| Akış | Durum | Eksik olan |
|---|---|---|
| A. Hazırlık başladı (`PREPARING`) | Gönderim kodu hazır | Servis kimliği |
| B. Hazır (`READY`) | Gönderim kodu hazır; **sıra koruması yok** | Servis kimliği; Kitchen'ın `READY`'yi `PREPARING` teslimine kadar bekletmesi |
| C. Kitchen iptal talebi | `OUT_OF_STOCK`, `CUSTOMER_REQUEST`, `KITCHEN_ERROR` için gönderim kodu hazır; **`OTHER` gönderilemez**; **Order'ın kararı Kitchen'a işlenmez** | Servis kimliği; `OTHER` açıklaması; kararın uygulanması |
| D. Kitchen'ın onay/ret kararı | **Uygulanmadı** | Order'ın talebini alan uç, kalıcı kayıt, personel cevap işlemi, giden olay; Order'ın teslimi (KO3-02) |

B ve C'deki Kitchen eksiklerinin bir bölümü [PR #77](https://github.com/software-lecture-labs/Restofy/pull/77) içindedir ve bu branch'e alınmadı:

| Eksik | #77'deki karşılığı |
|---|---|
| `READY`, `PREPARING` teslim edilene kadar bekler | `OutboxDeliveryRepository.isWaitingForPreparing`, `findDueIds` filtresi, `OutboxDeliveryService` claim kontrolü |
| `OTHER` açıklaması | `KitchenCancellationRequestedMessage.Payload.reasonText` ve istek doğrulaması |
| Order cevabının karara dönüştürülmeden önce doğrulanması | `OrderCancellationReceipt` (yalnız doğrulama; karar servisine bağlama #77'de de yok) |

### 2. Olay → uç → gövde eşlemesi

Kaynak, Kitchen outbox'ındaki `ORDER` hedefli teslimatlardır. `NOTIFICATION` teslimatlarına dokunulmaz.

| Kitchen işlemi | Outbox olayı | Order ucu (`/api/v1/internal/order-events`) | Gönderilen alanlar |
|---|---|---|---|
| Hazırlığı başlat | `KitchenPreparationStarted` | `POST /kitchen` | `eventId`, `restaurantId`, `orderId`, `kitchenStatus: "PREPARING"`, `occurredAt` |
| Hazır işaretle | `KitchenOrderReady` | `POST /kitchen` | aynı alanlar, `kitchenStatus: "READY"` |
| İptal talebi | `KitchenCancellationRequested` | `POST /kitchen-cancellation-request` | `eventId`, `restaurantId`, `orderId`, `cancelAll: true`, `reasonCodes` (tek kod) |

Neden kodları: `OUT_OF_STOCK` → `ITEM_UNAVAILABLE`; `CUSTOMER_REQUEST` ve `KITCHEN_ERROR` aynen. `OTHER` için Order açıklama zorunlu tutar, kayıtlı Kitchen olayı açıklama taşımaz: istek **hiç gönderilmez**, teslimat `FAILED` (`REASON_TEXT_MISSING`) olur. Metin uydurulmaz.

Order bilinmeyen alanı reddettiği için gövdeye başka alan eklenmez. Ürün, kalem, personel kimliği, iç not ve fiyat gönderilmez; `requestedByStaffId` isteğe bağlıdır ve gönderilmez.

Başlıklar: `Authorization: Service <credential>`, `Content-Type: application/json`, `Accept: application/json` ve olayda varsa `X-Correlation-Id`. Order tekrar eden teslimi yalnız gövdedeki `eventId` ile tanır; `Idempotency-Key` gönderilmez.

### 3. Güvenlik

| Konu | Uygulanan |
|---|---|
| Servis kimliği | `OrderServiceCredentials` arayüzü isteğin restoranını, yolunu ve gövde baytlarını alır. **Üretim uygulaması yoktur** (D-09 açık). Kimlik bilgisi alınamazsa istek gönderilmez. |
| Taşıma | `ORDER_SERVICE_URL` yalnız yolsuz bir HTTPS adresi olabilir. Düz HTTP yalnız `localhost`, `127.0.0.1` ve `::1` için kabul edilir. Sertifika ve hostname doğrulaması Java varsayılanıdır, kapatılmaz. |
| Yönlendirme | İzlenmez; kimlik bilgisi başka adrese gitmez. |
| Yanıt | En çok 16 KiB okunur. |
| Loglar | Kimlik bilgisi, istek gövdesi ve yanıt gövdesi loglanmaz. Saklanan hata kodu yalnız HTTP durumu ve Order'ın büyük harfli hata kodudur. |
| CSRF, çerez, `Origin` | Uygulanmaz: tarayıcı oturumu kullanılmayan servis çağrısıdır; çerez ve `Origin` gönderilmez. |
| RBAC, personel JWT'si | Uygulanmaz: personelin yetkisi olayı doğuran Kitchen komutunda denetlenir; personel tokenı Order'a taşınmaz. |
| Restoran sınırı | `restaurantId` istek anındaki bir parametreden değil, kayıtlı outbox olayından gelir. Order siparişi bu restoranda bulamazsa `404` döner. |
| Yeni gelen uç | Eklenmedi; Kitchen'ın güvenlik zinciri değişmedi. |

### 4. Teslim onayı ile iş kararı ayrımı

- Teslimat yalnız `200` **ve** sözleşmeye uyan gövde ile `DELIVERED` olur: gövdedeki `orderId` gönderilenle aynı olmalı ve `outcome` ucun tanımladığı bir değer olmalıdır (`/kitchen`: `APPLIED`, `IGNORED_STALE`, `IGNORED_ORDER_CLOSED`; iptal talebi: `APPLIED`, `REJECTED`).
- Teslimat durumu yalnız `outbox_deliveries` satırını değiştirir. Hazırlık durumu, geçmiş, iptal talebi ve inbox değişmez.
- İptal talebinin cevabında `APPLIED`, Order'ın siparişi iptal ettiğini; `REJECTED`, iptal etmediğini söyler. İkisi de "istek ulaştı ve işlendi" demektir. **Kitchen bu kararı uygulamaz:** sipariş mutfakta aktif, talep `REQUESTED` kalır. Order sonradan ayrıca olay göndermez; karar yalnız bu cevaptadır. Kitchen bu durumda sipariş, talep ve olay kimliğiyle bir `WARN` logu yazar. Bu log uzlaştırma kaydının yerini tutmaz.
- Kitchen'da bir siparişi `CANCELLED` yapan tek yol `OrderDecisionService.apply` olarak kalır.

### 5. Yanıt sınıflandırması

| Order'ın cevabı | Teslimat |
|---|---|
| `200` + uygun gövde | `DELIVERED` |
| `400 INVALID_REQUEST`, `415 UNSUPPORTED_MEDIA_TYPE`, `422 VALIDATION_ERROR` / `UNKNOWN_ORDER_ITEM` / `CANCEL_QUANTITY_EXCEEDED`, `409 IDEMPOTENCY_CONFLICT` / `ORDER_ALREADY_CANCELLED` | Hemen `FAILED` |
| `409 INVALID_STATUS_TRANSITION` (eksik adım; Order bunu hatırlamaz) | Yeniden denenir |
| `409 CANCELLATION_ALREADY_PENDING`, diğer `409` | Yeniden denenir |
| `401`, `403`, `404`, `408`, `429`, `5xx`, `3xx` | Yeniden denenir |
| Order'ın hata kodunu taşımayan hata gövdesi | Yeniden denenir (cevap aradaki bir bileşenden gelmiş olabilir) |
| `200` ama boş, okunamayan, büyük veya uyumsuz gövde; `200` dışındaki `2xx` | Yeniden denenir |
| Zaman aşımı, bağlantı hatası, yanıtsız kapanan bağlantı | Yeniden denenir |

### 6. Idempotency ve sıralama

- Teslim **en az bir kez** modelidir; "tam bir kez" garantisi yoktur. Cevap kaybolursa veya gönderici sonucu yazmadan durursa aynı olay yeniden gider.
- Yeniden denemede `eventId` ve gövde baytları değişmez; ikisi de testle gösterilir. Kimlik bilgisi her denemede yeniden istenir.
- Order'ın aynı `eventId`'yi gerçekten tek kez işlediği **bu çalışmada doğrulanmadı**; test sunucusu bunu kanıtlayamaz.
- Aynı komutun tekrarı yeni olay üretmez (mevcut davranış, veritabanı kısıtıyla korunur).
- **Sıra:** Gönderici vadesi gelenleri en eski vade önce gönderir. `PREPARING` yeniden deneme beklerken `READY` denenebilir. Order eksik adımı `409 INVALID_STATUS_TRANSITION` ile reddedeceği için durum atlanmaz, fakat `READY` her reddedilişte bir deneme tüketir ve `PREPARING` uzun süre ulaşamazsa kalıcı olarak `FAILED` olabilir. Koruma [PR #77](https://github.com/software-lecture-labs/Restofy/pull/77) birleşmeden bu branch'te yoktur.
- Bir siparişin teslim hatası başka siparişleri durdurmaz. İşlenemeyen bir kayıt (okunamayan olay, kilit veya veritabanı hatası) atlanır ve değişmeden kalır.

### 7. Yeniden deneme ve zaman aşımı

- Gecikmeler: 5, 10, 20, 40, 80, 160, 320 saniye. 8. deneme de başarısız olursa teslimat `FAILED` (`MAX_ATTEMPTS_EXCEEDED`).
- **Sınır:** Bekleme sürelerinin toplamı **10 dakika 35 saniyedir** (istek süreleri ve zamanlayıcı aralığı hariç). Order bundan uzun süre kapalı kalırsa bekleyen teslimatlar `FAILED` olur. Kayıt ve olay silinmez, ancak **yeniden sıraya alan bir araç veya uç yoktur**.
- Başarısızları görmek (Kitchen veritabanında, salt okunur):

  ```sql
  SELECT d.id, d.restaurant_id, e.event_type, e.id AS event_id, d.attempt_count, d.last_error_code, d.last_attempt_at
  FROM outbox_deliveries d JOIN outbox_events e ON e.id = d.event_id
  WHERE d.destination = 'ORDER' AND d.status = 'FAILED' ORDER BY d.last_attempt_at;
  ```

- Her denemeden önce teslimat satır kilidiyle 60 saniyeliğine sahiplenilir; HTTP çağrısı sırasında transaction ve kilit açık değildir. Bu sayede birden çok örnek aynı anda çalışabilir ve duran bir göndericinin işi süre dolunca yeniden alınır.
- İstemci kendi başına yeniden denemez. Okuma zaman aşımı yanıt gövdesini de kapsar.
- Kitchen komutları Order'ı beklemez; Order kapalıyken de hazırlık işlemleri başarılı olur ve olay outbox'ta kalır.

### 8. Yapılandırma

| Ayar | Tür | Not |
|---|---|---|
| `kitchen-order` Spring profili | `SPRING_PROFILES_ACTIVE` | Gönderici ve zamanlayıcı yalnız bu profille oluşur |
| `ORDER_SERVICE_URL` | mevcut ENV | HTTPS gerekir. `.env.example` içindeki yerel değer `http://order-service:8080` bu profille reddedilir. |
| `REST_CLIENT_CONNECT_TIMEOUT_MS`, `REST_CLIENT_READ_TIMEOUT_MS` | mevcut ENV | Varsayılan 3000 / 5000. Pozitif ve toplamı 60 saniyeden kısa olmalı. |
| `restofy.kitchen.order-delivery.interval` | düz uygulama ayarı | Varsayılan `PT2S`; her turda en çok 20 teslimat |

Yeni ENV, bağımlılık veya migration eklenmedi. **Profil bugün başlamaz:** `OrderServiceCredentials` uygulaması olmadığı için uygulama açılışta açık bir hata verir. Profil kapalıyken olaylar `PENDING` olarak birikir.

### 9. Açmadan önce tamamlanması gerekenler

1. Order'da Kitchen kimlik doğrulaması (KO3-01) ve ona uygun Kitchen imzalayıcısı. Kitchen için yeni anahtar ayarı ve güven ilişkisi gerekir; [SECURITY.md](../SECURITY.md) uyarınca ayrıca onaylanmalıdır.
2. `READY` sıra koruması.
3. Order'ın iptal cevabının doğrulanıp Kitchen'da uygulanması. **Bu yapılmadan profil açılırsa kararlar kaybolur:** teslimat tamamlanmış sayılır ve istek yeniden gönderilmez.
4. `OTHER` açıklaması.
5. Order için HTTPS adresi (ortak ortam kararı).
6. Profil ilk açıldığında birikmiş `PENDING` olayların tamamı gönderilmeye başlar; açmadan önce incelenmelidir.

Gerçek siparişlerle uçtan uca deneme için ayrıca sipariş kabulünün Kitchen'a ulaşması ([PR #77](https://github.com/software-lecture-labs/Restofy/pull/77)) ve personel oturumunun çalışması ([PR #61](https://github.com/software-lecture-labs/Restofy/pull/61)) gerekir.

### 10. Doğrulama

`backend/` klasöründe (Docker çalışırken):

```text
.\services\kitchen-service\mvnw.cmd -B -ntp -f pom.xml -pl services/kitchen-service -am clean test
```

| Test sınıfı | Gösterdiği |
|---|---|
| `HttpOrderEventSinkTests` | Gerçek `RestClient` ile uç, başlık, gövde, yanıt sınıflandırması, zaman aşımı, bağlantı hatası, yönlendirme |
| `OrderHttpDeliveryIntegrationTests` | PostgreSQL üzerinde komut → outbox → HTTP: onay, kayıp cevap, kesinti, deneme sınırı, paralel gönderici, sonuç yazılmadan duran gönderici, iptal cevabının siparişi değiştirmemesi |
| `OrderDeliveryDispatcherTests` | `kitchen-order` profiliyle zamanlanmış teslim (kimlik bilgisi yalnız testte tanımlı) |
| `OrderDeliveryConfigurationTests` | Profil, adres ve zaman aşımı kuralları |

Test sunucusu Order Service değildir. Gerçek Order'ın kimlik doğrulaması, idempotent işleyişi ve durum sıralaması doğrulanmadı.
