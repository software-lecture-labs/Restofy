# Q5 uygulama ve doğrulama durumu

Tarih: 5 Kasım 2026. Güncel kod, seçilen C protokolünü uygular. Son test/inceleme sonuçları bu dosyada kayıtlıdır; GitHub'da PR veya ADR değiştirilmemiştir.

## Git izolasyonu

- Worktree: `C:\Users\gsker\Desktop\JAVA PROJESİ\qr-management-system-q5`.
- Branch: `feature/q5-access-revocation`.
- HEAD/taban: `94f80e5069625630e5be129235ac7c702ffd1ba4`. Son fetch'te origin/dev aynı commit'tir.
- Commit, push, merge, cherry-pick, yeni worktree veya PR oluşturulmadı.
- Orijinal Auth branch'i: `feature/auth-service`, HEAD `0356bb530cd37c83a2142fc8b5fe44004e8ee99c`.
- Orijinal tracked diff hash'i başlangıçta ve yeniden kontrolde aynı: `52ddf15f2515c8a1837d6cf41624d20ad6f78f65`. Staged diff boş kaldı; iki mevcut untracked keygen yolu yerinde. Untracked dosyalar için başlangıçta ayrı içerik hash'i alınmadığından daha güçlü bir byte karşılaştırması iddia edilmiyor.
- Dev'de bulunmayan `readSession/readIdentity`, personel yönetimi endpoint'leri, frontend veya Mail özellikleri taşınmadı. PR #17 ADR'si dev'de bulunmadığından yerel bir [Q5 ADR önerisi](../decisions/ADR-AUTH-Q5-coordinated-revocation.md) eklendi.

## Mevcut mekanizma

Auth, PREPARE'da kaynak bariyeri ve kullanıcı kilidi altında immutable SID manifesti oluşturur. Aynı kilit/gate login, refresh, MFA ve reset proof üretimini korur. Transaction dışındaki koordinatör QR fence'ini ister; gerçek QR imzasına ve tam manifest'e bağlı ACK olmadan FINALIZE çalışmaz.

Logout dış transaction varken reddedilir: `Propagation.NEVER` kullanır; HTTP altında askıya alınmış transaction/kilit bırakılmaz. DB transaction'ları ayrı bean/proxy sınırlarındadır. Client ayrıca açık transaction'da transport çağrısını reddeder. HTTP client bean ömrü boyunca paylaşılır ve kapanışta kapatılır.

Mevcut geçersiz refresh/replay/session, deactivate ve toplu iptal yolları doğrudan revoke yazmak yerine protokolü kullanır. Rol kaldırma için ROLE finalizasyonu ve bütün eski session'ları iptal eden servis bileşeni vardır; dev'de olmayan rol yönetimi endpoint'i varmış gibi raporlanmaz.

Reset/MFA: proof/factor son etkisi PREPARE'da tüketilmez. Worker yalnız fence + ACK hazırlar; aynı amaç ve geçerli kanıtla retry'da iş etkisi FINALIZE ile aynı transaction'da commit edilir. Son etkideki hata bütün transaction'ı rollback eder. Beş dakikalık resume süresi sonunda, ancak ACK varsa, SID invalidation tamamlanır ve `business_outcome=EXPIRED` olur; password/recovery-code değişikliği yapılmış sayılmaz.

QR'da exact 17 personel method/path envanteri, yerel JWT/claim, RBAC, cookie/Origin/CSRF tüketimi ve primary PostgreSQL guard ile korunur. Bütün instance'lar aynı negatif durumu okur. Normal istekte Auth HTTP yoktur. Guest ve Waiter delegasyon sınırları default deny kalır; ayrı entegrasyonları açılmaz.

Internal challenge/bootstrap/revocations yollarında body deserialize edilmeden dedicated servis kimliği doğrulanır; controller tam body/digest/nonce bağını tekrar denetler. Receipt yalnız store commit'inden sonra imzalanır ve DTO log çıktısı maskelidir. Public gateway bu üç yola route vermez.

