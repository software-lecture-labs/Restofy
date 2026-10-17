# RESTOFY — MENU SERVICE BACKEND / FAZ 2 UYGULAMA PLANI

> **Belge:** Codex / yazılım geliştirme ajanı için uygulanabilir `PLAN.md`  
> **Servis:** `menu-service` — **Order Service değildir**  
> **Faz:** **MENU-02 — Ürün Yönetimi**  
> **Sorumlu:** Merve  
> **Kapsam:** **Yalnızca Faz 2: dört ana task × dört zorunlu alt adım = 16 alt adım**  
> **Repository:** `/Users/mervecemre/qr-management-system` (gerçek dizini doğrula)  
> **Geliştirme durumu:** MENU-01 uygulanmış ve V61 DEV Menu veritabanına geçirilmiştir; işe başlamadan **gerçek güncel kod ve DB** doğrulanacaktır.

## 0. AJANIN ANA GÖREVİ

Bu planı baştan sona oku. **Yalnız MENU-02 fazını uygula.** Önce gerçek `menu-service` kodunu, tamamlanmış MENU-01 kategori modelini, repo güvenlik kurallarını, Flyway sürümlerini ve testleri incele. Önceki fazın kodunu yeniden üretme veya değiştirmek için gerekçe arama; mevcut çalışan davranışları koru.

Sohbette **dört ana task ve bunların dörder alt adımından oluşan 16 maddelik Markdown checkbox listesi** göster. Kodlamayı TASK 2.1 → 2.2 → 2.3 → 2.4 sırasıyla yürüt. Her alt adım gerçekten tamamlanır tamamlanmaz güncel listeyi sohbette **yeniden** göster, yalnız tamamlanan satırı `[x]` yap, yapılan değişikliği ve kontrol kanıtını anlat, bir sonraki adımı duyur. Faz sonunda rapor vererek **dur**. Açık kullanıcı onayı olmadan MENU-03'e, MENU-04'e veya başka servis koduna geçme.

**Asla `git add`, `git commit`, `git push`, `git merge`, `git rebase`, tag/release veya deploy yapma.** **Pre-Push'u veya SonarQube tam taramasını çalıştırma:** kullanıcı bu kalite kapılarını kendisi manuel çalıştırıyor. Bağımsız Menu/Maven unit–integration testleri, derleme ve JaCoCo ölçümü yapabilirsin; sonucu gerçek komut ve exit code ile bildir. Test atlama, sahte PASS, güvenliği geçici gevşetme, coverage threshold düşürme yasaktır.

## 1. PROJE VE KAYNAK ÖNCELİĞİ

Restofy; her restoranın menüsünü, siparişlerini, mutfağını, QR oturumlarını, ödemesini ve yönetimini kendi güvenli bağlamında işleten mikroservis sistemidir. Altı geliştirici 12 iş servisini paylaşır; ayrıca API Gateway vardır. **Menu Service**, kategori, ürün, fiyat/vergi tanımları ve güncel ürün satışa uygunluğu kayıtlarının sahibidir. Auth kimlik/rol, Order sipariş snapshot'ı, Kitchen mutfak hazırlığı ve stokta yok komutu, Rating ürün puanı için kendi bağımsız servisleridir. Bu fazda onların kodunu yazma.

Önce repository'nin gerçek sürümünü/özelliklerini oku. Kaynak çatışmasında öncelikler:

1. `docs/gereksinimler/Restofy_Nihai_Gereksinimler.docx` içindeki **nihai normatif ürün kararları**.
2. `docs/SECURITY.md` **zorunlu güvenlik kuralları**; ilgili ürün kararının güvenli uygulanması gerekir.
3. `docs/decisions/migrations.md` ve gerçek Flyway history/migration dosyaları.
4. `docs/requirements.md`, `docs/architecture.md`, `docs/database.md`, `docs/er-diagram.md`, `docs/folder-structure.md` mevcutsa: **çoğu önerilen hedef tasarımdır, uygulanmış kod kanıtı değildir**.
5. Bu dosyadaki Faz 2 görevlendirmesi. Belirsizlikte iş kuralı uydurma: belgeler arası farkı belirt, güvenlik veya veri modelini etkileyen kararda onay iste.

