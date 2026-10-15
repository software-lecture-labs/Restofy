# Restofy — Klasör Yapısı ve Yerleşim Rehberi

**Tarih:** 15 Ekim 2026. Kaynak: repository ve `Restofy_Nihai_Gereksinimler.docx`.  
`[MEVCUT]` incelenen dosya/klasörü, `[ÖNERİ]` geliştirme sırasında eklenecek yapıyı belirtir. Bu analizde uygulama klasörleri değiştirilmedi.

İlgili belgeler: [gereksinimler](requirements.md), [mimari](architecture.md), [veritabanı](database.md), [ER](er-diagram.md).

## 1. Mevcut repository ağacı

Üretilen build/test çıktıları, bağımlılık dizinleri ve servislerde tekrarlanan dosyalar aşağıda kısaltılmıştır.

```text
qr-management-system/
├── README.md
├── .env.example
├── .dockerignore
├── .gitattributes
├── .gitignore
├── .qodo/
├── assets/git-images/                   # README marka ve mimari görselleri
├── backend/
│   ├── pom.xml                         # Parent: Java 25 / Spring Boot 4.1.1
│   ├── test-support/
│   │   └── application-test.properties # Ortak test profili / Testcontainers
│   └── services/
│       ├── api-gateway/
│       ├── auth-service/
│       ├── qr-service/
│       ├── menu-service/
│       ├── order-service/
│       ├── kitchen-service/
│       ├── waiter-service/
│       ├── payment-service/
│       ├── notification-service/
│       ├── admin-service/
│       ├── rating-service/
│       ├── mail-service/
│       └── timer-service/
├── frontend/
│   ├── package.json
│   ├── package-lock.json
│   ├── .env.example
│   ├── next.config.ts
│   ├── tsconfig.json
│   ├── eslint.config.mjs
│   ├── vitest.config.mts
│   ├── playwright.config.ts
│   ├── sonar-project.properties
│   ├── README.md
│   ├── public/                         # Restofy görselleri
│   ├── test/setup.ts
│   ├── e2e/                            # app.spec.ts, http.spec.ts
│   └── src/
│       ├── app/                        # layout, boş page, CSS, ikonlar, layout testi
│       ├── features/
│       │   ├── admin/
│       │   ├── auth/
│       │   ├── kitchen/
│       │   ├── menu/
│       │   ├── notification/
│       │   ├── order/
│       │   ├── payment/
│       │   ├── qr/
│       │   ├── rating/
│       │   └── waiter/
│       └── shared/
│           ├── components/ui/
│           ├── config/
│           ├── hooks/
│           ├── lib/
│           │   ├── api/
│           │   └── i18n/               # config, request, actions, tipler ve testler
│           │       └── messages/       # tr.json, en.json; şu an boş sözlükler
│           ├── types/
│           └── utils/
├── docs/
│   ├── SECURITY.md
│   ├── web-rules.md
│   ├── gereksinimler/
│   │   └── Restofy_Nihai_Gereksinimler.docx
│   ├── decisions/migrations.md
│   └── checklist/web-site-final-checklist.md
├── postgres/
│   ├── init-databases.sh
│   └── postgres.md
├── ops/                                # env.cmd / env.ps1 / env.sh ve rehber
├── pre-push/                           # Manuel kalite komutları ve testleri
├── lib/                                # Kalite scriptlerinin ortak kodu ve testi
├── sonarqube/                          # İsteğe bağlı analiz komutları ve testleri
├── logs/.gitkeep
├── docker-compose.yml
└── docker-compose.sonar.yml
```

Frontend feature/shared yapraklarının çoğu `.gitkeep` ile ayrılmış iskeletlerdir. Bir klasörün varlığı özelliğin uygulandığını göstermez. Repository'de uygulanmış iş API client'ı veya müşteri/garson/mutfak ekranı bulunmadı.

`docs/web-rules.md`, güvenlik eş belgesine `.agents/SECURITY.md` olarak referans veriyor; incelemede gerçek belge `docs/SECURITY.md` konumunda bulundu. Bu bağlantı tutarsızlığı ayrı bir dokümantasyon düzeltmesi olarak ele alınmalıdır.

## 2. Mevcut backend servis şablonu

Örnek `order-service`; Java paket kökü servise göre değişir (`com.restofy.order`, `com.restofy.menu` vb.).

