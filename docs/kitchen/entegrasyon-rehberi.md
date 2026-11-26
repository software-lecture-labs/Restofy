# Kitchen entegrasyon rehberi

Kitchen'ı çağıracak veya Kitchen'dan veri bekleyecek ekipler için. **Güncelleme:** 26 Kasım 2026 (KITCHEN-INT-01). Etiketler: **UYGULANDI**, **UYGULANMADI**, **DOĞRULANMADI**.

Kimlik doğrulama konusunda [kitchen-01](../../backend/services/kitchen-service/docs/kitchen-01-contract.md) ve [kitchen-02](../../backend/services/kitchen-service/docs/kitchen-02-contract.md) sözleşme notlarındaki eski "kimlik doğrulayan bileşen yok" ifadelerinin yerine bu belge geçer. İş kuralları o notlardadır.

## 1. Kitchen bugün ne sağlıyor?

| Yüzey | Kim çağırır | Durum |
|---|---|---|
| Personel HTTP API'si `/api/v1/kitchen/**` | Personel tarayıcısı (Gateway üzerinden) | **UYGULANDI**; paylaşılan ortamda anahtar değeri ve HTTPS Auth adresi bekliyor, **DOĞRULANMADI** |
| Servisten servise HTTP ucu | Order, Menu veya başka servis | **UYGULANMADI**. `/api/v1/internal/**` dahil başka her yol reddedilir |
| Giden olaylar (Order, Notification) | Kitchen → diğerleri | Outbox'a yazılır, **teslim edilmez** |
| Menu komutları | Kitchen → Menu | Port var, gerçek adaptör **UYGULANMADI** |

**Bir servis Kitchen personel API'sini kendi kimliğiyle çağıramaz.** Servis kimliği hiçbir personel yetkisi vermez; personel cookie'sini başka bir servisin saklayıp kullanması da desteklenen bir sözleşme değildir.

## 2. Personel kimlik doğrulaması

Auth Service'in mevcut sözleşmesi tüketilir. Her istekte sırayla:

1. `restofy_access` HttpOnly cookie'si okunur. Başka kaynak okunmaz: `Authorization` (Bearer, Staff, Service), `X-Restaurant-Id`, `X-Role`, `X-User-Id`, query ve gövde alanları kimlik değildir.
2. JWT yerelde doğrulanır: yalnız RS256, yapılandırılmış issuer, **tek** `restofy-staff-api` audience, `token_type=access`, `auth_state=AUTHENTICATED`, `auth_policy=q5-idle-v1`, `iat = nbf` (en çok 3 sn ileride), süresi dolmamış `exp`, ömür ≤ 300 sn, UUID `sub/sid/jti/restaurant_id` (sıfır olmayan restoran), `ADMIN|WAITER|KITCHEN` rolü, en çok 4096 karakter.
3. Aynı cookie ile Auth'a `GET /api/v1/auth/session` sorulur (yalnız access cookie'si gönderilir, yönlendirme izlenmez, önbellek ve yeniden deneme yoktur). Dönen rol token rolüyle aynı olmalı, süreler geçerli olmalıdır.

3. adım **geçicidir**: Auth ile karşılıklı onaylanmış bir tüketici sözleşmesi değildir ve `dev`'deki `ADR-AUTH-Q5` aynı yaklaşımı QR için reddetmiştir. Gerekçeler, doğrulanan davranışlar ve açık sorular [AUTH-03](integration/kitchen-auth-requests-01.md) içindedir.

Kullanıcı, restoran ve rol yalnız imzalı token'dan alınır. Kitchen token'ı saklamaz, loglamaz, başka servise iletmez ve cookie yazmaz.

### Yazma isteklerinde ek şartlar (CSRF)

`GET`/`HEAD` dışındaki her istekte hepsi birden gerekir:

- Tam olarak bir `Origin` ve `CORS_ALLOWED_ORIGINS` listesinde birebir eşleşme;
- tek `restofy_csrf` cookie'si (Auth biçimi, süresi dolmamış);
- aynı değerde tek `X-CSRF-TOKEN` header'ı (query/form parametresi kabul edilmez);
- `Content-Type` gönderiliyorsa `application/json`.

Auth'un HMAC imzasını Kitchen doğrulamaz (sır Auth'tadır); koruma Origin izin listesi ve çift gönderim eşitliğidir. Arayüz `/api/v1` dışında çalıştığı için değeri `document.cookie`'den okuyamaz; `GET /api/v1/auth/csrf` yanıtındaki `token` alanını kullanır ve login/refresh sonrası yeniden alır.

