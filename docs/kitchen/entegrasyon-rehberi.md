# Kitchen entegrasyon rehberi

## Kitchen → Order olay teslimatı (KITCHEN-INT-03)

- **Tarih:** 27 Kasım 2026
- **Dayanak:** `dev` `49aa16a` üzerindeki Order sözleşmesi. Birleşmemiş [PR #47](https://github.com/software-lecture-labs/Restofy/pull/47) bu uçların istek ve yanıt gövdelerini değiştirmiyor.
- **Durum:** Kitchen göndericisi yazıldı ve yerel bir HTTP test sunucusuyla test edildi. **Hiçbir ortamda açılamaz ve gerçek Order Service ile doğrulanmadı.**
- **Order'dan beklenenler:** [kitchen-order-requests-01.md](integration/kitchen-order-requests-01.md)

### 1. Akışların durumu

| Akış | Durum | Eksik olan |
|---|---|---|
| A. Hazırlık başladı (`PREPARING`) | Gönderim kodu hazır | Servis kimliği |
| B. Hazır (`READY`) | Gönderim kodu hazır; **sıra koruması yok** | Servis kimliği; `READY`'nin `PREPARING` teslimine kadar bekletilmesi |
| C. Kitchen iptal talebi | Gövde eşlemesi hazır ve testli, ama **talepler gönderilmez, bekletilir** (§4) | Order cevabının doğrulanıp uygulanması; `OTHER` açıklaması; servis kimliği |
| D. Kitchen'ın onay/ret kararı | **Uygulanmadı** | Order'ın talebini alan uç, kalıcı kayıt, personel cevap işlemi, giden olay; Order'ın teslimi (KO3-02) |

Kalan işlerin nerede çözüleceği:

| Eksik | Nerede |
|---|---|
| `READY`'nin `PREPARING` teslimine kadar bekletilmesi | Kitchen [PR #77](https://github.com/software-lecture-labs/Restofy/pull/77): `OutboxDeliveryRepository.isWaitingForPreparing`, `findDueIds` filtresi, claim kontrolü |
| `OTHER` açıklaması | Kitchen PR #77: `KitchenCancellationRequestedMessage.Payload.reasonText` ve istek doğrulaması |
| Order cevabının doğrulanması | Kitchen PR #77: `OrderCancellationReceipt` |
| Doğrulanan cevabın `OrderDecisionService` ile uygulanması ve bekletmenin kaldırılması | Kitchen; #77 birleştikten sonra (#77'de de yok) |
| D akışı | Kitchen; yeni gelen uç ve personel ucu, büyük olasılıkla `V90` ve ürün kararları gerektirdiği için **önce onay**. Order'dan gelen isteği doğrulayan alıcı PR #77'de. |
| Order'ın Kitchen kimliğini doğrulaması, `OrderCancellationRequested` teslimi | Order (KO3-01, KO3-02; PR #47'de hazırlanıyor) |
| Kitchen imzalayıcısı | Kitchen; KO3-01 `dev`'e girdikten sonra, yeni ENV ve güven ilişkisi için **önce onay** (§11) |
| Uzun kesinti sonrası `FAILED` teslimatın yeniden sıraya alınması | **Bu branch'te yapıldı** (§7) |

### 2. Olay → uç → gövde eşlemesi

Kaynak, Kitchen outbox'ındaki `ORDER` hedefli teslimatlardır. `NOTIFICATION` teslimatlarına dokunulmaz.

| Kitchen işlemi | Outbox olayı | Order ucu (`/api/v1/internal/order-events`) | Gönderilen alanlar |
|---|---|---|---|
| Hazırlığı başlat | `KitchenPreparationStarted` | `POST /kitchen` | `eventId`, `restaurantId`, `orderId`, `kitchenStatus: "PREPARING"`, `occurredAt` |
| Hazır işaretle | `KitchenOrderReady` | `POST /kitchen` | aynı alanlar, `kitchenStatus: "READY"` |
| İptal talebi (**bekletilir**) | `KitchenCancellationRequested` | `POST /kitchen-cancellation-request` | `eventId`, `restaurantId`, `orderId`, `cancelAll: true`, `reasonCodes` (tek kod) |

