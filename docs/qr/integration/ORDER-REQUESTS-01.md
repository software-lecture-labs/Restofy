# ORDER-REQUESTS-01 — QR-INT-01 için Order tarafından beklenenler

- **Tarih:** 18 Kasım 2026
- **Talep eden:** Alper — QR / Payment
- **Muhatap:** Hamza — Order Service sorumlusu ve onun kodlama ajanı
- **Durum:** Payment'tan bağımsız QR → Order akışı uygulandı ve yerel iki servis testinde doğrulandı. R3 ertelendi. Tam kalite kapısı açık.

## QR tarafı teslim kontrolü — 18 Kasım 2026

`1d9f792` üzerindeki `c87b4fa` ve `c6759af` teslimleri, Payment'tan bağımsız QR istemcisi için gereken Order sözleşmesini sağlıyor.

| İstek | Kod incelemesi sonucu |
| --- | --- |
| R1 | Order, `order-qr` profilinde ayrı RS256 QR servis kimliği ile restoran ve imzalı HTTP istek bağını doğruluyor. QR imzalayıcısı yerel iki servis akışında kullanıldı. Ortak ortam TLS dağıtımı ayrıca doğrulanacak. |
| R2 | Lifecycle uçları ve DTO'lar mevcut. IDLE için null süre dolmamış sayılıyor. QR, tam komutu ve digest'i koruyup değişmez `requestedAt` anına göre ilk 500 uygun adayı gönderiyor. Kalan adaylar işlemin kesin sonucundan sonra yeni komutla değerlendiriliyor. |
| R3 | **Ertelendi.** Payment kaynak API'si hazır olana kadar siparişli oturumun yetkili okuması ve yeni/yenilenen izni `503 DEPENDENCY_UNAVAILABLE` ile kapalı kalır. |
| R4 | V131, hazırlık öncesi `NOT_CLOSED` kararını kalıcılaştırıyor. Gecikmiş aynı prepare `409 INVALID_STATUS_TRANSITION` alıyor. K13 teyidi, gate ve aynı operationId/digest ile tekrar sözleşmesi korunuyor. İmzalı IDLE ve teyit iki gerçek servisle doğrulandı. K13 eşlemesi ve yanıt kaybı QR HTTP taşıma testlerinde kapsanıyor. |

**QR uygulaması:** `OrderLifecycleClient`, `qr-order` profili altında `ORDER_SERVICE_URL` üzerinden imzalı read, prepare, status, ACK ve abort çağrılarını yapar. Aynı işlem kimliği ve digest korunur. Yanıt bağının uyuşmaması, HTTP hatası veya kayıp yanıt bir kapanış/abort kanıtı üretmez. `AUTH_RESTAURANT_ID` ve mevcut timeout ayarları kullanılır. QR'a ayrılan imza anahtarı, profil ayarları ve anahtar üretim komutu [QR anahtar rehberinde](../qr-key-uretimi.md#qr--order-lifecycle-imza-anahtarı) yer alır.

**QR doğrulaması:** Java 25 tam backend koşusunda QR için 621, Order için 978 test geçti. Yeni QR HTTP istemcisinin 5 testi, imzayı ve gövde bağını, 500 aday sınırını, yanlış digest'i, 409/503, yanıt kaybı ve okuma zaman aşımını kapsar. Node.js anahtar ve Compose testlerinde 26 test geçti.

Geçici PostgreSQL üzerinde ayrı gerçek QR ve Order JVM'leri ile imzalı siparişsiz `IDLE_MAIN` ve Order teyidi, 501 süresi dolmuş misafirin iki kalıcı işlemde sonlandırılması, null süreli misafirin korunması ve Payment yokken siparişli ana oturumun açık kalması doğrulandı. Bu koşu yerel loopback HTTP kullandı. Ortak ortam TLS yapılandırması ve R3 mali doğrulaması ayrı kalır.