```text
backend/services/order-service/
├── pom.xml
├── Dockerfile
├── mvnw
├── mvnw.cmd
└── src/
    ├── main/
    │   ├── java/com/restofy/order/
    │   │   ├── OrderServiceApplication.java
    │   │   ├── api/
    │   │   │   ├── controller/
    │   │   │   ├── dto/request/
    │   │   │   ├── dto/response/
    │   │   │   └── exception/
    │   │   ├── application/
    │   │   │   ├── service/
    │   │   │   └── mapper/
    │   │   ├── domain/
    │   │   │   ├── entity/
    │   │   │   ├── enums/
    │   │   │   └── exception/
    │   │   └── infrastructure/
    │   │       ├── client/
    │   │       ├── config/
    │   │       │   ├── RestClientConfiguration.java
    │   │       │   └── RestClientProperties.java
    │   │       ├── persistence/repository/
    │   │       └── security/
    │   └── resources/
    │       └── application.properties
    └── test/
        └── java/com/restofy/order/
            ├── OrderServiceApplicationTests.java
            └── infrastructure/config/RestClientIntegrationTests.java
```

Katmanların çoğunda yalnız `.gitkeep` vardır. `classpath:db/migration` servis ayarlarında tanımlıdır; inceleme anında iş alanı migration dosyası bulunmamaktadır. Migration yolu aşağıdaki hedef yapıda ilk gerçek dosyayla oluşturulmalıdır.

Gateway aynı iş servisi şablonuyla değerlendirilmemelidir: domain entity, JPA repository veya kendi DB migration'ı gerektirmez. Routing/filter/security/config sorumluluğu taşır.

## 3. Önerilen backend yerleşimi

Mevcut katmanlı iskelet korunarak, işlev geldikçe ekleme önerilir:

```text
src/main/java/com/restofy/order/
├── api/
│   ├── controller/OrderController.java
│   ├── dto/request/CreateOrderRequest.java
│   ├── dto/response/OrderResponse.java
│   └── exception/ApiExceptionHandler.java
├── application/
│   ├── service/CreateOrderService.java
│   ├── service/CancelOrderService.java
│   ├── mapper/OrderMapper.java
│   └── port/                           # Yalnız ihtiyaç halinde arayüzler
│       ├── MenuQuotePort.java
│       └── TableSessionPort.java
├── domain/
│   ├── entity/Order.java
│   ├── entity/OrderItem.java
│   ├── enums/OrderStatus.java
│   ├── exception/InvalidOrderTransitionException.java
│   └── event/                          # İhtiyaç halinde iş olayları
└── infrastructure/
    ├── client/                         # Portların RestClient adaptörleri
    ├── config/
    ├── persistence/repository/
    ├── security/                       # Auth/tenant sözleşmesi sonrası
    └── messaging/                      # Outbox dispatcher / inbox tüketimi

src/main/resources/db/migration/
└── V41__create_order_core.sql           # Yalnız örnek; henüz oluşturulmadı

src/test/java/com/restofy/order/
├── domain/                             # Durum ve fiyat kuralları
├── application/                        # Use-case ve hata telafisi
├── api/                                # DTO, rol ve tenant senaryoları
└── infrastructure/                     # PostgreSQL, HTTP ve idempotency
```

Controller HTTP ve DTO sorumluluğu taşır; fiyat/durum/yetki kuralları controller'a gömülmez. Application bir use-case'i ve yerel transaction sınırını yürütür. Domain geçişleri ve iş invariant'larını taşır. Infrastructure DB, HTTP, teslimat ve kimlik adaptörlerini uygular.

Bu öneri mevcut katmanlı iskelettir; yalnız klasör adı üzerinden strict hexagonal mimari uygulanmış sayılmaz. Domain'in framework'ten tamamen bağımsızlığı seçilirse ayrı persistence entity + mapper maliyeti ayrıca değerlendirilir. JPA/domain ayrımı karar verilmeden iki paralel model üretilmez.

### Servis bazında yerleşecek iş modeli

- `auth-service`: Personel, restoran üyeliği/rolü, oturum, TOTP ve kurtarma.
- `admin-service`: Restoran ve operasyon ayarları; olaylardan rapor projeksiyonları.
- `qr-service`: Masa, QR kimlik bilgisi, ana/guest oturumu, kapanış ve sipariş kabul koordinasyonu.
- `menu-service`: Kategori, ürün, içerik/seçenek, fiyat ve kalıcı satış erişilebilirliği.
- `order-service`: Sepet, sipariş snapshot'ı, ürünler, iptal, teslim ve sipariş geçmişi.
- `kitchen-service`: Sipariş bazlı hazırlık, mutfak item snapshot'ı, fire, günlük sayımlar.
- `waiter-service`: Çağrı, sahiplenme/çözüm ve operasyon ekranı projeksiyonları.
- `payment-service`: Ödeme denemesi, callback, doğrulanmış tahsilat ve uzlaştırma.
- `notification-service`: Bildirim, alıcı bazlı okuma ve teslim denemesi.
- `rating-service`: Ödeme uygunluğu, ürün/restoran oyu, puan özeti.
- `mail-service`: E-posta gönderim işi ve denemesi. Parola sıfırlama tokenının iş otoritesi Auth'tur.
- `timer-service`: Yalnız onaylanan Wi-Fi erişim hakkı/cihaz adaptörü; masa idle timeout'u buraya zorunlu bağımlı değildir.

