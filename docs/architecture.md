# Restofy — Mimari Analizi ve Hedef Tasarım

**Tarih:** 15 Ekim 2026. Kaynak: nihai gereksinim Word belgesi ve repository.  
**Durum:** Mevcut altyapı analizi + uygulanmamış mimari öneri. Kod, ENV, DB veya dağıtım ayarı değiştirilmedi.

İlgili belgeler: [gereksinimler ve açık kararlar](requirements.md), [veri modeli](database.md), [ER](er-diagram.md), [klasörler](folder-structure.md).

## 1. Mimari değerlendirme

Repository, tek frontend ve 13 backend modülünden oluşan mikroservis monorepo'sudur. API Gateway'in arkasında 12 veri sahibi servis vardır. Tek PostgreSQL sunucusu üzerinde ayrı veritabanları kullanılır; bu yapı tek paylaşımlı iş şeması değildir. Ayrı veritabanı tek başına güvenlik izolasyonu sağlamaz: Compose şu anda tüm veri servislerine aynı `POSTGRES_USER` ve `POSTGRES_PASSWORD` değerlerini geçirir.

Servis sınırları ürün gereksinimleriyle büyük ölçüde örtüşür; ancak işlevler henüz uygulanmamıştır. Üretim Java kodu 13 başlangıç sınıfı ve Order RestClient yapılandırmasından oluşur. Bu nedenle hedef akışların bugün çalıştığı veya mikroservisler arasında dağıtık tutarlılığın çözüldüğü söylenemez.

12 veri servisi erken aşamada önemli işletim maliyeti yaratır: bağlantı havuzları, hata telafisi, sözleşme sürümleri ve bağımsız migration yönetimi gerekir. Bu çalışma mevcut servis yapısını korur; daha fazla servis önermeden kritik akışların dikey olarak tamamlanmasını önerir.

## 2. Mevcut topoloji

```mermaid
flowchart TB
    Browser["Tarayıcı"] --> Frontend["Next.js frontend / host :5000"]
    Browser --> Gateway["API Gateway / host :5050"]
    Frontend -. "Gelecekte sunucu API çağrısı" .-> Gateway
    Gateway --> Auth["Auth"]
    Gateway --> QR["QR"]
    Gateway --> Menu["Menu"]
    Gateway --> Order["Order"]
    Gateway --> Kitchen["Kitchen"]
    Gateway --> Waiter["Waiter"]
    Gateway --> Payment["Payment"]
    Gateway --> Notification["Notification"]
    Gateway --> Admin["Admin"]
    Gateway --> Rating["Rating"]
    Gateway --> Mail["Mail"]
    Gateway --> Timer["Timer"]
    Auth --> PG["Tek PostgreSQL 17 sunucusu / 12 ayrı DB"]
    QR --> PG
    Menu --> PG
    Order --> PG
    Kitchen --> PG
    Waiter --> PG
    Payment --> PG
    Notification --> PG
    Admin --> PG
    Rating --> PG
    Mail --> PG
    Timer --> PG
```

Şema mevcut route/DB yapılandırmasını gösterir; tarayıcı iş çağrıları henüz uygulanmamıştır. Frontend'in API client'ı yoktur. Backend host portları Compose'ta `127.0.0.1` üzerinde yayınlanır: Auth 5051, Order 5052, Kitchen 5053, Waiter 5054, Menu 5055, QR 5056, Payment 5057, Notification 5058, Admin 5059, Rating 5060, Mail 5061, Timer 5062. PostgreSQL varsayılan host portu 5489'dur; portlar ENV ile değişebilir. Container iç servis portu varsayılan 8080'dir.

Gateway Web MVC kullanır; WebFlux tabanlı olduğu varsayılmamalıdır. Route'lar `/api/v1/{alan}` önekini korur; örneğin `/orders`, `/waiters`, `/payments` alanları çoğuldur, `/menu` ve `/qr` farklıdır. Gateway route varlığı alttaki iş endpoint'inin varlığını veya yetkilendirmeyi kanıtlamaz.

## 3. Servis sahipliği — önerilen sınırlar

### Auth

Personel kimliği, parola hash'i, restoran üyeliği/rol, iptal edilebilir auth oturumu, admin TOTP ve kurtarma. Guest masa oturumunun operasyonel sahibi QR; guest kimlik doğrulama sözleşmesi Auth ile birlikte tasarlanır. Siparişin müşteriye ait olup olmadığını yalnız Auth değil, kaynak sahibi servis de denetler.

### Admin

Restoran kaydı, zaman dilimi ve sipariş/ödeme yöntem ayarları. Yönetim ekranı bir erişim yüzeyidir; ürün Menu, masa QR, personel Auth üzerinden yönetilir. Admin başka servis veritabanına yazmaz. Rapor projeksiyonları kaynak olaylardan beslenebilir; finansal doğruluk Payment kayıtlarına dayanır.

### QR

Masa, masaya özel QR kimlik bilgisi, ana masa oturumu, guest oturumu, masa kapanışı ve idle expiry. Tek açık masa oturumu, tarama sayımı ve kapanış sırasında yeni siparişin engellenmesi bu sınırda koordine edilir. Üç saatlik Wi-Fi süresiyle karıştırılmaz.

### Menu

Kategori, ürün, içerik/seçenek, fiyat/vergi tanımı ve ürünün güncel satışa uygunluğunun tek yazarı önerilir. Kitchen ürün kapatma/açma yetkili komutunu Menu'ye iletir. Böylece iki servisin birbirinden farklı availability gerçeği tutması engellenir. Kitchen değişikliğin onayını almadan başarı göstermemelidir.

### Order

Sepet, fiyat snapshot'ı, sipariş kabulü, iptal kararları, teslim ve iş durumu. Kitchen'da hazırlık otoritesini, Payment'ta tahsilat otoritesini devralmaz. Kendi ekranları için sürümlü projeksiyon tutabilir. Bir siparişin iptaline mutfak değil Order karar verir.

### Kitchen

V1 sipariş bazlı hazırlık, salt okunur ürün/seçenek snapshot'ı, startedAt/readyAt, iptal talebi ve kesinleşmiş iptal tüketimi; manuel fire ve gün sonu mutfak sayımı. Servis teslimi Kitchen READY durumunu değiştirmez. Reçete, hammadde stoğu ve ürün bazlı hazırlık V1 kapsamı değildir.

### Waiter

Garson çağrıları, sahiplenme/çözüm ve restoran operasyon görünümü. Teslim isteğini Order'a, kapanış isteğini QR'a, restoranda ödeme teyidini Payment'a yönlendirir. Bu kayıtların ikinci iş otoritesi olmaz.

### Payment

Ödeme girişimi, sağlayıcı referansı/callback, yetkili personel teyidi, başarılı tahsilat, belirsiz sonuç ve uzlaştırma. İstemcinin “başarılı” sayfası ödeme kanıtı değildir. Gerçek sağlayıcı ve iade sözleşmesi açık karardır. Demo başarılı işlem gerçek tahsilat kaydıyla karıştırılmaz.

### Notification / Rating / Mail / Timer

- Notification: Olaydan rol/kişi hedefli bildirim, okundu bilgisi ve teslim durumu. Çağrının çözüldüğüne Waiter karar verir.
- Rating: Order/Payment'tan doğrulanan değerlendirme uygunluğu ve oy/puan özeti; nakit ödeme kuralı açık kalır.
- Mail: E-posta işinin gönderim/yeniden deneme takibi. Kimlik sırrı veya sıfırlama yetkisinin otoritesi değildir.
- Timer: Onaylanırsa Wi-Fi hakkı ve ağ adaptörü. QR idle expiry için Timer çalışıyor olması zorunlu değildir.

## 4. Senkron doğrulama ve dayanıklı olaylar

**Mevcut:** Statik servis URL'leri, Gateway yönlendirmesi ve Order RestClient timeout altyapısı. Broker, service discovery, SSE/WebSocket, outbox/inbox, retry dispatcher veya circuit breaker uygulaması bulunmadı.

**Öneri:** Kullanıcıya anlık kesin cevap gereken komut/doğrulamalar iç HTTP ile; commit sonrası bildirim/projeksiyonlar dayanıklı olay teslimiyle yürütülür. V1 için broker zorunlu tutulmadan veritabanı outbox + iç HTTP dispatcher değerlendirilebilir. Broker seçimi D-10'dur; Kafka/RabbitMQ varmış gibi tasarım yazılmamalıdır.

Her olay önerilen şu alanları taşır: `eventId`, `eventType`, `schemaVersion`, `occurredAt`, `restaurantId`, `aggregateId`, `aggregateVersion`, `correlationId`, `causationId`, gerekli minimum `payload`.

Üretici iş verisi ve outbox kaydını tek yerel transaction'da yazar. Dispatcher hedef başına teslim denemesini izler. Tüketici `(consumer_name,event_id)` tekilliğini, iş değişikliğini ve inbox kaydını aynı transaction'da commit eder. ACK commit'ten sonra gönderilir. Birden fazla hedef için tek global `published=true` alanı yeterli değildir.

Teslimat en az bir kez olabilir; “tam olarak bir kez ağ teslimi” iddiası kurulmaz. Aggregate version eski olayın yeni durumu ezmesini engeller. Sıra boşluğu varsa kısa retry sonrası sahibi servisten snapshot alınır. Hata kuyruğu/manuel uzlaştırma, deneme üst sınırı, backoff ve alarm tanımlanmalıdır. Olay payload'ları ham guest tokenı, parola veya kart verisi taşımaz.

Önerilen olay kataloğu:

