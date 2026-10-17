# RESTOFY — MENU SERVICE BACKEND / FAZ 1 UYGULAMA PLANI

> **Dosya türü:** Codex / yapay zekâ kodlama ajanı için yürütülebilir `PLAN.md`  
> **Servis:** `menu-service` (Order Service **değil**)  
> **Faz:** `MENU-01 — Kategori ve Alt Kategori Yönetimi`  
> **Sorumlu:** Merve  
> **Kapsam:** **Yalnız 1 faz, 4 ana task ve 16 zorunlu alt adım**  
> **Repository:** `/Users/mervecemre/qr-management-system` (gerçek konumu doğrula)  
> **Durum:** Başlanmadı; yapılmış gibi işaretleme.

## 0. AJANA KESİN TALİMAT

Bu belgeyi uygulamaya başlamadan önce baştan sona oku. **Yalnızca MENU-01 fazını uygula.** Önce gerçek repository ve güvenlik/migration durumunu incele. Dört ana task ve bunların dörder alt adımını **sohbet içinde Markdown checkbox listesi** olarak göster; çalışma boyunca her tamamlanan alt adımdan sonra **güncel listeyi yeniden sohbete yaz**, kutucuğu `[x]` yap, hangi dosyaların değiştiğini ve hangi testin gerçekten çalıştığını belirt, ardından sıradaki alt adıma geçtiğini açıkça duyur. Yalnız en sonda toplu tik atmak yasaktır.

Dört task tamamlandığında **test kanıtlarına dayalı Faz 1 kapanış raporu** üret ve **DUR**. MENU-02'ye veya başka servise kullanıcıdan açık onay almadan geçme. **Asla `git add`, `git commit`, `git push`, `git merge`, `git rebase`, release/tag veya deploy yapma.** `git reset --hard`, `git clean -fd` ve veri kaybettiren komutları da uygulama. Hiçbir testi çalıştırmadan `PASS` yazma.

## 1. PROJE BAĞLAMI VE FAZ SINIRI

Restofy, restoran bazlı (multi-tenant) QR menü ve sipariş platformudur. 6 geliştirici 12 iş servisini paylaşır; ayrıca API Gateway bulunur. İlk aşamada her geliştirici kendi ilk servisini bağımsız olarak geliştirir; servis bağlantıları sonradan yapılır. **Bu plan yalnız Menu Service'in kategori altyapısını geliştirir.**

| Kişi | Birinci servis | İkinci servis |
|---|---|---|
| Kerem | Auth | Mail |
| Alper | QR | Payment |
| Hamza | Order | Notification |
| **Merve** | **Menu** | Timer |
| Nisa | Kitchen | Rating |
| Umut | Waiter | Admin |

**MENU-01 teslim kapsamı:**

1. Ana kategori oluşturma, güncelleme ve aktif/pasif yapma.
2. Alt kategori oluşturma, güncelleme ve aktif/pasif yapma.
3. Alt kategoriyi **aynı restoranın** ana kategorisine bağlama; geçersiz/döngüsel ilişkiyi engelleme.
4. Yönetim amaçlı kategori/alt kategori listeleme ve detay API'leri; filtre/sıralama/sayfalama.

**Bu fazda YAPILMAYACAK:** Ürün CRUD, fiyat/KDV, malzeme, ürün görseli, ürün availability, Kitchen/Rating/Order entegrasyonu, müşteri ürün listesi, frontend, Auth Service'in kendisi, Gateway mimarisinin yeniden yazılması, ikinci fazın migration'ı veya endpointleri. Müşteriye gösterilecek **aktif kategori ağacı için yalnız read-model tasarımı** yapılabilir; public HTTP erişiminin açılması ayrıca security/onay kuralına tabidir. Kategori pasifleştirmesi çocukların fiziksel durumunu kendiliğinden değiştirmemeli; gelecek müşteri menüsü, pasif ata kategorilerin alt ürünlerini dışlayacaktır.

## 2. KOD YAZMADAN ÖNCE ZORUNLU İNCELEME

Şu gerçek dosya ve yolları repository içinde bul ve içeriğini kontrol et (dosya yoksa açıkça belirt, varmış gibi davranma):

