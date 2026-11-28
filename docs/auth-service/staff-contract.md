# Auth personel oturumu sözleşmesi

Bu belge, Auth'un personel oturumu için diğer servislere verdiği sözleşmeyi tanımlar: token biçimi, çerezler, CSRF değeri ve `GET /api/v1/auth/session` ucu. Order, Waiter, Kitchen, Menu ve QR personel isteklerini bu kurallara göre doğrular. Burada yazmayan her şey Auth'un iç ayrıntısıdır ve habersiz değişebilir. Burada yazan bir şey değişecekse önce tüketicilere haber verilir.

QR'a özgü erişim iptali (Q5) ayrı belgededir: [Q5 sözleşmesi](q5-contract.md). Eski `feature/auth-service` dalındaki `staff-contract.md` (300–3600 sn, varsayılan 900) geçerli değildir.

## İmza anahtarı

- Personel token'larını tek bir RSA anahtarı imzalar (`AUTH_JWT_PRIVATE_KEY`, en az 2048 bit). Algoritma yalnız RS256.
- Token başlığında `kid` yoktur. Tüketici tek bir açık anahtarla doğrular.
- Açık anahtar Base64 X.509 biçimindedir. `node ops/auth/generate-keys.mjs` çıktısındaki `MAIL_AUTH_PUBLIC_KEY` bu çiftin açık anahtarıdır; tüketici kendi değişkenine (`ORDER_AUTH_STAFF_PUBLIC_KEY`, `WAITER_AUTH_STAFF_PUBLIC_KEY`, `KITCHEN_AUTH_STAFF_PUBLIC_KEY`, `MENU_AUTH_STAFF_PUBLIC_KEY`) bu değeri yazar.
- Özel anahtar, CSRF sırrı ya da başka bir Auth sırrı hiçbir servise verilmez.
- Planlı anahtar değişimi yoktur. `kid` olmadığı için değişim bütün tüketicilerin aynı anda güncellenmesini gerektirir; önceden duyurulur.

## Access token

| Alan | Değer |
| --- | --- |
| `iss` | `JWT_ISSUER` (varsayılan `restofy-auth`) |
| `aud` | `["restofy-staff-api"]`, tek eleman |
| `sub` | Personel kullanıcı kimliği (UUID) |
| `sid` | Oturum kimliği (UUID) |
| `jti` | Token kimliği (UUID) |
| `iat`, `nbf` | Üretim anı; ikisi aynı |
| `exp` | `iat + 300` sn; oturumun hareketsizlik ya da mutlak bitişi daha yakınsa o |
| `role` | `ADMIN`, `WAITER` veya `KITCHEN` |
| `restaurant_id` | `AUTH_RESTAURANT_ID` (UUID). MVP'de tek restoran |
| `token_type` | `access` |
| `auth_state` | `AUTHENTICATED` |
| `auth_policy` | `q5-idle-v1` |

Ömür ayarı (`JWT_ACCESS_TTL_SECONDS`) yalnız 300 kabul eder; başka bir değerle Auth başlamaz. Bu yüzden token ömrü hiçbir zaman 300 sn'yi geçmez.

Tüketici şunları denetler ve biri tutmazsa isteği 401 ile reddeder (Order, Waiter ve QR bugün bunların hepsini yapıyor):

1. İmza RS256 ve açık anahtarla doğru.
2. `iss` beklenen değer, `aud` tam olarak `restofy-staff-api`.
3. `token_type=access`, `auth_state=AUTHENTICATED`, `auth_policy=q5-idle-v1`.
4. `iat`, `nbf`, `exp`, `jti` var; `exp` geçmemiş ve `exp - iat` en çok 300 sn.
5. `role` o işlem için izinli.

`restaurant_id`'yi servisin kendi restoranıyla karşılaştırmak ek bir güvencedir; QR yapıyor. MVP'de Auth tek bir `AUTH_RESTAURANT_ID` ile çalıştığı için başka bir değer üretmez.

Kimlik yalnız bu claim'lerden alınır; istek gövdesindeki ya da başlıktaki kullanıcı bilgisi yetki kararında kullanılmaz.

Claim biçimi değişirse (örneğin çok restoranlı üyelik) `auth_policy` değeri de değişir. `q5-idle-v1` isteyen bir tüketici bu durumda güvenli tarafta kalıp reddeder.

## Çerezler

| Çerez | Path | HttpOnly | Ömür | İçerik |
| --- | --- | --- | --- | --- |
| `restofy_access` | `/api/v1` | Evet | Token ömrü (en çok 300 sn) | Access token |
| `restofy_refresh` | `/api/v1/auth` | Evet | Oturumun mutlak bitişine kadar | Yenileme değeri; Auth dışına gitmez |
| `restofy_mfa` | `/api/v1/auth` | Evet | En çok 5 dk | MFA bekleyen oturum token'ı (`aud=restofy-auth-mfa`); Auth dışına gitmez |
| `restofy_csrf` | `/api/v1` | Hayır | 15 dk | CSRF değeri |

Hepsi `SameSite=Lax`. `Secure`, `AUTH_COOKIE_SECURE` ile belirlenir; `false` yalnız geliştirme ortamında kabul edilir.

