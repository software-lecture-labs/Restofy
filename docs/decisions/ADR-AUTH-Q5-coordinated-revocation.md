# Q5 — Koordineli personel erişimi iptali

Durum: PR #18'de uygulandı; QR teknik değerlendirmesi ve PR/reviewer onayı bekleniyor. Alper yaklaşımı olumlu değerlendirdi; bu, bütün uygulama ayrıntılarının kabulü veya production onayı değildir.

6 Kasım 2026: [ADR-QR-003 Q1–Q5 ve §6](ADR-QR-003-qr-auth-entegrasyonu.md) bu revize yanıtla eşleştirildi. PR #17 ile dev'e alınan önceki kararlar korunur; Q5 uygulaması PR #18'de Draft olarak incelenir. [Alper'in değerlendirme kaydı](https://github.com/software-lecture-labs/Restofy/pull/18#issuecomment-6671475409) ve [güncel test/sınır raporu](../auth-service/q5-implementation-status.md) esas alınır. Bu belge güncellemesi uygulamayı merge etmez veya kalan entegrasyonları tamamlanmış saymaz.

## Kapsam ve garanti

Tek restoran MVP'sinde Auth tek personel/oturum otoritesidir. QR yalnız kendi personel erişimlerini korur. Dev'de bulunmayan personel yönetimi endpoint'leri eklenmez. Guest token taşıması ve Waiter servis delegasyonu bu kararın kapsamında değildir.

Auth FINALIZE transaction'ının commit'inden sonra başlayan yeni QR yetkilendirmesi, manifestteki eski SID'yi kabul edemez. Daha önce başlamış kontrollerin/işlemlerin geriye dönük iptali vaat edilmez. Henüz FINALIZE edilmemiş bir işlem, tamamlanmış hesap kapatma veya logout olarak sunulmaz.

## Seçilen C protokolü

1. Auth kısa PREPARE transaction'ında kaynak bariyerini SHARE, kullanıcı kimliğini UPDATE kilidiyle alır. İşlem ve hedef SID manifesti kalıcıdır. Kullanıcı başına yalnız bir tamamlanmamış işlem bulunur.
2. Aynı kullanıcı kilidi ve pending kontrolü login, refresh ve MFA credential üretiminde uygulanır. PREPARE öncesi mint edilmiş SID manifestte yer alır; PREPARE sonrasında yeni credential üretimi reddedilir.
3. Transaction dışında Auth, özel servis credential'ı ile QR'a komut gönderir.
4. QR, aynı primary PostgreSQL transaction'ında SID engelleri ile operation/digest receipt kaydını yazar. Farklı manifestle aynı operation ID reddedilir. Engel kayıtları yalnız eklenir; eski komut onları silemez.
5. QR receipt imzası, store transaction proxy'sinden dönüldükten sonra oluşturulur. Açık transaction içinde receipt imzalamak reddedilir.
6. Auth QR imzasını, issuer/audience/scope, operation ID, restoran, generation ve manifest digest'ini doğrular. ACK kalıcılaştırılır; ayrı FINALIZE transaction'ı kullanıcı kilidini yeniden alıp oturum/refresh iptallerini ve varsa hesap/rol değişikliğini uygular.

HTTP altında açık DB transaction'ı veya kullanıcı kilidi tutulmaz. Normal QR personel isteklerinde Auth HTTP çağrısı yoktur.

`revocation_revision`, kullanıcı başına monoton koordinasyon sırasıdır; JPA `version` alanından ayrıdır. JWT'ye bir kullanıcı güvenlik sürümü eklemek yerine bütün ilgili SID'ler kilit altında dondurulur. QR yalnız negatif SID kayıtlarını birleştirir; düşük revision'lı bir komut daha önceki engeli geri alamaz. Kimlik/rol değişikliği, eski rolü taşıyan bütün oturumları iptal eder.

## Kanıtın dayanakları

- Her normal credential üretimi kaynak bariyeri ve kullanıcı kilidinden geçer. Bu, SID manifestinin eksik kalmasını önler.
- QR receipt imzası ancak fence + receipt transaction'ı commit edildikten sonra üretilir. Auth yalnız tam manifest için doğrulanmış receipt'i kabul eder.
- QR kontrolleri yeni READ COMMITTED transaction'ında primary DB'ye gider. Positive cache veya read replica kullanılmaz.
- Guard kayıtları otomatik silinmez. Böylece ACK ile FINALIZE arasındaki fence kaybolmaz.
- PostgreSQL `fsync=on` zorunludur; Q5 yazma transaction'ları `synchronous_commit=on` kullanır. Recovery/read-replica üzerinden yetki verilmez.