Kitchen CORS açmaz. Beklenen düzen aynı origin'dir (frontend `/api` isteklerini Gateway'e iletir). Tarayıcının başka bir origin'den doğrudan Gateway'i çağırdığı düzen (`NEXT_PUBLIC_API_BASE_URL` dolu) Kitchen için **çalışmaz**; CORS eklemek güvenlik sözleşmesi değişikliği olduğu için ayrı onay ister.

## 3. Yetki matrisi ve restoran kapsamı

| Method / yol (`/api/v1/kitchen`) | KITCHEN | ADMIN | WAITER |
|---|---|---|---|
| `GET /orders`, `GET /orders/{orderId}` | ✔ | ✔ | 403 |
| `PATCH /orders/{orderId}/start-preparation`, `.../mark-ready` | ✔ | 403 | 403 |
| `GET /orders/{orderId}/cancellation-requests` | ✔ | ✔ | 403 |
| `POST /orders/{orderId}/cancellation-requests` | ✔ | 403 | 403 |
| `PATCH /products/{productId}/availability` | ✔ | 403 | 403 |
| `GET /products/closed`, `GET /reports/daily`, `GET /waste` | ✔ | ✔ | 403 |
| `POST /waste` | ✔ | 403 | 403 |

`HEAD` okuma kuralını izler. Eşlenmemiş yazma yöntemleri de yalnız KITCHEN kuralına tabidir. Anonim erişim yalnız health uçlarındadır.

Her sorgu ve komut personelin imzalı `restaurant_id`'siyle sınırlıdır. Başka restoranın siparişi **404** döner (varlığı sızdırılmaz). Aynı `orderId` iki restoranda iki bağımsız kayıttır.

## 4. Örnekler

Değerler yer tutucudur; gerçek token veya cookie belgeye yazılmaz.

```http
GET /api/v1/kitchen/orders?status=ACTIVE&size=1
Cookie: restofy_access=<access-jwt>
```

```json
{
  "content": [
    {
      "orderId": "11111111-1111-4111-8111-111111111111",
      "orderNumber": "A-1",
      "tableNumber": "1",
      "tableSessionId": "22222222-2222-4222-8222-222222222222",
      "status": "WAITING",
      "receivedAt": "2026-11-26T09:00:00Z",
      "newArrival": true,
      "itemCount": 1,
      "cancelledAt": null,
      "cancellationReason": null
    }
  ],
  "page": 0,
  "size": 1,
  "totalElements": 1,
  "totalPages": 1
}
```

```http
POST /api/v1/kitchen/waste
Cookie: restofy_access=<access-jwt>; restofy_csrf=<csrf-proof>
Origin: https://staff.example.test
X-CSRF-TOKEN: <csrf-proof>
Idempotency-Key: 33333333-3333-4333-8333-333333333333
Content-Type: application/json

{"productId":"44444444-4444-4444-8444-444444444444","quantity":2,"reasonCode":"SPOILAGE"}
```

Yanıt `201`: `id, productId, productName, quantity, reasonCode, description, orderId, recordedAt, recordedByUserId`. Restoran, kullanıcı ve zaman sunucudan gelir.

## 5. Hata davranışı

