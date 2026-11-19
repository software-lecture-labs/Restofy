# Order Service — sözleşmeler ve kararlar

> **Durum:** Bu belge Order Service'in **koddaki** durumunu özetler. HTTP sözleşmesinin tek yetkili kaynağı koddan üretilen OpenAPI belgesidir (`SWAGGER_ENABLED=true` iken `GET /v3/api-docs`; varsayılan kapalı). Burada anlatılan bir şeyin uygulanmış olması, başka servislerle uçtan uca doğrulandığı anlamına gelmez.
>
> **Etiketler:** **UYGULANDI** (kodda var ve Order testleriyle doğrulandı), **UYGULANMADI** (yok), **DOĞRULANMADI** (kodda var ama gerçek servisle denenmedi).
>
> Ortak belgelerde (`architecture.md`, `SECURITY.md`, `database.md`, `requirements.md`) Order'a özel ayrıntı tutulmaz; yalnızca sınırlar orada, ayrıntı bu belgededir.

## 1. Sorumluluk sınırı

| Order Service'in sahibi olduğu | Sahibi olmadığı |
|---|---|
| Sepetler, siparişler, fiyat/KDV snapshot'ları | Masalar, QR kimlik bilgileri, masa/guest oturumları (QR) |
| İptal kararları ve iptal geçmişi | Ödeme, tahsilat, iade (Payment) |
| Siparişin hazırlık ve ödeme **durumu kaydı** (ödeme durumunu yalnız Payment'ın doğrulanmış olayı değiştirir) | Hazırlık süreci (Kitchen) |
| Masa oturumu kapanış **uygunluğu** ve kalıcı kapanış işlemi (ADR-QR-001) | Masayı fiilen kapatmak (QR) |

Başka servisin veritabanına erişim, ortak JPA entity veya fiziksel FK yoktur. `restaurant_id`, `table_*`, `guest_*` değerleri mantıksal referanstır. Tüm parasal tutarlar `bigint` minor birimdir, fiyatlar KDV dahil brüttür, KDV/net satır başına `ROUND_HALF_UP` ile ayrıştırılır (D-08'in yalnız saklama biçimi kısmı).

## 2. Kimlik ve yetki — geçici sözleşme (D-09)

Order üç kimlik türünü, her biri kendi portu üzerinden doğrular. İstemci başlıkları (`X-Role`, `X-User-Id`, `X-Restaurant-Id`) hiçbir türde yetki kaynağı değildir; çerez okunmaz, CSRF kapalıdır çünkü tarayıcının otomatik eklediği bir kimlik bilgisi kullanılmaz.

| Tür | Başlık | Doğrulayan port | Not |
|---|---|---|---|
| Misafir | `restofy_guest` HttpOnly cookie | `QrSessionPort` | Restoran, masa, guest ve oturum durumu QR'dan gelir |
| Personel | `Authorization: Staff <opak kimlik>` | `AuthContextPort` | Roller `ADMIN`, `WAITER`, `KITCHEN`; aktif restoran doğrulanmış kimlikten alınır |
| Servis | `Authorization: Service <credential>` | `ServiceIdentityPort` | `KITCHEN`, `PAYMENT`, `QR`; yalnız `/api/v1/internal/**` |

- `order-qr` profili gelen QR lifecycle çağrılarının RS256 doğrulayıcısını, Order → QR HTTP adaptörünü ve QR sepet aktivitesi dispatcher'ını yükler. Bu profil `dev` veya `test` ile birlikte başlatılamaz. Gerçek Auth personel ve Menu katalog bağlantıları bu kapsamın dışındadır. `dev`, `test` veya `order-qr` dışındaki profillerde QR ve servis kimliği adaptörleri erişimi güvenli biçimde reddeder.
- Order, QR'ın ürettiği `restofy_guest` cookie'sinden tek bir 43 karakterli opak token okur ve her guest isteğinde ACTIVE bağlamı QR'dan doğrulatır. Guest yazma işlemleri tam olarak bir izinli `Origin`, tek `restofy_guest_csrf` cookie'si ve aynı değerde tek `X-Guest-CSRF` header'ı gerektirir. Order, Origin'i `CORS_ALLOWED_ORIGINS` ile karşılaştırır. Personel ve iç servis kimliği ayrı kalır. Guest Bearer istekleri reddedilir.
- Salt okunur GET ve HEAD için CSRF kanıtı aranmaz. Birden fazla veya birleştirilmiş `Authorization` değeri kimlik doğrulamasından önce `400 INVALID_REQUEST` alır. `401` challenge'ı ilgili akışa göre `GuestSession`, `Staff` veya `Service` adını taşır. `GuestSession` challenge'ı cookie sözleşmesini belirtir.
- **`dev` profili kimlik doğrulama yapmaz.** Sabit yer tutucu misafir, personel (ADMIN dahil) ve servis kimlikleri, API Gateway üzerinden gelen isteklerde de kabul edilir. Bu yüzden `dev` profili yalnızca yerel makinede kullanılmalı, paylaşılan veya erişilebilir bir sunucuda çalıştırılmamalıdır. Servis `dev` profiliyle açıldığında günlüğe tek ve belirgin bir uyarı bloğu yazılır (kimlik bilgilerini yazmaz, `dev` dışında üretilmez).
- **Açık karar:** Personel kimliğinin taşınma biçimi (`Staff` başlığı mı, Auth'un `HttpOnly` çerezi mi) Auth sahibiyle netleşecek. Çerez seçilirse Order'ın CSRF/Origin korumasını da üstlenmesi gerekir.
- QR → Order credential üretimi ve dağıtım işi için [QR entegrasyon talebine](QR-REQUESTS-01.md) bakın.

## 3. Uç noktalar

Gateway yalnızca `/api/v1/orders` ve `/api/v1/carts` yollarını yönlendirir. `/api/v1/internal/**` ve `/api/v1/admin/**` Gateway'den yönlendirilmez; ağ erişim kontrolü ayrıca doğrulanmalıdır (D-02, **DOĞRULANMADI**).

### 3.1 Misafir ve personel (UYGULANDI)

| Yöntem ve yol | Kim | Not |
|---|---|---|
| `GET /api/v1/carts/current` | misafir | Boş sepet döner, sepet oluşturmaz |
| `POST /api/v1/carts/current/items` | misafir | İsteğe bağlı `Idempotency-Key`; aynı seçim tek satıra birleşir |
| `PATCH /api/v1/carts/current/items/{id}` | misafir | Miktarı ayarlar |
| `DELETE /api/v1/carts/current/items/{id}` ve `/items` | misafir | Satır sil / sepeti temizle |
| `POST /api/v1/orders` | misafir | `Idempotency-Key` **zorunlu**; sepeti siparişe çevirir |
| `GET /api/v1/orders`, `/{id}`, `/{id}/history` | misafir (kendi), personel | Filtre: `tableSessionId`, `tableId`, `view`, `paymentStatus`; KITCHEN indirgenmiş görünüm görür (tutar ve ödeme yok) |
| `PATCH /api/v1/orders/{id}/fulfillment-status` | personel | Bir adım ileri |
| `POST /api/v1/orders/{id}/cancellations` | siparişin sahibi misafir, `WAITER`, `ADMIN` | `Idempotency-Key` zorunlu |
| `GET /api/v1/orders/{id}/cancellations` | sahibi misafir, personel | |
| `PATCH /api/v1/orders/{id}/cancellations/{cid}/reason` | `WAITER`, `ADMIN` | Eksik nedeni bir kez ekler |
| `GET /api/v1/admin/order-cancellations` | `ADMIN` | Restoran kimlikten alınır, istek parametresinden değil |

Başka restoranın veya başka misafirin kaynağı **404** döner (varlık sızdırılmaz); rol yetmiyorsa 403, kimlik yoksa 401.

### 3.2 Servisten servise (UYGULANDI, DOĞRULANMADI)

| Yöntem ve yol | Yalnız | Amaç |
|---|---|---|
| `POST /api/v1/internal/order-events/kitchen` | KITCHEN | `WAITING/PREPARING/READY` durumu |
| `POST /api/v1/internal/order-events/kitchen-cancellation-request` | KITCHEN | Kitchen'ın kendi iptal talebi (neden zorunlu) |
| `POST /api/v1/internal/order-events/kitchen-cancellation` | KITCHEN | Bekleyen iptal talebine onay/ret |
| `POST /api/v1/internal/order-events/payment` | PAYMENT | Tek ödeme durumu değiştirme yolu |
| `POST /api/v1/internal/table-sessions/{id}/closure-preparations` | QR | Kapanış veya boşta-süre bitişi için izin ve tutuk (`purpose` SETTLED veya IDLE, bkz. 5) |
| `GET  /api/v1/internal/table-sessions/{id}/closure-preparations/{operationId}?restaurantId=…&commandDigest=…` | QR | İşlemin durumu ve izin yenileme/geri çekme (bilinmeyen işlem `NOT_STARTED`) |
| `POST /api/v1/internal/table-sessions/{id}/closure-preparations/{operationId}/confirmation` | QR | `CLOSED` (isteğe bağlı makbuzla) / `NOT_CLOSED` (abort) |
| `GET  /api/v1/internal/table-sessions/{id}/order-state?restaurantId=` | QR | Siparişlerin boşta-süre kuralları için anlamı, salt okunur |

`kitchen-cancellation-request` aynı çağrının yanıtında karar verir (`outcome` `APPLIED` veya `REJECTED`; ayrı bir olay yok ve Kitchen kendi talebinin sonucu olarak olay hedeflerinden bilerek çıkarılmıştır). Yanıt, kararın verildiği andaki sipariş sürümünü (`orderVersion`: onay sürümü artırır, ret siparişe dokunmaz) ve kararın zamanını (`decidedAt`) taşır. Aynı `eventId` ve aynı gövdeyle tekrar gelen istek **ilk kararın** sürümünü ve zamanını döner (sipariş bu arada ilerlemiş olsa da); sürüm `order_cancellations.decided_order_version` (V50) alanında saklanır. Tekilleştirme kaydı süresi dolduktan sonra gelen tekrarda (72 saat) `cancellationId`, `orderVersion` ve `decidedAt` boş döner, sonuç yine ilk sonuçtur. Kitchen kendi talebiyle eşleştirmeyi kendi outbox satırının `eventId` değeriyle yapar; ayrı bir `requestId` alanı yoktur. Iptal nedeni kataloğu: `CHANGED_MIND`, `ORDERED_BY_MISTAKE`, `WAIT_TOO_LONG`, `CUSTOMER_REQUEST`, `ITEM_UNAVAILABLE`, `KITCHEN_ERROR`, `OTHER` (Kitchen'ın `OUT_OF_STOCK` değeri `ITEM_UNAVAILABLE` olarak gönderilir).

Her olayda `eventId` tekilleştirme sağlar: aynı kimlik tekrar gelirse işlem iki kez yapılmaz (`duplicate=true`), aynı kimlik farklı gövdeyle gelirse `409`. Başka restoranın siparişi `404`.

## 4. İş kuralları (UYGULANDI)

**Hazırlık durumu.** `RECEIVED → PREPARING → READY → SERVED → COMPLETED`; adım atlanmaz, geri dönülmez. `PREPARING/READY` için yetkili Kitchen servisidir; personelin bunları `PATCH` ile vermesi yalnız `dev`/`test` profilinde açıktır. `SERVED/COMPLETED` için `WAITER` veya `ADMIN`. `COMPLETED` ödeme gerektirmez (operasyonel ve finansal tamamlanma ayrıdır). `CANCELLED` yalnız iptal kullanım senaryosuyla gelir.

**Ödeme durumu** (hazırlıktan bağımsız ikinci eksen): `NOT_REQUESTED → PENDING → PAID | FAILED | CANCELLED`, `FAILED → PENDING`; `PAID` ve `CANCELLED` sonludur. Yalnız Payment'ın doğrulanmış olayı değiştirir; istemcideki başarı sayfası kanıt sayılmaz. `PENDING`/`PAID` olayı siparişin güncel borcunu ve para birimini taşımalıdır (`409 PAYMENT_MISMATCH`).

**Tahsilat güvenliği.** Her tahsilat `payment_captures` tablosunda `captureId` ile veritabanı kısıtlarıyla tekil tutulur: silinmez, yeniden yazılmaz, bir sipariş en çok bir tahsilatla kapanır. İkinci farklı tahsilat, başka siparişte kullanılmış `captureId`, tutarı uyuşmayan veya iptal edilmiş siparişe gelen `PAID` uzlaştırma vakası olur. `PENDING`'inden önce gelen `PAID` doğrulanmadan uygulanmaz, kalıcı bekler.

**Sipariş oluşturma.** Fiyat, KDV ve toplam sunucuda hesaplanır, istemcinin gönderdiği tutara güvenilmez. Müşterinin onayladığı toplam ve fiyat parmak izi uyuşmazsa sipariş verilmez (`409 PRICE_CHANGED`); değişmiş fiyat ancak parmak izi + `acknowledgePriceChanges` ile kabul edilir. Sipariş anında QR oturumu yeniden sorulur. Aynı `Idempotency-Key` aynı siparişi döner, farklı gövdeyle `409`. Bir sepet en çok bir kez siparişe çevrilir.

**İptal.** Tam veya kısmi (satır ve adet). `RECEIVED` siparişte talep aynı işlemde kesinleşir; `PREPARING` siparişte yalnız güvenilir Kitchen onayıyla (onay yoksa talep açık kalır, hiçbir zaman otomatik onay veya zaman aşımı yoktur); `READY` ve sonrası iptal edilemez (D-02); ödenmiş siparişi müşteri iptal edemez. İptal **iade yapmaz**: gereken iade (`refund_required_minor`) kaydedilir, gerçekleşen iade yalnız Payment'ın doğrulamasıyla yazılır. Neden isteğe bağlı kısıtlı katalog ve en çok 300 karakter düz metindir. Yazar türü `GUEST`, `STAFF` veya `SERVICE`'tir; servisin bildirdiği personel (`reported_staff_id`) yalnız beyandır, hiçbir yetki kararında kullanılmaz.

**Eşzamanlılık.** Aynı sipariş üzerindeki durum, ödeme ve iptal işlemleri sipariş satırı kilidiyle serileşir. Aynı masa oturumunda checkout ile kapanış sıralaması için bkz. 5.

**Saklama.** Idempotency kayıtları: sepet 24 saat, checkout ve iptal 72 saat. Teslim edilmiş outbox 30 gün, inbox 90 gün. Finansal kayıtlar (`payment_captures`, `payment_reconciliation_cases`) hiçbir temizlikte silinmez.

## 5. ADR-QR-001 — masa oturumu yaşam döngüsü (Order tarafı)

QR masa ve oturumların, Order siparişlerin sahibidir. QR'da ayrıntılı sipariş/finans kopyası tutulmaz. Yanıtlar QR'ın `OrderSessionAccess`, `SessionTransition` ve `OrderSessionState` tiplerine eşlenir. QR'ın HTTP istemcisi PR #28'de yer alır.

**İzin (authority).** Hazırlama ve sorgu yanıtı `sourceStatus`, `effect`, pozitif `sourceRevision`, `verifiedAt`, `validUntil` (`verifiedAt` + 1 dakika), `complete`, `checkoutFenced` ve `idleGuestIds` taşır. QR etkiyi uygulamadan önce aynı QR komutunu, kesin kapsamı, tazeliği ve kalıcı checkout engelini doğrular. `dev/test` bütün yerel Order kapsamını değerlendirir. `order-qr` profilinde sipariş içeren bir oturum için yeni izin, mevcut `PREPARED` izninin yenilenmesi ve `order-state` **503 DEPENDENCY_UNAVAILABLE** döner. Siparişsiz oturum SETTLED kapanışına uygun değildir. Kalıcı işlemin final durumu ve K13 teyit/abort yolu kullanılabilir.

**İzin yenileme ve geri çekme.** Aynı işlemi `GET` ile sormak siparişleri yeniden kontrol eder: hâlâ geçerliyse izin yenilenir (yeni `verifiedAt`/`validUntil`); QR yerelde bir şey uygulamadan önce siparişler değiştiyse izin geri çekilir (`BLOCKED` veya `UNKNOWN`, `effect=NONE`, `blockers`). Geri çekilen işlemin tutuğu, QR `NOT_CLOSED` ile abort edene kadar sürer; zaman aşımı abort sayılmaz. Order'ın "hayır" deme hakkı QR yerelde uygulayana kadar sürer, uyguladıktan sonra biter (aşağıda `CLOSED`). `GET` yanıtında `200 NOT_STARTED` gelirse QR, yalnız yerel komut hâlâ hazırlık bekliyorsa aynı komutla prepare yapabilir. QR'ın yerel abort kararı varsa `NOT_CLOSED` teyidini tekrarlar.

**Amaç `SETTLED`: kapanış uygunluğu (K2, K3, K7) — UYGULANDI.** Oturumun tüm siparişlerine, hangi misafir verdiyse bakılır. Hiç siparişi olmayan misafir kapsama girmez; hiç siparişi olmayan oturum asla uygun değildir. Sonuç:

| Sonuç | Anlamı |
|---|---|
| `ELIGIBLE` | Her sipariş `COMPLETED` ve ödenmiş (borç 0), veya `CANCELLED` ve iade bekleyen yok |
| `NOT_ELIGIBLE` | Kesin engel var: `NO_ORDERS`, `ORDER_NOT_COMPLETED`, `PAYMENT_NOT_SETTLED`, `CANCELLATION_PENDING`, `REFUND_PENDING`, `SCOPE_CHANGED` (izin verildikten sonra siparişler değişti) |
| `UNKNOWN` | Para belirsiz, uygun sayılmaz: `PAYMENT_IN_PROGRESS`, `PAYMENT_VERIFICATION_PENDING`, `RECONCILIATION_REQUIRED` |

Kesin engel belirsizlikten önce gelir. Ödeme durumu, Payment'ın doğrulanmış olaylarıyla Order'da kayıtlı hâlidir; Payment'a doğrudan sorgu **UYGULANMADI**.

**Amaç `IDLE`: boşta-süre bitişi (K6) — UYGULANDI.** Order, süreleri `requestedAt` anına göre değerlendirir. `mainIdleExpiresAt` veya misafirin `idleExpiresAt` değeri boşsa ilgili süre dolmamış sayılır. Hiçbir sipariş oturumu korumuyorsa ve ana süre bittiyse tüm oturum sona erebilir (`IDLE_MAIN`). Diğer durumlarda yalnızca süresi bitmiş ve siparişiyle korunmayan adaylar sona erebilir (`IDLE_GUESTS`, `idleGuestIds`). Hiçbiri uygun değilse `BLOCKED` (`SESSION_PROTECTED_BY_ORDERS` veya `NOTHING_TO_EXPIRE`) döner. Yerleşik bir `SETTLED` oturum kapanış protokolünü kullanır. `IDLE_GUESTS` tutuğu yalnız seçilen misafirlerin sipariş vermesini engeller. Tamamlanan bir `IDLE_GUESTS` işleminin ardından oturumda yeni işlem hazırlanabilir.

QR, kalıcı komutunda tam oturum görüntüsünü ve o komuttan üretilen digest'i korur. HTTP isteğinde yalnız `requestedAt` anında süresi dolmuş en fazla 500 misafir adayını gönderir. Daha fazla aday varsa QR, önceki işlemin sonucu kesinleştikten sonra güncel görüntüyü kullanarak kalan adayları yeni bir `operationId` ile değerlendirir.

**Sipariş durumu (order-state) — UYGULANDI.** `GET /api/v1/internal/table-sessions/{id}/order-state?restaurantId=` salt okunur, hiçbir şeye izin vermez. `knowledge`: `NO_ORDERS` (hiç sipariş yok veya hepsi güvenle iptal), `OPEN_WORK` (bitmemiş, ödenmemiş veya parası belirsiz sipariş var), `SETTLED`; eksik veri hiçbir zaman "sipariş yok" diye bildirilmez. `protectedGuests` güvenle iptal edilmemiş siparişi olan misafirlerdir; `cancellationReleases` tüm siparişleri güvenle iptal edilmiş misafiri iptal anıyla eşler; `mainCancellationAt` yalnız oturumdaki her sipariş güvenle iptal edildiyse ana oturumun serbest kaldığı andır. "Güvenle iptal" = iptal edilmiş, kararı verilmiş, iade bekleyen yok, ödeme yolda değil, doğrulanmamış veya uzlaştırılmamış bildirim yok; belirsiz para kimseyi serbest bırakmaz. `revision`, `verifiedAt` ve `validUntil` izinle aynı anlamdadır.

**Sıralama (K8) — UYGULANDI.** Checkout ve hazırlık, masa oturumu başına PostgreSQL advisory lock ile sıralanır (checkout paylaşımlı, hazırlık özel kilit; çok örnekli çalışmada da geçerli). Tutuk önce kurulduysa checkout hiçbir kayıt yazmadan `409 TABLE_SESSION_CLOSING` döner; checkout önce girdiyse kapsamda yer alır.

**Kalıcı işlem (K9) — UYGULANDI.** `table_session_closures` (V48): yalnız izin verilen işlem saklanır (`PREPARED → COMPLETED | RELEASED`). Aynı `operationId` ve aynı `commandDigest` aynı işlemi döner (izin yenilenir); aynı `operationId` başka bir komutla (digest, amaç, oturum, restoran) `409 IDEMPOTENCY_CONFLICT`; oturumda başka canlı işlem varsa `409 CLOSURE_IN_PROGRESS`. Hazırlanmış işlem zamanla kendiliğinden bırakılmaz.

**Tam komut doğrulaması.** QR'ın adaptörü yanıtın kendi komutuna ait olduğunu doğrulamadan ACK/abort sonucu üretmez (QR entegrasyon rehberi §6). Bu yüzden yanıt `commandDigest` alanını taşır. `.../confirmation` isteğinde `commandDigest` **zorunludur** (eksikse `422`); `GET .../closure-preparations/{operationId}` için isteğe bağlıdır. Verilen digest, aynı kapsamdaki hazırlık veya abort kaydıyla eşleşmelidir. Uyuşmazlıkta `409 IDEMPOTENCY_CONFLICT` döner. `NOT_STARTED` yanıtı istekte verilmişse digest'i geri yansıtır. Digest biçimi 64 küçük harfli hex'tir, aksi `422`.

**Personel (K11) kaynaklı kapanış.** QR'ın garson/admin komutu aynı `SETTLED` protokolüdür; fark yalnız QR'ın komut digest'indedir (kaynak ve gerçek aktör digest'e bağlanır). Order digest'i anlamlandırmaz, yalnız saklar ve eşler. Personel isteği ödeme/iş koşullarını atlamaz; uygunluk kuralı değişmedi. Nakit/online (K10) ayrımı Payment'tadır: Order yine yalnız Payment'ın doğrulanmış olaylarıyla kendi kayıtlarında ödenmiş saydığını kullanır.

**Abort (`NOT_CLOSED`).** Hazırlanmış işlemde tutuğu bırakır ve yeni siparişler yeniden kabul edilir. Aynı onay tekrar gönderilebilir. Ters terminal onay `409 INVALID_STATUS_TRANSITION` döner. Abort önce ulaşırsa Order, `operationId`, restoran, oturum ve digest'i V131'deki değişmez kayda yazar ve `200 NOT_STARTED` döner. Aynı komutun tekrar abort çağrısı aynı sonucu alır. Sonradan ulaşan eşleşen prepare `409 INVALID_STATUS_TRANSITION`, aynı kimliğin farklı kapsam veya digest ile hazırlanması `409 IDEMPOTENCY_CONFLICT` döner. Abort kaydı checkout tutuğu yaratmaz. Tek başına GET `NOT_STARTED` yanıtı bir abort kararı oluşturmaz.

**Makbuz.** `CLOSED` onayı isteğe bağlı olarak QR'ın yerel sonucunu taşır: `effect`, `sessionVersion`, `tableVersion`, `completedAt`, `expiredGuests`. `effect` izin verilenle aynı olmalıdır (aksi hâlde `409 CLOSURE_RECEIPT_MISMATCH` ve işlem `PREPARED` kalır); makbuz saklanır.

**K13 ve kapsam sapması.** Kimlikleri ve digest'i eşleşen `PREPARED` işleme ait geçerli `CLOSED` teyidi işlemi `COMPLETED` yapar. Makbuz gönderildiyse etkisi hazırlanan etkiyle eşleşmelidir. İzin kapsamı sonradan değiştiyse Order aynı transaction'da V49 `table_session_closure_drifts` kaydını ekler ve `WARN` yazar. Tekrar ACK veya GET aynı kalıcı sonucu, `scopeDrift` ve `driftBlockers` değerlerini döndürür. Yanıtın okuma zamanı ve kaynak sürümü güncel olabilir. QR yerel sonucu korur, sapma uzlaştırmaya kalır. Sapma ekranı, raporu ve olayı **UYGULANMADI**.

**Misafir aktivitesi (K6) — Order'ın payı UYGULANDI.** Başarılı her sepet değişikliği (ekle, miktar, sil, temizle) aynı işlemde QR hedefli `CartActivityRecorded` outbox olayı yazar. Okuma, idempotent tekrar, değişmeyen miktar, boş sepeti temizleme ve reddedilen istek olay üretmez. Olay ürün, fiyat, not veya jeton taşımaz. Dispatcher commit edilmiş olayı QR'a teslim eder. Oturum süresini QR günceller.

**Açık bağımlılık.** K4'ün Payment tarafı ve Order'ın Payment doğrulama sorgusu bekliyor.

### 5.1 QR HTTP alanları ve sonuçları

Tüm yollar `/api/v1/internal/table-sessions/{tableSessionId}` altında, doğrudan Order'a ve QR servis credential'ı ile çağrılır. POST gövdeleri `application/json`; UUID'ler standart metin, zamanlar ISO-8601 UTC'dir. JSON gövdesinde bilinmeyen alanlar 400 döner. İstemci yanıtın ek alanlarını tolere eder.

- `GET /order-state?restaurantId=…` salt okumadır. 200 gövdesi `restaurantId`, `tableSessionId`, `knowledge` (`NO_ORDERS`, `OPEN_WORK`, `SETTLED`), pozitif `revision`, `verifiedAt`, `validUntil`, UUID dizisi `protectedGuests`, UUID → zaman haritası `cancellationReleases` ve nullable `mainCancellationAt` içerir. QR adaptörü wire `tableSessionId` alanını `sessionId` olarak eşler.
- `POST /closure-preparations` içinde `restaurantId`, `operationId`, `purpose` (`SETTLED`, `IDLE`) ve `commandDigest` zorunludur. IDLE ayrıca `requestedAt` ve 0–500 elemanlı bir `guests` dizisi gerektirir. Her elemanda null olmayan `guestSessionId` gerekir. `mainIdleExpiresAt` ve `idleExpiresAt` atlanabilir veya null olabilir. 500'ü aşan istek `422 VALIDATION_ERROR` döner. SETTLED için IDLE alanları atlanabilir. Ayrı `Idempotency-Key` başlığı yoktur.
- `GET /closure-preparations/{operationId}?restaurantId=…&commandDigest=…` çağrısında QR her zaman digest gönderir. Sunucuda bu query alanı geriye uyumluluk için opsiyoneldir. Bilinmeyen veya verilen restoran/main kapsamı dışında kalan işlemde 200 `NOT_STARTED`, `purpose=null` ve istek digest'i döner. Digest verilmediyse bu yanıtın `commandDigest` değeri null olur. Aynı kapsamda farklı digest 409 döner.
- `POST /closure-preparations/{operationId}/confirmation` içinde `restaurantId`, `outcome` (`CLOSED`, `NOT_CLOSED`) ve `commandDigest` zorunludur. Opsiyonel `receipt` içinde `effect`, `completedAt`, sıfır veya pozitif `sessionVersion`, `tableVersion`, `expiredGuests` gönderin. QR, CLOSED için kalıcı yerel makbuzunu kullanır. Bilinen işlem için CLOSED → COMPLETED ve NOT_CLOSED → RELEASED. Hiç hazırlanmamış bir işlemin NOT_CLOSED teyidi digest'i geri yansıtan 200 NOT_STARTED döner.

`ClosureResponse` kimlikleri `operationId`, `restaurantId`, `tableSessionId`, `purpose`, `commandDigest`; kalıcı durum `state` (`NOT_STARTED`, `NOT_PREPARED`, `PREPARED`, `COMPLETED`, `RELEASED`); kaynak durumu `sourceStatus` (`NOT_STARTED`, `UNKNOWN`, `BLOCKED`, `AUTHORISED`); etki `effect` (`NONE`, `SETTLED_CLOSE`, `IDLE_MAIN`, `IDLE_GUESTS`); karar `outcome` (`ELIGIBLE`, `NOT_ELIGIBLE`, `UNKNOWN`) taşır. `blockers`, `idleGuestIds` ve `driftBlockers` boş dizi olabilir. `scopeDrift`, `complete` ve `checkoutFenced` boolean'dır. `orderCount` negatif olamaz, `scopeFingerprint` NOT_STARTED için null, `preparedAt`, `completedAt`, `releasedAt` duruma bağlı nullable alanlardır. `sourceRevision`, `verifiedAt` ve `validUntil` her yanıtta vardır. Tam bir blocker listesi için Order `ClosureBlocker` enum'una bakın.

Gövde örneği, IDLE `POST /closure-preparations`:

```json
{
  "restaurantId": "8ba04b97-d241-4e86-970e-481feca0292e",
  "operationId": "9e2ee566-0910-489b-93d6-06e48195500b",
  "purpose": "IDLE",
  "commandDigest": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
  "requestedAt": "2026-11-18T12:00:00Z",
  "mainIdleExpiresAt": "2026-11-18T11:58:00Z",
  "guests": []
}
```

Örnek 200, aynı işlem henüz hazırlanmadığında:

```json
{
  "operationId": "9e2ee566-0910-489b-93d6-06e48195500b",
  "restaurantId": "8ba04b97-d241-4e86-970e-481feca0292e",
  "tableSessionId": "fbab1cc9-a2e9-49dd-991f-f75fd40d732a",
  "purpose": null,
  "commandDigest": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
  "state": "NOT_STARTED",
  "sourceStatus": "NOT_STARTED",
  "effect": "NONE",
  "outcome": "UNKNOWN",
  "blockers": [],
  "orderCount": 0,
  "scopeFingerprint": null,
  "sourceRevision": 1,
  "verifiedAt": "2026-11-18T12:00:00Z",
  "validUntil": "2026-11-18T12:01:00Z",
  "complete": true,
  "checkoutFenced": false,
  "idleGuestIds": [],
  "preparedAt": null,
  "completedAt": null,
  "releasedAt": null,
  "scopeDrift": false,
  "driftBlockers": []
}
```

Örnek CLOSED teyit gövdesi, `POST /closure-preparations/{operationId}/confirmation`:

```json
{
  "restaurantId": "8ba04b97-d241-4e86-970e-481feca0292e",
  "outcome": "CLOSED",
  "commandDigest": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
  "receipt": {
    "effect": "IDLE_MAIN",
    "sessionVersion": 3,
    "tableVersion": 2,
    "completedAt": "2026-11-18T12:00:11Z",
    "expiredGuests": 0
  }
}
```

`GET /order-state` için siparişsiz oturumun 200 yanıtı:

```json
{
  "restaurantId": "8ba04b97-d241-4e86-970e-481feca0292e",
  "tableSessionId": "fbab1cc9-a2e9-49dd-991f-f75fd40d732a",
  "knowledge": "NO_ORDERS",
  "revision": 1,
  "verifiedAt": "2026-11-18T12:00:00Z",
  "validUntil": "2026-11-18T12:01:00Z",
  "protectedGuests": [],
  "cancellationReleases": {},
  "mainCancellationAt": null
}
```

Hata gövdesinde `code`, `message`, varsa `details` ve `correlationId` bulunur. Örnek `{"code":"CLOSURE_RECEIPT_MISMATCH","message":"The receipt names another effect than the one that was permitted","details":{"permitted":"IDLE_MAIN","received":"SETTLED_CLOSE"}}`. Geçersiz JSON/UUID, bilinmeyen enum veya eksik zorunlu query 400 `INVALID_REQUEST`; DTO alan doğrulaması ve digest deseni 422 `VALIDATION_ERROR`. Eksik/geçersiz credential 401, tanınan imzayla yetkisiz servis/scope veya istek/tenant uyuşmazlığı 403. Yanlış digest veya prepare'da aynı ID ile farklı restoran/main/amaç 409 `IDEMPOTENCY_CONFLICT`; başka canlı işlem 409 `CLOSURE_IN_PROGRESS`; bilinmeyen işleme CLOSED 404 `CLOSURE_NOT_FOUND`; yanlış makbuz etkisi ilk ve tekrarlanan CLOSED için 409 `CLOSURE_RECEIPT_MISMATCH`; ters terminal teyit 409 `INVALID_STATUS_TRANSITION`; kaynak kullanılamıyorsa 503 `DEPENDENCY_UNAVAILABLE`. Transport timeout'u veya 5xx, NOT_STARTED/NOT_CLOSED sonucu üretmez.

### 5.2 Payment kaynak bağımlılığı

Payment sahibi Alper'in kaynak API'si bekliyor. Order'ın ihtiyacı, doğrulanmış servis kimliğiyle restoran + main + kesin sipariş kimlikleri/sürümleri için tek bir tam, sürümlü ve süreli mali kapsam sonucu. Her sipariş için para birimi, kalan borç, doğrulanmış tahsilat, devam eden veya belirsiz deneme, iade ve uzlaştırma engeli değerlendirilmeli. Eksik sipariş, eksik veri, stale veya timeout mali onay vermez. Endpoint ve DTO, Payment sahibi hazır olduğunda kesinleştirilecek.

Order'ın ileride kullanacağı akışta uzak sorgu DB kilidi dışında yapılmalı. Sipariş kapsamı ve sürümü kısa yerel gate işlemi altında tekrar eşlenmeli, uyuşmazlıkta yeni izin verilmemeli. İzin geçerliliği mali kaynağın kanıt süresini aşmamalı. Kaynak değişikliği ile yerel commit arasındaki ayrı transaction sınırı, Payment kaynak garantisiyle birlikte kararlaştırılacak. K13 geçerli yerel CLOSED sonucunu korur ve sapmayı uzlaştırmaya kaydeder.

### 5.3 Order → QR HTTP ve yapılandırma

- `POST /api/v1/internal/qr/guest-sessions/validate` opak `guestToken` taşır ve doğrulanmış ACTIVE bağlamı döndürür. `GET /api/v1/internal/qr/table-sessions/{sessionId}/guests/{guestId}/state` checkout öncesinde durumu tekrar sorgular. Order, QR'ın belgelenmiş guest problem gövdesine sahip `404` yanıtını oturum reddi olarak işler. Diğer `404` yanıtları, `401/403` servis kimliği hataları ve erişilemeyen kaynak `503 DEPENDENCY_UNAVAILABLE` olarak döner. `checkedAt` değerlendirildiği anda en fazla 15 saniye eski veya 2 saniye ileride olabilir. Her checkout için yeni QR sorgusu yapılır ve yerel kapanış kilidi uygulanır.
- `POST /api/v1/internal/qr/events/cart-activity` commit edilmiş v1 olayı taşır. Üç çağrıda da ayrı Order RSA anahtarıyla imzalanmış `Authorization: Service <JWT>` kullanılır. İmza restoranı, yöntemi, ham hedefi ve gönderilen gövdenin SHA-256 özetini bağlar. JWT en fazla 30 saniye geçerlidir ve her HTTP denemesinde yenilenir. Gövde sınırı 16 KiB'dir.
- Compose kullanırken `order-service.environment.SPRING_PROFILES_ACTIVE` değerini servis bazlı override ile `order-qr` olarak ayarlayın. Order için farklı bir QR adresi gerekiyorsa `QR_SERVICE_URL` değerini aynı Order override'ında verin. İzinli tarayıcı origin listesi `CORS_ALLOWED_ORIGINS` ile sağlanır.
- Production dağıtımında Order için `APP_ENV=prod` veya `APP_ENV=production` kullanın. `prod` ve `production` Spring profilleri de production sayılır. Bu işaretlerden biri `dev`, `test` ya da `local` profiliyle birlikte bulunursa Order başlatmayı reddeder.
- `QR_SERVICE_URL` HTTPS origin olmalıdır. `ORDER_QR_CALLER_KEY_ID`, Base64 PKCS#8 `ORDER_QR_CALLER_PRIVATE_KEY` ve `ORDER_QR_CALLER_RESTAURANT_ID` Order'a verilir. QR açık anahtarı `QR_ORDER_CALLER_PUBLIC_KEYS` ile alır. QR'ın `AUTH_RESTAURANT_ID` değeri Order outbound restoranıyla aynı olmalıdır. Inbound için Order hâlâ `ORDER_QR_SERVICE_PUBLIC_KEYS` ve `ORDER_QR_SERVICE_RESTAURANTS` kullanır. Anahtar çiftleri ayrı tutulur.
- Özel CA kullanıldığında Order'a genel CA sertifikalarını içeren truststore'u salt okunur mount ile verin. Yalnız Order'ın Compose override'ında `JAVA_TOOL_OPTIONS` ile JVM truststore yolunu yapılandırın. JVM bu seçenekleri günlüğe yazabildiği için burada yalnız yol kullanın. `QR_SERVICE_URL` adı QR sertifikasının SAN alanıyla eşleşmeli. QR'ın Order'a ulaştığı HTTPS uç ayrı yapılandırılır. TLS doğrudan Order'da sonlanıyorsa Spring Boot'un `SERVER_SSL_*` ayarlarını ve salt okunur sunucu keystore'unu yalnız Order'a uygulayın. Keystore parolasını deployment secret üzerinden verin. Sertifika ve hostname doğrulaması açık kalır. QR TLS yapılandırması QR dağıtımının sorumluluğunda.

## 6. Olaylar (outbox)

Olaylar iş değişikliğiyle aynı transaction'da `outbox_events` ve hedef başına `outbox_deliveries` olarak yazılır. Dispatcher yalnız yapılandırılmış restoranın QR hedefli `CartActivityRecorded` v1 teslimatlarını işler. PostgreSQL teslimatı kısa süreliğine sahiplenir ve HTTP çağrısı transaction dışında yapılır. `204` teslimatı tamamlar. Geçici bağlantı ve servis hataları 5 saniyeden, `401/403/404` yapılandırma hataları 30 saniyeden başlayan artan gecikmeyle tekrar denenir. Bütün hata türleri aynı beş denemelik üst sınırı paylaşır. `409` ve tekrar edilemeyen hatalar `FAILED` kalır. Denemeler tükendiğinde olay ve hata kodu inceleme için saklanır. Son deneme sırasında süreç durursa sonucu belirsiz olarak işaretlenir. QR, `eventId` ve içerikle tekrarları tekilleştirir. Diğer olay hedeflerinde teslimat D-10 kararı kapsamında bekliyor.

## 7. Veritabanı

Order, V41–V50 zincirini ve onaylı V131 ek sürümünü kullanır. V50, paylaşılmış zincirin üzerine eklenen ilk migration'dır.

| Sürüm | İçerik |
|---|---|
| V41 | Sepetler (aktif sepet tekilliği, sona ermiş sepet yeniden aktifleşmez) |
| V42 | Siparişler, kalemler, seçenekler, numara sayacı; snapshot değişmezliği (trigger) |
| V43 | Idempotency kayıtları, outbox |
| V44 | Durum geçmişi (yalnız eklenir) |
| V45 | Inbox; hazırlık ve ödeme geçiş koruması (trigger) |
| V46 | Tahsilatlar ve uzlaştırma vakaları |
| V47 | İptaller, kalemleri ve nedenleri; `cancelled_quantity` tutarlılığı |
| V48 | Masa oturumu işlemleri: amaç, etki, komut özeti, tutuk, makbuz (kapanış ve boşta-süre) |
| V49 | Kapanış kapsam sapması kayıtları (yalnızca eklenir) |
| V50 | Kararın sipariş sürümü `decided_order_version` (bir kez yazılır; koruma tetikleyicisi genişletildi); neden kataloğunda `CUSTOMER_REQUEST`. V47 değiştirilmeden, onun üzerine uygulanır |
| V131 | Hazırlık öncesi abort kararları için değişmez `table_session_closure_aborts` kaydı |

`ddl-auto=validate`; şemayı yalnız Flyway değiştirir. Uygulanmış bir migration paylaşıldıktan sonra yeniden yazılmaz; değişiklik yeni bir migration olarak eklenir (V50 böyle eklendi).

## 8. Hata sözleşmesi

`ProblemDetail` benzeri tek gövde: `code`, `message`, isteğe bağlı `details`; yığın izi, SQL, jeton veya kişisel veri içermez. Başlıca kodlar: `401 UNAUTHENTICATED`, `403 ACCESS_DENIED`, `404 ORDER_NOT_FOUND / CLOSURE_NOT_FOUND`, `409 PRICE_CHANGED / TABLE_SESSION_NOT_ACTIVE / TABLE_SESSION_CLOSING / IDEMPOTENCY_CONFLICT / INVALID_STATUS_TRANSITION / PAYMENT_MISMATCH / CANCELLATION_NOT_ALLOWED / CLOSURE_IN_PROGRESS`, `422 VALIDATION_ERROR`, `503 DEPENDENCY_UNAVAILABLE`. Tam liste `ErrorCode` içindedir.

## 9. Açık işler

- Ortak tarayıcı akışı, QR entegrasyon ve guest-cookie dallarının birleşmesine ve QR rate limit kapısına bağlı. Tarayıcı sayfası `/api/v1` dışında çalışıyorsa bu path'e bağlı CSRF cookie'si sayfanın JavaScript'inden okunamaz. QR, Gateway ve frontend için ortak çözüm ayrıca kararlaştırılacak.
- Personel kimliği taşıma biçimi (Auth ile).
- Order → Payment doğrulama sorgusu ve QR sepet aktivitesi dışındaki outbox teslimatları.
- QR ekibinin yapacağı işler [QR-REQUESTS-01](QR-REQUESTS-01.md) belgesinde.
- Sapma kayıtlarını (V49) kimin okuyacağı: şimdilik yalnızca kayıt ve log.
- PR #28 QR ve Order süreçleri ayrı test veritabanlarıyla HTTPS üzerinden doğrulandı. Senaryolar aktif ve kullanılamayan guest doğrulamasını, gecikmiş prepare'ı, Payment olmadan SETTLED reddini, 501 misafirin 500 ve 1 aday olarak işlenmesini, kayıp `204` sonrası Order restart'ını ve QR makbuzunun tek kalmasını kapsadı. Checkout kilit yarışı Order'ın PostgreSQL testlerinde doğrulandı.
