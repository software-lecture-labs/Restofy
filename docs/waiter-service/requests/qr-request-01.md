# QR-REQUEST-01 · Guest reddi için sabit hata kodu ve Waiter servis kimliği

**23 Kasım 2026 · Umut (Waiter) → Alper (QR) · bilgi: Hamza (Order)**

**Kaynak:** #35 incelemesinde hocanın minor notu. Not #35'i bozmuyor, sonraki entegrasyonda ele alınacak.

**Durum:** Alper kabul etti (#35, 23 Kasım). QR ayrı küçük bir PR'da guest ret yanıtına sabit bir `code` alanı ekleyecek. Kod tek ve genel kalacak, `detail` metni yerinde duracak. **Kod adı ve alan yapısı QR PR'ında netleşecek.** Aşağıdaki `GUEST_SESSION_UNAVAILABLE` Waiter'ın önerisidir, QR PR'ı birleşince bu belge kesin adla güncellenir.

## Sorun

Waiter ve Order, guest doğrulamasında QR'ın ortak ucunu kullanıyor:

`POST /api/v1/internal/qr/guest-sessions/validate`

QR, tanınmayan veya artık kullanılamayan guest için `404` problem yanıtı döndürüyor. Bu reddi bugün yalnız `detail` metninden tanıyabiliyoruz:

```json
{ "type": "about:blank", "title": "Not Found", "status": 404, "detail": "Guest session is unavailable." }
```

QR'ın aynı `notFound` işleyicisi masa ve QR kodu bulunamadığında da `404` döndürüyor. Eksik rota veya kapalı `qr-order-inbound` profili de `404` verir. Waiter bu `404`'leri guest reddiyle karıştırmamak için metne bakıyor:

- Waiter: `QrClient.isUnavailableGuest` → `detail` metni aynıysa müşteriye `401`, değilse `502 DEPENDENCY_BAD_RESPONSE`.
- Order: `QrSessionHttpAdapter.invalidGuestProblem` → aynı metin karşılaştırması.

İnsan okuyacağı bir metin değişirse (yazım düzeltmesi, çeviri, yeniden ifade) iki servis de guest reddini tanıyamaz. Bu durumda yetkisiz erişim olmaz. Ancak oturumu biten müşteri "tekrar QR okut" yerine servis hatası görür ve bu hatalar bağımlılık arızası olarak loglanır.

## İstek

Guest reddine, QR'ın kapanış hatalarında zaten kullandığı (`StaffClosureException` → `code`) biçimde sabit bir `code` alanı eklenmesi:

```json
{ "type": "about:blank", "title": "Not Found", "status": 404,
  "detail": "Guest session is unavailable.", "code": "GUEST_SESSION_UNAVAILABLE" }
```

- Önerilen alan adı `code`, değer `GUEST_SESSION_UNAVAILABLE` (kesin ad QR PR'ında). Değer sözleşmenin parçası sayılır ve değişmez. `detail` serbest metin olarak kalabilir.
- Kapsam: `validate` ucu ve aynı reddi veren `GET /api/v1/internal/qr/table-sessions/{sessionId}/guests/{guestId}/state` ucu.
- Diğer `404`'ler (masa, QR kodu, bilinmeyen rota) bu kodu taşımamalı.
- QR'ın mevcut kuralı korunur: tanınmayan, başka restorana ait, kapalı veya süresi dolmuş guest için **aynı** yanıt. Kod bu durumları birbirinden ayırmaz.

## Geçiş sırası

1. QR `code` alanını ayrı bir PR'da ekler. `detail` metni bu aşamada değişmez.
2. Waiter ve Order ayrı PR'larda `code` alanına geçer (Waiter'da `QrClient.isUnavailableGuest`). Geçiş süresince ikisini de kabul ederler: kesinleşen `code` değeri **veya** eski `detail` metni.
3. İki servis de geçtikten sonra metin karşılaştırması kaldırılır. Bundan sonra QR `detail` metnini özgürce değiştirebilir.

## Kabul ölçütleri

- [ ] Guest reddi `404` yanıtı `application/problem+json` ile `code: "GUEST_SESSION_UNAVAILABLE"` taşır.
- [ ] Masa/QR kodu `404`'leri ve eksik rota bu kodu taşımaz.
- [ ] Tanınmayan, yabancı restoran, kapalı ve süresi dolmuş guest için yanıt gövdesi aynıdır.
- [ ] QR testleri `code` alanını doğrular. Waiter ve Order sözleşme testleri geçiş sırasında hem kodlu hem eski yanıtı kabul eder.

## Waiter'ın QR'a servis kimliğiyle erişmesi ([#38](https://github.com/software-lecture-labs/Restofy/issues/38))

QR, ortak `POST /api/v1/internal/qr/guest-sessions/validate` ucunda bugün yalnız Order'ın servis JWT'sini kabul ediyor. Waiter servis kimliği göndermediği için gerçek QR `401` döndürüyor ve müşteri garson, yardım veya hesap çağrısı oluşturamıyor.

QR'ın ortak doğrulama ucunda Waiter'ın kendi servis kimliğini doğrulaması ve Waiter'ın `QrClient` üzerinden bu kimliği göndermesi isteniyor. Alper, Hamza ve Umut kimlik doğrulama sözleşmesini birlikte netleştirecek. Uygulama öncesi [güvenlik kuralları](../../SECURITY.md) uyarınca onay alınacak.

**Kabul ölçütleri**

- [ ] Gerçek QR ile uçtan uca testte Waiter, kendi doğrulanmış servis kimliğiyle geçerli guest oturumunu doğrular ve müşteri çağrısını doğru masa oturumuna kaydeder.
- [ ] Eksik, geçersiz veya bu uç için yetkisiz servis kimlikleri reddedilir. Waiter, QR'dan gelen `401/403` yanıtını `502 DEPENDENCY_REJECTED` olarak işler ve çağrı oluşturmaz.
- [ ] Order'ın mevcut doğrulama ve guest durum sorguları çalışmaya devam eder. Waiter'ın erişimi ortak doğrulama ucuyla sınırlıdır.
