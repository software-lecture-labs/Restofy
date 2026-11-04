# PR #17 — QR–Auth entegrasyonu için Auth yanıtı

Durum: **Kerem / Auth tarafının mevcut sözleşmeye dayalı yanıtı.** Bu belge yeni endpoint,
ENV, migration veya erişim açılması değildir. PR #17 içindeki
`ADR-QR-AUTH-001` Q1–Q9 sorularına karşılık verir.

## Karar özeti

Personel isteklerinde çevrimiçi Auth introspection endpoint'i eklenmeyecek. QR, tarayıcının
`restofy_access` HttpOnly cookie'sindeki `staff-v1` access JWT'sini Auth'un statik X.509 RSA
public key'iyle yerel doğrulayacak; ardından rol, restoran ve QR kaynağı yetkisini kendi servis
sınırında uygulayacak.

Bu, mevcut Auth sözleşmesini korur: logout, hesap pasifleştirme ve rol değişikliği bağımsız
servislere en geç access JWT süresi dolduğunda yansır. Varsayılan access süresi 900 saniye,
izin verilen yapılandırma aralığı 300–3600 saniyedir. Anlık küresel iptal garantisi verilmez.
Bu gecikme kabul edilmiyorsa yeni introspection/revocation tasarımı ayrıca güvenlik onayı,
servis kimliği, timeout ve kesinti politikası gerektirir; PR #17 kapsamında varsayılmaz.

## Q1–Q9 yanıtları

### Q1 — QR hangi kanıtı doğrulayacak?

QR, `restofy_access` cookie'sindeki access JWT'yi yerel olarak doğrular. Auth'a her istekte
çağrı yapılmaz; yeni session-introspection endpoint'i açılmaz. QR, serbest `userId`, `sid`,
`role`, `restaurantId` header/body değerlerini kimlik kanıtı saymaz ve refresh/MFA cookie'sini
istemez.

Doğrulama sonucu QR içinde minimum şu principalı üretir:

- `sub` → personel UUID,
- `sid` → Auth session UUID,
- `role` → `ADMIN`, `WAITER` veya `KITCHEN`,
- `restaurant_id` → mevcut restoran kapsamı,
- `jti` → token UUID,
- `auth_state=AUTHENTICATED`, `token_type=access`.

### Q2 — Auth, QR servis kimliğini nasıl doğrulayacak?

Yerel personel JWT doğrulaması için QR → Auth çağrısı olmadığı için bu akışta QR servis
credential'ı gerekmez. QR'ın Order/Payment/Waiter gibi servislere yaptığı iç çağrıların servis
kimliği ayrı sözleşmedir; personel JWT'si onun yerine kullanılamaz.

İleride çevrimiçi Auth endpoint'i istenirse iç ağ veya `X-Service-Name` yeterli sayılmayacak;
çağırana özel anahtar, audience/scope, replay ve rotation modeli ayrıca onaylanacaktır. Mevcut
Notification → Auth dar sözleşmesi QR'a otomatik olarak genellenmez.

### Q3 — Hangi alanlar doğrulanacak?

QR doğrulayıcısı en az şu kontrolleri fail-closed yapar:

- algoritma yalnız RS256,
- issuer yapılandırılmış Auth issuer'ı,
- audience `restofy-staff-api`,
- `token_type=access` ve `auth_state=AUTHENTICATED`,
- `exp`, `iat`, `nbf` varlığı ve zaman tutarlılığı,
- `sub`, `sid`, `jti` geçerli UUID,
- rol allowlist'i ve `restaurant_id` eşleşmesi,
- token uzunluğu en fazla 4096 karakter.

`MFA_PENDING`, refresh, guest ve service tokenları personel principalı üretmez. Auth kimliği
doğrulasa bile QR, izin verilen rolü ve hedef masa/oturumun aynı restorana ait olduğunu ayrıca
denetler.

### Q4 — Hata davranışı

- Eksik, bozuk, süresi dolmuş veya yanlış tür/audience token: **401**.
- Geçerli personel fakat QR işlemi için yanlış rol: **403**.
- Başka restorana/kaynağa erişim: QR'ın güvenli 403/404 politikası.
- Public key veya zorunlu güvenlik ayarı eksik/hatalı: uygulama başlangıcında fail-fast;
  korumalı endpoint açılmaz.
- Auth kesintisi: önceden verilmiş ve süresi geçmemiş JWT'nin yerel doğrulanmasını bozmaz;
  login, refresh ve activity çağrıları etkilenebilir.

Bu modelde personel doğrulaması için uzak timeout/retry veya QR transaction'ı altında Auth
çağrısı bulunmaz.

### Q5 — Cache ve iptal gecikmesi

Ayrı olumlu introspection cache'i yoktur. JWT, kendi `exp` süresine kadar yerel doğrulama
kanıtıdır. Logout, pasifleştirme ve rol değişikliği en fazla kalan access TTL kadar gecikebilir;
süresi geçmiş token kullanılmaz. QR bu süreyi uzatmaz ve Auth kesintisinde eski tokenı yeniden
geçerli hâle getirmez.