Tüketici servise gelen tek personel çerezi `restofy_access`'tir. Aynı adla birden fazla çerez gelirse Auth bunu belirsiz sayar ve 401 döner.

## CSRF

Durum değiştiren personel isteklerinde (`POST`, `PUT`, `PATCH`, `DELETE`) tarayıcı `restofy_csrf` çerezindeki değeri `X-CSRF-TOKEN` başlığında da gönderir.

Değerin biçimi `bitiş.nonce.imza`:

- `bitiş`: değerin geçerlilik sonu, Unix zamanı (saniye, onluk tamsayı). Üretimden 15 dk sonrası.
- `nonce`: 32 rastgele bayt, dolgusuz base64url (43 karakter).
- `imza`: HMAC-SHA256, dolgusuz base64url (43 karakter). Anahtarı yalnız Auth'tadır.

Auth dışındaki bir servis imzayı doğrulayamaz ve doğrulamaya çalışmaz. Tüketicinin denetimi:

1. Çerezdeki değerle `X-CSRF-TOKEN` birebir aynı.
2. `Origin` başlığı var ve servisin izinli tek bir origin'iyle birebir aynı.

Korumanın dayanağı, başka bir sitenin bu çerezi okuyamaması ve bu başlığı yazamamasıdır. Değerin yukarıdaki biçimde olduğuna ve `bitiş`in geçmediğine bakmak ek bir denetimdir; Order ve Waiter yapıyor. Bu biçime dayanan bir servis varsa biçim habersiz değişmez, değişecekse önceden duyurulur.

## `GET /api/v1/auth/session`

Servisler, oturumun o an hâlâ geçerli olduğunu Auth'a sormak için bu ucu kullanabilir. Bu bir zorunluluk değildir: yalnız yerel token doğrulaması da bu sözleşmeye uygundur. O durumda iptal, pasifleştirme ve rol değişikliği en geç 300 sn içinde yansır.

Kurallar:

- Personel çerezi (`restofy_access`) yalnız bu uca ve HTTPS ile iletilir. Tek istisna geliştirme ve test ortamında iç ağda HTTP'dir: `INTERNAL_HTTP_ALLOWED=true`, `APP_ENV` `dev`, `local` ya da `test`, adres bir Compose servis adı ya da loopback ([ADR-ORTAK-001](../decisions/ADR-ORTAK-001-ic-agda-http.md)). Başka bir servise iletilmez, loglanmaz.
- İstekte `Origin` başlığı gönderilmez. Gönderilirse Auth'un izinli origin listesinde olmalıdır, yoksa 403 döner.
- Çağrı etkinlik sayılmaz, hareketsizlik süresini uzatmaz. Süreyi yalnız `POST /api/v1/auth/activity` uzatır.
- Sonuç o isteğin ötesinde önbelleğe alınmaz. Auth cevabı `Cache-Control: no-store` ile döner.

Cevaplar:

| Durum | Anlamı | Tüketici |
| --- | --- | --- |
| 200 ve gövdede `status=AUTHENTICATED` | Oturum geçerli | İşleme devam eder |
| 401 | Token geçersiz ya da süresi dolmuş, oturum iptal edilmiş, kullanıcı pasif veya rolü değişmiş | Reddeder |
| Diğer her şey (403, 5xx, zaman aşımı, yönlendirme, `status` farklı) | Doğrulanamadı | İşlemi yapmaz |

200 gövdesi: `status`, `role`, `idleExpiresAt`, `absoluteExpiresAt`, `mfaSetupRequired`. `mfaSetupRequired` yalnız `status` `MFA_PENDING` iken dolar; diğer durumlarda `null` gelir. Tüketici rolü yine token'dan alır; Auth, token'daki rol kullanıcının güncel rolüyle uyuşmuyorsa zaten 401 döner.

Bu uca ileride bir hız sınırı gelirse çağıran servisin adresine göre olmaz; önceden duyurulur.

## Hareketsizlik ve oturum süresi

- Hareketsizlik süresi ADMIN ve WAITER için 15 dk, KITCHEN için 60 dk.
- Access token'ın `exp` değeri hareketsizlik bitişini geçmez.
- Süreyi yalnız `POST /api/v1/auth/activity` uzatır (CSRF ve `Origin` gerekir). Oturum okumak, polling ve yenileme uzatmaz. Hangi etkileşimin etkinlik sayılacağı #64'te konuşuluyor.

## Bu sözleşmede olmayanlar

- **Servis kimliği:** Auth merkezi bir servis kimliği vermez. Servisler arası çağrılarda bugünkü düzen, çağıran servisin kendi anahtarıyla imzaladığı istektir (QR→Order, Waiter→QR #41, Waiter→Order #45). Ortak sözleşme (D-09) ayrı karardır.
- **İç ağda TLS:** İç ağda TLS ortak altyapı kararıdır (#63); o gelene kadar geliştirme ve test ortamında iç ağda HTTP istisnası [ADR-ORTAK-001](../decisions/ADR-ORTAK-001-ic-agda-http.md)'deki kurala bağlıdır. Auth ortak adresi ya da Compose'u tek başına değiştirmez.
- **Anlık iptal bildirimi:** Auth yalnız QR'a iptal bildirir (Q5). Diğer servisler için iptal ya `/session` ile anında ya da token ömrüyle en geç 300 sn içinde yansır.
