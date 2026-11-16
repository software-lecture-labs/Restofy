# ADR-MENU-001 — Müşteri menüsünün güven modeli, görünürlüğü ve okuma tutarlılığı

- **Durum:** Kabul edildi (geliştirme aşaması), QR/Admin doğrulaması bağlanınca gözden geçirilecek
- **Tarih:** 2026-10-30
- **Sahip:** Menu (Merve)
- **İlgili kod:** `PublicMenuController`, `PublicMenuService`, `PublicProductDetailService`, `PublicProductSearchRepository`,
  `PublicRestaurantScopeResolver`, `DenyAllPublicRestaurantScopeResolver`, `MenuSecurityConfiguration`, `ProductEligibility`

## Bağlam

Müşteri menüyü QR ile açacak. QR doğrulaması QR Service'in işidir, restoran kaydı ve açık/kapalı durumu Admin'in işidir. İkisi
de henüz uygulanmadı (`qr-service` ve `admin-service` yalnız iskelet ya da erken aşamada, Auth'ta servis kimliği yok, D-09 açık).
Gateway `/api/v1/menu/**` yolunu kimlik doğrulamadan Menu'ye iletir. Menu, hangi restoranın menüsünü göstereceğini istemcinin
söylediği değerden **doğrulamadan** öğrenemez.

## Karar

1. Müşteri uçları yönetim uçlarından ayrı bir önekte, yalnız `GET`: `/api/v1/menu/public/restaurants/{restaurantRef}/...`.
2. Restoran, **`PublicRestaurantScopeResolver` portu** ile çözülür. `restaurantRef` istemci girdisidir ve tek başına bir şey
   kanıtlamaz. Üretim varsayılanı **fail-closed**'dur: hiçbir referansı doğrulamaz, her istek aynı 404'e gider. QR/Admin
   doğrulaması gelene kadar müşteri menüsü üretimde **bilerek çalışmaz**. Testlerde yalnız `src/test` içindeki test çözümleyicisi
   vardır.
3. `SecurityFilterChain` ikiye ayrıldı: `GET /api/v1/menu/public/**` açık (oturumsuz, çerezsiz), o yolda diğer metotlar reddedilir.
   Geri kalan her şey Spring Boot'un önceki varsayılanıyla aynıdır (kimlik zorunlu, CSRF açık, yalnız `health` açık). Bu davranış
   `MenuSecurityChainApiTests` ile kilitlidir.
4. Müşterinin gördüğü küme tek yerde tanımlıdır: `ProductEligibility.isListedPublicly()` = ürün aktif, yayınlı, kategori zinciri
   aktif ve **availability kaydı var**. Aynı koşullar SQL'de (`FROM_LISTED`) sayfalamadan önce uygulanır; liste, toplam ve tek ürün
   sorgusu aynı parçayı paylaşır. 80 durum kombinasyonu hem SQL'den hem Java kuralından geçirilerek ikisinin ayrışmadığı testle
   doğrulanır.
5. `OUT_OF_STOCK` ve `TEMPORARILY_CLOSED` ürünler **görünür**, `canOrder=false`'tur. (Faz 4 planında "gizle" yazıyordu; ürün sahibi
   TASK 2.3 kararına uyularak görünür bırakıldı.) Availability kaydı olmayan ürün müşteriden gizlenir; yönetim tarafındaki
   `isVisibleToCustomer` anlamı değişmez, yalnız ek bir `isListedPublicly` alanı yönetim yanıtına eklendi.
6. Her müşteri okuması tek **salt okunur `REPEATABLE READ`** işlemidir: sayfa, toplam, kategori ve availability aynı anlık
   görüntüden gelir. Bu bir **anlık görüntü** garantisidir, canlı görünüm veya rezervasyon değildir: `canOrder` yalnız gösterim
   bilgisidir, Order sipariş anında availability'yi yeniden doğrulamak zorundadır.
7. Gizli, bilinmeyen, başka restoranın ürünü/kategorisi ve doğrulanmamış restoran **aynı 404 gövdesini** döndürür. Yanıtlar
   `Cache-Control: no-store` taşır. Müşteri DTO'ları ayrıdır ve bir alan allowlist testiyle korunur (restoran kimliği, sürüm,
   audit, durum bayrakları, availability nedeni, KDV oranı yoktur).

## Seçenekler ve ödünleşimler

| Seçenek | Artı | Eksi |
|---|---|---|
| A1: `restaurantId` (UUID) ile herkese açık okuma | Hemen çalışır | UUID yetki gibi kullanılır, restoranın açık/kapalı olduğu kontrol edilemez, QR gelince sözleşme değişir |
| A2: HTTP ucu yok, yalnız servis katmanı | En güvenli | Müşteri menüsü kullanılamaz |
| **A3: port + fail-closed varsayılan** | Sözleşme ve okuma modeli hazır, sahte doğrulama yok, QR/Admin gelince yalnız port uygulaması değişir | Üretimde gerçek çözümleyici bağlanana kadar menü 404 verir |

## Sonuç ve izleme

- Gerçek çözümleyici (QR veya Admin tabanlı) bağlandığında bu ADR yeniden değerlendirilir. Çözümleyicinin referansı doğrulaması ve
  restoranın açık olduğunu denetlemesi şarttır.
- **Hız sınırı yok.** Müşteri uçları Gateway'de ve Menu'de sınırlandırılmamıştır (`docs/SECURITY.md` §12). Uçlar açılmadan önce
  sınırlama gerekir.
- Arama ve sıralama planı ölçülmedi; kataloglar büyürse [ADR-MENU-004](ADR-MENU-004-kalicilik-erisimi-ve-arama.md) yeniden bakılır.