| Durum | Yanıt |
|---|---|
| Cookie yok, tekrarlanmış, geçersiz veya süresi dolmuş; Auth oturumu reddetti; Kitchen'da açık anahtar tanımlı değil | `401`, gövdesiz |
| CSRF/Origin şartı eksik veya hatalı (Auth'a hiç sorulmaz; anonim istek de 403 alır) | `403`, gövdesiz |
| Doğrulanmış kimliğin rolü yetmiyor | `403`, gövdesiz |
| Auth'tan güvenilir cevap yok (zaman aşımı, bağlantı, TLS, 401 ve 200 dışı her durum, yönlendirme, okunamayan veya token ile çelişen yanıt) | `503`, `code=AUTH_UNAVAILABLE`, `retryable=true`, `Retry-After: 2`. İstek **işlenmedi** |
| Menu bağlı değil / sonucu bilinmiyor | `503`, `MENU_NOT_INTEGRATED` / `MENU_UNAVAILABLE`; asla başarı |
| Beklenmeyen sunucu hatası (ör. veritabanı erişilemiyor) | `500`; iç ayrıntı dönmez. Oturumun bittiği anlamına **gelmez**. Yazma isteğinin sonucu varsayılmaz; aynı `Idempotency-Key` ile tekrar sorulur |

```json
{
  "title": "Service Unavailable",
  "status": 503,
  "detail": "Staff authentication is currently unavailable; the request was not processed",
  "instance": "/api/v1/kitchen/orders",
  "code": "AUTH_UNAVAILABLE",
  "retryable": true
}
```

Kitchen Auth çağrısını kendisi tekrarlamaz. İstemci yazma isteğini aynı `Idempotency-Key` ile yeniden gönderebilir.

## 6. Personel delegasyonu

- Menu portu `restaurantId` ve `requestedByUserId` değerlerini **yalnız** doğrulanmış kimlikten alır. İstemcinin bildirdiği aktör veya restoran portun içine giremez.
- İptal talebi ve fire kaydı aktörü Kitchen veritabanında aynı şekilde tutulur. Giden `KitchenCancellationRequested` olayı bugün personel kimliği taşımaz.
- Bu alanlar bir **beyandır**: alıcı servis Kitchen'ın servis kimliğini ayrıca doğrulamadan bunlara güvenemez. Servis kimliği mekanizması **UYGULANMADI** ([AUTH-05](integration/kitchen-auth-requests-01.md)).
- Aynı `Idempotency-Key` başka bir personel tarafından kullanılırsa `409`.

## 7. Giden olaylar (teslim edilmiyor)

Ortak zarf: `eventId, eventType, schemaVersion (1), occurredAt, restaurantId, orderId, aggregateVersion, correlationId, causationId, payload`.

| Olay | Hedef | `payload` |
|---|---|---|
| `KitchenPreparationStarted` | Order | `preparationStatus=PREPARING, startedAt` |
| `KitchenOrderReady` | Order, Notification | `preparationStatus, startedAt, readyAt` |
| `KitchenCancellationRequested` (ek alan `requestId`) | Order | `orderId, reasonCode, productId, orderItemId` |

Bunlar **önerilen** sözleşmelerdir. Taşıma, hedef adres ve servis kimliği yoktur. Tüketici en az bir kez teslimata göre `eventId` ile tekilleştirmelidir.

## 8. Order ve Menu ihtiyaçlarının durumu

- Order: [kitchen-order-requests-01](integration/kitchen-order-requests-01.md). Personel kimliği karşılandı; iç alıcı, mesaj uyumu, gönderici adaptör, Order kaynaklı iptal ve servis kimliği karşılanmadı.
- Menu: [kitchen-menu-requests-01](integration/kitchen-menu-requests-01.md). Restoran/kullanıcı kaynağı karşılandı; HTTP adaptörü, model kararları ve servis kimliği karşılanmadı.
- Auth: [kitchen-auth-requests-01](integration/kitchen-auth-requests-01.md).

## 9. Yapılandırma

| Kitchen özelliği | Bağlı ENV | Not |
|---|---|---|
| `restofy.auth.staff-public-key` | `KITCHEN_AUTH_STAFF_PUBLIC_KEY` | Auth'un **açık** anahtarı. Boşken personel doğrulaması kapalı (401) |
| `restofy.auth.service-url` | `AUTH_SERVICE_URL` | Anahtar tanımlıyken yalnız HTTPS origin, aksi halde uygulama başlamaz. Ortak Compose'da bugün HTTP'dir ([AUTH-02](integration/kitchen-auth-requests-01.md)) |
| `restofy.auth.issuer` | `JWT_ISSUER` | |
| `restofy.auth.access-ttl-seconds` | `JWT_ACCESS_TTL_SECONDS` | Anahtar tanımlıyken 1–300; Auth 300 kullanır |
| `restofy.auth.allowed-origins` | `CORS_ALLOWED_ORIGINS` | Joker ve yol içeren değer uygulamayı başlatmaz |
| `restofy.auth.connect-timeout-ms` / `read-timeout-ms` | `REST_CLIENT_CONNECT_TIMEOUT_MS` / `REST_CLIENT_READ_TIMEOUT_MS` | Varsayılan 3000 / 5000 |

## 10. Bilinen sınırlar

- Auth erişilemezken personel API'si kullanılamaz. Devre kesici yoktur; yavaş Auth her isteği zaman aşımı kadar bekletir.
- Auth'un Q5 kaynağı `ACTIVE` değilken `/session` 503 döner; bu yüzden Kitchen dolaylı olarak QR bootstrap'ına da bağlıdır (koddan, denenmedi).
- Kitchen restoranı yapılandırmadaki tek bir kimliğe ayrıca sabitlemez; imzalı `restaurant_id`'ye göre veri sınırlar.
- Rate limit bu görevde eklenmedi veya değiştirilmedi.
- Access cookie 300 sn yaşar; frontend yenilemesi ve mutfak aktivite bildirimi uygulanmadı.
- İzole ortamda gerçek Auth ve Gateway ile denendi: giriş, MFA, logout, Gateway üzerinden okuma/yazma ve Auth kesintisi. Idle, rol ve pasifleştirme durumları geçici veritabanında SQL ile canlandırıldı. Ortak ortam, tarayıcı arayüzü, Auth'un yönetici iptal akışı ve üretim TLS'i **DOĞRULANMADI**.