## Bootstrap ve eski token geçişi

İki servis CLOSED/unready başlar. Auth credential üretimini kalıcı bariyerle durdurup bütün mevcut SID'leri baseline'a alır. QR güncel DB nonce'u ve tam digest'e bağlı komutla fence'leri kalıcılaştırır; Auth ancak kendi sakladığı baseline için imzalı ACK ile bariyeri açar. Aynı tamamlanmış nonce'un gecikmiş worker retry'ı yeni session'ları tekrar iptal etmez.

Primary, `fsync=on` ve Q5 yazılarında `synchronous_commit=on` zorunludur. Replica/DB/guard/readiness hatasında erişim açılmaz. PostgreSQL başlangıç zamanı değişirse eski readiness geçersizdir. Restore'da karantina ve doğrulanmış yeniden bootstrap prosedürü ADR'de zorunludur; rastgele canlı VM/DB geçmişi geri sarılması için koşulsuz garanti iddia edilmez.

JWT TTL artırılmadı. Expiry idle ve absolute sınırla kısaltılır. `auth_policy=q5-idle-v1`, eski idle politikasındaki token'ları dışlar. İlk geçiş ve DB rebootstrap mevcut personelin yeniden girişini gerektirir.

## Migration ve configuration

- Auth: `V7__coordinate_staff_revocation.sql` — per-user revision, operation/manifest, source barrier ve tam baseline.
- QR: `V122__persist_staff_revocation_guard.sql` — kalıcı SID fence, receipt, readiness/incarnation nonce.
- Eski uygulanmış SQL değiştirilmedi. V7/V122 yalnız bu yeni Q5 migration'larıdır; testte geçici DB'lere uygulanmıştır.
- Yeni dedicated service trust ayarları: `AUTH_Q5_COMMAND_PRIVATE_KEY`, `AUTH_Q5_COMMAND_PUBLIC_KEY`, `QR_Q5_RECEIPT_PRIVATE_KEY`, `AUTH_Q5_QR_PUBLIC_KEY`, `AUTH_Q5_QR_GENERATION`, `AUTH_Q5_QR_URL`.
- Staff public key mevcut `MAIL_AUTH_PUBLIC_KEY` materyalidir; Auth signing/CSRF sırrı QR'a aktarılmaz.
- Gerçek ENV dosyası, deployment secret'ı veya anahtar dosyası üretilmedi/değiştirilmedi. Kriptografik test anahtarları yalnız fixture belleğindedir.
- Production HTTPS ister; dev HTTP istisnası hem profile hem APP_ENV gelişim ortamını doğrular ve yalnız loopback/qr-service içindir. Private TLS/bootstrap erişimi deployment önkoşuludur.

## Güvenlik garantisi ve kalan sınırlar

FINALIZE commit'inden sonra başlayan yeni QR kontrolü, manifestteki eski SID için kalıcı fence'i görür veya doğrulama kaynağı kullanılamadığından 503 ile kapanır. Daha önce başlamış işlerin geriye dönük iptali vaat edilmez.

QR kesintisinde PREPARE başarı değildir; fence gelmeden eski QR erişimi sürebilir. Fence kurulduğunda erişim kapanır; ACK/FINALIZE hatasında fence geri alınmaz. Güncel durum source transaction, QR PostgreSQL/HTTP ve gerçek ağ testleriyle ayrı ayrı sınanır.

Kalan operasyonel koşullar:

