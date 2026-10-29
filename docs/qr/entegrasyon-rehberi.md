# QR Service — Entegrasyon Rehberi

**Güncelleme:** 28 Ekim 2026. **Kapsam:** QR-01, QR-02, QR-03 ve QR-04 backend teslimi; Auth entegrasyonu öncesi bakım düzeltmeleri.

Bu rehber gerçek QR kaynak kodundaki sözleşmeleri açıklar. Başka servislerde henüz bulunmayan endpoint'leri varmış gibi tanımlamaz. Java servis metotları ağ üzerinden çağrılabilen API değildir; karşı servis QR domain/JPA sınıflarını import etmemeli veya QR veritabanına bağlanmamalıdır.

Çalışmanın sonraki PR hedefi `feature/qr-service` → `dev`: dört fazın QR backendini ortak geliştirme dalına taşımak ve Auth Service entegrasyonuna zemin hazırlamak. Bu, Auth entegrasyonunun yapılmış veya API'lerin açılmış olduğu anlamına gelmez. PR/merge işlemleri geliştirici tarafından ayrıca yürütülür.

## 1. Teslim kapsamı ve gerçek durum

| Faz | Uygulanan backend kapsamı |
| --- | --- |
| QR-01 | Restoran kapsamlı masa oluşturma/güncelleme, aktiflik, detay/liste, filtreleme/sayfalama, validation, optimistic version ve hata sözleşmesi |
| QR-02.1–02.4 | Rastgele QR kimliği, digest + AES-GCM şifreli saklama, yenileme/eski kimliği geçersizleştirme, aktif/pasif, referans güvenli hard delete, metadata, PNG görüntüleme/indirme |
| QR-03.1–03.4 | QR doğrulama/masa eşleştirme, tek açık ana oturum, her kabul edilen taramaya ayrı guest, tek seferlik guest tokenı, kalıcı kabul edilmiş tarama ve aktif guest istatistiği |
| QR-04.1–04.4 | Bağımsız guest/ortak main idle süreleri, sipariş admission rezervasyonu, sıralı iş/finans sonucu ve tekrar güvenliği, personel onaylı kapanış ve ayrı temizlik işlemi |

**IMPLEMENTED (UYGULANDI):** Bu fazların QR içindeki iş kuralları, yerel PostgreSQL/Flyway şeması ve testleri. Order/Payment koordinasyonunun Java sınırları mevcut; olumlu kaynak kanıtları yalnız testlerde üretilir.

**NOT IMPLEMENTED (UYGULANMADI):** QR içinde gerçek Auth/üyelik/rol ve servis kimliği adaptörleri; gerçek restoran doğrulaması; Order/Payment HTTP taşıması ve uzak kaynak fence/tam kapsam doğrulaması; downstream kapanış bildirimi/Notification arşivlemesi; frontend ve güvenli guest tarayıcı taşıması.

**NOT VERIFIED (DOĞRULANMADI):** Gerçek Auth → Gateway → QR → Order → Payment başarı E2E, gerçek mobil kamera ve production işletim hazırlığı. Testlerdeki restoran/personel/Order/Payment mock'ları gerçek entegrasyon kanıtı değildir.

**Review sonrası mimari not:** V29/V30 admission ve finansal güvenlik projeksiyonunun kapsamı Order/Payment sahipleriyle ortak karar beklemektedir. Alternatifler ve açık sorular [ADR-QR-001](../ADR/ADR-QR-001-masa-kapanisi-ve-siparis-koordinasyonu.md) içinde kayıtlıdır. Bu ADR henüz kabul edilmedi; mevcut iş kodu bu review düzeltmesinde değiştirilmedi. Aşağıdaki iç sözleşmeler nihai ekip uzlaşması olarak okunmamalıdır.

HTTP'de health dışında **denyAll** vardır. Auth servisini başlatmak, `SWAGGER_ENABLED=true` yapmak veya restoran/rol header'ı göndermek erişimi açmaz. Erişim açılması, mevcut güven sınırlarının gerçek kaynaklarla tamamlanmasını ve negatif yetki testlerini gerektirir.

Normatif kaynaklar: [güvenlik](../SECURITY.md), [gereksinimler](../requirements.md), [mimari](../architecture.md), [veri modeli](../database.md), [ER](../er-diagram.md), [yerleşim](../folder-structure.md), [migration aralıkları](../decisions/migrations.md), [web kuralları](../web-rules.md). Eski analizlerin tarihli mevcut durum tespitleri güncel QR kodunun yerine geçmez.

## 2. Gerçek HTTP envanteri

Önek **`/api/v1/qr`**; aşağıdaki yollar buna göredir. Gateway'de bu önek için Web MVC route mevcut, path korunur. Route varlığı authentication/authorization veya iş akışının açık olduğu anlamına gelmez. Personel izin matrisi Auth ekibiyle kesinleşecek; “yönetim” burada mevcut kapalı `TableManagementAccess` sınırını ifade eder. Hiçbir iş endpoint'i şu anda public değildir.