İncelemen gereken temel yollar:

- `README.md`, `backend/pom.xml`, `backend/services/menu-service/pom.xml`.
- `backend/services/menu-service/src/main/java/com/restofy/menu/` ve `src/test/`.
- `backend/services/menu-service/src/main/resources/db/migration/`; önceki **V61** migration ve ilişkili entity/repository/controller/testler.
- `backend/services/api-gateway` yalnız mevcut `/api/v1/menu/**` yönlendirmesini **oku**.
- `docker-compose.yml`, `backend/test-support/application-test.properties`, `.env.example` yalnız mevcut yapılandırmayı **oku**.
- `docs/SECURITY.md`, `docs/decisions/migrations.md`, geçerli tüm ilgili Restofy gereksinimleri.

Ön incelemede raporla: aktif branch ve çalışma ağacındaki korunacak değişiklikler; mevcut kategori API davranışı; Menu DB migration history (DEV DB'ye bağlanmak güvenliyse); güncel ürün entity/migration var mı; ilk **boş** V61–V70 sürümü; test çalışma yöntemi; Auth/tenant yetki sınırı. İhtiyaç olmayan başka servis, frontend, Gateway, Compose, shared config, gerçek `.env`, kök POM dosyalarını **değiştirme**.

### Fazın uygulama kapsamı

1. Ürün oluşturma, güncelleme, mantıksal silme/pasife alma.
2. Ürünü kategori/alt kategori ile doğru ve tenant güvenli ilişkilendirme.
3. Ürünün aktifliği, müşteri yayını/görünürlüğü ve satışa uygunluğunu birbirinden ayrı yönetme.
4. Ürün fiyatı ve zorunlu temel özellikler için doğru server-side validasyon.

**Bu fazda yapılmayacaklar:** ürün görseli upload altyapısı; içerik/malzeme seçenekleri CRUD; kalori/gramaj/hazırlık süresinin kapsamlı detay API'leri; müşteri için public ürün arama; Kitchen'ın gerçek HTTP/event entegrasyonu; Rating'in canlı ortalama puanı; Order/Payment işlemleri; frontend. Faz 2 şeması ileriki fazların alanlarını içerebilir, ancak bunu özelliklerin bittiği şeklinde raporlama.

## 2. FAZ 1 İLE UYUMLULUK VE DB POLİTİKASI

- MENU-01 sonunda `categories` ve V61 DEV ortamında oluşturulmuştur. **`V61__...sql` dosyasını asla düzenleme, silme, yeniden adlandırma veya numarasını değiştirme.** Mevcut 89 kategori testinin ve %95,58 Menu Service coverage ölçümünün raporlanan eski baz değerler olduğunu unutma: yeni kod nedeniyle yeni ölçüm alınmalıdır.
- Menu Service migration aralığı **V61–V70**; sıradaki gerçekten boş sürümü seç (muhtemelen `V62`, ancak kesin karar gerçek dosya/history incelemesiyle). `V62__create_menu_products.sql` yalnızca olası addır. Her faz için sabit migration sayısı yoktur.
- `spring.jpa.hibernate.ddl-auto=validate` korunur. Şemayı Hibernate `create/update` ile üretme; DDL için Flyway kullan.
- DB migration **yalnız Menu DB'sinde** yapılır; başka servis DB'sine FK/JOIN/SQL erişimi yoktur. Aynı Menu DB'deki kategori → ürün ilişkisi tenant-uyumlu bileşik FK ve/veya eşdeğer DB koruması içermelidir.
- **Canlı DEV DB migration uygulanması, Docker konteynerinin yeniden oluşturulması, veri reseti, yeni ENV sözleşmesi, güvenlik modelinin değiştirilmesi veya mevcut migration üzerinde müdahale için ÖNCEDEN açık kullanıcı onayı iste.** Testcontainers'ta geçici test veritabanına migration uygulamak bu onayın yerine geçmez; güvenli test olarak yapılabilir.
- `flyway clean`, `repair`, `baseline`, `out-of-order`, `DROP/TRUNCATE`, volume silme, `git reset --hard` ve `git clean -fd` izinsiz yasaktır.
- V61 sonrası eski imaja otomatik geri dönüşün Flyway validate sorununa takılabileceği biliniyor; otomatik restore veya rollback yapma. Etkilenecek varlıkları önceden raporla.

## 3. ZORUNLU TASARIM KARARLARI VE SINIRLAR

### 3.1 Yetkilendirme ve restoran izolasyonu

Tüm yönetim mutasyonları yalnız **doğrulanmış ADMIN + restoran üyeliği** ile gerçekleştirilir; kategori ve ürün okuması da ilgili güvenilir tenant sınırından geçer. Kullanıcı isteğindeki `restaurantId`, `role`, `X-Restaurant-Id` veya `X-User-Id` yetkinin kaynağı olamaz. Faz 1'deki fail-closed actor/authorization sınırını **koru**. Auth Service henüz bağlanmamışsa gerçek canlı CRUD endpoint'leri 401 kalabilir; testlerde kontrollü ve sadece test profili actor kullan, production'a `permitAll`/mock admin ekleme. 

API rotası oluştururken Gateway dış `/api/v1/menu` prefix'inin serviste nasıl eşleştiğini teyit et; çift prefix veya yetkisiz public route yaratma. Uygulamada `401` doğrulanamayan istek, `403` doğrulanmış yetkisiz istek, `404` kaynak bulunmaması/tenant gizleme, `409` version/duplicate çatışması, `400/422` şema ve iş kuralı validasyonu olarak tutarlı kullan. Stack trace, SQL, credential veya iç host bilgilerini istemciye döndürme.

### 3.2 Önerilen ürün veri modeli (gerçek kaynak ve Faz 1 şemasıyla teyit ederek uygula)

| Alan | Amaç ve kural |
|---|---|
| `id` | UUID PK; tarihte değişmez |
| `restaurant_id` | UUID NOT NULL; tenant kapsamı |
| `category_id` | UUID NOT NULL; **tek FK ile doğrudan seçilen kategoriye** bağlanır; bağlı parent `categories.parent_id`'den bulunur |
| `name` | Zorunlu, trim edilmiş, boş olmayan, güvenli uzunluk (öneri 200) |
| `description` | Opsiyonel; ayrıntılı kullanım Faz 3'te |
| `base_price` | `numeric(19,4)` / Java `BigDecimal`, 0 veya üzeri; para hesaplamasında floating point yok |
| `currency` | ISO 4217 üç harfli kod; uygulanabilir kod politikası kararı doğrula |
| `vat_rate` | `numeric(7,4)`, yüzde 0–100; ülkeye göre oran uydurma |
| `price_includes_vat` | Boolean; KDV dahil ibaresi için açık bilgi |
| `is_active` | İşletme ürünü arşivledi/pasifleştirdi mi? Soft state; tarihsel id saklanır |
| `is_published` | Müşteriye gösterilmeye aday mı? `false` gizli/taslak |
| `version` | Optimistic lock; stale update → anlamlı `409` |
| `created_at`, `updated_at` | `timestamptz`; UTC anları |
| `image_url`, `weight_grams`, `calories`, `estimated_prep_minutes` | Gelecek Faz 3 için nullable alanlar; mevcut kaynak kararlarıyla uyumluysa şemada yer alabilir, ancak bu fazda fonksiyonlar tamamlandı sayılmaz |

**Tek kategori referansı** esastır: `category_id` ana kategoriye veya alt kategoriye bağlanabilir; `categories.parent_id` üzerinden ana/alt ayrımı türetilir. Aynı üründe birbirini tutmayabilecek `category_id` + `subcategory_id` gibi iki bağımsız FK icat etme. Müşteride üst kategori filtresi doğrudan ona bağlı ürünleri ve altında aktif alt kategorilerin ürünlerini kapsayacak şekilde sonraki faza hazırlanır. Bu politikanın ürüne özel ayrıntısını dokümante et.

`categories` tablosunda V61'in oluşturduğu alanlar (ör. generated `depth`, `parent_depth`) varsa tek kaynak olarak bunları kullan; var olduğunu önceden varsayma. `(restaurant_id, category_id)` çocuk FK'si, `categories(restaurant_id,id)` ebeveyn anahtarı ve gerçek V61 şemasıyla uyumlu kurulmalı.

### 3.3 Üç farklı durumun karıştırılmaması

**İşletme aktifliği:** `products.is_active` (pasif/arşivli → müşteri satın alamaz).  
**Yayın/görünürlük:** `products.is_published` (gizli/taslak → müşteriye görünmez).  
**Operasyonel erişilebilirlik:** `product_availability.status`:

- `AVAILABLE` — yayın/aktiflik gibi diğer koşullar da sağlanırsa siparişe uygun;
- `OUT_OF_STOCK` — stokta yok;
- `TEMPORARILY_CLOSED` — geçici kapalı.

Bu üç alan birbirini **değiştirmez**. Availability tablosunda kayıt bulunmaması **AVAILABLE kabul edilmez; fail closed**. Yeni ürün için uygulama transaction'ı içinde availability satırı oluşturulur; ürün başlangıçta taslak/gizli olsa bile `AVAILABLE` yalnız operasyonel uygunluğu ifade eder, yayın izni değildir. Kullanıcıdan talep edilmeden ürünü otomatik public yayına alma. `is_published` ve `is_active` başlangıç değerlerini kaynak ve ürün kararıyla açıklaştır; belirsizse **güvenli taslak varsayılanı** seçip belirt.

**Sistem yeniden başladığında kapalı ürün otomatik açılmaz.** Faz 2'de temel `product_availability` gerçeği Menu DB'de kalıcı tutulur. Kitchen üzerinden kapatma/açma entegrasyonu, idempotent `commandId` geçmişi ve detaylı history Faz 4 konusudur; Faz 2'de bunları varmış gibi ilan etme. Bu fazda admin ürün durumu mutasyonları için gerekçe, aktör ve zaman tasarımı audit ihtiyacıyla uyumlu olmalı; history planını aşamalara göre açıkça belirt.

### 3.4 Servis katmanları ve HTTP kuralları

Faz 1'in mevcut paket desenini koru: `api/controller`, `api/dto/request|response`, `api/exception`, `application/service`, `domain/entity|enums`, `infrastructure/persistence/repository|security`. Business doğrulamayı controller'a doldurma. `@Transactional` yalnız kendi Menu DB'sini kapsar. DTO'larda client'ın id, restaurantId, actor, createdAt, version gibi alanlarla yetki yükseltmesine izin verme; sürüm çakışmasını kontrollü işle. Dinamik sıralama alanları allowlist, pagination boyutu üst sınırlı, tüm DB sorguları parametrik olmalı.

Önerilen (henüz gerçek olduğu varsayılmayan) yönetim API yüzeyi — gerçek Gateway/service route standardına uyarla:

| İşlem | Yöntem ve örnek relatif yol | Önerilen sonuç |
|---|---|---|
| Ürün oluştur | `POST /products` | 201 + ürün response |
| Ürün güncelle | `PATCH /products/{id}` | 200 veya 204 |
| Ürün arşivle/pasifleştir | `PATCH /products/{id}/active` veya mevcut update sözleşmesi | 200 veya 204 |
| Ürün detay | `GET /products/{id}` | 200 / güvenli 404 |
| Yönetim ürün listesi | `GET /products?categoryId=&isActive=&page=&size=` | 200, paginated |
| Yayın durumunu güncelle | `PATCH /products/{id}/publication` veya kontrollü update alanı | 200 / 409 |
| Yönetim availability değişimi | `PATCH /products/{id}/availability` | 200 / 409; yetkili actor |

Sırf tabloyu geniş göstermek için gereksiz endpoint üretme; Faz 1 konvansiyonunu esas al. Bu fazda **public müşteri ürün endpoint'i açma**.

## 4. MENU-02 — DÖRT TASK / ON ALTI ALT ADIM

### TASK 2.1 — Ürün oluşturma, güncelleme, pasife alma

- [ ] **2.1.1** MENU-01 kategorilerinin şemasını, ürünle ilgili mevcut dosya/tabloları ve Flyway history'yi incele; bir sonraki kullanılabilir Menu migration sürümünü doğrula.
- [ ] **2.1.2** `products` şeması, tenant uyumlu kategori FK'si, UUID, fiyat/KDV alanları, `is_active`, `is_published`, optimistic version, zaman alanları ve indeksler için **yeni** Flyway migration oluştur; JPA entity/repository eşlemesini geliştir.
- [ ] **2.1.3** Doğrulanmış ADMIN + restaurant context ile ürün oluşturma/güncelleme/arşivleme (hard delete değil) use-case, DTO ve controller iş akışını uygula; önceki API hata biçimine uy.
- [ ] **2.1.4** Gerçek PostgreSQL/Testcontainers migration+CRUD; soft delete; validation; cross-tenant; yetkisiz; stale version; önceki kategori API regresyon testlerini çalıştır.

**Kabul:** Ürün id stabil ve geçmiş için korunur; başka tenant'ın ürünü okunamaz/değiştirilemez; V61 checksum değişmez; yeni migration başka DB'ye dokunmaz. Hard-delete ile referansları koparma yoktur.

### TASK 2.2 — Ürünleri kategori ve alt kategorilerle ilişkilendirme

- [ ] **2.2.1** Ürün `category_id` ilişkisinin ana kategori veya alt kategoriye bağlanma politikasını Faz 1'in iki katmanlı hiyerarşisiyle tutarlı biçimde yazılı hale getir.
- [ ] **2.2.2** Ürün create/update sırasında seçilen kategorinin varlığını, doğrulanmış restaurant ile aynı tenant'ı, doğru kategori tipini ve geçiş kurallarını sunucu tarafında doğrula; tenant uyumlu FK ile destekle.
- [ ] **2.2.3** Yönetim ürün detayında seçilen kategori ve varsa üst kategori bilgisini döndür; yönetim ürün listesi için ana/alt kategoriye göre filtreleme, deterministic sort ve sınırlı pagination uygula.
- [ ] **2.2.4** Başka restoran kategorisi, olmayan parent, pasif kategori, yeniden kategori seçimi, stale version, parent/child ilişki değişimi ve filtre sonuçları için negatif/API/PostgreSQL testlerini bitir.

**Kabul:** Ürün farklı tenant kategorisine bağlanamaz. Pasif kategori ile ürün yönetim kaydı ve müşteri yayını ayrı ele alınır; bu fazın yönetim API'si pasif kaydı yetkili kullanıcıya gösterebilir. Pasif bir kategoriye yeni ürün bağlamanın yasaklanıp yasaklanmadığı ürün kararı açık değilse muhafazakâr seçimi raporla, sessizce iş kuralı uydurma.

### TASK 2.3 — Ürün aktifliği, görünürlük ve satışa uygunluk durumları

- [ ] **2.3.1** Ürün aktif/pasif (`is_active`), yayın/gizli (`is_published`) ve operasyonel availability (`AVAILABLE`, `OUT_OF_STOCK`, `TEMPORARILY_CLOSED`) eksenlerini ayrı modelle; eksik availability kaydını satışı engelleyen durum olarak ele al.
- [ ] **2.3.2** Yeni ürün oluşturma transaction'ında `product_availability` satırını güvenli varsayılanla yaz; Menu DB'deki tek yetkili yazarı koru. Var olan uygulanmış migration'a dokunmadan gereken yeni tablo/indeks/kısıtları uygun boş migration sürümüyle ekle.
- [ ] **2.3.3** Yetkili yönetim API/use-case'lerinde ürün pasifleştirme/yeniden aktifleştirme, yayınla/gizle ve satışa açık/kapalı durumlarını ayrı güncelle; uygun neden, değiştiren aktör, değişim zamanı ve version kontrolünü uygula. Kitchen'ın canlı entegrasyonuna girme.
- [ ] **2.3.4** Üç eksenin kombinasyon matrisi, pasif parent/child, eksik availability, restart sonrası durumun korunması, aynı ürüne yarışan update ve yetkisiz/cross-tenant işlemler için unit + PostgreSQL testleri uygula.

**Kabul:** Gizlemek ürünü fiziksel silmez; pasifleştirmek availability'yi açmaz; availability `OUT_OF_STOCK` durumunda kalıcıdır. Faz 4 Kitchen komutu ve history/idempotency entegrasyonu **bekliyor** olarak raporlanır.

### TASK 2.4 — Ürün fiyatı ve temel özellik validasyonu

- [ ] **2.4.1** Ürün ad, açıklama (opsiyonel), `BigDecimal base_price`, `currency`, `BigDecimal vat_rate`, `price_includes_vat` request/response ve JPA alanlarının tip/NULL/precision eşleşmesini tamamla.
- [ ] **2.4.2** Trim/whitespace, zorunlu alan, maksimum uzunluk, 0 ve negatif fiyat politikası, aşırı büyük/overflow tutarlar, 3-harf para kodu, 0–100 KDV yüzdesi, null/NaN benzeri geçersiz istemci girdileri için güvenilir sunucu/DB kontrolü uygula; geçerli vergi oranı icat etme.
- [ ] **2.4.3** Tek tip güvenli hata (`ProblemDetail` veya mevcut sözleşme), `400/422/404/409`, DTO mass assignment engeli, audit ve version tutarlılığı, para hesabında `float/double` kullanılmaması ve validasyon mesajlarını doğrula.
- [ ] **2.4.4** Tüm yeni unit/MockMvc/PostgreSQL regresyon testleri ve taze JaCoCo line coverage ölçümünü çalıştır; 4 taskın kanıtlarıyla Faz 2 kapanış raporunu hazırla, ardından DUR.

**Kabul:** Backend fiyat/KDV kaynağıdır; istemci mali veri veya tenant değeriyle kuralları aşamaz. Projede en az %80 line coverage kalite eşiğini koru; hedefe ulaşılamazsa güvenliği veya kapsamı düşürmeden eksik iş senaryolarının testlerini geliştir.

## 5. HER ADIMDA SOHBETTE CHECKBOX PROTOKOLÜ

**Zorunlu format:** İlk mesajda şu listenin **tamamı boş** gösterilir. Her tamamlanan alt adımdan sonra **yeni sohbet mesajında** en azından dört ana task ve aktif taskın tüm alt adımlarının güncel hali; tercihen **tüm 16 satır** gösterilir. Buradaki kutuları kullanıcı görmeden yalnız dosyada değiştirmen yeterli değildir.

```markdown
### MENU-02 / FAZ 2 — ANLIK İLERLEME
- [ ] TASK 2.1 — Ürün CRUD ve soft delete
  - [ ] 2.1.1 Repo/migration analizi
  - [ ] 2.1.2 Şema/entity/repository
  - [ ] 2.1.3 Use-case/DTO/controller
  - [ ] 2.1.4 CRUD ve güvenlik testleri
- [ ] TASK 2.2 — Kategori ilişkilendirme
  - [ ] 2.2.1 Bağlama politikası
  - [ ] 2.2.2 Tenant ve kategori kontrolü
  - [ ] 2.2.3 Filtreleme ve response
  - [ ] 2.2.4 İlişki/tenant negatif testleri
- [ ] TASK 2.3 — Durum eksenleri
  - [ ] 2.3.1 Active/publication/availability ayrımı
  - [ ] 2.3.2 Availability şema ve transaction
  - [ ] 2.3.3 Yetkili durum komutları
  - [ ] 2.3.4 Restart/matrix/concurrency testleri
- [ ] TASK 2.4 — Validasyon ve kapanış
  - [ ] 2.4.1 Tipler ve DTO eşleşmesi
  - [ ] 2.4.2 Sayısal/metinsel doğrulamalar
  - [ ] 2.4.3 Hata/actor/audit sınırları
  - [ ] 2.4.4 Tam Faz 2 test/coverage ve rapor

Son işlem: ...
Değiştirilen dosyalar: ...
Test kanıtı: komut + PASS/FAIL/NOT RUN + sayılar ...
Sıradaki adım: ...
Engeller/onay gereken işlemler: ...
```

**İşaretleme disiplini:** `BLOCKED`, `NOT VERIFIED`, `NOT RUN`, `FAIL` alt adımları `[ ]` kalır. Bir test geçmeden bitmiş sayma. Ara aşamada faz dışına taşan mimari karar gerektiren durumlarda özel onay iste; faz sonu onayı beklerken bir sonraki fazın kodunu yazma.

## 6. KALİTE, TEST VE KORUMA

- Testlerden önce test planını belirt; iş kuralları, olumlu ve olumsuz API/DB durumları, cross-tenant, rol, optimistic locking ve boş/taşkın fiyat sınırlarına gerçek test yaz.
- PostgreSQL/Testcontainers DB kısıtlarını doğrulamak için tercih edilir; H2 sonucu PostgreSQL kanıtı sayılmaz.
- Menu kapsamına özel Maven `test`, `verify`, `jacoco:report` gibi komutları gerektiğinde çalıştır; `-DskipTests` ile başarı elde etme. Başlangıçtaki **89 test, %95,58** geçmiş Faz 1 değeridir; yeni sonuç yeniden ölçülür.
- **Pre-Push ve SonarQube genel taramasını AJAN ASLA çalıştırmayacak.** Kullanıcı bu komutları kendisi çalıştıracaktır. Mevcut `pre-push/` ve `sonarqube/` dosyalarına dokunma. Gerekiyorsa yalnız elle çalıştırma talimatı ver.
- Auth hazır değilse canlı ürün CRUD hâlâ 401 olabilir: bu güvenlik tasarımıdır. MockMvc/Testcontainers ile test edilen CRUD'yi canlı Auth bağlantısı olarak göstermeye çalışma. Kimliği bypass etme, security filtrelerini kapatma.
- Migration/Compose/DEV container yeniden çalıştırma gerekiyorsa önce güncel DB ve yedek durumunu açıkla ve kullanıcıdan onay al; onay gelmeden uygulama.
- `README.md` veya dokümanda gerekli değişiklik gerekiyorsa kapsamı açıkça bildir, servise özel dokümanla sınırla, varsayılan mevcut test/güvenlik dosyalarını bozma.

## 7. FAZ 2 ÇIKIŞ KRİTERLERİ (DEFINITION OF DONE)

- [ ] Ürün oluşturma, güncelleme ve güvenli pasife alma/archive çalışıyor.
- [ ] Ürün → kategori/alt kategori tek ve doğru tenant FK ile ilişkilendiriliyor.
- [ ] `is_active`, `is_published`, `product_availability.status` farklı anlamlara sahip ve kalıcı.
- [ ] Müşteriye açık olmayacak ürünler için salt iş kuralı temeli hazır; **public menü endpoint'i Faz 4'e bırakılmış**.
- [ ] Fiyat, para birimi, KDV ve ürün adı validation testlerle kanıtlanmış.
- [ ] Önceki MENU-01 testleri regresyonda PASS; güncel JaCoCo ölçümünde hedef ≥%80 veya eksik açık.
- [ ] Yeni migration dosyaları V61–V70 içinde; **V61 checksum ve geçmiş değiştirilmedi**.
- [ ] Üretimde fail-closed Auth/tenant güvenliği korunmuş.
- [ ] Tüm kapsam dışı Kitchen/Rating/Order entegrasyonları dürüstçe `PENDING / NOT VERIFIED` olarak ayrılmış.
- [ ] Pre-Push/SonarQube **ajan tarafından çalıştırılmadı**; Git commit/push **yapılmadı**.
- [ ] Kullanıcıya rapor sunuldu ve **MENU-03 için açık onay bekleniyor**.

## 8. FAZ SONU RAPOR FORMATI

Ajan aşağıdaki alanları doldurmadan fazı koşulsuz tamamlandı ilan etmemeli:

1. **Özet:** MENU-02 TASK 2.1–2.4 ve 16 alt adımın durumları (DONE / BLOCKED / NOT VERIFIED).
2. **Kod değişikliği:** eklenen/değiştirilen dosyalar ve gerekçeler; başka serviste değişiklik varsa ayrı açıkla.
3. **Migration:** gerçek sürüm dosya adları, Flyway testi ve **DEV DB uygulanıp uygulanmadığı**; V61'e dokunulmadığı.
4. **API kontratı:** yöntem, path, yetki, restaurant kapsamı, request/response, durum kodları.
5. **Ürün modeli:** category FK, üç durum ekseni, soft delete, sürüm/audit davranışı.
6. **Testler:** gerçek komut, toplam/başarılı/başarısız/atlanan test sayısı ve PostgreSQL kontrolleri.
7. **Coverage:** yeni JaCoCo toplamı ve Menu Service yüzdesi; %80 eşik karşılaştırması.
8. **Güvenlik:** çapraz tenant/rol/idempotency/concurrency negatif testleri ve canlı Auth sınırı.
9. **Devreye alma:** Docker veya DEV migration uygulanmadıysa bunu açık bildir; uygulanmışsa izin/tarih/sonuç.
10. **Risk ve açık kararlar:** Auth/Order/Kitchen/Rating bekleyen bağlantılar, politika belirsizlikleri.
11. **Git ve manuel kalite:** Git add/commit/push yapılmadı; Pre-Push/SonarQube tam tarama kullanıcıya bırakıldı.
12. **Kapanış:** `MENU-02 için rapor tamamlandı; MENU-03'e geçmiyorum. Onayınızı bekliyorum.`

## 9. KULLANICI TARAFINDAN AJANA GÖNDERİLECEK BAŞLANGIÇ KOMUTU

> `RESTOFY_MENU_SERVICE_FAZ_2_MENU02_TAM_PLAN.md` dosyasını eksiksiz oku. Önce mevcut Restofy repository, MENU-01/V61, SECURITY.md ve migration history'yi doğrula. Yalnız MENU-02'deki 4 task / 16 alt adımı uygula. Her biten alt adımdan sonra güncel checkbox listesini **chat'te tekrar** yaz, gerçek test sonuçlarını bildir ve sıradaki adıma geç. Onay gerektiren DB/ENV/güvenlik/Compose değişikliğinde dur. **Pre-Push veya SonarQube tam taramasını çalıştırma; ben manuel çalıştıracağım.** **Git add/commit/push/merge/deploy yapma.** Başka servislere veya frontend'e dokunma. TASK 2.4 sonunda genel rapor verip dur; MENU-03'e ancak açık onayımla geç.