- `README.md`, `backend/pom.xml`, `backend/services/menu-service/pom.xml`.
- `backend/services/menu-service/src/main`, `src/test`, `src/main/resources` ve mevcut migration dosyaları.
- `docs/gereksinimler/Restofy_Nihai_Gereksinimler.docx`; varsa `Restofy Servisler.docx` ve `Restofy Gereksinimler Yapı.docx`.
- `docs/SECURITY.md`, `docs/decisions/migrations.md`.
- Varsa `docs/requirements.md`, `docs/architecture.md`, `docs/database.md`, `docs/er-diagram.md`, `docs/folder-structure.md`.
- `docker-compose.yml`, `postgres/init-databases.sh`, `.env.example`, `backend/test-support/application-test.properties`.
- `backend/services/api-gateway` içindeki gerçek `/api/v1/menu` route'u ve testleri.
- `ops/` ve `pre-push/` dokümantasyonu (varsa).

Kullanıcı tarafından sağlanan analizlere göre **doğrulanması beklenen, henüz varsayılmayacak** teknoloji: Java 25, Spring Boot 4.1.1, Maven multi-module, PostgreSQL 17, Spring Cloud Gateway Server Web MVC, JPA/Hibernate `ddl-auto=validate`, Flyway. Servis dizini `backend/services/menu-service`, paket kökü `com.restofy.menu`, Gateway ön eki `/api/v1/menu` olabilir; **gerçek kod esas alınır**.

**Migration kuralı:** Menu Service'e ayrılan Flyway aralığı `V61–V70`'tir. Önce kendi Menu DB'sindeki ve repo içindeki mevcut sürümleri kontrol et; boş ilk sürüm `V61__create_menu_categories.sql` **önerisidir, otomatik varsayım değildir**. Bu aralık on migration oluşturmak zorunluluğu getirmez. Kullanılmış migration dosyasını değiştirme, yeniden numaralandırma veya `repair/baseline/clean` yapma. Üretim SQL'ini yalnız `backend/services/menu-service/src/main/resources/db/migration/` altında yönet; test fixture dosyalarını migration olarak kopyalama.

Ön inceleme mesajında şunları yaz: repo yolu, görülen sürümler, mevcut Menu kodu/tabloları, ilk uygun migration sürümü, mevcut güvenlik modelinin durumu, varsa blokajlar ve **4 taskın tam checkbox listesi**. Bu inceleme **beşinci task değildir**.

## 3. GÜVENLİK VE MİMARİ — PAZARLIKSIZ KURALLAR

### 3.1 Değiştirilebilecek dosyalar

Varsayılan yazma alanı: **yalnız** `backend/services/menu-service/**` altındaki kaynak/test/migration ve servise özel gerekli dokümantasyon. Diğer mikroservislerin kaynakları, `api-gateway`, kök/parent POM, Docker/Compose, `postgres/`, gerçek `.env` dosyaları, `docs/SECURITY.md`, mevcut ortak yapılandırma **izinsiz değiştirilmez**. Gerekirse talep edilen değişikliği ve gerekçesini raporla, onay bekle.

Yeni ENV anahtarı, dışa açık endpoint, rol/auth/tenant güven modeli değişikliği, Gateway/Compose düzenlemesi veya mevcut veritabanına migration uygulanması gibi `docs/SECURITY.md` tarafından özel onaya bağlanan işlemlerde **ilgili değişiklikten önce açık onay** iste. Planın verilmiş olması her tehlikeli işlemin otomatik izni değildir. `flyway clean/repair/baseline`, DROP/TRUNCATE, volume reset, gerçek veriyi silme **yasak; özel açık izin olmadan deneme**.

### 3.2 Tenant/rol izolasyonu

Her kategori için `restaurant_id UUID NOT NULL` ve bütün okuma/yazma sorgularında gerçek doğrulanmış restoran kapsamı kullan. Kullanıcının verdiği `restaurantId`, `X-Restaurant-Id`, `X-Role`, `X-User-Id` başlıklarına güvenip yetki verme. Mutasyonlar yalnız doğrulanmış **ADMIN** rolüne ve **aynı restoran üyeliğine** açık olmalıdır. Auth Service henüz hazır değilse `AuthorizationPort` / `CurrentRestaurantContext` benzeri güvenli sınır oluştur; üretimde `permitAll`, sabit admin veya production'a taşınan test tokenı ile endpointi açık bırakma. Testlerde yalnız test profiline özel güvenli actor enjekte et. Canlı kimlik doğrulama kararlaştırılmadıysa `BLOCKED / NOT VERIFIED` belirt; hiç değilse unit ve izinli test-contract senaryolarını gerçekleştir.

