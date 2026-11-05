# Q5 Auth–QR sözleşmesi

Bu belge yalnız Q5 eklerini tanımlar; dev'de bulunmayan personel yönetimi API'leri varmış gibi göstermez. Tasarım ve restore varsayımları [ADR önerisindedir](../decisions/ADR-AUTH-Q5-coordinated-revocation.md).

## Browser ve servis davranışı

| Yol/işlem | Davranış |
| --- | --- |
| `POST /api/v1/auth/logout` | Normal Auth cookie/CSRF sözleşmesi sürer. QR ACK + FINALIZE tamamlanırsa 204 ve cookie temizliği; kesintide kalıcı PREPARE ile 503. Aynı SID yeniden denenir. |
| Reset confirm / MFA recovery-code regenerate | İlk doğrulanmış istek PREPARE ile 503 olabilir. Worker ACK sonrası aynı amaç/kanıtla retry, FINALIZE ve iş değişikliğini atomik tamamlar. 503 başarı değildir. |
| Login/refresh/MFA grant | Kullanıcıda pending operation veya kapalı kaynak bariyeri varsa yeni credential üretilmez. |
| QR staff isteği | Yerel RS256/claim doğrulaması + primary DB guard + yerel rol/restaurant/kaynak kontrolü. Auth HTTP çağrısı yapılmaz. |

QR'da invalid/expired JWT veya iptal SID 401; doğrulanmış kimliğin rol/CSRF reddi 403; guard/DB/readiness kesintisi 503'tür. Önceden başlayan bir işin sonradan iptal edildiği iddia edilmez.

## Internal komut

`POST /internal/q5/revocations`, `Authorization: Bearer <dedicated service JWT>`, JSON body:

```text
operationId: UUID
restaurantId: UUID
userId: UUID
revision: positive integer
generation: stable QR consumer UUID
targets: [{ sessionId: UUID, denyUntil: Instant }]
```

JWT: `iss=auth-service`, `aud=restofy-qr-revocation`, `token_type=service`, `scope=staff:revocation:write`; `sub=operationId`; restaurant, generation ve canonical manifest SHA-256 claim'leri; UUID jti, iat/nbf/exp, en çok 30 saniye. Digest normalleştirilmiş UUID/Instant alanlarına bağlıdır; wire JSON boşlukları güvenlik girdisi değildir.

Yanıt `{receipt: <signed JWT>}`. QR imzası yalnız commit sonrası oluşturulur. ACK: `iss=qr-service`, `aud=restofy-auth`, `scope=staff:revocation:ack`, aynı operation/restaurant/generation/digest ve kısa süre. Bir HTTP 200 tek başına ACK değildir. Duplicate komut kayıtlı manifesti yeniden doğrular; yeni, kısa ömürlü imza aynı kalıcı sonuca bağlıdır.

`POST /internal/q5/challenge` body `operationId,restaurantId,generation`; scope `staff:bootstrap:challenge`. Yanıt güncel DB nonce'u ve readiness bilgisidir. Bu cevap tek başına Auth'u açmaz.

`POST /internal/q5/bootstrap` body `operationId,restaurantId,generation,nonce,entries[{sessionId,userId,denyUntil}]`; scope `staff:bootstrap:write`, tam baseline digest'i ve nonce. Yanıt imzalı `staff:bootstrap:ack` receipt'idir. Auth kendi kalıcı baseline digest'ini de kontrol etmeden bariyeri açmaz.

İç yollar gateway route envanterinde yoktur. QR private portunu public ingress'e açmayın. Browser cookie/Origin ve staff JWT internal credential değildir. Production HTTPS/JVM trust yapılandırması zorunludur.

## Auth entegrasyon envanteri

| Mevcut yol | Q5 sınırı |
| --- | --- |
| `StaffSessionService.logout` | Transaction dışı koordinatör; kalıcı, idempotent PREPARE; ACK; ayrı FINALIZE. |
| `login`, `refresh`, `completeMfa`, private `grant` | Kaynak bariyeri + aynı kullanıcı kilidi + pending kontrolü. |
| Geçersiz refresh/replay/session ve inactive hesap tespiti | Doğrudan session/refresh revoke yerine kalıcı AUTO PREPARE; worker koordinasyonu. |
| `deactivate`, `revokeAllSessions` | Hesap/oturum değişikliği QR ACK gelmeden commit edilmez. Mevcut servis sözleşmesidir; yeni endpoint eklenmedi. |
| `revokeOtherSessions` / MFA regenerate | Aktör SID korunur, diğer SID'ler dondurulur; worker iş etkisini tamamlayamaz. |
| `PasswordResetService.request/verify/confirm` | Yeni proof üretimi de pending/source bariyerine uyar; confirm son etkisi FINALIZE ile aynı transaction'dadır. |
| Rol kaldırma | Revocation action ROLE + bütün eski SID'lerin iptali hazırdır; dev'de olmayan rol yönetimi endpoint'i eklenmedi. |

Auth dışından doğrudan kullanıcı/rol/session SQL değişikliği yapıp bu protokolün garanti sağladığını varsaymayın. Yeni güvenlik yazıcıları aynı sınırları kullanmalıdır.

## Operasyonel durum

`staff_revocation_operations`: PREPARED → ACKNOWLEDGED → COMPLETED. `business_outcome`: PENDING/APPLIED/EXPIRED; AUTO için null. Beş dakikalık iş resume süresi sonunda yalnız doğrulanmış fence iptali tamamlanabilir; password/code etkisi uygulanmış sayılmaz. Kaynak veya QR kapalıysa güvenlik nedeniyle süre dolması fail-open yapmaz.

`attempts` worker claim sayısını, `next_attempt_at` durable yeniden-deneme zamanını tutar. Beklenmeyen kullanıcı version değişikliği fail-closed inceleme gerektirir. SQL ile pending kaydını silerek veya guard'ı zorla ready yaparak müdahale etmeyin.

Yeni gizli ayarların tamamı boş olarak `.env.example` içindedir; gerçek `.env` değiştirilmez. Anahtarlar test dışında üretilmemiştir. `AUTH_Q5_QR_URL` yalnız origin olmalıdır, path/query/userinfo kabul edilmez. Mevcut staff signing/CSRF sırları QR'a kopyalanmaz.

Q5 command private/public çifti Auth başlangıcında; receipt private/public çifti QR başlangıcında eşleştirilir. Mevcut `AUTH_Q5_COMMAND_PUBLIC_KEY` artık Auth'a da, mevcut `AUTH_Q5_QR_PUBLIC_KEY` QR'a da verilir; yeni ENV adı eklenmez. QR ayrıca staff verification key, ayrı trust anahtarları, restoran, issuer, TTL ve trusted origin listesini başlangıçta doğrular. Anahtarlar yalnız bir kez parse edilir. Yanlış peer dağıtımının tamamı yerel doğrulamayla kanıtlanamaz; gerçek komut/ACK imzaları hâlâ zorunludur ve eşleşmezse FINALIZE yoktur.

Desteklenen restore işlemi [Q5 restore runbook](q5-restore-runbook.md) sırasını zorunlu uygular. Bütün Auth/QR süreçleri ve personel ingress'i durdurulur; restore sonrasında bakım SQL'i yeni nonce ile guard'ı kapatır. Güncel tam baseline + Auth finalizasyonu olmadan trafik açılmaz. `ready=false` tek başına ve habersiz DB rollback'i desteklenen kurtarma değildir.
