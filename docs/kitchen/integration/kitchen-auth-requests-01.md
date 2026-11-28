# Kitchen → Auth talepleri 01 (KITCHEN-INT-01)

- **Tarih:** 26 Kasım 2026; son güncelleme 27 Kasım 2026 (Auth'un ikinci yanıtı ve PR durumları)
- **Talep eden:** Kitchen (Nisa)
- **İlgili servis / sorumlu:** Auth (Kerem). Ortak `docker-compose.yml` / `.env.example` ve TLS için ayrıca ekip kararı gerekir.
- **Yanıt kaynağı:** [PR #61](https://github.com/software-lecture-labs/Restofy/pull/61) yorumları (26 Kasım 2026).

Bu belge Auth kodunda veya ortak dosyalarda değişiklik içermez.

## Okunan kaynakların durumu (kontrol tarihi: 27 Kasım 2026)

| Kaynak | Durum |
|---|---|
| [PR #67](https://github.com/software-lecture-labs/Restofy/pull/67) — personel oturumu sözleşmesi | **`dev`'de birleşti.** İçeriği birleşmeden önce incelenen sürümle aynı |
| [#63](https://github.com/software-lecture-labs/Restofy/issues/63) — iç ağda HTTPS | Açık |
| [PR #69](https://github.com/software-lecture-labs/Restofy/pull/69) — #63 için ADR-ORTAK-001 | Açık, **birleşmemiş**; ADR durumu "Önerildi". Yeniden inceleme istendi; resmi inceleme durumu hâlâ **değişiklik isteği**, resmi onay yok. Yorumlardaki gelişme [AUTH-02](#auth-02--servisler-arası-https-auth-adresi--açik-63) içindedir |
| [#64](https://github.com/software-lecture-labs/Restofy/issues/64) — personel etkinliği | Kapalı (tamamlandı) |
| [PR #70](https://github.com/software-lecture-labs/Restofy/pull/70) — ortak etkinlik hook'u | `dev`'de birleşti |
| [PR #75](https://github.com/software-lecture-labs/Restofy/pull/75) — zorunlu CI kontrolü düzeltmesi | `dev`'de birleşti (#70'ten önce) |

Geçerli personel sözleşmesi `dev`'deki [`docs/auth-service/staff-contract.md`](https://github.com/software-lecture-labs/Restofy/blob/c5981b89619eb6b6967e0cb06578f938b17d8c1e/docs/auth-service/staff-contract.md) dosyasıdır; bu branch'e `dev` birleştirilmediği için dosya burada yoktur. `feature/auth-service` dalındaki eski `staff-contract.md` geçerli değildir. `ADR-AUTH-Q5` ve `ADR-QR-003` yalnız QR kapsamındadır ve Kitchen'ı bağlamaz.

## Durum özeti

| Talep | Konu | Yanıt geldi | Kitchen'da uygulandı | Gerçek ortamda doğrulandı |
|---|---|---|---|---|
| AUTH-01 | Auth açık anahtarı | Evet | Evet (ayar hazır) | **Hayır** |
| AUTH-02 | HTTPS Auth adresi | Birleşmiş karar yok (#63; öneri PR #69) | Mevcut koruma korunuyor | **Hayır** |
| AUTH-03 | `/session` kullanımı | Evet | Evet | **Hayır** (izole denendi) |
| AUTH-05 | Servis kimliği ve delegasyon | Evet: Auth merkezi kimlik vermiyor; D-09 açık | **Hayır** | **Hayır** |
| AUTH-04 | Mutfak ekranı oturumu (sonraki iş) | Evet: ortak hook `dev`'de (#70) | Frontend işi; mutfak ekranı yok, hook ona uygulanmadı | **Hayır** |

"Gerçek ortam", ekibin ortak dağıtımıdır. İzole kabul denemesi bunun yerine geçmez.

## İzole kabul denemesi

`dev` `00c0ff4`'ün değiştirilmemiş Auth (CSRF cookie düzeltmesi PR #72 dahil), QR ve API Gateway kodu; geçici PostgreSQL; yalnız o çalıştırmada üretilen anahtarlar; ADMIN MFA zorunlu; Auth'un önünde **yalnız test için** HTTPS proxy. 40 kontrol geçti (27 Kasım 2026'da güncel Kitchen koduyla yeniden çalıştırıldı; bu üç servisin çalışma kodu o tarihte `dev`'de aynıydı). Ortak container, ENV dosyası veya gerçek anahtar kullanılmadı. Proxy ortak ortam çözümü değildir.

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

**Bekleyen öneri (birleşmedi, Kitchen'da uygulanmadı):** [PR #69](https://github.com/software-lecture-labs/Restofy/pull/69)'daki öneriye göre (kontrol tarihi: 27 Kasım 2026) servisler arası bir adres ancak şunların hepsiyle HTTP olabilir: `INTERNAL_HTTP_ALLOWED=true`; `APP_ENV` değeri `dev`, `local` veya `test` **ve** `prod`/`production` profili etkin değil; host noktasız bir Compose servis adı veya loopback; adres yalnız origin. Ayar açıkken `APP_ENV` veya profil `prod` ise ya da ayar `true`/`false` dışında bir değerse servis başlamaz. İstemci yönlendirme izlemez ve istisna kullanılırken bir kez uyarı yazılır. PR ayrıca ortak Compose bloğuna `INTERNAL_HTTP_ALLOWED` (varsayılan `false`) ve `APP_ENV` (varsayılan `dev`) ekler. `.env.example` içinde de `INTERNAL_HTTP_ALLOWED=false` yazar; Compose'da HTTP'yi denemek isteyen kendi `.env` dosyasında `true` yapar.

**Koşulun durumu (henüz kesinleşmiş karar değildir):**

1. İlk hâl `dev`/`test` **profili** istiyordu. İnceleyen, Compose'daki entegrasyon profilleriyle çakışabileceğini belirtip değişiklik istedi; profil koşulu öneriden çıkarıldı.
2. Auth sorumlusu yerine `APP_ENV`'e bakmayı önerdi ve PR #61'deki yanıtında (26 Kasım, 22:05 UTC) inceleyenin cevabını beklediğini yazdı.
3. İnceleyen PR #69'da (22:12 UTC) `APP_ENV` koşulunun uygun olduğunu yorumla yazdı, değerleri `dev`, `local`, `test` olarak istedi ve ADR'nin güncellenmesini söyledi. PR güncellendi.
4. `.env.example` varsayılanı sorusunu PR sahibi, QR sorumlusunun da tercih ettiği daha sıkı seçenek olan `false` ile kapattı (26 Kasım, 22:50 UTC; inceleyen `true` isterse geri alacağını yazdı) ve yeniden inceleme istedi.
5. 27 Kasım 2026 kontrolünde PR açık, ADR "Önerildi" ve yeniden inceleme bekliyor; resmi inceleme durumu hâlâ değişiklik isteği, resmi onay yok.

Yorumdaki uygun görüş ile resmi inceleme durumu aynı şeyi söylemediği için bu belge `APP_ENV` koşulunu kabul edilmiş saymaz.

Birleşirse tek başına Kitchen davranışını değiştirmez. ADR, Kitchen'ı istemcisini küçük bir PR ile uyarlayacak servisler arasında sayar (yanında #61'i anar): ayarı ve ortam, profil ve adres kurallarını uygulamak, başlamama koşulları, uyarı ve ADR'de listelenen testler. Bu, personel cookie'sinin iç ağda açık metin gitmesini kabul eden bir güvenlik değişikliğidir ve ayrıca onay ister.

**Sözleşme:** `dev`'deki sözleşme personel cookie'sinin `/session`'a **yalnız HTTPS ile** iletileceğini yazar; bugün geçerli kural budur. Auth sorumlusu, istisna kabul edilirse bu cümleyi mümkünse aynı PR'da güncelleyeceğini yazdı; PR #69'un güncel hâli bu değişikliği içeriyor ve birleşene kadar `dev`'deki sözleşme değişmez.

**Kabul:** `dev`'de birleşmiş ortak karar; ardından ortak ortamda login → `GET /api/v1/kitchen/orders` `200` ve bir yazma isteği başarılı.

## AUTH-03 — `/session` tüketici sözleşmesi — YANITLANDI, UYGULANDI

**Yanıt:** `GET /api/v1/auth/session` servisler için desteklenir. Yalnız access cookie ve HTTPS; `401` ret; diğer her sonuç "doğrulanamadı"; etkinlik sayılmaz; sonuç istekler arasında saklanmaz. Her istekte sormak zorunlu değildir; yalnız yerel doğrulamada iptal en geç token ömrü içinde yansır. Auth'un Kitchen için önerisi: okuma ve polling'de yerel doğrulama; durum değiştiren işlemlerde ek olarak `/session`. Gerekçe: her çağrı Auth'ta kullanıcı satırını kilitler. Kitchen'a ayrı bir iptal bildirimi alıcısı önerilmiyor. İleride hız sınırı gelirse çağıran servisin adresine göre olmayacak.

**Token ömrü:** Auth önce "tam 300 sn" yazdı, sonra düzeltti: **en çok 300 sn.** Oturumun idle veya mutlak bitişi yakınsa `exp` daha erkendir. Kitchen `exp − iat ≤ 300` denetimini uygular ve daha kısa ömrü kabul eder.

**Kitchen'ın seçimi:** Öneri aynen uygulandı. Hangi ucun teyit istediği [rehber §2](../entegrasyon-rehberi.md) tablosundadır.

**Kabul edilen sonuç:** Okumalarda iptal en çok 300 sn gecikir. Auth kesintisi durum değiştiren işlemleri anında durdurur.

**Sözleşmenin sınırı:** `/session` yanıtında kullanıcı ve restoran kimliği yoktur (`status`, `role`, `idleExpiresAt`, `absoluteExpiresAt` ve `mfaSetupRequired`). Kitchen `status`, `role` ve süreleri karşılaştırır. Kullanıcı ve restoran uyuşmazlığını gönderilen token üzerinden Auth reddeder. Bu belge yanıta yeni alan eklenmesini talep etmez.

**Auth'un ikinci yanıtı (PR #61, 26 Kasım 22:05 UTC) — Kitchen davranışı değişmedi:**

- Başka kullanıcı, restoran veya oturum taşıyan token'ın `401` alması beklenen davranıştır. Yanıta göre Auth, token çözülürken `restaurant_id`'yi zorunlu tutar; `/session`'da `sid` ile bulunan oturumun aynı kullanıcıya ait olduğunu, oturum durumunun ve rolün güncel olduğunu denetler.
- KITCHEN için hareketsizlik süresi 60 dakikadır.
- `mfaSetupRequired` yalnız MFA bekleyen oturumda dolar; servislerin gönderdiği access cookie ile `null` gelir. `dev`'deki sözleşme bu alanı henüz yazmıyor; ekleme PR #69'un içindedir ve birleşmedi.
- Açık okuma listesi ve listede olmayan her isteğin (yeni eklenen uçlar dahil) varsayılan olarak `/session` teyidine gitmesi doğrudur.

## AUTH-05 — Güvenilir servis kimliği ve personel delegasyonu — AÇIK (D-09)

**Durum:** Order ve Menu bağlantılarında güvenilir servis kimliği ve personel delegasyonu **ortak sözleşme bekliyor, uygulanmadı.** Bu, KITCHEN-INT-01'in açık gereksinimidir.

**Yanıt:** Auth'ta merkezi servis kimliği yoktur ve şu an planlanmıyor. Ortak sözleşme (D-09) açık ekip kararıdır. Bugün bazı servis çiftleri her çağıranın kendi anahtarıyla imzaladığı istekleri kullanıyor. Bu bilgi Kitchen için yeni bir güven ilişkisi onayı **değildir**.

**Kitchen:** Servis kimliği ve delegasyon **uygulanmadı**. Bugün yalnız koruma sınırları vardır:

- Servis credential'ı personel oturumu sayılmaz; `Authorization` okunmaz; iç yollar kapalıdır.
- İstemcinin kimlik veya delegasyon header'ları ve gövde alanları yetki kazandırmaz.
- Menu portu `restaurantId` ve `requestedByUserId` değerlerini yalnız doğrulanmış personelden alır; token porta girmez. Alıcıya bunu kanıtlayacak bir mekanizma yoktur.
- Gerçek bir Order veya Menu çağrısı yoktur.

**Ortak sözleşmenin cevaplaması gerekenler:** kimliğin üretimi, doğrulanması, anahtar dağıtımı ve rotasyonu; çağıran, hedef, işlem ve restoran sınırı; azami ömür, tekrar koruması ve isteğin imzaya bağlanması; delegasyonun nasıl taşınıp kanıtlanacağı; reddedilen veya doğrulanamayan çağrıda davranış.

**Sözleşmeden bağımsız Kitchen koşulları:** header veya gövde alanı tek başına kanıt değildir; servis kimliği personel yetkisi vermez; personel cookie'si veya JWT'si Auth `/session` dışında bir yere iletilmez; doğrulanamayan çağrı başarı üretmez.

## AUTH-04 — Mutfak ekranının oturum sürekliliği — ortak hook hazır, mutfak ekranı sonraki iş

Frontend işidir; bu taskın tamamlanma şartı değildir.

**Süreler:** Access cookie en çok 300 sn yaşar. KITCHEN idle süresi 60 dk'dır ve yalnız `POST /api/v1/auth/activity` ile uzar; Kitchen okumaları ve `/session` uzatmaz.

**Hazırlanan (`dev`'de, PR #70; [#64](https://github.com/software-lecture-labs/Restofy/issues/64) kapalı):** ortak `useStaffActivity` hook'u ve onu kullanan `StaffActivity` bileşeni. Tıklama veya dokunma, tuş ve kaydırma etkinlik sayılır; dakikada en çok bir istek gider. Sekme yeniden görünür olunca oturum süre uzatılmadan okunur. Hook'un kendi isteği `401`/`403` alırsa bir kez `refresh` denenir; o da reddedilirse giriş sayfasına yönlendirilir. Auth'a ulaşılamazsa oturum kapatılmaz. Mutfak için ayrı kural yoktur. Yanıt tarihinde #70 CI düzeltmesini (#75) bekliyordu; ikisi de sonradan birleşti.

**Uygulanmayan:** Bileşen bugün yalnız personel ana sayfasındadır. `dev` frontend'inde **mutfak ekranı yoktur**, dolayısıyla hook mutfak ekranına uygulanmamıştır. Ekran yazılınca bileşeni bir kez eklemesi gerekir.

**Etki (koddan; tarayıcıda denenmedi):** Hook süreli yenileme yapmaz; yalnız etkileşimde veya sekmeye dönüşte istek gönderir. Dokunulmadan izlenen bir mutfak ekranında hook tek başına access cookie'yi yenilemez; ekranın okumaları cookie dolunca (en geç 5 dk) `401` alabilir. Mutfak ekranının kendi veri isteklerinde yenilemeyi ayrıca ele alması gerekir. Hiç etkileşim olmazsa oturum 60 dk sonunda düşer.
