# ADR-QR-003 — QR ve Auth entegrasyonu

- **Durum:** QR personel RBAC matrisi (Q3.1), mevcut anahtar kaynağı (Q7), Kerem'in personel cookie/CSRF tüketici yaklaşımı (Q8) ve tek restoran MVP kapsamı (Q9) 5 Kasım'da Alper tarafından kabul edildi. Kerem'in ilk Q1–Q9 yanıtları kaydedildi. Alper yalnız yerel JWT doğrulamasıyla kalan TTL kadar iptal/rol değişikliği gecikmesini kabul etmedi; güncel oturum/iptal kontrolü istedi. Kerem'in revize çözümü ve ortak sözleşme bekleniyor. Uygulama yapılmadı.
- **Oluşturulma:** 1 Kasım 2026. **Son güncelleme:** 5 Kasım 2026.
- **Hazırlayan:** Alper — QR / Payment sorumlusu. **Auth yanıtı:** Kerem Kalyoncu.
- **Numaralandırma:** Önceki adı `ADR-QR-AUTH-001-qr-auth-entegrasyonu.md` idi. Reviewer'ın aynı QR sırasını kullanma önerisiyle ADR-QR-003 olarak düzenlendi; [ADR-QR-002](ADR-QR-002-security.md) müşteri güvenliğini ele alır.
- **Kapsam:** Personel JWT/cookie, güncellik ve iptal sınırı, CSRF, restoran/RBAC sorumluluğu ve küçük uygulama PR'ları.

Bu belge yanıt, öneri, kabul edilmiş QR kararı ve uygulanmış kodu ayrı gösterir. Kerem'in önerisi Alper'in veya reviewer'ın bütün ayrıntıları onayladığı anlamına gelmez. Kod, ENV, migration, endpoint erişimi ve entegrasyon rehberi bu çalışmada değiştirilmedi. PR #17 hâlâ tartışma/inceleme kaydıdır.

## 1. Review kaynakları ve kapsam