Tam pre-push backend test, build ve coverage kontrollerini geçti. Genel sonuç exit `1`: mevcut Next.js build güvenlik kontrolünde durdu ve yerel Auth/Mail ENV eksikleri Docker/HTTP kontrollerini engelledi. Frontend HTTP/Playwright çalıştırılmadı.

SonarQube `up` ve `status` exit `0`, `scan` exit `1`. Backend analizinde güvenlik/güvenilirlik/bakım D/C/A, kapsam %92,9 ve tekrar %0,4. Backend Quality Gate başarısız. Frontend A/A/A, kapsam %95,8 ve tekrar %0,0. Frontend Quality Gate geçti. Bunlar proje ölçümleridir. Mevcut Sonar tokenı dosya bazlı bulgu sorgusuna yetki vermedi.

**QR Sonar düzeltmesi:** Yalnız QR modülünde Java 25 ile `clean verify` çalıştırıldı. 621 test geçti, hata ve atlama yok. Ayrı `restofy-qr-service` Sonar analizinde Quality Gate geçti. Güvenlik/güvenilirlik/bakım A/A/A, kapsam %87,5, tekrar %0 ve açık bulgu sıfır. Personel yazmalarında Spring Security CSRF kontrolü mevcut Auth çerezini doğruluyor. Q5 servis zincirindeki `S4502` istisnası yalnız önceden imzası doğrulanan ve tarayıcı `Cookie`/`Origin` başlıkları reddedilen talepler için belgelendi. JJWT'nin zorunlu `Date` dönüşümü tek bir adaptörle sınırlandı. Bu koşu tam backend veya frontend analizini tekrarlamadı.

## 1. Ajana verilecek görev ve kapsam

Bu belgeyi Order çalışma ağacında uygula. Önce repository'nin `AGENTS.md`, `CLAUDE.md` ve bunların istediği sekiz zorunlu belgeyi belirtilen sırayla oku. Ardından aşağıdaki kaynakları ve gerçek Order kodunu/testlerini incele. Bu belgedeki tespitleri kendi güncel dalınla karşılaştır; zaten karşılanmış maddeleri yeniden uygulama.

Amaç, **QR-INT-01: QR → Order gerçek HTTP entegrasyonu** önündeki Order bağımlılıklarını gidermek ve QR tarafına uygulanabilir bir sözleşme teslim etmektir. ADR-QR-001 Model A, K6–K9 ve K13 korunacak. QR masa/guest oturumlarının; Order sipariş kabulünün; Payment mali kanıtın sahibi kalacak.

Yalnız Order üretim kodu, ilgili testler ve mevcut Order entegrasyon belgesi üzerinde çalış. QR, Waiter, Auth ve Payment üretim kodunu kendiliğinden değiştirme. Başka servis işi gerekiyorsa sorumlusunu, gereken minimum sözleşmeyi ve engellediği maddeyi raporla. QR-INT-02/03/04, guest cookie geçişi, personel Auth entegrasyonu, rate limit veya genel outbox dispatcher bu talebin kapsamına dahil değildir.

**Onay sınırı:** Yeni/değişen ENV, servis kimliği/güven ilişkisi veya ödeme sözleşmesi için önce somut tasarım önerisi hazırla ve repository kuralları uyarınca açık onay al. Bu belge belirli bir token üreticisi, algoritma, anahtar dağıtımı veya Payment endpoint'ini seçmez. Onay bekleyen işi mock credential, `permitAll`, sahte tenant veya sabit başarılı yanıtla geçme. Commit, push, deploy, veri reseti ve uygulanmış migration değişikliği yapma.

## 2. İncelenecek kaynaklar ve mevcut durum

Repository köküne göre kaynaklar:

