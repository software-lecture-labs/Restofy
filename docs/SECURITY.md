# RESTOFY — SECURITY.md

> Durum: **Zorunlu güvenlik politikası ve geliştirme standardı**. Bu belge, özelliklerin tamamlandığını iddia etmez. Mevcut uygulama ile hedef gereksinim ayrı değerlendirilir.
>
> Kapsam: Java 25 / Spring Boot 4.1.1 mikroservisleri, Spring Cloud Gateway Server Web MVC, Spring RestClient, PostgreSQL 17 / Flyway, Next.js 16 / TypeScript arayüzü, Docker, testler ve geliştirici ajanları.
>
> Eş belge: `web-rules.md` (arayüz, erişilebilirlik, SEO, hukuk, test ve yayın gereksinimleri). Çelişki halinde güvenlik kuralı gevşetilmez; sorumludan karar alınır.

## 0. Gerçek durum, kapsam ve temel ilkeler

- RESTOFY; **QR masadan sipariş, restoran menüsü, mutfak, garson, sipariş, ödeme ve yönetim** akışlarına sahip, restoranlar arasında veri ayrımı gerektiren bir platformdur.
- Mevcut altyapı: 13 servis (12 veri kullanabilen servis + API Gateway), tek PostgreSQL instance'ında servis başına ayrı veritabanı, Flyway ve Docker Compose. API Gateway / RestClient / Flyway için altyapı testleri bulunmaktadır; gerçek iş endpoint'lerinin, Auth/JWT/RBAC'in, ödemenin ve işletme sınırlarının uygulanmış olduğu varsayılmaz.
- **Deny by default, least privilege, defense in depth, fail closed, secure by design.** Frontend'in sakladığı/gizlediği hiçbir şey yetki kontrolü sayılmaz.
- Platform yöneticisi, restoran yöneticisi, mutfak ve garson erişimleri **birbirinden ayrıdır**. Gerçek rol adları/izin matrisi Auth geliştirmesinde onaylanır; bu dosyadaki örnek roller tamamlanmış RBAC uygulaması iddiası değildir.
- Kimlik doğrulama yöntemi, servisler arası güven modeli, müşteri/anonim masa oturumu ve ödeme sağlayıcısı için nihai karar verilmediyse uygulama önce tasarlanıp onaylanır. Geçici `permitAll` ile işlevi 'bitmiş' sayma.
- Güvenliği test amacıyla kapatmak veya geniş açmak yasaktır. Test izolasyonu gereken durumlarda **yalnızca test kapsamındaki** güvenli düzenlemeler kullanılır.

## 1. ENV, gizli bilgiler ve onay

- Şifreler, signing key'ler, OAuth/SMTP/API anahtarları, ödeme sağlayıcısı sırları, webhook sırları ve `SONAR_TOKEN` kaynak koduna, Dockerfile'a, SQL migration'a, README'ye, frontend bundle'ına, loga veya teste gerçek değer olarak **asla** konulmaz.
- Root `.env`, `.env.dev`, `.env.prod` gibi gerçek ortam dosyaları **Git dışında** kalır. Repoya yalnız gerçek sır içermeyen `.env.example` konabilir. `.gitignore` ve Git history gözden geçirilir.
- `NEXT_PUBLIC_` değişkenleri herkese açıktır; gizli değer içeremez. Frontend erişilebilir API adresi secret değildir, fakat iç Docker host/credential sızdırılmamalıdır.
- Yeni ENV anahtarı ekleme, silme, yeniden adlandırma, anlamını değiştirme veya `.env*` sözleşmesini düzenleme **öncesinde açık onay** iste. Onaydan sonra `.env.example`, ilgili Compose aktarımı ve dokümanları tutarlı güncelle; gerçek değeri sohbete/rapora kopyalama.
- Production kritik ENV eksik, boş, uyumsuz veya güvensizse uygulama **fail fast** etmeli. Test/DEV kolaylıkları production'a sızmamalı.
- Geliştirici kendi SonarQube sunucusunu ve kendi `SONAR_TOKEN` değerini yerel olarak ayarlar. **SonarQube isteğe bağlıdır; Pre-Push onu çağırmaz veya token istemez.**

## 2. Repository, ajanlar ve yerel kalite kapısı