- [Hocanın 2 Kasım review'ı](https://github.com/software-lecture-labs/Restofy/pull/17#pullrequestreview-5743201623): Guest cookie kararı Order/Waiter'ı etkiliyor; K3 ortak karar bekleyen öneri olmalı. Uygulama belleği + Bearer alternatifi de değerlendirilmeli. İki ADR aynı QR numara sırasına alınmalı.
- [Kerem'in 4 Kasım yanıtı](https://github.com/software-lecture-labs/Restofy/pull/17#issuecomment-6634331755) ve [0356bb5'teki Q1–Q9 belgesi](https://github.com/software-lecture-labs/Restofy/blob/0356bb530cd37c83a2142fc8b5fe44004e8ee99c/docs/auth-service/qr-auth-integration-response.md).
- [Aynı commit'teki staff-v1 sözleşmesi](https://github.com/software-lecture-labs/Restofy/blob/0356bb530cd37c83a2142fc8b5fe44004e8ee99c/docs/auth-service/staff-contract.md): JWT/cookie/CSRF üretici ve tüketici davranışları.

Kerem'in yanıtı ve staff-v1 GitHub üzerinden salt okunur incelendi; Auth dalı çekilmedi. Yerel karşılaştırma PR #17 başı `19be5ae` üzerindeki Auth/QR/Order kaynaklarıyla yapıldı. Uzak belgenin veya test isimlerinin bulunması QR entegrasyonunun çalıştığını kanıtlamaz. Yerelde bulunmayan Auth dokümanlarına yukarıda sabit commit bağlantısı verildi.

[ADR-AUTH-001](ADR-AUTH-001-mail-gonderimi.md) Auth → Mail teslimini, [ADR-AUTH-002](ADR-AUTH-002-rate-limit-ve-kilitler.md) Auth limit/kilitlerini ele alır; bu çalışma onların kararlarını veya Auth kodunu değiştirmez.

## 2. Mevcut durum ve önceki öneriden fark

- [StaffJwtTokens](../../backend/services/auth-service/src/main/java/com/restofy/auth/infrastructure/security/StaffJwtTokens.java) RS256 access JWT üretir. Auth kendi [StaffSessionService](../../backend/services/auth-service/src/main/java/com/restofy/auth/application/service/StaffSessionService.java) kontrolünde ayrıca kullanıcı aktifliği, güncel rol, oturum iptali ve idle/mutlak süreyi denetler.
- [AuthCookies](../../backend/services/auth-service/src/main/java/com/restofy/auth/infrastructure/security/AuthCookies.java): `restofy_access` Path `/api/v1`; refresh/MFA Path `/api/v1/auth`. Access HttpOnly; cookie'ler host-only, SameSite Lax ve production'da Secure'dür.
- Alper'in ilk önerisi her istekte güncel Auth oturum kontrolüydü. **Kerem bunun yerine mevcut staff-v1'i koruyarak yerel JWT doğrulaması önerdi.** Bu nedenle yeni introspection, onun servis credential'ı, uzak timeout ve olumlu sonuç cache'i bu önerinin parçası değildir.
- Yerel JWT doğrulaması Auth'un güncel DB kontrolüne eşdeğer değildir. QR hesabın sonradan pasifleştirildiğini, rolün değiştiğini, oturumun iptal/idle nedeniyle kullanılamaz olduğunu doğrudan öğrenmez; imzalı tokenı en fazla geçerli access süresi içinde kabul edebilir. **Alper bu kalan-TTL erişim penceresini kabul etmedi.** Kerem'in ilk yanıtı tarihsel dayanak olarak korunur; hedef tasarım Q5'teki yeni gereksinime göre yeniden yanıtlanmalıdır.
- [QR HTTP sınırı](../../backend/services/qr-service/src/main/java/com/restofy/qr/infrastructure/security/TableSecurityConfiguration.java) sağlık dışında denyAll kalır; gerçek staff verifier/principal/yetki adaptörü henüz eklenmedi.

## 3. Q1–Q9: Kerem'in yanıtları ve kalan kararlar

### Q1 — QR, Auth'a hangi kanıtla başvuracak?

**İlk soru:** Güncel personel oturumu hangi kanıt ve istekle doğrulanacak?

**Kerem'in yanıtı:** Her istekte QR → Auth çağrısı yapılmayacak. QR `restofy_access` cookie'sindeki access JWT'yi Auth public key'iyle yerel doğrulayacak. Refresh/MFA istenmeyecek; istemci header/body kimlikleri kanıt sayılmayacak.

**QR çıktısı:** Doğrulanmış `sub`, `sid`, `jti`, `role`, `restaurant_id`, `auth_state=AUTHENTICATED`, `token_type=access` içeren minimum principal. `staff-v1` sözleşme adıdır; yeni bir JWT claim'i eklendiği varsayılmaz.

**Durum:** İlk Auth yanıtı alındı; Alper Q5'teki gecikmeyi reddettiği için salt yerel doğrulama yeterli çözüm olarak kabul edilmedi. JWT imza/claim kontrolü korunarak güncel oturum/iptal bilgisinin hangi kanıtla öğrenileceğini Kerem yeniden önerecek. QR kodu uygulanmadı.

### Q2 — Auth, QR servisinin kimliğini nasıl doğrulayacak?

**Kerem'in yanıtı:** Personel doğrulamasında Auth çağrısı olmadığı için bu akışta QR servis credential'ı gerekmeyecek. İç ağ veya serbest `X-Service-Name` yine güven kaynağı değildir.

**Sınır:** QR → Order/Payment/Waiter ve Waiter → QR gibi gerçek iç çağrılar ayrı servis kimliği/delegasyon sözleşmeleridir. Personel JWT'si onların yerine geçmez. Kerem'in belirttiği dar Notification → Auth sözleşmesi QR'a otomatik genellenmez. İleride introspection seçilirse ayrı güvenlik tasarımı gerekir.

**Durum:** İlk yerel doğrulama önerisi için yanıtlandı. Revize çözüm QR → Auth çağrısı gerektirirse çağıran kimliği, audience/scope, credential dağıtımı ve rotation yeniden tanımlanmalı. İç servis kimliği bu belgeyle uygulanmış veya genel olarak çözülmüş değildir.

### Q3 — Hangi oturum, kullanıcı, rol ve restoran bilgileri doğrulanacak?

**Kerem'in yanıtı:** Yalnız RS256; yapılandırılmış issuer; audience içinde `restofy-staff-api`; access + AUTHENTICATED; zorunlu ve tutarlı `exp`, `iat`, `nbf`; UUID `sub`, `sid`, `jti`; rol allowlist'i, restoran eşleşmesi ve en fazla 4096 karakter token.

**QR sorumluluğu:** `ADMIN`, `WAITER`, `KITCHEN` bilinen rol değerleridir; roller hiyerarşik değildir ve her rol her QR işlemine erişmez. Endpoint izni ile hedef masa/oturumun restoranı ayrıca doğrulanır. MFA_PENDING, refresh, guest veya service credential personel principalı üretmez.

**Durum:** JWT kontrol listesi yanıtlandı; QR endpoint matrisi Q3.1 ile Alper tarafından kabul edildi. Zaman toleransı/TTL yapılandırması ve güncel oturum kontrolü uygulamadan önce kesinleştirilecek. Token claim'i güncel Auth DB durumu diye sunulmayacak. Revize çözüm aktiflik, oturum iptali/idle ve güncel rolün hangi yetkili sonuçla doğrulanacağını da belirtmeli.

### Q3.1 — Onaylanan QR personel RBAC matrisi

**Karar sahibi / tarih:** Alper, 5 Kasım 2026. **Durum:** QR tarafı için KABUL EDİLDİ / UYGULANMADI. Bu onay Auth güncellik sözleşmesinin, reviewer incelemesinin veya endpoint'lerin açılışının tamamlandığı anlamına gelmez.

Aşağıdaki yolların başlangıcı `/api/v1/qr`'dır. İzin yalnız doğrulanmış personelin kendi restoranındaki hedef masa/QR/oturum içindir. Roller hiyerarşik değildir; ADMIN ve WAITER izinleri açıkça sayılır. KITCHEN rolü bu QR personel endpoint'lerinin hiçbirine erişmez.

| Method | Yol | ADMIN | WAITER | KITCHEN |
| --- | --- | --- | --- | --- |
| POST | `/tables` | İzinli | Reddedilir | Reddedilir |
| PUT | `/tables/{id}` | İzinli | Reddedilir | Reddedilir |
| PATCH | `/tables/{id}/active` | İzinli | Reddedilir | Reddedilir |
| GET | `/tables` | İzinli | İzinli | Reddedilir |
| GET | `/tables/{id}` | İzinli | İzinli | Reddedilir |
| GET | `/tables/{id}/session-statistics` | İzinli | İzinli | Reddedilir |
| POST | `/tables/{tableId}/qr-codes` | İzinli | Reddedilir | Reddedilir |
| POST | `/tables/{tableId}/qr-codes/{qrId}/rotate` | İzinli | Reddedilir | Reddedilir |
| PATCH | `/tables/{tableId}/qr-codes/{qrId}/active` | İzinli | Reddedilir | Reddedilir |
| DELETE | `/tables/{tableId}/qr-codes/{qrId}` | İzinli | Reddedilir | Reddedilir |
| GET | `/tables/{tableId}/qr-codes` | İzinli | Reddedilir | Reddedilir |
| GET | `/tables/{tableId}/qr-codes/{qrId}` | İzinli | Reddedilir | Reddedilir |
| GET | `/tables/{tableId}/qr-codes/{qrId}/image` | İzinli | Reddedilir | Reddedilir |
| GET | `/tables/{tableId}/qr-codes/{qrId}/download` | İzinli | Reddedilir | Reddedilir |
| POST | `/table-sessions/{sessionId}/close` | İzinli | İzinli | Reddedilir |
| GET | `/table-sessions/{sessionId}/close-commands/{commandId}` | İzinli | İzinli | Reddedilir |
| POST | `/table-sessions/{sessionId}/cleaned` | İzinli | İzinli | Reddedilir |

Toplam 17 personel method/path çifti: 11 yalnız ADMIN, 6 ADMIN veya WAITER. Mevcut query/body validation, version ve idempotency gereksinimleri korunur. Rol izni tek başına kaynak sahipliği kontrolü değildir; başka restoran veya aynı restorandaki yanlış table/session/QR eşleşmesi ayrıca reddedilir.

**Kapanış sınırı:** ADMIN/WAITER normal kapanış isteyebilir ve sonucunu sorgulayabilir; bu yetki Order/Payment koşullarını atlama veya force-close/force-rotate yetkisi vermez. Temizlendi ayrı işlemdir. [ADR-QR-001 K11/K12](ADR-QR-001-masa-kapanisi-ve-siparis-koordinasyonu.md) koşulları korunur.

**Kitchen kararı:** Normal mutfak akışı sipariş ve ilgili masa bağlamını Order üzerinden alacak; KITCHEN personeline bunun için QR masa listeleme/detay yetkisi açılmayacak. İncelenen [Order listesi](../../backend/services/order-service/src/main/java/com/restofy/order/api/dto/response/OrderListResponse.java) ve [OrderPlacedEvent](../../backend/services/order-service/src/main/java/com/restofy/order/domain/event/OrderPlacedEvent.java) masa/oturum kimliklerini taşır; masa numarası/görünen ad snapshot'ı içermez. Bu gösterim ihtiyacının Order sözleşmesinde nasıl sağlanacağı Hamza ile netleştirilecek; Order veya Kitchen kodu bu çalışmada değiştirilmedi. İleride siparişi olmayan masaları da içeren bağımsız bir mutfak masa ekranı istenirse yeni gereksinim ve ayrı izin incelemesi gerekir.

**Ayrı güven sınırları:** Bu matris müşteri `/validate`, `/table-sessions/open`, `/guest-sessions/activity` yollarını personel JWT'siyle açmaz. İç Waiter close/status yolları doğrulanmış servis kimliği ve aktif personel delegasyonu ister; yalnız personel JWT'si bu iç servis yetkisinin yerine geçmez. Guest ve iç servis sözleşmeleri ayrıca tamamlanacak.

**Uygulama kabulü:** Her personel yolunda anonim/yanlış credential türü, üç rol, başka restoran, yanlış kaynak bağı ve izinli normal kullanım testleri gerekir. Uygulama ilk dilimden başlayacak; matrisin onayı tüm 17 endpoint'i aynı PR'da açma kararı değildir.

### Q4 — Geçersiz oturumla Auth kesintisi nasıl ayrılacak?

**Kerem'in yanıtı:** Eksik, bozuk, süresi dolmuş veya yanlış imza/tür/audience token 401; geçerli personelin yasak rol/işlemi 403; kaynak/tenant erişimi QR'ın güvenli 403/404 politikası. Public key veya zorunlu güvenlik ayarı hatalıysa başlangıç fail-fast olacak.

**İlk önerinin kesinti davranışı:** Auth kesintisi, önceden verilmiş geçerli JWT'nin yerel doğrulamasını bozmaz; login, refresh ve personel activity etkilenebilir. Tokenın süresi dolarsa uzatılmaz. Kerem'in salt yerel modelinde Auth timeout/retry veya introspection dalı yoktu. Order gibi diğer bağımlılıkların kesinti davranışı bu tartışmayla değiştirilmez.

**Revizyon bekliyor:** Güncel kontrol seçilince kesin geçersiz kullanıcı oturumu ile doğrulama kaynağının kesintisi ayrılmalı. Güncel durum doğrulanamıyorsa sessizce yalnız eski JWT'ye dönülerek erişim açılmamalı; güvenli hata/503, timeout ve tekrar davranışını Kerem önermeli. Servis credential reddi personelin logout olduğu anlamına gelmez. Uzak kontrol QR DB transaction/kilidi altında yapılmamalı. QR hata eşlemeleri ve negatif testleri uygulanmadı.

### Q5 — Önbellek ve iptal gecikmesi ne olacak?

**Kerem'in yanıtı:** Ayrı olumlu introspection cache'i yok. JWT kendi `exp` sınırına kadar kanıttır. Logout, hesap pasifleştirme ve rol değişikliği diğer servislere kalan access ömrü kadar gecikmeyle yansıyabilir. Varsayılan 900 saniye; Auth yapılandırması 300–3600 saniyeye izin verir. Bunlar incelenen sözleşme/örnek değerleridir; gerçek ortam ENV'i okunmadı.

Browser logout sırasında kendi cookie'sini temizleyebilir; daha önce kopyalanmış geçerli tokenın bağımsız QR verifier'ında anında iptal edildiği anlamına gelmez. QR doğrulaması Auth idle zamanını da DB'den sorgulamaz. Süresi geçmiş token için stale kullanım veya otomatik yeni token üretimi yoktur.

**5 Kasım Alper kararı — kalan TTL gecikmesi kabul edilmiyor:** Hesap pasifleştirme, oturum iptali ve yetki kaldırma sonrasında eski JWT'nin süresi dolana kadar QR erişimi sağlaması istenmiyor. Geçerli JWT'ye ek olarak güncel oturum/iptal ve yetki durumu doğrulanmalı. Bu bir güvenlik gereksinimidir; mevcut kapalı QR endpoint'lerinde doğrulanmış bir açık bulunduğu iddiası değildir. QR'ın Auth DB'sini okuması veya bir public introspection endpoint'ini kendiliğinden açması onaylanmış değildir.

**Kerem'den beklenen revizyon:**

1. JWT imza/claim kontrolüne eklenecek güncel oturum/iptal mekanizması ve minimum istek/yanıt alanları; aktiflik, revocation, idle ve güncel rol kapsamı.
2. İç çağrı gerekiyorsa doğrulanmış QR servis kimliği ve sınırlı yetki; public key/secret ayrımı, yeni ENV ve Auth/QR değişiklik sahipliği.
3. Kesin geçersizlik ile bağımlılık kesintisinin ayrılması; timeout, güvenli tekrar ve fail-closed davranışı. Eski olumlu sonuca sessiz fallback yapılmaması.
4. Önbellek/iptal yayılımı düşünülüyorsa bu gereksinimin nasıl korunacağı; herhangi bir gecikme varsa açıkça belirtilmesi ve yeniden onaya sunulması. İlk modeldeki 900 saniye bu gereksinimin yerine geçmez.
5. Auth–QR ortak negatif/eşzamanlılık testleri ve incelenebilir uygulama PR'larının dağılımı.

**Kabul hedefi:** Yetkili kaynakta iptal/pasifleştirme kalıcılaştıktan sonra başlayan yeni bir QR yetkilendirme kontrolü, eski JWT hâlâ geçerli olsa da kaldırılmış yetkiyi vermemeli. Devam eden veya önceden yetkilendirilmiş işlemlerin yarış sınırı ayrıca tanımlanmalı; tüm servislerde aynı anda işlem geri alma garantisi uydurulmamalı. Kesin mekanizma, cache politikası ve hata sözleşmesi Kerem'in revize yanıtından sonra ortaklaştırılacak. [SECURITY](../SECURITY.md) kapsamındaki erişim güncelliği korunacak.

### Q6 — Kontrol personelin idle süresini yenileyecek mi?

**Kerem'in yanıtı:** Yerel JWT doğrulaması, QR okuması ve polling Auth idle süresini yenilemez. Anlamlı personel etkileşiminde frontend mevcut `POST /api/v1/auth/activity` çağrısını Auth cookie + Origin + CSRF ile kontrollü sıklıkta yapar. Guest aktivitesi farklıdır.

**Durum:** İlk Alper önerisiyle örtüşüyor. Hangi frontend etkileşimlerinin ve sıklığın aktivite olacağı ayrıca tanımlanacak; QR'ın Auth'a her istekte activity yazması planlanmıyor.

### Q7 — Public key dağıtımı ve rotation nasıl yapılacak?

**Kerem'in yanıtı:** İlk entegrasyon statik RS256 ve ona karşılık gelen Base64 X.509/SPKI RSA public key kullanacak. Private signing/CSRF/MFA/reset sırları paylaşılmayacak. JWKS, otomatik discovery veya online rotation uygulanmış değildir.

**5 Kasım Alper kararı — mevcut üretim aracı kullanılacak:** Anahtar kaynağı Kerem'in mevcut [ops/auth/generate-keys.mjs](../../ops/auth/generate-keys.mjs) aracıdır; kullanım sınırı [ops/auth/README.md](../../ops/auth/README.md) içindedir. QR entegrasyonu için yeni bir üretim yöntemi, ayrı RSA çifti veya zorunlu anahtar değişimi eklenmeyecek. QR personel JWT'sini, Auth'un gerçekten kullandığı private key'in public karşılığıyla doğrulayacak.

Mevcut araç aynı çiftin private tarafını `AUTH_JWT_PRIVATE_KEY`, Base64 X.509/SPKI public tarafını `MAIL_AUTH_PUBLIC_KEY` adıyla üretir. Public çıktının mevcut adı Mail içindir; aynı public materyal QR personel doğrulamasında da kullanılabilir. Tokenın audience/tür/issuer kontrolleri yine QR'a uygun yapılmalıdır. Auth private key'i veya CSRF/MFA/reset sırları QR'a aktarılmaz; `QR_PAYLOAD_ENCRYPTION_KEY` bu imza anahtarından farklı amaçtadır.

Araç yeni kurulum çıktısını Git dışında tutulan `secrets/auth-mail-keys.env` dosyasına yazar, mevcut hedefi ezmez ve `.env`/`.env.dev` dosyalarını otomatik düzenlemez. Mevcut Auth kurulumu varsa sırf QR eklendi diye yeni çıktıyla aktif anahtarları değiştirmek bu kararın parçası değildir. Kullanıcının talebiyle 5 Kasım'da araç çalıştırılıp yeni kurulum dosyası oluşturuldu; aktif ENV/servis ayarlarına aktarım veya anahtar rotasyonu yapılmadı. Gerçek değerler belgeye veya loga yazılmadı.

**Kalan uygulama ayrıntısı:** QR yapılandırmasında mevcut public key'in okunması, gerekli `.env.example`/Compose aktarımı ve issuer/tenant/TTL ayarları uygulama PR'ında somutlaştırılacak; henüz yeni ENV adı belirlenmedi veya dosya değiştirilmedi. Yeni/değişen ENV olursa repository onay kuralı geçerlidir. Eski/yeni key geçişi ancak ayrıca bir rotation ihtiyacı doğarsa koordineli planlanır; bu entegrasyon için JWKS veya key-ring altyapısı eklenmez. Güncel oturum/iptal kontrolü Q5 kapsamında Kerem'in revize yanıtını beklemeye devam eder.

### Q8 — Personel cookie ve CSRF sözleşmesi nasıl uyumlanacak?

**5 Kasım Alper kararı — Kerem'in mevcut tüketici sözleşmesi kabul edildi:** Personel browser akışında Auth'un mevcut access cookie'si ve CSRF üreticisi kullanılacak. QR için ayrı staff CSRF sırrı, yeni üretim aracı veya zorunlu yeni CSRF endpoint'i eklenmeyecek. Guest cookie için ADR-QR-002'deki ayrı toplantı kararı ve Q5'te bekleyen güncel personel oturumu/iptal çözümü farklı sorumluluklardır. QR kodunda henüz uygulanmadı.

**Kerem'in yanıtı:** Browser access cookie'sini `/api/v1` QR yollarına otomatik taşır. Frontend HttpOnly access tokenını okuyup Authorization header'ına koymaz. QR mutasyonlarında tüketici sözleşmesi birlikte uygulanır:

- İzinli Origin'in tam eşleşmesi; credential'lı browser çağrısı.
- `restofy_csrf` cookie'si ile `X-CSRF-TOKEN` header'ının eşitliği.
- Uygun JSON content type ve endpoint'e özel method/header allowlist.
- Eksik, çift veya uyuşmayan güvenlik girdisinde fail-closed; yalnız Origin veya yalnız cookie eşitliği yeterli sayılmaz.

Auth kendi HMAC/refresh-MFA credential bağını ayrıca denetler. QR bu sırrı almaz ve bu HMAC doğrulamasını yapmış gibi belgelenmez; refresh cookie Path'i genişletilmez. Önceki ayrı QR staff CSRF cookie önerisinin yerine Kerem'in mevcut `restofy_csrf` tüketici sözleşmesi Alper tarafından kabul edildi. Guest CSRF modeli bununla kendiliğinden çözülmez.

**Frontend ayrıntısı:** UI `/api/v1` dışında olduğunda `document.cookie` ile bu Path'teki CSRF cookie'sini okumaya dayanılmaz. `GET /api/v1/auth/csrf` JSON yanıtındaki token alınır; `credentials: include` kullanılır. Login/refresh/MFA değişiminden sonra yeniden alınır; çoklu sekme/refresh koordinasyonu gerekir. Bu JSON CSRF kanıtıdır, personel access JWT'si değildir.

Her QR isteği öncesinde Auth'tan yeni CSRF tokenı veya personel JWT'si alma zorunluluğu kurulmayacak. Frontend CSRF kanıtını ilgili yazma isteğine ekler; kararlaştırılmış oturum değişimi/yenileme anlarında Auth'tan yeniden alır. Bunun Q5 için tasarlanacak güncel personel oturumu kontrolüyle aynı işlem olmadığı korunur.

**Kalan uygulama ayrıntıları:** Yerel/production tam Origin adresleri, endpoint'e özel yöntem/header listesi, eksik/çift/uyuşmayan girdilerin kesin hata eşlemesi ve negatif testler uygulama PR'ında somutlaştırılacak. Credential'lı CORS için wildcard Origin kullanılmayacak. Kesin adres/ENV değeri bu belgeyle seçilmedi; personel yazma yolları bu koruma tamamlanmadan açılmayacak.

### Q9 — Mevcut ve gelecekteki restoran kapsamı ne olacak?

**Kerem'in yanıtı:** MVP tek `AUTH_RESTAURANT_ID`; `restaurant_id` bunun imzalı kapsamıdır. QR kendi hedef restoranını bununla eşleştirir; request parametresi/header üyelik kanıtı değildir. Çoklu üyelik/aktif restoran seçimi uygulanmamıştır; ileride sürümlü tüketici geçişi gerektirir.

**5 Kasım Alper kararı — MVP tek restoran için geliştirilecek:** Restofy'nin bu MVP sürümü tek bir restoranın kullanımına odaklanacak. Auth mevcut tek `AUTH_RESTAURANT_ID` yapılandırmasıyla ilerleyecek; QR ve diğer entegrasyonlar aynı restoran kimliğiyle uyumlu çalışacak. Personelin birden fazla restoran üyeliği, aktif restoran seçimi ve restoranlar arasında geçiş bu MVP'nin kapsamı dışındadır. Bu hedef mevcut Auth sözleşmesiyle uyumludur; yeni üyelik altyapısı bu entegrasyonun ön koşulu yapılmayacak.

**Korunan güvenlik sınırı:** Mevcut `restaurant_id` alanları, kaynak sahipliği kontrolleri ve yanlış restoran reddi korunur. Tek restoran hedefi istemcinin serbest restoran kimliğine güvenme, kontrolü kaldırma veya UUID'yi koda sabitleme yetkisi vermez. QR yetkili restoranı JWT/güncel doğrulama sonucu ile hedef masa/QR/oturum üzerinde eşleştirecek. ADMIN rolü başka restoran verisine genel erişim vermez.

Tarihli analiz belgelerindeki çok restoran hedefi bu MVP'de uygulanmış sayılmayacak. Çoklu restoran desteği istenirse üyelik, aktif restoran, token/güncellik ve tüketici geçişleri ayrı kapsam ve karar olarak ele alınacak. Bu çalışma mimari/gereksinim rehberlerini topluca yeniden yazmaz; MVP kapsam daraltmasını burada kaydeder. QR'ın gerçek restoran ayarı Q7 kapsamında uygulama PR'ında bağlanacak; bu karar için ENV veya DB değişikliği yapılmadı.

**Durum:** MVP kapsamı Alper tarafından KABUL EDİLDİ. Mevcut Auth tek restoran yaklaşımı kullanılacak; QR entegrasyonu henüz uygulanmadı ve Q5 güncel oturum/iptal gereksinimi devam ediyor.

## 4. Rate limit sahipliği ve reviewer kapsamı

Alper'in QR limitlerini QR'da yönetme kararıyla Kerem'in yanıtı örtüşüyor: Auth login/MFA/parola sıfırlamayı; QR tarama, guest oluşturma, activity ve QR yönetimini Auth'a limit çağrısı olmadan yönetir. Gateway'in ilerideki genel trafik koruması servis limitlerinin yerine geçmez. Hoca bu konuya özel kesin bir mimari onay vermiş gibi yazılmaz; tüm servis sahipleri adına karar alınmaz.

Servis sahipliği instance başına ayrı sayaç demek değildir. Sayaç deposu/çoklu instance tutarlılığı, güvenilir proxy adresi, sayısal bütçeler, kapasite/kesinti ve 429/Retry-After davranışı rate limit PR'ında ayrıca karara bağlanacak. Auth/başka servis DB'si kullanılmayacak. Personel kimlik doğrulama ile limit kontrolü farklı sorumluluklardır.

Guest tokenını HttpOnly cookie'ye taşıma Hamza (Order), Umut (Waiter) ve Alper (QR / Payment) tarafından 5.11.2026 toplantısında **onaylanmıştır**. Gerekli taşıma, QR doğrulama ve CSRF/Origin değişiklikleri ilgili servislerin entegrasyonu sırasında yapılacak; teknik ayrıntılar [ADR-QR-002 K3–K6](ADR-QR-002-security.md) kapsamında somutlaştırılacak. Onay ayrıca PR yorumuna bağlı tutulmaz. Personel `restofy_access` sözleşmesi ve guest cookie sözleşmesi ayrı kimlik bağlamlarıdır; uygulamaları henüz tamamlanmadı.

## 5. Alper'in küçük PR planı ve açılış sınırları

Alper 5 Kasım'da önce RBAC/JWT/HTTP cookie entegrasyonu, ardından ayrı rate limit PR'ı istedi. Bu bir uygulama sırasıdır; bütün endpoint'leri iki adımda topluca açma kararı değildir.

| Parça | Planlanan kapsam | Açılış koşulu |
| --- | --- | --- |
| PR 1 — Personel güvenlik temeli | JWT verifier, üzerinde anlaşılacak güncel oturum/iptal kontrolü, minimum principal, Q3.1'de onaylanan rol/tenant/kaynak kontrolleri, personel cookie/CSRF davranışı ve negatif testler | Kerem'in Q5 revizyonu ve Q1–Q4'e etkileri, Q7 ayarlar ve Q8 Origin önce kesinleşecek. İlk inceleme dilimi olarak yalnız masa listeleme/detay önerilir; açılış kapsamı ve bu iki endpoint'in kaynak/limit ihtiyacı ayrıca belirlenecek. |
| PR 2 — QR rate limit | QR'a özel bütçeler, sayaç tutarlılığı, güvenilir istemci adresi, 429/kesinti davranışı ve testler | Yeni ENV/migration/güven tasarımı ihtiyacı varsa önce onay; geliştirme kapalı endpoint'ler üzerinde yapılabilir. |
| Sonraki küçük dilimler | Kalan personel yönetimi/lifecycle, guest browser taşıması ve gerçek iç servis bağlantıları | İşleme uygun CSRF/limit ve gerçek yetki/kaynak kontrolleri hazır oldukça endpoint bazında açılış; her dilim ayrı doğrulanır. |

**Müşteri sınırı:** Tarama/guest açma/activity endpoint'leri, taşıma ve kötüye kullanım korumaları tamamlanmadan PR 1'de açılmaz. Guest cookie kararı rate limit PR'ında dolaylı olarak uygulanmaz. Personel yazma işlemleri için de gereken limitler henüz yoksa güvenlik temeli yazılabilir fakat işlem açılmış sayılmaz. Kapanış/Waiter işlemlerinin gerçek Order/servis kimliği gereksinimleri Auth entegrasyonuyla kendiliğinden tamamlanmaz.

## 6. Açık kararlar ve sahiplik

| Başlık | Durum / sorumlu |
| --- | --- |
| Kalan TTL kadar iptal/rol/idle farkı | Alper kabul etmedi; güncel oturum/iptal kontrolü gereksinimi kaydedildi. Kerem'in revize mekanizma ve sahiplik yanıtı bekleniyor. |
| JWT anahtar kaynağı | Alper onayladı: mevcut ops/auth/generate-keys.mjs; QR Auth'un eşleşen public key'ini kullanacak. Yeni üretim yöntemi veya zorunlu rotation yok. |
| Public key / issuer / restoran / azami TTL / Origin aktarımı | Uygulama ayrıntısı: mevcut anahtarın QR ayarına bağlanması; henüz yeni ENV adı veya runtime değişikliği yok. |
| QR personel rol matrisi | Alper tarafından Q3.1 ile kabul edildi; 11 ADMIN, 6 ADMIN/WAITER; KITCHEN erişimi yok. Kodda uygulanmadı. |
| Personel cookie/CSRF tüketici modeli | Alper tarafından Q8 ile kabul edildi: restofy_access, mevcut Auth csrf endpoint'i, restofy_csrf/X-CSRF-TOKEN eşitliği ve tam Origin kontrolü. QR'da uygulanmadı; kesin Origin ve test ayrıntıları açık. |
| MVP restoran kapsamı | Alper tarafından Q9 ile kabul edildi: uygulama MVP'de tek restoran için geliştirilecek; Auth mevcut AUTH_RESTAURANT_ID ile ilerleyecek. Çoklu üyelik/restoran seçimi MVP dışında; mevcut restoran/kaynak kontrolleri korunacak. |
| İlk PR'da açılacak yollar | Masa listeleme/detay önerisi; matrisin tamamını açma kararı yok. Güvenlik ve açılış koşulları tamamlanacak. |
| Kitchen için masa numarası/görünen ad | Sipariş bağlamının Order üzerinden sağlanması hedefi; mevcut DTO/olayda gösterim snapshot'ı eksik, Hamza ile sözleşme ayrıntısı netleştirilecek. |
| Anlamlı personel aktivitesi ve csrf bootstrap/refresh uygulaması | Frontend + Auth; Q8 CSRF yaklaşımı kabul edildi, gerçek etkileşim/sıklık ve sekme koordinasyonu uygulama ayrıntısı; QR guest aktivitesinden ayrı |
| Guest browser taşıması | Hamza, Umut ve Alper tarafından 5.11.2026 toplantısında HttpOnly cookie olarak onaylandı. Gerekli Order/Waiter/QR ve browser güvenlik değişiklikleri servis entegrasyonlarında uygulanacak; ek PR teyit yorumu beklenmiyor. |
| QR rate limit ayrıntıları | Alper/QR; Gateway/proxy adresi için ilgili geliştiriciyle koordinasyon |
| Gerçek Auth → Gateway → QR E2E | Kerem + Alper; çalıştırılmadı |

Kerem ilk yanıt için yeni Auth endpoint/ENV/migration eklemediğini bildirdi. Bu, Alper'in yeni güncellik gereksiniminin mevcut Auth sözleşmesiyle karşılandığı anlamına gelmez; ek Auth işi gerekip gerekmediğini Kerem revize yanıtında belirleyecek. QR uygulaması Alper'e aittir. Auth'ta kalan bütün entegrasyon işlerinin tamamlandığı veya herkesin PR'ı onayladığı ileri sürülmez. Alper'in bu gereksinimi yerel ADR'ye işlendi; GitHub'da Kerem'e iletildiği veya Kerem'in kabul ettiği varsayılmaz. Yeni ortak kararlara tarih, gerekçe, sahip ve PR/yorum bağlantısı eklenecek.

## 7. Doğrulama ve teslim sınırı

**İNCELENDİ:** PR #17 review/yorumları, sabit commit'teki Auth yanıtı/staff-v1, yerel Auth/QR/Order kodu ve ilgili test kaynakları. **UYGULANMADI:** QR verifier/principal/RBAC/CSRF/rate limit adaptörleri ve yeni yapılandırma. **DOĞRULANMADI:** Gerçek browser/E2E, iptal-gecikme davranışının QR testi ve key rotation.

Bu dokümantasyon çalışmasında backend testleri, pre-push ve Sonar çalıştırılmadı. İlerideki kabul testleri yanlış imza/issuer/audience/tür/zaman/UUID/rol/restoran, MFA/guest/service karışıklığı, duplicate cookie/header, CSRF/Origin, güncel kontrol kesintisi ve polling/idle ayrımını kapsamalı. Hâlâ geçerli JWT ile kalıcı logout/hesap pasifleştirme/rol kaldırma sonrası yeni isteğin reddi, cache/iptal yayılımı ve işlem sürerken değişen yetki ayrıca test edilmeli. Commit/push, PR açıklaması/yorum/review/merge kullanıcı tarafından yapılır.

İlgili kaynaklar: [SECURITY](../SECURITY.md), [QR lifecycle ADR'si](ADR-QR-001-masa-kapanisi-ve-siparis-koordinasyonu.md), [müşteri güvenliği](ADR-QR-002-security.md).