| Method | Yol | Tasarlanan kapsam / istek | Uygulanan başarı sözleşmesi |
|---|---|---|---|
| POST | `/tables` | Yönetim; CreateTableRequest | 201 TableResponse + Location |
| PUT | `/tables/{id}` | Yönetim; UpdateTableRequest | 200 TableResponse |
| PATCH | `/tables/{id}/active` | Yönetim; ChangeTableActiveRequest | 200 TableResponse |
| GET | `/tables/{id}` | Yönetim; tenant kapsamlama | 200 TableResponse |
| GET | `/tables` | Yönetim; filtre/page/sort query | 200 TablePageResponse |
| POST | `/tables/{tableId}/qr-codes` | Yönetim; body yok | 201 CreatedQrCodeResponse |
| POST | `/tables/{tableId}/qr-codes/{qrId}/rotate` | Yönetim; QrCodeVersionRequest | 201 CreatedQrCodeResponse |
| PATCH | `/tables/{tableId}/qr-codes/{qrId}/active` | Yönetim; ChangeQrCodeActiveRequest | 200 QrCodeResponse |
| DELETE | `/tables/{tableId}/qr-codes/{qrId}?version=…` | Yönetim; version query zorunlu | 204, body yok |
| GET | `/tables/{tableId}/qr-codes` | Yönetim; page/size | 200 QrCodePageResponse |
| GET | `/tables/{tableId}/qr-codes/{qrId}` | Yönetim; metadata | 200 QrCodeResponse |
| GET | `/tables/{tableId}/qr-codes/{qrId}/image` | Yönetim; görsel | 200 image/png, inline |
| GET | `/tables/{tableId}/qr-codes/{qrId}/download` | Yönetim; aynı görsel | 200 image/png, attachment |
| POST | `/validate` | İncelenecek müşteri scan erişimi; ValidateQrCodeRequest | 200 ValidatedQrCodeResponse; oturum açmaz |
| POST | `/table-sessions/open` | İncelenecek müşteri scan erişimi; ValidateQrCodeRequest | Her kabulde 201 OpenedGuestSessionResponse |
| POST | `/guest-sessions/activity` | Aynı guest/masa bağlamı; GuestActivityRequest | 200 GuestActivityResponse |
| GET | `/tables/{id}/session-statistics` | Yönetim | 200 TableSessionStatisticsResponse |
| POST | `/table-sessions/{sessionId}/close` | Aynı restoran doğrulanmış WAITER/ADMIN; SessionLifecycleRequest | 200 SessionLifecycleResponse |
| POST | `/table-sessions/{sessionId}/cleaned` | Aynı restoran doğrulanmış WAITER/ADMIN; SessionLifecycleRequest | 200 SessionLifecycleResponse |

**19 iş method/path'i vardır.** `/actuator/health` önek dışında public 200'dür, ayrıntıları açmaz. Swagger/OpenAPI dev ayarı olsa bile HTTP yetkisi otomatik açılmaz; anonim `/v3/api-docs` isteğinin 401 dönmesi güvenlik testinde doğrulanır. Yukarıdaki 200/201/204 uygulanan controller sözleşmesidir; gerçek kimlikli HTTP başarı E2E'si değildir.

Bütün iş JSON yanıtları `Cache-Control: no-store`. PNG 512×512; `Content-Disposition` adı `qr-{qrId}.png`, nosniff, noindex/nofollow/noarchive ve no-referrer header'ları mevcut. Metadata liste/detayı digest, şifreli payload veya ham token döndürmez. INACTIVE QR/masa görseli basılabilir; bu, tarama yetkisi sağlamaz. REVOKED görseli 409 olur.

### Ağ adresleri ve istemci kurulumu

`.env.example` içindeki örnek topoloji aşağıdadır; gerçek ortam portları ENV ile değişebilir:

| İstemci | Base adres | Kullanım |
| --- | --- | --- |
| Tarayıcı / host uygulaması | `http://localhost:5050/api/v1/qr` | Gateway üzerinden dış istek; üretimde onaylanmış HTTPS edge adresi kullanılır |
| Aynı Compose ağındaki servis | `QR_SERVICE_URL=http://qr-service:8080` | Base URI'ye `/api/v1/qr/...` yolu eklenir; servis kimliği ayrıca gerekir |
| Yerel tanılama | `http://127.0.0.1:5056` | QR host portu; doğrudan erişim QR yetki kontrolünü atlamaz |

Docker içindeki `localhost`, QR container'ını değil çağıran container'ı gösterir. Servis URL'sinin ENV'de bulunması gerçek bir HTTP istemcisi veya servis güveni kurulduğu anlamına gelmez. İlgili servisin kendi infrastructure/client katmanında Spring `RestClient` ve sürümlü DTO'lar kullanılmalı; başka servisin JPA/domain sınıfları paylaşılmamalıdır.

Mevcut `REST_CLIENT_CONNECT_TIMEOUT_MS` / `REST_CLIENT_READ_TIMEOUT_MS` sözleşmesinden yararlanın; örnekte 3000/5000 ms vardır. Bunların bütün QR/karşı-servis çağrılarına otomatik uygulandığını varsaymayın; mevcut istemci yapılandırması ve testleri kontrol edilmelidir. HTTP timeout'ı işlem başarısızlığına dair kesin kanıt değildir. Özellikle create/rotate/open/admission/outcome/close komutlarına genel otomatik POST retry eklemeyin; işlem türünün uzlaştırma ve tekrar güvenliği kuralını uygulayın.

`/order-admissions`, `/order-outcomes` veya `/internal/...` gibi ağ endpoint'leri bu teslimde yoktur. Aşağıdaki Java sözleşmeleri için transport eklemek, sürümlü request/response, servis kimliği, tenant doğrulaması ve ayrı kabul testleriyle yapılacak entegrasyon işidir.

## 3. Request ve response şekilleri

### Validation

| DTO / query | Alanlar ve gerçek sınırlar |
|---|---|
| CreateTableRequest | `tableNumber`: zorunlu, blank olamaz, en fazla 40; `displayName`: opsiyonel/en fazla 120; `capacity`: opsiyonel/pozitif integer |
| UpdateTableRequest | Aynı alanlar + zorunlu nonnegative `version`; **PUT tam değiştirmedir**, eksik displayName/capacity temizlenir |
| ChangeTableActiveRequest / ChangeQrCodeActiveRequest | Zorunlu boolean `isActive`, zorunlu nonnegative `version` |
| QrCodeVersionRequest | Zorunlu nonnegative `version`; rotate body'de, delete query'de |
| ValidateQrCodeRequest | Yalnız `qrToken`: 43 URL-safe karakter `[A-Za-z0-9_-]` |
| GuestActivityRequest | Aynı biçimde `guestToken` + zorunlu UUID restaurantId/tableSessionId/guestSessionId; bu ID'ler doğrulanacak beklentidir, yetki değildir |
| SessionLifecycleRequest | `version`: zorunlu/nonnegative; close için main version, cleaned için tableVersion |
| GET tables | isActive yalnız `true/false`; operationalStatus enum; page 0..10000, size 1..100; varsayılan 0/20; sort `tableNumber/createdAt/updatedAt`, direction `asc/desc` |
| GET QR list | page 0..10000, size 1..100, varsayılan 0/20; sıralama issuedAt DESC, id DESC |