- Kodlama ajanları açık izin olmadan `git add`, commit, push, merge, release, repository setting değişikliği veya deploy yapmaz; kullanıcı hazırladığı değişiklikleri gözden geçirir.
- Push öncesi mevcut yerel kalite komutu: ` .\pre-push\pre-push.cmd full dev ` (Windows); Linux/macOS karşılığı `sh ./pre-push/pre-push.sh full dev` veya çalıştırılabilir izinli script. Gerçek repo arayüzü değişirse belgeleri birlikte güncelle.
- `SAFE TO COMMIT` **yalnız yerel testlerin geçtiği** anlamına gelir; SonarQube/Quality Gate/production güvenliği onayı değildir.
- Test atlama, coverage şişirmek için production dosyalarını hariç tutma, güvenlik kuralını kapatma, başarısız kontrolü PASS gösterme yasaktır.
- `package-lock.json` ve Maven sürüm kontratları korunur. Yeni veya güncellenen bağımlılıkların güvenlik ve lisans etkileri incelenir. `npm audit --omit=dev` temizliği, bütün development risklerinin temiz olduğu anlamına gelmez; HIGH/CRITICAL bulgular ayrıca değerlendirilir. `npm audit fix --force` onaysız kullanılmaz.
- SonarQube Community Build ileri bir kontrol aşamasıdır; her geliştiricinin çalıştırması zorunlu değildir.

## 3. Kimlik doğrulama, token ve oturum

- Auth Service ayrı ekip çalışmasıdır. Nihai **JWT/cookie/session** sözleşmesi uygulanmadan hangi endpoint'in kimlik doğruladığı varsayılmaz; güvenlik kritik kararlar belgelendirilir.
- Tarayıcı kimlik doğrulama tokenları **localStorage, sessionStorage, IndexedDB veya kalıcı JS erişilebilir store** içine yazılmaz. Cookie tabanlı kararda `HttpOnly`, production'da `Secure`, uygun `SameSite`, kısa scope/path/domain ve expiry kullanılır.
- Cookie ile kimlik doğrulama kullanılıyorsa state-changing isteklerde **CSRF koruması** tasarlanıp test edilir; CORS CSRF'nin yerine geçmez.
- JWT seçilirse: imzalama anahtarı yalnız backend secret mekanizmasından alınır; algoritma allowlist, signature/issuer/audience/expiry doğrulanır. Access token kısa ömürlüdür; refresh token varsa rotation, revocation, replay tespiti ve güvenli hash saklama gerekir. Gerçek süre/strateji Auth mimarisinde onaylanır.
- Logout ve hesap devre dışı bırakma, kullanılan oturum/token modeline uygun biçimde yetkiyi sonlandırmalıdır. Parola değişikliği/reseti ve kritik yönetici eylemleri için yeniden doğrulama ve MFA ihtiyacı risk bazlı belirlenir.
- Parolalar tersine çevrilebilir olarak veya düz metin tutulmaz; Spring Security'nin güvenli password encoder'ı (örn. BCrypt/Argon2 destekli yaklaşım) kullanılır. Rate limit ve kullanıcı varlığı ifşasını azaltan genel hata mesajları gerekir.
- Anonim müşteri QR oturumu, **personel veya restoran yönetimi yetkisi** sağlamaz. QR kodu görmek, personel olarak kimlik doğrulamak anlamına gelmez.
- **Order Service kimlik türleri (geçici sözleşme, D-09/Auth ile kesinleşecek):** Müşteri `Authorization: Bearer <opak jeton>` (doğrulama QR portundan), personel `Authorization: Staff <opak kimlik bilgisi>` (doğrulama `AuthContextPort` üzerinden: `userId`, aktif `restaurantId`, rol `ADMIN`/`WAITER`/`KITCHEN`). Üçüncü tür, servisten servise kimlik, personelden ayrı çalışır: `Authorization: Service <opak kimlik bilgisi>` (doğrulama `ServiceIdentityPort` üzerinden; yalnız `/api/v1/internal/**` uçlarını açar, hiçbir müşteri/personel uç noktasını açmaz). Hedef yöntem (K-02, onaylandı; gerçek issuer/doğrulayıcı henüz yok): TLS üzerinden kısa ömürlü, **imzalı servis token'ı**; adaptör imzayı (döndürülebilir anahtarlarla), issuer'ı, audience'ı (Order Service), geçerlilik süresini ve servis kimliğini doğrular, kaynak servisi izinli işlemle eşler ve token tekrar kullanımını (ör. `jti`) değerlendirir; mTLS ileride ek katman olabilir ama zorunlu değildir; anahtar saklama/rotasyon Auth'tadır. Gerçek entegrasyon tamamlanana kadar production güvenliğinin sağlandığı iddia edilmez; `dev`/`test` dışında bu doğrulayıcı da **fail-closed** (503) çalışır, yani internal uçlar production'da kapalıdır. Internal uçların Gateway'den yönlendirilmemesi ağ izolasyonunu **kanıtlamaz**: production dağıtımında ağ erişim kontrolleri ve servis kimlik doğrulaması ayrıca doğrulanmalıdır (D-02). KITCHEN rolü kendi restoranının sipariş liste/detayını **indirgenmiş görünümle** okuyabilir (ödeme durumu, tutar ve fiyat yok; ödeme filtresi ve geçmiş yasak); KITCHEN ödeme durumunu değiştiremez, sipariş oluşturamaz; `PREPARING/READY` verme yolu (PATCH) yalnız `dev`/`test` profillerinde açıktır, production'da yalnız Kitchen servisi olayı geçerlidir. Uygulanamayan bir `PAID` olayı sessizce düşürülmez; denetlenebilir biçimde kaydedilir ve uzlaştırma için duyurulur. Ödeme olayları `eventId`'nin yanında kararlı bir tahsilat referansı (`captureId`) ve ödeme girişimi (`paymentAttemptId`) taşır; süreli mesaj tekilleştirmesi finansal tekilliğin yerine geçmez, referanslar kalıcı kayıtlarda tutulur ve aynı siparişe farklı tahsilatla gelen ikinci `PAID` otomatik uzlaştırma kaydı olur. Checkout'ta değişmiş fiyat, müşteri açıkça (güncel parmak izi + `acknowledgePriceChanges`) kabul etmedikçe sipariş edilmez; ret hiçbir kayıt yazmaz. Ödeme durumu yalnızca Payment servisinin doğrulanmış olayıyla değişir (`POST /api/v1/internal/order-events/payment`, tutar/para birimi ve restoran eşleşmesi zorunlu); mock servis kimliği `dev`/`test` dışında yüklenmez, dolayısıyla production'da mock ödeme olayı kabul edilmez. Üç doğrulayıcı birbirinin kimlik bilgisini görmez. İstemci başlıkları (`X-Role`, `X-User-Id`, `X-Restaurant-Id`) hiçbir türde yetki kaynağı değildir. Gerçek Auth entegrasyonu gelene kadar `dev`/`test` dışındaki her profilde personel doğrulaması **fail-closed** (503) çalışır; mock adapter yalnız `dev`/`test` profillerinde yüklenir. Bu, müşteri QR oturumu, sepet ve sipariş oluşturma davranışını değiştirmez.

