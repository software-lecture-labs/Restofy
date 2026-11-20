# Waiter Service → QR Service entegrasyon talepleri

**20 Kasım 2026 · Umut → Alper · PR önerisi**

Alper, garson ekranında masanın durumunu ve güncel oturumunu QR'dan almak, müşteri çağrısını doğru oturuma bağlamak ve kapanıştan sonra masayı güvenle tekrar kullanıma açmak istiyorum. İlk PR olarak iç masa listesi ve detayını öneriyorum. İkisi hem garson ekranını hem de kapatma ve temizlik komutlarının ihtiyaç duyduğu sürüm bilgisini sağlar.

İnceleme dayanağı `feature/waiter-service` (`b8bec29`). Bu commit'teki QR kaynakları yereldeki `origin/feature/qr-service` (`1f98fbb`) ile aynı. Guest cookie ve personel güvenliği kararları yereldeki `origin/dev` (`3761324`) üzerinden kontrol edildi.

## PR 1 · Masa listesi ve detay (WAITER-01)

| İç uç | Waiter'daki kullanım |
| --- | --- |
| `GET /api/v1/internal/tables?restaurantId={id}&page=0&size=200` | Garson ekranında restoranın tüm masaları ve varsa canlı ana oturumları |
| `GET /api/v1/internal/tables/{tableId}?restaurantId={id}` | Masa detayı ve komut öncesi güncel sürüm |

QR'ın personel masa API'si `content`, `isActive` ve en fazla 100 kayıt kullanıyor. Canlı oturum bilgisi de o DTO'da yok. İç uçlarda Waiter'ın beklediği alanlarla çalışalım. K11 kapanış yanıtında bulunan masa görünümü aynı alanları zaten taşıyor.

- Liste zarfı `{items, page, size, totalElements}`. Sayfa sıfırdan başlar. `size=200` desteklenir, sıralama `id` ile kararlıdır. Waiter'ın mevcut toplam sınırı restoran başına 500 masadır.
- Masa alanları `id`, `restaurantId`, `tableNumber`, `displayName`, `capacity`, `operationalStatus`, `active`, `version` ve `currentSession`. `displayName` ve `capacity` boş olabilir.
- `currentSession` yalnız `OPEN` veya `CLOSING` ana oturum için dolu olur. Alanları `id`, `status`, `openedAt`, `lastActivityAt`, `scanCount` ve `version`. `lastActivityAt` ve `scanCount` boş olabilir. `scanCount` kabul edilen tarama sayısıdır. Masa ve ana oturumun `version` alanları zorunludur.
- Masa durumunu QR verir (`AVAILABLE`, `OCCUPIED`, `CLEANING`, `DISABLED`). Yeni ziyaret yeni bir ana oturum ID'si alır. Liste ve detay yanıtları guest kimlik bilgisi veya token taşımaz.

**Kabul ölçütleri**

- [ ] 201 masalık restoran iki sayfada eksiksiz ve tekrarsız gelir. Boş restoran `200` ve boş liste döner.
- [ ] Siparişi bulunmayan dolu masa `OCCUPIED` ve canlı oturumuyla görünür. Kapanan oturum listeden düşer ve masa durumu QR'ın uyguladığı geçişi yansıtır.
- [ ] Bulunmayan veya başka restorana ait masa `404` döner. Doğrulanmamış servis çağrısı veri alamaz.

## PR 2 · Garson, yardım ve hesap çağrısı için guest doğrulama (WAITER-02)

**İstenen uç** `POST /api/v1/internal/guest-sessions/verify`

Waiter'ın mevcut iç istemcisi `{ "guestToken": "…" }` gönderiyor ve `{ restaurantId, tableId, tableSession: { id, status }, guestSession: { id, status } }` bekliyor. QR'daki `GuestSessionValidationService` doğrulamayı zaten yapıyor. İstediğim, bu davranışın iç HTTP üzerinden erişilebilir olması. Order'ın da aynı doğrulamaya ihtiyacı olduğu için iç DTO'yu Hamza'yla birlikte netleştirelim.

- `200` yanıtı `OPEN` ana oturum ve ona bağlı `ACTIVE` guest döndürür. Waiter çağrının restoran, masa ve oturum kimliklerini bu yanıttan alır.
- QR'ın mevcut doğrulama davranışıyla uyumlu önerim, tanınmayan veya artık kullanılamayan guest için genel `404`. Servis yetkisi reddi `401/403`, doğrulama altyapısı kesintisi ayrı bağımlılık hatası olarak ele alınır.
- Doğrulama oturum aktivitesini yenilemez. Ham token yanıta, URL'ye veya loga girmez. QR doğrulama sınırını kendi tarafında uygular. Waiter'ın çağrı oluşturma limiti ayrı kalır. QR bu uçta `429` döndürecekse Waiter eşlemesini aynı entegrasyonda yapalım. Mevcut istemci QR'dan gelen `429` yanıtını genel bağımlılık hatasına çeviriyor.
- **Tarayıcı tarafı** 5 Kasım'daki ADR-QR-002 kararı HttpOnly guest cookie. QR'ın `chore/qr-guest-session` dalındaki sözleşmesine (`restofy_guest`, `restofy_guest_csrf` çerezleri, `X-Guest-CSRF` başlığı, tek ve izinli `Origin`) göre Waiter'ı uyarladım: `X-Guest-Token` kalktı, çağrı yazma ucu çerezi okuyup CSRF/Origin kanıtını arıyor ve doğrulamayı yine QR'a yaptırıyor (`WAITER-INT-01`). Bu dal `dev`'e girmediği için canlı uçtan uca doğrulama yapılmadı. Cookie süresi ve yenileme ayrıntıları QR'da kalıyor. Bu karar tarayıcı ile servis arasındaki taşıma için geçerli.