Dolayısıyla FINALIZE commit'inden sonraki yeni kontrol, daha önce commit edilmiş fence'i görür veya doğrulama kaynağı kullanılamıyorsa 503 ile kapanır. Kullanılabilir bir eski JWT'ye sessiz fallback yoktur. Bu ispat güvenilir servis anahtarları, primary DB ve aşağıdaki restore protokolü varsayımlarına dayanır; yönetici tarafından protokol dışında veri/VM geçmişi geri sarılması için koşulsuz bir garanti değildir.

## Hata, retry ve crash recovery

| Durum | Davranış |
| --- | --- |
| QR kapalı/ağ kesintisi | Auth PREPARE kalır; FINALIZE ve başarı yanıtı yoktur. Fence henüz yoksa QR eski SID'yi kabul edebilir. |
| QR commit etti, ACK kayboldu | Aynı operation ID ve manifest yeniden gönderilir; QR kayıtlı sonucu doğrular ve yeni, kısa ömürlü imzalı receipt döner. |
| ACK kalıcı, Auth FINALIZE başarısız | QR engeli kaldırılmaz. Auth ACK ve pending gate'i tutar; yeniden dener. |
| Auth worker çöktü | PostgreSQL'deki işlem ve claim süresi korunur. Başka worker/yeniden başlayan süreç devam eder. |
| İki worker/instance | SKIP LOCKED ile sınırlı batch ve 30 saniyelik yeniden deneme süresi kullanılır. Süresi dolmuş claim duplicate teslim üretebilir; idempotency bunu güvenli kılar. |
| QR DB/guard kaydı kullanılamıyor | Personel kontrolü 503; JWT fallback'i yok. |
| Kesin SID iptali | 401. İmza/claim hatası 401, doğrulanmış kimliğin rol/CSRF reddi 403. |

Worker 5 saniye arayla, en çok 8 işlem claim eder. Connect/read timeout'ları 2/3 saniyedir. Bu bir mesaj broker'ı veya genel workflow motoru değildir.

## Reset ve MFA yan etkileri

Parola sıfırlama ve recovery-code yenileme, ordinary AUTO iptalinden ayrılır:

- İlk istek, mevcut grant/password/factor kanıtını doğrular; credential tüketimi veya son iş değişikliği öncesinde PREPARE oluşturur ve 503 döner.
- Worker QR fence'ini kurup ACK kaydeder; iş değişikliğini kendisi uygulamaz.
- Reset aynı request/grant kimliğiyle; MFA aynı actor SID ile ve geçerli password/factor kanıtıyla yeniden denenir. FINALIZE ile password/factor/code değişiklikleri aynı transaction'dadır.
- FINALIZE sonrasındaki bir iş hatası, `BadCredentialsException` için eski no-rollback politikasının arkasına saklanamaz: bütün iş transaction'ı rollback edilir.
- Pending sürede yeni login/refresh/MFA grant ve yeni reset doğrulama credential'ı üretilemez. Reset talebinde pending hesap, bilinmeyen hesap gibi aynı genel yanıtla ele alınır; yeni reset maili/grant üretilmez.
- Beş dakikalık resume süresi uzatılmaz. Süre aşımında ancak QR ACK varsa SID iptali tamamlanır; `business_outcome=EXPIRED` olur. Parola veya recovery-code değişikliği yapılmış sayılmaz. QR erişilemiyorsa timeout güvenlik engelini kaldırmaz.

Kullanıcı sürümü beklenmedik biçimde değişmişse otomatik tahmin yapılmaz; işlem fail-closed kalır ve inceleme gerektirir.

## Bootstrap, çoklu instance ve restore

Auth ve QR başlangıçta CLOSED/unready'dir. DB'deki boş liste “herkes geçerli” anlamına gelmez.