## 4. Restoran bazlı tenant izolasyonu ve RBAC — kritik

- Restofy'nin ana güvenlik sınırı **restaurant/tenant** bağlamıdır. Her restoranın masaları, menüsü, siparişleri, çalışanları, ödeme bilgileri, stokları ve raporları yalnız yetkili restoran bağlamında görülür/değiştirilir.
- Restoran seçimi veya `restaurantId`/`tableId`/`orderId`/`menuId` gibi tüm kimlikler **untrusted** girdidir. Yalnız kullanıcının gönderdiği ID'ye güvenilmez; her okuma ve yazma sunucuda tenant + yetki doğrulaması yapar.
- Restaurant Manager, Kitchen Staff, Waiter, Platform Admin gibi roller için her operasyonun izin matrisi belirlenecek; gerçek enum/ad/scope implementasyona göre belgelenecek. **Global admin otomatik olarak müşteri/işletme sahipliği almaz**; gerekecek istisnalar açık ve auditable olmalıdır.
- Personel A'nın başka restoranın `/orders/{id}`, `/tables/{id}`, `/payments/{id}`, `/reports` gibi kaynaklarına erişimi reddedilir; IDOR/BOLA ve toplu veri sızıntısı testleri zorunludur.
- Backend yetkilendirmesi Gateway'de yapılmış olsa bile ilgili servisin güvenlik sınırında **yeniden doğrulanır**. İç ağ trafiği kendiliğinden güvenli değildir.
- Erişim kararı hesap/rol durumu değişince güncelliğini kaybetmemeli; cache'lenen yetkiler için iptal/güncelleme stratejisi belirlenmelidir.
- Yönetici, garson ve mutfak panelleri `noindex` olsa bile **backend auth/RBAC olmadan açılmaz**. `robots.txt` güvenlik aracı değildir.