- `docs/decisions/ADR-QR-001-masa-kapanisi-ve-siparis-koordinasyonu.md`
- `docs/decisions/ADR-QR-002-security.md`
- `docs/decisions/ADR-QR-003-qr-auth-entegrasyonu.md`
- `docs/decisions/migrations.md`
- `docs/qr/entegrasyon-rehberi.md`
- `docs/order-service/INTEGRATION_CONTRACTS.md`
- `backend/services/order-service/src/main/java/com/restofy/order/api/controller/InternalTableSessionController.java`
- Aynı serviste `api/dto/request/{PrepareClosureRequest,ConfirmClosureRequest}.java` ve `api/dto/response/{ClosureResponse,OrderSessionStateResponse}.java`
- Aynı serviste `application/port/ServiceIdentityPort.java`, `infrastructure/security/ServiceAuthenticationFilter.java`, `infrastructure/client/UnavailableServiceIdentityAdapter.java` ve `infrastructure/client/mock/InMemoryServiceIdentityAdapter.java`
- Aynı serviste `application/service/TableSessionClosureService.java` ve `infrastructure/persistence/TableSessionClosureStore.java`
- `backend/services/qr-service/src/main/java/com/restofy/qr/application/port/OrderSessionAccess.java` ve QR `domain/SessionTransition.java` — yalnız sözleşme karşılaştırması için salt okunur.

18 Kasım yerel incelemesinde Order'da lifecycle endpoint'leri, kalıcı koordinasyon ve K13 mantığı bulunuyor. Eksik olan şey bu endpoint'leri yeniden oluşturmak değildir:

| Alan | Yerel kod/belge kanıtı | Beklenti |
| --- | --- | --- |
| Gerçek QR servis kimliği | `ServiceIdentityPort` gerçek issuer/verifier olmadığını belirtiyor. `UnavailableServiceIdentityAdapter`, `!dev & !test` profillerinde 503 üretiyor; dev/test adaptörü sabit mock kimlikler kullanıyor. | R1: Gerçek, onaylanmış servis güven sözleşmesi ve Order verifier'ı |
| Lifecycle HTTP | `InternalTableSessionController` ve dört request/response DTO'su mevcut. | R2: Var olan sözleşmenin QR'a açık ve tutarlı teslimi; kanıtlanan eksik varsa dar düzeltme |
| Payment kaynağı | Order entegrasyon belgesi §5, Order → Payment doğrulama sorgusunu uygulanmamış olarak işaretliyor. Kapanış servisi yerel Order kayıtlarıyla çalışıyor. | R3: ADR K7 için yetkili mali doğrulama; Payment bağımlılığını ayrı gösterme |
| Kalıcı gate ve K13 | Order kodu ve V48/V49 mevcut. | R4: Yeniden yazmadan regresyon ve güvenilir HTTP kanıtı |

Kendi dalında bu durum değişmişse dosya/commit/test kanıtıyla farkı belirt. Test dosyasının varlığını geçmiş test sayma.

## 3. R1 — Gerçek QR servis kimliği: birincil engel

- [x] Mevcut servis güven mekanizmasını araştır. Uygun onaylanmış mekanizma varsa yeniden kullan; yoksa minimum tasarımı ve karar gerektiren noktaları Hamza/Alper'e sun. Auth'a yeni çağrı veya yeni token üretim altyapısı kendiliğinden ekleme.
- [x] QR'ın credential'ı nasıl elde edeceğini/yenileyeceğini ve Order'ın nasıl doğrulayacağını somutlaştır. İmzalı token seçilirse signature, algoritma allowlist'i, issuer, Order audience, süre ve doğrulanmış QR subject/scope kontrollerini; anahtar dağıtımı/rotation, iptal ve replay sınırlarını tanımla. Henüz seçilmemiş değerleri kararlaştırılmış gibi yazma.
- [x] Gerçek verifier'ı uygun runtime profillerinde bağla. Dev/test mock'unu gerçek kimlik doğrulama veya E2E kanıtı sayma; production'a taşıma. Mevcut fail-closed davranışı koru.
- [x] Yalnız yetkili QR servisinin lifecycle yollarına erişmesini sağla. Guest, personel, Kitchen ve Payment kimliği QR yetkisi kazanmasın. Serbest servis adı/rol/restoran header'ı kanıt değildir.
- [x] Tenant kapsamının hangi doğrulanmış kanıta dayandığını belgele. Query/body `restaurantId` tek başına yetki değildir; başka restoran/oturum ve operationId eşleşmelerini güvenli biçimde denetle.
- [ ] Eksik/geçersiz credential için 401; geçerli fakat yetkisiz servis için 403; gerekli doğrulama kaynağı kesintisinde 503 davranışını gerçek HTTP testleriyle doğrula. Eksik veya bozuk zorunlu güvenlik ayarlarında güvenli başlangıç davranışını test et. Credential, anahtar veya token loglama.

