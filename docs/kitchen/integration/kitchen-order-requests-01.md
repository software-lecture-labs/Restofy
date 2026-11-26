# Kitchen ↔ Order talepleri 01 (KITCHEN-INT-01)

- **Tarih:** 26 Kasım 2026
- **Talep eden:** Kitchen (Nisa)
- **İlgili servis / sorumlu:** Order (Hamza). Servis kimliği için Auth (Kerem), ENV/Compose için ekip onayı.
- **İncelenen:** `origin/dev` `6e72336`; PR #47 (`chore/order-kitchen-integration`, `6d4fdb3`, 24 Kasım) **yalnız okundu**, birleştirilmedi ve çalıştırılmadı. İçindeki [kitchen-entegrasyon-rehberi](https://github.com/software-lecture-labs/qr-management-system/blob/6d4fdb3/docs/order-service/kitchen-entegrasyon-rehberi.md) Order'ın Kitchen'dan beklentisidir.

Bu belge bir taleptir; Order kodunda değişiklik içermez.

## 1. Order'ın Kitchen'dan beklediği ve bugünkü durum

| # | İhtiyaç | Durum | Kanıt |
|---|---|---|---|
| 1 | Personel uçlarında gerçek Auth kimliği (rehberde "principal'ı oluşturan Auth adaptörü yok") | **KARŞILANDI** (kodda; paylaşılan ortamda anahtar değeri ve HTTPS Auth adresi bekliyor, [AUTH-01/02](kitchen-auth-requests-01.md)) | `StaffSessionAuthenticator`, güvenlik testleri |
| 2 | `POST /api/v1/internal/kitchen/order-events` alıcısı, commit sonrası 204 | **KARŞILANMADI** | Kitchen'da iç controller yok; `/api/v1/internal/**` reddedilir. Yerel işleyici `AcceptedOrderIngestService` var |
| 3 | `OrderAccepted` mesajının uyumu | **UYUMSUZ** | Bkz. 2 |
| 4 | Hazırlık olaylarını gönderen `OrderEventSink` adaptörü ve `deliverDue` çalıştırıcısı | **KARŞILANMADI** | Olaylar outbox'a yazılıyor; üretim sink'i ve zamanlayıcı yok |
| 5 | Kitchen iptal talebinin Order'a eşlenmesi (`cancelAll`, `OUT_OF_STOCK` → `ITEM_UNAVAILABLE`) | **KARŞILANMADI** | 4. maddeye bağlı |
| 6 | `OTHER` için açıklama | **KARŞILANMADI** | `KitchenCancellationRequestedMessage` bilerek yalnız neden kodunu taşır; ürün kararı bekliyor |
| 7 | Order kaynaklı iptal olayları (`OrderCancellationRequested`, `OrderCancelled`, `OrderItemsCancelled`, `OrderCancellationRejected`) | **KARŞILANMADI** | `OrderDecisionService` yalnız Kitchen'ın kendi `requestId`'li talebinin kararını uygular. Kısmi iptal D-02'yi bekler |
| 8 | Kitchen → Order imzalı servis kimliği; Order → Kitchen imzasının doğrulanması | **KARŞILANMADI, ONAY BEKLİYOR** | Yeni güven ilişkisi ve sırlar ([AUTH-05](kitchen-auth-requests-01.md)) |

## 2. Mesaj uyumsuzluğu (kanıt)

PR #47 `KitchenEventProjection` ile Kitchen `OrderAcceptedMessage` / `OrderAcceptedMessageReader` (`dev`) karşılaştırması:

| Alan | Order gönderiyor | Kitchen bekliyor | Sonuç |
|---|---|---|---|
| `eventType` | `"OrderAccepted"` | alan yok; bilinmeyen alan reddedilir | Mesaj reddedilir |
| `tableId` | zorunlu | alan yok | Mesaj reddedilir |
| `tableNumber` | gönderilmiyor | zorunlu, boş olamaz | Mesaj reddedilir |
| kalem seçeneği sayısı | çıkarılan + eklenen birleşik | en çok 50 | Üst sınır karşılaştırılmalı |
| iptal kararı kimliği | `cancellationId` | Kitchen'ın `requestId`'si | Eşleme kuralı gerekli |

Bugünkü haliyle Order'ın kabul mesajı Kitchen'a bağlansa bile **işlenmez**. Bu Auth entegrasyonunun kapsamı değildir.

## 3. Order'dan istenenler

### ORD-01 — Servis kimliği sözleşmesinin kesinleşmesi
**Beklenen:** PR #47'deki claim kümesinin (`kid`, `iss`, `sub`, `aud`, `scope`, `restaurant_id`, `method`, `target`, `body_sha256`, `jti`, `iat = nbf`, ≤ 30 sn) nihai olup olmadığı ve `dev`'e ne zaman gireceği. Tekrar (replay) için `jti` saklanacak mı, yoksa yalnız olay kimliği tekilleştirmesine mi güveniliyor?
**Etki:** Karar olmadan Kitchen imzalayıcı veya doğrulayıcı yazmaz.
**Kabul:** Karar `dev`'deki sözleşmede; yanlış restoran, scope, hedef, gövde özeti ve süresi dolmuş imza için ortak negatif senaryolar.

### ORD-02 — Personel delegasyonunun anlamı
**Mevcut:** `dev`'deki `KitchenCancellationRequest.requestedByStaffId` isteğe bağlıdır ve belgeye göre yetki kararında kullanılmaz. Kitchen talebi yapan personeli doğrulanmış kimlikten kendi tablosuna yazar; giden olay bugün bu kimliği **taşımaz**.
**Soru:** Order, Kitchen servis kimliğine hangi işlemleri hangi restoran kapsamında yetkilendiriyor ve `requestedByStaffId` yalnız denetim bilgisi olarak mı kalacak? Kitchen'ın beklentisi: servis kimliği yalnız belgelenmiş Kitchen olay yollarını açar; personel cookie'si/JWT'si Order'a gönderilmez.
**Kabul:** Yazılı cevap.

### ORD-03 — Kitchen uçlarının üretimde açılabilmesi
**Mevcut:** `dev`'de `order-qr` ve dev/test dışındaki profillerde `UnavailableServiceIdentityAdapter` her servis çağrısını reddeder; Kitchen için doğrulayıcı yalnız PR #47'dedir.
**Kabul:** Order'ın üç Kitchen ucu gerçek imzayla çalışır, imzasız ve hatalı imzalı istek reddedilir.

### ORD-04 — Mesaj sözleşmesinde ortak karar
**Beklenen:** `tableNumber` kaynağı (Order göndermeyecekse Kitchen ekranı neyi gösterecek), `eventType` ve `tableId` alanları, seçenek üst sınırı.
**Kabul:** Üretici ve tüketici aynı örnek JSON'u testinde kullanır.

## 4. Kitchen'ın sonraki işi (bu branch'te değil)

Öneri: güncel `dev` tabanlı ayrı branch `feature/kitchen-int-02-order-integration`: mesaj uyumu, iç alıcı, `OrderEventSink` adaptörü ve çalıştırıcı, Order kaynaklı tam iptal. Ön koşullar: AUTH-05 ve ORD-01. `OTHER` açıklaması ve kısmi iptal (D-02) ürün kararı gerektirir.

Yanıt gelmezse mevcut sözleşmeden **kesin** çıkarılabilenler: `eventType`, `tableId` ve isteğe bağlı `tableNumber` okunması için tasarım hazırlığı ve `OUT_OF_STOCK` → `ITEM_UNAVAILABLE` eşlemesi. Servis kimliği, kısmi iptal ve `OTHER` metni tahmin edilmez.