### Q6 — Idle süresi

JWT doğrulaması, QR okuması ve polling Auth idle süresini yenilemez. Yalnız anlamlı personel
etkileşimi mevcut `POST /api/v1/auth/activity` akışıyla yeniler. Frontend bu çağrıyı gerçek
kullanıcı etkileşiminde, Auth cookie + Origin + CSRF sözleşmesiyle ve kontrollü sıklıkta yapar;
QR'ın guest aktivitesi personel aktivitesi değildir.

### Q7 — Public key dağıtımı ve rotation

İlk entegrasyon mevcut statik RS256 modelini kullanır. Auth session private key'i paylaşılmaz;
ona karşılık gelen Base64 X.509/SPKI public key operatör tarafından QR'a güvenli yapılandırmayla
verilir. QR yalnız doğrulama yapar.

QR tarafındaki ayar adı, `.env.example`/Compose aktarımı ve anahtar kurulum adımı uygulama
PR'ından önce açık onayla belirlenmelidir. JWKS, otomatik key discovery veya online rotation
uygulanmış kabul edilmez. Anahtar değişimi eski ve yeni public key'in kontrollü geçişini
gerektirir; tek taraflı değişim mevcut oturumları 401'e düşürür.

### Q8 — Cookie ve CSRF

Personel access cookie'si Path `/api/v1` olduğu için QR isteklerine tarayıcı tarafından eklenir;
JavaScript tokenı okuyup `Authorization` header'ına taşımaz. QR mutasyonları mevcut consumer
sözleşmesini uygular:

- tam eşleşen izinli `Origin`,
- credential'lı istek,
- `restofy_csrf` cookie ile `X-CSRF-TOKEN` eşitliği,
- uygun JSON content type ve method allowlist,
- eksik/çift/değiştirilmiş güvenlik girdisinde fail-closed.

Auth CSRF sırrı QR'a verilmez, refresh cookie path'i genişletilmez ve Auth'un refresh/MFA'ya
özel CSRF üretim kodu QR'a kopyalanmaz. Personel ve guest CSRF bağlamları ayrı kalır. Frontend,
login/refresh/MFA değişiminden sonra mevcut `/api/v1/auth/csrf` endpoint'inden tokenı yeniler.

### Q9 — Restoran kapsamı

Mevcut MVP tek `AUTH_RESTAURANT_ID` kullanır; access JWT'deki `restaurant_id` bu kapsamdır.
QR, bu claim'i hedef kaynağın restoranıyla eşleştirir. Request parametresi veya serbest header
üyelik kanıtı değildir.

Çoklu restoran üyeliği uygulanmış değildir. İleride eklendiğinde yeni üyelik/aktif restoran
seçimi ve sözleşme sürümü gerekir; mevcut token semantiği sessizce genişletilmez.

## PR #17 guest token notu

Hocanın yorumuna katılıyoruz: `ADR-QR-002` K3'teki guest tokenını HttpOnly cookie'ye taşıma
kararı yalnız QR tarafında kesinleşmiş sayılamaz. Order ve Waiter bugün Bearer token/CSRF
varsayımlarına sahiptir. K3, Hamza ve Umut'un açık onayı ile ortak browser sözleşmesi
belirlenene kadar **ÖNERİ / ORTAK KARAR BEKLİYOR** olarak kalmalıdır.

Personel cookie kararı guest taşıma kararını zorunlu kılmaz; staff, guest ve service
credential'ları birbirinin yerine kullanılmaz.

## Sahiplik ve sonraki uygulama

| İş | Sahip |
| --- | --- |
| `staff-v1` claim/cookie sözleşmesi ve Auth public key kaynağı | Kerem / Auth |
| QR JWT doğrulayıcı, principal, rol/tenant/kaynak kontrolleri | Alper / QR |
| QR personel CSRF filtresi ve negatif güvenlik testleri | Alper / QR |
| Anlamlı personel etkileşiminde Auth activity çağrısı | Frontend + Auth sözleşmesi |
| Guest token taşıma kararı | QR + Order + Waiter + frontend |
| Gerçek Auth → Gateway → QR E2E | Kerem + Alper |

Auth tarafında bu karar için yeni runtime kodu gerekmiyor. Uygulama PR'ı QR tarafında verifier,
CSRF ve yetki adaptörünü eklediğinde; yanlış imza/audience/tür/rol/restoran, süresi dolmuş token,
MFA/guest/service karışıklığı ve Auth kesintisi senaryoları doğrulanmalıdır.

## Dayanak

- [staff-v1 sözleşmesi](staff-contract.md)
- [QR devir kaydı](qr-service/README.md)
- [Auth kapsam kapanışı](completion-review.md)
- [Güvenlik politikası](../SECURITY.md)
- [PR #17](https://github.com/software-lecture-labs/Restofy/pull/17)
