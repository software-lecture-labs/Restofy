# Restofy — Yapay Zekâ Ajanları İçin Çalışma Talimatları

> **Konum:** Repository ana dizini (`/AGENTS.md`).  
> **Kapsam:** Restofy üzerinde çalışan tüm yapay zekâ kodlama ajanları ve tüm proje dizinleri.  
> **Amaç:** Projenin gerçek durumunu anlamak, belgelenmiş kararlara uymak ve gereksinim uydurmadan ya da tamamlanmamış özellikleri uygulanmış göstermeden kodu güvenli biçimde değiştirmek.

## 1. Zorunlu dokümantasyon okuma — HER İŞTEN ÖNCE

**Her yeni ajan oturumunun başında**; planlama, kod oluşturma/düzenleme, bağımlılık yükleme, migration üretme veya mimari önerme işlemlerinden **önce**, aşağıdaki dosyaların **tamamını belirtilen sırayla oku:**

1. `docs/SECURITY.md` — zorunlu güvenlik kuralları, onay gerektiren işlemler, gizli bilgiler, tenant izolasyonu ve güvenli çalışma.
2. `docs/requirements.md` — ürün kapsamı, kesinleşmiş iş kuralları, mevcut eksikler ve açık kararlar (`D-01`–`D-15`).
3. `docs/architecture.md` — mikroservis sorumlulukları, servisler arası iletişim, iş akışları ve tutarlılık sınırları.
4. `docs/database.md` — servislere ait veritabanları, entity'ler, önerilen şemalar, kısıtlar ve transaction ilkeleri.
5. `docs/er-diagram.md` — mantıksal ilişkiler ve servis bazlı ER diyagramları; fiziksel `FK` ile servisler arası mantıksal `REF` ilişkilerini ayırt et.
6. `docs/folder-structure.md` — mevcut repository yapısı ve backend/frontend kodlarının yerleştirilmesi gereken konumlar.
7. `docs/decisions/migrations.md` — Flyway sürüm aralıkları ve sorumlu geliştiriciler için **esas alınacak kaynak**. Bu dosya doğrudan `docs/` altında değil, `docs/decisions/` altındadır.
8. `docs/web-rules.md` — frontend, kullanıcı deneyimi, erişilebilirlik, SEO, gizlilik, çoklu dil ve yayın kuralları; arayüzü etkileyen backend değişiklikleri için de geçerlidir.

**Bu dosyaları yalnızca listeleme veya yüzeysel okuma.** Kullanılan araç çıktıyı kısaltıyorsa kalan bölümleri küçük parçalar hâlinde oku. Zorunlu belgelerden biri eksikse ya da okunamıyorsa hangi dosyanın eksik olduğunu ve bunun etkisini bildir; içeriğini tahmin ederek kural uydurma. Yeni oturumda veya dokümanlar değiştiğinde güncel dosyaları yeniden oku. Aynı oturumda her yeni görev için ilgili bölümleri tekrar incele.

Dokümanlardan sonra, herhangi bir değişiklik yapmadan önce ilgili **gerçek kaynak kodu**, yapılandırmayı, veritabanı migration dosyalarını ve testleri incele. Belgeler hem mevcut altyapıyı hem de gelecekte önerilen mimariyi anlatır: **Bir özelliğin belgelenmiş olması, uygulanmış olduğu anlamına gelmez.**

## 2. Dokümanlar ve uygulama arasında karar verme kuralları

- Kullanıcının güncel ve açık görevini yerine getir; isteğin kapsamını kendiliğinden genişletme.
- `docs/SECURITY.md`; güvenlik, onay ve veri koruma kuralları için zorunludur. Başka bir dokümana uymak veya bir testi geçirmek amacıyla bu kuralları gevşetme.
- Kesinleşmiş ürün davranışlarında `docs/requirements.md` içindeki `[KARAR]` olarak işaretlenmiş normatif kuralları ve nihai gereksinim belgesini esas al. `[ÖNERİ]` ifadesi **teklif** anlamına gelir; onaylanmış özellik değildir. `[AÇIK]` ve `D-01`–`D-15` maddeleri, belgelenmiş yeni bir karar bulunana kadar çözümlenmemiş kabul edilir.
- Flyway **sürüm aralıkları ve sorumlular** konusunda `docs/decisions/migrations.md`, analiz belgelerindeki eski bilgilerden önceliklidir. Güncel dağılımda **Auth Service V1–V10** aralığına sahiptir; tarihli bir analizde Auth aralığının bulunmadığı yazsa bile güncel karar dosyası geçerlidir.
- Şu anda neyin mevcut olduğunu veya hangi testlerin geçtiğini belirlemek için güncel kodu incele ve ilgili testleri gerçekten çalıştır. Eski `[MEVCUT]` tespitleri zamanla geçersiz hâle gelebilir.
- Kaynaklar arasında önemli bir çelişki varsa çelişkinin ne olduğunu açıkça belirt, güvenli/mevcut davranışı koru ve yeni mimariyi veya ürün kuralını kendiliğinden seçmek yerine açık karar iste.
- `docs/web-rules.md` içindeki bazı eski referanslar `.agents/SECURITY.md` konumunu gösterir. Bu repository için geçerli güvenlik belgesi **`docs/SECURITY.md`** dosyasıdır.

