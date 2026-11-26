# Kitchen → Auth talepleri 01 (KITCHEN-INT-01)

- **Tarih:** 26 Kasım 2026 (Auth yanıtlarıyla güncellendi)
- **Talep eden:** Kitchen (Nisa)
- **İlgili servis / sorumlu:** Auth (Kerem). Ortak `docker-compose.yml` / `.env.example` ve TLS için ayrıca ekip kararı gerekir.
- **Yanıt kaynağı:** [PR #61](https://github.com/software-lecture-labs/Restofy/pull/61) yorumları (26 Kasım 2026).

Bu belge Auth kodunda veya ortak dosyalarda değişiklik içermez.

## Okunan kaynakların durumu (26 Kasım 2026)

| Kaynak | Durum |
|---|---|
| [PR #67](https://github.com/software-lecture-labs/Restofy/pull/67) — personel oturumu sözleşmesi, `3c91e6e` | Açık, bir onay almış, **birleşmemiş**. PR sahibinin yorumuna göre yalnız belge değiştiği için zorunlu kontrol tetiklenmiyor |
| [#63](https://github.com/software-lecture-labs/Restofy/issues/63) — iç ağda HTTPS | Açık. İnceleyen yorumu sınırlı bir HTTP istisnasını önerdi |
| [PR #69](https://github.com/software-lecture-labs/Restofy/pull/69) — #63 için ADR-ORTAK-001, `3a94fef` | Açık, **birleşmemiş**; ADR durumu "Önerildi, ayrıntılar onay bekliyor". İnceleyen **değişiklik istedi:** `dev`/`test` profil koşulu Compose'daki entegrasyon profiliyle çakışabilir |
| [#64](https://github.com/software-lecture-labs/Restofy/issues/64) — personel etkinliği | Açık. Öneri bir inceleyen yorumunda uygun bulundu; frontend uygulanmadı |

PR #67'deki sözleşme `dev`'e girmiş sayılmaz. `feature/auth-service` dalındaki eski `staff-contract.md` geçerli değildir. `ADR-AUTH-Q5` ve `ADR-QR-003` yalnız QR kapsamındadır ve Kitchen'ı bağlamaz.

## Durum özeti

| Talep | Konu | Yanıt geldi | Kitchen'da uygulandı | Gerçek ortamda doğrulandı |
|---|---|---|---|---|
| AUTH-01 | Auth açık anahtarı | Evet | Evet (ayar hazır) | **Hayır** |
| AUTH-02 | HTTPS Auth adresi | Karar yok: ortak altyapı (#63) | Mevcut koruma korunuyor | **Hayır** |
| AUTH-03 | `/session` kullanımı | Evet | Evet | **Hayır** (izole denendi) |
| AUTH-05 | Servis kimliği ve delegasyon | Evet: Auth merkezi kimlik vermiyor; D-09 açık | **Hayır** | **Hayır** |
| AUTH-04 | Mutfak ekranı oturumu (sonraki iş) | Öneri #64'te | Kitchen işi değil; frontend uygulanmadı | **Hayır** |

"Gerçek ortam", ekibin ortak dağıtımıdır. İzole kabul denemesi bunun yerine geçmez.

## İzole kabul denemesi

`dev` `00c0ff4`'ün değiştirilmemiş Auth (CSRF cookie düzeltmesi PR #72 dahil), QR ve API Gateway kodu; geçici PostgreSQL; yalnız o çalıştırmada üretilen anahtarlar; ADMIN MFA zorunlu; Auth'un önünde **yalnız test için** HTTPS proxy. 40 kontrol geçti. Ortak container, ENV dosyası veya gerçek anahtar kullanılmadı. Proxy ortak ortam çözümü değildir.

| Durum (gerçek Auth) | Durum değiştiren istek | Okuma |
|---|---|---|
| Geçerli oturum | `/session` bir kez sorulur, işlem yapılır | `/session` sorulmaz |
| Idle bitişine yakın, Auth'un verdiği 20 sn'lik token (idle zamanı SQL ile) | Süresi içinde teyitle yapılır; süre dolunca `401`, Auth'a sorulmaz | Süresi içinde `200`; süre dolunca `401` |
| Logout sonrası, süresi dolmamış token | `401`, yazılmaz | `200` (bilinen gecikme) |
| Hesap pasifleştirme (SQL ile) | `401`, yazılmaz | `200` (bilinen gecikme) |
| Rol değişikliği (SQL ile) | `401`, yazılmaz | Denenmedi |
| Idle süresi dolmuş (SQL ile) | `401`, yazılmaz | Denenmedi |
| Geçerli imzalı fakat başka kullanıcı, restoran veya oturum | `401`, yazılmaz | Denenmedi; yerel doğrulama imzalı değerleri kullanır |
| Auth kesintisi | `503 AUTH_UNAVAILABLE`, yazılmaz | Rol kurallarıyla çalışır |
| Güvenilmeyen sertifika | `503`, cookie iletilmez | Denenmedi; okuma tasarım gereği Auth'u çağırmaz |
| Anahtar tanımlı, Auth adresi HTTP | Kitchen başlamaz | Kitchen başlamaz |

"Geçerli imzalı fakat başka kullanıcı, restoran veya oturum" satırı, o çalıştırmanın test anahtarıyla imzalanan token'larla denendi. Gerçekte böyle bir token'ı yalnız özel anahtarın sahibi üretebilir.

**Doğrulanmayanlar:** ortak ortam, gerçek TLS dağıtımı, tarayıcı arayüzü üzerinden akış, Auth'un yönetici iptal akışı (`ACK_REQUIRED`) ve Q5 kaynağı kapalıyken davranış.

---

## AUTH-01 — Kitchen için Auth açık anahtarı — YANITLANDI

**Yanıt:** Personel token'ları tek RSA anahtarıyla imzalanır, `kid` yoktur. `ops/auth/generate-keys.mjs` çıktısındaki `MAIL_AUTH_PUBLIC_KEY` aynı çiftin açık anahtarıdır ve Kitchen `KITCHEN_AUTH_STAFF_PUBLIC_KEY` olarak kullanır. Özel anahtar veya başka sır paylaşılmaz. Anahtar değişirse tüketen servisler birlikte güncellenir.

**Kitchen:** Ayar, Kitchen `application.properties`, `.env.example` (boş değer) ve `docker-compose.yml` içinde yalnız `kitchen-service` bağlantısı hazır. Alınma yolu ve uyarılar [rehberin yapılandırma bölümünde](../entegrasyon-rehberi.md).

**Not:** Betiğin her çalıştırması yeni bir çift üretir. "Betiğin çıktısı", Auth'un kullandığı özel anahtarla **birlikte** üretilmiş değer olarak anlaşılmalıdır.

**Kalan:** Ortak ortamda değer, AUTH-02 çözülmeden doldurulmamalıdır; aksi halde Kitchen başlamaz.

## AUTH-02 — Servisler arası HTTPS Auth adresi — AÇIK (#63)

**Yanıt:** İç Docker ağında TLS mi, kapalı ağda HTTP mi kullanılacağı ortak altyapı kararıdır. Karar gelene kadar mevcut davranış korunmalıdır.

**Kitchen (değişmedi):** Anahtar boşken bütün personel istekleri `401`. Anahtar tanımlıyken yalnız HTTPS origin kabul edilir; HTTP adresle Kitchen başlamaz. Sertifika ve hostname doğrulaması JVM varsayılanındadır, kapatılmaz.

**Bugünkü engel:** Compose'da `AUTH_SERVICE_URL` ortak `runtime-env` ile düz HTTP gelir (`http://auth-service:8080`), iç ağda TLS tanımı yoktur ve aynı değeri API Gateway'in Auth rotası da kullanır.

**Bekleyen öneri (uygulanmadı):** PR #69, yalnız `dev`/`test` profilinde, `INTERNAL_HTTP_ALLOWED=true` iken ve yalnız Compose servis adı veya loopback için HTTP'ye izin verilmesini önerir. Birleşirse tek başına Kitchen davranışını değiştirmez. Kitchen'da ayrı bir değişiklik gerekir: ayarı okumak, profil ve adres kurallarını uygulamak, `prod` ile açıldığında başlamamak, bir kez uyarı yazmak ve ADR'deki beş test. Bu, personel cookie'sinin iç ağda açık metin gitmesini kabul eden bir güvenlik değişikliğidir ve ayrıca onay ister.

**Çelişki:** PR #67 sözleşmesi personel cookie'sinin `/session`'a **yalnız HTTPS ile** iletileceğini yazar. PR #69 dev/test için HTTP istisnası tanımlar. İkisi de birleşirse sözleşme metninin istisnayı anması gerekir.

**Kabul:** `dev`'de birleşmiş ortak karar; ardından ortak ortamda login → `GET /api/v1/kitchen/orders` `200` ve bir yazma isteği başarılı.

## AUTH-03 — `/session` tüketici sözleşmesi — YANITLANDI, UYGULANDI

**Yanıt:** `GET /api/v1/auth/session` servisler için desteklenir. Yalnız access cookie ve HTTPS; `401` ret; diğer her sonuç "doğrulanamadı"; etkinlik sayılmaz; sonuç istekler arasında saklanmaz. Her istekte sormak zorunlu değildir; yalnız yerel doğrulamada iptal en geç token ömrü içinde yansır. Auth'un Kitchen için önerisi: okuma ve polling'de yerel doğrulama; durum değiştiren işlemlerde ek olarak `/session`. Gerekçe: her çağrı Auth'ta kullanıcı satırını kilitler. Kitchen'a ayrı bir iptal bildirimi alıcısı önerilmiyor. İleride hız sınırı gelirse çağıran servisin adresine göre olmayacak.

**Token ömrü:** Auth önce "tam 300 sn" yazdı, sonra düzeltti: **en çok 300 sn.** Oturumun idle veya mutlak bitişi yakınsa `exp` daha erkendir. Kitchen `exp − iat ≤ 300` denetimini uygular ve daha kısa ömrü kabul eder.

**Kitchen'ın seçimi:** Öneri aynen uygulandı. Hangi ucun teyit istediği [rehber §2](../entegrasyon-rehberi.md) tablosundadır.

**Kabul edilen sonuç:** Okumalarda iptal en çok 300 sn gecikir. Auth kesintisi durum değiştiren işlemleri anında durdurur.

**Sözleşmenin sınırı:** `/session` yanıtında kullanıcı ve restoran kimliği yoktur (`status`, `role`, `idleExpiresAt`, `absoluteExpiresAt`; `dev` kodunda ayrıca `mfaSetupRequired`). Kitchen `status`, `role` ve süreleri karşılaştırır. Kullanıcı ve restoran uyuşmazlığını gönderilen token üzerinden Auth reddeder. Bu belge yanıta yeni alan eklenmesini talep etmez.

## AUTH-05 — Güvenilir servis kimliği ve personel delegasyonu — AÇIK (D-09)

**Yanıt:** Auth'ta merkezi servis kimliği yoktur ve şu an planlanmıyor. Ortak sözleşme (D-09) açık ekip kararıdır. Bugün bazı servis çiftleri her çağıranın kendi anahtarıyla imzaladığı istekleri kullanıyor. Bu bilgi Kitchen için yeni bir güven ilişkisi onayı **değildir**.

**Kitchen:** Servis kimliği ve delegasyon **uygulanmadı**. Bugün yalnız koruma sınırları vardır:

- Servis credential'ı personel oturumu sayılmaz; `Authorization` okunmaz; iç yollar kapalıdır.
- İstemcinin kimlik veya delegasyon header'ları ve gövde alanları yetki kazandırmaz.
- Menu portu `restaurantId` ve `requestedByUserId` değerlerini yalnız doğrulanmış personelden alır; token porta girmez. Alıcıya bunu kanıtlayacak bir mekanizma yoktur.
- Gerçek bir Order veya Menu çağrısı yoktur.

**Ortak sözleşmenin cevaplaması gerekenler:** kimliğin üretimi, doğrulanması, anahtar dağıtımı ve rotasyonu; çağıran, hedef, işlem ve restoran sınırı; azami ömür, tekrar koruması ve isteğin imzaya bağlanması; delegasyonun nasıl taşınıp kanıtlanacağı; reddedilen veya doğrulanamayan çağrıda davranış.

**Sözleşmeden bağımsız Kitchen koşulları:** header veya gövde alanı tek başına kanıt değildir; servis kimliği personel yetkisi vermez; personel cookie'si veya JWT'si Auth `/session` dışında bir yere iletilmez; doğrulanamayan çağrı başarı üretmez.

## AUTH-04 — Mutfak ekranının oturum sürekliliği — sonraki iş (#64)

Frontend işidir; bu taskın tamamlanma şartı değildir.

**Mevcut durum:** Access cookie en çok 300 sn yaşar. KITCHEN idle süresi 60 dk'dır ve yalnız `POST /api/v1/auth/activity` ile uzar; Kitchen okumaları ve `/session` uzatmaz. `dev` frontend'i açık ekranda süreli yenileme ve `activity` çağrısı yapmaz.

**#64'teki öneri:** tıklama veya dokunma, tuş ve kaydırma etkinlik sayılsın; dakikada en çok bir istek; mutfak için ayrı kural olmasın. Bir inceleyen yorumu bunu uygun buldu ve mutfak ekranının aynı ortak hook'u kullanmasını istedi. Issue açık, kod yazılmadı.

**Etki:** Frontend yenileme yapmadan mutfak ekranı en geç 5 dakikada `401` alır; yalnız izlenen ekranın oturumu 60 dk sonra düşer.
