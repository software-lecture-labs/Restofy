# ADR-MENU-005 — Personel kimliği: Auth `staff-v1` token'ının Menu'da yerel doğrulanması

- **Durum:** Kabul edildi (geliştirme aşaması). Üretimde etkinleştirme iki onaya bağlı: yeni ENV (`MENU_AUTH_STAFF_PUBLIC_KEY`) ve çalışan Auth ile uçtan uca deneme
- **Tarih:** 2026-11-23 (MENU-INT-01)
- **Sahip:** Menu (Merve); üretici sözleşme Auth (Kerem)
- **İlgili kod:** `StaffJwtVerifier`, `StaffCookieAuthenticationFilter`, `StaffCsrfProtection`, `StaffAuthProperties`, `StaffSecurity`,
  `VerifiedStaffActorProvider`, `SecurityProblems`, `MenuSecurityConfiguration`

## Bağlam

Yönetim uçları (kategori, ürün, seçenek, availability) `CurrentActorProvider` portuna bağlıydı ve üretimde fail-closed 401 veriyordu
(ADR-MENU-001/002, D-09). Auth artık personel oturumunu üretiyor: **staff-v1**. Kaynaklar (salt okunur incelendi; yerel çalışma ağacındaki
Auth kopyası geride olduğundan uzak dallardan okundu: `origin/feature/auth-service` @ `cd6508f`, `origin/dev` @ `ca9ab1a`):

- `docs/auth-service/staff-contract.md`, `q5-contract.md`, `qr-auth-integration-response.md`, `menu-service/README.md`,
  `docs/decisions/ADR-AUTH-Q5-coordinated-revocation.md`;
- üretim kodu: `StaffJwtTokens.issue`, `AuthCookies`, `AuthCsrfTokens`, `AuthRequestFilter`, test: `StaffConsumerContractTests`.

Sözleşmenin Menu'yu ilgilendiren özeti: HttpOnly `restofy_access` cookie'si (Path `/api/v1`, SameSite=Lax); RS256 JWT, claim'ler
`sub, iss, aud=[restofy-staff-api], iat, nbf, exp, jti, sid, role, auth_state=AUTHENTICATED, token_type=access, auth_policy=q5-idle-v1,
restaurant_id`; ömür en çok 300 sn; tüketici yalnız Auth'un X.509 **açık** anahtarını alır; mutasyonda izinli `Origin` + `restofy_csrf`
cookie'sinin `X-CSRF-TOKEN` ile eşitliği. Auth çevrimiçi introspection sunmaz (test `/internal/v1/auth/introspect` yolunun kapalı
olduğunu doğrular); iptal koordinasyonu (Q5) yalnız QR'a gider. Servis kimliği (`Authorization: Service`) genel bir **öneridir**,
Menu için tanımlı bir uç/anahtar yoktur.

## Karar

1. **Yerel doğrulama.** Token Auth'un açık anahtarıyla Menu'da doğrulanır; Auth'a çağrı yapılmaz. Kabul koşulları: yalnız RS256 (`none` ve
   açık anahtarla HS256 reddedilir), yapılandırılmış issuer, **tek** `restofy-staff-api` audience, `token_type=access`,
   `auth_state=AUTHENTICATED`, `auth_policy=q5-idle-v1`, `iat ≤ şimdi < exp`, ömür ≤ 300 sn, UUID biçimli `sub/sid/jti/restaurant_id`
   (restoran sıfır UUID değil), bilinen rol, token ≤ 4096 karakter. MFA-bekleyen, refresh, mail ve servis token'ları personel sayılmaz.
2. **Kimlik yalnız imzalı claim'lerden.** Restoran, kullanıcı ve rol claim'lerden gelir; `X-Restaurant-Id`, `X-Role`, `X-User-Id`,
   `Authorization`, sorgu ve gövde alanları okunmaz. Aynı isimli iki cookie belirsizdir ve reddedilir.