## 3. Proje bağlamı ve mimari sınırlar

Restofy, **mikroservis monorepo** yapısına sahip, QR tabanlı ve birden fazla restoranı destekleyen restoran yönetim platformudur.

- **Backend:** Java 25, Spring Boot 4.1.1 ve Maven. `backend/services/` altında **API Gateway** ve **kendine ait veritabanı bulunan 12 servis** yer alır: Auth, Admin, QR, Menu, Order, Kitchen, Waiter, Payment, Notification, Rating, Mail, Timer.
- **Gateway:** Spring Cloud Gateway Server **Web MVC** kullanır; rotalar `/api/v1/...` yapısındadır. Gateway'in iş veritabanı yoktur. Bir rotanın tanımlı olması, çalışan iş endpoint'inin bulunduğu anlamına gelmez.
- **Servisler arası HTTP iletişimi:** Uygun yerlerde Spring `RestClient` kullanılır. Feign, mesaj aracısı (broker), service discovery, SSE/WebSocket veya outbox dağıtım mekanizmasının hâlihazırda uygulandığını varsayma.
- **Veri:** PostgreSQL 17 kullanılır. Tek PostgreSQL sunucusunda, veri sahibi her servis için **ayrı veritabanı** vardır. Şema değişikliklerini Flyway yönetir; production ortamında JPA/Hibernate için `ddl-auto=validate` kullanılır.
- **Frontend:** Next.js 16 App Router, React, TypeScript, Tailwind CSS ve `next-intl` üzerinden TR/EN desteği. `frontend/src/app`, `frontend/src/features` ve `frontend/src/shared` arasındaki sınırları koru.
- **Geliştirme ve dağıtım:** Mevcut Docker Compose ve yerel frontend düzenine uy. Kubernetes, mesaj aracısı, production altyapısı veya yeni servisler varmış gibi davranma; kendiliğinden ekleme.

**Veri sahipliği (service data ownership) kurallarını uygula:**

- **Auth:** Personel kimlikleri, restoran üyelikleri ve üzerinde uzlaşılmış authentication modeli. **Admin:** Restoran ayarları.
- **QR:** Masalar, QR kimlik bilgileri, masa/guest oturumları ve masa kapanışı koordinasyonu.
- **Menu:** Kategoriler, ürünler, fiyatlar/seçenekler ve ürünün satışa uygunluğuna ilişkin yetkili kayıt. Kitchen, bu durumu ikinci bir veritabanı gerçeği olarak tutmak yerine değişiklik talebini Menu'ye iletir.
- **Order:** Siparişler, ürün/fiyat/vergi snapshot'ları, iptal kararları ve servis edildi bilgisi.
- **Kitchen:** Sipariş bazlı hazırlık (`WAITING → PREPARING → READY`), onaylanmış iptal olaylarını uygulama, fire ve mutfak raporları. V1'de ürün başına ayrı hazırlık durumu yoktur. Servis edilen sipariş Kitchen'da `READY` kalır.
- **Waiter:** Garson çağrıları. Sipariş teslimi ve masa kapanışı, sırasıyla Order ve QR'ın yetki alanındaki komutlardır.
- **Payment:** Ödeme denemeleri, kanıta dayalı tahsilat kayıtları, callback'ler ve uzlaştırma. İstemcide başarı sayfası gösterilmesi **ödeme kanıtı değildir**.
- **Notification:** Bildirim gönderimi ve alıcı bazında okundu durumu. **Rating:** Doğrulanmış değerlendirme uygunluğu ve değerlendirmeler. **Mail:** E-posta işleri ve gönderim kayıtları. **Timer:** Wi-Fi kontrol cihazı entegrasyonu plan aşamasındadır; çalışıyormuş gibi gösterilmez.