**Order doğrulaması.** Order tarafında request bağını doğrulayan RS256 verifier kullanılıyor. İmzalı HTTP testlerinde 401 ve 403 doğrulandı. Verifier başlatılırken eksik veya geçersiz ayarların reddedildiği konfigürasyon testinde doğrulandı. Kimlik kaynağı kullanılamadığında 503 uygulama testiyle doğrulandı. Gerçek QR token üretimi ve bu hata yolunun ortak HTTP testi açık.

**Teslim:** QR geliştiricisinin tahmin yapmadan kullanabileceği header şeması, credential edinme yöntemi, doğrulama/tenant kapsamı, profil/konfigürasyon sözleşmesi ve güvenli örnekler. Gerçek sırları bu belgeye, yanıta veya repoya koyma.

## 4. R2 — Mevcut HTTP sözleşmesini koru ve kesinleştir

Mevcut temel yol `/api/v1/internal/table-sessions/{tableSessionId}`. İç yolları Gateway üzerinden public açma. Şu yolları yeniden üretme veya ayrı bir `/abort` endpoint'i icat etme:

| İşlem | Method ve göreli yol | Mevcut istek |
| --- | --- | --- |
| Sipariş/guest koruması | `GET /order-state` | Query: `restaurantId` |
| Hazırlık | `POST /closure-preparations` | `PrepareClosureRequest` |
| Durum / izin yenileme | `GET /closure-preparations/{operationId}` | Query: `restaurantId`, `commandDigest`; QR her sorguda digest gönderecek |
| Yerel sonucu teyit | `POST /closure-preparations/{operationId}/confirmation` | `ConfirmClosureRequest`, `outcome=CLOSED`, yerel `receipt` |
| Abort | Aynı confirmation POST'u | `outcome=NOT_CLOSED`, aynı operationId ve digest |

Mevcut kimlik header şeması `Authorization: Service <credential>`. Bu şemanın varlığı gerçek credential üretimi/doğrulamasının uygulandığı anlamına gelmez; R1 ayrıca karşılanmalıdır.

### Request/response ayrıntıları

- Prepare: `restaurantId`, `operationId`, `purpose` (`SETTLED`/`IDLE`), `commandDigest`; IDLE için ayrıca `requestedAt`, `mainIdleExpiresAt`, `guests[{guestSessionId,idleExpiresAt}]`. Mevcut DTO en fazla 500 guest kabul ediyor. QR tarafının batch/liste sınırlarıyla uyumu kontrol et; sessiz kesme yapma, uyumsuzsa bildir.
- `commandDigest` mevcut biçimde 64 küçük harfli hex'tir. QR kendi değişmez tam komutundan üretir; Order QR komutunu yeniden yorumlayarak başka digest üretmez. Prepare için kalıcı tekrar kimliği gövdedeki `operationId` ve digest'tir; yeni bir zorunlu `Idempotency-Key` header'ı varsayma.
- Confirmation: `restaurantId`, `outcome`, zorunlu `commandDigest`; mevcut opsiyonel `receipt` alanları `effect`, `sessionVersion`, `tableVersion`, `completedAt`, `expiredGuests`. QR mevcut yerel makbuzunu gönderecek; yeni body alanlarını karşılıklı anlaşmadan zorunlu yapma.
- `OrderSessionStateResponse`: `restaurantId`, `tableSessionId`, `knowledge`, `revision`, `verifiedAt`, `validUntil`, `protectedGuests`, `cancellationReleases`, `mainCancellationAt`. QR portundaki `sessionId` ile wire DTO'daki `tableSessionId` ad farkı QR adaptöründe eşlenebilir; bunu tek başına Order kusuru sayma.
- `ClosureResponse`: kimlikler, `purpose`, `commandDigest`, `state`, `sourceStatus`, `effect`, `outcome`, `blockers`, `sourceRevision`, `verifiedAt`, `validUntil`, `complete`, `checkoutFenced`, `idleGuestIds` ve K13 `scopeDrift/driftBlockers` korunmalı. Mevcut ek alanların çıkarılmasını istemiyoruz; QR adaptörü bilinmeyen ek alanlara toleranslı olacak.