3. **Rol endpoint'te yeniden denetlenir.** Token'dan okunan rol yetki değildir: `AdminAccess.requireAdmin` (mevcut) doğrulanmış aktörden
   ADMIN ister; WAITER/KITCHEN 403 alır, gövde doğrulamasından önce 401/403 kararı korunur.
4. **Mutasyon kanıtı.** Spring `CsrfFilter` açık kalır; deposu `StaffCsrfProtection`'dır: tek `restofy_csrf` cookie'si (Auth biçimi
   `bitiş.nonce.imza`, süresi dolmamış) başlıkla birebir aynı olmalı, `Origin` tam bir adet ve izin listesinde olmalı, içerik türü varsa
   `application/json` olmalı. Eksik, çift, süresi dolmuş, biçimsiz veya farklı kanıt 403'tür ve hiçbir şey yazılmaz. Depo cookie yazmaz.
5. **Hata sözleşmesi.** 401/403 sabit RFC 9457 metni; imza, süre, audience gibi neden ayrımı sızmaz; `WWW-Authenticate: Basic` yoktur.
6. **Üç zincir, form/Basic giriş yok.** Müşteri zinciri değişmedi; personel zinciri `/api/v1/menu/**`; varsayılan zincir yalnız `health`
   açar. Boot'un oluşturduğu rastgele parolalı varsayılan kullanıcı boş `UserDetailsService` ile engellenir.
7. **Fail-closed yapılandırma.** Açık anahtar boşsa personel kimliği kapalıdır ve her yönetim isteği 401'dir (anahtar gömülmez, uydurulmaz).
   Yanlış/zayıf (<2048 bit) anahtar, issuer'sız anahtar veya joker/yollu origin uygulamayı başlatmaz; hata mesajı değeri yazmaz.
8. **İç servis çağıranı değişmedi.** `TrustedKitchenCallerProvider` fail-closed kalır. Personel kimliği servis kimliği değildir (test:
   doğrulanmış ADMIN/KITCHEN aktörü varken çağıran hâlâ boş, komut `UnauthenticatedException`).

## Seçenekler

