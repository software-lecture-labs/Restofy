# Kitchen entegrasyon rehberi

Kitchen'ı çağıracak veya Kitchen'dan veri bekleyecek ekipler için. **Güncelleme:** 28 Kasım 2026 (KITCHEN-INT-01 personel doğrulaması; KITCHEN-INT-02 Order olay alımı, [§11](#11-order--kitchen-olay-alımı-ve-order-talepleri)). Etiketler: **UYGULANDI**, **UYGULANMADI**, **DOĞRULANMADI**.

Kimlik doğrulama konusunda [kitchen-01](../../backend/services/kitchen-service/docs/kitchen-01-contract.md) ve [kitchen-02](../../backend/services/kitchen-service/docs/kitchen-02-contract.md) sözleşme notlarındaki eski "kimlik doğrulayan bileşen yok" ifadelerinin yerine bu belge geçer. İş kuralları o notlardadır.

## 1. Kitchen bugün ne sağlıyor?

| Yüzey | Kim çağırır | Durum |
|---|---|---|
| Personel HTTP API'si `/api/v1/kitchen/**` | Personel tarayıcısı (Gateway üzerinden) | **UYGULANDI**; ortak ortamda HTTPS Auth adresi yok ([#63](https://github.com/software-lecture-labs/Restofy/issues/63)), **DOĞRULANMADI** |
| Order olay alımı `POST /api/v1/internal/kitchen/order-events` | Order Service (imzalı servis kimliği, yalnız HTTPS) | **UYGULANDI**, yalnız sipariş kabulü; varsayılan kapalı (§11) |
| Diğer servisten servise HTTP uçları | Menu veya başka servis | **UYGULANMADI**. Bu tek yol dışında `/api/v1/internal/**` dahil her yol reddedilir |
| Giden olaylar (Order, Notification) | Kitchen → diğerleri | Outbox'a yazılır, **teslim edilmez** |
| Menu komutları | Kitchen → Menu | Port var, gerçek adaptör **UYGULANMADI** |

**Bir servis Kitchen personel API'sini kendi kimliğiyle çağıramaz.** Servis kimliği hiçbir personel yetkisi vermez; personel cookie'sini başka bir servisin saklayıp kullanması da desteklenen bir sözleşme değildir.

## 2. Personel kimlik doğrulaması

Kaynaklar: Auth sorumlusunun 26 Kasım 2026 yanıtları ([PR #61](https://github.com/software-lecture-labs/Restofy/pull/61) yorumları) ve `dev`'deki [personel oturumu sözleşmesi](https://github.com/software-lecture-labs/Restofy/blob/c5981b89619eb6b6967e0cb06578f938b17d8c1e/docs/auth-service/staff-contract.md) ([PR #67](https://github.com/software-lecture-labs/Restofy/pull/67); [yerel kopya](../auth-service/staff-contract.md)). Eski `feature/auth-service` dalındaki sözleşme geçerli değildir. `ADR-AUTH-Q5` ve `ADR-QR-003` yalnız QR kapsamındadır, Kitchen'ı bağlamaz.

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

`HEAD` okumayı izler. Teyitsiz uçlar **açık bir listedir** (`StaffReadEndpoints`), "bütün GET'ler" değildir: listede olmayan her istek, yeni eklenen bir GET dahil, teyit ister. Bir test uygulamanın bütün Kitchen eşlemelerinin sınıflandırılmasını (yöntem belirtmeyen eşleme dahil) ve teyitsiz listenin tam olarak sınıflandırılmış okumalar olmasını şart koşar. Listedeki yol değişkenleri **yalnız UUID** ile eşleşir; böylece sipariş kimliğinin yerine sabit bir sözcükle sonradan eklenen bir uç (ör. `/orders/export`) listedeki okuma sayılmaz ve teyit ister. UUID olmayan bir kimlikle gelen okuma da önce teyide gider, sonra `400` alır. Aynı `Idempotency-Key` ile tekrar edilen komut da teyit ister.

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
- Bu alanlar bir **beyandır**: alıcı servis Kitchen'ın servis kimliğini ayrıca doğrulamadan bunlara güvenemez. Kitchen'ın **giden** çağrıları için servis kimliği mekanizması **UYGULANMADI** ([AUTH-05](integration/kitchen-auth-requests-01.md)). Order'dan gelen olayların kimlik doğrulaması §11.2'dedir ve personel yetkisi vermez.
- Aynı `Idempotency-Key` başka bir personel tarafından kullanılırsa `409`.

## 7. Giden olaylar (teslim edilmiyor)

Ortak zarf: `eventId, eventType, schemaVersion (1), occurredAt, restaurantId, orderId, aggregateVersion, correlationId, causationId, payload`.

| Olay | Hedef | `payload` |
|---|---|---|
| `KitchenPreparationStarted` | Order | `preparationStatus=PREPARING, startedAt` |
| `KitchenOrderReady` | Order, Notification | `preparationStatus, startedAt, readyAt` |
| `KitchenCancellationRequested` (ek alan `requestId`) | Order | `orderId, reasonCode, productId, orderItemId`; yalnız `OTHER`'da `reasonText` (§11.6) |

Bunlar **önerilen** sözleşmelerdir. Taşıma, hedef adres ve servis kimliği yoktur. Tüketici en az bir kez teslimata göre `eventId` ile tekilleştirmelidir.

## 8. Açık talepler

**KITCHEN-INT-01 (Auth taskı):**
- Auth: [kitchen-auth-requests-01](integration/kitchen-auth-requests-01.md). Açık anahtarın kaynağı ve `/session` kullanımı yanıtlandı ve Kitchen'da uygulandı. **Açık:** iç ağda HTTPS ([#63](https://github.com/software-lecture-labs/Restofy/issues/63)). HTTP istisnası önerisi ([PR #69](https://github.com/software-lecture-labs/Restofy/pull/69)) birleşmedi ve Kitchen'da uygulanmadı; HTTPS şartı geçerlidir ([AUTH-02](integration/kitchen-auth-requests-01.md)).
- Order ve Menu bağlantılarında güvenilir servis kimliği ve personel delegasyonu: **ortak sözleşme bekliyor, uygulanmadı**. Sözleşmenin cevaplaması gerekenler ve Kitchen'da kalan güvenlik işi [AUTH-05](integration/kitchen-auth-requests-01.md) içindedir.

**Sonraki işler (bu taskın tamamlanma şartı değil):**
- Order: gönderici adaptör ve Order kaynaklı iptalin uygulanması. Ayrı entegrasyon işidir; sipariş kabulü alıcısı ve mesaj uyumu §11'dedir.
- Menu: HTTP sözleşmesi, ürün açma/kapama adaptörü ve model kararları. Ayrı entegrasyon işidir.
- Frontend: mutfak ekranı. Ortak etkinlik hook'u `dev`'de hazır ([PR #70](https://github.com/software-lecture-labs/Restofy/pull/70)); ekran henüz yok. Yazılınca hook'u kullanacak ve kendi veri isteklerinde yenilemeyi ele alacak (AUTH-04).

## 9. Yapılandırma

| Kitchen özelliği | Bağlı ENV | Not |
|---|---|---|
| `restofy.auth.staff-public-key` | `KITCHEN_AUTH_STAFF_PUBLIC_KEY` | Auth'un **açık** anahtarı (kaynağı aşağıda). Boşken personel doğrulaması kapalı (401) |
| `restofy.auth.service-url` | `AUTH_SERVICE_URL` | Anahtar tanımlıyken yalnız HTTPS origin, aksi halde uygulama başlamaz (okumalar Auth'u çağırmasa da). Ortak Compose'da bugün HTTP'dir ([AUTH-02](integration/kitchen-auth-requests-01.md)) |
| `restofy.auth.issuer` | `JWT_ISSUER` | |
| `restofy.auth.access-ttl-seconds` | `JWT_ACCESS_TTL_SECONDS` | Kitchen'ın `exp − iat` için uyguladığı **üst sınır**. Anahtar tanımlıyken 1–300 olmalı. Auth'un ortak ayarıdır ve Auth yalnız 300 kabul eder; Auth'unkinden küçük bir değer geçerli token'ları reddeder |
| `restofy.auth.allowed-origins` | `CORS_ALLOWED_ORIGINS` | Joker ve yol içeren değer uygulamayı başlatmaz |
| `restofy.auth.connect-timeout-ms` / `read-timeout-ms` | `REST_CLIENT_CONNECT_TIMEOUT_MS` / `REST_CLIENT_READ_TIMEOUT_MS` | Varsayılan 3000 / 5000 |

Order olay alıcısının ayarları §11.2.1'dedir.

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
- Access cookie token ömrü kadar, **en çok** 300 sn yaşar. `dev` frontend'inde süreli yenileme yoktur: personel ana sayfası açılırken oturum sorgusu reddedilirse bir kez `refresh` denenir; aynı sayfadaki ortak `StaffActivity` bileşeni (PR #70) yalnız etkileşimde veya sekmeye dönüşte istek gönderir ve kendi isteği reddedilirse `refresh` dener. Mutfak ekranı yoktur ve bileşen ona uygulanmamıştır ([AUTH-04](integration/kitchen-auth-requests-01.md)).
- İzole ortamda (`dev` `00c0ff4` Auth, QR ve API Gateway kodu; geçici PostgreSQL; yalnız o çalıştırmada üretilen anahtarlar; Auth'un önünde **yalnız test için** HTTPS proxy) 40 kontrol geçti: giriş, MFA, okuma/yazma ayrımı, Auth'un idle bitişine yakın verdiği 20 sn'lik token, logout, Auth kesintisi, güvenilmeyen sertifika, HTTP adresle açılmama ve Gateway üzerinden okuma/yazma. Idle, rol değişikliği ve pasifleştirme geçici veritabanında SQL ile canlandırıldı. Ortak ortam, tarayıcı arayüzü, Auth'un yönetici iptal akışı ve gerçek TLS dağıtımı **DOĞRULANMADI**; test proxy'si ortak ortamın hazır olduğu anlamına gelmez.

## 11. Order → Kitchen olay alımı ve Order talepleri

Order ekibi için. Yalnız PR #47'deki Order taleplerine (`6d4fdb3`, `docs/order-service/kitchen-entegrasyon-rehberi.md`) Kitchen tarafında yapılan hazırlığı anlatır. Order sorumlusunun sonradan verdiği yanıtlar işlenmiştir; yanıttaki bir davranış PR #47 kodunda henüz yoksa ayrıca belirtilir. PR #47 ve QR'ın masa numarası değişikliği (PR #78) artık `dev`'dedir; aşağıda "PR #47 göndericisi" `dev`'deki Order göndericisidir.

> **Order–Kitchen entegrasyonu tamamlanmadı ve etkinleştirilmedi.** `dev`'deki Order kabul mesajında `tableNumber` gönderir; bu alanı taşımayan, daha önce kaydedilmiş olaylar **reddedilir**. Dört iptal olayı `422` alır ve ortak ortamda Kitchen için HTTPS yoktur. Bu konular çözülmeden Order'da `order-kitchen` profili Kitchen'a karşı açılmamalıdır: reddedilen olay Order outbox'ında kalıcı hataya düşer ve aynı siparişin sonraki olaylarını durdurur.

### 11.1 Taleplerin durumu

Beklenen yanıtların ayrıntısı Order sorumlusuyla ayrıca paylaşılır; `KO2-xx` numaraları o listedeki maddelerdir. "Ekip kararı", yalnız Order'ın veya yalnız Kitchen'ın veremeyeceği kararları gösterir.

| Order talebi | Kitchen tarafında hazır | Açık kalan | Sonraki gerçek entegrasyona kaldı |
|---|---|---|---|
| Olay alım ucu | Uç var; yalnız sipariş kabulünü işler; varsayılan kapalı | Ortak ortamda HTTPS (ekip kararı, KO2-07) | Gerçek bağlantı ve etkinleştirme |
| Servis imzası, restoran kapsamı, HTTPS | Doğrulama hazır; anahtar rotasyonu yerelde denendi | `kid` ve açık anahtar paylaşımı (KO2-07); HTTPS | Ortak ortam denemesi |
| `eventType: "OrderAccepted"` | Hazır | – | – |
| Zorunlu `tableId`, isteğe bağlı `tableNumber` | `tableId` kabul ediliyor. `tableNumber` **zorunlu kaldı** | – (`dev`'deki Order numarayı sipariş anında alıp gönderir) | – |
| Fiyat ve misafir bilgisi kabul etmeme | Zaten vardı | – | – |
| Seçenek, alan ve boyut sınırları | Hazır; saklanamayan metin açıkça reddediliyor | Order'ın kalem adedi ayarı, ortak sınır belirlenmeden 999 üstüne açılmamalı (KO2-09) | – |
| Tekrar teslim ve yeniden gönderim | Reddedilen veya geçici hata alan olay iz bırakmıyor; aynı gövde yeniden değerlendiriliyor. Yalnız `tableId`'si farklı tekrar ayırt edilemiyor | Otomatik yeniden sıraya alma yok; `dev`'de tek teslim için operatör betiği var (KO2-04) | – |
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

### 11.2 Uç ve güvenlik koşulları

`POST /api/v1/internal/kitchen/order-events`, `Content-Type: application/json`. Doğrudan `kitchen-service`'e gider; API gateway bu yolu yönlendirmez.

#### 11.2.1 Yapılandırma

| Ayar | Anlamı |
|---|---|
| `KITCHEN_ORDER_CALLER_PUBLIC_KEYS` | JSON nesnesi: `kid` → Base64 X.509 RSA açık anahtar (en az 2048 bit). Order'ın `ORDER_KITCHEN_CALLER_PRIVATE_KEY` anahtarının açık yarısı; `kid`, `ORDER_KITCHEN_CALLER_KEY_ID` ile aynı olmalı |
| `ORDER_KITCHEN_SERVICE_RESTAURANTS` | Order'ın olay gönderebileceği restoran UUID'leri, virgülle ayrılmış, küçük harfli standart biçim. PR #47 aynı adı Order'da da kullanır |

- İkisi birlikte dolu değilse uç **kapalıdır**; her istek `401` alır.
- İkisi dolu ve biri kullanılamaz haldeyse Kitchen başlamaz; hata iletisi değeri yazmaz.
- Compose bu iki ayarı yalnız `kitchen-service`'e verir. `.env.example` içinde boşturlar. Özel anahtar Kitchen'a verilmez.
- Her ortamda ayrı anahtar çifti kullanılmalıdır.

**Anahtar değiştirme sırası:** (1) yeni açık anahtar, yeni `kid` ile Kitchen haritasında eskisinin yanına eklenir ve Kitchen yeniden başlatılır; (2) Order yeni `kid` ile imzalamaya geçer; (3) eski anahtar haritadan çıkarılır ve Kitchen yeniden başlatılır. İmzalayan önce değiştirilirse istekler `401` alır.

`ORDER_KITCHEN_SERVICE_RESTAURANTS` ortak `.env.example` dosyasında **bir kez** bulunur; Order ve Kitchen alıcısı aynı satırı kullanır. İkinci kez eklenmemelidir: sonradan gelen boş bir tekrar doldurulan değeri ezer.

#### 11.2.2 HTTPS

Kitchen yalnız **sunucunun kendisinin güvenli gördüğü** bağlantıyı kabul eder; `X-Forwarded-Proto` ve `Forwarded` başlıklarına güvenmez. Bu depodaki compose düzeninde Kitchen için TLS dinleyicisi yoktur. Bu nedenle ortak ortamda imzalı istek de `403 HTTPS_REQUIRED` alır. TLS'i bir vekilde sonlandırmak, ayrıca kararlaştırılması gereken bir güven ayarı ister (KO2-07).

#### 11.2.3 İmzalı kimlik

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

#### 11.2.4 Kimliğin yeniden kullanılması (`jti`)

Kitchen `jti` zorunlu tutar, fakat **saklamaz**. Gerekçe:

- İmza yöntemi, yolu, restoranı ve gövdenin özetini bağlar. Ele geçirilen bir kimlikle yalnız **aynı gövde**, aynı uca, en çok 30 saniye içinde gönderilebilir.
- Uygulanan tek olay sipariş kabulüdür. `eventId` ve restoran–sipariş çifti veritabanında kalıcı olarak tekildir; Kitchen sipariş silmez. Aynı gövde ikinci sipariş oluşturmaz ve hazırlık ya da iptal durumunu geri almaz.
- İlk isteği Kitchen'a ulaşmadan yakalayıp sonradan gönderen biri, yalnız Order'ın imzaladığı gerçek siparişi oluşturabilir.

Kalan risk: süre içinde aynı isteğin çok kez gönderilmesi yinelenen sorgu, log ve sayaç yükü üretir; hız sınırı yoktur. Bu gerekçe **yalnız** bugünkü davranış için geçerlidir. Yeni bir olay türü (ör. iptal) uygulanmadan önce `eventId` ile aynı kalıcılıkta tekilleştirilmelidir.

### 11.3 Sipariş kabul sözleşmesi (`schemaVersion` 1)

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

Örnek gövde (`dev`'deki Order `tableNumber` değerini gönderir):

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

### 11.4 Yanıtlar

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

PR #47'de bir olay en çok 5 kez denenir ve teslim edilmeyen olay aynı siparişin sonraki olaylarını bekletir. Başarısız tek bir teslim, `dev`'deki `backend/services/order-service/ops/requeue-kitchen-delivery.sql` operatör betiğiyle yeniden sıraya alınır; **otomatik** yeniden sıraya alma yoktur.

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

#### Tekrar teslim

İçerik karşılaştırması sipariş sürümünü, masa oturumunu, masa ve sipariş numarasını ve bütün kalemleri kapsar. Sipariş numarası, masa numarası ve ürün adının baş ve son boşlukları yok sayılır. `occurredAt` ve `correlationId` saklanmadığı için karşılaştırılmaz. `tableId` de saklanmaz: **yalnız `tableId` değeri farklı olan bir tekrar `204` alır.** İlk kabul olayı, sipariş hazırlanmaya başladıktan veya iptal edildikten sonra da aynı olay sayılır.

### 11.5 İptal olayları

`OrderCancellationRequested`, `OrderCancelled`, `OrderItemsCancelled` ve `OrderCancellationRejected` için Kitchen yalnız kimliği ve restoran kapsamını doğrular, sonra `422` döner. Olayın diğer alanları işlenmez, hiçbir kayıt oluşmaz. Hiçbir olay kısmi iptali tüm siparişin iptaline çevirmez. Uygulanmayan olaya `204` dönülmez.

Kitchen'ın mevcut karar servisi yalnız **kendi açtığı** iptal talebinin kararını uygular. Bu servis `requestId` (Kitchen'ın kimliği), `orderVersion` ve `decidedAt` ister; Kitchen bunların hiçbirini uydurmaz.

#### 11.5.1 Kesin tam iptal (henüz uygulanmıyor)

Belirlenen neden biçimi: `"reasons": [ { "code": "CHANGED_MIND" } ]`; neden yoksa `"reasons": []`. Serbest metin gönderilmez; sonradan eklenen neden ayrı olay üretmez. `dev`'deki Order bu alanı `OrderCancelled` mesajında gönderir. Bu biçimdeki mesaj `WAITING`, `PREPARING` ve `READY` durumlarının hepsinde `422` alır.

Bugünkü sonuçları:

- Order'da iptal kesinleşse de sipariş mutfak kuyruğunda kalır ve mutfak hazırlamaya devam edebilir.
- Bu siparişe sonradan gönderilecek hazırlık bilgisine Order `IGNORED_ORDER_CLOSED`, yeni bir Kitchen iptal talebine `ORDER_ALREADY_CANCELLED` döner. Daha önce işlenmiş aynı talebin tekrarında ilk karar geçerlidir.
- Uygulanmadan önce karar gereken konular: `READY` siparişin durumu (D-02), o sırada açık Kitchen iptal talebinin nasıl kapanacağı ve yerel talebi olmayan iptalin kalıcı tekilleştirilmesi için şema değişikliği.

#### 11.5.2 Mutfak onayı bekleyen iptal (henüz uygulanmıyor)

Order bu talebi süre dolunca kendiliğinden reddetmez; 15 dakika yalnız yönetici görünümündeki dikkat süresidir. Talep açıkken hazırlık `READY`'ye ilerleyebilir ve `READY` talebi kapatmaz. Sonradan verilen onayı Order yeniden denetler ve `ORDER_ALREADY_PREPARED` ile reddedebilir. Açık talep varken Kitchen'ın yeni talebine `409 CANCELLATION_ALREADY_PENDING` ve mevcut `cancellationId` döner; karar o talep üzerinden beklenir.

Kitchen bugün Order'ın açık talebini **bilemez** (olay `422` alır). Bu yüzden aynı sipariş için yeni yerel talep kabul edebilir (`202`). Gönderim kurulduğunda Order bunu `409` ile reddeder ve yerel talebi kapatacak bir yol bugün yoktur. Bu akış kurulmadan Kitchen iptal talebi gönderimi açılmamalıdır.

### 11.6 Kitchen → Order

**Gönderim yoktur.** İstemci, zamanlayıcı, imzalama ve Kitchen özel anahtarı eklenmedi; olaylar Kitchen outbox'ında bekler ve teslim edilmiş işaretlenmez.

| Outbox olayı | Gövdede hazır olan |
|---|---|
| `KitchenPreparationStarted` | `preparationStatus: PREPARING`, `startedAt` |
| `KitchenOrderReady` | `preparationStatus: READY`, `startedAt`, `readyAt` |
| `KitchenCancellationRequested` | `requestId`, `orderId`, `reasonCode` (`OUT_OF_STOCK`, `CUSTOMER_REQUEST`, `KITCHEN_ERROR`, `OTHER`), stok nedeninde `productId`, `orderItemId`; **yalnız `OTHER`'da** `reasonText` (diğer kodlarda alan hiç yoktur) |

Her olayın `eventId` değeri saklanır; tekrar denemede aynı kalır. İptal talebi bütün siparişi kapsar. Order'a eşlemede `cancelAll: true` ve `OUT_OF_STOCK` → `ITEM_UNAVAILABLE` gönderim geliştirilirken uygulanacaktır; diğer üç kod Order kataloğunda aynı adla vardır.

**`OTHER` açıklaması.** Personel API'sinde (`POST /api/v1/kitchen/orders/{orderId}/cancellation-requests`) `OTHER` için `reasonText` zorunludur. Baştaki ve sondaki boşluklar atıldıktan sonra 1–300 karakter olmalıdır; uzunluk Order'daki gibi sayılır (bir emoji 2). Metin eksikse, boşsa, yalnız boşluksa (bölünmez boşluk dahil), 300'ü aşıyorsa ya da ASCII dışı kontrol karakteri veya eşsiz surrogate içeriyorsa yanıt `400 INVALID_REASON_TEXT` olur. ASCII kontrol karakteri ve 500'ü aşan metin genel doğrulamada `code` alanı olmayan `400` alır. İki durumda da kayıt oluşmaz. Diğer kodlarda metin isteğe bağlıdır (en çok 500) ve Kitchen'da kalır. (§11.5.1'deki "serbest metin gönderilmez" kuralı yalnız Order → Kitchen tam iptal mesajı içindir.)

> **Metni misafir okuyabilir.** Gönderim kurulduğunda `OTHER` açıklamasını Order'daki iptal ayrıntısında **siparişin sahibi olan misafir** ile aynı restoranın yetkili garsonu ve yöneticisi okuyabilir. Metin için ayrı bir otomatik silme süresi görülmemiştir. Açıklamaya kişisel bilgi veya mutfak içi not yazılmamalıdır. Personel ekranında bu uyarı henüz yoktur; bu çalışmada frontend değiştirilmedi.

Bu kuraldan önce açıklamasız kaydedilmiş bir `OTHER` talebinin olayında metin yoktur ve Order onu reddeder; sonradan metin üretilmez. Aynı `Idempotency-Key` ile açıklamasız tekrar artık `400` alır.

Sıra kuralı hazırdır:

- Bir siparişin `KitchenOrderReady` kaydı, aynı siparişin `KitchenPreparationStarted` kaydı Order hedefine **teslim edilmeden** gönderime alınmaz.
- Hazırlık olayı tekrar beklerken veya kalıcı olarak başarısızken `READY` bekler ve deneme hakkı harcamaz. Mutfaktaki `READY` durumu değişmez.
- Başka siparişler, başka restoranlar ve bildirim teslimi etkilenmez.

#### 11.6.1 Order'ın iptal talebi yanıtı

PR #47, Kitchen'ın **kendi** iptal talebinin sonucunu Kitchen'a sonradan olay olarak göndermez; sonuç yalnız HTTP yanıtında gelir. Bu yanıt için yerel doğrulama hazırdır (`OrderCancellationReceipt`); onu çağıran bir istemci yoktur.

| Yanıt | Yerel sonuç |
|---|---|
| `outcome` (`APPLIED` veya `REJECTED`), eşleşen `orderId`, `cancellationId`, `scope: FULL`, `orderVersion`, `decidedAt`; rette `rejectionCode` | Karar mesajı oluşur; karar servisinin kuralları uygulanır (eski sürüm uygulanmaz; `READY` sipariş iptal edilmez, uzlaştırma kaydı oluşur) |
| Bu alanlardan biri eksik (72 saat sonrasındaki tekrar yanıtı dahil) | **Eksik:** karar oluşmaz, hiçbir kayıt değişmez, talep `REQUESTED` kalır |
| Başka sipariş, kısmi kapsam, bilinmeyen sonuç veya onayda `rejectionCode` | **Uyuşmazlık:** hiçbir şey uygulanmaz |

Yanıtın kendi olay kimliği olmadığı için karar, gönderilen Kitchen olayının `eventId` değeriyle tekilleştirilir; aynı yanıtın tekrarı ikinci kez uygulanmaz.

**Kalan risk:** Eksik yanıtta `outcome: APPLIED` ise Order siparişi iptal etmiştir, Kitchen ise siparişi aktif görmeye devam eder. Bu durum yalnız talebin `REQUESTED` kalmasından anlaşılır; uzlaştırmayı kimin nasıl yapacağı belirlenmemiştir. Order'ın `eventId` ile ilk karar bağını inbox saklama süresi boyunca koruma önerisi uygulanırsa geç tekrar da eksiksiz olabilir; bu öneri henüz uygulanmamıştır.

#### 11.6.2 Gönderim geliştirilirken yanıtların eşlenmesi (öneri, uygulanmadı)

| Order'ın yanıtı | Kitchen teslim kaydı |
|---|---|
| Hazırlık olayına `200`: `APPLIED`, `IGNORED_STALE`, `IGNORED_ORDER_CLOSED` | Teslim edildi |
| İptal talebine eksiksiz `200` | Karar önce yerelde uygulanır, sonra teslim edildi |
| İptal talebine eksik veya uyuşmayan `200` | Teslim edildi sayılmaz; görünür bir hata koduyla (ör. `ORDER_RECEIPT_INCOMPLETE`) uzlaştırma bekler |
| `409 ORDER_ALREADY_CANCELLED`, `409 CANCELLATION_ALREADY_PENDING` | Başarısız; hata kodu saklanır. Yerel talebin kapanışı §11.5.1 ve §11.5.2'deki kararlara bağlıdır |
| Zaman aşımı, bağlantı hatası, `5xx` | Sonuç belirsiz; aynı `eventId` ile yeniden denenir |

### 11.7 Doğrulamanın sınırı

Davranış Kitchen'ın yerel testleriyle doğrulandı: test amaçlı imzalayıcı, test sırasında üretilen anahtarlar ve gerçek sunucuda geçici sertifikalı TLS. Order yanıtları testlerde PR #47'nin yanıt biçimine göre elle yazıldı. PR #47'nin imzalayıcı sınıfının ürettiği kimlik, önceki bir yerel denemede Kitchen doğrulayıcısından geçti. **Çalışan bir Order servisine bağlanılmadı;** gerçek mesaj akışı, anahtar dağıtımı ve ortak ortam doğrulanmadı.

Yukarıdaki `OTHER` kuralı dışında personel uçları bu bölümün kapsamında değildir (§2–§5).