- [x] Her endpoint için DTO nullability, enum, validation, HTTP başarı/hata kodu ve güvenli JSON örneklerini mevcut `INTEGRATION_CONTRACTS.md` içinde doğrula/güncelle. Çalışan OpenAPI ile belge çelişmesin; ikinci entegrasyon rehberi açma.
- [x] `NOT_STARTED` sorgusunda verilen digest geri yansısın; bu sonucun `purpose=null` olabilmesi belgelensin. Bilinmeyen işlem 200 NOT_STARTED'dır; transport hatasıyla karıştırılmasın.
- [x] Aynı operationId + değişmiş digest/amaç/restoran/main için mevcut `409 IDEMPOTENCY_CONFLICT`; eşzamanlı başka canlı işlem için `409 CLOSURE_IN_PROGRESS` semantiğini koru.
- [x] Confirmation hatalarını koru: bilinmeyen işleme CLOSED → 404; yanlış etki → `409 CLOSURE_RECEIPT_MISMATCH`; ters terminal teyit → `409 INVALID_STATUS_TRANSITION`; validation → 422. Gerçek hata gövdesi `code/message/details` ise QR tarafı onu eşlesin; sırf başka servise benzetmek için hata modelini yeniden yazma.

**Order doğrulaması.** Onaylanan IDLE sözleşmesi Order'da uygulandı. Boş ana veya misafir süresi dolmamış sayılır. Order testleri 500 adayın kabul edildiğini ve 501 adayın 422 döndürdüğünü doğruladı. QR adaptörü tam görüntüyü ve komut digest'ini saklayacak, bir işlemde süresi dolmuş en çok 500 aday gönderecek ve kalan adayları önceki işlem kesinleştikten sonra yeni `operationId` ile değerlendirecek. Adaptörün uygulaması ve doğrulaması açık.

## 5. R3 — K7 mali uygunluk: Order işi ile Payment bağımlılığını ayır

ADR K7, Order'ın Payment'tan yetkili mali durumu doğrulayarak tam masa oturumu kapsamına uygunluk sağlamasını ister. Yerel Order ödeme olayları ve kayıtları mevcut; bunları gerçek Order → Payment sorgusu yapılmış gibi sunma.

- [x] Gerçek Payment sözleşmesini araştır. Varsa onaylanmış API ile Order tarafındaki doğrulamayı bağla. Yoksa **Payment sorumlusu Alper'den** gereken minimum sorgu, kimlik, kapsam, sürüm/tazelik ve belirsizlik sözleşmesini iste; endpoint/DTO uydurma ve Payment kodunu devralma.
- [ ] Tüm guest'lerin gerçek siparişleri, ödenmemiş COMPLETED siparişler, devam eden/belirsiz ödeme, iade ve uzlaştırma yükümlülükleri değerlendirmede bulunsun. Boş liste/istemci `paid=true` veya tek PAID alanı kapanış kanıtı olmasın. Siparişsiz guest için ödeme arama; hiç siparişsiz oturumu SETTLED_CLOSE yapma.
- [ ] Eksik/stale/ulaşılamayan mali kanıtı uygunluk sayma. `complete`, `sourceStatus`, `checkoutFenced`, doğrulama zamanı ve izin süresi gerçek kanıtı yansıtsın. Kesinti UNKNOWN veya sözleşmeye uygun güvenli hata olarak ayrılsın.
- [ ] Uzak HTTP'yi uzun DB lock/transaction altında tutma. Kaynak doğrulaması ile gate/yazım arasındaki yarış sınırını somutlaştır; iki servis arasında atomiklik iddia etme. Mevcut K8 ve K13 garantilerini koruyan çözüm onay gerektiriyorsa önce öneriyi sun.

