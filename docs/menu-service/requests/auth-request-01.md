# REQUEST-01 · Menu Service Auth ve güvenlik entegrasyon ihtiyaçları

**24 Kasım 2026 · Merve (Menu) → Kerem (Auth) · bilgi: Alper (ortak Compose/ENV), Hamza (Order), Nisa (Kitchen)**

| | |
|---|---|
| **Talep ID** | AUTH-REQUEST-01 (Menu'nun Auth'a ilk talebi, MENU-INT-01'in ilk talebi) |
| **Talep eden** | Menu Service (Merve) |
| **İlgili servis ve ekip** | Auth Service (Kerem). Madde 7 ortak altyapı olduğu için Alper; madde 5 Order (Hamza) ve Kitchen (Nisa) ile ortak |
| **Bağlı PDA görevi** | MENU-INT-01 — Menu Service Auth ve Güvenlik Entegrasyonu |
| **Durum** | **Auth yanıtladı (PR #46 yorumları, sözleşme PR #67).** MENU-INT-01 **KISMİ**: ENV ve Compose bağlantısı tamam, güncel `dev` Auth'uyla yerel E2E yapıldı; pasifleştirme/rol senaryoları PR #81'e bağlı (bloke), ortak ortamda deneme yok |

Bu belge yalnız bir taleptir. Auth, Order, Kitchen veya QR kodunda değişiklik içermez ve hiçbirini Menu tarafı uygulamış gibi anlatmaz.

## Dayanak

- Auth: `origin/dev` (`ca9ab1a`, Q5 dahil) ve `origin/feature/auth-service` (`cd6508f`; `docs/auth-service/*` paketi henüz `dev`'de değil). Kaynaklar: `StaffJwtTokens`, `AuthCookies`, `AuthCsrfTokens`, `staff-contract.md`, `q5-contract.md`, `ADR-AUTH-Q5-coordinated-revocation.md`, `qr-auth-integration-response.md`.
- Menu: `feature/menu-service` üzerindeki yerel çalışma (`688947d` tabanı). **Bu çalışma henüz commit edilmedi ve `dev`'de yok**; MENU-INT-01 PR'ında ayrıca incelemeye sunulacak. Belgeleri: `backend/services/menu-service/README.md`, `docs/decisions/ADR-MENU-005-auth-personel-kimligi.md` (aynı çalışmada, `dev`'de henüz yok).

## Menu'da bugün mevcut olanlar

Yerel Menu çalışmasında `mvn -pl services/menu-service clean verify`: **1422 test başarılı, 0 hata, %98,19 line coverage** (Java 25, Testcontainers PostgreSQL 17).

| Konu | Durum |
|---|---|
| `restofy_access` cookie'sinin Auth açık anahtarıyla **yerel** RS256 doğrulaması | Var. Yalnız RS256; `iss`; tek `restofy-staff-api` `aud`; `token_type=access`; `auth_state=AUTHENTICATED`; `auth_policy=q5-idle-v1`; `iat/nbf/exp/jti/sid`; ömür ≤ 300 sn; UUID biçimli `sub/sid/jti/restaurant_id`; rol `ADMIN/WAITER/KITCHEN` |
| Kimlik kaynağı | Restoran, kullanıcı ve rol yalnız imzalı claim'lerden; başlık, `Authorization`, sorgu ve gövde alanları okunmuyor |
| Kategori, ürün, seçenek, availability yönetimi | Yalnız ADMIN; WAITER/KITCHEN 403; başka restoran verisi 404 |
| Mutasyon kanıtı | `Origin` (tam bir adet, allowlist), JSON içerik türü, `restofy_csrf` cookie'sinin `X-CSRF-TOKEN` ile eşitliği ve süresi. **Kriptografik imza doğrulaması yok** (madde 3) |
| Yapılandırma | `MENU_AUTH_STAFF_PUBLIC_KEY`, `JWT_ISSUER`, `CORS_ALLOWED_ORIGINS` okunuyor. Anahtar boşsa her yönetim isteği 401 |
| Servis kimliği (Order/Kitchen → Menu) | **Yok.** `TrustedKitchenCallerProvider` fail-closed; Menu'da iç HTTP ucu yok |
| Gerçek Auth ile uçtan uca test | **Yapılmadı.** Testler JVM içinde üretilmiş anahtar çiftiyle Auth'un token biçimini taklit eder |

## İstenen kararlar ve geliştirmeler

### 1. Açık anahtar sözleşmesi

**Mevcut:** `dev` `.env.example`'da Waiter için `WAITER_AUTH_STAFF_PUBLIC_KEY`, Mail için `MAIL_AUTH_PUBLIC_KEY` var; QR'ın `application.properties`'i personel anahtarını `MAIL_AUTH_PUBLIC_KEY`'den okuyor. `feature/auth-service` ise Mail için ayrı bir signer tanımlıyor (`MAIL_AUTH_PUBLIC_KEY` artık o signer'ın açık anahtarı). `key-setup.md`: personel açık anahtarı `secrets/auth-staff-public.key` olarak yazılıyor, "yeni ENV değil".

**İstenen:**
- Menu için `MENU_AUTH_STAFF_PUBLIC_KEY` adı uygun mu? Yoksa personel açık anahtarı için ortak tek bir ad mı kullanılmalı? QR'ın `MAIL_AUTH_PUBLIC_KEY` kullanımı Mail ayrı signer'a geçince personel doğrulamasını bozar mı?
- Açık anahtarı tüketicilere dağıtma yolu (şu an elle kopyalama): sorumlu, biçim (Base64 X.509 RSA ≥ 2048 bit) ve ortamlar arası süreç.
- Anahtar döndürme ve `kid`: Auth tek anahtar kullanıyor, token'da `kid` yok. Planlı rotasyon olacak mı? Olacaksa tüketici nasıl eski ve yeni anahtarı birlikte kabul edecek?
- Menu yalnız **açık** anahtar ister; özel anahtar veya secret paylaşılması istenmiyor.

**Kabul:** Ad, dağıtım ve rotasyon kararı Auth dokümanında yazılı; Menu aynı adı `application.properties` ve README'de kullanır.

### 2. Personel JWT sözleşmesi

**Mevcut:** `StaffJwtTokens.issue` (`dev`) `auth_policy=q5-idle-v1` üretiyor ve access ömrü ayarı 300 sn'ye sabit (`MIN=MAX=5 dk`). `staff-contract.md` (`feature/auth-service`) ise `auth_policy` claim'ini listelemiyor ve "access TTL 300–3600 sn, varsayılan 900" diyor. QR ve Waiter `auth_policy`'yi zorunlu tutuyor; Menu de zorunlu tutuyor.

**İstenen:**
- `restofy_access` biçimini (HttpOnly, Path `/api/v1`, SameSite=Lax) ve şu claim'leri **ortak sözleşmede** kesinleştirin: `iss`, `aud` (tek elemanlı dizi `["restofy-staff-api"]`), `sub`, `sid`, `jti`, `role`, `restaurant_id`, `token_type`, `auth_state`.
- `auth_policy=q5-idle-v1` sözleşmeye açıkça eklensin (veya farklı karar bildirilsin). Eski `auth_policy`'siz token'lar tüketiciler tarafından reddedilmeli mi?
- Maksimum 300 sn kuralı sözleşmede sabitlensin; `staff-contract.md`'deki 300–3600 / 900 ifadesi koda göre düzeltilsin.
- `restaurant_id` bugün tek sabit restoran (`AUTH_RESTAURANT_ID`). Çok restoranlı üyelik gelirse claim biçimi değişecek mi?

**Kabul:** `staff-contract.md` koddaki token ile birebir uyumlu; Menu'nun doğrulayıcısı bu belgeden doğrulanabilir.

### 3. CSRF güvenliği

**Mevcut:** Auth CSRF değerini `bitiş.nonce.imza` biçiminde HMAC ile üretiyor ve refresh/pending credential özetine bağlıyor. HMAC anahtarı Auth'ta kalıyor. Menu yalnız biçimi, süreyi, cookie–başlık eşitliğini ve `Origin` allowlist'ini denetleyebiliyor.

**İstenen:** Menu için yeterli doğrulama seviyesini belirleyin:
- (a) Mevcut çift gönderim + `Origin` allowlist yeterli kabul edilir (imza Auth'ta kalır), veya
- (b) Auth, tüketicilerin CSRF imzasını doğrulayabileceği güvenli bir mekanizma tanımlar. Auth'un gizli HMAC anahtarını paylaşması **istenmiyor**; yeni bir uç, ayrı doğrulama anahtarı veya başka bir yol Auth'un tasarımıdır.

Henüz var olmayan bir uç varmış gibi varsaymıyoruz.

**Kabul:** Karar Auth dokümanında yazılı; Menu belgesi ve testi seçilen seviyeyi yansıtır.

### 4. Oturum iptali, hesap aktifliği ve rol değişikliği

**Mevcut:** Auth introspection sunmuyor; Q5 iptal koordinasyonu yalnız QR'a gidiyor. Yerel doğrulayan tüketicide logout, pasifleştirme ve rol değişikliği en çok 300 sn gecikmeyle yansır. Waiter (`dev`) bunun yerine her istekte Auth'un `GET /api/v1/auth/session` ucunu çerezle çağırıyor; bu `staff-contract.md`'de ortak sözleşme olarak yazılı değil. Menu yerel doğrulamayı seçti ve bu gecikmeyi README/ADR-MENU-005'te belgeledi. **Anlık yansıma bugün yok ve vaat edilmiyor.**

**İstenen:** Karar verin:
- (a) Menu için en çok 300 sn gecikme **kabul**, veya
- (b) Menu Q5 iptal hedefi olur (hedef kaydı, imzalı komut, ACK), veya
- (c) Tüketicilerin kullanması onaylı bir oturum doğrulama mekanizması (servis kimliği, timeout, kesinti politikası dahil). Waiter'ın `/session` kullanımı bu sözleşme olarak onaylanıyor mu?

**Kabul:** Seçilen yol Auth sözleşmesinde yazılı; seçenek (b) veya (c) ise Menu ayrı onaylı iş olarak uygular ve test eder.

### 5. Servis kimliği — D-09 (Order/Kitchen → Menu)

**Mevcut:** D-09 çözülmedi. `staff-contract.md` genel servis JWT'sini "öneri" olarak anlatıyor ("bu modelin tüm servislerde uygulandığı iddia edilmez"). Uygulanmış ikili sözleşmeler var: QR→Order (`Authorization: Service`, `iss=restofy-qr`, `aud=restofy-order`, scope, ≤ 30 sn, `jti`, `method/target/body_sha256`), Waiter→QR (`qr:guest-validate`), Notification→Auth. Menu için tanımlı bir çağıran, anahtar, `aud` veya uç yok; Kitchen'da Menu adaptörü yok (`MENU_NOT_INTEGRATED`). Menu'da yalnız `KitchenAvailabilityCommandService` ve fail-closed `TrustedKitchenCallerProvider` var (ADR-MENU-002).

**İstenen (sorumluluklar ayrı):**
- **Auth (Kerem):** Auth bu modelde anahtar otoritesi mi, yoksa her çağıran kendi anahtarını mı yönetir (QR→Order modeli gibi)? Genel servis JWT önerisi onaylı sözleşme mi?
- **Menu (Merve), alıcı olarak:** Çağıran listesi (`KITCHEN`, `ORDER`), her çağıran için izinli işlem matrisi, doğrulama kodu. Menu bunu onaydan önce uygulamaz.
- **Kitchen (Nisa) ve Order (Hamza), çağıran olarak:** Kendi servis imzalayıcıları ve Menu çağrıları.
- Taslak (**onaylı değil**): QR→Order biçimi örnek alınarak çağıran başına ayrı RSA anahtarı ve `kid`, `iss`/`sub` çağıran, `aud=restofy-menu`, çağıran başına scope (örn. availability komutu / uygunluk teklifi), ≤ 30 sn ve `jti`, `method/target/body_sha256` bağı. Uç yolu ve alan yapısı ayrı onay ister (`docs/SECURITY.md` §8, §17).

**Kabul:** Token biçimi, `aud`, scope, yetki matrisi, süre ve replay koruması yazılı; alıcı ve çağıranlar için sorumlular belli. Bu olmadan Menu fail-closed kalır.

### 6. Gerçek Auth ile entegrasyon testi

**İstenen:** Menu'nun gerçek Auth'la şu akışları doğrulayabilmesi için test sözleşmesi: personel girişi (ADMIN MFA dahil), cookie oluşturulması, ADMIN yetkili istek, CSRF korumalı mutasyon, logout, hesap pasifleştirme, rol değişikliği, geçersiz ve süresi dolmuş JWT. Özellikle:
- Test ortamı için hesap/rol bootstrap'ı (`AUTH_DEV_*`) ve `AUTH_MFA_REQUIRED` kullanımı Menu'nun denemesi için uygun mu?
- Rol değişikliği ve pasifleştirmenin ACK_REQUIRED (503/PENDING) davranışında Menu'nun gözlemleyeceği sonuç nedir?
- Gerçek secret veya özel anahtar **istenmiyor**: tüketici yalnız açık anahtarı alır; test hesapları dev bootstrap ile üretilir.

**Kabul:** Menu, Auth'u yerelde ayağa kaldırıp yukarıdaki akışları elle/otomatik doğrulayabilir; sonuçlar MENU-INT-01 raporuna yazılır.

### 7. Ortak yapılandırma (`.env.example` ve Docker Compose)

**Mevcut:** `MENU_AUTH_STAFF_PUBLIC_KEY` yeni bir ENV adıdır ve `.env.example` ile Compose'a henüz eklenmedi; Menu bu dosyalara dokunmadı. Compose Menu container'ına yalnız DB ve `MENU_IMAGE_ALLOWED_ORIGINS` aktarıyor. Auth bu ortak dosyalardan tek başına sorumlu değil.

**İstenen:** Alper (ortak Compose/ENV koordinasyonu) ve Kerem (anahtar sağlama): madde 1'deki ad onaylandıktan sonra `MENU_AUTH_STAFF_PUBLIC_KEY`, `JWT_ISSUER` ve `CORS_ALLOWED_ORIGINS`'in Menu container'ına aktarılması. Onay verilene kadar Docker'da Menu her yönetim isteğine 401 verir (fail-closed). `docs/SECURITY.md` §1 gereği yeni ENV için açık onay gerekir.

## Sorumluluk sınırları

| Konu | Sorumlu |
|---|---|
| Token/cookie üretimi, claim sözleşmesi, CSRF imzası, iptal mekanizması | Auth (Kerem) |
| Açık anahtar doğrulaması, rol ve restoran yetkisi, tenant izolasyonu, Menu ucu | Menu (Merve) |
| `.env.example`, Compose, ortak ağ | Alper (koordinasyon) |
| Menu'ya çağrı yapan servis kimliği | Order (Hamza), Kitchen (Nisa) |

Menu, Auth kodunu değiştirmeyi önermiyor; Auth'a ait değişiklikler Auth sahibinin kararı ve PR'ıdır.

## Bağımlılıklar

- MENU-INT-01'in KISMİ'den kapanması: madde 1 ve 7 (etkinleştirme), madde 6 (uçtan uca doğrulama), madde 4 ve 5 (kapsam kararları).
- Madde 5, Order ve Kitchen servis kimliği işleriyle ortak.
- Menu çalışması `dev`'e girmeden gerçek Auth denemesi yapılamaz; MENU-INT-01 PR'ı bu belgeden bağımsız açılacak.

## Açık konular

- Madde 4 seçeneği (a/b/c) ve Waiter'ın `/session` kullanımının statüsü.
- Madde 3 seviyesi (a/b).
- Auth rotasyonu ve `kid` planı.
- Çok restoranlı üyelik geldiğinde `restaurant_id` biçimi.
- Menu CORS ön-uçuş desteği: arayüz gelince Menu'nun ayrı, onaylı işi; bu talebin parçası değil.

## 8. Auth yanıtı ve güncel durum (28 Kasım 2026)

Kerem (PR #46): açık anahtar adı `MENU_AUTH_STAFF_PUBLIC_KEY` uygun, değeri `MAIL_AUTH_PUBLIC_KEY`; `kid` yok, planlı rotasyon yok (değişirse önceden haber); token ömrü **en çok** 300 sn; CSRF için çerez-başlık eşitliği ve tek izinli `Origin` yeterli, imza Auth'ta ve paylaşılmaz; Menu için en çok 300 sn iptal gecikmesi kabul, ek mekanizma yok; Auth merkezi servis kimliği vermez, her çağıran kendi anahtarıyla imzalar (D-09 kararı Menu/Order/Kitchen ve hocada); sözleşme `docs/auth-service/staff-contract.md`. Son yorum: pasifleştirme/rol denemesi DB'den değil, PR #81 uçlarıyla yapılacak; Menu'de değişiklik gerekmiyor.

| Madde | Durum |
|---|---|
| 1 Açık anahtar adı ve değeri | Onaylı. `.env.example` ve Compose'un `menu-service` bloğuna eklendi (değersiz); yerel `.env.dev` dolduruldu. Ortak ortamda değer tanımı Alper/Kerem'de |
| 2 JWT sözleşmesi | Menu doğrulayıcısı sözleşmeyle uyumlu, kod değişmedi |
| 3 CSRF | Seviye (a), kod değişmedi |
| 4 İptal | Seçenek (a): en çok 300 sn, gerçek Auth'la ölçüldü |
| 5 Servis kimliği | Auth'tan beklenen yok; Order → Menu ucu kendi imza modeliyle tamamlandı (PR #49), Kitchen → Menu bekliyor |
| 6 Gerçek Auth testi | Yerel izole E2E yapıldı (ADR-MENU-005 güncellemesi). Pasifleştirme/rol: **bloke (PR #81)**. MFA'lı akış ve Gateway/tarayıcı: yapılmadı |
| 7 ENV/Compose | **Tamamlandı** (ENV adı onaylı) |