Başka bir servisin veritabanını doğrudan sorgulama veya veritabanları arasında fiziksel foreign key oluşturma. Servisler arası kimlikler, yetkili API/olay sözleşmeleriyle doğrulanan mantıksal referanslardır. Başka servislerin JPA/domain entity'lerini paylaşma; gerektiğinde sürümlenmiş DTO/olay sözleşmelerini kullan. Tenant (`restaurant_id`), rol, nesne sahipliği, idempotency ve transaction sınırlarını tasarıma dâhil et. `@Transactional`, **servisler arası işlemleri atomik hâle getirmez**.

Kodun bakımını kolaylaştırmak için SOLID, KISS, DRY ve YAGNI ilkelerine uy. Somut ihtiyaç bulunmadıkça `Interface`/`ServiceImpl` çiftleri veya tekrar eden soyutlama katmanları üretme. Uygulamayı farklı bir mimari tarza dönüştürmek yerine mevcut klasör yapısını koru.

## 4. Flyway ve veritabanı değişikliği protokolü

**Herhangi bir servisin migration dosyasına dokunmadan önce `docs/decisions/migrations.md` dosyasını oku.** Bu dosya, servis sürüm aralıkları ve sorumluları için güncel tek kaynaktır (örneğin Auth V1–V10, Mail V11–V20). Başka servisin numaralandırmasından veya eski analizden sürüm aralığı tahmin etme.

- Yalnız ilgili servisin şu dizininde çalış: `backend/services/<service-name>/src/main/resources/db/migration/V<version>__<description>.sql`.
- Kullanılmamış ve ilgili servise ayrılmış bir sürüm seçmeden önce o servisin **mevcut** migration dosyalarını ve erişim varsa `flyway_schema_history` kayıtlarını incele. Sürüm aralıkları ekip içi iş dağılımıdır; **her servisin bağımsız Flyway geçmişi** vardır, tek bir global sürüm zinciri yoktur.
- Daha önce uygulanmış migration dosyalarını değiştirme, yeniden numaralandırma, silme veya yeniden yazma. Gerekirse izin verilen numarayla yeni migration ekle. Aralık dolmuşsa veya numara çakışıyorsa ekip kararı için bildir.
- Şema değişikliklerinin sahibi Hibernate `update`/`create` değil, Flyway'dir. `ddl-auto=validate` ayarını koru.
- Açık izin olmadan `flyway clean`, `repair`, `baseline`, yıkıcı migration, `docker compose down -v`, veri sıfırlama veya veritabanı volume silme işlemi yapma.
- PostgreSQL kısıtlarını ve migration'ları ilgili entegrasyon testleriyle doğrula. Markdown ER diyagramlarını çalıştırılabilir DDL olarak kabul etme.

## 5. Güvenlik ve önceden onay gerektiren işlemler

`docs/SECURITY.md` belgesinin tamamı geçerlidir. Asgari, vazgeçilmez kurallar şunlardır:

- **Varsayılan olarak erişimi reddet.** Authentication, rol, aktif restoran/tenant, guest/masa sahipliği ve nesne sahipliği kontrollerini, Gateway'in arkasında olsa bile **verinin sahibi backend servisinde** yap.
- Tarayıcıdan gelen `restaurantId`, rol, fiyat, ödeme durumu veya kimlik bildiren keyfî header değerlerine yetki kaynağı olarak güvenme. Restoranlar arası yetkisiz erişime (IDOR/BOLA) izin verme.
- Gizli bilgileri Git'te izlenen dosyalardan, kaynak koddan, frontend bundle'ından, migration SQL'lerinden, örneklerden ve loglardan uzak tut. Kimlik doğrulama tokenlarını tarayıcının `localStorage`, `sessionStorage` veya IndexedDB alanında saklama.
- Yalnızca bir özelliği veya testi çalıştırabilmek için güvenlik filtrelerini, CORS/CSRF kurallarını, rate limit'leri, yetkilendirmeyi, test assertion'larını veya coverage kurallarını zayıflatma.
- Ödeme, sipariş oluşturma, webhook işleme ve masa kapanışında idempotency, eşzamanlılık ve sonucu belirsiz işlemler dikkatle ele alınmalıdır.
- `docs/SECURITY.md` gereği **önce açık onay iste**: Güvenlik sözleşmesi değişiklikleri, yeni public endpoint'ler, auth/tenant/payment/QR yetki tasarımı, yeni/değişen ENV değişkenleri veya sırlar, servis kimliği/güven ilişkileri, production ağ ayarları, veritabanı yetkileri/yıkıcı işlemler ve görev kapsamı dışındaki mimari değişiklikler.
- Kullanıcının açık izni olmadan `git add`, commit, push, merge, tag/release, deploy, repository ayarı değiştirme veya veri silme yapma. Ajan otomatik Git hook oluşturmamalıdır.