- Key provisioning ve production TLS uygulanmadan deployment hazır sayılmaz; bu görev deployment yapmaz.
- Restore karantina protokolü ve primary DB varsayımları zorunludur. Güvenlik verisini elle silip ready yapmak desteklenmez.
- Baseline en çok 100.000 SID kabul eder. Aşımda truncation/fail-open yoktur; pagination/retention ayrı kanıt gerektirir. Tombstone otomatik silinmez.
- Beklenmeyen user version drift'inde pending işlem otomatik tahminle tamamlanmaz; inceleme gerekir. Minimal tanılama operation/status/attempts ve readiness'tir; genel monitoring/event platformu eklenmedi.
- Yeni tüketici servis bu sabit QR protokolüne kendiliğinden katılamaz; üyelik bariyeri ve bütün zorunlu tüketici ACK'leri ayrıca tasarlanmalıdır.
- Online key rotation, yeni personel endpoint'i, frontend pending UX ve guest/Waiter delegasyonu kapsam dışıdır.
- Auth feature branch'i gelecekte entegre edilirken V5/V6 ile V7'nin forward-only migration sırası ayrıca incelenmelidir.

## Otomatik doğrulamalar

| Katman | Doğrulama |
| --- | --- |
| Auth/PostgreSQL | Orijinal logout/ACK kabul testi; kalıcı PREPARE; fake ACK reddi; idempotency; iki eşzamanlı operation; login/refresh/MFA pending gate; ADMIN rolünün kaldırılması; toplu session/refresh iptali; FINALIZE failure; business rollback ve EXPIRED sonucu; tam baseline binding; stale bootstrap duplicate; ambient transaction reddi; prod ENV'de HTTP istisnası reddi. |
| QR/PostgreSQL + gerçek security chain | Commit sonrası imza, duplicate/altered manifest, out-of-order eklemeli fence, rollback, eksik DB guard, eski incarnation/nonce, wrong scope/issuer, JWT/role/tenant/legacy policy, CSRF, cookie belirsizliği; 17 personel method/path'te eski SID reddi. |
| Gateway | Q5 internal POST'ları 404; hiçbir downstream'e iletilmiyor. |
| Gerçek JVM/ağ | Ayrı Auth ve iki QR süreci; ayrı geçici Auth/QR DB'leri; cold bootstrap; Auth portu gerçekten kapalıyken yerel QR; QR outage; PREPARE crash recovery; commit edilmiş ACK'in yolda kaybı; aynı operation retry; DB outage/stop/start ve rebootstrap; DB trigger ile zorlanan FINALIZE failure sonrası fence/ACK koruması. |

Auth-only legacy regresyon fixture'ları peer imzası üretir; bunlar QR commit kanıtı olarak sayılmaz. O kanıt ayrı gerçek QR HTTP/DB ve JVM ağ testinden gelir. Hiçbir güvenlik assertion'ı kaldırılmadı veya test devre dışı bırakılmadı.

Testcontainers'ın varsayılan fsync=off başlangıcı kabul edilmedi. Test-only `q5postgresql` provider'ı fsync=on kullanır; approved JDBC URL/profile/driver ve gerçek DB'yi reddeden guard testleri korunur. Production jar'a test provider eklenmez.

## Komutlar ve sonuçlar

```powershell
mvn -o '-Dmaven.repo.local=C:\Users\gsker\.m2\repository' -f backend/services/auth-service/pom.xml verify -q
mvn -o '-Dmaven.repo.local=C:\Users\gsker\.m2\repository' -f backend/services/qr-service/pom.xml verify -q
mvn -o '-Dmaven.repo.local=C:\Users\gsker\.m2\repository' -f backend/pom.xml -pl services/qr-service,services/api-gateway verify -q
mvn -o '-Dmaven.repo.local=C:\Users\gsker\.m2\repository' -f backend/pom.xml -pl services/auth-service,services/qr-service '-Dtest=RevocationTransactionsTests,RevocationInternalHttpTests' test -q
node --check ops/tests/q5-network.mjs
node ops/tests/q5-network.mjs
git diff --check
```

Son P1 kaynak koduyla tam Java kontrolleri: Auth 304, QR 606, gateway 48 test; failure/error/skip yok. `q5-p1-auth-verify.log` ve `q5-p1-qr-gateway-verify.log` exit 0'dır. Maven verify paketleme ve mevcut ilgili kontrolleri çalıştırır. Bütün 13 servis reactor'ü veya deployment smoke testi çalıştırılmış gibi sunulmaz.