İş alanında numara trim edilir; restoran+numara unique'dir. Case-insensitive numara politikası eklenmedi. Unknown JSON alanları uygulama ayarıyla reddedilir; DTO'ya `role`, `paymentSucceeded`, beklenmeyen restaurantId veya operationalStatus eklemek yetki kazandırmaz. UUID/enum/body parse ve Jakarta validation hataları kontrollü 400'dür.

### Örnekler (temsili değerler, gerçek sır/veri değildir)

Masa oluştur:

```json
{"tableNumber":"12","displayName":"Bahçe 12","capacity":4}
```

TableResponse:

```json
{"id":"00000000-0000-0000-0000-000000000012","restaurantId":"00000000-0000-0000-0000-000000000001","tableNumber":"12","displayName":"Bahçe 12","capacity":4,"operationalStatus":"AVAILABLE","isActive":true,"version":0,"createdAt":"2026-10-20T00:00:00Z","updatedAt":"2026-10-20T00:00:00Z"}
```

PUT: `{"tableNumber":"12","version":0}` displayName/capacity'yi NULL yapar. PATCH active: `{"isActive":false,"version":0}`. Rotate ve lifecycle: `{"version":0}`; delete: `?version=0`.

Scan body: `{"qrToken":"<gerçek-43-karakter-token>"}`. Activity body:

```json
{"guestToken":"<gerçek-43-karakter-token>","restaurantId":"00000000-0000-0000-0000-000000000001","tableSessionId":"00000000-0000-0000-0000-000000000002","guestSessionId":"00000000-0000-0000-0000-000000000003"}
```

`<…>` yer tutucuları literal gönderilmez; biçim doğrulamasından geçmez. Ham QR/guest tokenını loga, analytics'e veya kalıcı JS store'a koyma. Müşteri sayfasının guest taşıma/yeniden yükleme sözleşmesi henüz onaylanıp uygulanmadı.

OpenedGuestSessionResponse şekli:

```json
{"tableSession":{"id":"00000000-0000-0000-0000-000000000002","restaurantId":"00000000-0000-0000-0000-000000000001","tableId":"00000000-0000-0000-0000-000000000012","status":"OPEN","version":0,"openedAt":"2026-10-20T00:00:00Z","lastActivityAt":"2026-10-20T00:00:00Z","idleExpiresAt":"2026-10-20T00:15:00Z","orderProtected":false},"guestSession":{"id":"00000000-0000-0000-0000-000000000003","restaurantId":"00000000-0000-0000-0000-000000000001","tableSessionId":"00000000-0000-0000-0000-000000000002","status":"ACTIVE","version":0,"joinedAt":"2026-10-20T00:00:00Z","lastActivityAt":"2026-10-20T00:00:00Z","expiresAt":"2026-10-20T00:15:00Z","orderProtected":false},"guestToken":"<yalnız-ilk-yanıtta-dönen-token>"}
```

Kapanış yanıtı; ardından cleaned body'de **7** kullanılır:

```json
{"sessionId":"00000000-0000-0000-0000-000000000002","tableId":"00000000-0000-0000-0000-000000000012","status":"CLOSED","version":4,"closingAt":"2026-10-20T00:20:00Z","closedAt":"2026-10-20T00:20:00Z","closedByUserId":"00000000-0000-0000-0000-000000000004","tableStatus":"CLEANING","tableVersion":7}
```