Başka restoranın kategori ID'si GET, PATCH veya alt kategori parent ilişkisiyle erişilememeli. Cross-tenant erişim, IDOR/BOLA, yanlış rol, geçersiz veya eksik kimlik denemeleri için negatif test yaz.

### 3.3 Kategori hiyerarşisi ve veri modeli

**Bu faz için önerilen tek tablo:** `categories`.

| Alan | Beklenen tip / kural |
|---|---|
| `id` | `uuid`, PK, uygulamada üretilir (mevcut standarda uy) |
| `restaurant_id` | `uuid NOT NULL`, Admin DB'ye SQL FK değil mantıksal REF |
| `parent_id` | Nullable UUID, aynı **Menu DB** içindeki `categories` tablosuna parent FK |
| `name` | `varchar(150) NOT NULL`, trim/boş ad doğrulaması |
| `sort_order` | integer, kararlı sıralama |
| `is_active` | boolean, açık varsayılan ve kontrollü yaşam döngüsü |
| `version` | bigint optimistic locking |
| `created_at`, `updated_at` | `timestamptz`, tutarlı UTC anları |

- `parent_id IS NULL` ⇒ ana kategori. `parent_id IS NOT NULL` ⇒ alt kategori. **En fazla iki katman.**
- Ana kategorinin başka bir ebeveyni olamaz; alt kategorinin çocuğu olamaz; kendine ebeveynlik ve döngü yasaktır.
- `(restaurant_id,id)` benzersiz anahtar / aynı tenant'a parent bağlayan bileşik FK veya bunun **DB seviyesinde eşdeğer koruması** bulunmalı. Yalnız uygulama kontrolü yarışları önlemeyebilir.
- Aynı restoranda aynı ebeveyn altında normalleştirilmiş aynı kategori isminin tekrarına engel olmak önerilen tasarım kuralıdır; repo/iş kararı farklıysa açıklayıp karar notu ekle.
- Pasife alma **soft state** değişikliğidir; kayıtları hard-delete etmez, çocuk `is_active` bayraklarını otomatik değiştirmez.
- Kategori adını güncelleme, aktiflik ve parent ilişkilendirme için optimistic locking ve anlamlı `409 Conflict` testleri kullan.
- JPA entity'leri API response olarak döndürme; DTO ve katmanlı mimari uygula (`api/controller`, `api/dto`, `application/service`, `domain`, `infrastructure/persistence`). Gerçek repository'deki yerleşim farklıysa projeyi bozmadan uyum sağla.

### 3.4 Endpoint taslakları — gerçek route ile teyit et

Aşağıdakiler mevcut endpoint iddiası değil, **MENU-01 için önerilen API sözleşmesidir**. Dış Gateway yolu `/api/v1/menu` ile iç servis controller mapping'inin nasıl birleştiğini önce doğrula; çift prefix üretme.

| Method | Gateway'e göre göreli path | Yetki | Amaç |
|---|---|---|---|
| `POST` | `/categories` | ADMIN + restaurant | Ana kategori oluştur |
| `PATCH` | `/categories/{id}` | ADMIN + restaurant | Ana kategori güncelle / aktif-pasif |
| `GET` | `/categories` | ADMIN + restaurant | Yönetim ana kategori listesi |
| `GET` | `/categories/{id}` | ADMIN + restaurant | Kategori detayı |
| `POST` | `/categories/{parentId}/subcategories` | ADMIN + restaurant | Alt kategori oluştur |
| `PATCH` | `/subcategories/{id}` | ADMIN + restaurant | Alt kategori güncelle / aktif-pasif |
| `GET` | `/categories/{parentId}/subcategories` | ADMIN + restaurant | Kategoriye bağlı alt kategoriler |
| `GET` | `/subcategories/{id}` | ADMIN + restaurant | Alt kategori detayı (gerekirse) |