## 5. QR, masa ve anonim sipariş güvenliği

- QR linkleri dışarıdan kopyalanabilir ve paylaşılabilir. **QR kodu tek başına kullanıcının fiziksel olarak masada olduğunu kanıtlamaz.** Tehdit modeli ve kabul edilen risk ürün kararına bağlanır.
- QR kodunda DB şifresi, API key, JWT, personel oturumu veya ödeme sırrı bulunmaz. QR hedefleri HTTPS ve doğrulanmış host/path kullanır; açık yönlendirme yapılmaz.
- QR ile seçilen `restaurantId` / `tableId` sunucuda eşleştirilir; devre dışı masa, yanlış restoran, kapanmış QR ve geçersiz bağlantı güvenli reddedilir. Kalıcı public table identifier kullanılacaksa onun yetki kazandırmadığı kabul edilir.
- Masa oturumuna geçici yetki tanımlanırsa ömür, scope, tekrar kullanım/replay ve yeniden başlatma/iptal kuralları ayrıca tasarlanır. Sadece tahmin edilemez token replay'e karşı tek başına yeterli değildir.
- QR menüsü **okuma erişimi** ile sipariş oluşturma/iptal/ödeme gibi **yazma yetkileri** ayrı kontrol edilir. Parametre değiştirerek başka masanın siparişi okunamaz/değiştirilemez.
- Yetkisiz biri QR linkini dışarıdan açtığında sahte sipariş spam'i oluşturma riskine karşı rate limiting, restoran açık/masa aktif kontrolü ve uygun kötüye kullanım önlemleri uygulanır.
- QR/table tokenları ve session kimlikleri analytics, URL preview, OpenGraph, log, referrer veya Search Console/sitemap içine sızdırılmaz. QR masa sayfaları `noindex` ve sitemap dışıdır.

## 6. Sipariş, stok, fiyat, durum ve idempotency

- Sepette görünen fiyat, indirim, vergi, para birimi, servis bedeli ve toplam **frontend'e güvenilerek** kabul edilmez; backend yetkili veriden hesaplar.
- Sipariş anında ürün adı/fiyatı/KDV/opsiyon gibi gerekli ticari snapshot Order Service içinde saklanır; gelecekte menü değişikliği eski sipariş tutarını bozmaz.
- Stok düşme, sipariş oluşturma, iptal ve ödeme güncelleme işlemlerinin transaction sınırları açıkça tanımlanır; yarış koşulları ve eşzamanlı talepler PostgreSQL üzerinde test edilir.
- Her sipariş için geçerli durum geçişleri backend'de kontrol edilir: yetkisiz, geriye dönük, çift veya eksik transition reddedilir. Garson/mutfak yetkileri ayrıdır.
- Tekrarlı tıklama, ağ yeniden denemesi, callback/webhook tekrarı veya zaman aşımı sonucunda **çift sipariş/çift ödeme** oluşmamalıdır. İlgili mutasyonlarda idempotency key, eşsiz constraint ve uygun yanıt tekrar üretimi tasarlanır; yalnız frontend buton kilitlemeye güvenilmez.
- Spring `@Transactional` sadece çağrıldığı servisin veritabanını kapsar. Order → Payment → Kitchen gibi sınır ötesi işlemler için tutarlılık, telafi/uzlaşma ve başarısızlık durumları iş akışında belgelenir. Otomatik POST retry varsayılanı yoktur.
- Sipariş başarılı yanıtı, kalıcı kayıt başarısı doğrulanmadan dönmez; mail/notification başarısızlığı siparişin varlığını çelişkili göstermemelidir.
- Garson/mutfak panellerindeki eşzamanlı güncellemeler, kayıp güncelleme ve stale state durumlarına karşı test edilir.

## 7. Ödeme ve finansal veri