| Seçenek | Artı | Eksi | Sonuç |
|---|---|---|---|
| **A. Yerel doğrulama (staff-v1)** | Auth'un belgelenmiş sözleşmesi; Auth kesintisinde bağımlılık yok; yeni uç yok | İptal en çok 300 sn gecikir | **Seçildi** |
| B. Her istekte Auth `GET /session` (Waiter'daki gibi) | Anlık iptal | Auth belgesi bunu sözleşme olarak vermiyor ("yeni introspection ayrıca güvenlik onayı ister"); ham credential Auth'a iletilir; HTTPS Auth URL'si ve yeni ENV gerekir; Auth kesintisi tüm yönetimi durdurur | Seçilmedi; Auth/Merve kararı gerekir |
| C. Menu'yu Q5 iptal hedefi yapmak | Kanıtlı iptal | Auth tarafında hedef/anahtar/ACK değişikliği | Kapsam dışı (Auth kodu değiştirilmez) |

## Sonuçlar ve bilinen sınırlar (gizlenmiyor)

- **İptal gecikmesi.** Logout, hesap pasifleştirme, rol değişikliği ve parola sıfırlama Menu'ye yeni token ile veya eski token dolunca (≤ 300 sn)
  yansır. `aRoleChangeIsHonouredWithTheNextTokenWhileTheOldOneLivesUntilItsSignedExpiry` bu sınırı belgeleyen testtir; **anlık iptal
  doğrulanmış değildir ve vaat edilmez.**
- **CSRF imzası.** Auth HMAC anahtarını paylaşmaz; Menu kanıtın biçimini ve süresini denetler, imzasını değil. Güvence `Origin` izin listesi +
  SameSite=Lax + çift gönderim eşitliğidir.
- **CORS yok.** Menu önceden olduğu gibi CORS açmaz. Farklı origin'li bir yönetim arayüzü ön-uçuş yanıtı gerektirir; CORS bir güvenlik
  sözleşmesi değişikliğidir ve ayrıca onaylanır.
- **Anahtar döndürme.** Auth tek anahtar kullanır (`kid`/halka yok); anahtar değişirse Menu'nun açık anahtarı da değişmelidir.
- **Paylaşılan ortamda doğrulama yok.** Gerçek Auth koduyla yapılan deneme izole bir ortamdadır (aşağıdaki bölüm); ekibin Docker ortamındaki
  Menu'ya anahtar aktarılmadığı ve oradaki Auth imajı eski/MFA zorunlu olduğu için orada uçtan uca deneme yapılmadı.

## Gerçek Auth ile doğrulama (24 Kasım 2026, izole ortam)

**Yöntem.** `origin/dev`'deki Auth ve QR kodu değiştirilmeden derlendi ve yerel bir geçici PostgreSQL ile çalıştırıldı; anahtarlar bu deneme
için üretildi (ekibin anahtarları, `.env` ve Compose kullanılmadı). Auth'un girişi, QR ile imzalı iptal el sıkışması (Q5) tamamlanana
kadar kapalıdır; bu yüzden gerçek QR de çalıştırıldı. Menu bu çalışma ağacından derlenip `MENU_AUTH_STAFF_PUBLIC_KEY` olarak Auth'un
açık anahtarıyla çalıştırıldı. Dev kullanıcıları ile gerçek `POST /api/v1/auth/login` yapıldı, dönen çerezler Menu'ya gönderildi.

**Gözlenen (gerçek Auth çıktısı):** `restofy_access` HttpOnly, Path `/api/v1`, SameSite=Lax, 300 sn; JWT başlığında yalnız `alg=RS256`
(`kid` yok); claim'ler `sub, iss, aud=[restofy-staff-api], iat, nbf, exp, jti, sid, role, auth_state, restaurant_id, token_type,
auth_policy` ve ömür 300 sn: Menu'nun doğrulayıcısı bunları olduğu gibi kabul etti. Auth'un `restofy_csrf` değeri (`bitiş.nonce.imza`)
Menu'nun biçim denetiminden geçti.

| Senaryo | Sonuç |
|---|---|
| Cookie yok / sahte `Authorization: Bearer` / token sorgu parametresinde / refresh cookie'si access yerine | 401 |
| ADMIN gerçek cookie: kategori listele/oluştur/güncelle, ürün oluştur | 200 / 201 |
| Oluşturmada CSRF başlığı yok, Origin yok, izinli olmayan Origin, farklı CSRF değeri | 403, kayıt yok |
| WAITER ve KITCHEN gerçek cookie: GET ve POST | 403 |
| İmzası bozulmuş token, başka tokenın payload'ı + özgün imza | 401 |
| Sahte `X-Restaurant-Id` / `X-Role` başlığı | Kayıt başlığa değil Auth'un imzalı `restaurant_id`'sine yazıldı (veritabanından doğrulandı) |
| Auth `refresh` ile yeni access cookie | Menu kabul etti (200) |
| Süresi dolan gerçek token (300 sn sonra) | 401; taze girişle 200 |
| Müşteri (public) uçları ve `actuator/health` cookie'siz | Kimlik engeli yok |
| **Auth logout (204) sonrası eski access cookie** | Auth `/session` **401**, Menu **200** (süre dolana kadar) |

Son satır, "anlık iptal yok" sınırının gerçek Auth'la **ölçülmüş** hâlidir; Menu'ya Auth'a her istekte soru sorma ekleyerek kapatılmadı
(Seçenek B, yukarıda; Auth sözleşmesinde yok, ayrı karar ister).

**Bu doğrulama neyi kanıtlamaz.** Ekibin paylaşılan Docker ortamını (orada çalışan Auth imajı 5 Kasım tarihlidir, `JWT_ACCESS_TTL_SECONDS=900`
ve MFA zorunludur; dev'deki Auth kodu ise ömrü tam 300 sn'ye sabitler ve 900 ile başlamaz); gerçek bir MFA'lı yönetici hesabını; hesap
pasifleştirme ve rol değişikliğinin yansımasını (Auth'ta bunu tetikleyen bir uç denenmedi); D-09 servis kimliğini. Bu satırlar açıktır.

## MENU-INT-01 gereksinim durumu (güncelleme: 24 Kasım 2026)

**Görev bütünüyle TAMAMLANMADI.** Menu'nun kendi kodu ve testleri hazırdır ve gerçek Auth kodu ile izole ortamda doğrulanmıştır;
paylaşılan ortamda etkinleşmesi ve bazı maddeler dış kararlara bağlıdır.

| # | Gereksinim | Durum | Not |
|---|---|---|---|
| 1 | Auth JWT/HttpOnly cookie entegrasyonu | KISMİ | Kod ve testler tamam; gerçek Auth koduyla izole ortamda doğrulandı (yukarıdaki bölüm). `MENU_AUTH_STAFF_PUBLIC_KEY` onaysız/aktarılmamış → paylaşılan Docker'da 401. Paylaşılan ortamda deneme yok |
| 2 | Kategori/ürün/seçenek ADMIN yetkisi | TAMAMLANDI | WAITER/KITCHEN 403; testli. Etkinleşmesi madde 1'deki anahtara bağlı |
| 3 | CSRF ve Origin | KISMİ | Origin allowlist, çift gönderim eşitliği, içerik türü, süre: tamam. **CSRF imzası doğrulanmıyor** (HMAC Auth'ta), CORS/ön-uçuş yok |
| 4 | Güncel oturum, hesap aktifliği, rol değişikliği | KISMİ | Yeni token'ın rolü hemen geçerli; logout/pasifleştirme/rol değişikliği eski token için en çok 300 sn gecikir. **Logout için gerçek Auth'la ölçüldü**: Auth hemen reddetti, Menu süre dolana kadar kabul etti. Anlık yansıma yok |
| 5 | Order/Kitchen güvenilir servis kimliği | BEKLİYOR | D-09 onaysız; yalnız fail-closed korunuyor, çalışan mekanizma yok |
| 6 | Restoran kimliği doğrulanmış kullanıcıdan | TAMAMLANDI | İmzalı `restaurant_id`; sahte başlık/sorgu/gövde testli |
| 7 | Güvenlik testleri | KISMİ | Yetkisiz, yanlış rol, başka restoran, geçersiz/süresi dolmuş JWT: tamam. "Servis kesintisi": doğrulama yerel olduğu için Auth çağrısı yok, test edilecek kesinti yok. Servis kimliği testi yalnız fail-closed. Gerçek Auth ile izole E2E yapıldı; paylaşılan ortamda yok |

## Engellenmiş / bekleyen maddeler

| Madde | Neden | Karar sahibi |
|---|---|---|
| `MENU_AUTH_STAFF_PUBLIC_KEY` ENV'i, `.env.example` ve Compose aktarımı | Yeni ENV ve ortak dosyalar (SECURITY.md §1) | Ekip / Alper (Compose), Kerem (anahtar sağlama) |
| Anlık iptal (introspection veya Menu'yu Q5 hedefi yapma) | Auth sözleşmesinde yok; servis kimliği, timeout ve kesinti politikası gerekir | Auth + Menu |
| Kitchen/Order → Menu servis kimliği ve HTTP ucu | D-09: token biçimi, `aud`, kapsam, replay, izin matrisi onaysız; Kitchen'da Menu adaptörü yok | Auth + Kitchen + Order + Menu |
| CORS/ön-uçuş | Yeni güvenlik yüzeyi; arayüz henüz yok | Menu + frontend |
| Paylaşılan ortamda uçtan uca doğrulama | Anahtar aktarımı (ENV onayı), güncel Auth imajı ve `JWT_ACCESS_TTL_SECONDS=300`, MFA'lı hesap gerekir | Menu + Auth + Alper |