Önceki aşamada üretim kodu değişmeden eklenen signed-negative assertion'ları ilgili 18 testle geçti: doğru QR anahtarıyla imzalı ama yanlış digest/scope ACK'i ve yanlış audience/staff-key servis credential'ı reddedildi. Bu tarihsel dar kontrolün logu `q5-signed-negative-cases.log` dosyasıdır; son P1 tam kontrolleri de bu assertion'ları içerir.

Son artifact'larla gerçek ağ kabulü ardışık iki kez geçti: her koşuda 13 PASS ve final başarı satırı, toplam komut exit 0. Auth'un gerçekten kapalı portu, iki gerçek QR instance'ı, Auth process crash'leri, kayıp ACK, gerçek DB outage/restart/rebootstrap, trigger ile zorlanan FINALIZE failure ve aynı PostgreSQL sürecindeki gerçek mantıksal restore doğrulandı. Test aracı jar tazeliğini denetler, uygulama/JVM ENV override'larını miras almaz, yalnız yerel Docker fixture'ı kullanır. Test DB'leri explicit sabittir; gerçek ENV dosyası okunmaz/yazılmaz. Cleanup yalnız kendi UUID label'ını doğruladığı container/volume ve kendi JVM'lerine uygulanır.

Ara başarısızlıklar gizlenmedi: eski doğrudan logout testi önce kırmızıydı; yeni migration beklentileri ve fixture FK cleanup'ı uyarlandı; vendor fsync=off varsayımı kaldırıldı. Ağ testindeki --rm restart hatası, Windows Java launcher alt-process temizliği ve redundant Hikari JDBC URL binding'i düzeltildi. Launcher nedeniyle eski “Auth kapalı” sonucu geçersiz sayılıp gerçek JDK executable ve kapalı port kontrolüyle yeniden doğrulandı.

Güncel loglar Git dışı `logs/` altındadır: `q5-p1-auth-verify.log`, `q5-p1-qr-gateway-verify.log`, `q5-p1-network-1.log`, `q5-p1-network-2.log`. Önceki `q5-auth-final-reviewed.log`, `q5-qr-final-reviewed.log`, `q5-qr-gateway-final-verify.log`, `q5-network-final-result.log` ve başarısız ara denemeler korunur; son kaynak kanıtıyla karıştırılmaz.

## Son P1 düzeltmeleri — F1–F4

- **F1:** Aynı postmaster içinde `ready=false` eski nonce'u değiştirmediği gerçek PostgreSQL assertion'ıyla doğrulandı (`logs/q5-p1-restore-red-confirmed.log`). Desteklenen restore bütün Auth/QR süreçleri ve personel ingress'i kapalıyken `ops/qr/q5-restore-quarantine.sql` çalıştırır. Yeni nonce eski baseline'ı geçersiz kılar; güncel tam baseline/ACK/Auth FINALIZE tamamlanmadan trafik açılmaz. [Somut bakım prosedürü](q5-restore-runbook.md). Habersiz rollback ve uzlaştırılmamış Auth restore'u desteklenmez.
- **F2:** Ağ fixture'ı geçici Unix socket yerine nihai PostgreSQL TCP erişimini ve iki test DB'sini bekler. Sınırlı bekleme ve maskelenmiş FIXTURE/ACCEPTANCE hata sınıfları kullanılır; cleanup yalnız kendi label/UUID kaynaklarına aittir. Önceki başarısız loglar korunur.
- **F3:** Auth command private/public çifti ve QR receipt private/public çifti başlangıçta eşleştirilir. QR ayrıca staff/servis anahtar ayrımı, restaurant/issuer/TTL/origin doğrular. Anahtarlar immutable ayarlarda tekrar kullanılır. Yeni ENV adı yoktur: mevcut command public key Auth'a, receipt public key QR'a da Compose ile aktarılır. Yerel çift uyumu yanlış peer dağıtımının yerine geçmez; komut/ACK imza kontrolü devam eder.
- **F4:** `mfaMintLocksFirstAndPrepareIncludesItsSessionBeforeClosingIssuance` ve `prepareLocksFirstAndMfaCannotCommitCredentialsPastPendingBarrier`, gerçek PostgreSQL transaction'ları ve `pg_blocking_pids` ile iki kilit sırasını kanıtlar. MFA mevcut pending SID'yi upgrade eder; yeni SID açtığı iddia edilmez. PREPARE önceyse refresh credential oluşmaz, session MFA_PENDING kalır ve 503 döner.

