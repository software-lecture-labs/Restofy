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

**Doğrulama:** Kitchen testleri ve bir izole kabul denemesi. Denemede `dev`'deki değiştirilmemiş Auth ve QR kodu, geçici PostgreSQL, tek kullanımlık anahtarlar ve Auth'un önünde test amaçlı bir HTTPS proxy kullanıldı. Gerçek login cookie'si ve CSRF değeri kabul edildi; refresh sonrası yeni cookie çalıştı; gerçek logout'tan sonra süresi dolmamış eski cookie hemen 401 aldı. Auth kesintisi proxy'nin 503 dönmesiyle canlandırıldı.

**Doğrulanmayanlar:** MFA açık gerçek bir ADMIN hesabı (denemede `AUTH_MFA_REQUIRED=false`), hesap pasifleştirme ve rol değişikliği, gerçek TLS dağıtımı, Gateway ve tarayıcı üzerinden akış.

---

## AUTH-01 — Kitchen için Auth açık anahtarı ve ENV adı

**Mevcut durum / kanıt:** `.env.example` ve Compose'da `ORDER_AUTH_STAFF_PUBLIC_KEY` ve `WAITER_AUTH_STAFF_PUBLIC_KEY` var; Kitchen için karşılığı yok. Kitchen'ın `restofy.auth.staff-public-key` özelliğine bağlı bir ENV anahtarı tanımlanmadı.

**Etki:** Ortak ortamda Kitchen personel doğrulaması **kapalıdır**; her personel isteği 401 alır.

**Beklenen (onay bekliyor, uygulanmadı):**
1. Yeni anahtar `KITCHEN_AUTH_STAFF_PUBLIC_KEY`: Auth'un personel imza anahtarının Base64 X.509 **açık** karşılığı. Özel anahtar veya CSRF/MFA/reset sırrı Kitchen'a verilmez.
2. `.env.example`: boş değerli satır ve açıklama. `docker-compose.yml`: yalnız `kitchen-service.environment` altında `KITCHEN_AUTH_STAFF_PUBLIC_KEY: ${KITCHEN_AUTH_STAFF_PUBLIC_KEY:-}`.
3. Kitchen `application.properties`: `restofy.auth.staff-public-key=${KITCHEN_AUTH_STAFF_PUBLIC_KEY:}` (Kitchen dosyası; onaydan sonra eklenir).
4. Auth'tan: anahtarın üretildiği/alındığı resmi yol.

**Kabul:** Anahtar boşken tüm personel istekleri 401; doğru anahtarla gerçek login cookie'si 200; başka bir çiftin açık anahtarıyla 401; hatalı anahtarda uygulama başlamaz ve hata mesajı değeri yazmaz.

## AUTH-02 — Servisler arası HTTPS Auth adresi

**Mevcut durum / kanıt:** Compose'da `AUTH_SERVICE_URL` ortak `runtime-env` ile gelir; örnek değer düz HTTP'dir (`http://auth-service:8080`) ve Compose'da Auth için TLS tanımı yoktur. Kitchen, personel cookie'sini ilettiği için anahtar tanımlıyken **yalnız HTTPS origin** kabul eder; aksi halde başlamaz. Order (`order-auth`) ve Waiter aynı şartı uygular.

**Etki:** AUTH-01 tek başına yeterli değildir.

**Beklenen:** Kitchen container'ından erişilen, sertifikasının SAN alanı adresle eşleşen bir HTTPS Auth origin'i; Kitchen'a yalnız servis bazlı `AUTH_SERVICE_URL` override'ı; özel CA kullanılacaksa salt okunur truststore. Sertifika ve hostname doğrulaması kapatılmaz.

**Kabul:** Paylaşılan ortamda login → `GET /api/v1/kitchen/orders` 200; güvenilmeyen sertifikada 503; HTTP adresle Kitchen başlamaz.

## AUTH-03 — `/session` sorgusunun desteklenen tüketici sözleşmesi olduğunun teyidi

