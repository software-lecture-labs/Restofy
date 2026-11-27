# Waiter Service — Entegrasyon Rehberi

Bu rehber, Waiter'ın Auth, QR ve Order ile nasıl konuştuğunu kodda **gerçekten bulunan** hâliyle anlatır. Waiter PR #39 (QR) ve PR #40 (Order) birlikte merge edildiğinde geçerlidir. Karşı servislerin kendi rehberleri: [QR entegrasyon rehberi](../qr/entegrasyon-rehberi.md), [Waiter → Order entegrasyon rehberi](../order-service/entegrasyon-rehberi.md), [Order API sözleşmesi](../order-service/INTEGRATION_CONTRACTS.md).

| Durum | Kapsam |
| --- | --- |
| **UYGULANDI** | Bu belgedeki bütün uçlar, imzalar, eşlemeler ve hata davranışları Waiter kodunda var ve sahte karşı servislere karşı testli (Waiter `verify`: 656 test). |
| **DOĞRULANMADI** | Gerçek Auth, QR ve Order servisleriyle uçtan uca çağrı. Bkz. [§9](#9-uçtan-uca-doğrulama-kontrol-listesi). |

## 1. Genel bakış

| Karşı servis | Waiter ne için çağırır | Kimlik |
| --- | --- | --- |
| **Auth** | Her personel isteğinde oturumun hâlâ geçerli olduğunu sormak | Personelin `restofy_access` çerezi |
| **QR** | Misafir doğrulama, masa listesi/detayı, oturum kapatma, masa temizleme ve bunların sonuç sorguları | Waiter servis JWT'si (`aud=restofy-qr`), kapatma/temizleme POST'larında ek olarak personel delegasyonu |
| **Order** | Masa başı aktif sipariş sayısı, sipariş arama, detay, iptaller, "servis edildi" komutu ve sonuç sorgusu | Toplu okumalar ve serve için Waiter servis JWT'si (`aud=restofy-order`) ile personel delegasyonu; detay ve iptallerde `restofy_access` çerezi |

Waiter, karşı servisleri Gateway üzerinden değil, doğrudan `QR_SERVICE_URL`, `ORDER_SERVICE_URL` ve `AUTH_SERVICE_URL` adresleriyle çağırır. Başka bir servisin veritabanına erişmez. Masa ve oturumun sahibi QR, sipariş ve "servis edildi" bilgisinin sahibi Order'dır. Waiter yalnızca kendi garson çağrılarını ve gönderdiği komutların teknik kaydını tutar.

## 2. Gelen istekler: personel, misafir ve CSRF

### 2.1 Personel (Auth)

- Kimlik bilgisi Auth'un `restofy_access` çerezidir (HttpOnly). Waiter bu JWT'yi önce **yerelde** doğrular: RS256 imzası (`WAITER_AUTH_STAFF_PUBLIC_KEY`), `JWT_ISSUER`, `restofy-staff-api` audience, `AUTHENTICATED` oturum, `q5-idle-v1` politikası, tutarlı `iat`/`nbf`/`exp` ve geçerli kullanıcı/restoran/oturum kimlikleri.
- Ardından **tazelik** kontrolü yapılır: her istekte aynı çerezle `GET {AUTH_SERVICE_URL}/api/v1/auth/session` çağrılır. İptal edilmiş, boşta süresi dolmuş, rolü değişmiş veya devre dışı kalmış oturum anında reddedilir. Cevap önbelleğe alınmaz ve personel aktivitesi sayılmaz.
- Geçersiz kimlik `401` döner. Auth cevap veremezse istek "geçerli" de "geçersiz" de sayılmaz, bağımlılık hatası olarak döner (bkz. [§7](#7-waiterın-kendi-hata-cevapları)).
- `WAITER_AUTH_STAFF_PUBLIC_KEY` boşsa personel kimlik doğrulaması kapalıdır ve her personel isteği `401` alır. Anahtar ayarlıysa `AUTH_SERVICE_URL` HTTPS olmalıdır.
- `/api/v1/waiters/**` uçları `WAITER` rolü ister. Restoran, kullanıcı ve rol her zaman doğrulanmış token'dan alınır; istekteki `restaurantId` veya rol bilgisine güvenilmez.

### 2.2 Misafir (QR guest çerezi)

- Müşteriye açık tek uç `POST /api/v1/waiters/calls` (garson çağırma) ucudur. Kimlik bilgisi QR'ın verdiği HttpOnly `restofy_guest` çerezidir (ADR-QR-002 K3/K4).
- Waiter çerezi her istekte QR'a doğrulatır (bkz. [§3.1](#31-uçlar)). Restoran, masa, masa oturumu ve misafir oturumu **QR'ın cevabından** alınır, istekteki hiçbir alana güvenilmez.
- QR misafiri neden reddettiğini söylemez (bilinmeyen, bitmiş, süresi dolmuş veya oturumu kapanmış). Waiter de söylemez ve `401` döner. Eski `X-Guest-Token` başlığı artık kimlik değildir, gönderilse de yok sayılır.
- Personel çerezi misafir kimliği, misafir çerezi de personel kimliği yerine geçmez.

### 2.3 CSRF ve Origin

Tarayıcının kendiliğinden eklediği her iki çerez de CSRF koruması gerektirir. Bu yüzden her değiştirici istek için **double submit** ve **Origin** kanıtı istenir:

| İstek | Çerez (Auth/QR verir) | Başlık (aynı değer) |
| --- | --- | --- |
| Misafir çağrısı `POST /api/v1/waiters/calls` | `restofy_guest_csrf` | `X-Guest-CSRF` (ADR-QR-002 K6) |
| Diğer bütün personel değişiklikleri | `restofy_csrf` | `X-CSRF-TOKEN` |

- İstekte tam olarak **bir** `Origin` olmalı ve bu değer `CORS_ALLOWED_ORIGINS` listesinde bulunmalı.
- Kanıt eksik, çift, süresi dolmuş veya farklıysa istek kimlik doğrulamaya ulaşmadan `403` alır. CSRF koruması hiçbir uç için kapatılmaz.
- Waiter CSRF çerezi yazmaz. İki çerez de Auth'a ve QR'a aittir.

## 3. Waiter → QR

### 3.1 Uçlar

Her çağrı Waiter servis JWT'siyle imzalanır ([§5](#5-servis-kimliği-ve-personel-delegasyonu)). Kapsam, uç ailesine göre değişir:

| Waiter işlemi | QR ucu | Kapsam | `X-Restofy-Staff-Delegation` |
| --- | --- | --- | --- |
| Misafir doğrulama | `POST /api/v1/internal/qr/guest-sessions/validate` | `qr:guest-validate` | yok |
| Masa listesi | `GET /api/v1/internal/tables?restaurantId={id}&page={n}&size=200` | `qr:table-read` | yok |
| Masa detayı | `GET /api/v1/internal/tables/{tableId}?restaurantId={id}` | `qr:table-read` | yok |
| Oturum kapatma | `POST /api/v1/internal/tables/{tableId}/sessions/{tableSessionId}/close` | `qr:session-close` | **var** |
| Kapatma sonucu | `GET /api/v1/internal/tables/{tableId}/sessions/{tableSessionId}/close-commands/{commandId}?restaurantId={id}` | `qr:session-close` | yok |
| Masa temizlendi | `POST /api/v1/internal/tables/{tableId}/cleaned` | `qr:table-clean` | **var** |
| Temizleme sonucu | `GET /api/v1/internal/tables/{tableId}/clean-commands/{commandId}?restaurantId={id}` | `qr:table-clean` | yok |

- Masa listesi 200'lük sayfalarla okunur. En fazla 500 masa desteklenir; daha büyük restoranlarda liste kesilmez, istek `503 TABLE_SNAPSHOT_LIMIT_EXCEEDED` ile başarısız olur.
- Sonuç sorguları yalnız servis kimliğiyle yapılır. Böylece personel isteği olmayan arka plandaki uzlaştırıcı da ([§6.3](#63-kayıp-yanıt-uzlaştırması)) bu sorguları yapabilir.
- Kapatma ve temizleme POST'larında `Idempotency-Key` başlığı gövdedeki `commandId` ile aynıdır.

### 3.2 DTO'lar

**Masa** (`QrTableDto`): `id`, `restaurantId`, `tableNumber`, `displayName`, `capacity`, `operationalStatus` (`AVAILABLE`, `OCCUPIED`, `CLEANING`, `DISABLED`), `active`, `version`, `currentSession`.
**Oturum** (`QrSessionDto`): `id`, `status` (`OPEN`, `CLOSING`), `openedAt`, `lastActivityAt`, `scanCount`, `version`.
**Misafir bağlamı** (`QrGuestContextDto`): `restaurantId`, `tableId`, `tableSessionId`, `guestSessionId`, `state` (yalnız `ACTIVE` kabul edilir), `checkedAt`.
**Kapatma isteği** (`QrCloseRequestDto`): `commandId`, `restaurantId`, `tableId`, `tableSessionId`, `expectedVersion`, `requestedByUserId`, `reason`, `requestedAt`.
**Kapatma sonucu** (`QrCloseResultDto`): `commandId`, `state` (`CLOSED`, `CLOSING`, `REJECTED`), `tableId`, `tableSessionId`, `closedAt`, `guestSessions {total, ended}`, `table`, `rejectionCode`, `blockers`.
**Temizleme isteği** (`QrCleanRequestDto`): `commandId`, `restaurantId`, `tableId`, `expectedVersion`, `requestedByUserId`, `requestedAt`.
**Temizleme sonucu** (`QrCleanResultDto`): `commandId`, `status` (`APPLIED`, `DUPLICATE`, `REJECTED`), `rejectionCode`, `table`.

Waiter, QR cevaplarında bilinmeyen alanları yok sayar. Böylece QR'ın eklediği yeni bir alan sonucu belirsiz hâle getirmez. Kimlikler, enum değerleri ve aşağıdaki çapraz kontroller ise her zaman uygulanır.

### 3.3 Örnek: oturum kapatma

Değerler temsilidir, gerçek veri veya sır değildir.

~~~http
POST /api/v1/internal/tables/7c1d…/sessions/0d21…/close
Authorization: Service <RS256 JWT, scope=qr:session-close>
X-Restofy-Staff-Delegation: <personelin restofy_access JWT'si>
Idempotency-Key: 2f82e52b-5db9-4d2b-8eff-150b120a6731
Content-Type: application/json

{
  "commandId": "2f82e52b-5db9-4d2b-8eff-150b120a6731",
  "restaurantId": "d51abfce-3d70-472b-8f1c-acaa39f8581c",
  "tableId": "7c1d…",
  "tableSessionId": "0d21…",
  "expectedVersion": 4,
  "requestedByUserId": "9337b3b0-52dd-48a2-93b2-179632885948",
  "reason": "…",
  "requestedAt": "2026-11-27T11:20:00Z"
}
~~~

~~~json
{
  "commandId": "2f82e52b-5db9-4d2b-8eff-150b120a6731",
  "state": "CLOSED",
  "tableId": "7c1d…",
  "tableSessionId": "0d21…",
  "closedAt": "2026-11-27T11:20:01Z",
  "guestSessions": { "total": 3, "ended": 3 },
  "table": { "id": "7c1d…", "operationalStatus": "CLEANING", "…": "…" },
  "rejectionCode": null,
  "blockers": []
}
~~~

Waiter'ın kapatma cevabında kontrol ettikleri:
- `200` ile yalnız `CLOSED`, `202` ile yalnız `CLOSING` kabul edilir. Durum kodu ile `state` uyuşmazsa cevap güvenilmez sayılır.
- `commandId`, `tableId` ve `tableSessionId` istekle aynı olmalı. `table` varsa doğru restorana ait olmalı.
- `CLOSED` olan bir oturum masayı `CLEANING` durumunda bırakmış olmalı.

### 3.4 QR hata kodları

| İşlem | QR'ın kesin ret kodları |
| --- | --- |
| Kapatma | `SESSION_NOT_FOUND`, `SESSION_TABLE_MISMATCH`, `SESSION_VERSION_CONFLICT`, `SESSION_NOT_OPEN`, `SESSION_ALREADY_CLOSED`, `CLOSURE_BLOCKED` (`blockers` ile), `COMMAND_CONFLICT` |
| Temizleme | `TABLE_NOT_FOUND`, `TABLE_VERSION_CONFLICT`, `TABLE_NOT_CLEANING`, `TABLE_SESSION_ACTIVE`, `COMMAND_CONFLICT` |
| Misafir doğrulama | `GUEST_SESSION_UNAVAILABLE` (QR PR #44; geçiş süresince eski `detail` metni de tanınır) |

- `400`, `404`, `409` ve `422` kesin ret sayılır. Bu listede olmayan kodlar genel `QR_REJECTED` koduna çevrilir, böylece karşı servisin metni dışarı yansımaz.
- `401` ve `403`, QR'ın **Waiter'ı** reddettiği anlamına gelir; komut işlenmemiştir. Bu durum bağımlılık reddi (`REJECTED`) olarak raporlanır, hiçbir zaman "kapandı" ya da "komut reddedildi" sayılmaz.
- Misafir doğrulamasındaki `404` yalnız `GUEST_SESSION_UNAVAILABLE` koduyla geldiğinde "misafir yok" anlamına gelir. Başka bir `404` (örneğin olmayan bir yol) kullanılabilir cevap sayılmaz.

## 4. Waiter → Order

Uç ayrıntıları ve Order tarafının kuralları için: [Waiter → Order entegrasyon rehberi](../order-service/entegrasyon-rehberi.md).

### 4.1 Uçlar

| Waiter işlemi | Order ucu | Kimlik |
| --- | --- | --- |
| Masa başına aktif sipariş sayısı | `POST /api/v1/orders/session-summaries` | `order:read` imzası + `X-Restofy-Staff-Delegation` |
| Sipariş listesi, hazır kuyruk, geçmiş | `POST /api/v1/orders/search` | `order:read` imzası + `X-Restofy-Staff-Delegation` |
| Sipariş detayı | `GET /api/v1/orders/{orderId}` | `restofy_access` çerezi |
| İptal kayıtları | `GET /api/v1/orders/{orderId}/cancellations` | `restofy_access` çerezi |
| "Servis edildi" komutu | `POST /api/v1/internal/orders/{orderId}/serve` | `order:serve` imzası + `X-Restofy-Staff-Delegation` + `Idempotency-Key` |
| Komut sonucu | `GET /api/v1/internal/orders/{orderId}/serve-commands/{commandId}?restaurantId={id}` | `order:serve` imzası + `X-Restofy-Staff-Delegation` |

- İmzalı POST'larda `Cookie`, `Origin` ve gövdede `restaurantId` gönderilmez. Order restoranı imzadan ve personel kimliğinden alır.
- Oturum listesi boşsa Order çağrılmaz. Bir istekte en fazla 500 oturum kimliği gönderilir. Cevapta yer almayan oturumun sayısı **0** kabul edilir.
- Sayfalama: `page` 0 tabanlıdır, sayfa boyutu `limit` alanında gider, toplam `totalItems` alanından okunur. Order filtreyi sayfalamadan önce uygular; Waiter dönen sayfanın filtreye uyup uymadığını kontrol eder, gelen sayfayı tekrar filtreleyip toplamı yeniden hesaplamaz.
- `ORDER_SERVICE_URL` HTTPS değilse personel bilgisi Order'a **hiç gönderilmez**; istek `503` ("Order's staff connection requires HTTPS") ile biter. Bunun tek istisnası, `test` profilinde loopback adresine yapılan çağrılardır.

### 4.2 Sipariş durumları ve geçişler

Order'ın `fulfillmentStatus` değeri Waiter modeline şöyle eşlenir:

| Order `fulfillmentStatus` | Waiter iş durumu | Waiter hazırlık durumu | Teslim |
| --- | --- | --- | --- |
| `RECEIVED` | `ACCEPTED` | `WAITING` | `PENDING` |
| `PREPARING` | `ACCEPTED` | `PREPARING` | `PENDING` |
| `READY` | `ACCEPTED` | `READY` | `PENDING` |
| `SERVED` | `ACCEPTED` | `READY` | `SERVED` |
| `COMPLETED` | `COMPLETED` | `READY` | `SERVED` |
| `CANCELLED` | `CANCELLED` | `CANCELLED` | `PENDING` |

- Kısmi iptali olan sipariş `PARTIALLY_CANCELLED` olarak görünür. Ödeme durumu `NOT_REQUESTED` → `NOT_PAID`, `PENDING` → `PENDING`, `PAID` → `PAID` olarak eşlenir. İptal kayıtları `REQUESTED` → `REQUESTED`, `APPLIED` → `APPROVED`, `REJECTED` → `REJECTED` olarak eşlenir.
- Waiter'ın tetiklediği tek geçiş **`READY → SERVED`** geçişidir ve kararı yalnız Order verir. Waiter hazır olma, iptal ya da sürüm durumunu önceden kendisi kontrol etmez (okuyup ardından yazmak hiçbir şey kanıtlamaz). Personelin gördüğü bağlamla (`tableSessionId`, `expectedVersion`) tek bir idempotent komut gönderir.
- Waiter ekran filtreleri: `ACTIVE` → `view: "active"`, `CANCELLED` → `view: "cancelled"`, `AWAITING_DELIVERY` → `fulfillmentStatuses: ["READY"]`, `READY_HISTORY` → `fulfillmentStatuses: ["READY", "SERVED", "COMPLETED"]`.
- `servedAt`, Order'ın durum geçmişindeki ilk `SERVED` geçişinin zamanıdır ve liste, arama ve detay yanıtlarına eşlenir.

### 4.3 Servis komutunun sonuçları

| Order cevabı | Waiter'ın yorumu |
| --- | --- |
| `200 APPLIED` / `200 DUPLICATE` | Komut uygulandı. `commandId`, `orderId` ve `tableSessionId` komutla karşılaştırılır. |
| `409` + `ORDER_VERSION_CONFLICT`, `ORDER_SESSION_MISMATCH`, `ORDER_NOT_READY`, `ORDER_CANCELLED`, `ORDER_ALREADY_SERVED`, `ORDER_NOT_DELIVERABLE`, `ORDER_COMMAND_CONFLICT` | Kesin ret. Bilinmeyen kodlar `ORDER_REJECTED` koduna çevrilir. |
| `401` / `403` | Order **Waiter'ı** reddetti, komut işlenmedi. Komut reddi olarak kaydedilmez. |
| Zaman aşımı, `5xx`, okunamayan cevap | Sonuç **belirsizdir**. Waiter ne "servis edildi" ne de "başarısız" der. Bkz. [§6.3](#63-kayıp-yanıt-uzlaştırması). |
| Sonuç sorgusu `404` | Order'da kayıt yok, sonuç belirsiz kalır. Bu, komutun uygulanmadığının kanıtı değildir. |

## 5. Servis kimliği ve personel delegasyonu

Waiter, QR ve Order için **ayrı anahtar çiftleri** kullanır. İki imza aynı yapıdadır:

| Claim / başlık | QR | Order |
| --- | --- | --- |
| `Authorization` | `Service <JWT>` | `Service <JWT>` |
| JWT başlığında `kid` | `WAITER_QR_CALLER_KEY_ID` | `WAITER_ORDER_CALLER_KEY_ID` |
| `iss` / `sub` | `restofy-waiter` / `waiter-service` | `restofy-waiter` / `waiter-service` |
| `aud` | `restofy-qr` | `restofy-order` |
| `token_type` | `service` | `service` |
| `scope` | `qr:guest-validate`, `qr:table-read`, `qr:session-close`, `qr:table-clean` | `order:read`, `order:serve` |
| `restaurant_id` | `WAITER_QR_CALLER_RESTAURANT_ID` | İsteği yapan personelin restoranı |
| `method`, `target` | HTTP metodu ve gönderilen ham yol + sorgu dizesi | aynı |
| `body_sha256` | Gönderilen gövde baytlarının SHA-256'sı (GET'te boş gövde) | aynı |
| `staff_sha256` | Yalnız kapatma/temizleme POST'larında | Her imzalı Order çağrısında |
| `iat` = `nbf`, `exp` | En fazla 30 saniye geçerli | aynı |
| `jti` | Her istek için yeni UUID | aynı |

- Gövde **bir kez** JSON baytlarına çevrilir, bu baytlar imzalanır ve HTTP gövdesine aynen yazılır. Böylece `body_sha256` her zaman gönderilen baytlarla eşleşir.
- **Personel delegasyonu:** personelin erişim JWT'si yalnızca istek süresince `X-Restofy-Staff-Delegation` başlığıyla iletilir. `staff_sha256`, bu token'ın UTF-8 baytlarının küçük harfli hex SHA-256 özetidir. Token yalnız o isteğin kimlik doğrulama ayrıntısında (`StaffAccessToken`) tutulur; komut tablosuna, önbelleğe veya loglara yazılmaz ve `toString()` çıktısında görünmez.
- Personel isteği dışında (uzlaştırıcı) token yoktur ve uydurulmaz. Delegasyon gerektiren bir çağrı o durumda karşı servisten `401` alır.
- Anahtar ayarlanmamışsa hiçbir şey imzalanmaz, karşı servis `401` döner ve bu bir bağımlılık reddi olarak raporlanır. Kimlik hiçbir zaman uydurulmaz.

## 6. Zaman aşımı, idempotency, retry ve rate limit

### 6.1 Zaman aşımları

| Ayar | Varsayılan | ENV |
| --- | --- | --- |
| Bağlantı zaman aşımı | 3000 ms | `REST_CLIENT_CONNECT_TIMEOUT_MS` |
| Okuma zaman aşımı | 5000 ms | `REST_CLIENT_READ_TIMEOUT_MS` |

Bu değerler QR, Order ve Notification istemcilerinin hepsi için geçerlidir. Zaman aşımı `504`, erişilemeyen servis `503` olarak döner ([§7](#7-waiterın-kendi-hata-cevapları)).

### 6.2 Idempotency ve retry

- Waiter'ın değiştirici uçları (`POST …/calls`, `…/claim`, `…/resolve`, `…/orders/{id}/served`, `…/tables/{id}/sessions/{sid}/close`, `…/tables/{id}/cleaned`) `Idempotency-Key` başlığını kabul eder.
  - Aynı anahtar ve aynı istekle gelen ve cevabı bilinen bir komut, karşı servise yeniden gitmeden **ilk cevabı** tekrarlar.
  - Aynı anahtar başka bir istekle gelirse `409 IDEMPOTENCY_KEY_REUSED` döner. Aynı komut o anda işleniyorsa da çakışma döner.
- Waiter'ın karşı servise gönderdiği `commandId`, komutun ömrü boyunca sabittir ve QR/Order'a karşı idempotency anahtarı olarak kullanılır (`Idempotency-Key` = `commandId`).
- **Waiter hiçbir komutu kendiliğinden yeniden göndermez.** Cevabı kaybolmuş bir komut için personel aynı `Idempotency-Key` ile isteği tekrarlarsa Waiter önce karşı servise o `commandId` için ne kaydettiğini sorar. Komutu aynı kimlikle **yalnız karşı serviste kayıt yoksa** tekrar gönderir.
- Okumalar (masa listesi, sipariş arama) yeniden denenmez; hata doğrudan çağırana döner.

### 6.3 Kayıp yanıt uzlaştırması

- **Masa komutları (QR):** `TableCommandReconciler` arka planda her 5 saniyede bir, en fazla 20 komut alır. 20 saniyeden eski ve 48 saatten genç, sonucu bilinmeyen kapatma ve temizleme komutları için QR'a sonuç sorgusu yapar (`close-commands`, `clean-commands`). Komutu **asla yeniden göndermez**. QR "kapandı" derse o oturuma ait garson çağrıları da kapatılır. QR erişilemezse yalnız o komutların bir sonraki kontrolü ertelenir; hiçbir çağrı yeniden açılmaz, hiçbir oturuma dokunulmaz.
- **Servis komutu (Order):** sonuç bilinmiyorsa `GET /api/v1/orders/{orderId}/served-commands/{commandId}` (Waiter'ın ucu) Order'a sonuç sorgusu yapar. Order `APPLIED` derse komut onaylanır, `REJECTED` derse ret kodu kaydedilir, `404` derse sonuç belirsiz kalır.
- Waiter'ın komut durumları: `PENDING`, `IN_PROGRESS`, `COMPLETED`, `REJECTED`, `UNKNOWN`. Kapatma `CLOSING` (QR `202`) ile dönerse Waiter `202 Accepted` döner ve sonuç `GET /api/v1/waiters/tables/{tableId}/commands/{commandId}` ile izlenir.
- **Otomatik QR kapanışı:** QR'ın Waiter'a sormadan kapattığı oturumlar için kapanış olayı ucu (`session-closures`, `qr:session-closure-read`) Waiter'da **UYGULANMADI**. Bu tür kapanışlar Waiter'a yalnız masa okumalarında ve uzlaştırıcının sonuç sorgularında yansır.

### 6.4 Misafir çağrısı rate limit'i (ADR-WAITER-002)

- Bir misafir oturumu kayan 600 saniyelik pencerede en fazla **6 yeni çağrı** açabilir. Sayaç veritabanında tutulur, bu yüzden birden fazla Waiter örneğinde de tutarlıdır.
- Limit aşılırsa `429 Too Many Requests`, `code: CALL_RATE_LIMITED` ve `Retry-After` (saniye) döner.
- Bu değerler ENV değil, politika ayarıdır (`restofy.calls.guest-rate-limit.*`).

## 7. Waiter'ın kendi hata cevapları

Bütün hatalar `application/problem+json` biçimindedir ve sabit bir `code` alanı taşır.

| Durum | HTTP | `code` örnekleri |
| --- | --- | --- |
| Kimlik yok veya geçersiz | `401` | `AUTHENTICATION_REQUIRED` |
| Rol yetersiz, CSRF/Origin kanıtı eksik | `403` | `ACCESS_DENIED` |
| Geçersiz istek | `400` | `INVALID_REQUEST` |
| Kayıt bulunamadı | `404` | `CALL_NOT_FOUND`, `TABLE_NOT_FOUND`, `ORDER_NOT_FOUND`, `TABLE_COMMAND_NOT_FOUND` |
| İş kuralı / karşı servisin kesin reddi | `409` | `CALL_ALREADY_CLAIMED`, `CALL_ALREADY_RESOLVED`, `IDEMPOTENCY_KEY_REUSED`, `CLOSE_ALREADY_OPEN`, `CLEAN_ALREADY_OPEN`, QR ve Order ret kodları |
| Rate limit | `429` | `CALL_RATE_LIMITED` + `Retry-After` |
| Karşı servis zaman aşımı | `504` | — |
| Karşı servis erişilemez, HTTPS yok, masa limiti | `503` | `TABLE_SNAPSHOT_LIMIT_EXCEEDED` |
| Karşı servis Waiter'ı reddetti (`401`/`403`) veya cevabı güvenilmez | `502` | — |
| Beklenmeyen hata | `500` | `INTERNAL_ERROR` |

Karşı servis hataları hiçbir zaman boş listeye, sıfır sayıya, "kapandı" ya da "servis edildi" sonucuna çevrilmez.

## 8. Yapılandırma

| ENV | Nerede | Açıklama |
| --- | --- | --- |
| `AUTH_SERVICE_URL` | Waiter | Auth adresi. Personel doğrulaması açıksa HTTPS olmalı. |
| `WAITER_AUTH_STAFF_PUBLIC_KEY` | Waiter | Auth'un `AUTH_JWT_PRIVATE_KEY` anahtarına karşılık gelen Base64 X.509 RSA açık anahtar. Boşsa her personel isteği `401` alır. |
| `JWT_ISSUER`, `JWT_ACCESS_TTL_SECONDS` | Waiter | Auth'takiyle aynı olmalı. |
| `CORS_ALLOWED_ORIGINS` | Waiter | CSRF için kabul edilen `Origin` listesi. |
| `QR_SERVICE_URL` | Waiter | QR'ın iç adresi. |
| `WAITER_QR_CALLER_KEY_ID` | Waiter | QR'a karşı `kid` (`[A-Za-z0-9._-]{1,64}`). Order'ın `kid` değerinden farklı olmalı. |
| `WAITER_QR_CALLER_PRIVATE_KEY` | Waiter | Base64 PKCS#8 RSA özel anahtar, en az 2048 bit. |
| `WAITER_QR_CALLER_RESTAURANT_ID` | Waiter | QR'ın `AUTH_RESTAURANT_ID` değeriyle aynı olmalı. |
| `QR_WAITER_CALLER_PUBLIC_KEYS` | QR | Waiter'ın QR açık anahtarı, aynı `kid` ile. Boşsa QR Waiter'a güvenmez. |
| `ORDER_SERVICE_URL` | Waiter | Order'ın iç adresi. **HTTPS olmalı.** |
| `WAITER_ORDER_CALLER_KEY_ID` | Waiter | Order'a karşı `kid`. |
| `WAITER_ORDER_CALLER_PRIVATE_KEY` | Waiter | Base64 PKCS#8 RSA özel anahtar, en az 2048 bit. |
| `ORDER_WAITER_SERVICE_PUBLIC_KEYS` | Order | `kid` → Base64 X.509 RSA açık anahtar eşlemesi (JSON). |
| `ORDER_WAITER_SERVICE_RESTAURANTS` | Order | Waiter'ın çağırabileceği restoran UUID'leri (virgülle ayrılmış). Order `order-auth` profiliyle çalışmalı. |
| `REST_CLIENT_CONNECT_TIMEOUT_MS`, `REST_CLIENT_READ_TIMEOUT_MS` | Waiter | [§6.1](#61-zaman-aşımları) |

- QR anahtarının üç değeri birlikte ayarlanmalıdır. Hepsi boşsa imza kapalıdır; yalnız bir kısmı doluysa uygulama açılmaz. Order anahtarının iki değeri için de aynı kural geçerlidir.
- Özel anahtarlar yalnız git'e girmeyen ENV dosyalarında veya bir sır deposunda tutulur. Açık anahtarlar karşı servise aynı `kid` ile verilir.

## 9. Uçtan uca doğrulama kontrol listesi

Aşağıdaki akışlar sahte karşı servislere karşı testli, ancak gerçek servislerle **DOĞRULANMADI**. Gerçek doğrulamanın önündeki engeller:
- `docker-compose.yml` servisler arası TLS içermiyor. Waiter personel bilgisini Auth'a ve Order'a yalnız HTTPS ile gönderdiği için personel adımları şu anki compose ortamında çalışmaz.
- Order ↔ Kitchen olayları (`KitchenOrderReadyMessage` vb.) Kitchen kodunda "önerilen, henüz uzlaşılmamış" olarak işaretli. Sipariş READY durumuna gerçek akışla gelmiyor.

- [ ] **Misafir çağrısı:** QR masa açar → misafir `restofy_guest` çereziyle çağrı açar → personel çağrıyı üstlenip çözer. Yedinci çağrı `429` alır.
- [ ] **Servis edildi:** sipariş READY olur → personel "servis edildi" der → Order `SERVED` yapar → aynı komut tekrarlandığında ilk sürüm ve `servedAt` döner. Eski sürüm, yanlış oturum ve geçersiz imza reddedilir.
- [ ] **Kayıp yanıt (Order):** Order komutu uyguladıktan sonra yanıt kaybolur → Waiter sonuç sorgusuyla ilk kararı bulur. Kayıt yoksa belirsiz durum korunur.
- [ ] **Masa kapatma ve temizlik:** personel oturumu kapatır → QR `CLOSED` ve masa `CLEANING` olur → oturumun çağrıları kapanır → personel masayı temizler → masa `AVAILABLE` olur.
- [ ] **Kayıp yanıt (QR):** kapatma yanıtı kaybolur → uzlaştırıcı sonucu QR'dan bulur ve çağrıları kapatır.
- [ ] **İmza:** yanlış kapsam, süresi dolmuş JWT, değiştirilmiş gövde veya yanlış restoran QR ve Order tarafından reddedilir.
- [ ] **Tenant izolasyonu:** başka restorana ait masa, sipariş veya oturum hiçbir ekranda görünmez.
