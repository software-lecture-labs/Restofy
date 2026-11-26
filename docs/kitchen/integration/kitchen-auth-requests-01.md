# Kitchen → Auth talepleri 01 (KITCHEN-INT-01)

- **Tarih:** 26 Kasım 2026
- **Talep eden:** Kitchen (Nisa)
- **İlgili servis / sorumlu:** Auth (Kerem). Ortak `docker-compose.yml` / `.env.example` ve TLS için ayrıca ekip onayı gerekir.
- **İncelenen taban:** `origin/dev` `6e72336`. `origin/feature/auth-service` yalnız okundu; birleştirilmedi.

Bu belge bir taleptir. Auth kodunda veya ortak dosyalarda değişiklik içermez.

## Kitchen tarafında bugün tamamlanan

Auth'un **mevcut** sözleşmesi, Auth'ta hiçbir değişiklik gerektirmeden Kitchen'da uygulandı (ayrıntı: [entegrasyon rehberi](../entegrasyon-rehberi.md)):

- `restofy_access` cookie'sindeki JWT yerelde doğrulanır: RS256, issuer, tek `restofy-staff-api` audience, `token_type=access`, `auth_state=AUTHENTICATED`, `auth_policy=q5-idle-v1`, `iat = nbf`, en çok 300 sn ömür, UUID `sub/sid/jti/restaurant_id`.
- Her istekte aynı cookie ile `GET /api/v1/auth/session` sorulur. 401 reddir; diğer her durum (yönlendirme dahil) `503 AUTH_UNAVAILABLE` olur. Önbellek ve yeniden deneme yoktur.
- Yazma isteklerinde izinli `Origin` + `restofy_csrf` / `X-CSRF-TOKEN` eşitliği + JSON içerik türü.

**Doğrulama:** Kitchen testleri ve bir izole kabul denemesi. Denemede `dev`'deki değiştirilmemiş Auth, QR ve API Gateway kodu, geçici PostgreSQL, tek kullanımlık anahtarlar ve Auth'un önünde **yalnız test için** bir HTTPS proxy kullanıldı; ADMIN MFA zorunluydu. Gerçek login cookie'si ve CSRF değeri kabul edildi; gerçek TOTP kaydını tamamlayan ADMIN yalnız okuyabildi; Gateway üzerinden giriş, okuma ve yazma çalıştı. Oturum durumu senaryoları AUTH-03 tablosundadır. Proxy ortak ortam çözümü **değildir**.

**Doğrulanmayanlar:** ortak ortam, gerçek TLS dağıtımı, tarayıcı arayüzü üzerinden akış, Auth'un yönetici iptal akışı (`ACK_REQUIRED`) ve Q5 kaynağı kapalıyken davranış.

## Kapsam

| Talep | Konu | KITCHEN-INT-01 için | Durum |
|---|---|---|---|
| AUTH-01 | Auth açık anahtarı | Bölüm A | Kitchen bağlantısı hazır; değer ve alınma yolu bekliyor |
| AUTH-02 | HTTPS Auth adresi | Bölüm A | **AÇIK engel** |
| AUTH-03 | `/session` yöntemi | Bölüm A | **AÇIK**, Auth kararı bekliyor |
| AUTH-05 | Servis kimliği ve personel delegasyonu | Bölüm A | **UYGULANMADI**, ortak sözleşme bekliyor |
| AUTH-04 | Mutfak ekranının oturum sürekliliği | Bölüm B (sonraki iş) | Frontend işi; bu taskın tamamlanma şartı değil |

---

# A. KITCHEN-INT-01 güvenlik talepleri

## AUTH-01 — Kitchen için Auth açık anahtarı

**Kitchen tarafı tamamlandı** (ENV için proje sahibinin onayıyla): `KITCHEN_AUTH_STAFF_PUBLIC_KEY`, Kitchen `application.properties`, `.env.example` (boş değer) ve `docker-compose.yml` içinde yalnız `kitchen-service`.

**Mevcut durum:** Değer repoda yoktur ve olmamalıdır. Boşken ortak ortamda Kitchen personel doğrulaması **kapalıdır**; her personel isteği 401 alır. Anahtar yalnız AUTH-02 karşılandıktan sonra doldurulmalıdır; aksi halde Kitchen başlamaz.

**Beklenen:** Auth'tan, personel imza anahtarının Base64 X.509 **açık** karşılığının resmi alınma yolu. Özel anahtar veya CSRF/MFA/reset sırrı Kitchen'a verilmez.

**Kabul:** Anahtar boşken tüm personel istekleri 401; doğru anahtarla gerçek login cookie'si 200; başka bir çiftin açık anahtarıyla 401; hatalı anahtarda uygulama başlamaz ve hata mesajı değeri yazmaz.