**Mevcut durum / kanıt:**
- `dev`'deki [ADR-AUTH-Q5](../../decisions/ADR-AUTH-Q5-coordinated-revocation.md) iptal bildirimini yalnız QR'a gönderir.
- `origin/feature/auth-service` üzerindeki `docs/auth-service/staff-contract.md` (`dev`'de yok) tüketici için yerel doğrulama tarif eder, logout/pasifleştirme/rol değişikliğinin access süresi bitene kadar yayılmayabileceğini ve anlık kontrol vaadi olmadığını yazar. Aynı belge access TTL için "300–3600, varsayılan 900" der; `dev` kodu tam 300'ü zorunlu tutar.
- `dev`'de [Order](../../order-service/INTEGRATION_CONTRACTS.md) (§2.1) ve Waiter bu boşluğu her istekte mevcut `GET /api/v1/auth/session` ile kapatır. Menu (PR #46) yalnız yerel doğrulamayı seçmiştir.

**Kitchen'ın seçimi:** Order/Waiter modeli. Gerekçe: Auth'ta değişiklik gerektirmeden logout ve iptalin hemen yansıması.

**Etki:** Auth erişilemezken Kitchen personel istekleri 503 alır. `StaffSessionService.authenticate` her çağrıda kullanıcı satırını kilitler; açık kalan mutfak ekranlarının polling trafiği Auth'a aynen yansır.

**Yanıt beklenen sorular:**
1. `GET /api/v1/auth/session` servis tüketicileri için desteklenecek mi? (`status`, `role`, `idleExpiresAt`, `absoluteExpiresAt`, `mfaSetupRequired`; 401 = ret; sorgu personel aktivitesi sayılmaz.)
2. Bu uca ileride hız sınırı gelirse servis çağrıları nasıl hesaplanacak?
3. Güncel personel sözleşmesi (TTL 300 ile) `dev`'e alınacak mı?

**Kabul:** Kararın `dev`'deki bir belgede kayıtlı olması. Yanıt "hayır" ise Kitchen için kabul edilen iptal gecikmesi veya alternatif mekanizma aynı belgede yazılmalı.

## AUTH-04 — Mutfak ekranının oturum sürekliliği

**Mevcut durum / kanıt:** Access cookie 300 sn yaşar; [ADR-AUTH-Q5](../../decisions/ADR-AUTH-Q5-coordinated-revocation.md) frontend silent refresh'i **NOT IMPLEMENTED** olarak kaydeder. KITCHEN idle süresi 60 dk'dır ve yalnız `POST /api/v1/auth/activity` ile yenilenir; Kitchen okumaları ve `/session` sorgusu yenilemez.

**Etki:** Frontend bu iki işi yapmadan mutfak ekranı 5 dakikada bir 401 alır; yalnız izlenen (tıklanmayan) ekran 60 dk sonra oturumdan düşer.

**Beklenen:** Frontend tarafında sekmeler arası tek uçuşlu refresh ve "mutfakta hangi etkileşim aktivite sayılır" kararı. Güvenlik süreleri Kitchen tarafından uzatılmaz.

**Kabul:** Gerçek tarayıcıda 5 dakikayı aşan kullanımda kesintisiz kuyruk; logout sonrası bir sonraki istekte 401.

## AUTH-05 — Servis kimliği modeli kararı (D-09)

**Mevcut durum / kanıt:** Auth README'si servis kimliğinin bu teslimde olmadığını yazar. Order, çift bazlı RS256 imzalı istek modelini önerir (PR #47, `dev`'de değil: `kid`, `iss`, `sub`, `aud`, `scope`, `restaurant_id`, `method`, `target`, `body_sha256`, en çok 30 sn). Menu aynı konuyu sorar (PR #50, madde 8).

**Etki:** Kitchen → Order ve Kitchen → Menu bağlantıları kapalıdır. Kitchen servis anahtarı, doğrulayıcı veya iç endpoint **eklemedi**: bu yeni bir güven ilişkisi ve yeni sırlar demektir ([SECURITY.md](../../SECURITY.md) §1, §17).

**Yanıt beklenen sorular:**
1. Order'ın çift bazlı modeli ekip modeli olarak kabul ediliyor mu, yoksa Auth merkezi bir servis kimliği mi verecek?
2. Adına işlem yapılan personel nasıl taşınacak: imzalı claim mi, yalnız denetim amaçlı gövde alanı mı?

**Kabul:** Yazılı karar; servis kimliği tek başına personel yetkisi vermez; personel cookie'si veya JWT'si başka servise iletilmez (Auth `/session` hariç).