- Ödeme sağlayıcısı ve ödeme mimarisi seçilmeden **ödenmiş gibi gösteren sahte production ödeme akışı** kurulmaz.
- Tam kart numarası, CVV/CVC, manyetik şerit/3-D Secure sırrı **Restofy sunucusu, log, veritabanı veya analytics'e kaydedilmez**. Mümkün olduğunda sağlayıcının hosted/tokenized akışı kullanılır; PCI DSS yükümlülüğü kullanılan entegrasyon modeline göre değerlendirilir.
- Tutar/para birimi/order sahipliği server-side doğrulanır; client'ın gönderdiği 'payment succeeded' ifadesi hiçbir zaman kanıt sayılmaz.
- Ödeme sonucu yalnız güvenilir sağlayıcı doğrulaması veya imzalı webhook ve doğrulanmış işlem durumu üzerinden kabul edilir. Webhook imzası, timestamp/replay koruması, eşsiz event/transaction id ve idempotency zorunludur.
- Kısmi/çift ödeme, iptal, iade ve başarısız ödeme durumları için açık state machine ve uzlaştırma kuralları gerekir.
- Payment Service, erişim tokenlarını/sağlayıcı kimlik bilgilerini diğer servislere açmaz; cevaplarda yalnız iş gereği gereken güvenli alanlar bulunur.
- Nakit ödeme ile kart ödemesi iş akışları karıştırılmaz; ödeme onayı yetkili kişi veya sağlayıcıdan gelir.

## 8. Mikroservis, Gateway ve RestClient güvenliği

- Dış istemci istekleri tasarıma göre **API Gateway** üzerinden girer. Production'da yalnız gerekli edge/proxy/Gateway portları yayımlanır; `5051–5062`, iç PostgreSQL ve SonarQube dünyaya açık olmaz.
- Mevcut Docker ağındaki host adları ve `_SERVICE_URL` değerleri ENV ile belirlenir; `RestClient` çağrıları gerekli yerde doğrudan servise yapılır, Gateway'e zorunlu geri dönüş yoktur.
- İç servisler, client'ın eklediği `X-User-Id`, `X-Restaurant-Id`, `X-Role` gibi header'ları **kanıtsız güven kaynağı** sayamaz. Doğrulanmış kimlik/tenant bağlamı güvenli ve sahteciliğe dirençli mekanizmayla taşınır; servisler arası auth kararı Auth entegrasyonunda netleştirilir.
- `REST_CLIENT_CONNECT_TIMEOUT_MS` / `REST_CLIENT_READ_TIMEOUT_MS` mevcut yapılandırmalarından yararlanılır. Otomatik non-idempotent POST retry eklenmez; circuit breaker/queue gibi teknoloji ancak ölçülmüş ihtiyaçla gelir.
- SSRF: Server'ın istek attığı host/URI kullanıcı tarafından kontrol ediliyorsa allowlist, şema, çözümleme ve internal adres kontrolü uygulanır; iç servis adresleri sabit konfigürasyondan alınır.
- Gateway path/prefix standardı `/api/v1/` korunur. Gateway routing testi güvenlik/authorization testi değildir. Auth/CSRF/CORS eklenirken 43 route testinin yanı sıra yeni negatif testler yazılır.
- Servisler arasında ham JPA entity veya diğer servisin veritabanı nesnesi paylaşılmaz; API sözleşmeleri en az gerekli alanları açığa çıkarır.

## 9. PostgreSQL, Flyway ve veri ayrımı

- Tek PostgreSQL instance üzerinde veri gerektiren her servis kendi `restofy_*` database'ini kullanır; her database içinde `public` schema. **Başka servisin DB'sine doğrudan SQL/JPA erişimi yoktur.**
- Production'da DB sahibi/kullanıcı izinleri servis bazlı ve least privilege olacak şekilde oluşturulur. Ortak DEV kullanıcısı production izolasyonu için yeterli değildir.
- JPA/Hibernate production'da `spring.jpa.hibernate.ddl-auto=validate`; tablolar/indeksler Flyway migration ile değişir.
- Migration dosyası: `src/main/resources/db/migration/V1__description.sql` vb.; her serviste bağımsız version/history; uygulanmış migration sessizce değiştirilmez.
- `clean` production'da kapalı; `baseline-on-migrate` ve `out-of-order` onaysız açılmaz; checksum ve validation hataları fail closed. `repair`, `baseline`, `clean`, veri reseti veya volume silme **açık izin** gerektirir.
- PostgreSQL sorguları parametrik olmalı; dinamik sıralama, kolon ve filtre adları allowlist ile kısıtlanmalıdır.
- Kişisel veri saklama, retention/imha ve veritabanı backup/restore süreçleri belirtilir; üretim verisi DEV/teste maskesiz kopyalanmaz.