## 6. Her geliştirme görevi için çalışma akışı

1. **Anla:** Zorunlu dokümantasyonu (Bölüm 1) oku; ilgili gereksinimleri, açık kararları, sorumlu servisleri ve kod/testleri incele.
2. **Kapsamı belirle:** Doğru çözüm için gereken en küçük uygulama kapsamını çıkar. API sınırlarını, migration'ları, ENV etkilerini, güvenlik kurallarını, eşzamanlılık durumlarını ve gerekli onayları tespit et. Çözümlenmemiş ürün kararlarını kesinleşmiş gibi uygulama.
3. **Uygula:** İlgili servis/feature sınırında kal; mevcut adlandırma, `/api/v1` API düzeni, validation, hata yönetimi, mevcut bağımlılıklar, TR/EN çeviriler ve belgelenmiş tasarım sistemine uy.
4. **Test et:** Gerektiğinde odaklı unit, integration, güvenlik/tenant, negatif senaryo ve eşzamanlılık testleri ekle veya güncelle. Kullanılabilir araçlarla API, Flyway/PostgreSQL, frontend davranışı ve kritik kullanıcı akışlarını doğrula. **Çalıştırmadığın testi geçmiş gibi gösterme.**
5. **Gözden geçir:** Etkilenen dokümanların ve sözleşmelerin tutarlı olduğundan emin ol. Belgeleri **yalnız izin verilen görev kapsamında** güncelle; ürün kararlarını sessizce yeniden yazma. Güvenlik ve sürüm aralıklarını tekrar kontrol et.
6. **Raporla:** Değişen dosyaları, davranışı, sorumlulukları, migration/ENV/API etkilerini, gerçekten çalıştırılan testleri ve sonuçlarını, kalan riskleri ve doğrulanamayan noktaları özetle. **IMPLEMENTED (UYGULANDI)**, **NOT IMPLEMENTED (UYGULANMADI)** ve **NOT VERIFIED (DOĞRULANMADI)** durumlarını ayırt et.

### Yerel kalite kontrolleri

Repository'de, otomatik değil **manuel çalıştırılan** bir yerel pre-push süreci belgelenmiştir:

- **Windows:** `.\pre-push\pre-push.cmd full dev`
- **macOS/Linux:** `sh ./pre-push/pre-push.sh full dev`

Geçerli davranışları için gerçek `pre-push/` scriptlerini ve rehberini kontrol et. `SAFE TO COMMIT`, **yalnızca çalıştırılmış kontrollerin** durumunu belirtir; production'a hazır olunduğunu garanti etmez. Sunulan `docs/SECURITY.md` ve `docs/web-rules.md` belgelerinde SonarQube şu anda pre-push'a gömülü değil, **bağımsız/isteğe bağlı** olarak açıklanıyor. Gerçek scriptleri veya onaylanmış daha yeni bir kararı doğrulamadan aksini söyleme.

## 7. Ajanın son kontrol listesi

Bir görevin tamamlandığını söylemeden önce şunları kontrol et:

- [ ] `docs/decisions/migrations.md` dâhil sekiz zorunlu Markdown belgesinin tamamı okundu.
- [ ] Mevcut uygulama, önerilerden tahmin edilmek yerine gerçek kod üzerinden incelendi.
- [ ] Açık ürün kararlarından hiçbiri sessizce doldurulmadı.
- [ ] Güvenlik, restoran/guest izolasyonu, servis veri sahipliği ve API sözleşmeleri korundu.
- [ ] Veritabanı değişikliklerinde doğru servis/sürüm kullanıldı ve uygulanmış migration'lar korundu.
- [ ] İlgili testler gerçekten çalıştırıldı veya çalıştırılamama nedenleri dürüstçe belirtildi.
- [ ] İzinsiz commit, push, deploy, ENV değişikliği veya yıkıcı işlem yapılmadı.

**Temel kural: Önce oku, kodla karşılaştırarak doğrula, yalnız yetkilendirilen kapsamı uygula, değişiklikleri test et ve gerçek durumu raporla.**