## AUTH-02 — Servisler arası HTTPS Auth adresi

**Mevcut durum / kanıt:** Compose'da `AUTH_SERVICE_URL` ortak `runtime-env` ile gelir; örnek değer düz HTTP'dir (`http://auth-service:8080`) ve Compose'da Auth için TLS tanımı yoktur. Kitchen, personel cookie'sini ilettiği için anahtar tanımlıyken **yalnız HTTPS origin** kabul eder; aksi halde başlamaz. Order (`order-auth`) ve Waiter aynı şartı uygular.

Aynı ortak `AUTH_SERVICE_URL`, API Gateway'in Auth rotasının da hedefidir ve Compose'da Kitchen'a özel bir adres verecek bir bağlantı yoktur. Ortak değeri değiştirmek diğer servisleri etkiler.

**Etki:** **Açık engel.** AUTH-01 tek başına yeterli değildir: anahtar bugünkü HTTP adresle birlikte doldurulursa Kitchen başlamaz. Anahtar **doluyken** `JWT_ACCESS_TTL_SECONDS` 300'ü aşarsa da başlamaz (`KitchenAuthProperties`); anahtar boşken bu değer Kitchen'ın açılışını etkilemez. `dev`'in güncel Auth (`AuthSettings`) ve QR (`Q5SecuritySettings`) kodu ise anahtardan bağımsız olarak **tam 300** ister (`d1e7f0b`, 15 Kasım 2026).

**Beklenen:** Kitchen container'ından erişilen, sertifikasının SAN alanı adresle eşleşen bir HTTPS Auth origin'i; bunu Kitchen'a vermenin ekipçe onaylanmış yolu (yeni ENV/Compose bağlantısı ayrıca onay ister); özel CA kullanılacaksa salt okunur truststore. Sertifika ve hostname doğrulaması kapatılmaz. Kitchen bir adres veya sertifika varsaymaz.

**Kabul:** Paylaşılan ortamda login → `GET /api/v1/kitchen/orders` 200; güvenilmeyen sertifikada 503; HTTP adresle Kitchen başlamaz.

## AUTH-03 — `/session` sorgusunun desteklenen tüketici sözleşmesi olup olmadığı

**Durum: AÇIK.** Kitchen'ın her istekte `/session` çağırması **geçici bir Kitchen seçimidir**; Auth ile karşılıklı onaylanmış sözleşme değildir. Order ve Waiter'ın aynı yöntemi kullanması onay sayılmaz.