Fail-fast negatif testleri Auth'ta 12, QR'da 25 testtir; iki MFA yarışı Auth'ın 15 revocation testine dahildir. QR restore regresyonu 6 store testinin içindedir. Son tam toplamlar yukarıda kayıtlıdır.

Gerçek ağ testi artık aynı PostgreSQL sürecinde `pg_dump`/`pg_restore` içerir. İzole negatif kontrol karantinasız restore açığını yeniden üretir; sonra bakım SQL'i, kapalı readiness, iki QR instance'ı, yeni Auth baseline kimliği ve FINALIZE edilmiş eski SID'nin 401'i doğrulanır. Bu negatif kontrol production bakım adımı değildir. P1 koşuları on üç PASS aşaması içerir.

İlk restore proof denemesi Docker kapalıyken assertion'a ulaşmadı. İlk fixture ayrıca nonce'u kaydettikten sonra değiştiriyordu; test düzeneği düzeltildi ve asıl kırmızı assertion ayrı logla doğrulandı. Bu ara sonuçlar güvenlik kanıtı sayılmaz ve logları silinmedi.

İki gerçek Auth worker ve reset/MFA'nın gerçek ağ E2E'si bu tur eklenmedi. Yerel reset/MFA regresyonları ve zorunlu kilit testleri geçmiştir; kapsanmayan E2E tamamlanmış gösterilmez. Yeni dependency/Flyway migration/ENV adı, broker veya endpoint eklenmedi. Yeni bakım SQL'i migration değildir; uygulama başlangıcında otomatik çalışmaz.

## Önceki karşıt inceleme

Saboteur, yeni ekip üyesi ve güvenlik denetçisi bakışlarıyla protokol yeniden okundu. Düzeltilen somut bulgular: gecikmiş aynı-nonce bootstrap'ın yeni session'ları tekrar kapatması; her mesajda yeni HTTP client kaynağı açılması; dev profile/prod APP_ENV karışımında plaintext istisnası; NOT_SUPPORTED ile askıya alınmış dış transaction altında HTTP riski. Bunlar idempotency, kaynak ömrü, transport ve NEVER transaction sınırıyla kapatıldı. Kalan notlar yukarıdaki deployment/restore/retention varsayımlarıdır; koşulsuz bir güvenlik iddiasına gizlenmez.

## Değişen dosyalar

Dev tabanına göre 67 dosya: 34 tracked değişiklik ve 33 untracked; staged dosya yoktur. Önceki 59 dosya korunur. Aşağıdaki liste kaynak/test/configuration/belgelerini birlikte kapsar; generated target/log dosyaları dahil değildir.