Neden kodları: `OUT_OF_STOCK` → `ITEM_UNAVAILABLE`; `CUSTOMER_REQUEST` ve `KITCHEN_ERROR` aynen. `OTHER` için Order açıklama zorunlu tutar ve kayıtlı Kitchen olayı açıklama taşımaz. Bekletme kalktığında `OTHER` talebi hiç gönderilmez ve `FAILED` (`REASON_TEXT_MISSING`) olur; metin uydurulmaz.

Order bilinmeyen alanı reddettiği için gövdeye başka alan eklenmez. Ürün, kalem, personel kimliği, iç not ve fiyat gönderilmez. İsteğe bağlı `requestedByStaffId` gönderilmez.

Başlıklar: `Authorization: Service <credential>`, `Content-Type: application/json`, `Accept: application/json` ve olayda varsa `X-Correlation-Id`. Order tekrar eden teslimi yalnız gövdedeki `eventId` ile tanır; `Idempotency-Key` gönderilmez.

### 3. Güvenlik

| Konu | Uygulanan |
|---|---|
| Servis kimliği | `OrderServiceCredentials` arayüzü isteğin restoranını, yolunu ve gövde baytlarını alır. **Üretim uygulaması yoktur** (D-09 açık, §11). Kimlik bilgisi alınamazsa istek gönderilmez. |
| Taşıma | `ORDER_SERVICE_URL` yalnız yolsuz bir HTTPS adresi olabilir. Düz HTTP yalnız `localhost`, `127.0.0.1` ve `::1` için kabul edilir. Sertifika ve hostname doğrulaması Java varsayılanıdır, kapatılmaz. |
| Yönlendirme | İzlenmez; kimlik bilgisi başka adrese gitmez. |
| Yanıt | En çok 16 KiB okunur. |
| Loglar | Kimlik bilgisi, istek gövdesi ve yanıt gövdesi loglanmaz. Saklanan hata kodu yalnız HTTP durumu ve Order'ın büyük harfli hata kodudur. |
| CSRF, çerez, `Origin` | Uygulanmaz: tarayıcı oturumu kullanılmayan servis çağrısıdır; çerez ve `Origin` gönderilmez. |
| RBAC, personel JWT'si | Uygulanmaz: personelin yetkisi olayı doğuran Kitchen komutunda denetlenir; personel tokenı Order'a taşınmaz. |
| Restoran sınırı | `restaurantId` kayıtlı outbox olayından gelir. Order siparişi bu restoranda bulamazsa `404` döner. |
| Yeni gelen uç | Eklenmedi; Kitchen'ın güvenlik zinciri değişmedi. |

### 4. Teslim onayı ile iş kararı ayrımı

- Teslimat yalnız `200` **ve** sözleşmeye uyan gövde ile `DELIVERED` olur: gövdedeki `orderId` gönderilenle aynı olmalı ve `outcome` ucun tanımladığı bir değer olmalıdır (`/kitchen`: `APPLIED`, `IGNORED_STALE`, `IGNORED_ORDER_CLOSED`).
- Teslimat durumu yalnız `outbox_deliveries` satırını değiştirir. Hazırlık durumu, geçmiş, iptal talebi ve inbox değişmez.
- **İptal talepleri bekletilir (kod koruması).** Order, Kitchen'ın kendi talebini aldığı anda karara bağlar (`APPLIED` = iptal edildi, `REJECTED` = edilmedi). Karar yalnız bu cevaptadır, sonradan olay gelmez. Kitchen bu cevabı henüz uygulayamadığı için talep gönderilirse sipariş Order'da iptal olur ama mutfakta aktif kalır. Bu yüzden HTTP göndericisi `KitchenCancellationRequested` olaylarını almaz (`heldEventTypes`). Bu teslimatlar seçilmez ve sahiplenilmez: `PENDING` kalır, deneme harcamaz ve başka teslimatların yerini tutmaz. Talep Kitchen'da `REQUESTED` görünür.
- Bekletme, Order cevabı doğrulanıp `OrderDecisionService.apply` ile aynı akışta uygulandığında kaldırılmalıdır. Kaldırılmadan önce birikmiş talepler incelenmelidir; eski bir talep geç gönderilirse Order onu o anki sipariş durumuna göre karara bağlar.
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
| Okunamayan kayıtlı olay (bilinmeyen tür, bozuk gövde) | Gönderilmez; hemen `FAILED` (`UNREADABLE_EVENT`) |