**Yöntemin aleyhindeki kayıtlar:**
- `dev`'deki [ADR-AUTH-Q5](../../decisions/ADR-AUTH-Q5-coordinated-revocation.md) "her QR isteğinde Auth'a sorgu" seçeneğini (A) kullanılabilirlik ve ağ gecikmesi gerekçesiyle **reddeder**; [ADR-QR-003](../../decisions/ADR-QR-003-qr-auth-entegrasyonu.md) normal personel isteği için yeni introspection ucu önermez. İki karar da **QR kapsamındadır**; Kitchen için yazılı bir karar yoktur.
- `origin/feature/auth-service` üzerindeki `docs/auth-service/staff-contract.md` (`dev`'de yok) tüketici için yerel doğrulama tarif eder ve anlık merkezi kontrol vaadi vermez. Aynı belge access TTL için "300–3600, varsayılan 900" der; `dev` kodu tam 300'ü zorunlu tutar.
- `/session` tarayıcı istemcisinin durum ucudur: yanıtta kullanıcı, oturum veya restoran kimliği yoktur.

**Yöntemin lehindeki kayıtlar:**
- Q5 iptal bildirimi yalnız QR'a gider. Kitchen yalnız yerel doğrulasaydı logout, pasifleştirme veya rol değişikliğinden sonra eski token'ı en çok 300 sn kabul ederdi; yönetici işlemi QR onayıyla "tamamlandı" göründükten sonra bile.
- `dev`'de [Order](../../order-service/INTEGRATION_CONTRACTS.md) (§2.1) ve Waiter aynı ucu kullanır. Menu (PR #46) yalnız yerel doğrulamayı seçmiştir.

**`dev` koduyla görülen davranış** (G: izole ortamda gerçek Auth ile denendi; K: yalnız koddan):

| Durum | `/session` → Kitchen | Kanıt |
|---|---|---|
| Logout | 401 → 401 | G |
| Idle süresi dolmuş | 401 → 401 | G (zaman SQL ile geriye alındı) |
| Rol değişikliği | 401 → 401 | G (SQL ile; `dev`'de bunun HTTP ucu yok) |
| Hesap pasifleştirme | 401 → 401 | G (SQL ile; `dev`'de bunun HTTP ucu yok) |
| Başka restoranı gösteren, geçerli imzalı token | 401 → 401 | G (test anahtarıyla imzalandı). MVP tek restoranlıdır; kullanıcı bazında restoran değişikliği yoktur |
| MFA bekleyen ADMIN | Kitchen token'ı yerelde reddeder, Auth'a sormaz | G (gerçek MFA bekleyen cookie ve gerçek TOTP kaydı) |
| `ACK_REQUIRED` yönetici işlemi beklemede | 503 → 503 | K |
| Q5 kaynağı `ACTIVE` değil (ör. Auth DB yeniden başladı) | 503 → 503 | K. Yeniden `ACTIVE` olması QR ile bootstrap ister: Kitchen **dolaylı olarak QR'a da** bağımlıdır |
| Auth süreci durdu / sertifika güvenilmiyor | → 503 | G. Güvenilmeyen sertifikada cookie iletilmedi |

**Maliyet:** Auth yokken Kitchen personel API'si kullanılamaz. `dev` kodunda her `/session` çağrısı kullanıcı satırını iki kez (`FOR UPDATE`) kilitler; açık mutfak ekranlarının polling trafiği Auth'a aynen yansır. `origin/feature/auth-service-v2` (`04d29f7`, `dev`'de değil) hız sınırlarını giriş, MFA ve parola sıfırlamaya uygular; `/session` akışını değiştirmez.

**Güven sınırı:** Cookie yalnız yapılandırmadaki `AUTH_SERVICE_URL`'in HTTPS origin'ine gider; istek verisi hedefi etkilemez, yönlendirme izlenmez, sertifika ve hostname doğrulaması JVM varsayılanındadır. Auth bu durumda Kitchen'dan geçen personel cookie'sini görür (zaten üreticisidir).

**Yanıt beklenen sorular:**
1. `GET /api/v1/auth/session` Kitchen gibi servis tüketicileri için desteklenecek mi? (`status`, `role`, `idleExpiresAt`, `absoluteExpiresAt`, `mfaSetupRequired`; 401 = ret; sorgu personel aktivitesi sayılmaz.)
2. Desteklenmeyecekse hangisi: Kitchen'ın Q5 benzeri bir iptal tüketicisi olması (Auth değişikliği ve yeni güven ilişkisi gerekir) mi, yoksa yerel doğrulama ve en çok 300 sn iptal gecikmesinin ekipçe kabulü mü?
3. Bu uca ileride hız sınırı gelirse servis çağrıları nasıl hesaplanacak?
4. Güncel personel sözleşmesi (TTL 300 ile) `dev`'e alınacak mı?

**Kabul:** Kararın `dev`'deki bir belgede kayıtlı olması. Karar gelene kadar Kitchen kontrolü **kaldırmaz**.

## AUTH-05 — Güvenilir servis kimliği ve personel delegasyonu (D-09)

**Durum: UYGULANMADI, AÇIK.** KITCHEN-INT-01'in istediği "Order ve Menu bağlantılarında güvenilir servis kimliği ile personel delegasyonu" gereksinimi **karşılanmadı**. Bu görevde yeni anahtar veya güven ilişkisi onaylanmadı ([SECURITY.md](../../SECURITY.md) §1, §8, §17).

**Bugün Kitchen'da olan (yalnız koruma sınırları; servis kimliği değildir):**
- Servis credential'ı personel oturumu sayılmaz; `Authorization` okunmaz; iç yollar kapalıdır.
- İstemcinin gönderdiği kimlik veya delegasyon header'ları ve gövde alanları yetki kazandırmaz.
- Menu portu `restaurantId` ve `requestedByUserId` değerlerini yalnız doğrulanmış personelden alır; personel token'ı porta girmez. Alıcıya bunu kanıtlayacak bir mekanizma yoktur: bu alanlar bugün yalnız beyandır.
- Gerçek bir Order veya Menu çağrısı yoktur: Menu komutu başarı göstermez, giden olaylar teslim edilmez.

**Mevcut kayıtlar (Kitchen'ı kapsayan ortak karar yok):**
- `dev`'de servisler arası kimlik yalnız belirli çiftler için tanımlıdır: Auth→Mail, Auth↔QR (Q5) ve Order↔QR (Compose anahtar adları). [ADR-QR-003](../../decisions/ADR-QR-003-qr-auth-entegrasyonu.md) Q2, dar Q5 sözleşmesinin Order/Payment/Waiter delegasyonunu çözmediğini yazar.
- `origin/feature/auth-service` üzerindeki `docs/auth-service/staff-contract.md` (`dev`'de yok) **genel bir servis JWT önerisi** içerir: her çağıran kendi anahtarıyla RS256, `Authorization: Service`, `iss`/`sub` çağıran, `aud` hedef, en çok 60 sn, tekrar koruması ve uç izin matrisi. Belge, modelin bütün servislerde uygulandığını iddia etmez.
- Order PR #47 (`dev`'de yok) çift bazlı imzalı istek önerir: `kid`, `iss`, `sub`, `aud`, `scope`, `restaurant_id`, `method`, `target`, `body_sha256`, `jti`, en çok 30 sn.
- İki öneri en azından azami ömürde ayrışır (60 / 30 sn). PR #47 yöntem, hedef ve gövde özetini de imzaya bağlar. Menu aynı konuyu sorar (PR #50, madde 8).

**Auth'tan (ekip kararı olarak) beklenen ortak sözleşme.** Kitchen bir tasarım seçmez; sözleşme en az şunları yazılı cevaplamalıdır:
1. Hangi model esas: yukarıdaki önerilerden biri mi, başkası mı? Kimliği kim üretir, alıcı nasıl doğrular; anahtar dağıtımı ve rotasyonu nasıl yapılır, hangi ENV/Compose bağlantıları gerekir?
2. Kimlik neyle sınırlanır: çağıran, hedef, izinli işlem, restoran kapsamı?
3. Azami ömür, tekrar (replay) koruması ve isteğin (yöntem, yol, gövde) imzaya bağlanıp bağlanmayacağı.
4. Delegasyon: adına işlem yapılan personel nasıl taşınır ve bunu kim kanıtlar; alıcı bunu yetki kararında mı kullanır, yalnız denetim bilgisi midir?
5. Reddedilen, doğrulanamayan veya sonucu bilinmeyen çağrıda davranış ve ortak olumsuz test örnekleri.

**Sözleşmeden bağımsız Kitchen koşulları:** servis kimliği tek başına personel yetkisi vermez; header veya gövde alanı tek başına kanıt sayılmaz; personel cookie'si veya JWT'si, Auth `/session` dışında başka servise iletilmez; doğrulama yapılamazsa işlem reddedilir ve başarı üretilmez.

**Sözleşme geldiğinde Kitchen'da kalan güvenlik işi:**

| Kitchen'da yapılacak | Yön |
|---|---|
| Her giden çağrıda Kitchen servis kimliğini sözleşmeye göre üretmek; üretilemiyorsa çağrı yapmamak | Kitchen → Order, Menu |
| Personel delegasyonunu yalnız doğrulanmış personelden, sözleşmenin biçiminde taşımak (bugün giden `KitchenCancellationRequested` personel kimliği taşımaz) | Kitchen → Order, Menu |
| Herhangi bir iç uç açılmadan önce çağıranı, hedefi, izinli işlemi ve restoran kapsamını doğrulamak; personel yetkisi vermemek | Order → Kitchen |
| Anahtar yapılandırması; eksik veya hatalıysa kapalı kalmak (yeni ENV ayrıca onay ister) | İki yön |
| Testler: geçerli kimlik yalnız izinli işlemi açar; yanlış imza, süre, hedef, işlem, restoran ve tekrar reddedilir; personel JWT'si servis kimliği yerine geçmez; delegasyon istemciden değiştirilemez; doğrulanamayan çağrı başarı üretmez | İki yön |

Bu güvenlik işinin ön koşulu ortak sözleşmedir. Menu HTTP uçları, ürün açma/kapama entegrasyonu ve Order mesaj alanı uyumsuzlukları **ayrı işlerdir** ve bu taskın tamamlanma şartı değildir; kimliğin gerçek Order/Menu bağlantısı üzerinde uçtan uca doğrulanması o işlerle birlikte yapılabilir.

**Kabul:** `dev`'de yazılı ortak karar; ardından yukarıdaki Kitchen güvenlik işi ve testleri.

---

# B. Sonraki iş (bu taskın tamamlanma şartı değil)

## AUTH-04 — Mutfak ekranının oturum sürekliliği

**Mevcut durum / kanıt:** Access cookie 300 sn yaşar; [ADR-AUTH-Q5](../../decisions/ADR-AUTH-Q5-coordinated-revocation.md) frontend silent refresh'i **NOT IMPLEMENTED** olarak kaydeder. KITCHEN idle süresi 60 dk'dır ve yalnız `POST /api/v1/auth/activity` ile yenilenir; Kitchen okumaları ve `/session` sorgusu yenilemez.

**Etki:** Frontend bu iki işi yapmadan mutfak ekranı 5 dakikada bir 401 alır; yalnız izlenen (tıklanmayan) ekran 60 dk sonra oturumdan düşer.

**Beklenen:** Frontend tarafında sekmeler arası tek uçuşlu refresh ve "mutfakta hangi etkileşim aktivite sayılır" kararı. Güvenlik süreleri Kitchen tarafından uzatılmaz.

**Kabul:** Gerçek tarayıcıda 5 dakikayı aşan kullanımda kesintisiz kuyruk; logout sonrası bir sonraki istekte 401.