- `.env.example`
- `backend/services/api-gateway/src/test/java/com/restofy/gateway/ApiGatewayRoutingTests.java`
- `backend/services/auth-service/src/main/java/com/restofy/auth/application/service/MfaVerificationService.java`
- `backend/services/auth-service/src/main/java/com/restofy/auth/application/service/PasswordResetService.java`
- `backend/services/auth-service/src/main/java/com/restofy/auth/application/service/StaffSessionService.java`
- `backend/services/auth-service/src/main/java/com/restofy/auth/application/service/revocation/RevocationBootstrapTransactions.java`
- `backend/services/auth-service/src/main/java/com/restofy/auth/application/service/revocation/RevocationCoordinator.java`
- `backend/services/auth-service/src/main/java/com/restofy/auth/application/service/revocation/RevocationPendingException.java`
- `backend/services/auth-service/src/main/java/com/restofy/auth/application/service/revocation/RevocationReadiness.java`
- `backend/services/auth-service/src/main/java/com/restofy/auth/application/service/revocation/RevocationRetryWorker.java`
- `backend/services/auth-service/src/main/java/com/restofy/auth/application/service/revocation/RevocationTransactions.java`
- `backend/services/auth-service/src/main/java/com/restofy/auth/infrastructure/client/QrRevocationClient.java`
- `backend/services/auth-service/src/main/java/com/restofy/auth/infrastructure/security/AuthRequestFilter.java`
- `backend/services/auth-service/src/main/java/com/restofy/auth/infrastructure/security/MfaAttemptLimiter.java`
- `backend/services/auth-service/src/main/java/com/restofy/auth/infrastructure/security/QrRevocationReceipts.java`
- `backend/services/auth-service/src/main/java/com/restofy/auth/infrastructure/security/StaffJwtTokens.java`
- `backend/services/auth-service/src/main/resources/application.properties`
- `backend/services/auth-service/src/main/resources/db/migration/V7__coordinate_staff_revocation.sql`
- `backend/services/auth-service/src/test/java/com/restofy/auth/api/StaffAuthHttpTests.java`
- `backend/services/auth-service/src/test/java/com/restofy/auth/api/StaffMfaHttpTests.java`
- `backend/services/auth-service/src/test/java/com/restofy/auth/application/service/MfaIntegrationTests.java`
- `backend/services/auth-service/src/test/java/com/restofy/auth/application/service/PasswordResetIntegrationTests.java`
- `backend/services/auth-service/src/test/java/com/restofy/auth/application/service/RevocationTransactionsTests.java`
- `backend/services/auth-service/src/test/java/com/restofy/auth/application/service/StaffSessionIntegrationTests.java`
- `backend/services/auth-service/src/test/java/com/restofy/auth/infrastructure/persistence/StaffPersistenceTests.java`
- `backend/services/auth-service/src/test/java/com/restofy/auth/infrastructure/client/QrRevocationClientSettingsTests.java`
- `backend/services/auth-service/src/test/java/com/restofy/auth/support/AuthSessionTestSupport.java`
- `backend/services/auth-service/src/test/java/com/restofy/auth/support/AuthTestDatabaseInitializer.java`
- `backend/services/auth-service/src/test/java/com/restofy/auth/support/AuthTestDatabaseInitializerTests.java`
- `backend/services/auth-service/src/test/java/com/restofy/auth/support/DurablePostgreSQLProvider.java`
- `backend/services/auth-service/src/test/resources/META-INF/services/org.testcontainers.containers.JdbcDatabaseContainerProvider`
- `backend/services/qr-service/src/main/java/com/restofy/qr/infrastructure/security/SessionLifecycleAccess.java`
- `backend/services/qr-service/src/main/java/com/restofy/qr/infrastructure/security/Q5SecuritySettings.java`
- `backend/services/qr-service/src/main/java/com/restofy/qr/infrastructure/security/StaffRequestFilter.java`
- `backend/services/qr-service/src/main/java/com/restofy/qr/infrastructure/security/TableManagementAccess.java`
- `backend/services/qr-service/src/main/java/com/restofy/qr/infrastructure/security/TableSecurityConfiguration.java`
- `backend/services/qr-service/src/main/java/com/restofy/qr/infrastructure/security/revocation/RevocationInternalController.java`
- `backend/services/qr-service/src/main/java/com/restofy/qr/infrastructure/security/revocation/RevocationReadiness.java`
- `backend/services/qr-service/src/main/java/com/restofy/qr/infrastructure/security/revocation/RevocationServiceCredentials.java`
- `backend/services/qr-service/src/main/java/com/restofy/qr/infrastructure/security/revocation/StaffRevocationStore.java`
- `backend/services/qr-service/src/main/resources/application.properties`
- `backend/services/qr-service/src/main/resources/db/migration/V122__persist_staff_revocation_guard.sql`
- `backend/services/qr-service/src/test/java/com/restofy/qr/QrServiceApplicationTests.java`
- `backend/services/qr-service/src/test/java/com/restofy/qr/infrastructure/GuestSessionPostgresIntegrationTests.java`
- `backend/services/qr-service/src/test/java/com/restofy/qr/infrastructure/QrCodePostgresIntegrationTests.java`
- `backend/services/qr-service/src/test/java/com/restofy/qr/infrastructure/QrLifecyclePostgresIntegrationTests.java`
- `backend/services/qr-service/src/test/java/com/restofy/qr/infrastructure/QrRotationPostgresIntegrationTests.java`
- `backend/services/qr-service/src/test/java/com/restofy/qr/infrastructure/QrReadPostgresIntegrationTests.java`
- `backend/services/qr-service/src/test/java/com/restofy/qr/infrastructure/Q5SecuritySettingsTests.java`
- `backend/services/qr-service/src/test/java/com/restofy/qr/infrastructure/QrValidationPostgresIntegrationTests.java`
- `backend/services/qr-service/src/test/java/com/restofy/qr/infrastructure/RevocationInternalHttpTests.java`
- `backend/services/qr-service/src/test/java/com/restofy/qr/infrastructure/ScanStatisticsPostgresIntegrationTests.java`
- `backend/services/qr-service/src/test/java/com/restofy/qr/infrastructure/StaffRevocationStoreTests.java`
- `backend/services/qr-service/src/test/java/com/restofy/qr/infrastructure/TablePostgresIntegrationTests.java`
- `backend/services/qr-service/src/test/java/com/restofy/qr/infrastructure/TableSessionPostgresIntegrationTests.java`
- `backend/services/qr-service/src/test/java/com/restofy/qr/infrastructure/TransitionIntegrationSupport.java`
- `backend/services/qr-service/src/test/java/com/restofy/qr/support/DurablePostgreSQLProvider.java`
- `backend/services/qr-service/src/test/java/com/restofy/qr/support/Q5TestSecuritySupport.java`
- `backend/services/qr-service/src/test/resources/META-INF/services/org.testcontainers.containers.JdbcDatabaseContainerProvider`
- `docker-compose.yml`
- `docs/auth-service/q5-contract.md`
- `docs/auth-service/q5-implementation-status.md`
- `docs/auth-service/q5-pr-review.md`
- `docs/auth-service/q5-restore-runbook.md`
- `docs/decisions/ADR-AUTH-Q5-coordinated-revocation.md`
- `ops/tests/q5-network.mjs`
- `ops/qr/q5-restore-quarantine.sql`

