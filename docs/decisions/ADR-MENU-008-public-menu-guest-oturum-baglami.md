# ADR-MENU-008 — Public menü: restoranın QR guest-oturum doğrulamasından alınması (altyapı)

- **Durum:** Kabul edildi. QR sözleşmesi ve Menu servis kimliği PR #65 ile `dev`'de; **Menu tarafındaki gerçek QR istemcisi MENU-INT-04 ile eklendi** (anahtar yapılandırılmadıkça kapalı, üretimde ENV ve Compose bağlantısı ayrıca onay bekliyor)
- **Tarih:** 2026-11-25 (MENU-INT-04)
- **Sahip:** Menu (Merve); doğrulayıcı QR (Alper), servis kimliği Auth (Kerem)
- **İlgili kod:** `GuestToken`, `GuestSessionVerification`, `GuestSessionVerifier`, `PublicGuestScopeInterceptor`, `GuestSessionRestaurantScopeResolver`, `NotConnectedGuestSessionVerifier`, `PublicGuestCookie`, `VerifiedGuestScope`, `PublicMenuWebConfiguration`
- **Talep:** [qr-request-01](../menu-service/requests/qr-request-01.md) (QR'dan beklenen kararlar orada)

## Bağlam

[ADR-MENU-001](ADR-MENU-001-musteri-menusu-guven-modeli.md) müşteri menüsünün restoranını `PublicRestaurantScopeResolver` portuna bıraktı ve üretimde fail-closed (404) yaptı. Restoranı güvenilir biçimde QR bilir; ama QR'ın müşteri yolları kapalıdır, iç guest doğrulama ucu yalnız Order'ın servis kimliğini kabul eder ve Menu için tanımlı bir servis kimliği yoktur. Alper önerilen sözleşmeyi henüz onaylamadı. Bu ADR, **karardan bağımsız** olarak yazılabilen Menu altyapısını kaydeder; QR çağrısını, servis kimliğini ve QR'sız menüyü uydurmaz.

## Karar

1. **Doğrulama transaction'dan önce, web katmanında.** `PublicGuestScopeInterceptor`, yalnız `/api/v1/menu/public/**` için controller'dan önce guest kimliğini `GuestSessionVerifier`'a sorar ve sonucu istek özniteliğine yazar. Menü okuması `REPEATABLE READ` transaction içinde çalışır; QR'a ağ çağrısı orada yapılırsa yavaş bir QR veritabanı bağlantısını tutardı. Çözümleyici yalnız bellekteki sonucu okur, G/Ç yapmaz. Mevcut servis ve controller imzaları değişmedi.
2. **Üç sonuç, dördüncüsü yok.** `Verified(restaurantId)`: güvenilir kaynak onayladı (sıfır UUID olamaz). `Rejected`: kaynak açıkça reddetti. `Unavailable`: karar verilemedi (ulaşılamıyor, zaman aşımı, Menu'nun kimliği reddedildi, hız sınırı, bozuk yanıt). Doğrulayıcı istisna fırlatırsa veya `null` dönerse `Unavailable` sayılır: hata asla başarı ya da ret sayılmaz.
3. **Restoranı yalnız doğrulanmış sonuç belirler.** Üretim çözümleyicisi (`GuestSessionRestaurantScopeResolver`) bir referansı yalnız şu iki koşul birlikte sağlanırsa güvenilir sayar: isteğin guest kimliği doğrulanmış **ve** URL'deki referans o restoranın kanonik UUID'si. URL, başlık, sorgu, gövde, `Authorization`, başka çerezler ve çerezdeki değer restoran kimliği sayılmaz. Doğrulanmış restoranın dışındaki bir restoranı URL ile seçmek, doğrulanmamış restoranla aynı 404'tür.
4. **Çerez.** Yalnız `restofy_guest` adlı **tek** ve 43 karakterlik URL-safe çerez kimlik sayılır (ADR-QR-002 K3). Hiç, biçimsiz veya aynı isimli iki çerez "kimlik yok" demektir ve QR'a sorulmaz. Kimlik (`GuestToken`) metne çevrilince kendini gizler; yanıta, URL'ye, loga ve istisna metnine girmez.
5. **QR'a gereksiz soru yok.** Kimlik yoksa veya URL referansı kanonik UUID değilse (hiçbir zaman doğrulanmış restoranla eşleşemez) QR'a sorulmaz.
6. **HTTP eşlemesi.** `Verified` + eşleşen URL: menü (200). `Rejected`, kimlik yok, eşleşmeyen veya biçimsiz referans: ADR-MENU-001'deki aynı 404. `Unavailable`: `503`, sabit metin, `no-store`, **hiçbir menü verisi ve önbellek yok**; 404 ile karıştırılmaz.
7. **Üretim varsayılanı bağlı değil; gerçek istemci yalnız anahtar tanımlıysa devreye girer.** `NotConnectedGuestSessionVerifier` her kimliği `Rejected` yapar. Yani QR bağlanana kadar hiçbir şey doğrulanmaz ve eski `DenyAll` çözümleyicinin davranışı (her müşteri isteği 404) aynen sürer. `PublicGuestSessionProductionWiringTests` bunun bekçisidir: iyi biçimli çerez ve restoranın gerçek UUID'siyle bile 404.
8. **Güvenlik zinciri değişmedi.** `MenuSecurityConfiguration` (müşteri zinciri yalnız `GET`) ve yönetim uçları olduğu gibidir; interceptor yönetim uçlarına uygulanmaz ve guest çerezi yönetim yetkisi vermez.

## Seçenekler

| Seçenek | Artı | Eksi | Sonuç |
|---|---|---|---|
| Çözümleyici içinde QR çağrısı | Port imzası aynı kalır | QR çağrısı sırasında DB bağlantısı tutulur; yavaş QR havuzu tüketir | Seçilmedi |
| Port imzasını (`resolve(ref, token)`) değiştirmek | Açık | Tüm servis imzaları ve servisleri doğrudan çağıran birkaç test sınıfı değişir | Seçilmedi |
| **Web katmanında doğrulama + istek özniteliği** | Imzalar değişmez; G/Ç transaction dışında; çözümleyici saf | Gizli bir istek bağlamı vardır (tek yerde, bu ADR'de belgeli) | **Seçildi** |

## Yapılmayanlar ve sınırlar (gizlenmiyor)

- **QR'a gerçek HTTP istemcisi yok.** Önerilen uç ve yanıt biçimi qr-request-01'de bir **taslaktır**; kabul edilmemiştir. Servis kimliği (D-09) olmadan korumasız ya da uydurma kimlikli bir çağrı yazılmadı.
- **`restofy_guest` adı QR kodundan alındı** ve bir tüketici servisin bu çerezi okuması ADR-QR-002 §3'te hâlâ açık soru (talep, karar 10). Gerçek doğrulayıcı bağlanana kadar çerezin değeri hiçbir yetkiye dönüşmez.
- **URL referansı = restoran UUID'si** kuralı Menu'nun geçici kuralıdır. Restoran kaydı (Admin) gelirse ve slug kullanılırsa yeniden değerlendirilir.
- **QR'sız menü etkinleştirilmedi** (talep, madde 6).
- **Önbellek ve yeniden deneme yok**; kararları QR ile bekliyor.
- Restoranın açık/kapalı durumu (Admin) okunamaz; müşteri menüsü bunu yansıtmaz.
- `MenuSecurityConfiguration` ve README'ye dokunulmadı (MENU-INT-01 dalıyla çakışmamak için). README'deki "fail-closed port" anlatımı davranış olarak hâlâ doğrudur.

## MENU-INT-04 durumu (25 Kasım 2026)

**KISMİ.** Menu tarafı hazır ve testli; gerçek doğrulama QR kararlarına ve servis kimliğine bağlı.

| Parça | Durum |
|---|---|
| Menu: restoran bağlamı çözümleme altyapısı, port, hata ayrımı, fail-closed varsayılan | Tamam, testli |
| QR'a gerçek istemci | **Bekliyor** (QR sözleşmesi + D-09) |
| Müşteri endpoint'lerinin gerçek restoranla çalışması | **Bekliyor** (üretimde 404) |
| Çerezin Gateway'den Menu'ya taşınması, ortak ortamda uçtan uca deneme | Doğrulanmadı |

## MENU-INT-04 güncellemesi (27 Kasım 2026): gerçek QR istemcisi

QR tarafı PR #65 ile `dev`'e girdi (Alper; Auth tarafından "itirazım yok", D-09'un son kararı hocada). Menu tarafında
`GuestSessionVerifier` portuna gerçek HTTP istemcisi bağlandı. Önceki karar maddeleri (üç sonuç, web katmanında doğrulama,
restoranı yalnız doğrulanmış sonucun belirlemesi, 404/503 ayrımı) **değişmedi**; yalnız doğrulayıcının gerçeği değişti.

| Konu | Karar |
|---|---|
| Uç | `POST /api/v1/internal/qr/menu/guest-sessions/validate`, gövde `{"guestToken":"<43 karakter>"}` (QR sözleşmesi, `docs/qr/entegrasyon-rehberi.md`) |
| Kimlik | Menu'nun **kendi** RSA anahtarıyla `Authorization: Service <JWT>`: RS256 + `kid`, `iss=restofy-menu`, `sub=menu-service`, `aud=["restofy-qr"]`, `token_type=service`, `scope=qr:menu-guest-validate`, UUID `jti`, `iat==nbf`, ömür ≤ 30 sn, `method`/`target`/`body_sha256`. **`restaurant_id` claim'i yoktur** (QR taşıyan token'ı 403 ile reddeder). `iat` saat farkına karşı 5 sn geriye alınır |
| Gönderilmeyenler | `Cookie`, `Origin`, `Content-Encoding`, istemci başlıkları, restoran. İstek yalnız bu iki başlık ve JSON gövdeyle gider |
| Sonuç eşlemesi | `200` + `state=ACTIVE` + gerçek restoran kimliği: `Verified`. `404` **ve** `code=GUEST_SESSION_UNAVAILABLE` (`application/problem+json`): `Rejected`. Başka her şey `Unavailable` (503): `400/401/403/429/5xx`, kodsuz `404`, yönlendirme (izlenmez), zaman aşımı, bağlantı hatası, bozuk/büyük/yanlış türde gövde. Yeniden deneme ve önbellek yok |
| Kanal | HTTPS kaynağı; düz HTTP yalnız `localhost`/`127.0.0.1`/`[::1]` (guest token gövdede gider). Yol, sorgu, kullanıcı bilgisi, parça yok |
| Zaman aşımı | Bağlantı 2 sn, okuma 3 sn (yapılandırma özelliği, ENV değil) |
| Açma | `MENU_QR_CALLER_PRIVATE_KEY` boşsa bağlantı **kapalı** (her müşteri isteği 404, eski davranış). Doluysa `QR_SERVICE_URL`, `MENU_QR_CALLER_KEY_ID` ve anahtar geçerli olmalı, değilse uygulama **başlangıçta durur**; hata ayar adını söyler, değeri asla göstermez |
| Sırlar | Guest token ve imzalı kimlik hiçbir log satırına, istisna metnine, yanıta veya URL'ye girmez; hata yalnız sınıf adıyla loglanır |

**Yeni ENV (onay ve Compose bağlantısı bekliyor):** `MENU_QR_CALLER_KEY_ID` ve `MENU_QR_CALLER_PRIVATE_KEY` (Base64 PKCS#8 RSA ≥ 2048 bit);
`QR_SERVICE_URL` mevcut değişkendir. QR'a eşleşen **açık** anahtar `QR_MENU_CALLER_PUBLIC_KEYS` ile verilir. Bu iş `.env.example`,
`docker-compose.yml` veya ortak yapılandırmaya **dokunmadı**; Menu servisine ENV aktarımı Alper'in ortak dosyalarındadır.

### Doğrulama kapsamı (abartılmadan)

| Seviye | Ne yapıldı |
|---|---|
| Birim ve sözleşme testleri | İmzalayıcı, istemci (her yanıt ve hata yolu), yapılandırma, log sızıntısı; QR'ın `MenuCallerFilter` kurallarını kaynak koddan yeniden uygulayan **sözleşme sunucusuna** karşı. Bunlar **E2E değildir** |
| Tam zincir testi | Çerez, interceptor, gerçek HTTP istemci, çözümleyici, PostgreSQL menü okuması; QR yerine aynı sözleşme sunucusu |
| **Gerçek iki servis** | QR (`dev`, `qr-order-inbound` profili) ve Menu iki ayrı süreç olarak, atılabilir PostgreSQL ve üretilmiş test anahtarlarıyla yerelde çalıştırıldı; curl ile 26 çağrı (geçerli oturum, iki restoran izolasyonu, kapalı/bitmiş/süresi dolmuş/bilinmeyen oturum, sahte başlık ve parametre, QR kesintisi ve dönüşü, QR'ın tanımadığı anahtar, bağlantı kapalıyken varsayılan). Süreçler ve anahtarlar silindi |
| Yapılmayan | Tarayıcı, Gateway ve Compose üzerinden uçtan uca akış (QR misafir yolları K7 hız sınırıyla henüz açılmadı); QR'ın Order'a sorduğu sınır durumu (Order bağlı değilken 503 gözlendi) |