- `OrderAccepted`: Order → Kitchen, Waiter, Notification, Admin. Sipariş/ürün snapshot'ı taşır.
- `KitchenPreparationStarted`: Kitchen → Order, Waiter; hazırlık projeksiyonunu ilerletir.
- `KitchenOrderReady`: Kitchen → Order, Waiter, Notification; kaynakta adı belirlenmiştir.
- `OrderCancellationRequested`: **Order → Kitchen/Waiter/Admin/Notification olayı** (mutfak onayı bekleyen talep, UYGULANDI); kesinleşmiş iptal olayı değildir. Bu bir komut değildir: Kitchen'ın kendi talebi (`POST /api/v1/internal/order-events/kitchen-cancellation-request`) ve Kitchen'ın Order talebine cevabı (`POST /api/v1/internal/order-events/kitchen-cancellation`) Order'a giden iki **ayrı çağrıdır**.
- `OrderCancelled` / `OrderItemsCancelled` / `OrderAmountAdjusted` / `OrderCancellationRejected`: Order → ilgili servisler (hedefler K-13 tablosunda; UYGULANDI, schemaVersion 1). Eski belgelerdeki tekil `OrderItemCancelled` adı **kullanılmaz**; tek sözleşme `OrderItemsCancelled`'dır (bir iptalin bir veya daha fazla kalemi).
- `ProductAvailabilityChanged`: Menu → ilgili görüntü/projeksiyonlar; Order checkout'ta otoriteyi ayrıca doğrular.
- `OrderServed`: Order → Waiter/Admin; Kitchen READY kalır.
- `PaymentSucceeded`: Payment → Order/QR/Rating/Admin/Notification; sadece doğrulanmış gerçek tahsilat.
- `TableSessionClosed`: QR → Waiter/Notification/Order; aktif listeler arşivlenir, geçmiş korunur.
- `WasteRecorded`: Kitchen → Admin rapor projeksiyonu.

## 5. Önerilen sipariş akışı

```mermaid
sequenceDiagram
    actor C as Müşteri
    participant G as Gateway
    participant Q as QR
    participant O as Order
    participant M as Menu
    participant K as Kitchen
    participant N as Notification
    C->>G: QR doğrula / guest oturumu aç
    G->>Q: Masa ve QR doğrulaması
    Q-->>C: Ana oturuma bağlı guest bağlamı
    C->>G: Sipariş komutu + idempotency key
    G->>O: Kimlik / tenant bağlamıyla komut
    O->>Q: Sipariş kabul rezervasyonu
    O->>M: Yetkili fiyat / seçenek / availability doğrulaması
    M-->>O: Sürümlü teklif
    O->>O: Sipariş + snapshot + outbox commit
    O->>Q: Rezervasyonu siparişe bağla
    O-->>C: orderId / kabul sonucu
    O-->>K: OrderAccepted dayanıklı teslim
    K->>K: Inbox + mutfak kaydı commit
    K->>K: WAITING → PREPARING → READY
    K-->>O: KitchenOrderReady
    K-->>N: KitchenOrderReady
```

Bu akış uygulama önerisidir, mevcut kod davranışı değildir. Uzak HTTP çağrılarını uzun DB lock'ı altında tutmak yerine kısa yerel transaction ve açık koordinasyon tercih edilir.

### 5.1 Kapanış ve sipariş yarışı

Sadece “oturum açık mı?” GET'i yeterli değildir: kontrol ile Order commit'i arasında garson masayı kapatabilir. Önerilen `order_admissions`, QR'da idempotent bir kabul rezervasyonudur. Kapanış önce ana oturumu `CLOSING` yapar; yeni rezervasyon reddedilir. Mevcut rezervasyonların sonuçları Order'dan uzlaştırılmadan masa kapatılmaz.

Order commit olup QR teyidi kaybolursa rezervasyon zaman aşımıyla kör silinmez; sabit orderId/idempotency eşlemesiyle Order sorgulanır. Rezervasyon iptali ile geciken Order commit yarışı için version/fencing ve komut son tarihi gerekir. Bu koordinasyon uygulanmadan atomik kapanış garantisi verilemez. Basit bir kontrol + event yayını bu ihtiyacı karşılamaz.

**Order Service uygulama durumu (ORDER-01/02):** Order checkout, commit'ten hemen önce `QrSessionPort.currentSessionState(...)` ile oturumu yeniden sorar; bu **yarışı kapatmaz** (kontrol ile commit arasında masa kapanabilir) ve rezervasyon/fencing **uygulanmamıştır**. Yerel olarak doğrulanan: aynı sepetten çift sipariş yok, rollback tutarlı, kontrol anında kapalı oturum reddedilir. Öneri sözleşme ve açık noktalar: `docs/order-service/INTEGRATION_CONTRACTS.md` (QR) ve `docs/order-service/DECISIONS.md` §5–6. `OrderPlaced` olayı outbox'a yazılır ancak dispatcher/tüketici yoktur (D-10 açık).

**Order Service durum yönetimi (ORDER-03, UYGULANDI):** `fulfillmentStatus` tek çizgidir: `RECEIVED → PREPARING → READY → SERVED → COMPLETED`; adım atlanmaz, geri dönülmez, `COMPLETED/CANCELLED` sonrası geçiş yoktur. `CANCELLED` yalnız ORDER-04 iptal use-case'iyle gelir (commit 2'de uygulandı; bkz. aşağıdaki **sipariş iptali** paragrafı), ham durum güncellemesiyle gelmez. **Yetki (K-05):** `PREPARING/READY` için ana otorite Kitchen servisidir (`POST /api/v1/internal/order-events/kitchen`); KITCHEN personelinin `PATCH` ile bu iki durumu vermesi yalnız `dev`/`test` profillerinde açık, kontrollü bir mock mekanizmasıdır, diğer profillerde 403 döner (kod değil profil belirler, çalışma anında açılamaz). `SERVED` ve `COMPLETED` için WAITER veya ADMIN yetkilidir; ADMIN işlemi aktörüyle geçmişe yazılır. Müşteri hiçbir durumu değiştiremez. Her iki yol da aynı sipariş satırı kilidinde serileşir, aynı durum makinesinden geçer. **`COMPLETED` ödeme gerektirmez (K-03):** operasyonel tamamlanma ile finansal tamamlanma ayrı yaşam döngüleridir; tamamlanmış ama ödenmemiş siparişler `view=completed` ile `paymentStatus` filtresiyle ve yanıttaki iki ayrı durum alanıyla ayırt edilir. **Tekrar (K-06):** sipariş zaten istenen durumdaysa ve çağıran o durumu vermeye yetkiliyse, kim olursa olsun no-op döner (`changed:false`, yeni history satırı ve outbox olayı yok); farklı personel olması 409 nedeni değildir. Daha eski bir durum ise uygulanmaz (409), yani durum sessizce geri gitmez. **Okuma (K-04):** KITCHEN kendi restoranının sipariş listesini ve detayını indirgenmiş görünümle okur (kalemler, miktarlar, ekstralar, çıkarılan malzemeler, notlar, hazırlık durumu); ödeme durumu, tutarlar ve fiyatlar yanıtta yer almaz, `paymentStatus` filtresi ve geçmiş (ödeme ekseni ve personel kimlikleri içerir) KITCHEN için 403'tür. Kitchen servisi ileride kendi okuma modelini `OrderPlaced` olayından kurabilir; bu iş mantığı Order'a taşınmaz. Her değişiklik tek transaction'da sipariş satırını kilitler (`SELECT … FOR UPDATE`), durumu günceller, `order_status_history` satırı ve `OrderFulfillmentStatusChanged` outbox olayını yazar; veritabanı aynı kuralları tetikleyiciyle tekrar uygular (V47). Kitchen olayları `inbox_events` tablosunda olay kimliğiyle tekilleştirilir: tekrar gelen olay özgün sonucu döner, geç/tekrarlanan durum `IGNORED_STALE`, iptal edilmiş sipariş için olay `IGNORED_ORDER_CLOSED`, adım atlayan olay `409 INVALID_STATUS_TRANSITION` ile reddedilir ve hatırlanmaz (gönderen eksik adımdan sonra yeniden dener).