`GET /categories` için örnek parametreler: `isActive`, `parentId`/`level`, `page`, `size`, izinli `sort`. Zorunlu değilse gereksiz endpoint çoğaltma. İstemciden gelen sort alanlarını allowlist ile sınırla; makul pagination üst sınırı koy. Create `201`, GET `200`; update için projeye uygun `200/204`, validation `400/422`, auth `401`, yetki `403`, yok/tenant gizleme `404`, duplicate/sürüm `409`. Projedeki gerçek tekil hata sözleşmesini koru; SQL, stack trace veya secret ifşa etme.

## 4. FAZ 1 — DÖRT ANA TASK / ON ALTI ALT ADIM

**Başlangıçtaki tek aktif task: `MENU-01 / TASK 1.1`.** Önce inceleme, ardından sırayla uygula. Test kanıtı olmadan kutucuk işaretleme.

### TASK 1.1 — Kategori veri modeli ve ilk Flyway migration

- [ ] **1.1.1** Menu servisinin gerçek entity/repository/migration/tenant durumunu incele; `V61–V70` içindeki ilk boş sürümü doğrula.
- [ ] **1.1.2** `categories` UUID, tenant, parent, name, sort, active, version, zaman alanlarını; indeksleri ve veri bütünlüğü kurallarını tasarla.
- [ ] **1.1.3** Yalnız Menu DB için yeni Flyway SQL oluştur; tenant uyumlu parent FK, iki seviyeli hiyerarşi için uygulanabilir CHECK/transaction doğrulamalarını tasarla.
- [ ] **1.1.4** JPA entity/repository ve PostgreSQL/Testcontainers migration smoke testini uygula; Flyway + Hibernate `validate` uyumunu doğrula.

**Kabul:** Boş Menu DB'de migration geçmeli; aynı tenant'a parent kısıtı DB'de güvenilir biçimde korunmalı; başka DB değişmemeli. Gerçek PostgreSQL çalışmıyorsa bu test `NOT RUN`, ilgili task `NOT VERIFIED` kalır.

### TASK 1.2 — Ana kategori oluşturma, güncelleme ve aktif/pasif

- [ ] **1.2.1** Doğrulanmış ADMIN + restaurant bağlamı altında request/response DTO'larını ve validation'ı oluştur.
- [ ] **1.2.2** Ana kategori create/update/activate/deactivate use-case'lerini; ad normalizasyonunu, duplicate ve optimistic version kontrolünü uygula.
- [ ] **1.2.3** Controller/servis/repository bağlarını kur; HTTP response ve hata sözleşmesini standarda uygun uygula.
- [ ] **1.2.4** Pozitif/negatif testleri yaz ve çalıştır: boş/uzun ad, duplicate, farklı restoran, yanlış rol, kimlik eksik, yarışan update, `401/403/404/409`.

**Kabul:** Aynı restoranın yetkili ADMIN'i kategoriyi yönetebilir; başka restoran kullanıcısı okuyamaz/değiştiremez; pasifleştirme fiziksel silme değildir.

### TASK 1.3 — Alt kategori oluşturma, güncelleme ve ana kategori ilişkisi

- [ ] **1.3.1** `parent_id` temelli alt kategori create/update/activate/deactivate iş akışını ve DTO'larını geliştir.
- [ ] **1.3.2** Parent'ın aynı restoranda **ana kategori** olduğunu doğrula; self-parent, döngü, üçüncü seviye ve cross-tenant bağını reddet.
- [ ] **1.3.3** Ebeveyn değiştirme gerekiyorsa transaction + yarış/sürüm denetimi yap; desteklenmeyecekse açıkça belgelenmiş güvenli `400/409` davranışı kullan.
- [ ] **1.3.4** Aktif/pasif ve üst kategori durumları, yanlış/başka restoran parent'ı, duplicate ve stale version için unit/API/PostgreSQL güvenlik testlerini tamamla.

**Kabul:** Ana/alt kategori ilişkisi tutarlı ve tenant güvenlidir; ana kategori pasifleştirilince çocuk kayıtların `is_active` bayrağı otomatik değişmez.

### TASK 1.4 — Kategori / alt kategori listeleme ve detay API'leri