## 10. API kontratları, validation ve hata cevapları

- Her yeni endpoint için HTTP method, path, public/protected sınıfı, auth scope, tenant scope, DTO ve örnek başarı/hata durumları dokümante edilir.
- Her harici request **güvenilmez** kabul edilir. Jakarta Validation, uzunluk/aralık/enum/tür kuralları, JSON/body boyutu, pagination limitleri ve allowlist'ler sunucuda zorunlu olmalıdır.
- Frontend doğrulaması yalnız UX içindir; backend doğrulaması esas alınır. Boş/whitespace içerik, fiyat/miktar negatif değerleri, beklenmeyen alanlar ve yetki değişkenleri kontrollüdür.
- Hata response'larında stack trace, SQL, iç hostname, filesystem path, token, cookie, secret, personel PII bulunmaz. Tutarlı Spring `ProblemDetail` veya projece kararlaştırılan tek hata sözleşmesi kullanılır.
- **401** kimlik doğrulama eksik/geçersiz, **403** geçerli kimlikle yetki yok, **404** kaynak yok veya güvenli gizleme, **409** çakışma, **422/400** doğrulama semantiğine göre, **429** rate limit; kararı API standardında tutarlı uygula.
- Swagger/OpenAPI development/testte `API_DOCS_ENABLED` ile açılabilir; production'da varsayılan kapalıdır. Dokümana gerçek secret veya müşteri verisi örneği yazılmaz.

## 11. CORS, CSRF, browser ve güvenlik header'ları

- CORS explicit origin allowlist ile yönetilir; credential ile `*` birleştirilmez. İzinli method/header listeleri ihtiyaçla sınırlıdır. Origin doğrulaması yetkilendirme sayılmaz.
- Cookie auth kullanılıyorsa CSRF için origin/referrer ve token politikası kararlaştırılıp state-changing endpointlerde test edilir. GET işlemleri state-changing olmamalıdır.
- Production HTTPS/TLS, uygun HSTS, `X-Content-Type-Options: nosniff`, Content Security Policy (`frame-ancestors` dahil), Referrer-Policy ve Permissions-Policy değerlendirilir; Next.js için CSP nonces/hashes planı gerçek rendering ile test edilir.
- XSS'e karşı React'in varsayılan escape davranışı korunur; kullanıcı içeriği `dangerouslySetInnerHTML` ile sunulmaz. HTML sanitization gerekiyorsa tanımlı ve test edilmiş çözüm kullanılır.
- İçerik güvenliği: cookie taşıyan private ekranlar ve sipariş/müşteri yanıtları cache kontrolü bakımından incelenir (`private` / `no-store` gerektiğinde); paylaşımlı CDN cache tenant verilerini karıştırmamalıdır.
- Redirect, external link ve QR hedeflerinde açık yönlendirme, kötü amaçlı schema ve `target="_blank"` riskleri engellenir.

## 12. Rate limiting, kötüye kullanım ve gerçek istemci IP'si

- Login/kayıt/parola sıfırlama, QR erişim ve sipariş denemeleri, ödeme başlatma, ödeme webhook'u, iletişim formu ve admin mutasyonları ayrı risk profilleriyle sınırlandırılır.
- Rate limit yalnız frontend'de değil backend/edge'de uygulanır; 429 ve gerekirse `Retry-After` döner. Limitler kullanıcıya/restorana/masaya/IP'ye göre doğrulanmış kimlikle ve adil şekilde seçilir; IP tek başına her senaryo için yeterli değildir.
- Reverse proxy arkasında istemci IP'si sadece **güvenilir proxy** zincirinden okunur; herkese açık `X-Forwarded-For` doğrudan güvenilmez.
- Tek PostgreSQL instance/13 servis kaynak tüketimini göz önünde bulundurarak bağlantı havuzları sınırlıdır; kullanıcı talepleri ile sınırsız bağlantı, bellek, kuyruk veya dosya boyutu büyümesi önlenir.
- DDoS ve yoğun kullanım testleri, gerçek production güvenlik kontrollerini devre dışı bırakmadan gerçekleştirilir.

## 13. Dosya/görsel yükleme ve QR üretimi

