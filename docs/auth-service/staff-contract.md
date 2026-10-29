# staff-v1 — mevcut personel sözleşmesi

Bu sözleşme mevcut Auth üretimini tarif eder; tüketici uygulaması her servisin sahibindedir. API /api/v1/auth altında csrf, login, refresh, logout, session, activity, mfa/*, password-reset/* yollarını korur. Yeni endpoint, ENV veya migration yoktur.

## Personel JWTsi

- Kaynak: HttpOnly `restofy_access` cookie, Path=/api/v1. İmza yalnız RS256; Auth X.509 RSA açık anahtarıyla yerel doğrulama. Özel signing/CSRF/MFA/reset anahtarları paylaşılmaz.
- `iss=restofy-auth` (yapılandırılmış JWT_ISSUER), `aud` içinde `restofy-staff-api`, `token_type=access`, `auth_state=AUTHENTICATED`. MFA audience/türü staff için reddedilir.
- `sub` kullanıcı UUID, `sid` session UUID, `jti` UUID; `role=ADMIN|WAITER|KITCHEN`; `restaurant_id` sabit AUTH_RESTAURANT_ID. Kimlik client header/body alanlarından türetilmez. Roller hiyerarşik değildir.
- En çok 4096 karakter. İmza/algoritma/issuer/audience/tür/rol/tenant/UUID alanları, exp/nbf/iat varlığı ve zaman aralığı denetlenir. Access TTL ayarı 300–3600 sn; varsayılan 900. JWT mutlak session sonunu aşmaz. exp anında geçersizdir.
- Auth verifier kendi DBsinden session/rol/aktiflik de denetler; consumer yalnız yerel JWT doğruladığında logout/disable/rol değişikliği access süresi bitene kadar yayılmayabilir. Bu karar değiştirilmedi; anlık merkezi kontrol vaadi yoktur.

## Cookie ve CSRF

| Cookie | Path | HttpOnly |
|---|---|---|
| restofy_access | /api/v1 | evet |
| restofy_refresh | /api/v1/auth | evet |
| restofy_mfa | /api/v1/auth | evet |
| restofy_csrf | /api/v1 | hayır |
| restofy_reset | /api/v1/auth/password-reset | evet |

Cookie host-only ve SameSite=Lax; production Secure. Refresh/pending/reset raw tokenları downstream staff uçlarına taşınmaz. Kimlik tokenı JSONda, localStorage/sessionStorage/IndexedDBde tutulmaz.

Her mutasyonda izinli Origin + cookieyle aynı X-CSRF-TOKEN gerekir. Auth HMAC ve credential bağını ayrıca denetler; consumerlar bu sırrı almaz. Consumer Origin, JSON içerik türü ve çift gönderim eşitliği uygular; eksik güvenlik girdisinde fail closed. Auth CORS yalnız GET/POST/OPTIONS; consumer kendi onaylı yöntemlerini açar.

**Frontend dar-path ayrıntısı:** UI /api/v1 dışında olduğunda document.cookie CSRF cookieyi okuyamaz. GET /api/v1/auth/csrf yanıtının token alanını al; login/refresh/MFA yükseltmesi sonrası yeniden al. credentials: include kullan. Tek cookie deposu sekmeler arasında paylaşılır; refresh single-flight gerekir.

Refresh tek kullanımlık; ilk kullanımdan sonraki 10 sn tekrar 200 fakat yeni credential vermez. Pencere sonrasında reuse ilgili sessionı iptal eder. Activity yalnız gerçek kullanıcı etkileşiminde; polling/refresh/session okuma idle yenilemez. ADMIN/WAITER idle 15 dk, KITCHEN 60 dk; varsayılan mutlak session 7 gün.

## Ayrı kimlik türleri ve Saga

- MFA_PENDING: kısa ADMIN doğrulama yetkisi; staff business yetkisi yok.
- Guest: QRın alanı; masa/guest tokenı staff JWT olarak kullanılamaz. Guest taşıma/revoke sözleşmesi tamamen uygulanmış değildir.
- Auth→Mail mevcut sözleşmesi: [Mail sayfası](mail-service/README.md). Bearer + aud=restofy-mail + scope=password-reset:send; genel Service şemasıyla aynı değildir.
- Genel servis JWT önerisi: her çağıran kendi anahtarıyla RS256, Authorization: Service, iss/sub çağıran servis, aud hedef, en çok 60 sn, replay/endpoint izin matrisi. Bu modelin tüm servislerde uygulandığı iddia edilmez.
- Saga iş komutları kaynak servisler arasında kalır. Kimlik doğrulama ve dağıtık transaction/telafi farklı sorumluluklardır.

## Uyumluluk ve kanıt

Mevcut cookie/claim/endpoint isimleri korunur. Değişiklik gerektiğinde yeni bir sözleşme sürümü ve tüketici geçişi ayrıca planlanır. Auth tarafında bütün consumerlara özel endpoint açılmaz. StaffConsumerContractTests üreticiyi JCA/Jackson tabanlı bağımsız test tüketicisiyle doğrular; bu test gerçek Order/Kitchen/QR runtime entegrasyonu değildir. SessionSecurityTests, StaffAuthHttpTests, StaffSessionIntegrationTests, AuthCookiesTests mevcut negatif/CSRF/refresh kanıtlarını sağlar.