## Tek PR hazırlığı

[Hazır PR taslağı ve checklist](q5-pr-review.md), [wire/entegrasyon sözleşmesi](q5-contract.md) ve [ADR önerisi](../decisions/ADR-AUTH-Q5-coordinated-revocation.md) yereldedir. Alper'in özellikle pending davranışı, reset/MFA retry, cold/restore re-login, dedicated trust/TLS, kapasite/retention ve migration sırasını incelemesi gerekir. Kodlar commit edilmemiştir; PR açılması ve ADR'nin kabulü kullanıcı/ekip onayındadır.

P1 düzeltmeleri sonrası Draft PR teknik olarak hazırdır. Merge, ekip review'u ve servis/restore sözleşmesinin kabulüne bağlıdır. Production hazır değildir: gerçek key provisioning/TLS, deployment restore tatbikatı, minimum izleme ve operasyon sahipliği doğrulanmadı. Commit/push/merge/PR işlemi yapılmadı; orijinal Auth branch'i korunur.

## Son yerel doğrulama — P2 bulgularının hedefli giderilmesi

Bu bölüm önceki test/işlem durumlarının güncel ekidir. Taban `94f80e5`, değişikliklerin henüz commit edilmediği Q5 HEAD `805b77c` olarak kaldı. PR #18 Draft ve uzak branch değişmedi. Bu turda commit, push veya PR güncellemesi yapılmadı.