- [ ] **1.4.1** Yönetim ana/alt kategori listesi, aktiflik ve parent filtreleri, kararlı sıralama ve sınırlı sayfalama geliştir.
- [ ] **1.4.2** Yönetim detay DTO'larını üret; pasif kayıtları yalnız yetkili yönetim API'sinde göster, JPA entity'yi response'a açma.
- [ ] **1.4.3** Gelecekte müşteri menüsünde kullanılacak yalnız aktif kategori ağacı read-modelini servis katmanında tasarla; yeni public endpoint açmadan önce güvenlik/onay koşulunu uygula.
- [ ] **1.4.4** API kontrat, PostgreSQL filtre/listeleme, cross-tenant IDOR ve regresyon testlerini çalıştır; Faz 1 kapanış raporunu hazırla.

**Kabul:** Doğru restoran ve ebeveyn filtresiyle kararlı listeler, güvenli detay, boş sonuç, düzgün pagination, tenant sızıntısı olmaması.

### FAZ 1 ÇIKIŞ KRİTERLERİ

- 4 ana task ve 16 alt adım için gerçek uygulanmış kod/test kanıtı veya açık `BLOCKED / NOT VERIFIED` kaydı var.
- Kategori migration sürümü ve DB kısıtları doğrulanmış; schema/test fixture farkı anlaşılır.
- Yönetim CRUD/list/detail fonksiyonları doğru tenant ve rol sınırında.
- Geçersiz parent ilişkisi, duplicate ve eşzamanlı değişim testleri değerlendirildi.
- Önceden var olan Menu/ana repo testleri bozulmadı ya da sorunlar dürüstçe raporlandı.
- **Ürün, Order veya Kitchen koduna geçilmedi.**
- Faz sonu raporundan sonra **kullanıcının yeni faz onayı bekleniyor**.

## 5. SOHBETTE CANLI CHECKBOX PROTOKOLÜ

**Yalnız dosyada checkbox güncellemek yeterli değildir.** Gerçek chat mesajlarında ilerlemeyi göster. Her biten alt adımdan sonra **dört ana taskı ve aktif taskın alt adımlarını** yeniden yaz; dilersen bütün alt adımları sürekli göster. Bir önceki `[ ]` ancak **kod + doğrulama kanıtı** varsa `[x]` olabilir. Test için `NOT RUN`, sonuç başarısızsa `FAIL`, güvenlik bağımlılığında `BLOCKED` yaz ve `[ ]` bırak.

Örnek çıktı (yalnız biçim örneği; gerçek çalışmada boş başla):

```markdown
### MENU-01 — GÜNCEL İLERLEME
- [x] TASK 1.1 — Kategori veri modeli ve migration
  - [x] 1.1.1 Repo/Flyway analizi
  - [x] 1.1.2 Şema tasarımı
  - [x] 1.1.3 SQL migration
  - [x] 1.1.4 PostgreSQL + Hibernate validate testi: PASS
- [ ] TASK 1.2 — Ana kategori yönetimi
  - [x] 1.2.1 DTO/validation
  - [ ] 1.2.2 İş kuralları ← ŞU AN BURADAYIM
  - [ ] 1.2.3 Controller/API
  - [ ] 1.2.4 Negatif ve güvenlik testleri
- [ ] TASK 1.3 — Alt kategori yönetimi
- [ ] TASK 1.4 — Listeleme / detay

Tamamlanan adım: 1.2.1
Değişen dosyalar: ...
Kanıt / çalıştırılan test: ...
Sıradaki adım: 1.2.2
Engel / izin ihtiyacı: Yok / ...
```

Her mikro adım sonunda zorunlu olarak **(a)** güncel checkbox listesi, **(b)** ilgili dosyalar, **(c)** gerçekten çalıştırılan test/inceleme, **(d)** sıradaki alt adım, **(e)** varsa blokajı paylaş. Başlangıçta tüm kutular boş olmalı. Faz içinde kullanıcı kontrolü olmadan yeni faza geçme.

## 6. TEST, HATA VE TAMAMLANMA STANDARDI