1. Auth, yetkili challenge çağrısıyla QR'ın güncel DB incarnation nonce'unu alır. PostgreSQL başlangıç zamanı değişmişse QR yeni nonce üretip kapalı kalır.
2. Auth kaynak bariyerini kısa transaction'da PREPARING yapar. Devam eden SHARE sahipleri biter; yeni credential/iptal işlemleri durur. Bütün mevcut SID'ler kalıcı tam baseline'a alınır.
3. Auth nonce ve tam manifest'e bağlı bootstrap komutu yollar. QR bütün baseline engellerini, receipt'i ve readiness'i aynı transaction'da kurar. Eski nonce ile gelen komut reddedilir.
4. Auth imzalı baseline ACK'ini kendi sakladığı operation/nonce/digest ile eşleştirir; baseline oturumlarını ve refresh'lerini iptal edip kaynak bariyerini açar.

Bu geçiş/DB yeniden bootstrap'ı mevcut personelin yeniden giriş yapmasını gerektirir. Yeni QR instance, aynı primary DB'nin doğrulanmış readiness ve fence durumunu her kontrolde okur; instance belleğinde bir deny listesine dayanmaz.

`/actuator/health/readiness`, `staffRevocation` durumunu içerir; liveness ayrı kalır. Mevcut Docker ağında Auth, QR readiness kapalıyken private bootstrap adresine erişebilir. Gelecekte bir load balancer/Kubernetes kullanılırsa private bootstrap kanalı unready instance'a ulaşabilmelidir; aksi halde güvenli biçimde kapalı kalan bir bootstrap deadlock'u oluşur.

Restore'da bütün personel ingress'i ve bütün Auth/QR süreçleri (eski instance/worker dahil) kapatılır. Restore tamamlandıktan sonra, süreçler başlamadan `ops/qr/q5-restore-quarantine.sql` zorunlu çalıştırılır: durable primary'de yeni rastgele nonce üretir ve guard'ı aynı transaction'da kapatır. `ready=false` tek başına veya belirsiz bir restart talimatı yeterli değildir. Yeni nonce Auth'un eski baseline'ı tekrar kullanmasını engeller; güncel tam baseline ve Auth FINALIZE/source ACTIVE doğrulanmadan trafik açılmaz. Aynı PostgreSQL sürecinde mantıksal restore da bu sırayla desteklenir. [Somut bakım/kurtarma prosedürü](../auth-service/q5-restore-runbook.md) uygulanır. Habersiz SQL/VM/RAM rollback'i otomatik tespit garantisinde değildir; Auth restore'u kaybolmuş kimlik/yetki kararlarının ayrıca uzlaştırılmasını gerektirir.

## JWT, idle ve QR erişimi

Access TTL artırılmadı. Auth `exp=min(configured TTL, absolute deadline, current idle deadline)` üretir. `auth_policy=q5-idle-v1`, idle sınırı bulunmayan eski JWT'lerin yeni QR doğrulamasında kullanılmasını engeller. Bu küçük claim değişikliği güvenli eski-token geçişi için gereklidir; rol/SID/restaurant/audience sözleşmesi korunur.

QR'ın 17 personel method/path çifti açık envanterle korunur: 11 ADMIN, 6 ADMIN/WAITER; KITCHEN reddedilir. Bilinmeyen ve guest/delegation yolları default deny kalır. Tenant ve kaynak eşleşmeleri mevcut use-case katmanında korunur.

Browser access cookie'si `restofy_access` kullanılır; Authorization başlığı staff kimliği değildir. Mutasyonlar trusted Origin, JSON content type, tek `restofy_csrf` cookie ve eşit tek `X-CSRF-TOKEN` ister. Auth'un CSRF HMAC sırrı QR'a aktarılmaz. Çift access veya beklenmeyen refresh/MFA credential'ı reddedilir.

## Internal güven sözleşmesi ve deployment

`POST /internal/q5/challenge`, `/bootstrap`, `/revocations` public gateway tarafından route edilmez. QR ayrıca body deserialization öncesinde servis kimliğini doğrular. Cookie/Origin taşıyan istek reddedilir; personel JWT'si servis credential'ı olarak kabul edilmez.

Dedicated Auth command ve QR receipt RSA çiftleri, 2048+ bit, RS256 kullanılır. Issuer/audience/scope, operation, restoran, generation, digest ve 30 saniyelik süre kontrol edilir. Receipt DTO'larının `toString()` çıktısı credential içermez. Production transport HTTPS ister; yerel dev için yalnız loopback/`qr-service` HTTP istisnası vardır. Native Spring TLS veya private TLS termination ve JVM trust store deployment tarafından sağlanmalıdır.