### 6. Idempotency ve sıralama

- Teslim **en az bir kez** modelidir; "tam bir kez" garantisi yoktur. Cevap kaybolursa veya gönderici sonucu yazmadan durursa aynı olay yeniden gider.
- Yeniden denemede ve elle yeniden sıraya almada `eventId` ve gövde baytları değişmez; testle gösterilir. Kimlik bilgisi her denemede yeniden istenir.
- Order'ın aynı `eventId`'yi gerçekten tek kez işlediği **bu çalışmada doğrulanmadı**; test sunucusu bunu kanıtlayamaz.
- Aynı komutun tekrarı yeni olay üretmez (mevcut davranış, veritabanı kısıtıyla korunur).
- **Sıra:** Gönderici vadesi gelenleri en eski vade önce gönderir. `PREPARING` yeniden deneme beklerken `READY` denenebilir. Order eksik adımı `409 INVALID_STATUS_TRANSITION` ile reddettiği için durum atlanmaz. Ancak `READY` her ret alışında bir deneme harcar ve `PREPARING` uzun süre ulaşamazsa kalıcı olarak `FAILED` olabilir. Koruma PR #77'de; bu branch'e alınmadı. O zamana kadar `FAILED` bir `READY`, §7'deki betikle `PREPARING`'den sonra yeniden sıraya alınabilir.
- Bir siparişin teslim hatası başka siparişleri durdurmaz. Kilit veya veritabanı hatası alan bir kayıt atlanır ve değişmeden kalır.

### 7. Yeniden deneme, zaman aşımı ve yeniden sıraya alma

- Gecikmeler: 5, 10, 20, 40, 80, 160, 320 saniye. 8. deneme de başarısız olursa teslimat `FAILED` (`MAX_ATTEMPTS_EXCEEDED`).
- **Sınır:** Bekleme sürelerinin toplamı **10 dakika 35 saniyedir** (istek süreleri ve zamanlayıcı aralığı hariç). Order bundan uzun süre kapalı kalırsa bekleyen teslimatlar `FAILED` olur. Kayıt ve olay silinmez.
- Başarısızları görmek (Kitchen veritabanında, salt okunur):

  ```sql
  SELECT d.id, d.restaurant_id, e.event_type, e.aggregate_id AS order_id, e.id AS event_id,
         d.attempt_count, d.last_error_code, d.last_attempt_at
  FROM outbox_deliveries d JOIN outbox_events e ON e.id = d.event_id
  WHERE d.destination = 'ORDER' AND d.status = 'FAILED'
  ORDER BY e.restaurant_id, e.aggregate_id, e.occurred_at;
  ```

- **Yeniden sıraya alma:** Neden giderildikten sonra Kitchen veritabanında yazma yetkisi olan operatör, tek bir teslimatı [`requeue-order-delivery.sql`](../../backend/services/kitchen-service/ops/requeue-order-delivery.sql) ile `PENDING`'e döndürür:

  ```text
  psql -X -v ON_ERROR_STOP=1 -d <KITCHEN_DATABASE> -v restaurant_id=<RESTAURANT_UUID> -v event_id=<EVENT_UUID> -f backend/services/kitchen-service/ops/requeue-order-delivery.sql
  ```

  Betik yalnız verilen restorana ait, `ORDER` hedefli ve `FAILED` durumundaki teslimatı değiştirir. Olay, `eventId` ve gövde aynı kalır. Teslimat yeni bir deneme hakkıyla hemen sıraya girer. Önceki deneme sayısı, hata kodu, son deneme zamanı ve işlemi yapan veritabanı kullanıcısı döner; bunları olay kaydına ekleyin. Satır dönmezse yeniden sıraya alınacak bir teslimat bulunmamıştır. Aynı siparişte önce `PREPARING`, sonra `READY` yeniden sıraya alınmalıdır. Kesin ret (`HTTP_4xx_…`, `REASON_TEXT_MISSING`) neden giderilmeden yeniden sıraya alınırsa aynı sonucu verir.
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