- Menü ürünü görseli, restoran logosu vb. yüklemeler ancak özellik kararıyla eklenir; kullanıcı filename/path hiçbir zaman storage anahtarı olarak doğrudan kullanılmaz.
- Dosya uzantısı ve istemcinin `Content-Type` değeri tek doğrulama değildir: MIME/signature, boyut, çözünürlük/piksel sayısı, yükleme kotası, içerik türü ve yetki doğrulanır; SVG/HTML aktif içerik riskleri değerlendirilir.
- Public/private storage ayrımı yapılır. Sahiplik/tenant kontrolü her dosya okuma/değiştirmede gerekir. `nosniff`, güvenli `Content-Disposition`, uygun cache policy uygulanır.
- QR üretirken güvenilir domain, encode, HTTPS, metin boyutu ve dışa aktarma/yeniden oluşturma kuralları uygulanır. QR içinde secret gömülmez; revoke/rotate gerekiyorsa süreç dokümante edilir.

## 14. Veri mahremiyeti, KVKK, analitik ve loglama

- Müşterinin adı, telefon/e-posta, sipariş geçmişi, ödeme bilgileri ve masa hareketleri amaçla sınırlı işlenir. Restoran işletmecisi ile platformun veri sorumlusu/veri işleyen rolleri kullanım modeline göre **hukuken değerlendirilecek**; otomatik varsayılmayacaktır.
- KVKK aydınlatma, gerekliyse açık rıza, çerezler, saklama ve imha, başvuru kanalı ve yurt dışı aktarım yükümlülükleri `web-rules.md` ile koordine edilir.
- Zorunlu cookie ile isteğe bağlı analitik/marketing cookie ayrılır. Gerekiyorsa izin verilmeden isteğe bağlı takip başlatılmaz; ret/geri alma erişilebilir olmalıdır. Hukuken gerekmediğinde göstermelik consent banner kurulmaz.
- Analytics'e QR token, masa oturum kimliği, müşteri adı/telefonu, sipariş detayları, ödeme kimlik bilgileri veya tam QR URL gönderilmez.
- Log ve audit: sipariş durum değişikliği, ödeme olayı, yetki değişikliği, yönetici işlemi ve şüpheli başarısız giriş gibi eylemler mümkün olduğunca olay kodu + gerekli ID'lerle kaydedilir; şifre/token/cookie/ham ödeme verisi loglanmaz.
- Hangi personelin hangi siparişi ne zaman değiştirdiği gibi finansal/operasyonel audit gereksinimleri tasarlanır; log erişimi tenant ve yönetici yetkilerine göre sınırlanır.
- Backup/restore, retention, ihlal bildirimi ve kullanıcı veri talepleri için production öncesi süreç tanımlanır; hukukla ilgili maddeler işletmeye ve mevzuata göre teyit edilir.

## 15. Docker, network ve production

- DEV Compose ile production erişilebilirlik sınırları ayrılır. Frontend şu anda host üzerinde çalışır; frontend'e kendiliğinden Dockerfile veya Compose servisi ekleme.
- Production'da yalnız HTTPS reverse proxy/Gateway ve gerekli public frontend erişimi bulunur; backend servisleri, PostgreSQL, Actuator detayları, SonarQube ve yönetici izleme endpointleri doğrudan internete açılmaz.
- Dockerfile image'ları minimum runtime bağımlılığı içerir; `.dockerignore` ile gerçek `.env*`, log, node_modules ve build artifaktları yanlışlıkla image'a girmez.
- Servisler için health/readiness, CPU/RAM, JDBC pool, bağlantı limiti, disk ve hata logları izlenir. Health response secret veya iç yapı topolojisini açığa çıkarmaz.
- Container mümkün olduğunda non-root çalışır; image/scanner ve bağımlılık güncelleme politikası release öncesi doğrulanır.
- Yedek ve geri yükleme test edilmeden production migrasyonu veya veri kaybı riski taşıyan değişiklik yapılmaz.

## 16. Test, güvenlik denetimi ve kabul kapıları

