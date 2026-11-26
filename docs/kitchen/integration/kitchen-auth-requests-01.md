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

---

## AUTH-01 — Kitchen için Auth açık anahtarı

**Kitchen tarafı tamamlandı** (ENV için proje sahibinin onayıyla): `KITCHEN_AUTH_STAFF_PUBLIC_KEY`, Kitchen `application.properties`, `.env.example` (boş değer) ve `docker-compose.yml` içinde yalnız `kitchen-service`.

**Mevcut durum:** Değer repoda yoktur ve olmamalıdır. Boşken ortak ortamda Kitchen personel doğrulaması **kapalıdır**; her personel isteği 401 alır. Anahtar yalnız AUTH-02 karşılandıktan sonra doldurulmalıdır; aksi halde Kitchen başlamaz.

**Beklenen:** Auth'tan, personel imza anahtarının Base64 X.509 **açık** karşılığının resmi alınma yolu. Özel anahtar veya CSRF/MFA/reset sırrı Kitchen'a verilmez.

**Kabul:** Anahtar boşken tüm personel istekleri 401; doğru anahtarla gerçek login cookie'si 200; başka bir çiftin açık anahtarıyla 401; hatalı anahtarda uygulama başlamaz ve hata mesajı değeri yazmaz.

## AUTH-02 — Servisler arası HTTPS Auth adresi

**Mevcut durum / kanıt:** Compose'da `AUTH_SERVICE_URL` ortak `runtime-env` ile gelir; örnek değer düz HTTP'dir (`http://auth-service:8080`) ve Compose'da Auth için TLS tanımı yoktur. Kitchen, personel cookie'sini ilettiği için anahtar tanımlıyken **yalnız HTTPS origin** kabul eder; aksi halde başlamaz. Order (`order-auth`) ve Waiter aynı şartı uygular.

Aynı ortak `AUTH_SERVICE_URL`, API Gateway'in Auth rotasının da hedefidir ve Compose'da Kitchen'a özel bir adres verecek bir bağlantı yoktur. Ortak değeri değiştirmek diğer servisleri etkiler.

**Etki:** **Açık engel.** AUTH-01 tek başına yeterli değildir: anahtar bugünkü HTTP adresle birlikte doldurulursa Kitchen başlamaz. Ayrıca `JWT_ACCESS_TTL_SECONDS` 300 olmalıdır (`.env.example` öyledir); eski bir yerel ENV dosyasındaki 900 değeri de Kitchen'ı (ve `dev` Auth'u) başlatmaz.

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

## AUTH-04 — Mutfak ekranının oturum sürekliliği

**Mevcut durum / kanıt:** Access cookie 300 sn yaşar; [ADR-AUTH-Q5](../../decisions/ADR-AUTH-Q5-coordinated-revocation.md) frontend silent refresh'i **NOT IMPLEMENTED** olarak kaydeder. KITCHEN idle süresi 60 dk'dır ve yalnız `POST /api/v1/auth/activity` ile yenilenir; Kitchen okumaları ve `/session` sorgusu yenilemez.

**Etki:** Frontend bu iki işi yapmadan mutfak ekranı 5 dakikada bir 401 alır; yalnız izlenen (tıklanmayan) ekran 60 dk sonra oturumdan düşer.

**Beklenen:** Frontend tarafında sekmeler arası tek uçuşlu refresh ve "mutfakta hangi etkileşim aktivite sayılır" kararı. Güvenlik süreleri Kitchen tarafından uzatılmaz.

**Kabul:** Gerçek tarayıcıda 5 dakikayı aşan kullanımda kesintisiz kuyruk; logout sonrası bir sonraki istekte 401.

## AUTH-05 — Servis kimliği modeli kararı (D-09)

**Mevcut durum / kanıt:** Auth README'si servis kimliğinin bu teslimde olmadığını yazar. Order, çift bazlı RS256 imzalı istek modelini önerir (PR #47, `dev`'de değil: `kid`, `iss`, `sub`, `aud`, `scope`, `restaurant_id`, `method`, `target`, `body_sha256`, en çok 30 sn). Menu aynı konuyu sorar (PR #50, madde 8).

**Durum: AÇIK.** Yeni anahtar veya güven ilişkisi bu görevde onaylanmadı.

**Etki:** Kitchen → Order ve Kitchen → Menu bağlantıları kapalıdır. Kitchen servis anahtarı, doğrulayıcı veya iç endpoint **eklemedi**: bu yeni bir güven ilişkisi ve yeni sırlar demektir ([SECURITY.md](../../SECURITY.md) §1, §17).

**Yanıt beklenen sorular:**
1. Order'ın çift bazlı modeli ekip modeli olarak kabul ediliyor mu, yoksa Auth merkezi bir servis kimliği mi verecek?
2. Adına işlem yapılan personel nasıl taşınacak: imzalı claim mi, yalnız denetim amaçlı gövde alanı mı?

**Kabul:** Yazılı karar; servis kimliği tek başına personel yetkisi vermez; personel cookie'si veya JWT'si başka servise iletilmez (Auth `/session` hariç).
