# Kitchen → Order entegrasyonu: Order'dan beklenenler (01)

- **Tarih:** 27 Kasım 2026
- **Talep eden:** Kitchen (KITCHEN-INT-03)
- **İlgili:** Order sorumlusu `@HmzT270`
- **İncelenen kod:** `dev` `49aa16a`. Birleşmemiş durum için [PR #47](https://github.com/software-lecture-labs/Restofy/pull/47) `c1ea3b1` yalnız okundu; bu branch ona bağlı değildir.

Aşağıda yalnız Order tarafında yapılması gereken işler vardır. Kitchen'da kalan işler [entegrasyon rehberinde](../entegrasyon-rehberi.md) listelenir.

## KO3-01 — Order, Kitchen servis kimliğini gerçek ortamda doğrulamalı

**Engelleyici:** A, B, C ve D akışlarının tamamı.

**Mevcut durum (`dev`).** `/api/v1/internal/order-events/**` uçları `Authorization: Service <credential>` bekler ve doğrulamayı `ServiceIdentityPort` yapar:

| Order profili | Kitchen çağrısına ne olur |
|---|---|
| `dev` veya `test` | `InMemoryServiceIdentityAdapter` sabit bir yer tutucu değeri kabul eder. Gerçek kimlik doğrulama yapmaz. |
| `order-qr` | `QrServiceIdentityVerifier` yalnız QR'ın imzasını tanır |
| Diğerleri | `UnavailableServiceIdentityAdapter`: `503 DEPENDENCY_UNAVAILABLE` |

PR #47, `order-kitchen` profiliyle RS256 imzalı bir Kitchen doğrulayıcısı ekliyor. Henüz birleşmedi.

**Gerekli.** Kitchen çağrılarını `dev` ve `test` dışında doğrulayan bir doğrulayıcı ve yazılı sözleşmesi `dev`'e girmeli.

**Gerekçe.** Bugün mock dışında hiçbir Kitchen → Order çağrısı başarılı olamaz. Kitchen, birleşmemiş bir sözleşmeye göre imzalayıcı yazmadı.

**Beklenen davranış.**
- Doğru imzalı ve yetkili restorana ait istek işlenir.
- Geçersiz veya süresi dolmuş kimlik `401`, yetkisiz restoran ya da yetkisiz istek `401`/`403` alır; sipariş değişmez.
- Doğrulama yapılamıyorsa `503` döner; istek kabul edilmez.

**Kabul kriterleri.**
1. Doğrulayıcı `dev` dalında.
2. Sözleşme yazılı: başlık biçimi, imza algoritması, anahtar kimliği, zorunlu claim'ler, geçerlilik süresi, gövde ve yol bağlaması, HTTPS koşulu, Order'ın yapılandırma anahtarları.
3. Üç Kitchen ucu için olumlu ve olumsuz (yanlış anahtar, başka restoran, değiştirilmiş gövde) Order testleri var.
4. Kitchen tarafı hazır olduğunda gerçek iki servisle en az bir `PREPARING` isteği `200` alıyor.

Kitchen'ın özel anahtarı Order'a verilmez; yalnız açık anahtar ve anahtar kimliği paylaşılır.

## KO3-02 — Order'ın bekleyen iptal talebi Kitchen'a ulaşmalı

**Engelleyici:** yalnız D akışı (Kitchen'ın onay/ret kararı).

**Mevcut durum (`dev`).** `PREPARING` bir siparişte müşteri veya personel iptal isterse Order talebi açık tutar ve Kitchen'ın cevabını bekler; `OrderCancellationRequested` olayını `KITCHEN` hedefiyle outbox'a yazar. `dev`'de bu hedefe gönderim yapan bir bileşen yoktur. Order'ın karar ucu (`/kitchen-cancellation`) bu talebin `cancellationId` değerini zorunlu tutar. `dev`'de Kitchen'ın bekleyen talepleri ve bu değeri öğrenebileceği bir servis yolu yoktur. (Tek istisna, Kitchen kendi talebini gönderdiğinde dönebilen `409 CANCELLATION_ALREADY_PENDING` yanıtıdır; bu bir bildirim yolu değildir.)

PR #47 Kitchen'a giden bir gönderici ekliyor. Henüz birleşmedi.

**Gerekli.** `OrderCancellationRequested` olayının (en az `eventId`, `restaurantId`, `orderId`, `orderVersion`, `cancellationId`, `scope`, kalemler) Kitchen'a teslimi `dev`'e girmeli.

**Beklenen davranış.** Aynı olay aynı `eventId` ile yeniden gönderilebilir; Kitchen onay vermeden teslim başarılı sayılmaz.

**Kabul kriterleri.** Gönderici ve olay gövdesinin yazılı sözleşmesi `dev`'de; Order testi gönderilen gövdeyi ve yeniden gönderimde `eventId`'nin korunduğunu gösteriyor.

**Not.** Bu tek başına D akışını tamamlamaz. Kitchen'da bu olayı alan uç, kalıcı kayıt ve personel cevap işlemi de yoktur; bunlar Kitchen işidir ve ayrıca onay gerektirir.
