# Kitchen → Order entegrasyonu: Order'dan beklenenler (01)

- **Tarih:** 27 Kasım 2026 (Kitchen imzalayıcısı ve onay/ret akışı eklendikten sonra güncellendi)
- **Talep eden:** Kitchen (KITCHEN-INT-03)
- **İlgili:** Order sorumlusu `@HmzT270`
- **İncelenen kod:** `dev` `49aa16a`. Birleşmemiş [PR #47](https://github.com/software-lecture-labs/Restofy/pull/47) `c1ea3b1` salt okunur incelendi ve geçici, izole bir çalışma alanında denendi; bu branch ona bağlı değildir.

Aşağıda yalnız Order tarafında yapılması gereken işler vardır. Kitchen'da kalan işler [entegrasyon rehberinde](../entegrasyon-rehberi.md) listelenir.

## KO3-01 — Kitchen'ın imzası `dev`'de doğrulanmalı

**Engelleyici:** Kitchen'dan Order'a giden tüm istekler.

**Mevcut durum (`dev`).** `/api/v1/internal/order-events/**` uçları `Authorization: Service <credential>` bekler:

| Order profili | Kitchen çağrısına ne olur |
|---|---|
| `dev` veya `test` | `InMemoryServiceIdentityAdapter` sabit bir yer tutucu değeri kabul eder. Gerçek kimlik doğrulama yapmaz. |
| `order-qr` | `QrServiceIdentityVerifier` yalnız QR'ın imzasını tanır |
| Diğerleri | `UnavailableServiceIdentityAdapter`: `503 DEPENDENCY_UNAVAILABLE` |

**Kitchen tarafı hazır.** Kitchen, PR #47'de yazılı Kitchen tokenını üretiyor:

- RS256, `kid` ile imzalı.
- Claim'ler: `iss=restofy-kitchen`, `sub=kitchen-service`, `aud=restofy-order`, `scope=order:kitchen-events`, `token_type=service`, UUID `jti`.
- Süre: `iat = nbf`, `exp = iat + 30 sn`.
- İsteğe bağlama: `restaurant_id`, `method=POST`, `target` (ham yol) ve `body_sha256` (gönderilen gövdenin SHA-256 özeti).

Bu imza, PR #47'nin `KitchenServiceIdentityVerifier` ve `ServiceAuthenticationFilter` sınıflarıyla geçici bir çalışma alanında denendi:

- Doğru restoran: kabul edildi.
- İzin verilmeyen restoran ve değiştirilmiş gövde: reddedildi.

Bu deneme çalışan bir Order servisiyle yapılmadı.

**Gerekli.** PR #47'deki Kitchen doğrulayıcısı (veya aynı sözleşmeyi uygulayan bir doğrulayıcı) `dev`'e girmeli. Ortam hazır olduğunda:

- Kitchen'ın açık anahtarı ve `kid` değeri `ORDER_KITCHEN_SERVICE_PUBLIC_KEYS` ayarına,
- izin verilen restoranlar `ORDER_KITCHEN_SERVICE_RESTAURANTS` ayarına eklenmeli.

Kitchen'ın özel anahtarı Order'a verilmez.

**Beklenen davranış.**
- Doğru imzalı ve izin verilen restorana ait istek işlenir.
- Geçersiz veya süresi dolmuş kimlik `401` alır; izin verilmeyen restoran veya istek `401`/`403` alır. Sipariş değişmez.
- Doğrulama yapılamıyorsa `503` döner.

**Kabul kriterleri.**
1. Doğrulayıcı `dev` dalında.
2. Yukarıdaki claim listesi Order belgelerinde yazılı.
3. Üç Kitchen ucu için olumlu ve olumsuz Order testleri var: yanlış anahtar, başka restoran, değiştirilmiş gövde.
4. Gerçek iki servisle, HTTPS üzerinden en az bir `PREPARING` isteği `200` alıyor.

**Not (talep değil):** PR #47'deki doğrulayıcı, ileri tarihli `iat` değerini hiç tolere etmiyor. Kitchen `iat` değerini saniyeye aşağı yuvarlıyor; bu yüzden Kitchen'ın saati Order'dan bir saniyeden fazla ileride olursa istekler `401` alır ve yeniden denenir. Ortamda saat eşitlemesi (NTP) bu yüzden önemli.

## KO3-02 — Order'ın bekleyen iptal isteği Kitchen'a ulaşmalı

**Engelleyici:** yalnız D akışı (Kitchen'ın onay/ret kararı).

**Mevcut durum (`dev`).** `PREPARING` bir siparişte müşteri veya personel iptal isterse Order isteği açık tutar ve Kitchen'ın cevabını bekler. `OrderCancellationRequested` olayını `KITCHEN` hedefiyle outbox'a yazar, ancak `dev`'de bu hedefe gönderim yapan bir bileşen yoktur.

Order'ın karar ucu (`/kitchen-cancellation`) bu isteğin `cancellationId` değerini zorunlu tutar. Kitchen bu değeri yalnız bu olaydan öğrenebilir.

**Kitchen tarafı hazır (alıcı hariç).** Kitchen şunları yapabiliyor:

- isteği kalıcı olarak kaydetmek,
- personelin onay veya ret cevabını almak,
- cevabı `/kitchen-cancellation` ucuna göndermek.

Bunlar test sunucusuyla test edildi. PR #47'nin Kitchen'a gönderdiği gövde, Kitchen'ın ihtiyaç duyduğu alanların hepsini taşıyor: `eventId`, `eventType`, `schemaVersion`, `occurredAt`, `restaurantId`, `orderId`, `cancellationId`, `scope`, `kitchenConfirmationRequired`, `items[orderItemId, quantity]`. Olayı alan Kitchen ucu Kitchen PR #77'dedir; Order'dan beklenen bir iş değildir.

**Gerekli.** PR #47'deki Kitchen göndericisi `dev`'e girmeli.

**Beklenen davranış.** Aynı olay aynı `eventId` ve gövdeyle yeniden gönderilebilir. Kitchen kaydetmeden teslim başarılı sayılmaz.

**Kabul kriterleri.**
- Gönderici ve olay gövdesinin yazılı sözleşmesi `dev`'de.
- Order testi gönderilen gövdeyi ve yeniden gönderimde `eventId`'nin korunduğunu gösteriyor.
- Gerçek iki servisle bir isteğe verilen Kitchen cevabı Order'da kararı tamamlıyor.
