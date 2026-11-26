# Kitchen entegrasyon rehberi

Kitchen'ı çağıracak veya Kitchen'dan veri bekleyecek ekipler için. **Güncelleme:** 26 Kasım 2026 (KITCHEN-INT-01). Etiketler: **UYGULANDI**, **UYGULANMADI**, **DOĞRULANMADI**.

Kimlik doğrulama konusunda [kitchen-01](../../backend/services/kitchen-service/docs/kitchen-01-contract.md) ve [kitchen-02](../../backend/services/kitchen-service/docs/kitchen-02-contract.md) sözleşme notlarındaki eski "kimlik doğrulayan bileşen yok" ifadelerinin yerine bu belge geçer. İş kuralları o notlardadır.

## 1. Kitchen bugün ne sağlıyor?

| Yüzey | Kim çağırır | Durum |
|---|---|---|
| Personel HTTP API'si `/api/v1/kitchen/**` | Personel tarayıcısı (Gateway üzerinden) | **UYGULANDI**; ortak ortamda HTTPS Auth adresi yok ([#63](https://github.com/software-lecture-labs/Restofy/issues/63)), **DOĞRULANMADI** |
| Servisten servise HTTP ucu | Order, Menu veya başka servis | **UYGULANMADI**. `/api/v1/internal/**` dahil başka her yol reddedilir |
| Giden olaylar (Order, Notification) | Kitchen → diğerleri | Outbox'a yazılır, **teslim edilmez** |
| Menu komutları | Kitchen → Menu | Port var, gerçek adaptör **UYGULANMADI** |

**Bir servis Kitchen personel API'sini kendi kimliğiyle çağıramaz.** Servis kimliği hiçbir personel yetkisi vermez; personel cookie'sini başka bir servisin saklayıp kullanması da desteklenen bir sözleşme değildir.

## 2. Personel kimlik doğrulaması