- Controller'a iş kuralı gömme; API → application → domain → repository katman sorumluluklarını koru.
- Doğru DTO tipleri, Jakarta Validation, boşluk-only ad kontrolü, uzunluk limiti, negatif/hatalı sort/page/size sınırı.
- `restaurant_id` her sorguya uygulanır; yalnız verilen UUID'nin tahmin edilmez olması güvenlik kanıtı değildir.
- Parametreli sorgu ve sort allowlist; loglarda token, parola, session, gerçek ENV, PII veya iç sistem sırları yok.
- Cross-tenant parent FK, yanlış rol, duplicate create, concurrent update, kategori pasifleştirme ve kararlı listeleme mutlaka senaryolarda yer alır.
- `@Transactional` yalnız yerel Menu DB değişikliklerini kapsar; başka servis DB'sine erişme.
- Flyway `validate`, Hibernate schema validation ve gerçek PostgreSQL/Testcontainers testini tercih et; H2 testi PostgreSQL kanıtı değildir.
- Testler yalnız gerçekten çalıştırıldığında `PASS`. Docker/Testcontainers yoksa `NOT RUN` ve nedeni; isteğe bağlı geniş pre-push testini kullanıcı açıkça istemedikçe başlatma.
- Test kırığını gizlemek için security'yi kapatma, test silme, coverage'ı manipüle etme, çalışan kodu keyfî refaktör etme.
- Repo araçlarına göre örnek Maven komutu (önce doğrula): `cd backend && mvn -pl services/menu-service -am test`. Komutun gerçek çıktısını raporla.

Bir task ancak aşağıdakiler **kanıtlanırsa** `[x]` olur: iş kuralları uygulanmış, API/DTO sözleşmesi tutarlı, migration gerekiyorsa doğrulanmış, pozitif/negatif/tenant/rol testleri çalıştırılmış, ilgili dosyalar belirtilmiş, yeni güvenlik açığı veya doğrulanmamış kritik kısım gizlenmemiş. Kritik test çalıştırılamadıysa taskı koşulsuz `DONE` sayma.

## 7. FAZ 1 KAPANIŞ RAPORU — ZORUNLU ŞABLON

Bütün adımlar değerlendirilince şu raporu **sohbette** ver:

```markdown
# RESTOFY MENU SERVICE — MENU-01 FAZ SONU RAPORU

## Task Durumları
- [x/ ] 1.1 Kategori veri modeli ve migration — DONE / BLOCKED / NOT VERIFIED
- [x/ ] 1.2 Ana kategori CRUD ve aktiflik — DONE / BLOCKED / NOT VERIFIED
- [x/ ] 1.3 Alt kategori ve parent ilişkileri — DONE / BLOCKED / NOT VERIFIED
- [x/ ] 1.4 Listeleme/detay API'leri — DONE / BLOCKED / NOT VERIFIED

## Yapılan İşler ve Kabul Kanıtları
...

## Değişen Dosyalar
- dosya yolu — neden değişti

## API Sözleşmeleri
- Method, gerçek path, ADMIN/restaurant yetkisi, örnek istek/yanıt, hata kodları

## Flyway / PostgreSQL
- kullanılan V61–V70 sürümü, şema değişiklikleri, migration doğrulaması

## Çalıştırılan Testler
- tam komut, test sayısı, PASS / FAIL / SKIPPED / NOT RUN, hata özeti

## Güvenlik ve Tenant İzolasyonu
- ADMIN, restaurant bağlamı, başka restoran ID erişimi, açık blokajlar

## Riskler ve Bekleyen Kararlar
- Auth sözleşmesi, özel izin gerektiren migration/public route vb.

## Git Durumu
- git status (salt okunur özet)
- Commit: YAPILMADI
- Push: YAPILMADI
- Deploy: YAPILMADI

## Faz Sonucu
- 16/16 alt adım gerçekten tamamlandı mı? Hangileri BLOCKED/NOT VERIFIED?
- **MENU-02'ye geçmemi onaylıyor musun?**
```

**SON KURAL:** Raporu sunduğunda **DUR**. Kullanıcı onay verene kadar MENU-02 kodu, ürün migration'ı, başka servis değişikliği, commit/push/merge/deploy veya otomatik faz geçişi **KESİNLİKLE YOK**.

## 8. AJAN İÇİN TEK SATIRLIK BAŞLATMA KOMUTU

> `Restofy_Menu_Service_FAZ1_MENU01_PLAN.md` dosyasını tamamen oku ve uygula. Repository belgelerini ve güncel kodu doğrula; MENU-01'in 4 task / 16 alt adımını chat'te checkbox olarak göster. Sadece 1.1'den başla, her adımı test kanıtıyla `[x]` yaparken güncel listeyi yeniden yaz, sıradaki adımı bildir. Dört task sonunda rapor ver ve dur. **Commit/push/merge/deploy yapma; MENU-02'ye onaysız geçme.**