**Order Service ödeme durumu (ORDER-03, UYGULANDI):** `paymentStatus`, `fulfillmentStatus`'tan bağımsız ayrı bir durum makinesidir: `NOT_REQUESTED → PENDING → PAID | FAILED | CANCELLED`, `FAILED → PENDING` (yeni deneme); `PAID` ve `CANCELLED` sonludur. `FAILED → CANCELLED` yalnız **sipariş `CANCELLED` iken** geçerlidir (canlı siparişte `FAILED` kalır çünkü misafir yeniden deneyebilir). Durumu **yalnızca** Payment servisi `POST /api/v1/internal/order-events/payment` ile değiştirir (`Authorization: Service …`); müşteri, personel veya istemci başlığıyla değişmez ve istemcideki başarı sayfası kanıt sayılmaz. Olay alanları ve anlamları §5.4'teki **Payment olay sözleşmesi**'ndedir. **Tutarlar (K-08):** `initial_*_minor` hiçbir zaman değişmez. `current_due_minor` **kalan borçtur**; `PENDING` ve `FAILED` iken değişmez, doğrulanmış `PAID` ile **0** olur. `PENDING` ve `PAID` olayları siparişin o anki borç tutarını ve para birimini taşımak zorundadır (uyuşmazlık `409 PAYMENT_MISMATCH`, hatırlanmaz). Kavramlar ayrıdır: özgün toplam, iptal edilen tutar (ORDER-04), net ödenecek tutar, doğrulanmış tahsilat ve kalan borç; kısmi/bölünmüş ödeme **desteklenmez**, kısmi iptal sonrası borç hesabı aşağıdaki sipariş iptali paragrafındadır (ORDER-04). **Kimlik ve tekilleştirme (K-14/K-15, V49):** `eventId` yalnız **mesajı** idempotent yapar ve yalnız inbox saklama penceresi (90 gün) boyunca hatırlanır. Parayı `captureId`, bir denemeyi `paymentAttemptId` tanımlar; bu üç kavram karıştırılmaz. Her tahsilat `payment_captures` tablosunda kaydedilir ve **veritabanı** şunları garanti eder: bir `captureId` yalnız bir kez vardır (`UNIQUE`), bir sipariş en çok **bir** tahsilatla `APPLIED` olur (kısmi `UNIQUE`), kayıt hiçbir zaman yeniden yazılmaz/silinmez ve `APPLIED` kayıt ancak `PAID` siparişte bulunabilir (commit anında kontrol). Aynı tahsilat başka `eventId` ile veya inbox penceresi dolduktan sonra yeniden gelse bile **ikinci bir finansal etki oluşmaz**; eşzamanlı yarışta tek işlem kazanır. Tahsilat durumları: `APPLIED`; `PENDING_VERIFICATION` (bkz. aşağıda); `NOT_APPLIED` (gerekçe: `SECOND_CAPTURE`, `ORDER_CANCELLED`, `PAYMENT_CANCELLED`, `AMOUNT_MISMATCH`, `ATTEMPT_ENDED`). Uygulanamayan her tahsilat için `payment_reconciliation_cases` (append-only, `(order, capture, reason)` tekil) satırı ve `OrderPaymentReconciliationRequired` outbox olayı yazılır. Aynı siparişe **farklı** `captureId` ile gelen ikinci `PAID` olası çift tahsilattır (`SECOND_CAPTURE`) ve sıradan 'stale' sayılmaz; aynı `captureId`'nin başka siparişte kullanılması `CAPTURE_USED_ON_OTHER_ORDER` vakasıdır. Tahsilatın sipariş, restoran, tutar ve para birimi ilişkisi doğrulanır. `PAID`, siparişin gördüğü (`PENDING` ile başlamış) bir `paymentAttemptId`'yi adlandırmalıdır; eski bir denemenin `FAILED/CANCELLED`'ı yeni denemeyi bitiremez. **Sırası bozuk PAID (K-15):** güvenilir Payment servisinin `PAID`'i, `PENDING`'inden önce gelirse (deneme kimliği siparişçe bilinmiyorsa) tahmin edilmez ve düşürülmez: `PENDING_VERIFICATION` olarak kalıcı bekler (yanıt `outcome=PENDING_VERIFICATION`, sipariş değişmez). İlgili `PENDING` geldiğinde bekleyen tahsilat aynı transaction'da doğrulanır: tutar ve para birimi eşleşirse uygulanır (`PAID`, borç 0); eşleşmezse veya zaten başka tahsilat uyguladıysa `NOT_APPLIED` + vaka olur. Tekrar gönderimler çift etki yaratmaz. **Provizyon geçişi:** V49 öncesi geçici işaretler (history `reason` referansları, uzlaştırma outbox olayları, `PaymentCapture*` inbox işaretleri) `payment_captures`/`payment_reconciliation_cases` içine geriye dönük doldurulur; eski kayıtlar silinmez veya yeniden yazılmaz. **Sıralama ve zaman (K-12):** sıralamayı zaman damgası değil durum makinesi ve deneme kimliği belirler; `occurredAt` (Payment'ın saati, UTC) yalnız `PENDING/FAILED/CANCELLED` için 'son uygulanan olaydan eski' elemesinde kullanılır, **`PAID` zaman damgası yüzünden hiçbir zaman yok sayılmaz**. Eski olaylar yaşları yüzünden reddedilmez; sunucu saatinden `restofy.payment.max-clock-skew` (varsayılan 5 dakika) kadar ileri tarihli olay `422` ile reddedilir. Geçersiz geçiş (ör. `PENDING` olmadan `PAID`) `409 INVALID_STATUS_TRANSITION` olur ve eksik adımdan sonra yeniden denenebilir. **Uygulanamayan PAID (K-07):** sipariş `CANCELLED`, ödeme `CANCELLED`, deneme sona ermiş, tutar uyuşmuyor, sipariş zaten başka tahsilatla `PAID` veya `captureId` başka siparişe ait ise `PAID` kaybolmaz: aynı transaction'da `payment_captures` (`NOT_APPLIED`), `payment_reconciliation_cases` ve `OrderPaymentReconciliationRequired` outbox olayı (ADMIN, PAYMENT, NOTIFICATION) yazılır, yanıt `200` ve `reconciliationRequired:true` olur; sipariş durumları değişmez. Para iadesi Payment servisinin ve insanların kararıdır, Order Service otomatik iade yapmaz. Diğer olaylar iptal edilmiş sipariş için `IGNORED_ORDER_CLOSED` olur. `PAID`, `OrderPaymentStatusChanged` olayıyla QR'a da bildirilir (masa kapanışı sinyali); Order Service QR'ı veya başka servisin verisini değiştirmez. Bu bölümdeki her şey yalnız mock servis kimliğiyle test edilmiştir; gerçek Payment entegrasyonu yoktur. **captureId sözleşmesi (KARAR 06, hedef sözleşme; Payment Service henüz DOĞRULAMADI):** bkz. §5.4'teki *captureId benzersizlik sözleşmesi*. Order Service bugün `captureId`'yi platform genelinde benzersiz kabul eder ve buna göre korunur; Payment'ın bu garantiyi gerçek entegrasyonda sağladığı iddia edilmez.