**Order doğrulaması.** Payment kaynak API'si henüz hazır değil. Minimum sözleşme Order entegrasyon belgesinin 5.2 bölümünde kayıtlı. Order, siparişli oturum için kaynak yokken imzalı HTTP çağrısında 503 dönüyor. Tam mali kapsam doğrulaması ve uzak kaynakla yarış yönetimi Payment sözleşmesine bağlı.

**Sahiplik:** Order adaptörü/kararı Hamza'nın, Payment kaynak API'si Alper'in işidir. Payment henüz hazır değilse bu madde BLOCKED kalabilir; R1/R2 gibi bağımsız ilerleyen işler raporlanabilir. Gerçek mali doğrulama yokken tüm QR-INT-01 bağımlılıklarını DONE ilan etme.

## 6. R4 — Korunacak iş davranışı ve testler

Aşağıdaki davranışların önemli kısmı mevcut. Önce testleri çalıştır; yalnız gerçek eksiklere test/düzeltme ekle:

- [x] `order-state` salt okumadır; aktivite yenilemez veya kapanış izni vermez. Siparişli main ve sahibi guest korunur; güvenli son iptal zamanları gecikmiş teslim anından yeniden başlatılmaz. Belirsiz mali durum guest'i serbest bırakmaz.
- [x] Checkout önce commit ederse kapanış kapsamına girer; prepare önce kalıcılaşırsa ilgili checkout kayıt yazmadan 409 olur. IDLE_GUESTS yalnız seçilen guest'leri engeller. PostgreSQL kalıcı gate'i korunur; bellek kilidiyle değiştirilmez.
- [x] Tekrar prepare/status aynı kalıcı işlemi kullanır. Süresi geçmiş izin aynı operationId ile yenilenir veya geri çekilir; izin süresinin dolması gate'i otomatik kaldırmaz.
- [x] **K13:** Doğru PREPARED işleme ait geçerli CLOSED makbuzu, sonradan kapsam sapmış olsa da COMPLETED olur. Sapma V49'daki değişmez kayda gider; aynı ACK/GET aynı sapmayı döndürür. Yanlış kimlik/digest/etki veya RELEASED işlemden CLOSED dönüşü kabul edilmiş sayılmaz.
- [x] NOT_CLOSED, yalnız kesin abort sonucuyla gate'i bırakır; hiç hazırlanmış olmayan işlem için digest'e bağlı güvenli sonuç döner. Timeout abort kanıtı değildir. ACK yanıt kaybı ikinci kapanış, tahsilat veya geçmiş değişikliği üretmez.
- [x] Gerçek verifier ile geçerli/eksik/bozuk/süresi geçmiş/yanlış audience credential; yanlış servis, başka tenant ve yanlış işlem bağı testleri çalışır. İlgili profilin mock'a sessizce düşmediği kanıtlanır.
- [ ] 2xx, 4xx, 5xx, servis kesintisi, timeout/yanıt kaybı, stale status, restart sonrası aynı işlemle uzlaştırma ve checkout yarışı uygun sınırda test edilir. QR RestClient transport testleri QR işidir; Order ajanı QR kodunu değiştirmeden kendi HTTP/gate davranışını kanıtlar.

**Order doğrulaması.** V131 ile ilk NOT_CLOSED teyidi PostgreSQL'e kalıcı yazılır ve 200 NOT_STARTED döner. Aynı işleme gecikmiş prepare 409 INVALID_STATUS_TRANSITION döner. Aynı PostgreSQL'i kullanan ayrı Order JVM'lerinde yeniden başlatma sonrası bu durum, prepare ve CLOSED yanıtları dikkate alınmadan uzlaştırma ve yinelenen CLOSED teyidi doğrulandı. Checkout yarışı ayrı PostgreSQL testinde doğrulandı. Gerçek QR RestClient ile transport hatası ve iki servis E2E testi açık.