- Her işlevin test matrisi en az normal kullanım + hatalı input + anonim/yanlış rol + **başka restoran verisi** + eşzamanlılık/idempotency uygun olan durumları kapsar.
- Gateway route HTTP testleri, RestClient timeout/transport testleri ve gerçek PostgreSQL Flyway testleri mevcut altyapı kanıtlarıdır; business yetki testlerinin yerine geçmez.
- API/JPA validation/security/controller/integration testleri ile frontend Vitest/RTL; akış olgunlaştığında Playwright E2E ve erişilebilirlik taramaları kullanılır.
- OWASP ASVS / OWASP API Security Top 10 / OWASP ZAP gibi test kaynakları **izinli test ortamlarında** ve kapsam belirlenerek kullanılır; yanlış pozitifler ve gerçek kanıt ayrılır.
- `pre-push` manuel kalite kapısıdır; raporu kullanıcı çalıştırır. SonarQube ayrı ve opsiyoneldir; yokluğu tek başına geliştirmeyi durdurmaz.
- Production çıkışını durduracak örnekler: auth bypass, tenant IDOR/BOLA, QR üzerinden yetkisiz başka masa yazması, çift tahsilat, webhook forgery, secret leak, doğrudan dışa açık DB/internal service, doğrulanmamış yüksek riskli bağımlılık ve geri alınamaz veri kaybı.
- Bir kontrol henüz uygulanmadıysa **NOT IMPLEMENTED**, test edilmediyse **NOT VERIFIED** olarak açık raporlanır. Şu anda çalışan mimari diye production güvenlik onayı verilmez.

## 17. Ajan çalışma ve değişiklik onay kuralları

- Her ajan önce gerçek repository ve bu belgeyi okur; planı uygulanmış özellik olarak göstermeden önce kodu/testleri doğrular.
- Yeni public endpoint, auth/cookie/CSRF/CORS/RBAC/tenant modeli, servis arası güvenlik, ödeme sözleşmesi, migration/DB kullanıcıları, gizli ENV ve production ağ topolojisini değiştirmeden **önce onay** ister.
- Kimlik doğrulama, bir testin geçmesi için kapatılamaz. Test-only fixture gerçek üretim kontrollerini yanıltamaz.
- Her tamamlanan API için şu teslim bilgisi istenir: method/path, public/protected, rol + restoran scope, request/response DTO, validation, örnek güvenli input, hata statüleri, test kanıtı, ENV/migration değişiklikleri.
- Güvenlik problemi bulunursa şifre/token sızdırmadan risk, etki, tekrar üretme koşulları ve çözüm yaklaşımı raporlanır; özel güvenlik bildirimi tercih edilir.

## 18. Özellik tamamlama güvenlik kontrol listesi

- [ ] Secret/sensitive ENV sızıntısı yok; yeni ENV onayı ve `.env.example` uyumu var.
- [ ] Public/protected endpoint matrisi güncel ve deny-by-default uygulanıyor.
- [ ] Authentication ve gerekiyorsa CSRF sunucuda test edildi.
- [ ] Restaurant tenant izolasyonu; cross-tenant IDOR/BOLA testleri PASS.
- [ ] Rol/izin kontrolü hem servis sınırında hem API düzeyinde test edildi.
- [ ] QR masa kimliği ve yazma yetkisi güvenilir şekilde bağlandı; replay/spam riski değerlendirildi.
- [ ] Sipariş fiyatı/toplamı yalnız backend'de hesaplandı; stok/durum yarış koşulları test edildi.
- [ ] Gerekli sipariş/ödeme mutasyonlarında idempotency ve duplicate testleri PASS.
- [ ] Ödeme sağlayıcı doğrulaması ve webhook imza/replay kontrolleri varsa PASS.
- [ ] DB sorguları parametrik, Flyway migration güvenli, diğer servis DB erişimi yok.
- [ ] Validation, uygun HTTP hata kodları ve hassas veri içermeyen `ProblemDetail` var.
- [ ] CORS, CSRF, güvenli cookie ve güvenlik header'ları uygun.
- [ ] QR/table/payment özel linkleri sitemap, SEO, analytics ve loga sızmıyor.
- [ ] Loglarda şifre/token/cookie/ödeme kartı/özel PII yok; audit ihtiyacı karşılanıyor.
- [ ] Önemli HIGH/CRITICAL dependency riskleri değerlendirildi; production ön koşulları sağlandı.
- [ ] Backend/frontend/ilgili E2E testleri ve `pre-push` gerçek sonuçları raporlandı.
- [ ] Henüz implemente edilmemiş / doğrulanmamış kontroller açıkça işaretlendi.

**Temel ilke:** Restoran, masa, personel, sipariş ve ödeme yetkisi her kritik istekte **backend tarafından** doğrulanır. Kolaylık, geliştirme hızı veya UI davranışı güvenlik sınırını zayıflatamaz.