**Order Service sipariş iptali (ORDER-04 commit 2, 3 ve 4, UYGULANDI).** `POST /api/v1/orders/{orderId}/cancellations` (`Idempotency-Key` zorunlu; gövde: `cancelAll:true` **veya** `items[{orderItemId, quantity}]`, ikisi birden/hiçbiri 422; isteğe bağlı `reasonCodes[]`, `reasonText`). Çağıran: siparişin sahibi misafir (aktif masa oturumu; kapalı oturum 409) veya aynı restoranın WAITER/ADMIN personeli; KITCHEN ve servis kimlikleri 403, başka restoran/misafir 404, kimliksiz 401. **Talep ile kesinleşme ayrıdır** (`order_cancellations.status`: `REQUESTED` → `APPLIED`/`REJECTED`, karar bir kez verilir). Kurallar (`CancellationPolicy`, saf kural sınıfı): `RECEIVED` → talep aynı transaction'da kesinleşir (`decisionSource=AUTO`); `PREPARING` → `REQUESTED` kaydedilir, `OrderCancellationRequested` yazılır ve **sipariş, miktarlar ve tutarlar değişmez**; yalnız güvenilir Kitchen servisi kesinleştirebilir (Kitchen yanıtı yoksa, ör. production'da entegrasyon yokken, talep açık kalır; doğrulanmamış söze dayanarak hiçbir zaman kesinleşmez); `READY/SERVED/COMPLETED` → 409 `CANCELLATION_NOT_ALLOWED` (**D-02, onaylı karar:** hazırlanmış/servis edilmiş sipariş için iptal iş akışı bilerek kapalıdır; bkz. aşağıdaki D-02 notu); `CANCELLED` → 409 `ORDER_ALREADY_CANCELLED`; ödenmiş siparişi misafir iptal edemez (409 `ORDER_ALREADY_PAID`), WAITER/ADMIN edebilir. Siparişte aynı anda tek açık talep olabilir (409 `CANCELLATION_ALREADY_PENDING`; veritabanı kısmi `UNIQUE` ile de garanti). Hesap (`CancellationCalculator`, BIGINT minör birim): miktar aktif miktarı aşamaz (422 `CANCEL_QUANTITY_EXCEEDED`), başka siparişin satırı 422 `UNKNOWN_ORDER_ITEM`; iptal brütü `birim fiyat × miktar`, iptal edilen net ise kalan birimlerin checkout yuvarlamasıyla öncesi−sonrası farkıdır; böylece adım adım iptaller toplamda satırın özgün net ve KDV'sini kuruşu kuruşuna verir. Fiyat anlık görüntüsü değişmez; yalnız `order_items.cancelled_quantity` ve `orders.current_due_minor` hareket eder (`cancelled_quantity`, uygulanmış iptal satırlarının toplamına eşit olmak zorundadır; veritabanı commit anında zorlar). Kalan tüm birimler iptal edilince (kısmi taleplerin sonuncusu dahil) kapsam `FULL` olur, sipariş `CANCELLED` olur ve ödeme ekseni `NOT_REQUESTED/PENDING/FAILED → CANCELLED` kapanır (history `source=CANCELLATION`); `PAID` aynen kalır. **Para ve iade ayrımı:** ödenmemiş siparişte yeni borç `önceki borç − iptal brütü` olur; `PAID` siparişte borç 0 kalır, doğrulanmış tahsilat `collected_minor`'dur ve iptal sonrası siparişin değeri tahsilattan azsa fark `refund_required_minor` (**gerekli iade**, önceki iptallerde zaten gerekli görülen düşülür) olarak kaydedilir. `refunded_minor` (**gerçekleşen iade**) yalnız Payment servisi doğrulayabilir; Order Service onu hiçbir zaman yazmaz (0 kalır) ve otomatik iade yapmaz. Tam iptalde `PENDING_VERIFICATION` bekleyen tahsilat `NOT_APPLIED(ORDER_CANCELLED)` olur ve uzlaştırma vakası açılır; sonradan gelen `PAID` aynı kuralla vaka olur; kısmi iptalden sonra eski tutarla gelen `PAID` `AMOUNT_MISMATCH` vakasıdır. **D-02 kararı (onaylandı, uygulandı):** `READY`, `SERVED` ve `COMPLETED` durumundaki sipariş için normal iptal iş akışı **kapalıdır**: müşteri, WAITER ve ADMIN dahil hiçbir rol, ödeme durumu ne olursa olsun iptal başlatamaz (409 `CANCELLATION_NOT_ALLOWED`, `details.reason=ORDER_ALREADY_PREPARED`). WAITER/ADMIN yetkisi bu kuralı atlatmaz; iade ihtiyacı sipariş iptaliyle karıştırılmaz (reddedilen talep hiçbir iptal kaydı, olay veya iade ihtiyacı üretmez), sipariş ve geçmişi değişmez. Kural tek yerde, `CancellationPolicy.CLOSED_FOR_CANCELLATION` kümesinde tutulur. Hazırlanmış veya servis edilmiş siparişler için yönetici müdahalesi, iade veya operasyonel düzeltme akışı **ayrı bir iştir ve tasarlanmadı/uygulanmadı**. **Neden (görev 2):** hiç neden, çoklu hazır kod (`CHANGED_MIND`, `ORDERED_BY_MISTAKE`, `WAIT_TOO_LONG`, `ITEM_UNAVAILABLE`, `KITCHEN_ERROR`, `OTHER`; her biri en çok bir kez; kod tam eşleşmelidir) ve/veya en çok 300 karakterlik düz metin kabul edilir; bilinmeyen kod, tekrar, uzun metin ve kontrol karakteri 422'dir; metin hiçbir zaman yorumlanmaz. Her neden satırı kaynağını (`GUEST`/`STAFF`/`SERVICE`), ekleyeni ve zamanı taşır ve append-only'dir; kod kataloğu kodda ve `CHECK` kısıtında ortaktır (bir test eşitliği doğrular). **Olaylar (K-13, görev 4):** hepsi iptalle aynı transaction'da outbox'a yazılır (rollback testli): `OrderCancellationRequested` (yalnız mutfak onayı gerekirken; Kitchen, Waiter, Admin, Notification), `OrderCancelled` (tam iptal; Kitchen, Payment, Waiter, Admin, Notification, QR), `OrderItemsCancelled` (kısmi; Kitchen, Waiter, Admin, Notification), `OrderAmountAdjusted` (borç değişti veya iade gerekli; Payment, Admin). `OrderCancellationRejected` Kitchen reddinde ve sistem reddinde yazılır (Waiter, Admin, Notification; sistem reddinde Kitchen de). Olaylar misafir/personel kimliği veya jeton taşımaz (yalnız tür ve rol). Dispatcher olmadığından hiçbiri teslim edilmiş sayılmaz. **Idempotency ve eşzamanlılık:** anahtar çağıran + `CANCEL_ORDER` işlemi kapsamlıdır (72 saat); aynı anahtar ve aynı istek (neden dahil) aynı iptali döner (`Idempotent-Replayed: true`), aynı anahtar farklı istek 409 `IDEMPOTENCY_CONFLICT`; reddedilen istek anahtarı tüketmez. Sipariş satırı kilidi (`SELECT … FOR UPDATE`) iptal, ödeme ve mutfak adımlarını serileştirir. **Okuma:** sipariş satırları `cancelledQuantity`/`remainingQuantity` taşır (`quantity` özgün miktardır); detay `cancelledGrossMinor` ve `activeGrossMinor` ekler; liste `unitCount` aktif birimleri sayar; KITCHEN tutar görmez. Gerçek Auth/QR/Kitchen/Payment ile uçtan uca doğrulanmamıştır (yalnız mock). **Kitchen kararı (commit 3):** `POST /api/v1/internal/order-events/kitchen-cancellation` (`Authorization: Service`, yalnız KITCHEN; gövde: `eventId`, `restaurantId`, `orderId`, `cancellationId`, `decision` `APPROVED`/`REJECTED`, ret için `rejectionCode`). Olay `inbox_events`'te (`KITCHEN`, `KitchenCancellationDecision`) `eventId` ile tekilleştirilir: tekrar gelen olay özgün sonucu döner (`duplicate:true`), zaten karara bağlanmış talep `IGNORED_STALE` olur ve karar değişmez, başka siparişte kullanılan `eventId` 409, restoran/sipariş/iptal eşleşmeyen çağrı 404 olur ve hatırlanmaz (Kitchen yeniden dener). **Onay güvenilmez kabul edilmez:** sipariş satırı kilidi altında kurallar siparişin ŞU ANKİ durumuna göre yeniden değerlendirilir; sipariş arada ödendiyse (müşteri talebi), `READY` veya ötesine geçtiyse ya da talep edilenle aynı olmaktan çıktıysa Order Service talebi kendisi reddeder (`decision_source=SYSTEM`, `rejection_code` `ORDER_ALREADY_PAID`/`ORDER_ALREADY_PREPARED`/`NOT_ALLOWED_STATE`/`ORDER_CHANGED`) ve hiçbir şey iptal edilmez. Geçerli onayda tutarlar o anki siparişe göre yeniden hesaplanır, `APPLIED` olur (`decision_source=KITCHEN`, `decided_by=SERVICE/KITCHEN`), sipariş ve geçmiş anında iptal yolundaki aynı kodla (`CancellationApplier`) güncellenir ve `OrderCancelled`/`OrderItemsCancelled` + `OrderAmountAdjusted` yazılır. Kitchen reddi `REJECTED` olur: iptal edilen tutarlar 0, borç değişmez, `OrderCancellationRejected` Kitchen'a gönderilmez (kendi yanıtı); sistem reddi Kitchen'a da gider. Ret sonrası sipariş yeni bir talebe açıktır. Kitchen yanıtı gelmezse talep sonsuza dek `REQUESTED` kalır ve sipariş etkilenmez. **Kitchen kaynaklı iptal talebi ayrı bir sözleşmedir (UYGULANDI)**: bkz. aşağıdaki *ORDER-04 servisler arası iptal sözleşmeleri*; Kitchen'ın kendi talebi (`…/kitchen-cancellation-request`) ile Order talebine cevabı (`…/kitchen-cancellation`) farklı uç noktalardır. **Neden tamamlama (görev 3):** `PATCH /api/v1/orders/{orderId}/cancellations/{cancellationId}/reason` WAITER veya ADMIN (KITCHEN, misafir ve servis kimlikleri 403; ADMIN yetkisi başka role dolaylı yetki vermez), yalnız talepte hiç neden yoksa ve bir kez (müşterinin, garsonun, ADMIN'in veya Kitchen'ın nedeni değiştirilemez ve ona eklenemez); neden append-only'dir, hiçbir zaman değiştirilmez veya eklenmez (409 `CANCELLATION_REASON_ALREADY_SET`); aynı waiter'ın aynı içeriği yeniden göndermesi zararsız tekrardır (200). Müşteri nedeni varken garson nedeni veritabanı tetikleyicisiyle de engellenir. Ekleyen kimliği, rolü (WAITER/ADMIN) ve zaman `order_cancellation_reasons` ve `order_cancellations.staff_reason_added_*` içinde denetim için tutulur. Talep `REQUESTED`/`APPLIED`/`REJECTED` olabilir; neden tamamlamak kararı değiştirmez. **Okuma:** `GET /api/v1/orders/{orderId}/cancellations` sipariş sahibi misafire (aktif oturum) ve WAITER/ADMIN'e açıktır (personel kimlikleri yalnız ADMIN'e), KITCHEN 403. **Raporlama (görev 4, yalnız veri; yönetici arayüzü yoktur):** `GET /api/v1/admin/order-cancellations` yalnız ADMIN içindir (diğer roller, misafir ve servisler 403) ve her zaman çağıranın kendi restoranını döner (restoran doğrulanmış kimlikten gelir, parametre değildir). Filtreler: `from` (dahil) / `to` (hariç) talep zamanına göre (ISO tarih veya UTC an), `reasonCode`, `scope`, `status`; en yeni önce, `page` (0 tabanlı) ve `limit` (1–100, varsayılan 20), kimlik ikinci sıralama anahtarıdır (sayfa sınırında satır atlanmaz/tekrarlanmaz). Her satır: sipariş, kapsam, durum, karar kaynağı, talep eden (personel ise kullanıcı kimliği; misafir kimliği asla), nedenler, birim sayısı ve tutarlar (özgün/iptal edilen net-KDV-brüt, önceki ve yeni borç, tahsilat, **gerekli iade** ve **gerçekleşen iade** ayrı). `summary` yalnız sayfayı değil filtrenin eşleştirdiği her şeyi anlatır: durum sayıları, para birimi başına `APPLIED` iptallerin tutarları, her neden kodunun kaç iptalde geçtiği (Kitchen V1 grubuyla: `CUSTOMER_REQUEST`, `OUT_OF_STOCK`, `KITCHEN_ERROR`, `OTHER`) ve nedeni henüz olmayan iptal sayısı. `REQUESTED` satır tahmini tutar gösterir, `REJECTED` hiçbir şey iptal etmemiştir; toplamlar yalnız `APPLIED` sayar. **Kaynak gerçek kayıtlardır:** rapor `order_cancellations*` tablolarından okur, outbox'tan değil; teslim edilmiş outbox olayları temizlense de rapor değişmez (testli). **Bekleyen talepler:** `REQUESTED` satır `pendingAgeSeconds` ve `overdue` taşır; `overdue=true` ve `sort=requestedAt,asc` en uzun bekleyen talepleri öne çıkarır, `summary.pending` eşiği (`restofy.cancellation.pending-attention-after`, varsayılan 15 dakika, düz özellik), gecikmiş sayısını ve en eski bekleyen talebi verir. Bu **yalnız görünürlüktür**: Kitchen yanıtı gelmedi diye otomatik onay/ret, zamanlayıcı, bildirim veya eskalasyon yoktur; bildirim/eskalasyon sözleşmesi (Notification ve Waiter/Admin ile) gerçek entegrasyon bağımlılığıdır. Order Service'te yönetici arayüzü, dışa aktarma (CSV/PDF) ve Gateway rotası yoktur. **Kitchen kaynaklı talep kaydı ve denetim temsili (D-13, V50'de çözüldü):** işlemi yapan Kitchen **servisidir**, personel değildir ve veritabanında da öyle saklanır: `requested_by_type=SERVICE`, `requested_by_id=KITCHEN` ve `requested_by_role=KITCHEN` (doğrulanmış servis kimliği; veritabanı `SERVICE` yazarını yalnızca Kitchen için kabul eder). Servisin 'adına hareket ettim' dediği mutfak personeli (`requestedByStaffId`) **bir beyandır**: ayrı `order_cancellations.reported_staff_id` kolonunda tutulur (isteğe bağlı, yalnız `SERVICE` talebinde dolu olabilir, talepten sonra değişmez), hiçbir yetki, sahiplik veya 'aynı kişi' kararında kullanılmaz (örn. beyan edilen kişi neden tamamlama 'tekrarı' yapamaz). Nedenleri de aynı servis yazarıyla saklanır (`source=SERVICE`, `added_by_type=SERVICE`, `added_by_id`/`added_by_role=KITCHEN`). API, olay ve raporda talep eden ve neden kaynağı `SERVICE` (rol `KITCHEN`) görünür; ADMIN ayrıca servis kimliğini (`requestedBy.id=KITCHEN`) ve ondan **ayrı** olarak servisin beyan ettiği personeli (`requestedBy.reportedStaffId`) görür, olaylar ve diğer roller ikisini de görmez. Talep ve karar ayrı olgulardır: `requested_at` ve talep eden (Kitchen servisi) ile `decided_at`, `decided_by_type=SYSTEM` (Order Service kuralları) ve `decision_source` (kabulde `KITCHEN` = Kitchen'ın kendi talebine dayanarak, redde `SYSTEM`) ayrı alanlardır; talebin alındığı ayrıca `inbox_events` ve idempotency kaydında durur; `kitchen_confirmation_required=false`. Önceki geçici `STAFF` + `KITCHEN_SERVICE` + `service:KITCHEN;reportedStaff=…` eşlemesi kaldırılmıştır; düzeltme yeni migration numarası kullanılmadan, V50 hiçbir uzak dala gönderilmeden ve hiçbir kalıcı veritabanına uygulanmadan V50'nin kendisinde yapılmıştır.