Diğer response alanları (Java record isimleri gerçek DTO'lardır):

| DTO | Alanlar |
|---|---|
| TablePageResponse | **content**, page, size, totalElements, totalPages |
| QrCodePageResponse | **items**, page, size, totalElements, totalPages (masa listesinden farklı anahtar) |
| CreatedQrCodeResponse | id, restaurantId, tableId, status, version, issuedAt, expiresAt, **qrToken** |
| QrCodeResponse | id, restaurantId, tableId, status, version, issuedAt, revokedAt, expiresAt |
| ValidatedQrCodeResponse | qrId, restaurantId, tableId, tableNumber, displayName, checkedAt; doğrulama snapshot'ı, yetki değil |
| GuestActivityResponse | restaurantId, tableSessionId, guestSessionId, mainVersion, guestVersion, lastActivityAt, mainIdleExpiresAt, guestExpiresAt, mainOrderProtected, guestOrderProtected |
| TableSessionStatisticsResponse | restaurantId, tableId, liveSessionId, liveSessionStatus, activeGuestCount, sessionAcceptedScanCount, tableAcceptedScanCount, checkedAt |

Ham guest tokenı yalnız yeni guest yanıtında döner; DB'de SHA-256 digest bulunur. QR ise digest + AES-GCM şifreli içerikle korunur; aynı QR ileride görsel olarak tekrar alınabilir. QR metadata token kurtarma API'si değildir. Kaybolan QR create/rotate yanıtından sonra önce metadata okunur; kör yeni rotate yapılmaz.

### Hata sözleşmesi

ProblemDetail `status/detail/title` temel sözleşmesi; iç SQL/token/stack trace açılmaz. 400 validation/parse; 401 doğrulanmış kimlik yok; 403 yetki/CSRF; 404 yok/yabancı tenant/uygunsuz QR veya guest; 409 duplicate/stale version/uygunsuz lifecycle/FK veya kapanış koşulu; 503 yapılandırılmamış QR görsel adresi veya eksik gerçek kapanış kanıtı. Genel beklenmeyen hata güvenli 500'dür. Şu an canlı business yazmaları CSRF'siz 403, protected GET'ler anonim 401; varsayılan ADMIN/WAITER rolü de izin sağlamaz. 429 bu serviste uygulanmış kabul edilmez; erişim açılmadan rate limit tasarlanmalıdır.

## 4. İlişkiler ve bağımsız durumlar

QR DB sahibi zincir: restoran REF → dining_tables → qr_codes/table_sessions → guest_sessions/order_admissions/qr_scan_events. CONFIRMED admission → order_business_states → order_business_events. Tenant/main/guest zincirinde yerel FK'ler vardır; Auth/Admin/Order/Payment DB'sine FK/JOIN yoktur. Kimlikleri servislerin JPA entity'siyle paylaşma.

| Eksen | Uygulanan davranış |
|---|---|
| Masa | AVAILABLE→OCCUPIED ilk main ile; personel kapanışı OCCUPIED→CLEANING; ayrı temizlendi CLEANING→AVAILABLE. Siparişsiz güvenli idle doğrudan AVAILABLE. AVAILABLE/DISABLED aktiflikten ayrı; OCCUPIED/CLEANING pasifleştirilemez. |
| QR | ACTIVE/INACTIVE/REVOKED; masa başına tek ACTIVE. Rotate eski ACTIVE'i revoke edip yeni credential çıkarır. REVOKED yeniden açılamaz. Referanslı hard delete FK ile 409; geçmiş cascade silinmez. |
| Main | OPEN/CLOSING/CLOSED/EXPIRED; aynı masa için en fazla tek OPEN/CLOSING. Normal personel kapanışında OPEN→CLOSING→CLOSED kısa tek yerel transaction'dır. |
| Guest | Her kabul edilen scan, ilk dahil, ayrı ACTIVE guest üretir; kapanış ENDED, idle EXPIRED. UUID veya QR bilmek guest/personel yetkisi değildir. |
| Admission | PENDING→CONFIRMED veya REJECTED; timeout ile silinmez. Karar tekrarları yeni sipariş izni değildir. |
| Business | ORDER_PLACED→PAYMENT_PENDING→COMPLETED veya güvenilir tam iptal CANCELLED; doğrudan ORDER_PLACED→COMPLETED yalnız gerçek tamamlanma+SETTLED kanıtıyla. Terminal iş durumu aktifleşmez; aynı terminalde yeni mali bilgi işlenebilir. |
| Financial | UNVERIFIED / SETTLED / CANCELLATION_CLEAR / RECONCILIATION_REQUIRED; Payment tahsilat/bakiye modelinin yerine geçmez. |

**COMPLETED ana masayı kapatmaz.** Owner ve main kapanışa kadar korunur. Ön ödeme bir hazırlanan siparişi tamamlanmış saydırmaz. Kısmi iptal tam CANCELLED değildir. Ödenmiş/belirsiz iptal refund değildir; uzlaştırma engeli korunur. Geç mali olay eski guest'i veya sonraki main'i canlandırmaz.

Her guest sipariş yokken kendi anlamlı aktivitesinden 15 dakika; main herhangi bir geçerli scan/activity ile yenilenir. Bir guest'in hareketi diğer telefonun süresini yenilemez. Okuma/polling/arka plan sekme süre yenilememeli. Frontend henüz bu davranışı uygulamadı; activity beyanı insan/fiziksel varlık kanıtı değildir. Tam deadline'da kullanım reddedilir; UTC/PG mikrosaniye hassasiyeti korunur.

CONFIRMED kabul main ve yalnız owner guest'i korur. PENDING kullanım ömrünü süresiz uzatmaz fakat bilinmeyen sipariş yüzünden fiziksel boşaltmayı engeller. Güvenilir tam iptal kapsamıyla owner'ın son siparişi iptal edilince iptal kaynak zamanından 15 dakika; bütün siparişler güvenli iptalse kullanılabilir guest'ler+main için aynı kural. `max(gerçek lastActivityAt, cancelledAt)+15 dakika`; geç mesaj fazladan süre kazandırmaz, expired guest canlanmaz.

Idle job mevcut: başlangıç/fixed delay 60 saniye, batch 100 ve keyset tarama. Varsayılan Order bilgisi UNKNOWN'dur. Dolayısıyla cron var diye gerçek siparişsizlik doğrulanmış olmaz; fiziksel expiry güvenilir NO_ORDERS/uygun yerel sertifika olmadan çalışmaz. Kullanım deadline'ı ile fiziksel satır temizliği farklıdır. Sayım scan/guest sayısıdır; benzersiz insan sayısı değildir. V28 öncesi kayıt dışı taramalar geriye dönük uydurulmaz.

## 5. Bağlanacak kapalı güven sınırları

| QR sınıfı | Şimdiki gerçek davranış | Entegrasyonda gereken |
|---|---|---|
| TableManagementAccess.requireRestaurantId | Authentication exception | Aktif kimlik, yönetim izni ve restoran üyeliği; client header değil |
| SessionLifecycleAccess.requireStaff | Authentication exception | Güncel aynı-restoran WAITER/ADMIN + userId; yerel doğrulanmış bağlam, lock altında uzak I/O yok |
| OrderAdmissionAccess.requireRestaurantId | Authentication exception | Gerçek Order servis kimliği, tenant ve exact komut/fence/kalıcı karar |
| OrderOutcomeAccess.verify | Authentication exception | Yetkili Order+Payment, exact Command/tenant/order/evidence ve güvenilir tam Scope |
| IdleExpiryOrderAccess.knowledge | UNKNOWN | Yetkili, güncel sipariş varlığı; erişim hatası/timeout/not-found UNKNOWN kalır |
| SessionClosureEvidenceAccess.verify | 503 unavailable | Staff onayına bağlı tam Order/Payment kapsamı ve mali güvenlik; kaynak fence/geçerlilik |

Bu sınıflara sabit tenant, test mock'u veya `permitAll` koymak entegrasyon değildir. Auth/Gateway'de doğrulama yapılması QR'ın kendi yetki kontrolünü kaldırmaz. SecurityFilterChain, CSRF/CORS/rate limit, token/cookie taşıma, revoke ve üyelik değişikliği davranışı birlikte incelenmeli. Guest credential'ı personel veya tüm masanın sipariş erişimi vermez. Tarayıcı tokenı localStorage/sessionStorage/IndexedDB'de saklanmaz; güvenli taşıma modeli ayrıca onaylanmalı.

## 6. Order/Payment servis içi v1 sözleşmeleri

**Aşağıdakiler Java servis sınırlarıdır; HTTP endpoint veya çalışan karşı-servis API'si DEĞİLDİR.** Broker/outbox dispatcher, güvenilir network transport ve retry worker yoktur. Yeni taşıma/servis güven modeli ayrı onayla tasarlanmalı.

### Admission — OrderAdmissionService

`reserve(Reservation)` alanları: restaurantId, tableSessionId, guestSessionId, sabit orderId, idempotencyKey, requestDigest, guestToken. Key `[A-Za-z0-9._:-]{1,120}`; digest SHA-256 biçiminde 64 küçük hex. Response: id, restaurantId, tableSessionId, guestSessionId, orderId, status, fencingVersion, version, requestedAt, expiresAt, confirmedAt, rejectedAt.

`confirm(Decision)` / `reject(Decision)`: restaurantId, admissionId, orderId, requestDigest, fencingVersion, expectedVersion, occurredAt. Admission kendi değişmez komutunun fence=1 değerini döndürür; ID tekrar kullanılmaz. Order bunu kendi kalıcı iş kaydı/tombstone'u ile atomik doğrulamalı. Son tarih en çok 60 saniyedir; korumasız guest/main deadline'ı daha erkense kısalır. Confirm kaynağın deadline'dan önce kalıcı kabul ettiğini kanıtlamalı; geç teslim ile geç iş kararı karıştırılmaz. Ret geçici network hatası değil, bu exact komut için kalıcı Order tombstone'udur.

Akış: sabit orderId/key/digest → QR reserve → Order kendi transaction'ında deadline/fence/kimlik/ürün-fiyat kontrolü + kalıcı karar → exact confirm/reject. Order commit'i başarırken confirm kaybolursa yeni sipariş üretme. Aynı rezervasyonu uzlaştır; kör timeout ret/silme yok. Reserve replay mevcut sonucu döndürebilir; yeni Order commit yetkisi veya güncel guest kullanılabilirlik garantisi değildir. Yeni confirm ORDER_PLACED projection oluşturur; eski confirm retry ilerlemiş business durumunu sıfırlamaz.

### Business sonuç — OrderBusinessOutcomeService

`apply(Command)` / `inspect(Command)` alanları: restaurantId, admissionId, eventId, **admission başına** sequence (1'den başlar), QR projection expectedVersion, status, occurredAt, evidenceId. Kaynak Order/Payment global DB version'ı bu sequence değildir.

`OrderOutcome.Evidence`: verifiedRestaurantId, orderId, exact command, finance, scope. Scope: revision, confirmedSequences haritası (admissionId→güncel kaynak sequence), pendingIds, complete. Sunucu üyelik/sıraları aynı parent kilidinde eşleştirir; kaynak adapter'ı kapsamın gerçek uzak evreni içerdiğini ayrıca doğrulamalı. Yerel boşluk/boş liste complete kanıtı değildir. Eksik legacy projection okumalarda uydurulmaz; güvenilir ilk sıralı sonuçla kurulabilir.

Receipt `(restaurant,eventId)` ve `(restaurant,admission,sequence)` tekildir; `QR_OUTCOME_V1` SHA-256 fingerprint exact command/finance/kanıt/kapsamı bağlar. Aynı olay aynı immutable ACK'i döndürür, timer/version yenilemez; değiştirilmiş duplicate/gap/stale version 409. `Result`: eventId, admissionId, sequence, status, reconciliationRequired, version.

`inspect` current admissionId/status/finance/sequence/version/reconciliationRequired + varsa exact recordedEvent receipt döndürür; aktivite veya legacy projection üretmez. Kayıp ACK önce inspect ile uzlaştırılır. Başka bir siparişin güvenli iptali sibling projection version'ını ilerletebilir; eski ACK version'ı güncel varsayılmaz. Receipt varsa mutation tekrarı gerekmez; receipt yok/stale version varsa kaynak exact komutu güncel veriyle yeniden doğrulamalı. Genel otomatik POST retry eklenmez.

### Kapanış kanıtı — SessionClosure

Staff(restaurantId,userId) doğrulanmış yetkidir. Snapshot Parent(restaurantId,sessionId,tableId,sessionVersion,tableVersion,scopeRevision) ve admissions map'idir. Map değeri orderId/status/version + varsa Projection(status,finance,sequence,version) içerir. Evidence: exact staff/snapshot, verifiedAt, validUntil, complete, financiallyClear. Şart `verifiedAt <= checkedAt < validUntil`; testteki 30 saniye production politikası değildir.

Kapanış orkestratörü caller transaction'ını `NEVER` ile reddeder: prepare kısa transaction → kaynak kontrolü transaction dışında → finish yeni kısa transaction. Güncel staff ve snapshot tekrar eşleşir. `spring.jpa.open-in-view=false`, `ddl-auto=validate` ve parent kilit sırası korunmalı. Uzak Payment commit'i QR transaction'ıyla atomik değildir; adapter kendi fence/yeniden doğrulama sözleşmesini uygulamalı. Staff onayı olmadan ödeme otomatik kapanış oluşturmaz.

PENDING varsa 409 ve değişiklik yok; kalıcı CLOSING'e alıp bilinmeyen işi silen bir saga yoktur. Uygun yerel kapanış OPEN→CLOSING→CLOSED, aktif guest ENDED ve masa CLEANING'i birlikte commit eder. Önceden CLOSING varsa güvenli koşullarla tamamlanabilir. Cleaned ayrı işlemdir; sonraki/live main bulunursa eski temizlendi isteği reddedilir. Tarihler aynı mikrosaniyede çakışırsa sıra tahmin edilmez; konservatif 409 ve operasyonel uzlaştırma gerekebilir.

Close/cleaned tekrar güvenliği hedef duruma dayanır; immutable command receipt veya downstream event delivery garantisi değildir. Yanıt güncel görünüm olduğundan eski CLOSED main tekrarında masa artık yeni müşteride OCCUPIED görünebilir; yeni main'e dokunulmaz. Gerçek mutasyonda eski version yeniden okunmalı.

## 7. Diğer ekiplerin yapacağı işler

| Ekip | QR'da mevcut | Karşı tarafta / ortak çalışmada PENDING |
|---|---|---|
| Auth | Kapalı yönetim/lifecycle/servis kimlik sınırları, guest guard | Kimlik/tenant/rol/üyelik, güvenilir servis kimliği, guest taşıma/revoke, CSRF/CORS/rate limit ve negatif E2E; yeni güven modeli onayı |
| Admin | Masa/QR HTTP iş servisleri | Gerçek restoran varlığı ve ayar doğrulaması; yönetim UI/API bağlantısı; QR DB'ye doğrudan yazma yok |
| Order | reserve/confirm/reject ve outcome apply/inspect | Sabit ID/key/digest, kalıcı kabul/ret+fence/deadline, güvenilir kaynak sonuçları, transport/uzlaştırma; fiyat/Menu ve ödeme kuralları Order sahibinde |
| Payment | FinancialOutcome ve kapalı kapanış kanıt sınırı | Yetkili gerçek tahsilat/belirsiz girişim/iptal-iade kanıtı, tam kapsam ve fence; client başarı sayfası kanıt değil |
| Waiter | Staff close/cleaned HTTP sözleşmesi | Doğrulanmış WAITER restaurant context; version/read-conflict UI; temizliği ayrı komut; hazır sipariş teslimi QR değil Order işlemi |
| Menu | QR validate/open context | Menü sayfası ve kendi sipariş yetkisi ayrımı; QR iş durumunun ikinci yazarı olmama; doğrudan bir QR→Menu fiyat çağrısı mevcut değil |
| Notification | Kapanışın QR yerel durumu | Kapanış sonrası aktif bildirimleri arşivleyen güvenilir event/API tüketimi; dayanıklı olay teslimi henüz yok, geçmiş silinmez |
| Gateway | `/api/v1/qr/**` route | Kimlik/route erişim matrisi, header temizliği, CSRF/CORS/limit politikası; QR içinde tekrar tenant/nesne kontrolü |
| Frontend | API DTO/versiyon/no-store/görsel sözleşmeleri | QR fragment sayfası, güvenli guest taşıma, foreground activity, yenileme/kopma/409 UX, kendi sipariş sınırı ve kamera E2E |

Bu rehber karşı servislerde var olmayan URL veya endpoint adı tanımlamaz. Gerekli yeni iç taşıma sözleşmeleri versiyonlu DTO ile ayrıca kararlaştırılmalı; QR JPA entity'leri başka servise import edilmemeli. Kitchen/Rating/Timer entegrasyonu bu teslimin çalışan özelliği değildir. Wi-Fi süresi QR'ın 15 dakikasından ayrıdır; puanlama için kapanmış guest'e yeniden genel erişim verilmez.

## 8. Bağlama sırası ve kalan kararlar

1. Auth + Gateway + QR ekipleri yönetim/servis/guest güven sözleşmesini ve rol matrisini onaylar; Auth adapter'ları ve negatif tenant/rol testleri kurulur.
2. Admin restoran doğrulaması ve müşteri public entry URL/guest browser taşıması netleştirilir; yönetim ve scan erişimleri ayrı izinlerle açılır.
3. Order admission kalıcı commit/tombstone/fence/deadline sözleşmesi ve güvenilir transport bağlanır; kayıp teyit/timeout uzlaştırması doğrulanır.
4. Order/Payment sıralı outcome ve tam kapsam kanıtı bağlanır; NO_ORDERS ve mali belirsizlik güvenle ayırt edilir. Yerel test mock'ları production adapter yapılmaz.
5. Waiter/Admin onaylı close/cleaned ve Payment kaynak kanıtı birlikte test edilir; ödeme onayı erken kapanış tetiklemez.
6. Notification/downstream kapanış teslimi ve retry/operasyon uzlaştırması eklenir; masa kapanması bildirim gönderildi anlamına gelmez.
7. Gerçek Auth/Gateway/Order/Payment ve mobil müşteri akışıyla başarı/negatif/yarış E2E yapılır; sonra onaylı API açılışı ve production hazırlığı değerlendirilir.

Açık ürün/teknik konular: D-01/D-02 gerçek ödeme/iade ve kısmi/READY iptali; D-03 kapanmış guest sonrası değerlendirme yetkisi; D-09 Auth/servis/guest browser taşıması; D-10 olay teslimi; D-11 retention/backup/geri yükleme; D-14 QR rotate/revoke sonrası mevcut guest politikası; D-15 başka guest siparişleri/ortak masa hesabı. Mevcut rotate guest'leri otomatik bitirmez; bunun nihai ürün politikası olduğu iddia edilmez. D-04 için bu geliştirmede onaylanan bağımsız guest/ortak main/iptal kaynak zamanlı 15 dakika kararları geçerlidir. Nihai public URL ve fiziksel varlık/kopyalanmış QR tehdidi ayrıca ele alınmalıdır.

## 9. ENV, migration ve işletim

- `QR_PAYLOAD_ENCRYPTION_KEY`: Base64 32 byte AES anahtarı; gerçek değer Git/log/sohbet dışında. Yeniden başlatmada değiştirilmez. Kaybolursa eski şifreli QR içeriği çözülemez. Rotation/keyring/migration akışı bu teslimde yoktur.
- İlk kurulum yardımcısı `ops/qr/qr-key.mjs`; komutlar, profil/aktif `.env` eşitlemesi ve hata kodları [QR anahtar üretimi](qr-key-uretimi.md) içinde açıklanır. Compose anahtarın boş aktarımına izin verir; QR uygulaması boş/geçersiz anahtarla başlamaz. Diğer servislerin zorunlu ayarları ayrıca gereklidir.
- `QR_PUBLIC_ENTRY_URL`: Boşsa image/download 503. Dolu adres sınırlı HTTPS URL; userinfo/query/fragment yok; loopback HTTP yalnız dev/test ve prod yokken. Görsel payload **`entryUrl#token=<qrToken>`**. Fragment sunucu HTTP isteğine gitmez; müşteri sayfasının bunu güvenli okuması henüz uygulanmadı. Gerçek üretim domain'i belirlenmeli; mevcut dev ayarı production kararı değildir.
- `.env.example` alanları gerçek sır içermez. Yeni ENV değişikliği, Compose aktarımı, ağ veya dependency ayrı onay gerektirir; bu rehber ENV'yi değiştirmedi.
- V21 dining_tables; V22 qr_codes; V23 rotate; V24 INACTIVE; V25 optional expiresAt; V26 main; V27 guest; V28 accepted scan; V29 admission; V30 business projection/event. Testcontainers testleri bu şemayı PostgreSQL 17 üzerinde kurar ve validate eder; gerçek DEV veritabanının uygulanmış sürümü ayrıca kendi Flyway history'sinden kontrol edilmelidir. **Aralık dolu; V31 Payment'a ait.** Uygulanmış SQL değişmez; yeni şema için ekipten yeni aralık kararı gerekir. Hibernate update/create, Flyway clean/repair veya volume reset çözüm olarak kullanılmaz.
- QR expiry opsiyonel schema/domain desteğine rağmen mevcut HTTP'de expiresAt değiştirme endpoint'i yoktur. Guest credential kaybında mevcut guest'i geri veren API yoktur; yeniden scan ayrı guest yaratır. Genel liste/receipt/event taşıma endpoint'i hayal edilmemeli.
- Ortak DEV PostgreSQL kimliği production least-privilege izolasyonu sayılmaz. Production yedek/geri yükleme, servis DB rolleri, kaynak/bağlantı limitleri, TLS ve erişim sınırları ayrıca hazırlanmalı.

## 10. Auth entegrasyonuna başlarken

Auth ekibinin [PR #2](https://github.com/software-lecture-labs/Restofy/pull/2) teslimi referanstır; `dev` birleştirildikten sonra **gerçek güncel Auth kodu ve sözleşmesi yeniden okunmalıdır**. Auth'ın kendi testlerinin geçmesi QR yetkilendirmesinin çalıştığını kanıtlamaz.

Plan, mevcut QR PR'ının dev merge'inden sonra Auth'ı küçük PR'larla bağlamaktır. Endpoint başına zorunlu tek PR yerine ortak yetki/iş akışı bazında gruplama reviewer'a sorulacaktır; ilk öneri masa listeleme/detay ve `TableManagementAccess` doğrulamasıdır. Guest güvenliği ve hız sınırı tamamlanana kadar müşteri tarama endpoint'leri kapalı kalır. Bu, onaylanmış yeni bir erişim matrisi veya tamamlanmış Auth entegrasyonu değildir.

1. Auth'ın personel oturumu ve JWT/cookie sözleşmesini QR/Gateway sınırlarıyla karşılaştırın. İmza algoritması, issuer, audience, süre, token türü ve auth/MFA durumu kaynakta tanımlandığı biçimde doğrulanmalı. Auth'ın private signing key'i QR'a kopyalanmamalı; QR payload AES anahtarı personel JWT anahtarı değildir. Anahtar dağıtımı/yenilemesi ve servis kimliği için yeni güven tasarımı ayrıca onaylanmalıdır.
2. `TableManagementAccess` ve `SessionLifecycleAccess` için güncel aktif restoran üyeliği, işlem izni ve kullanıcı kimliği üreten gerçek adaptörleri geliştirin. Yönetim operasyonlarının izin matrisi kesinleşmeden her personele erişim vermeyin. Kapanış/temizlik aynı restoranın doğrulanmış WAITER/ADMIN yetkisini gerektirir. Sahte `X-Role`/`X-Restaurant-Id` ve varsayılan Spring kullanıcısı yetki kaynağı değildir.
3. Personel oturumu ile anonim guest yetkisini ayrı tutun. Guest tokenı yalnız eşleşen restoran/main/guest kapsamında geçerlidir; başka guest'in siparişlerini veya personel API'lerini açmaz. Tokenın tarayıcıda taşınması, reload ve kayıp token davranışı ayrıca kararlaştırılmalı; localStorage/sessionStorage/IndexedDB kullanılmamalı.
4. Cookie kullanımı için CSRF, izinli Origin/CORS, logout/revoke ve üyelik değişikliğinin etkisini birlikte uygulayıp test edin. Curl/Postman veya servis çağrısı başarısız diye CSRF kapatılmamalı. Personel cookie'si ile servis kimliği aynı şey değildir.
5. QR tarama/activity ve görsel üretimi için rate limit/kaynak sınırlarını belirleyin. Birden fazla instance'ta tutarlılık, limit kapasitesi dolduğunda masum kullanıcıların engellenmemesi ve güvenilir proxy üzerinden istemci IP'si çözümü doğrulanmalı. İstemcinin gönderdiği `X-Forwarded-For` doğrudan güvenilir kabul edilmemeli.
6. Önce testte ve onaylı entegrasyon ortamında gerçek Auth ile tenant/rol/nesne sahipliği ve CSRF negatif senaryolarını doğrulayın. Ancak bundan sonra endpoint bazlı erişim kurallarını açın; bütün iş API'lerini topluca `permitAll` yapmak entegrasyon değildir.

Mevcut kapalı adaptörleri kaldırıp sabit olumlu sonuç döndürmek güvenlik kontrolünü tamamlamaz. Yerine gerçek adaptör ve sözleşme testleri gelmelidir. Unit/entegrasyon testlerindeki kontrollü mock'lar korunur; gerçek E2E bunlara eklenir. Bu rehber Auth veya servis kimliği için yeni bir üretim sözleşmesini onaylamaz.

## 11. Bakım kararları ve gözlemlenebilirlik

### Beklenmeyen hata kaydı

`ApiExceptionHandler` beklenmeyen hata için `QR_UNEXPECTED_ERROR` olay kodunu ve exception sınıfını ERROR düzeyinde kaydeder. Exception mesajı, cause, stack trace, request URL/body/header veya token loga aktarılmaz; kullanıcıya güvenli 500 ProblemDetail döner. Bu minimal kayıt dağıtık tracing veya kapsamlı audit altyapısının yerini tutmaz. Entegrasyonda correlation/trace tasarımı ayrıca değerlendirilmelidir.

### İstatistik okumasının transaction kararı

`TableSessionStatisticsService.get` salt okunur PostgreSQL `REPEATABLE_READ` transaction'ı kullanır. Ana/guest/sipariş koruması ve tarama sayaçları tek MVCC snapshot'ından okunur; sırf istatistik için `PESSIMISTIC_WRITE` alınmaz. Devam eden tarama, activity veya kapanış yazma transaction'ının bitmesini beklemek gerekmez.

İlk okuma sırasında commit edilmemiş değişiklikler görülmez; sorgular arasında yeni commit gelse bile aynı yanıt eski snapshot'tan tutarlı kalır. Sonraki istek yeni commit'i görür. Bu yanıt güncel bir mutasyon izni değildir; Order/kapanış/guest yazma akışlarının kilitleri ve güncel doğrulamaları korunur.

`REQUIRES_NEW`, çağıranın varsa transaction'ını askıya alır; daha zayıf izolasyona sessizce katılmayı önler. Bunun maliyeti iç içe çağrıda ayrı bağlantı gerektirmesidir. Normal HTTP okuması bağımsız transaction'dır; yazma transaction'ı içinden istatistik çağırmayın. Yük/bağlantı havuzu kapasitesi production öncesi ölçülmelidir; bu teslim sayısal performans garantisi vermez.

### Sabitler ve otomatik backend kontrolü

Admission üst süresi `OrderAdmission.MAX_RESERVATION_WINDOW`, idle tarama aralığı `SWEEP_DELAY_MILLIS`, URL üst sınırı `MAX_ENTRY_URL_LENGTH` ile adlandırıldı. Masa ve QR listeleri ortak `PageLimits` doğrulamasını kullanır. Değerler ve API sınırları değişmedi; yeni uygulama ENV'si eklenmedi.

[Backend verify workflow'u](../../.github/workflows/backend-verify.yml), backend/kalite scripti/workflow değişen push'larda, `dev` hedefli PR'larda ve manuel tetiklemede Java 25 ile mevcut `pre-push backend dev` kapısını çalıştırır. Bu kapı `mvn clean verify`, her serviste gerçekten çalışmış ve atlanmamış JUnit testleri ve servis başına en az %80 satır coverage kontrolünü içerir. Node.js ve Docker/Testcontainers gerekir; gerçek uygulama ENV'si veya Sonar tokenı kullanılmaz. Workflow yalnız okuma iznine sahiptir; deploy/commit/push yapmaz. Bu dosyanın varlığı çalışmasının başarılı olduğu anlamına gelmez; ilk GitHub çalışması ayrıca kontrol edilmelidir. Branch protection veya zorunlu status check ayarı bu teslimde değiştirilmez.

## 12. Ortak kabul ve doğrulama

Mevcut kapalı ortamda Swagger üzerinden iş API'lerinden 200 beklenmez. Gerçek kimlikli başarı testleri, adaptörler tamamlandıktan sonra onaylı entegrasyon ortamında çalıştırılır.

| Alan | Birlikte doğrulanacak senaryolar |
| --- | --- |
| Auth/tenant | Tenant A'nın masa/QR işlemleri; tenant B aynı ID'yi okuyamaz/yazamaz; eksik/yanlış rol, üyelik iptali, süresi dolmuş veya yanlış audience token, sahte header, CSRF/Origin reddi |
| Masa | Duplicate numara 409; eksik/eski version; PUT'ta opsiyonel alanların temizlenmesi; yanlış enum/JSON, filtre/sayfalama sınırları |
| QR | Pasif/revoked/expired QR ve pasif masa reddi; rotate sonrası eski token; metadata'da sır bulunmaması; aynı PNG payload'ı ve güvenli header'lar; referanslı hard delete 409 |
| Scan/guest | Paralel ilk taramada tek main ve ayrı guest/event; her kabul 201; yeniden scan yeni guest; yanlış tenant/main/guest token reddi; gerçek kamera ve URL fragment akışı |
| İstatistik | Guest/event yazımı commit olmadan eski tutarlı snapshot; sorgular arasında commit olduğunda karışık sayaç olmaması; okuma yüzünden tarama/kapanışın yazma kilidi beklememesi; sayımların tekil insan diye sunulmaması |
| Idle | Her telefonun ayrı 15 dakikası; main'in geçerli hareketle uzaması; polling'in süreyi yenilememesi; exact deadline; UNKNOWN Order bilgisinde otomatik masa boşaltılmaması |
| Admission | Aynı key/digest tekrarının ikinci kayıt üretmemesi; değişmiş komut reddi; kayıp confirm/ACK; tombstone/deadline/fence ve geç commit; timeout'ın ret kanıtı sayılmaması |
| Outcome | Sıralı/tekrarlı/değiştirilmiş event, sequence gap, stale projection version, tam kapsam eksikliği, tüm/guest bazlı güvenli iptal restart'ı; geç ödeme/iptal belirsizliği; inspect okumasının süre yenilememesi |
| Kapanış | PENDING/aktif/ödenmemiş/legacy/mali belirsiz kapsamda reddetme; gerçek personel onayı + tam mali kanıt; aktif guest/main/table birlikte commit veya rollback; close ile scan/activity/reserve/outcome/expiry yarışı |
| Temizlik/downstream | CLEANING → AVAILABLE ayrı komut; eski main'in yeni müşteriyi boşaltamaması; tekrarların yeni etki üretmemesi; Notification arşivleme ve dayanıklı teslimin ayrıca doğrulanması |

Windows'ta repository kökünden mevcut manuel kontroller:

```powershell
.\pre-push\pre-push.cmd qr-service dev
.\pre-push\pre-push.cmd backend dev
```

Doğrudan Maven için **Java 25**, Maven ve Docker Engine/Desktop gerekir. Surefire test profilini açar; gerçek uygulama veritabanı yerine geçici PostgreSQL 17 kullanılır:

```powershell
mvn -B -f backend/pom.xml -pl services/qr-service -am clean verify
mvn -B -f backend/pom.xml clean verify
```

Frontend geliştirmesi bu teslimde yoktur. Frontend kapsamı açıldığında mevcut `pre-push full dev` ayrıca uygulanır. SonarQube bağımsız ve isteğe bağlıdır; pre-push'a gömülü değildir. Kod değişince önce güncel test/JaCoCo üretilmeli; eski raporlarla tarama yapılmamalıdır. Hiç çalıştırılmamış kontrol PASS veya `SAFE TO COMMIT` olarak sunulmaz.

## 13. Entegrasyonun tamamlanma ölçütü

Dört QR backend fazı tamamlanmış olsa da servisler arası entegrasyon, yukarıdaki PENDING adaptörler ve gerçek E2E olmadan tamamlanmış sayılmaz. `dev` PR'ı bu ayrımı açıkça belirtmeli: QR-01–QR-04 iş mantığı teslim ediliyor; Auth Service ile güvenli entegrasyon ortak dalda sonraki çalışma olacak. Order/Payment/Notification teslimi, API açılışı ve production hazırlığı ayrıca takip edilir.

Bu dosya ekip için Git'e alınacak QR rehberidir. Yerel task raporları, geliştirme ajan talimatı, gerçek `.env*`, tokenlar ve loglar Git dışında kalır. Bu rehberi paylaşmak için diğer yerel Markdown dosyalarını stage etmeye gerek yoktur.