Mevcut test başlangıç noktaları: `ServiceAuthFailClosedApiTests`, `TableSessionClosureApiTests`, `TableSessionPermitApiTests`, `TableSessionIdleApiTests`, `TableSessionClosureDatabaseTests`, `TableSessionClosureConcurrencyTests`. Çoklu gerçek Order instance testi ile tek JVM PostgreSQL yarış testini ayrı raporla. Mock HTTP server/test kimliği gerçek iki servis E2E değildir.

## 7. Doğrulama ve teslim

1. Değişen Order testlerini ve gerekli PostgreSQL/Flyway kontrollerini çalıştır. Order V41–V50 doludur; yeni migration gerekirse önce ek aralık kararı iste. V47/V50 veya başka uygulanmış SQL'i yeniden yazma; reset/repair/clean yapma.
2. `git diff --check` ve güncel rehbere göre Windows'ta `.\pre-push\pre-push.cmd full dev` çalıştır. Tam kontrolün kapsamını sessizce daraltma; kapsam dışı hataları ayrı raporla.
3. Bağımsız SonarQube komutlarını güncel rehberden doğrula: `.\sonarqube\sonar.cmd up`, `status`, `scan`. Tokenı açıklama veya volume silme. Security/Reliability/Maintainability, coverage, duplication ve Quality Gate sonuçlarını gerçek ölçümden aktar. Hedef A/A/A, coverage ≥ %80 ve duplication ≤ %3; sonuç yoksa PASS yazma.
4. Çalışan gerçek servislerle test mümkünse süreçleri/profilleri ve güven mekanizmasını belirt. QR adaptörü veya Payment hazır değilse ortak E2E'yi NOT RUN/BLOCKED raporla; mock başarıyı onun yerine koyma.
5. Hamza'ya/QR tarafına tek teslim raporu ver: R1–R4 checklist'i DONE/PARTIAL/BLOCKED; değişen dosyalar; endpoint/DTO/header/hata örnekleri; onaylanan konfigürasyon ve migration etkileri; test sayısı/komut/exit code/log kanıtı; pre-push ve Sonar sonuçları; kalan bağımlılıklar ve sahipleri. Tamamlanmayanı UYGULANMADI, denenmeyeni DOĞRULANMADI olarak ayır.
6. Gerçek commit atma; yalnız somut değişiklikleri ve gerçekten çalıştırılmış kontrolleri anlatan çok satırlı önerilen commit mesajı ver. Ardından dur.

**18 Kasım Order doğrulaması**

- Order `verify` çalıştırmasında 978 test geçti. V131 temiz PostgreSQL şemasında ve V50 üzerine yükseltmede uygulandı. Kalıcı abort kaydının güncellenemediği test edildi.
- Tam pre-push çalıştırmasında backend, frontend, production npm audit, Docker ve HTTP kontrolleri geçti. Git / Secret Check, bu belgenin önceden stage edilmiş sürümündeki üç satır sonu boşluğuna takıldı. Çalışma kopyasında boşluklar düzeltildi.
- SonarQube yeniden taramasında Order için güvenlik, güvenilirlik ve bakım puanları A/A/A, kapsama %94,3, tekrar %0,4 ve açık bulgu sayısı sıfır. Quality Gate tüm backend projesine uygulanıyor. Kalan 272 yeni kod bulgusu Order dışındaki dosyalarda.

**QR tarafına dönüş koşulu:** QR geliştiricisi gerçek servis credential'ını nasıl kullanacağını, Order'ın bunu nasıl doğruladığını ve mevcut lifecycle DTO/hata semantiğini tahmin etmeden uygulayabilmeli. Payment hazır değilse kalan engel ve güvenli davranış açık olmalı. QR adaptörünün uygulanması, QR testleri ve ortak E2E ayrıca QR sorumlusunda kalır; bu belge bunları yapılmış saymaz.