Yeni ayarlar: `AUTH_Q5_COMMAND_PRIVATE_KEY`, `AUTH_Q5_COMMAND_PUBLIC_KEY`, `QR_Q5_RECEIPT_PRIVATE_KEY`, `AUTH_Q5_QR_PUBLIC_KEY`, `AUTH_Q5_QR_GENERATION`, `AUTH_Q5_QR_URL`. Gerçek secret üretilmez veya örnek değer commit edilmez. Mevcut personel RSA çifti değiştirilmez; QR staff public key'i mevcut `MAIL_AUTH_PUBLIC_KEY` materyalini kullanır. İki yeni çift yalnız Q5 servis kimliği içindir; bu tercih ekip incelemesinde görünürdür.

Bu anahtarlar başlangıçta doğrulanıp tekrar kullanılır. Auth command çifti ve QR receipt çifti kendi servisinde public/private parametreleriyle eşleştirilir; bunun için mevcut command public key Auth'a, mevcut receipt public key QR'a da aktarılır. QR staff/servis trust anahtarlarının ayrılığını, restoran/issuer/TTL/origin ayarlarını başlangıçta doğrular. Anahtar veya kritik ayar hatası servis başlangıcını durdurur; secret/hata nedeni zinciri loglanmaz. Local çift uyumu iki servisin birbirine doğru dağıtıldığının kanıtı değildir; peer imza/manifest doğrulaması kaldırılmaz.

## Alternatifler ve sınırlar

P2 düzeltmesinde Auth kaynak bariyeri ve readiness, yerel restoran/generation ile kalıcı source identity'yi de eşleştirir; bağlı kimlik yeni bootstrap ile değiştirilemez. Yanlış-generation geçmiş PREPARED kayıtlar için otomatik bariyer kaldırma yoktur. [Manuel kurtarma önerisi, zorunlu peer-key deployment smoke kontrolü ve pending tanısı](../auth-service/q5-p2-recovery-and-peer-smoke.md) ayrı tanımlanmıştır. QR readiness tek başına staff peer anahtarının doğru dağıtıldığını kanıtlamaz; otomatik fingerprint bağlama bu düzeltmeye eklenmemiştir. Receipt kabul üst sınırı üreticiyle aynı 30 saniyedir.

JWT ve service receipt kontrolleri için sunucu saatleri senkron tutulmalıdır. Clock skew toleransı eklenip idle sınırı sessizce uzatılmaz; saat/configuration hatası erişim açarak telafi edilmez. Development HTTP istisnası yalnız gelişim profile'ı ile gelişim APP_ENV birlikte doğrulandığında geçerlidir.

- A: Her istekte Auth kontrolü daha küçük dağıtık durum maliyeti getirir, fakat istenen normal-istek bağımsızlığını sağlamaz.
- B: Auth commit'inden sonra asenkron bildirim, kayıp/gecikmiş bildirim penceresinde kesin Q5 şartını sağlamaz.
- C: Bu uygulama normal istekte bağımlılığı kaldırır; iptal tamamlama ve restore sırasında kullanılabilirliği QR'a bağlar. Basit event bildiriminin maliyetine sahipmiş gibi sunulmaz.

Baseline en çok 100.000 SID kabul eder; aşılırsa truncation yerine kapalı kalır. Engeller için otomatik retention yoktur. İlerideki bir servis, tüketici listesi/üyelik bariyeri ve tam baseline tamamlanmadan otorite kabul edilemez; bu PR yalnız zorunlu QR tüketicisini uygular. Online key rotation, genel event bus ve yeni personel yönetimi API'leri kapsam dışıdır.

Migration'lar Auth V7 ve QR V122'dir; uygulanmış eski SQL değiştirilmez. Auth feature branch'indeki V5/V6 bu PR'a taşınmaz. Gelecekte o branch entegre edilirken forward-only migration sırası ayrıca incelenmelidir.

Test komutları ve güncel sonuçlar: [Q5 uygulama durumu](../auth-service/q5-implementation-status.md). Önerilen PR metni: [Q5 review](../auth-service/q5-pr-review.md).
