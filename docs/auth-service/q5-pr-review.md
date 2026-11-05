# Tek Q5 PR taslağı

Hedef: `feature/q5-access-revocation` → `dev`. Henüz commit/push/PR yoktur.

Başlık: `feat(auth,qr): coordinate durable staff access revocation`

## Açıklama

Eski logout/session iptali Auth'ta commit edildiğinde QR süresi dolmamış JWT'yi kabul edebiliyordu. Bu değişiklik, önce QR primary PostgreSQL'e kalıcı SID fence'i kurup doğrulanmış ACK aldıktan sonra Auth FINALIZE çalıştırır. QR normal personel isteklerini yerel JWT, RBAC ve kendi DB guard'ıyla doğrular; her istekte Auth HTTP çağrısı yapmaz.

PREPARE credential üretimini aynı kullanıcı kilidiyle durdurur. Durable retry/idempotency kayıp ACK ve crash sonrası devam eder. Reset/MFA iş etkileri worker tarafından erken tamamlanmaz; doğrulanmış retry'da FINALIZE ile atomik commit edilir. Fail-closed bootstrap ve readiness, eski DB/boş deny listesiyle erişim açılmasını engeller. Idle sınırı JWT exp'e uygulanır; eski politika token'ları reddedilir.

Kapsam: Auth/QR Q5 bileşenleri, iki yeni migration, minimum ENV/Compose wiring, ilgili gateway güvenlik testi ve belgeler. Auth feature branch'i, frontend, Mail ve yeni personel yönetimi endpoint'leri taşınmadı.

## Review kararları

- FINALIZE sonrası başlayan yeni kontroller için kesin SID reddi; daha önce başlayan işlerin iptal edilmemesi.
- QR kesintisinde tamamlanamayan iptal ve pending süredeki erişim/credential davranışı.
- Reset/MFA 503 → proof ile retry sözleşmesi ve EXPIRED iş sonucu.
- Cold bootstrap/DB restart'ta bütün mevcut personelin yeniden giriş yapması.
- Dedicated Q5 command/receipt anahtar çiftleri, private TLS/bootstrap erişimi ve key provisioning sorumluluğu.
- DB primary/fsync/durability, restore karantinası ve positive cache kullanılmaması.
- 100.000 SID baseline sınırı, otomatik tombstone retention olmaması ve kullanıcı version drift'inde fail-closed inceleme.
- V7/V122 ve gelecekte Auth feature branch'i entegre edilirken forward-only migration sırası.

## Test checklist

- [x] Orijinal logout kabul testi korundu; ACK olmadan FINALIZE reddediliyor.
- [x] Auth transaction/race, reset/MFA ve DB güvenlik testleri.
- [x] QR JWT/RBAC/CSRF ve 17 method/path'te guard reddi.
- [x] Internal servis kimliği ve commit sonrası receipt.
- [x] Eski nonce ile restore/bootstrap reddi; source tam baseline binding.
- [x] F1: `ready=false` karşı örneği korundu; restore sonrası bakım SQL'i yeni nonce üretir. Desteklenen prosedür [restore runbook](q5-restore-runbook.md) içindedir; habersiz rollback garantisi yoktur.
- [x] F2: Nihai PostgreSQL TCP/DB hazırlığı beklenir; aynı artifact'larla ardışık iki gerçek ağ koşusu 13 aşamayla geçti. Fixture ve güvenlik assertion hataları ayrılır.
- [x] F3: Anahtarlar ve kritik ayarlar başlangıçta doğrulanır; yerel public/private çift uyumu ve immutable anahtar kullanımı test edilir.
- [x] F4: MFA mint/PREPARE kilidinin iki sırası gerçek PostgreSQL blocking PID gözlemiyle doğrulanır.
- [x] Gateway internal Q5 POST'larını hiçbir downstream'e iletmiyor.
- [x] Test PostgreSQL'i fsync=on; production güvenlik şartı test için kapatılmadı.
- [x] Son artifact'larla gerçek ağ/crash/ACK kaybı/DB restart ve FINALIZE failure kabul testi.
- [x] Diff/secret/Git koruma kontrolü; orijinal Auth HEAD/diff parmak izi korundu, gerçek secret dosyası değişmedi.

Çalıştırılan komutlar, güncel sayılar ve başarısız ara denemeler [durum raporunda](q5-implementation-status.md) tutulur. Bu taslak deployment veya ADR onayı değildir.
