# Waiter Service → Order Service entegrasyon talepleri

**20 Kasım 2026 · güncelleme 23 Kasım 2026 · Umut → Hamza · PR önerisi**

Hamza, garson ekranında masanın siparişlerini görmek, hazır siparişi masaya "servis edildi" diye kapatmak ve masa listesinde canlı oturumların sipariş sayısını göstermek istiyorum. Waiter bugün bunları Order'ın var olmayan taslak uçlarına göre yazılmış bir istemciyle yapıyor. İki küçük PR öneriyorum: önce salt okunur sipariş uçları, sonra "servis edildi" komutu. İkisi de ortak bir servis kimliği kararına bağlı (aşağıda "Ortak şart").

İnceleme dayanağı `origin/dev` (`ca9ab1a`) üzerindeki Order kaynakları (ilk sürüm `1cb4e12` üzerindeydi, 23 Kasım'da yeniden okundu). Waiter'ın taslak sözleşmesi Waiter'ın `integration-contracts.md` belgesinin §3 bölümünde (o klasör repoya girmiyor, dosya olarak paylaşıyorum). Order koduna dokunmadım, uçları çalıştırmadım. Aşağıdaki "Order'da var" sütunu kaynak okumasına dayanır.

## Waiter'ın beklediği ↔ Order'da bulunan

| Waiter'ın beklediği | Order'da var | Fark |
| --- | --- | --- |
| `POST /api/v1/internal/orders/session-summaries` (toplu `activeOrderCount`) | Yok | Masa listesinde masa başına istek atmamak için toplu sayım gerekiyor |
| `POST /api/v1/internal/orders/search` (`tableId`, `tableSessionIds`, `view`, sayfa) | Personel için `GET /api/v1/orders` (`tableSessionId` veya `tableId`, `view`, `paymentStatus`, `page`, `limit` 1–100, `sort`) | Personel kimliğine (`Authorization: Staff`) bağlı, restoran kimlikten gelir. Tek oturum süzer, çoklu oturum yok. Servis kimliğiyle çağrılamaz |
| `GET /api/v1/internal/orders/{orderId}?restaurantId=` | Personel için `GET /api/v1/orders/{orderId}` | Aynı fark |
| `POST /api/v1/internal/orders/{orderId}/serve` (komut kimliği, `expectedVersion`, aktör) | `PATCH /api/v1/orders/{orderId}/fulfillment-status` (WAITER/ADMIN, bir adım ileri) | Komut kimliği, sürüm denetimi ve komut sonucu sorgusu yok. Aktör çağıran personel; servis kimliği yok |
| `GET …/serve-commands/{commandId}?restaurantId=` | Yok | Cevabı kaybolan komutun sonucunu öğrenmek için gerekiyor |
| Waiter'ın servis kimliği | `ServiceIdentityPort` yalnız `KITCHEN`, `PAYMENT`, `QR` kabul ediyor | Waiter için çağıran rolü yok |

Önemli bir model farkı var. Order'da tek bir hazırlık ekseni var (`RECEIVED → PREPARING → READY → SERVED → COMPLETED`) ve ödeme ayrı eksen. Waiter'ın taslağı ise `preparation` ve `deliveryStatus` diye iki ayrı alan varsayıyor. Order'ın modeli bana yeterli görünüyor: hazır ve henüz servis edilmemiş kuyruk `READY`, geçmiş `READY/SERVED/COMPLETED`. Waiter'ı Order'ın mevcut alanlarına uyarlamayı tercih ederim. Bunu birlikte netleştirelim, aksi hâlde Order'dan ikinci bir `preparation` projeksiyonu istemiş olurum.

## Ortak uç ilkesi (23 Kasım)

Hocanın ve Alper'in PR #35 yorumundaki isteği doğrultusunda Waiter'a özel uç eklemek yerine Order'ın mevcut ortak uçlarını kullanmak öncelikli. Order PR #45 bu uçların çoğunu ekledi ve Waiter 24 Kasım'da onlara taşındı (aşağıdaki "uygulama durumu"). Aşağıdaki tablo ve bölümler ilk talebin kaydıdır:

- **Ortak uçla karşılanabilenler.** Tek oturumun sipariş listesi (`GET /orders?tableSessionId=…`), sipariş detayı ve sürümü (`GET /orders/{id}`), `READY → SERVED` adımı (`PATCH /orders/{id}/fulfillment-status`, `WAITER`/`ADMIN`). Bunlar personel kimliğiyle çalışıyor ve Waiter'ın bu kimliği Order'a iletmesi gerekiyor. Order'ın `dev` dalında personel kimliği `Authorization: Staff <opak kimlik>`, çerez uyarlaması `chore/order-auth-integration` dalında ve `dev`'e girmedi. Hangi biçimle gideceğimiz bu dalın birleşmesine bağlı.
- **Ortak uçla karşılanamayanlar.** Masa listesi için çoklu oturum sayımı (`session-summaries`) ve çoklu oturum filtresi, komut kimliğiyle idempotent `serve`, `expectedVersion` denetimi ve cevabı kaybolan komutun sonucunu sorgulama. Bunları yeni uç olarak istemek yerine önce şu iki seçeneği birlikte tartışmak istiyorum: (a) Waiter oturum başına `GET /orders` çağırır ve sayıyı kendisi türetir (masa listesinde 500 oturuma kadar istek yükü doğurur, sınırlanmalı), (b) Order bunları ortak uç olarak ekler ve Admin/Kitchen de kullanır.

## Waiter tarafı uygulama durumu (24 Kasım 2026)

Order PR #45 ve `entegrasyon-rehberi.md` okunarak Waiter istemcisi Order'ın gerçek sözleşmesine taşındı (`chore/waiter-order-integration`). Aşağıdaki tablo yalnız Waiter yerel testlerinde (Order'ın sahte hâli, servis JWT'sini doğrular) gösterilenleri içerir.

| Konu | Waiter'da şimdi |
| --- | --- |
| Okuma uçları | `POST /api/v1/orders/session-summaries`, `POST /api/v1/orders/search`, `GET /api/v1/orders/{id}` ve `/cancellations`. Personel olarak çağrılır (`restofy_access` çerezi iletilir), gövdede `restaurantId` yok |
| Sayfa | `limit` / `totalItems` Waiter'ın `size` / `totalElements` alanına eşlenir. `view` ile `fulfillmentStatuses` birlikte gönderilmez (`AWAITING_DELIVERY` → `READY`, `placedAt,asc`; `READY_HISTORY` → `READY/SERVED/COMPLETED`) |
| Durum eşlemesi | Order'ın tek ekseni Waiter'ın iç modeline çevrilir: `RECEIVED` bekliyor, `PREPARING` hazırlanıyor, `READY/SERVED/COMPLETED` hazır, `SERVED/COMPLETED` teslim edilmiş. Tutarlar küçük birimden para birimi basamağıyla çevrilir |
| `serve` | `POST /api/v1/internal/orders/{id}/serve` ve sonuç sorgusu. `Authorization: Service <JWT>` (RS256, `kid`, 30 sn, yol/gövde/personel özeti bağlı) ve `X-Restofy-Staff-Delegation`. Cookie/Origin gönderilmez. Makbuzdaki `commandId`, `orderId`, `tableSessionId` doğrulanır. 401/403/5xx hiçbir zaman "reddedildi" kaydı olmaz |
| Yeni ENV | `WAITER_ORDER_CALLER_KEY_ID`, `WAITER_ORDER_CALLER_PRIVATE_KEY` (boşsa imza yok, yarım doldurulursa açılış durur). Order tarafında `ORDER_WAITER_SERVICE_PUBLIC_KEYS` ve `ORDER_WAITER_SERVICE_RESTAURANTS` karşılığı Hamza ile ayarlanacak |

**Doğrulanmadı:** Gerçek Order ve gerçek Auth ile uçtan uca çalıştırma yapılmadı. Order'ın HTTPS şartı ve gerçek `order-auth` profilinde iki POST okumanın (CSRF/Origin) davranışı denenmedi.

### Order'dan istekler

- Detay (ve mümkünse liste satırı) cevabına `servedAt` eklenmesi. Waiter okumalarda `servedAt` göstermiyor (`null`), yalnız `serve` makbuzundan biliyor.
- Liste satırlarında `version`, kalem ve iptal yok. Waiter komut öncesi detayı okuyor. Liste satırına `version` eklenirse bir istek azalır.
- Sıralama yalnız `placedAt`. Hazır olma zamanına göre kuyruk istenirse `readyAt` sıralaması gerekir.
- İki POST okumanın gerçek Auth oturumuyla (CSRF/Origin) nasıl çağrılacağının netleştirilmesi.
- Waiter için `ORDER_WAITER_SERVICE_*` ayarının ve anahtar çiftinin birlikte kurulması.

## PR 1 · Salt okunur sipariş uçları (WAITER-01)

| İç uç | Waiter'daki kullanım |
| --- | --- |
| `POST /api/v1/internal/orders/session-summaries` | Masa listesinde canlı oturumların aktif sipariş sayısı, tek istekte |
| `POST /api/v1/internal/orders/search` | Masa detayında ve garson sipariş ekranında filtreli, sıralı, sayfalı liste |
| `GET /api/v1/internal/orders/{orderId}?restaurantId={id}` | Komut öncesi güncel sipariş ve `version` |

- `session-summaries` gövdesi `restaurantId` ve 1–500 elemanlı `tableSessionIds`. Yanıt `{items:[{tableSessionId, activeOrderCount}]}`. "Aktif" tanımını Order söylesin (iptal edilmemiş ve `COMPLETED` olmayan mı, yoksa `RECEIVED/PREPARING/READY` mi). Siparişi olmayan oturum atlanabilir.
- `search` Order'ın bugünkü liste kurallarını korusun: filtre ve sıralama bütün veri kümesine uygulanır, sonra sayfalanır, `totalElements` filtrelenmiş toplamdır. İstediğim ek: `tableSessionIds` listesi (1–500) ve Waiter'ın ihtiyaç duyduğu görünümler. Görünüm adlarını Order'ın fulfillment durumlarıyla eşleyelim (kuyruk için `READY`, geçmiş için `READY/SERVED/COMPLETED`).
- Sipariş DTO'sunda Waiter'ın ihtiyacı: `id`, `restaurantId`, `tableId`, `tableSessionId`, sipariş numarası, fulfillment durumu, ödeme durumu, zamanlar, kalemler (ürün adı, adet, `cancelledQuantity`, seçenekler, not), iptal kayıtları ve `version`. Misafir kimlikleri DTO'ya girmesin. Garson ekranı fiyat gösteriyorsa tutarlar KDV dahil brüt minor birim olarak yeterli.
- Restoran isteğin `restaurantId` alanından kapsam olarak alınır, kimlik kanıtı olarak değil. Başka restoranın siparişi `404`.

**Kabul ölçütleri**

- [ ] 501 oturumluk istek reddedilir. 500 oturum tek istekte sayılır. Boş `tableSessionIds` geçersizdir.
- [ ] Aynı masadaki iki ayrı oturumun siparişleri karışmaz. Kapanmış oturumun siparişleri yalnız o oturum açıkça istenirse gelir.
- [ ] Başka restoranın siparişi veya masası `404` döner. Doğrulanmamış servis çağrısı veri alamaz.
- [ ] Sayfalama deterministiktir (`placedAt` ve `id`), tekrarsız ve eksiksizdir. `limit` Waiter'ın 100'lük sayfasına uyar.

## PR 2 · "Servis edildi" komutu (WAITER-03)

**İstenen uçlar**

- `POST /api/v1/internal/orders/{orderId}/serve`
- `GET /api/v1/internal/orders/{orderId}/serve-commands/{commandId}?restaurantId={id}`

Gövde `commandId`, `restaurantId`, `tableSessionId`, `expectedVersion`, `requestedByUserId` ve `requestedAt` taşır. `Idempotency-Key` değeri `commandId` ile aynıdır. Waiter kararı vermez; Order'ın cevabını olduğu gibi iletir. Waiter `404`'ten "uygulanmadı" sonucu çıkarmaz.

Mevcut `PATCH /fulfillment-status` adımı bu komutun iş kuralı olarak yeniden kullanılabilir. Yeni olan, komut kimliğine göre idempotency, `expectedVersion` denetimi ve komut sonucunun kalıcı kaydı.

- Order tek işlemde doğrular: tenant ve nesne sahipliği, `tableSessionId` siparişin oturumuyla aynı, `expectedVersion` güncel, sipariş `READY` ve iptal edilmemiş, henüz servis edilmemiş. Waiter'ın önce GET ile bakması güvence sayılmaz.
- Aynı komut kimliği ve içerik `200` ile ilk sonucun aynısını döner (`APPLIED` ya da `DUPLICATE`). Zaman, aktör ve olay etkisi tekrarlanmaz. Aynı kimlik, farklı içerik `409 ORDER_COMMAND_CONFLICT`. Reddedilen komutun sonucu da kimliğe bağlı kaydedilir.
- Red kodları: `ORDER_NOT_READY`, `ORDER_CANCELLED`, `ORDER_ALREADY_SERVED`, `ORDER_VERSION_CONFLICT`, `ORDER_SESSION_MISMATCH`, `ORDER_NOT_DELIVERABLE`, `ORDER_COMMAND_CONFLICT`. Order'ın mevcut `INVALID_STATUS_TRANSITION` kodunu bu kodlara nasıl eşleyeceğimizi konuşalım. Waiter yalnız bu kodları iletir.
- `requestedByUserId` yalnız servis kimliği doğrulanmış Waiter'dan kabul edilir ve denetim kaydına yazılır. Order'ın bugünkü `reported_staff_id` alanı yalnız beyan; yetki kararında kullanılmıyor. Servis edildi kaydında aynı yaklaşım yeterli mi, Order'ın kendi personel kaydına bağlamak mı gerekiyor, birlikte karar verelim.
- Başarılı `200`, işlem kalıcı yazıldıktan sonra döner. Komut kimlikleri makul süre saklanır (süre Order'ın kararı).

**Kabul ölçütleri**

- [ ] `READY`, doğru oturum ve güncel sürümde komut `200 APPLIED` verir, sipariş `SERVED` olur, sürüm artar. Aynı komut tekrarında ikinci olay, zaman ve aktör oluşmaz.
- [ ] Eski `expectedVersion`, farklı oturum, `PREPARING` veya iptal edilmiş sipariş ilgili `409` koduyla reddedilir. Aynı kimlik, farklı içerik `ORDER_COMMAND_CONFLICT` döner.
- [ ] İki farklı komut aynı `expectedVersion` ile aynı anda gelirse biri kazanır. Servis ve iptal yarışında sipariş yalnız birini alır.
- [ ] POST yanıtı kaybolduğunda GET kayıtlı `APPLIED` veya `REJECTED` sonucunu döner. Kayıt yoksa `404`.
- [ ] Başka restoranın siparişi `404` döner. Servis kimliği yoksa komut işlenmez.

## Ortak şart · Servis kimliği

Bu iki PR'ı Order'ın iç yollarına bağlamak için Waiter'ın çağıran servis olarak tanınması gerekiyor. Bugün Order `KITCHEN`, `PAYMENT` ve `QR` rollerini kabul ediyor, `WAITER` yok. Waiter hiçbir kimlik bilgisi göndermiyor ve ben Order'ın servis kimliği biçimini seçmedim. Yöntemi Auth ve QR'la aynı kararla netleştirelim ki üç servis aynı şemayı kullansın. Waiter'ın çağıracağı yollar şunlarla sınırlı olmalı: sipariş okuma uçları ve `serve` komutu. İç yollar Gateway üzerinden yönlendirilmez.

## Takip işleri

- **D-02, kısmi iptal.** `PARTIALLY_CANCELLED` bir sipariş kalan kalemlerle servis edilebilir mi? Waiter karar vermez, Order'ın cevabını (`200` veya `ORDER_NOT_DELIVERABLE`) iletir.
- **Eksik ürün bilgisi.** Waiter şimdilik yalnız `quantity`, `cancelledQuantity` ve iptal kayıtlarını gösteriyor. Teslimde ürün bazlı "eksik teslim" bilgisi isteniyorsa Order'ın alanı ve anlamı tanımlanmalı. Waiter bu bilgiyi kendisi doldurmaz.
- **`OrderServed` olayı.** Olay Waiter'a ya da Admin'e gidecekse DTO'su ve sıralaması ayrıca tanımlanmalı. Waiter bu aşamada olay tüketmiyor, durumu Order'dan okuyor.
- **Kısmi (kalem bazlı) servis.** İlk sürümde yok. Komut siparişi bir bütün olarak servis edildi yapar.

## Repo referansları

- [Waiter'ın mevcut Order istemcisi](../../backend/services/waiter-service/src/main/java/com/restofy/waiter/infrastructure/client/order/OrderClient.java)
- [Order entegrasyon sözleşmeleri](INTEGRATION_CONTRACTS.md)
- [Order sipariş controller'ı](../../backend/services/order-service/src/main/java/com/restofy/order/api/controller/OrderController.java)