### 5.2 Menü değişimi ve checkout yarışı

Menu'den fiyat/availability alınması başka servisteki commit ile atomik değildir. Öneri: Menü yetkili sürümlü/süreli teklif üretir, Order bu teklif anını fiyat geçerliliği sınırı kabul eder. “Kapatma anından sonra hiçbir kabul olamaz” şartı istenirse Menu tarafında ayrıca reserve/confirm/fencing protokolü gerekir. Kaynakta bu dağıtık yarışın kesin zaman sınırı verilmediği için garanti uydurulmaz.

Kitchen stokta yok durumunda mevcut kesinleşmiş siparişi geriye dönük değiştirmez; iptal talebi Order'a gider.

### 5.3 Order Service — ORDER-03 sonrası teknik kararlar (K-01 … K-13)

Aşağıdaki kararlar kullanıcı tarafından onaylanmıştır. Durum: **U** = uygulandı ve yerel testlerle doğrulandı, **B** = yalnız belgelendi / tasarlandı, **E** = gerçek servis entegrasyonuna bağlı.

| Karar | İçerik | Durum |
|---|---|---|
| K-01 | Fiyat onayı: `priceFingerprint` + açık `acknowledgePriceChanges`; eski akış sıkılaştırıldı (aşağıda) | U |
| K-02 | Servis kimliği: TLS üzerinden kısa ömürlü, imzalı servis token'ı | B + E (Auth/Kerem) |
| K-03 | `COMPLETED` için `PAID` aranmaz; iki yaşam döngüsü bağımsız | U |
| K-04 | KITCHEN kendi restoranının sipariş liste/detayını indirgenmiş görünümle okur | U |
| K-05 | `SERVED`/`COMPLETED`: WAITER+ADMIN; `PREPARING`/`READY`: Kitchen servisi (personel yolu yalnız dev/test) | U, E (Kitchen/Nisa) |
| K-06 | Aynı durumun tekrarı yetkili herkes için no-op | U |
| K-07 | Uygulanamayan `PAID` kaybolmaz, uzlaştırma için kaydedilir (ikinci tahsilat dahil) | U, E (Payment/Alper) |
| K-08 | `FAILED→CANCELLED` yalnız iptal edilmiş siparişte; `PAID` ile kalan borç 0 | U |
| K-09 | `PaymentIntegrationPort` **oluşturulmadı** (aşağıda) | B |
| K-10 | Migration kapasite analizi (`database.md`) | B |
| K-11 | Inbox 90 gün / teslim edilmiş outbox 30 gün, yapılandırılabilir temizlik; finansal kayıtlar hariç | U (dispatcher yok) |
| K-12 | Payment olay zamanı: yapılandırılabilir ileri tolerans (5 dk) | U |
| K-13 | Beş iptal olayı sözleşmesi (aşağıda) | B (ORDER-04) |

**K-01 — Fiyat onayı ve `priceFingerprint` (uygulandı).** Sepet görünümü (`GET /api/v1/carts/current`) `priceFingerprint` döndürür: sepet kimliği, sepet sürümü, para birimi ve her satırın miktarı, canlı birim/satır fiyatı, KDV oranı, katalog fiyat sürümü ve her ekstranın canlı fiyatından sunucuda hesaplanan SHA-256 özeti. **Kural: müşterinin açıkça kabul etmediği güncel fiyat üzerinden sipariş oluşturulamaz.** Bir fiyat bileşeni (ürün, ekstra, KDV, katalog fiyat sürümü) sepetin son doğruladığı değerden farklıysa sipariş yalnızca istek hem o anki canlı fiyatların `expectedPriceFingerprint` değerini hem de `acknowledgePriceChanges: true` taşıyorsa oluşur; fiyat değişmemişse bu alanlar gerekmez (eski istemciler çalışmaya devam eder). Aksi her durumda (parmak izi yok/yanlış/eski, onay yok, toplam uyuşmuyor) `409 PRICE_CHANGED` döner; gövde **ürün/ekstra bazında** `details.priceChanges` (`PRODUCT_PRICE`, `EXTRA_PRICE`, `TAX_RATE`, `PRICE_VERSION`; önceki/yeni değer), `acknowledgementRequired`, `currentTotalMinor`, `currentCartVersion` ve `currentPriceFingerprint` içerir ve **sepete hiçbir şey yazılmaz** (sürüm ve saklı fiyatlar aynı kalır, idempotency anahtarı yanmaz; aynı anahtarla sonradan yapılan onaylı istek geçerlidir). Eskiden fiyat değişikliğinde saklı fiyatların yenilenmesiyle ikinci denemede başarılı olan akış **kaldırıldı**: parmak izi göndermeyen eski istemci ve onu otomatik tekrar eden her istek değişmiş fiyatta reddedilir. İstemcinin değeri hiçbir zaman fiyat kaynağı değildir; sunucu özeti canlı katalogdan yeniden hesaplar. Parmak izi sepet sürümünü ve tüm canlı bileşenleri içerdiğinden eski, başka sepete ait veya fiyat yeniden değişmiş bir onay yeniden kullanılamaz; kimlik doğrulama, aktif oturum, sepet sürümü, toplam kontrolü ve checkout transaction'ının yerine geçmez. İdempotency özeti parmak izini ve onayı içerir (aynı anahtar, farklı onay = `IDEMPOTENCY_CONFLICT`). Bilinen sınır: fiyat kontrolü ile commit arasında katalog değişirse sipariş, istemcinin onayladığı ve sunucunun doğruladığı fiyatla oluşur (commit transaction'ı içinde uzak katalog çağrısı yapılmaz). Not: misafir sepetteki bir satırı kendisi değiştirirse (ör. adet) o satırın saklı fiyatı yenilenir ve o yanıt yeni fiyatı gösterir.

**K-02 — Servis kimliği hedef sözleşmesi (yalnız belge; gerçek issuer/doğrulayıcı yok).** Servisten servise çağrılar `Authorization: Service <token>` taşır; token TLS üzerinden iletilen kısa ömürlü, imzalı bir servis token'ıdır. Gerçek adaptör (Kerem'in Auth entegrasyonuyla kesinleşecek): imzayı döndürülebilir anahtarlarla doğrular; `iss` (beklenen issuer), `aud` (Order Service), `exp/nbf` (küçük saat toleransıyla) ve servis kimliğini (`sub`) denetler; kaynak servisi izinli işlemle eşler (uç noktalar zaten servis başına yetkilendirilir: Kitchen yalnız `/kitchen`, Payment yalnız `/payment`); token tekrar kullanımını (ör. `jti`) değerlendirir; anahtar saklama ve rotasyonu Auth tarafındadır. mTLS ileride ek katman olabilir, şimdilik zorunlu değildir. `ServiceIdentityPort` arayüzü değişmez. Gerçek doğrulama yokken `dev/test` dışında fail-closed (503) çalışır; production güvenliğinin sağlandığı iddia edilmez.

**K-09 — Payment sınırı.** Order, ödeme durumu değişince Payment'ı senkron çağırmaz; Payment durumu bildirir (`POST …/payment`, §5.4). Kullanılmayan genel bir Payment istemcisi veya `PaymentIntegrationPort` **bilerek oluşturulmadı**. Order'ın Payment'a gelecekte ihtiyaç duyacağı işlemler (kesin tutarı sunma, iptal nedeniyle tutar değişikliğini bildirme, kısmi iptal sonrası mutabakat, iade koordinasyonu) iletişim teknolojisinden bağımsız **olay sözleşmeleriyle** (K-13: `OrderAmountAdjusted`, `OrderCancelled`, `OrderPaymentReconciliationRequired`) karşılanır; D-10 kesinleşince senkron bir port gerekip gerekmediği ORDER-04 sırasında yeniden değerlendirilir.

**K-11 — Inbox/outbox saklama (uygulandı, `restofy.messaging.*`).** `inbox-retention=P90D` (aynı zamanda mesaj tekilleştirme penceresi; en az 7 gün olmalıdır), `delivered-outbox-retention=P30D`. İşlenmiş inbox satırları süre dolunca silinir; bunlar yalnız mesaj kayıt defteridir. **Finansal kayıtlar temizlikten etkilenmez:** `payment_captures` ve `payment_reconciliation_cases` hiçbir temizliğin kapsamında değildir (V49 tetikleyicileri silmeyi de engeller) ve `OrderPaymentReconciliationRequired` outbox olayları teslim durumuna ve yaşa bakılmaksızın silinmez; süreli `eventId` tekilleştirmesi finansal tekilliğin yerine geçmez. V49 öncesi yazılmış `PaymentCapture*` inbox işaretleri de silinmez. Diğer outbox olayı yalnız **tüm teslimatları `DELIVERED`** ve son teslimattan itibaren süre dolmuşsa silinir; bekleyen/başarısız/teslimat satırı olmayan olaylar yaşına bakılmaksızın silinmez. Dispatcher olmadığından bugün hiçbir olay `DELIVERED` değildir ve hiçbir outbox olayı silinmez; gönderim garantisi verilmiş gibi raporlanmaz. Temizlik `FOR UPDATE SKIP LOCKED` ile sınırlı gruplar hâlinde çalışır; sipariş, kalem, `order_status_history` ve idempotency kayıtlarına dokunmaz. Başarısız teslimat için yeniden deneme dispatcher'ın (D-10) işidir.