**Kabul ölçütleri**

- [ ] Bir masadaki iki geçerli guest aynı ana oturum ID'sini, ayrı guest ID'lerini alır. Doğrulama aktivite zamanını değiştirmez.
- [ ] Süresi dolmuş veya kapanan oturuma bağlı credential ile çağrı oluşmaz. QR kesintisi geçersiz müşteri oturumu olarak gösterilmez.
- [ ] Cookie geçişinden sonra geçerli tarayıcı isteği çağrı açar. CSRF/Origin kontrolünü geçemeyen istek çağrı oluşturmaz.

## PR 3 · Masa kapanışı ve temizlik (WAITER-04)

**Kapatma ve sonuç sorgusu QR'da var.** Waiter iki K11 yolunu da kullanıyor.

- `POST /api/v1/internal/tables/{tableId}/sessions/{tableSessionId}/close`
- `GET /api/v1/internal/tables/{tableId}/sessions/{tableSessionId}/close-commands/{commandId}?restaurantId={id}`

Bu uçları Auth ile birlikte netleştirilecek servis kimliği ve aynı restoranın aktif `WAITER`/`ADMIN` delegasyonuna bağlayalım. QR'ın HTTP filtresi şu an bu uçlara erişimi engelliyor ve Waiter istemcisi bu kimlikleri henüz taşımıyor. QR'ın gerçek Order bağlantısı ve Order'ın Payment doğrulaması da uçtan uca kapanış için gerekli. `202 CLOSING` sırasında Waiter çağrıları açık tutar. Kesin `CLOSED` ile hedef oturumun açık çağrılarını iptal eder. K13'te `CLOSED` ve `RECONCILIATION_REQUIRED` birlikte gelirse kapanışı kaydeder ve uyarıyı saklar.

**Temizlik için eksik olan iki iç uç**

- `POST /api/v1/internal/tables/{tableId}/cleaned`
- `GET /api/v1/internal/tables/{tableId}/clean-commands/{commandId}?restaurantId={id}`

QR'da `POST /api/v1/qr/table-sessions/{sessionId}/cleaned` ve temizlik iş kuralı mevcut. İç sözleşmenin ihtiyacı, bu geçişi masa kimliğiyle kullanmak ve cevabı kaybolan komutun sonucunu `commandId` ile sorgulamak. POST gövdesi `commandId`, `restaurantId`, `tableId`, `expectedVersion` (masa sürümü), `requestedByUserId` ve `requestedAt` taşır. `Idempotency-Key` değeri `commandId` ile aynıdır. Aktör ve tenant doğrulanmış bağlamdan gelir.

**Kabul ölçütleri**

- [ ] Kapatma komutu doğru masa/ana oturum ve oturum sürümüne bağlıdır. Ödeme belirsizliği veya tamamlanmamış sipariş kapanışı engeller. Kapanış ile checkout yarışında kapanmış oturuma sipariş yazılmaz.
- [ ] Temizlik güncel sürümlü, aktif ve canlı oturumu olmayan `CLEANING` masayı `AVAILABLE` yapar. Eski sürüm veya yeni oturumla çakışan komut `409` döner. Waiter temizliği ancak QR'ın doğruladığı durumla gösterir.
- [ ] Temizlik POST'u `200 APPLIED` (aynı komut tekrarlanırsa aynı etkiyle `APPLIED` veya `DUPLICATE`) ve işlem anındaki müsait masa görüntüsünü döner. Aynı kimlikle farklı içerik `409 COMMAND_CONFLICT` döner.
- [ ] POST yanıtı kaybolduğunda GET, kayıtlı `APPLIED` veya `REJECTED` sonucunu döner. Kayıt yoksa `404`. Sonradan açılan oturum önceki temizlik sonucunu değiştirmez. GET ile dönen güncel masa görüntüsü masanın tekrar dolduğunu gösterir.
- [ ] Kimlik, rol ve tenant kontrolleri QR sınırında doğrulanır. İç yollar Gateway üzerinden yönlendirilmez.

## Takip işi · QR'ın kendi kapattığı oturumlar

Otomatik kapanış veya idle expiry Waiter komutu olmadan gerçekleştiğinde eski oturumun çağrıları garsonun aktif listesinde kalabiliyor. İlk aşamada masa okuma uçları hazır olunca, açık çağrısı olan masaları Waiter'da düzenli kontrol edip yalnız canlılığını yitirdiği QR'dan doğrulanan eski oturumun çağrılarını kapatabilirim. QR ulaşılamıyorsa çağrılar korunur. Kalıcı çözüm için kesin ana oturum kapanışını ve expiry'yi kapsayan dayanıklı bir QR bildirimi üzerinde anlaşalım. Minimum bağlam `restaurantId`, `tableId`, `tableSessionId`, son durum ve olay kimliği. Tekrarlar aynı çağrıyı iki kez değiştirmemeli ve yeni oturuma dokunmamalı.

## Repo referansları

- [Waiter'ın mevcut QR istemcisi](../../backend/services/waiter-service/src/main/java/com/restofy/waiter/infrastructure/client/qr/QrClient.java)
- [QR entegrasyon rehberi](entegrasyon-rehberi.md)
- [ADR-QR-001 · Kapanış ve Order koordinasyonu](../decisions/ADR-QR-001-masa-kapanisi-ve-siparis-koordinasyonu.md)
- ADR-QR-002 ve ADR-QR-003, yereldeki `origin/dev` (`3761324`) altında `docs/decisions/` dizininde.