- P2-1: Auth source kimliği restoran/generation ile aynı kaynak satırı kilidi altında eşleştiriliyor. Yanlış instance readiness, credential üretimi, PREPARE ve worker claim/teslim yolundan geçemiyor; bağlı source identity yeni bootstrap ile değiştirilemiyor.
- İlk iki negatif regresyon testi düzeltme öncesi gerçekten kırmızıydı (`q5-p2-red.log`: 2 failure). Düzeltme sonrası mevcut yarışlar dahil `RevocationTransactionsTests`: 18 geçti. Test fixture'ının rastgele restoran/generation'ı gerçek test source kimliğiyle eşleştirildi; güvenlik assertion'ları kaldırılmadı.
- Normal receipt kabul üst sınırı 60'tan 30 saniyeye indirildi. Uzun receipt'in PREPARED işlemi ACK/FINALIZE edemediği test edildi.
- Worker/coordinator sabit aşama/kategori ve operation UUID'si logluyor; exception mesajı/cause veya credential loglanmıyor. Hatalı kimlikte peer/claim çağrısı yapılmadığı ve hassas exception metninin loga çıkmadığı ayrı testle doğrulandı.
- P2-2 için otomatik fingerprint wire/manifest değişikliği yapılmadı. Yanlış staff key ile gerçek Auth JWT'sinin 401, doğru key ile 200 aldığı ağ regresyonu geçti. Readiness tek başına peer provisioning kanıtı değildir; [zorunlu deployment smoke ve geçmiş PREPARED kayıt için manuel kurtarma önerisi](q5-p2-recovery-and-peer-smoke.md) uygulanabilirlik/ekip incelemesi için yazıldı. Kurtarma önerisi gerçek veride uygulanmadı ve ayrı bir repair E2E testi yapılmadı.

Son komutlar:

```powershell
mvn -o '-Dmaven.repo.local=C:\Users\gsker\.m2\repository' -f backend/services/auth-service/pom.xml verify -q
mvn -o '-Dmaven.repo.local=C:\Users\gsker\.m2\repository' -f backend/pom.xml -pl services/qr-service,services/api-gateway verify -q
node --check ops/tests/q5-network.mjs
node ops/tests/q5-network.mjs
git -c core.safecrlf=false diff --check
```

Sonuç: Auth **308**, QR **606**, Gateway **48** test; failure/error/skip **0/0/0**, iki Maven verify exit **0**. Gerçek ağ kabulü **15 aşama**, exit **0**: aynı primary DB'de doğru/yanlış yapılandırılmış gerçek Auth instance'ları, yanlış peer key, normal Auth bağımsızlığı ve önceki 13 aşamalı outage/ACK/crash/restore akışı. Loglar Git dışı `logs/q5-p2-auth-verify.log`, `q5-p2-qr-gateway-verify.log`, `q5-p2-network.log` içindedir.

Dev `94f80e5` CI 41339093125 ve mevcut PR CI 42091225200, aynı Order `orders`/`carts` eksik tablo hatalarını içeriyor; Order/ortak test altyapısı değiştirilmedi. Yeni yerel kaynak için GitHub CI çalıştırılmadı. Mevcut CI kırmızısı ortadan kalkmış gibi raporlanmaz. Production hazırlığı ve peer smoke/kurtarma prosedürlerinin ekip kabulü tamamlanmış sayılmaz.