**K-13 — İptal olay sözleşmeleri (ORDER-04 commit 2 ve 3'te UYGULANDI).** Tüm olaylar `OrderPlaced` gibi sürümlü (`schemaVersion=1`), outbox'tan teslim edilen ve alıcı tarafında `eventId` ile tekilleştirilen olaylardır. Ortak alanlar: `eventId`, `eventType`, `schemaVersion`, `occurredAt`, `correlationId`, `restaurantId`, `orderId`, `orderNo`, `tableId`, `tableSessionId`, `fulfillmentStatus`, `paymentStatus`; sıralama için outbox `aggregateVersion` (siparişin sürümü, artan) kullanılır, tüketici daha eski sürümü yok sayar. Yeni alanlar geriye uyumlu eklenir; bilinmeyen alanlar yok sayılır. İptal talebinin alınması ile iptalin kesinleşmesi **ayrı olaylardır**.

| Olay | Anlamı | Ek alanlar |
|---|---|---|
| `OrderCancellationRequested` | Talep alındı (mutfak onayı gerekiyor); **hiçbir şey iptal edilmedi** | `cancellationId`, `scope` (`FULL`/`PARTIAL`), `kitchenConfirmationRequired`, `items[]` (`orderItemId`, `productId`, `name`, `quantity`, `remainingQuantity` = onaylanırsa kalacak, `amountMinor`), `reasons[]` (`source`, `code`, `text`), `requestedBy` (`type`, `role`), `previousDueMinor`, `projectedDueMinor`, `currency` |
| `OrderCancelled` | Tam iptal **kesinleşti** | `cancellationId`, `items[]`, `reasons[]`, `requestedBy`, `decidedBy`, `decidedAt`, `previousFulfillmentStatus`, `previousDueMinor`, `newDueMinor`, `cancelledAmountMinor`, `currency`, `refundRequiredMinor`, `refundReviewRequired` |
| `OrderItemsCancelled` | Kısmi iptal kesinleşti; sipariş sürer | `cancellationId`, `items[]` (`orderItemId`, `productId`, `name`, `quantity` = iptal edilen, `remainingQuantity`, `amountMinor`), `reasons[]`, `requestedBy`, `decidedBy`, `decidedAt`, `previousDueMinor`, `newDueMinor`, `cancelledAmountMinor`, `currency`, `refundRequiredMinor` |
| `OrderCancellationRejected` | Talep reddedildi; tutarlar ve durum değişmedi | `cancellationId`, `scope`, `items[]`, `requestedBy`, `rejectedBy`, `rejectionCode`, `rejectionNote`, `decidedAt` |
| `OrderAmountAdjusted` | Ödenecek tutar değişti veya iade gerekli (iptalle aynı transaction'da) | `cancellationId`, `adjustmentReason` (`FULL_CANCELLATION`/`PARTIAL_CANCELLATION`), `previousDueMinor`, `newDueMinor`, `originalGrossMinor` (değişmez), `cancelledTotalMinor`, `currency`, `paymentStatusAtAdjustment`, `refundRequiredMinor` |

Hedef servisler: Kitchen (`Requested` karar için, `Cancelled`/`ItemsCancelled` hazırlığı durdurmak/azaltmak için, `Rejected` bilgi), Payment (`OrderAmountAdjusted`, `OrderCancelled`: bekleyen ödemenin tutarını/iptalini ve iade incelemesini yürütür), Waiter (hepsi; sipariş ve masa görünümü), Admin (hepsi; rapor/denetim), Notification (`Requested`, `Cancelled`, `ItemsCancelled`, `Rejected`), QR (`OrderCancelled`: masa kapanış uygunluğu). Müşteri nedeni çoklu hazır neden + serbest metin + boş olabilir; garson nedeni sonradan ekleyebilir (talep kabulünü engellemez). Kısmi iptalde Kitchen V1 sipariş bazlı olduğundan hazırlığı başlamış ürünün iptali Kitchen onayı olmadan kesinleşmez (D-02 açık). Gerçek iade Payment'ın sorumluluğundadır.

**ORDER-04 servisler arası iptal sözleşmeleri (özet; Nisa/Kitchen ve Alper/Payment ile paylaşılacak).** Aşağıdakiler Order Service'in bugün **uyguladığı** davranıştır; karşı servislerin bunu tükettiği/ürettiği DOĞRULANMAMIŞTIR (yalnız mock servis kimliğiyle test edildi).

*Kitchen → Order, iki ayrı çağrı (`Authorization: Service`, yalnız KITCHEN servis kimliği, `ServiceIdentityPort`; üretimde doğrulayıcı yoksa fail-closed 503):*
1. **Kitchen'ın kendi iptal talebi:** `POST /api/v1/internal/order-events/kitchen-cancellation-request`. Gövde: `eventId` (Kitchen'ın olay kimliği, tekilleştirir), `restaurantId`, `orderId`, `cancelAll:true` **veya** `items[{orderItemId, quantity}]` (Kitchen V1 sipariş bazlı hazırladığı için Order satır bazında hazırlık bilgisi varsaymaz; miktar aktif miktarı aşamaz), **zorunlu** `reasonCodes[]` (en az bir kod; `OTHER` için `reasonText` de zorunlu; kodlar: `CHANGED_MIND`, `ORDERED_BY_MISTAKE`, `WAIT_TOO_LONG`, `ITEM_UNAVAILABLE`, `KITCHEN_ERROR`, `OTHER`), isteğe bağlı `reasonText` (en çok 300 karakter, düz metin) ve `requestedByStaffId` (yalnız denetim kaydı, yetki kaynağı değildir). Talebin alınması **iptal etmez**: Order Service kurallarını uygular ve ya iptal eder (`outcome=APPLIED`) ya da reddi gerekçesiyle kaydeder (`outcome=REJECTED`, `rejectionCode`: `ORDER_ALREADY_PREPARED` — D-02, `ORDER_ALREADY_PAID` — ödenmiş siparişe Kitchen sözüyle dokunulmaz, WAITER/ADMIN karar verir). **Kitchen politikası (kesin):** `RECEIVED` ve `PREPARING` + ödenmemiş (`NOT_REQUESTED`/`PENDING`/`FAILED`) sipariş: Order Service kurallarını kendisi doğrular ve aynı çağrıda iptal eder (WAITER/ADMIN onayı gerekmez); `PREPARING`'de bu, Kitchen'ın kendi talebinin zaten mutfak değerlendirmesi olması nedeniyle ayrıca Kitchen onayı beklemez; ödenmiş (`PAID`) sipariş her iki durumda da reddedilir (`ORDER_ALREADY_PAID`, otomatik iptal/iade yok, WAITER/ADMIN karar verir); `READY`/`SERVED`/`COMPLETED` reddedilir (`ORDER_ALREADY_PREPARED`, D-02); iptal edilmiş sipariş 409. Kitchen, Order Service kurallarını atlayamaz: restoran, sipariş, miktar, ödeme ve fulfillment kontrolleri her zaman Order Service'tedir. Hata yanıtları kaydedilmez, Kitchen düzeltip yeniden gönderir: `404` (sipariş yok / başka restoran), `409 CANCELLATION_ALREADY_PENDING` (başka bir talep Kitchen cevabını bekliyor; onu `…/kitchen-cancellation` ile cevaplayın), `409 ORDER_ALREADY_CANCELLED`, `409 IDEMPOTENCY_CONFLICT` (aynı `eventId`, farklı gövde/sipariş), `422` (neden yok, seçim geçersiz, miktar aşımı). Aynı `eventId` yeniden gönderimi `duplicate=true` döner ve ikinci etki yaratmaz (72 saatlik idempotency kaydı + 90 günlük inbox). Sonuç olayları: iptalde `OrderCancelled`/`OrderItemsCancelled` + `OrderAmountAdjusted` (Kitchen'a kendi talebi geri gönderilmez), redde `OrderCancellationRejected` (Waiter, Admin, Notification).
2. **Kitchen'ın Order talebine cevabı:** `POST /api/v1/internal/order-events/kitchen-cancellation` (müşteri/personel kaynaklı, `OrderCancellationRequested` ile bildirilen talebe `APPROVED`/`REJECTED`). Bu çağrı Kitchen'ın kendi talebinden **ayrıdır**; ikisi aynı uç noktada birleştirilmez. Onay, Order Service'in o anki durum doğrulamasından geçmezse talep Order Service tarafından reddedilir.