Kaynaklar: Auth sorumlusunun 26 Kasım 2026 yanıtları ([PR #61](https://github.com/software-lecture-labs/Restofy/pull/61) yorumları) ve personel oturumu sözleşmesi ([PR #67](https://github.com/software-lecture-labs/Restofy/pull/67), incelenen `3c91e6e`). **PR #67 henüz birleşmedi;** sözleşme `dev`'de değildir. Eski `feature/auth-service` dalındaki sözleşme geçerli değildir. `ADR-AUTH-Q5` ve `ADR-QR-003` yalnız QR kapsamındadır, Kitchen'ı bağlamaz.

**Her personel isteğinde (yerel doğrulama):**

1. Yalnız `restofy_access` HttpOnly cookie'si okunur. `Authorization` (Bearer, Staff, Service), `X-Restaurant-Id`, `X-Role`, `X-User-Id`, query ve gövde alanları kimlik değildir.
2. JWT yerelde doğrulanır: yalnız RS256, yapılandırılmış issuer, **tek** `restofy-staff-api` audience, `token_type=access`, `auth_state=AUTHENTICATED`, `auth_policy=q5-idle-v1`, `iat = nbf` (en çok 3 sn ileride), süresi dolmamış `exp` (tolerans yok), **`exp − iat` en çok 300 sn**, UUID `sub/sid/jti/restaurant_id` (sıfır olmayan restoran), `ADMIN|WAITER|KITCHEN` rolü, en çok 4096 karakter. Auth, oturum daha erken bitecekse daha kısa ömür verir; kısa ömür geçerlidir.

**Durum değiştiren isteklerde ek olarak (Auth teyidi):**

3. Rol yetkisi geçtikten sonra aynı cookie ile Auth'a `GET /api/v1/auth/session` sorulur. Yalnız access cookie'si gönderilir; `Origin` gönderilmez; yönlendirme izlenmez; yanıt önbelleğe alınmaz, yeniden deneme yoktur. Bu çağrı Auth'ta personel etkinliği sayılmaz. `200` yanıtında `status=AUTHENTICATED`, token ile aynı rol ve geçmemiş süreler gerekir. `401` ret; diğer her sonuç "doğrulanamadı" sayılır ve istek işlenmez.

`/session` yanıtında **kullanıcı ve restoran kimliği yoktur.** Kitchen bu ikisini karşılaştıramaz; gönderilen token'ın oturumuyla uyuşmazlığı Auth `401` ile reddeder (izole ortamda gerçek Auth ile denendi). Kullanıcı, restoran ve rol Kitchen'da yalnız imzalı token'dan alınır. Kitchen token'ı saklamaz, loglamaz, Auth `/session` dışında bir yere iletmez ve cookie yazmaz.

Yerelde reddedilen istek (CSRF/Origin eksik, token geçersiz veya süresi dolmuş, rol yetersiz) **Auth'a hiç gönderilmez.**

### Hangi istek Auth teyidi ister?

| İstek (`/api/v1/kitchen`) | Veri değiştirir mi | `/session` |
|---|---|---|
| `GET /orders`, `GET /orders/{orderId}` | Hayır | Hayır |
| `GET /orders/{orderId}/cancellation-requests` | Hayır | Hayır |
| `GET /products/closed` (Menu'den okur; Menu bağlı değil) | Hayır | Hayır |
| `GET /reports/daily`, `GET /waste` | Hayır | Hayır |
| `PATCH /orders/{orderId}/start-preparation`, `.../mark-ready` | Evet: durum, geçmiş, outbox | **Evet** |
| `POST /orders/{orderId}/cancellation-requests` | Evet: talep, outbox | **Evet** |
| `PATCH /products/{productId}/availability` | Evet: komut kaydı, Menu komutu | **Evet** |
| `POST /waste` | Evet: fire kaydı | **Evet** |

`HEAD` okumayı izler. Teyitsiz uçlar **açık bir listedir** (`StaffReadEndpoints`), "bütün GET'ler" değildir: listede olmayan her istek, yeni eklenen bir GET dahil, teyit ister. Bir test uygulamanın bütün Kitchen eşlemelerinin sınıflandırılmasını (yöntem belirtmeyen eşleme dahil) ve teyitsiz listenin tam olarak sınıflandırılmış okumalar olmasını şart koşar. Aynı `Idempotency-Key` ile tekrar edilen komut da teyit ister.

### Bu düzenin sonuçları

- **Okumalarda iptal gecikir.** Logout, hesap pasifleştirme, rol değişikliği veya idle süresi dolsa da eski token, süresi dolana kadar (en çok 300 sn) mutfak verisini **okuyabilir**. Bu süre yönetici işlemi Auth'ta tamamlanmış görünse de geçerlidir. İptalden sonra başlayan bir yazma isteği reddedilir; teyidi iptalden **önce** alınmış, o sırada işlenen istek tamamlanır.
- **Auth kesintisi yazmayı anında durdurur.** Auth erişilemezken, geçerli token'la okumalar sürer; durum değiştiren her istek hemen `503 AUTH_UNAVAILABLE` alır. Access token yenilenemediği için okumalar da en geç token süresi dolunca `401` alır.

### Yazma isteklerinde ek şartlar (CSRF)

`GET`, `HEAD`, `OPTIONS` ve `TRACE` dışındaki her istekte aşağıdakilerin hepsi birden gerekir:

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
| Cookie yok, tekrarlanmış, geçersiz veya süresi dolmuş; Kitchen'da açık anahtar tanımlı değil; teyit isteyen istekte Auth oturumu reddetti | `401`, gövdesiz |
| CSRF/Origin şartı eksik veya hatalı (Auth'a hiç sorulmaz; anonim istek de 403 alır) | `403`, gövdesiz |
| Doğrulanmış kimliğin rolü yetmiyor | `403`, gövdesiz |
| Teyit isteyen istekte Auth'tan güvenilir cevap yok (zaman aşımı, bağlantı, TLS, 401 ve 200 dışı her durum, yönlendirme, okunamayan veya token ile çelişen yanıt) | `503`, `code=AUTH_UNAVAILABLE`, `retryable=true`, `Retry-After: 2`. İstek **işlenmedi** |
| Menu bağlı değil / sonucu bilinmiyor | `503`, `MENU_NOT_INTEGRATED` / `MENU_UNAVAILABLE`; asla başarı |
| Beklenmeyen sunucu hatası (ör. veritabanı erişilemiyor) | `500`; iç ayrıntı dönmez. Oturumun bittiği anlamına **gelmez**. Yazma isteğinin sonucu varsayılmaz; aynı `Idempotency-Key` ile tekrar sorulur |

```json
{
  "title": "Service Unavailable",
  "status": 503,
  "detail": "Staff authentication is currently unavailable; the request was not processed",
  "instance": "/api/v1/kitchen/waste",
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

## 8. Açık talepler

**KITCHEN-INT-01 (Auth taskı):**
- Auth: [kitchen-auth-requests-01](integration/kitchen-auth-requests-01.md). Açık anahtarın kaynağı ve `/session` kullanımı yanıtlandı ve Kitchen'da uygulandı. **Açık:** iç ağda HTTPS ([#63](https://github.com/software-lecture-labs/Restofy/issues/63)).
- Güvenilir servis kimliği ve personel delegasyonu: **karşılanmadı**. Beklenen ortak sözleşme ve Kitchen'da kalan güvenlik işi [AUTH-05](integration/kitchen-auth-requests-01.md) içindedir.

**Sonraki işler (bu taskın tamamlanma şartı değil):**
- Order: iç alıcı, mesaj uyumu, gönderici adaptör ve Order kaynaklı iptal. Ayrı entegrasyon işidir.
- Menu: HTTP sözleşmesi, ürün açma/kapama adaptörü ve model kararları. Ayrı entegrasyon işidir.
- Frontend: mutfak ekranının oturum sürekliliği ve `/activity` ([#64](https://github.com/software-lecture-labs/Restofy/issues/64), AUTH-04).

## 9. Yapılandırma

| Kitchen özelliği | Bağlı ENV | Not |
|---|---|---|
| `restofy.auth.staff-public-key` | `KITCHEN_AUTH_STAFF_PUBLIC_KEY` | Auth'un **açık** anahtarı (kaynağı aşağıda). Boşken personel doğrulaması kapalı (401) |
| `restofy.auth.service-url` | `AUTH_SERVICE_URL` | Anahtar tanımlıyken yalnız HTTPS origin, aksi halde uygulama başlamaz (okumalar Auth'u çağırmasa da). Ortak Compose'da bugün HTTP'dir ([AUTH-02](integration/kitchen-auth-requests-01.md)) |
| `restofy.auth.issuer` | `JWT_ISSUER` | |
| `restofy.auth.access-ttl-seconds` | `JWT_ACCESS_TTL_SECONDS` | Kitchen'ın `exp − iat` için uyguladığı **üst sınır**. Anahtar tanımlıyken 1–300 olmalı. Auth'un ortak ayarıdır ve Auth yalnız 300 kabul eder; Auth'unkinden küçük bir değer geçerli token'ları reddeder |
| `restofy.auth.allowed-origins` | `CORS_ALLOWED_ORIGINS` | Joker ve yol içeren değer uygulamayı başlatmaz |
| `restofy.auth.connect-timeout-ms` / `read-timeout-ms` | `REST_CLIENT_CONNECT_TIMEOUT_MS` / `REST_CLIENT_READ_TIMEOUT_MS` | Varsayılan 3000 / 5000 |

### Açık anahtarın kaynağı

Auth personel token'larını tek bir RSA anahtarıyla imzalar; token'da `kid` yoktur. `KITCHEN_AUTH_STAFF_PUBLIC_KEY` değeri, `ops/auth/generate-keys.mjs` çıktısındaki `MAIL_AUTH_PUBLIC_KEY` alanıdır: **Auth'un o ortamda kullandığı `AUTH_JWT_PRIVATE_KEY` ile aynı çiftin** açık yarısı (Base64 X.509). Değeri o ortamın anahtarlarını yöneten kişiden alın.

- Değeri elde etmek için betiği **yeniden çalıştırmayın.** Her çalıştırma yeni bir çift üretir; yeni çiftin açık anahtarıyla Kitchen bütün token'ları `401` ile reddeder.
- Betiğin çıktı dosyası özel anahtar ve başka sırlar da içerir; Kitchen'a yalnız `MAIL_AUTH_PUBLIC_KEY` değeri verilir.
- Anahtar değişirse `kid` olmadığı için tüketen bütün servisler birlikte güncellenmelidir. Auth planlı bir değişim öngörmüyor ve değişiklikte önceden haber vereceğini yazdı.

## 10. Bilinen sınırlar

- Auth erişilemezken durum değiştiren istekler çalışmaz; okumalar yalnız mevcut token süresince sürer (§2). Devre kesici yoktur; yavaş Auth teyit isteyen her isteği zaman aşımı kadar bekletir.
- Auth'un Q5 kaynağı `ACTIVE` değilken `/session` 503 döner; bu yüzden Kitchen'ın yazma işlemleri dolaylı olarak QR bootstrap'ına da bağlıdır (koddan, denenmedi).
- Kitchen restoranı yapılandırmadaki tek bir kimliğe ayrıca sabitlemez; imzalı `restaurant_id`'ye göre veri sınırlar.
- Rate limit bu görevde eklenmedi veya değiştirilmedi.
- Access cookie token ömrü kadar, **en çok** 300 sn yaşar. `dev` frontend'i yalnız personel sayfası açılırken, oturum sorgusu 401/403 dönerse bir kez refresh dener; açık ekranda süreli yenileme ve mutfak aktivite bildirimi yoktur.
- İzole ortamda (`dev` `00c0ff4` Auth, QR ve API Gateway kodu; geçici PostgreSQL; yalnız o çalıştırmada üretilen anahtarlar; Auth'un önünde **yalnız test için** HTTPS proxy) 40 kontrol geçti: giriş, MFA, okuma/yazma ayrımı, Auth'un idle bitişine yakın verdiği 20 sn'lik token, logout, Auth kesintisi, güvenilmeyen sertifika, HTTP adresle açılmama ve Gateway üzerinden okuma/yazma. Idle, rol değişikliği ve pasifleştirme geçici veritabanında SQL ile canlandırıldı. Ortak ortam, tarayıcı arayüzü, Auth'un yönetici iptal akışı ve gerçek TLS dağıtımı **DOĞRULANMADI**; test proxy'si ortak ortamın hazır olduğu anlamına gelmez.