1. Order'da Kitchen kimlik doğrulaması (KO3-01) ve ona uygun Kitchen imzalayıcısı (§11).
2. `READY` sıra koruması (PR #77).
3. Order için HTTPS adresi (ortak ortam kararı).
4. Profil ilk açıldığında birikmiş `PENDING` olayların tamamı gönderilmeye başlar; açmadan önce incelenmelidir.

İptal talebinin gönderilmesi için ayrıca §4'teki cevabın uygulanması ve `OTHER` açıklaması gerekir. Gerçek siparişlerle uçtan uca deneme için sipariş kabulünün Kitchen'a ulaşması (PR #77) ve personel oturumunun çalışması ([PR #61](https://github.com/software-lecture-labs/Restofy/pull/61)) da gerekir.

### 10. Doğrulama

`backend/` klasöründe (Docker çalışırken):

```text
.\services\kitchen-service\mvnw.cmd -B -ntp -f pom.xml -pl services/kitchen-service -am clean test
```

| Test sınıfı | Gösterdiği |
|---|---|
| `HttpOrderEventSinkTests` | Gerçek `RestClient` ile uç, başlık, gövde (iptal talebi dahil), yanıt sınıflandırması, zaman aşımı, bağlantı hatası, yönlendirme |
| `OrderHttpDeliveryIntegrationTests` | PostgreSQL üzerinde komut → outbox → HTTP: onay, kayıp cevap, kesinti, deneme sınırı, yeniden sıraya alma betiği, iptal taleplerinin bekletilmesi, okunamayan olay, paralel gönderici, sonuç yazılmadan duran gönderici |
| `OrderDeliveryDispatcherTests` | `kitchen-order` profiliyle zamanlanmış teslim (kimlik bilgisi yalnız testte tanımlı) |
| `OrderDeliveryConfigurationTests` | Profil, adres ve zaman aşımı kuralları |

Test sunucusu Order Service değildir. Gerçek Order'ın kimlik doğrulaması, idempotent işleyişi ve durum sıralaması doğrulanmadı.

### 11. Servis kimliği nasıl tamamlanır

`dev`'deki Order, Kitchen çağrısını yalnız `dev`/`test` profilindeki sabit yer tutucu değerle kabul eder; diğer profillerde `503` döner. Bu yer tutucu gerçek bir kimlik değildir ve Kitchen'da kullanılmaz.

PR #47'deki Order doğrulayıcısı birleşirse Kitchen tarafında yapılacak iş, `OrderServiceCredentials`'ın bir RS256 imzalayıcıyla uygulanmasıdır. Arayüz bu sözleşmenin istediği bilgileri zaten verir. PR #47'nin beklediği token:

| Alan | Değer |
|---|---|
| Başlık | `Authorization: Service <JWT>`, `alg=RS256`, `kid` |
| Sabitler | `iss=restofy-kitchen`, `sub=kitchen-service`, `aud=restofy-order`, `scope=order:kitchen-events`, `token_type=service` |
| Süre | `iat = nbf`, `exp ≤ iat + 30 sn`, `jti` UUID |
| İsteğe bağlama | `restaurant_id`, `method=POST`, `target` = ham yol, `body_sha256` = gönderilen gövde baytlarının küçük harfli hex SHA-256 özeti |
| Order tarafı | `order-kitchen` profili, `ORDER_KITCHEN_SERVICE_PUBLIC_KEYS`, `ORDER_KITCHEN_SERVICE_RESTAURANTS`; istek HTTPS ile gelmeli |

Kitchen için özel anahtar ve `kid` ayarı **yeni ENV ve yeni güven ilişkisidir**; [SECURITY.md](../SECURITY.md) uyarınca uygulamadan önce onaylanmalıdır. Özel anahtar Kitchen'da kalır; Order'a yalnız açık anahtar ve `kid` verilir. Bu sözleşme `dev`'e girmeden Kitchen imzalayıcısı yazılmadı.
