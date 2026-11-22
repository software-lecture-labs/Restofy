# ADR-QR-002 — QR müşteri oturumu, cookie ve hız sınırı güvenliği

- **Durum:** Guest tokenının HttpOnly cookie ile taşınması Hamza, Umut ve Alper tarafından 5.11.2026 toplantısında ONAYLANDI. K3 ile bağlı K4/K6 güvenlik yükümlülükleri kabul edilen hedeftir; gerekli değişiklikler servis entegrasyonları sırasında uygulanacak. K1, K2, K5, K7 ve K8 QR kapsamında kabul edilen hedeflerdir. K3/K4/K5/K6 QR kodunda uygulandı (19 Kasım 2026, `chore/qr-guest-session`): HttpOnly `restofy_guest` cookie, guest CSRF + Origin, her kullanımda sunucu doğrulaması; gövdede `guestToken` yoktur. Müşteri uçları rate limit değişikliğine kadar kapalıdır (`denyAll`); K7 rate limit uygulanmadı.
- **Oluşturulma:** 1 Kasım 2026. **Son güncelleme:** 19 Kasım 2026.
- **Hazırlayan ve QR karar sahibi:** Alper — QR / Payment sorumlusu.
- **Toplantı ve karar sahipleri:** 5.11.2026 — Hamza (Order), Umut (Waiter), Alper (QR / Payment). Toplantı kararı Alper'in talebiyle bu ADR'ye kaydedildi; ayrıca PR yorumuyla onay bekleme koşulu yoktur.
- **Etkilenen taraflar:** QR, Order, Waiter, frontend ve guest kimliğini kullanacak Payment entegrasyonları. QR tarafındaki cookie/CSRF ayrıntıları K3'te kayıtlıdır; tüketici uyarlamaları entegrasyon işlerinde somutlaştırılacak.
- **İlgili personel çalışması:** [ADR-QR-003](ADR-QR-003-qr-auth-entegrasyonu.md) (önceki adı ADR-QR-AUTH-001).

Bu kayıt, Alper'in müşteri güvenliğini QR'da tutma ve QR hız sınırlarını servis içinde yönetme kararını; guest tokenının HttpOnly cookie ile taşınması içinse Hamza, Umut ve Alper'in ortak toplantı onayını belgeler. K3 artık yalnız QR'ın tek taraflı önerisi değildir. Karar onayı ile uygulama ve doğrulama ayrı durumlardır. K3/K4/K5/K6'nın QR tarafı `chore/qr-guest-session` dalında kod ve testlerle uygulandı (§6). ENV, migration ve entegrasyon rehberi değiştirilmedi.