*Order → diğerleri (outbox olayları, `schemaVersion=1`):* `OrderCancellationRequested` (Kitchen, Waiter, Admin, Notification), `OrderCancelled` (Kitchen*, Payment, Waiter, Admin, Notification, QR), `OrderItemsCancelled` (Kitchen*, Waiter, Admin, Notification), `OrderAmountAdjusted` (Payment, Admin), `OrderCancellationRejected` (Waiter, Admin, Notification; Kitchen reddi Kitchen'dan geldiyse Kitchen hariç). (*Kitchen kendi isteği sonucu oluşan iptalin olayını almaz.) Payload'lar misafir/personel kimliği veya jeton taşımaz; tüketici `eventId` ile tekilleştirir ve sıralama için outbox `aggregateVersion` kullanır. **Outbox kaydı teslimat değildir:** dispatcher yoktur (D-10 kesinleşmeden oluşturulmayacak), tüm teslimatlar `PENDING`'dir ve hiçbir olay gönderilmiş/alınmış sayılmaz; Notification, Kitchen, Payment, QR, Waiter ve Admin tüketimi doğrulanmamıştır.

*İade (Payment ile, GELECEK SÖZLEŞME — UYGULANMADI):* Order Service iadenin sahibi değildir. Bugün yalnızca **gerekli iade** kaydedilir (`order_cancellations.refund_required_minor`, `OrderAmountAdjusted.refundRequiredMinor`, `OrderCancelled.refundReviewRequired`); **gerçekleşen iade** (`refunded_minor`) için Order'da bir giriş yolu yoktur ve gerçek Payment doğrulaması olmadan hiçbir kod onu artırmaz (hep 0). Payment'ın gelecekte göndereceği iade bildirimi için hedef sözleşme: ayrı bir internal olay (örn. `refund` bildirimi) — `eventId`, `restaurantId`, `orderId`, `cancellationId`, platform genelinde benzersiz `refundId`, ilgili `captureId`, `amountMinor`/`currency`, `status` (`PENDING`/`COMPLETED`/`FAILED`) ve `occurredAt`. Kurallar: yalnız var olan ve iade gerektiren iptal için kabul; aynı `refundId` ikinci etki yaratmaz; sırası bozuk/gecikmiş olaylar durum makinesine göre ele alınır; çelişkili olay (gerekli iadeyi, tahsilatı veya para birimini aşan tutar, bilinmeyen iptal/tahsilat) uzlaştırma vakası olur ve `refunded_minor` artmaz; `refunded_minor` toplamı `refund_required_minor`'u aşamaz (veritabanı kısıtı vardır). Bu, `refundId` tekilliği için yeni bir tablo/kısıt gerektireceğinden **yeni migration ve D-13 onayı** ister; şu an oluşturulmadı. İade endpoint'i veya Payment adapter'ı yoktur.

**ORDER-04 iş kuralları (önceden kararlaştırılmış; talep, anında iptal, kısmi iptal ve para kuralları commit 2'de UYGULANDI, Kitchen kararı/neden tamamlama/rapor sonraki commit'lerde):** tam iptal; `RECEIVED` koşulları doğrulanır, `PREPARING` mutfak sürecini ve onayını dikkate alır, `READY/SERVED/COMPLETED` kontrolsüz müşteri iptaline açık değildir; iptal talebi ile onayı ayrıdır; mevcut ödeme durumu karar girdisidir. Kısmi iptal: miktar aktif miktarı aşamaz, aynı kalem için mükerrer iptal oluşmaz, aynı idempotency anahtarı ikinci kez uygulanmaz, fiyat snapshot'ları değişmez, iptal edilen ürün/miktar/tutar ayrı kaydedilir. Finansal senaryolar (bekleyen/tamamlanmış/başarısız ödeme, gecikmiş Payment olayları, tekrar gönderilen talep, ödeme ile iptalin eşzamanlılığı, ödenmiş siparişte iptal ve iade koordinasyonu) ORDER-04'te ele alınır.

**Doğrulama durumu (D-01 … D-05).** D-01: gerçek Auth, QR, Menu, Kitchen, Payment ile uçtan uca entegrasyon **doğrulanmadı** (yalnız mock). D-02: Gateway üzerinden çağrılar doğrulanmadı; `/api/v1/internal/**` Gateway'den yönlendirilmemesi ağ izolasyonunu **kanıtlamaz**, production dağıtımında ağ erişim kontrolleri ve servis kimlik doğrulaması ayrıca doğrulanmalıdır. D-03: çoklu Order Service örneği eşzamanlılığı doğrulanmadı (kilit PostgreSQL'dedir, testler tek JVM içi iş parçacıklarıdır). D-04: `pre-push` betiği ve SonarQube çalıştırılmadı. D-05: her yeni `@MockitoBean` kümesi yeni bir Spring bağlamı ve bağlantı havuzu açar (test PostgreSQL'i 'too many clients' verebilir); paylaşılan test altyapısına dokunulmadı, sorun çıkarsa ayrıca ele alınmalıdır.

### 5.4 Order Service — API ve entegrasyon sözleşmelerinin yeri

**Kaynak.** HTTP sözleşmesinin (alanlar, durum kodları, güvenlik şemaları, hata kodları) tek yetkili kaynağı koddan üretilen OpenAPI belgesidir: `GET /v3/api-docs` (`SWAGGER_ENABLED=true` iken; varsayılan kapalı) ve `OpenApiDocumentationTests` bu belgenin yollarını ve güvenlik şemalarını doğrular. Hata kodları `ErrorCode` enum'undadır. Olay kayıtları `domain/event` altındaki sürümlü `record`'lardır. Bu bölüm yalnız **dizin ve kararlar**dır; alan ayrıntısını OpenAPI'nin yerine tekrar yazmaz (çelişki riski). `docs/order-service/` bilerek silinmiştir ve yeniden oluşturulmamıştır.

| Uç nokta | Kimlik | Not |
|---|---|---|
| `GET/POST/PATCH/DELETE /api/v1/carts/current…` | misafir `Bearer` | sepet; görünümde `priceFingerprint` |
| `POST /api/v1/orders` | misafir `Bearer` + `Idempotency-Key` | checkout; fiyat onayı K-01 |
| `GET /api/v1/orders`, `/{id}`, `/{id}/history` | misafir (yalnız kendi), personel `Staff` | KITCHEN indirgenmiş görünüm, geçmiş yok |
| `PATCH /api/v1/orders/{id}/fulfillment-status` | personel `Staff` | rol matrisi K-05 |
| `POST /api/v1/internal/order-events/kitchen` | servis `Service` (Kitchen) | Gateway'den yönlendirilmez |
| `POST /api/v1/internal/order-events/payment` | servis `Service` (Payment) | Gateway'den yönlendirilmez |

**Giden olaylar (outbox'a yazılır, dispatcher yok):** `OrderPlaced`, `OrderFulfillmentStatusChanged`, `OrderPaymentStatusChanged`, `OrderPaymentReconciliationRequired`; planlanan: K-13'teki beş iptal olayı.

**Payment olay sözleşmesi (Alper'le paylaşılacak, teknolojiden bağımsız; `POST /api/v1/internal/order-events/payment`).** Gövde, bilinmeyen alanları reddeder.

| Alan | Anlam | Kural |
|---|---|---|
| `eventId` | **mesajın** benzersiz kimliği | zorunlu; yeniden gönderim aynı değeri taşır; yalnız mesajı idempotent yapar |
| `restaurantId`, `orderId` | hedef sipariş | zorunlu; eşleşmezse `404` (başka restoranın siparişi görünmez) |
| `paymentStatus` | `PENDING`, `PAID`, `FAILED`, `CANCELLED` | zorunlu; `NOT_REQUESTED` gönderilmez |
| `paymentAttemptId` | **bir ödeme girişimi** (misafirin bir denemesi) | her olayda zorunlu; `PENDING` başlatır, `FAILED/CANCELLED/PAID` bitirir; yeni deneme yeni kimlik; para hakkında bir şey söylemez |
| `captureId` | **gerçekleşmiş tek bir tahsilat** (para) | yalnız `PAID`'de zorunlu, diğerlerinde yasak; her yeniden gönderimde ve her mesajda aynı değer; hiçbir zaman `paymentAttemptId` ile aynı kavram değildir; Order Service'te küresel olarak benzersizdir (`payment_captures.capture_id` `UNIQUE`); hedef sözleşme aşağıdaki *captureId benzersizlik sözleşmesi*'ndedir (Payment henüz doğrulamadı) |
| `amountMinor`, `currency` | tutar (küçük birim) ve ISO para birimi | `PENDING` ve `PAID`'de zorunlu; siparişin o anki kalan borcuna eşit olmalı (`409 PAYMENT_MISMATCH`) |
| `occurredAt` | Payment'ın kendi saati (UTC) | zorunlu; sunucudan en fazla `restofy.payment.max-clock-skew` ileri; `PAID`'i hiçbir zaman engellemez |

Yanıt (`200`): `outcome` = `APPLIED` \| `IGNORED_STALE` \| `IGNORED_ORDER_CLOSED` \| `PENDING_VERIFICATION` (PAID, PENDING'ini bekliyor), `duplicate`, `paymentStatus`, `fulfillmentStatus`, `reconciliationRequired`. Gönderenin davranışı: `200` kesindir; `409 INVALID_STATUS_TRANSITION` ve `409 PAYMENT_MISMATCH` (`details.reason=UNKNOWN_PAYMENT_ATTEMPT`, yalnız FAILED/CANCELLED için) eksik adımdan sonra **yeniden denenir** (hatırlanmaz); `PENDING`'de `409 PAYMENT_MISMATCH` tutar uyuşmazlığıdır, düzeltme gerekir (gerçekleşmiş tahsilat `PAID`'in tutarı ise reddedilmez, `reconciliationRequired=true` ile kaydedilir); `422` hatalı gövdedir; `reconciliationRequired=true` insan incelemesi/iade kararı gerektirir ve Order bunu otomatik çözmez. Order Service para iadesi yapmaz ve gerçek tahsilatı doğrulayamaz: yalnız Payment'ın kimliği doğrulanmış bildirimine ve yukarıdaki tutarlılık kurallarına güvenir.

**captureId benzersizlik sözleşmesi (Alper'in Payment Service geliştirmesiyle paylaşılacak; HEDEF — Payment tarafından henüz DOĞRULANMADI, gerçek entegrasyonda sağlandığı iddia edilmez).** 1) Payment Service, Restofy içinde **her gerçek tahsilat için platform genelinde benzersiz bir `captureId` (UUID) üretir**; aynı fiziksel tahsilatı anlatan her mesajda (yeniden gönderim, farklı `eventId`, gecikmiş gönderim) **aynı** değeri taşır ve farklı bir tahsilat için aynı değeri **asla** kullanmaz. 2) `captureId`, ödeme sağlayıcısının verdiği işlem referansından (provider transaction/merchant referansı) **ayrıdır**. Sağlayıcı referansları Payment'ta saklanır; Order Service'e bu sürümde taşınmaz. Gerekirse sözleşmenin sonraki sürümünde `provider`, `merchantId` ve `providerTransactionRef` ayrı, isteğe bağlı alanlar olarak eklenebilir; `captureId` benzersizliği bunlara **bağlı değildir**. 3) Birden fazla sağlayıcı veya merchant kullanıldığında da `captureId` platform genelinde benzersiz kalır. Order Service tarafında kısıt `payment_captures.capture_id UNIQUE`'tir ve bu kapsam bu sözleşmeye dayanır; sözleşme değişirse (örn. sağlayıcı kapsamlı kimlik) yeni bir migration ve ek numara aralığı (D-13) gerekir. 4) Aynı tahsilat iki farklı siparişle ilişkilendirilemez: aynı `captureId` başka siparişte görülürse Order Service ikinci finansal etki üretmez ve `CAPTURE_USED_ON_OTHER_ORDER` uzlaştırma vakası açar. 5) Mükerrer tahsilat bildirimi (aynı `captureId`) ikinci bir finansal etki oluşturmaz, özgün sonuç döner. 6) Aynı siparişe **farklı** `captureId` ile gelen ikinci `PAID` gerçek ikinci tahsilat olabilir: `SECOND_CAPTURE` vakası açılır ve insanlar/Payment mutabakat ve iade kararını verir; Order Service otomatik iade yapmaz. 7) Payment'ın `captureId` üretimini ve kalıcılığını (yeniden başlatma sonrası aynı tahsilat için aynı değer) güvence altına alması Payment Service'in sorumluluğundadır; Order Service bunu doğrulayamaz.

**Eksik / doğrulanmamış sözleşmeler (rapor):** (1) olay kayıtlarının JSON şemaları dosya olarak yayımlanmaz, yalnızca koddaki `record`'lardır; (2) Kitchen'ın `OrderPlaced`'i tüketme sözleşmesi ve Notification/Waiter/Admin/QR tüketimi yalnız burada ve kodda tanımlıdır, karşı tarafla doğrulanmamıştır; (3) `QrSessionPort` ve `MenuCatalogPort` sözleşmeleri port javadoc'larındadır, QR ve Menu ekipleriyle onaylanmamıştır; (4) servis token'ı (K-02) ve personel kimliği (`AuthContextPort`) Auth ekibiyle kesinleşmemiştir; (5) Payment tarafı bu sözleşmeyi henüz bilmez.

## 6. Bağımsız durum makineleri

### Mutfak — normatif

```mermaid
stateDiagram-v2
    [*] --> WAITING
    WAITING --> PREPARING: Başlat / startedAt
    PREPARING --> READY: Hazır / readyAt
    WAITING --> CANCELLED: Order iptal onayı
    PREPARING --> CANCELLED: Order iptal onayı
    READY --> READY: Garson servis etti / Kitchen değişmez
```

READY'den iptal normal V1 geçişi değildir; sınır D-02'de kesinleştirilir. İptal talebi ayrı kayıttır, durum geçişi değildir.

### Diğer yaşam döngüleri — öneri

- **Sipariş işi:** `ACCEPTED`, `PARTIALLY_CANCELLED`, `CANCELLED`, `COMPLETED`. Tamamlanma için teslim/iptal ve ödeme koşulları birlikte değerlendirilir.
- **Teslim:** `PENDING`, `SERVED`; iptal edilen sipariş SERVED yapılamaz.
- **Ödeme girişimi:** `PENDING`, `SUCCEEDED`, `FAILED`, `CANCELLED`, teknik belirsizlik için `UNKNOWN`. Siparişin ödeme özeti girişimlerin sonucu ve tahsilattan türetilir.
- **Ana oturum:** `OPEN`, `CLOSING`, `CLOSED`, `EXPIRED`.
- **Masa temizliği:** `AVAILABLE`, `OCCUPIED`, `CLEANING`, `DISABLED`.
- **Çağrı:** `OPEN`, `CLAIMED`, `RESOLVED`, `CANCELLED`; alıcı “okundu” durumu ayrı.

“Hazır → servis → ödeme” ekran sırası ön ödeme seçeneğini engelleyen tek enum'a dönüştürülmez. Ödeme başarılı olsa da mutfak hazırlığı sürüyor olabilir.

## 7. İptal, ödeme ve uzlaştırma

Kitchen iptal talebi neden ve gerektiğinde ürün referansı içerir. Order mevcut iş/teslim/ödeme durumunu ve yetkiyi denetler; talebi kabul veya reddeder. Kesinleşen iptal geçmişe eklenir ve olay üretilir. Kitchen yalnız bu olayı uyguladıktan sonra CANCELLED olur.

Payment başarısı ile Order iptali birbirinden bağımsız transaction'lardır. İptal sonrası gecikmiş başarılı callback gerçek para alındıysa kaydı korunur ve manuel/otomatik uzlaştırma gerektirir; “sipariş iptal, callback'i yok say” yaklaşımı uygun değildir. İade kapsamı kesinleşmeden refund API veya otomatik iade vaadi verilmez. İlk sürümde ödenmiş sipariş iptalini yetkili incelemeye alma önerisi D-01/D-02 ile kararlaştırılır.

Ödeme başlatılırken sunucunun belirlediği tutar/para birimi/order referansı kullanılır. Ödeme yöntemi değişikliği ikinci tahsilat üretmemeli; tek aktif girişim ve tek başarılı settlement kısıtları kullanılmalıdır. Sağlayıcıya gönderilmiş fakat cevabı kayıp istek `UNKNOWN` olarak uzlaştırılır; yeni anahtarla tekrar tahsilat başlatılmaz.

Demo sağlayıcı ayrı mod/namespace ile işaretlenir; üretimde açılmaz. Demo başarılı kaydı gerçek `payment_settlements` tablosuna yazılmaz; gerçek ciro, değerlendirme ve masa ödeme koşullarına etkisi test politikasında açıkça tanımlanır.

## 8. Gerçek zamanlı ekranlar

SSE veya WebSocket henüz seçilmemiştir. Tek yönlü bildirim için SSE, etkileşim gereksinimi varsa WebSocket değerlendirilebilir; bu öneri uygulama değildir. İlk sürümde kontrollü polling de yeterli olabilir.

İlk açılışta yetkili snapshot, sonra olay/delta; kopma sonrası son sürüm/cursor ile tekrar senkronizasyon önerilir. Olay geçici UI bildirimi olabilir, kalıcı iş gerçeği sahibi servistedir. Kanal abonelikleri tenant + rol + kaynak sahipliğiyle sınırlandırılır; frontend'in gönderdiği restaurantId tek başına kanal yetkisi değildir. Gateway MVC/timeout kapasitesi seçilen uzun bağlantı yaklaşımıyla ayrıca doğrulanır.

## 9. Güvenlik ve güven sınırları

`docs/SECURITY.md` bu tasarımın yerel güvenlik dayanağıdır. Auth yöntemi kesinleşmeden cookie/JWT akışı uygulanmış sayılmaz.

- Gateway dış kimlik doğrulama ve hız limiti katmanı olabilir; kaynak sahibi servis rol/tenant/nesne yetkisini ayrıca doğrular.
- Kullanıcıdan gelen `X-Restaurant-Id` veya rol header'ına güvenilmez. Proxy kimlik header'ları temizlenir; servisler arası kimlik kriptografik veya eşdeğer güvenilir mekanizmayla doğrulanır.
- Guest yetkisi belirli ana/guest oturumuna bağlıdır. Masa numarası veya QR linkini bilmek tüm masanın geçmişine erişim sağlamaz.
- Tarayıcı auth tokenı localStorage/sessionStorage/IndexedDB'ye kalıcı yazılmaz. Cookie kararında CSRF ve güvenli cookie özellikleri birlikte tasarlanır.
- QR doğrulama, login, sipariş ve ödeme farklı rate-limit profilleri taşır. Ham token/özel linkler loga, analitiğe ve metadata'ya konmaz.
- Mail ve Timer route'larının Gateway'de bulunması bunların public olması gerektiği anlamına gelmez. İç komutlar dış erişime kapatılmalı; sağlayıcı webhook'u ayrı imza/replay doğrulaması kullanmalıdır.
- Production'da backend DB/servis portları internete açılmaz. Ayrı servis DB rolleri gerekir; bu çalışma yeni DB kullanıcısı veya ENV oluşturmadı.

Bu belge penetrasyon testi, güncel bağımlılık açığı taraması veya hukuki uygunluk onayı değildir.

## 10. Raporlama ve tarih

Admin paneli kaynak DB'ler arasında SQL JOIN yapmaz. Küçük kapsamda yetkili API aggregation, hacim büyüdüğünde olaydan idempotent rapor projeksiyonu önerilir. Raporların son senkronizasyon zamanı görünür olmalıdır.

Mutfak sayımları Kitchen'dan; doğrulanmış tahsilat Payment'tan; sipariş/satış/iptal Order'dan; masa yoğunluğu QR'dan; değerlendirme Rating'den gelir. Para toplamları para birimine göre ayrılır. “Kazanç” ürün maliyetleri olmadan kâr diye etiketlenmez.

Zamanlar `timestamptz` ile mutlak an olarak tutulur; servisler UTC kullanır. İş günü restoran IANA saat diliminde `[gün başı, ertesi gün başı)` aralığıyla UTC'ye çevrilir. Mevcut frontend tarih biçimi Europe/Istanbul'dur; çok restoran tasarımında restoran saat dilimi ayrıca tutulmalıdır.

## 11. Test, işletim ve riskler

Mevcut kanıtlar Gateway HTTP route/downstream test kodu, Order RestClient transport/timeout test kodu, Menu Flyway/PostgreSQL test kodu ve frontend i18n/tarayıcı testleridir. Bu incelemede testler çalıştırılmadı. İş akışı ve yetki testleri mevcut altyapı testlerinin yerine geçmez.

Uygulamada gerekli ek kontroller: tenant/rol/guest sahipliği, çift sipariş, callback tekrarı, iptal/ödeme/kapanış yarışları, olay sırası/tekrarı, servis kesintisi ve geri yükleme. Manuel pre-push komutu repository'deki `pre-push/pre-push.md` rehberinden izlenir; SonarQube ayrı ve isteğe bağlıdır.

Başlıca mimari riskler:

1. **Auth ve tenant uygulamasının yokluğu:** İş endpoint'lerinden önce güven sözleşmesi ve olumsuz yetki testleri.
2. **Dağıtık kapanış/checkout yarışı:** Rezervasyon, fencing ve uzlaştırma uygulanmadan atomiklik vaadi verilmemesi.
3. **Ortak DB kimliği:** Ayrı kullanıcı/izin ve migration/runtime rol ayrımı.
4. **Olay taşıyıcısının yokluğu:** Outbox + tüketici tekilliği; sonlu retry ve hata görünürlüğü.
5. **Ödeme belirsizliği:** Sağlayıcı, iade, UNKNOWN ve demo/gerçek ayrımı.
6. **Erken mikroservis maliyeti:** 12 havuz için varsayılan üst sınır toplamı 60 bağlantıdır; buna migration/test/operasyon bağlantıları da eklenir. Kapasite ölçülmelidir.
7. **Belirsiz ürün kararları:** `requirements.md` D-01–D-15 kapanmadan ilgili davranışın tamamlandı sayılmaması.

Deployment, broker, yeni ENV, migration ve güvenlik sözleşmesi değişiklikleri bu analiz tesliminin kapsamına dahil değildir. Tasarımı uygulamaya alma ayrı çalışmadır.