Servisler birbirlerinin Java domain/JPA paketlerini import etmemeli; başka servis DB'sine bağlanmamalıdır. Ortak iş entity'leriyle büyük bir shared kütüphane kurulması bağımsız sürümlemeyi zayıflatır. API/olay sözleşmeleri ayrı, sürümlü veri sözleşmeleridir.

## 4. Önerilen frontend yerleşimi

Mevcut feature yapısını koruyan örnek; tüm klasörlerin hemen açılması gerekmiyor:

```text
src/
├── app/
│   ├── layout.tsx
│   ├── page.tsx
│   ├── (customer)/
│   │   └── table/
│   │       ├── menu/page.tsx
│   │       ├── cart/page.tsx
│   │       ├── orders/page.tsx
│   │       └── payment/page.tsx
│   ├── (auth)/login/page.tsx
│   └── (staff)/
│       ├── admin/page.tsx
│       ├── kitchen/page.tsx
│       └── waiter/page.tsx
├── features/
│   └── order/
│       ├── index.ts                    # İhtiyaç varsa public export
│       ├── components/
│       ├── api/
│       ├── hooks/
│       ├── model/
│       └── types/
└── shared/
    ├── components/ui/
    ├── lib/api/                        # Gateway transport ve hata çözümleme
    ├── lib/i18n/                       # Mevcut TR/EN altyapısı
    ├── config/
    ├── hooks/
    ├── types/
    └── utils/
```

Route group parantezleri URL üretmez. Örnek yollar mevcut endpoint veya onaylanmış ürün rotası değildir. QR karşılama/oturum açma yolu ayrıca tasarlanır; ham bearer tokenın analitik/metadata/loga gitmesi engellenir. Personel route guard'ı yalnız UX'tir; yetki kontrolü backend'de yapılır.

`app/` sayfaları feature bileşenlerini birleştirir; fiyat/ödeme yetkisini belirlemez. `shared/` feature'a bağımlı olmaz. Feature'lar başka feature'ın iç dosyalarına doğrudan bağlanmaz. Feature'a özgü API DTO ve hook o feature içinde kalır; bütün iş mantığı tek `shared/api.ts` dosyasına doldurulmaz.

TR/EN metinleri aynı çeviri anahtarlarını kullanmalı; mevcut cookie dili ve `Europe/Istanbul` format davranışı korunmalıdır. Müşteri, personel ve ödeme verisi ortak public cache'e konmamalıdır. Yeni state/data-fetching paketi yalnız ihtiyaç ve onaylı teknik kararla eklenir.

Gateway, Mail ve Timer için sırf backend servisi bulunduğundan frontend feature klasörü açılmaz. Mevcut repository yaklaşımı da budur.

## 5. Test ve migration yerleşimi

- Üretim migration: Yalnız sahibinin `src/main/resources/db/migration/` yolu.
- Flyway test fixture: `menu-service/src/test/resources/flyway/fixtures/`; üretim migration'ına kopyalanmaz.
- Ortak test ENV/profil: `backend/test-support/application-test.properties`; gerçek ortam kimlik bilgisi içermez.
- Frontend bileşen/hook testleri: İlgili kod yanında `.test.ts(x)`.
- Uçtan uca senaryolar: `frontend/e2e/`; guest → mutfak → garson → ödeme akışı geliştikçe eklenir.
- Kalite araçları: `pre-push/`, ortak yardımcılar `lib/`; uygulama runtime iş mantığı buraya taşınmaz.
- Coverage/target/.next/Playwright raporları çıktı dizinleridir, kaynak dokümantasyon veya kalıcı uygulama verisi değildir.

## 6. Dokümantasyonun bakımı

Bu beş dosya kullanıcının istediği masaüstü teslim paketidir. Repository içine ileride alınırsa önerilen konum `docs/analysis/` altında aynı isimlerle tutulmasıdır; bu konum mevcut değildir ve bu çalışmada eklenmemiştir. Birbirlerine göreli bağlantıları korunur.

İş kuralı değiştiğinde requirements → architecture → database/ER → folder-structure sırası izlenerek tutarlılık kontrol edilir. Yeni migration uygulanmışsa geçmiş SQL yeniden yazılmaz. `docs/decisions/migrations.md` ekip sürüm aralıklarının mevcut kaynağıdır; Auth aralığı eksikliği karar kaydına alınmalıdır.

## 7. İşletim sınırı

Frontend yerel geliştirmede host üzerinde, backend/PostgreSQL Docker Compose ile çalışır. Bu analiz frontend'e Dockerfile, Kubernetes manifesti, broker, service discovery veya yeni bağımlılık eklemeyi gerektirmez. İhtiyaç ve teknik karar doğmadan klasör veya servis eklenmez.