**Review dayanağı:** [Hocanın 2 Kasım yorumu](https://github.com/software-lecture-labs/Restofy/pull/17#pullrequestreview-5743201623), [Kerem'in 4 Kasım yanıtı](https://github.com/software-lecture-labs/Restofy/pull/17#issuecomment-6634331755) ve [sabit Auth yanıtı](https://github.com/software-lecture-labs/Restofy/blob/0356bb530cd37c83a2142fc8b5fe44004e8ee99c/docs/auth-service/qr-auth-integration-response.md). İki yanıt da guest cookie değişikliğinin Hamza/Order ve Umut/Waiter onayına bağlanmasını ister. Hocanın bellek + Bearer alternatifi seçilmiş karar değildir. Reviewer'ın numara önerisiyle personel belgesi ADR-QR-003 oldu; bu belgenin ADR-QR-002 kimliği korundu.

## 1. Mevcut durum

| Kaynak | İncelenen davranış |
| --- | --- |
| [GuestTokenCodec](../../backend/services/qr-service/src/main/java/com/restofy/qr/infrastructure/security/GuestTokenCodec.java) | 32 byte SecureRandom ile Base64URL guest tokenı üretir; biçim kontrolü ve domain ayrımıyla SHA-256 digest hesaplar. |
| [Oturum açma controller'ı](../../backend/services/qr-service/src/main/java/com/restofy/qr/api/controller/TableSessionController.java) / [yanıt DTO'su](../../backend/services/qr-service/src/main/java/com/restofy/qr/api/dto/response/OpenedGuestSessionResponse.java) | Her kabul edilen tarama ayrı guest oluşturur; `guestToken` JSON yanıtında DÖNMEZ; yalnız HttpOnly `restofy_guest` cookie ile verilir (`GuestSessionOpenedResponse`; `OpenedGuestSessionResponse` yalnız iç servis sonucudur). Yanıt no-store kullanır. |
| `GuestActivityRequest` | KALDIRILDI. Aktivite isteğinde gövde yoktur; guest yalnız `restofy_guest` cookie ile tanınır ve her kullanımda sunucuda doğrulanır. |
| [GuestSessionValidationService](../../backend/services/qr-service/src/main/java/com/restofy/qr/application/service/GuestSessionValidationService.java) | Tokenı gerçek guest/main bağlamıyla, güncel kullanılabilirlik ve Order kaynak sınırıyla doğrular. Doğrulama aktivite yazmaz. |
| [HTTP güvenliği](../../backend/services/qr-service/src/main/java/com/restofy/qr/infrastructure/security/TableSecurityConfiguration.java) | Guest yolları (`/validate`, `/open`, aktivite) guest cookie, guest CSRF ve Origin filtresinden geçer; filtreyi geçen istek de K7 rate limit uygulanana kadar `denyAll` ile reddedilir. Rate limit yoktur. |

Rastgele token ve sunucudaki hash saklama mevcut temeldir. Browser taşıması için onaylanan HttpOnly cookie QR'da uygulandı; Order/Waiter/Payment/frontend entegrasyonları henüz tamamlanmadı. QR credential'ı ile guest tokenı farklı amaçlara sahiptir; biri diğerinin yerine geçmez.

**Tüketici uyumluluğu:** Yereldeki [Order guest filtresi](../../backend/services/order-service/src/main/java/com/restofy/order/infrastructure/security/GuestSessionAuthenticationFilter.java) `Authorization: Bearer` kullanır. [Order güvenlik yapılandırması](../../backend/services/order-service/src/main/java/com/restofy/order/infrastructure/security/SecurityConfiguration.java), browser'ın otomatik eklemediği header kimliğine dayanır ve cookie eklenmeden CSRF entegrasyonu ister. Waiter PR #14'ün [5811d2c güvenlik yapılandırması](https://github.com/software-lecture-labs/Restofy/blob/5811d2cf7091b5a63ecb3756fee37ca7888d9207/backend/services/waiter-service/src/main/java/com/restofy/waiter/infrastructure/security/SecurityConfiguration.java), guest çağrısını header credential varsayımıyla CSRF kontrolünden istisna eder; staff mutasyonlarında CSRF kalır. Waiter salt okunur incelendi; dalı çekilmedi. Cookie'ye geçiş yalnız QR JSON yanıtını değiştiren yerel bir sadeleştirme değildir.

## 2. Kabul edilen QR güvenlik ve ortak guest taşıma kararları

### K1 — Anonim müşteri kimliği QR sorumluluğunda kalacak

QR okutularak açılan guest için Auth personel hesabı/JWT'si üretilmeyecek. Auth'a müşteri giriş/doğrulama çağrısı zorunlu kılınmayacak. Guest, personel rolü veya başka guest'in sipariş/ödeme yetkisini kazanmaz.

Bu karar QR'ın bütün işlevlerinin diğer servislerden bağımsız olduğu anlamına gelmez: sipariş koruması, güvenli iptal ve kapanış için [ADR-QR-001](ADR-QR-001-masa-kapanisi-ve-siparis-koordinasyonu.md) kapsamındaki Order/Payment sınırları korunur.

### K2 — Güçlü token ve hash saklama korunacak

QR mevcut güçlü, rastgele guest tokenını üretmeye devam edecek; veritabanında ham token yerine digest saklanacak. Token, kimliği ve kapsamı doğrulanması gereken bir erişim kanıtıdır. Rastgelelik tokenın çalınmasını veya tekrar kullanılmasını kendiliğinden engellemez.

### K3 — Browser guest tokenı HttpOnly cookie ile taşınacak

**Durum: KABUL EDİLDİ / QR'DA UYGULANDI (19 Kasım 2026) — tüketici entegrasyonu bekliyor.** Guest tokenının HttpOnly cookie ile taşınması Hamza (Order), Umut (Waiter) ve Alper (QR / Payment) tarafından 5.11.2026 tarihli toplantıda onaylanmıştır. Gerekli cookie taşıma, QR doğrulama ve CSRF/Origin değişikliklerinin ilgili servislerin entegrasyonu sırasında yapılmasına karar verilmiştir.

**Kararın uygulanması:** Browser oturum açma yanıtındaki ham `guestToken` kaldırılacak; QR tokenı `Set-Cookie` ile verecek. İş gereği gerekli, hassas olmayan oturum bilgileri JSON'da kalabilir; kimlik alanları tek başına yetki kazandırmaz. Amaç guest credential'ının JavaScript tarafından doğrudan okunmasını sınırlamaktır. Kesin cookie/CSRF/servis taşıma ayrıntıları ve tüketici geçişi entegrasyon PR'larında tamamlanacak; toplantı onayının yerine geçecek ek PR yorumu beklenmez.

**Uygulanan somut değerler (19 Kasım 2026):** Credential cookie `restofy_guest` (HttpOnly, Secure (yalnız açık dev/local/test profilinde kapalı), SameSite=Strict, Path=`/api/v1`, Domain yok, Max-Age 12 saat; her aktivitede yenilenir). Cookie ömrü oturum geçerliliği değildir: kullanılamaz guest 404 alır ve cookie’ler aynı ad/Path ile silinir. CSRF: JS’in okuyabildiği `restofy_guest_csrf` cookie’si `X-Guest-CSRF` başlığıyla birebir eşleşmeli ve tek Origin izinli listede olmalı; personel `restofy_csrf`/`X-CSRF-TOKEN` adlarından ayrıdır. `/validate` ve `/open` şimdilik Origin + JSON ister (CSRF cookie’si henüz yoktur). Aynı tarayıcıda yeni tarama önceki guest cookie’sinin yerine geçer.

Cookie geçişinde browser aktivite isteği ve etkilenen tüketiciler birlikte uyarlanacak; eski JSON/gövde token yolunun kalıcı fallback olarak açık tutulması kararlaştırılmış değildir; QR'da eski gövde token yolu kaldırıldı. Tüketicilerle teknik sözleşme ve güvenli dağıtım/geçiş sırası entegrasyon işlerinde birlikte doğrulanacak. Browser dışındaki iç servis sözleşmesi ayrı kalır. Personel Auth cookie'si ile guest cookie'si farklı kimlik türleridir.

| Sorumlu | Servis entegrasyonunda yapılacak iş |
| --- | --- |
| Alper / QR | Guest credential'ını HttpOnly cookie ile verme, browser activity uyarlaması, gerçek guest/main doğrulama sınırı ve QR güvenlik testleri |
| Hamza / Order | Browser guest credential'ını cookie sözleşmesine göre alma; QR'a doğrulatma; cookie kullanılan mutasyonlarda CSRF/Origin ve negatif testleri uyarlama |
| Umut / Waiter | Guest çağrılarını cookie sözleşmesine uyarlama; QR doğrulamasını koruma; Bearer varsayımına dayanan CSRF istisnasını yeni modele göre düzenleme |
| Alper / Payment ve frontend entegrasyonları | İlgili guest akışlarını aynı taşıma/sahiplik sözleşmesine bağlama; credential'ı JavaScript depolamasına taşımama; gerekli browser CSRF kanıtını gönderme |

Bu tablo entegrasyon sorumluluğunu kaydeder. Yalnız Alper / QR satırı QR testleriyle uygulandı (§6); diğer satırlar tamamlanmış sayılmaz.

**Değerlendirilen alternatifler:**

| Seçenek | Kazanç | Ele alınacak maliyet / risk |
| --- | --- | --- |
| HttpOnly guest cookie | JavaScript ham guest tokenını doğrudan okuyamaz | Etkilenen cookie tüketicilerinde CSRF/Origin modeli, cookie kapsamı ve istemci geçişi; XSS ile yetkili istek yapılmasını tek başına engellemez |
| Uygulama belleğinde token + Bearer | Order/Waiter'ın mevcut guest header sözleşmesiyle uyum; browser credential'ı otomatik header'a eklemez | XSS tokenı okuyabilir; reload/sekme kapanışında token kaybı, guest sürekliliği ve yeniden tarama çözülmeli; kalıcı browser store'a taşınmaz |
| Gerekçelendirilecek başka model | Taraflar farklı bir browser/sunucu sınırı önerebilir | Yeni adapter/altyapı, sahiplik ve güvenlik maliyeti tanımlanmalı; mevcut BFF varmış gibi davranılmamalı |

Bearer header'ı taşıma biçimidir; tokenın localStorage/sessionStorage'da tutulmasını zorunlu kılmaz. Hocanın uygulama belleği önerisi kalıcı browser store'dan farklıdır. İki taşıma modelinde de production HTTPS ve sunucu yetkilendirmesi gereklidir. Ham tokenın JSON'da dönmesi tek başına güvenlik kusuru ilan edilmez. Browser guest credential'ını JavaScript'ten doğrudan okunamaz tutma amacıyla HttpOnly cookie seçildi; CSRF ve tüketici değişikliği maliyeti ortak entegrasyon kapsamına alındı. Bellek + Bearer hedef browser modeli olarak seçilmedi; yeni bir adapter/altyapı kararı da verilmedi.

### K4 — Guest cookie güvenlik özellikleri

**K3 kararının güvenlik koşulları:** Guest cookie credential'ı HttpOnly, production HTTPS üzerinde Secure ve uygun SameSite ile kullanılacak. Cookie gerekli en dar Path/host kapsamıyla sınırlanacak; Auth personel/refresh/MFA/CSRF cookie'leriyle çakışmayacak. Token localStorage, sessionStorage, IndexedDB veya kalıcı JS erişilebilir store'a taşınmayacak. Bu gereklilikler atlanamaz.

**Seçilen ayrıntılar (19 Kasım 2026):** Ad, SameSite, Path, Domain, ömür ve yenileme K3'teki somut değerlerdir. Secure yalnız açık dev/local/test profilinde kapanır; production güvenliği development kolaylığı için gevşetilmedi. Yeni ENV eklenmedi; ileride gerekirse uygulama öncesi açık onay alınacak.

**Path neden `/api/v1`:** Guest cookie'sini QR'ın yanında Order, Waiter ve Payment da kullanacak. Gateway'de bu servisler `/api/v1/qr`, `/api/v1/orders`, `/api/v1/waiters` ve `/api/v1/payments` altında, cookie Path'i ise tek bir ön ek alabiliyor. Bunların hepsini kapsayan en dar yol `/api/v1`. Bu yüzden tarayıcı cookie'yi `/api/v1` altındaki diğer servislere de gönderir. Bu servisler cookie'yi kullanmaz; cookie tek başına yetki vermez, çünkü QR onu her kullanımda doğrular (K5).

### K5 — Yetki her kullanımda sunucuda doğrulanacak

**Kabul edilmiş QR hedefi; taşıma yönteminden bağımsızdır.** Token veya cookie bulunması yeterli değildir. QR tokenın gerçek kaydından restoran/masa/main/guest bağını, aktiflikleri, süreleri, kapanış ve sipariş korumasını doğrulayacak. İstemcinin gönderdiği kimlikler yetki kaynağı olmayacak. Başka tenant/guest nesnesine erişim reddedilecek.

Cookie süresi ile sunucu oturumu ayrı kavramlardır. Sunucu kapatılmış veya kullanılamaz oturumu cookie kalsa da reddeder. Cookie ömrünü sabit 15 dakikaya bağlayıp sipariş nedeniyle korunmuş guest'in credential'ını kaybetmek hedef davranış değildir; süre/yenileme politikası bu korumayla birlikte tasarlanacak.

Kapanış sonrası cookie aynı isim/Path/Domain özellikleriyle temizlenecek şekilde tasarlanır. Ancak bütün telefonlara anında cookie silme yanıtı ulaştırılamaz; güvenlik sunucu tarafındaki geçersizliğe dayanır. Yeni credential üretme, kayıp token ve yeniden tarama politikası entegrasyon sırasında netleştirilecek.

### K6 — Cookie kullanılan guest yazma işlemleri CSRF korumalı olacak

**K3 kararının entegrasyon yükümlülüğü:** Guest aktivitesi ve diğer cookie'li durum değişiklikleri için etkilenen her serviste sunucuda CSRF doğrulaması ve uygun Origin politikası uygulanacak. HttpOnly tokenın JavaScript tarafından okunmasını sınırlar; CSRF'yi veya XSS üzerinden yapılabilecek bütün işlemleri tek başına engellemez. SameSite ve CORS tek başına CSRF doğrulamasının yerine konmayacak. Order/Waiter'ın header varsayımına dayanan istisnaları entegrasyon sırasında yeni modele göre düzenlenecek.

Yeni guest oluşturan giriş/tarama akışı da başlangıç CSRF/Origin ve kötüye kullanım tasarımına dahil edilecek. QR'da seçilen model: `restofy_guest_csrf` cookie'si + `X-Guest-CSRF` başlığı (double-submit) ve tek izinli Origin; CSRF cookie'si henüz olmayan `/validate` ve `/open` için başlangıç kanıtı Origin + JSON'dur. Order/Waiter/Payment tarafında CSRF modeli ve guest oluşturma limitleri (K7) henüz uygulanmadı. CSRF kanıtının taşınması guest credential'ını JavaScript'e açmayı gerektirmemeli; personel ve guest bağlamları karışmamalı.

### K7 — Hız sınırlarını QR yönetecek

QR tarama/doğrulama, guest oluşturma, aktivite ve kendi personel işlemlerinin limitlerini Auth'a limit kontrol çağrısı yapmadan uygulayacak. Personel JWT doğrulaması bundan ayrı kalır. Kerem bu sorumluluk dağılımını Auth/QR için destekledi; hocanın bütün servisler için kesin onay verdiği veya diğer servis sahiplerinin ortak karar aldığı varsayılmaz. Gateway'in genel koruması servis işlem limitinin yerine geçmez.

- Tarama için istemci adresi ve doğrulanmış QR/masa kapsamı; aktivite için doğrulanmış guest; personel işlemleri için kullanıcı/restoran kapsamı değerlendirilecek.
- Her kabul edilen tarama yeni guest oluşturduğu için guest oluşturma hızı ve aktif guest kapasitesi ayrıca sınırlandırılacak. Tek başına IP, ortak restoran Wi-Fi'sinde yeterli veya adil değildir.
- Geçersiz girdinin DB/kripto maliyetinden önce ucuz boyut/biçim ve kötüye kullanım kontrolleri değerlendirilecek. İstemcinin serbest restoran/guest beyanı doğrulanmış limit anahtarı sayılmayacak.
- Çoklu QR instance'ında toplam limit tutarlılığı sağlanacak. QR'ın kendi PostgreSQL'i bir seçenek; sayaç depolaması henüz seçilmedi. Auth/başka servis DB'si kullanılmayacak.
- Yalnız güvenilir Gateway/proxy zincirinden gelen istemci adresi kullanılacak. Güvenilir zincir ve forwarding header temizleme ayarı ayrıca ortaklaştırılacak; doğrudan `X-Forwarded-For` kabul edilmeyecek.
- Sayısal limitler, kapasite/eviction davranışı, sayaç deposu kesintisi ve 429/Retry-After sözleşmesi açık. Depo kesintisi sessizce sınırsız erişime dönüşmeyecek; ayrıntılı hata/erişilebilirlik politikası ayrıca seçilecek.

### K8 — Token sızıntısı ve önbellekleme engellenecek

Ham guest tokenı URL/query, log, hata yanıtı, analitik veya olay payload'ına yazılmayacak. Token oluşturan/taşıyan yanıtlar no-store olacak; mevcut oturum açma/aktivite no-store davranışı korunacak. Paylaşımlı proxy/CDN cache başka müşterinin cookie veya oturum verisini sunmamalı.

QR linkinin kopyalanabilmesi fiziksel masada bulunmayı kanıtlamaz. Cookie'ye geçiş bu riski çözmüş sayılmaz; fiziksel varlık için yeni Wi-Fi/konum zorunluluğu bu belgeyle eklenmez.

## 3. Uygulamadan önce çözülecek taşıma ve ürün soruları

| Konu | Yanıtlanacak ayrıntı / etkilenen taraf |
| --- | --- |
| Ortak taşıma sözleşmesinin teknik ayrıntıları | Cookie kararı 5.11.2026 toplantısında onaylandı. QR tarafında cookie adı/kapsamı, CSRF sözleşmesi ve DTO seçildi (K3); tüketici geçiş sırası entegrasyon PR'larında netleştirilecek; ek karar/onay yorumu beklenmiyor. |
| Order/Waiter/Payment'a guest taşıması | Frontend HttpOnly tokenı okuyup header'a koyamaz. Gerekli browser yolları, tüketici değişiklikleri/CSRF ve gerçek QR doğrulaması servis entegrasyonlarında uygulanmalı. Çalışan adapter/BFF varmış gibi davranılmayacak. |
| Farklı masa ve çoklu sekme | Aynı browser kapsamındaki sekmeler cookie'yi paylaşır. Başka QR okutulduğunda mevcut oturum, hesap erişimi ve cookie'nin üzerine yazılması davranışı seçilmeli; aynı anda çoklu masa desteği varsayılmayacak. |
| Tekrarlı tarama ve kayıp yanıt | Mevcut her tarama ayrı guest davranışıyla cookie yenilenmesi, reload ve yanıt kaybında tekrar açma/guest çoğalması birlikte değerlendirilmeli. Otomatik guest birleştirme kararı verilmedi. |
| Credential/cookie ömrü | Siparişli koruma, bağımsız guest idle süresi, browser kapanışı ve kayıp credential davranışıyla uyumlu politika seçilmeli. Geçersiz oturum yenilemeyle diriltilmemeli. |
| İstemci geçişi | JSON yanıtı ve activity gövdesi değişince mevcut frontend ve servis istemcilerinin etkisi incelenmeli; sürümleme/geçiş ve dağıtım sırası tanımlanmalı. |
| Cookie ve CSRF ayrıntıları | QR için seçildi (K3/K4/K6). Tüketici servislerde aynı cookie'nin nasıl kullanılacağı ve CSRF doğrulaması açık. |
| Limit ve proxy ayrıntıları | Sayısal bütçe, sayaç tutarlılığı, kapasite/kesinti ve güvenilir istemci adresi ilgili geliştiricilerle belirlenmeli. |

Bu açık sorular çözülmeden taşıma/güvenlik sözleşmesi tamamlanmış veya bütün müşteri API'leri açılabilir sayılmaz. Aktif guest kapasitesini aşan istekleri reddetmek, mevcut guest'leri rastgele silme yetkisi vermez.

## 4. Korunan iş kuralları ve kapsam sınırı

- Gerçek menü/sepet etkileşimi ilgili guest ve main süresini yeniler; başka guest'i yenilemez. Açık sekme/polling ve salt doğrulama aktivite değildir.
- Kapanmış veya süresi dolup kullanılamaz olmuş guest, gecikmiş bildirimle yeniden açılmaz. Sipariş koruması ve güvenli son iptal sonrası 15 dakika kuralları ADR-QR-001'e göre korunur.
- Guest tokenı sipariş kabulü, Payment tahsilat kanıtı veya personel/servis kimliği yerine geçmez. Order checkout/kapanış koordinasyonu ve guest sahipliği kendi yetkili sınırlarında korunur.
- Yeni public endpoint, tüm API'leri permitAll açma, Auth/Order/Waiter/Payment kod değişikliği, yeni broker veya ortak domain kütüphanesi bu çalışmanın kapsamında değildir.
- QR personel RBAC matrisi Alper tarafından [ADR-QR-003 Q3.1](ADR-QR-003-qr-auth-entegrasyonu.md) kapsamında kabul edildi; uygulama ve doğrulama durumu ADR-QR-003'te izlenir. Bu çalışma müşteri taşıma kararını personel entegrasyonuna zorunlu bağımlılık yapmaz.

## 5. Kabul ölçütleri — kısmen QR testleriyle doğrulandı

| Senaryo | Beklenen davranış |
| --- | --- |
| Yeni guest açma | DB'de digest, loglarda token yok, yanıt no-store. Browser JSON'unda ham token yok; uygun HttpOnly/Secure/SameSite Set-Cookie doğrulanır. |
| Başka restoran/main/guest veya sahte kimlik alanları | Cookie olsa bile yetkisiz erişim reddi |
| Geçersiz, belirsiz/çift cookie, kapalı veya kullanılamaz guest | Güvenli reddetme; terminal oturumu yenilememe |
| CSRF eksik/yanlış veya yasak Origin | Cookie kullanılan browser yollarında cookie varlığına rağmen durum değişikliğinin reddi; eski Bearer varsayımına dayanan istisnaların yeni modeli atlamaması |
| Başlangıç oturum açma istismarı | Kararlaştırılmış başlangıç koruması ve guest oluşturma limitleri uygulanır |
| 15 dakika idle / siparişle korunan guest | Sunucu süre/koruma kuralları korunur; cookie ömrü sipariş korumasını yanlışlıkla kesmez |
| Polling, gecikmiş aktivite, iki telefon | Yapay yenileme/diriltme olmaz; diğer telefonun süresi uzamaz |
| Çoklu sekme/başka masa/yanıt kaybı | Seçilecek açık politikaya uygun; sessizce yanlış guest/masa üzerinden işlem yapılmaz |
| Çoklu QR instance, ortak Wi-Fi, sahte forwarded header | Tutarlı/adil limit ve yalnız güvenilir proxy adresi |
| Sayaç kapasitesi veya deposu kesintisi | Seçilecek kontrollü hata davranışı; sessiz sınırsız erişim yok |
| Order/Waiter/Payment browser akışı | Güvenli taşıma ve gerçek QR doğrulaması; başka guest'in yetkisini kullanamama |
| Kapanış sonrası cookie ve token tekrarı | Cookie temizleme mümkünse uygulanır; eski token sunucuda reddedilir |

QR testleriyle doğrulanan kısım (`GuestCookieSecurityTests`, `GuestRoutesClosedHttpTests` ve mevcut guest servis testleri): JSON'da ham token olmaması ve cookie özellikleri, çift/eksik/bozuk cookie reddi, eksik/yanlış CSRF ve yasak/çoklu Origin reddi, personel cookie'sinin guest yerine geçmemesi, `/validate` ve `/open` için Origin + JSON zorunluluğu ve geçerli kanıtlı isteğin bile `denyAll` ile kapalı kalması. Limit, çoklu instance, proxy, Order/Waiter/Payment browser akışı ve gerçek tarayıcı senaryoları DOĞRULANMADI.

## 6. Uygulama ve doğrulama durumu

| Parça | Durum |
| --- | --- |
| Rastgele guest tokenı, digest saklama ve yerel guest doğrulama temeli | MEVCUT — kaynak incelendi; bu çalışmada değiştirilmedi |
| JSON'dan Set-Cookie'ye geçiş, activity uyarlaması, cookie yaşam döngüsü | UYGULANDI (QR, 19 Kasım 2026) — Order/Waiter/Payment/frontend tarafı UYGULANMADI |
| Guest CSRF/Origin | UYGULANDI (QR) — tüketici servislerde UYGULANMADI |
| QR'a özel hız sınırları (K7) | UYGULANMADI — ayrı PR (`chore/rate-limit`); o zamana kadar müşteri uçları `denyAll` |
| Gerçek browser ve servisler arası guest taşıma | DOĞRULANMADI — taraflar arası sözleşme ve E2E bekliyor |
| QR testleri | ÇALIŞTIRILDI — qr-service 629 test, 0 hata/başarısızlık (19 Kasım 2026) |
| Sonar ve pre-push | Sonar yerelde çalıştırıldı: QR guest değişikliklerinde açık bulgu/hotspot yok; backend kalite kapısı diğer servislerdeki bulgular nedeniyle başarısız. Pre-push son kodda yeniden ÇALIŞTIRILMADI |

Alper'in 5 Kasım planı: önce personel RBAC/JWT/cookie güvenlik temeli, ayrı PR'da QR rate limit. Guest HttpOnly cookie QR tarafında uygulandı; tüketici tarafı ilgili servislerin entegrasyonlarında yapılacak. K7 limitleri ve gerçek doğrulamalar tamamlanıp test edilmeden müşteri endpoint'leri açılmayacak. Bu çalışmada ENV/migration değişikliği yapılmadı. Entegrasyon rehberi bu aşamada güncellenmedi. Commit/push, PR/yorum, review ve merge geliştiriciye aittir.

Kaynaklar: [SECURITY](../SECURITY.md) §3–5, §8, §11–12; [Auth limiter ADR'si](ADR-AUTH-002-rate-limit-ve-kilitler.md); [Spring Security CSRF rehberi](https://docs.spring.io/spring-security/reference/features/exploits/csrf.html).
