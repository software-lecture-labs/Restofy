# Q5 — Source identity kurtarma ve peer anahtar smoke kontrolü

## Kalıcı kaynak kimliği

İlk güvenilir bootstrap, Auth source kaydını restoran ve QR generation kimliğine bağlar. Sonraki instance'lar bu kimliği yeniden seçemez. ACTIVE kontrolü, credential üretimi, PREPARE, ACK/FINALIZE ve worker kaynak kimliğiyle uyumlu olmalıdır. Kaynak satırının SHARE kilidi iş transaction'ının sonuna kadar; bootstrap'ın UPDATE kilidi yalnız kendi kısa transaction'ında tutulur. HTTP sırasında bu kilitler tutulmaz.

Yanlış instance readiness 503 verir. Yerel yapılandırmayı kalıcı kimlikle eşitleyip instance'ı yeniden başlatmak normal düzeltmedir. Generation değiştirerek yeni deployment kimliği seçmek veya `source_state` satırını elle ACTIVE yapmak bir kurtarma yolu değildir. Boş bir veritabanında ilk kimliğin doğru dağıtılması hâlâ deployment sorumluluğudur.

## Önceki sürümün bıraktığı yanlış-generation PREPARED operation

Bu bölüm **manuel, onaylı bakım önerisidir**; otomatik repair API/worker eklenmemiştir. Pending kaydını silmek, COMPLETED/ACKNOWLEDGED yazmak veya QR fence/receipt silmek yasaktır.

1. İlgili operasyonun ID'sini, eski generation'ını ve kullanıcı/revision bilgisini belirleyin. Personel ingress'ini ve bütün Auth/QR instance/worker'larını kapatın. Otomatik restart'ı durdurun; açık transaction/teslim kalmadığını doğrulayın. Eski imzalı servis komutlarının 30 saniyelik ömrü de dolmalıdır. Güvenilir saat varsayımı korunur.
2. Doğru Auth source ile QR guard generation'ının ve deployment restoranının aynı olduğunu doğrulayın. Bütün instance'ların doğru yapılandırılacağını doğrulayın. DB'ler restore edilmişse önce restore runbook uygulanır; bu prosedür kaybolmuş veri/receipt için kullanılamaz.
3. QR primary DB'de **bu operation ID için hiçbir receipt bulunmadığını** doğrulayın. Receipt varsa veya yokluğu güvenilir biçimde doğrulanamıyorsa burada durun; manifest değiştirilmez. QR fence'leri hiçbir durumda kaldırılmaz.
4. Auth primary'de kısa bakım transaction'ı açın. Kaynak satırını SHARE, ilgili kullanıcıyı UPDATE, operation satırını UPDATE kilidiyle bu sırayla alın. Kaynak ACTIVE ve doğru identity'de olmalıdır. Operation yalnız PREPARED olmalı; restoranı doğru source restoranı, `expected_user_version` güncel kullanıcı sürümüyle aynı olmalıdır. İlgili kullanıcının tek pending operation'ı olması ve manifestte hedef SID'lerin doğru kullanıcıya ait olması doğrulanır. Herhangi bir tutarsızlıkta rollback ve manuel uzlaştırma gerekir.
5. Yalnız bu ID'nin **hatalı `consumer_generation` alanını** doğrulanmış source generation'ına düzeltin; `next_attempt_at` yeniden denemeye açılabilir. WHERE koşulu eski generation, PREPARED durumu ve ID'yi içermeli; etkilenen satır tam olarak 1 olmalıdır. Başka alan, SID listesi, revision, proof/purpose/resume süresi veya kullanıcı verisi değiştirilmez. Değişiklik öncesi/sonrası bakım kaydı tutulur. Bu, teslim edilmediği doğrulanan hatalı manifest için istisnai operatör düzeltmesidir; normal idempotency sözleşmesi değildir.
6. Doğru yapılandırılmış QR ve Auth'u başlatın; personel ingress'i kapalı kalsın. **Aynı operation**, düzeltilmiş manifest için QR'a gönderilir; QR kalıcı fence/receipt commit eder; Auth yeni imzalı ACK'i doğrular; ancak sonra normal FINALIZE çalışır. Pending bariyeri bu ana kadar açık bırakılmaz.
7. AUTO işlemde COMPLETED ve eski SID'nin bütün QR instance'larında 401 olduğunu doğrulayın. RESET/MFA'da worker iş değişikliğini uygulamaz: mevcut resume/proof kuralları geçerlidir; süre dolduysa yalnız ACK'li iptal EXPIRED olarak tamamlanabilir. Kullanıcıya parola/factor değişikliği başarılı denmez. Doğrulamalar başarısızsa ingress kapalı ve pending kayıt korunur.

Bu önkoşulların tamamı doğrulanamıyorsa güvenli sonuç otomatik iyileştirme değil, işlemin pending kalmasıdır.

## Zorunlu deployment peer-key smoke kontrolü

QR readiness, aynı primary DB'nin bootstrap durumunu gösterir; doğru Auth staff public key'inin bu instance'a dağıtıldığını kanıtlamaz. Biçimi geçerli yanlış bir public key ile readiness UP görülebilir. Yerelde hesaplanan iki fingerprint'i aynı yapılandırmadan karşılaştırmak bağımsız güven kanıtı değildir.

Otomatik eşleşme için Auth'un gerçek staff public key fingerprint'ini imzalı bootstrap manifestine bağlamak, QR'da kalıcılaştırmak ve her instance'ın yerel anahtarıyla eşleştirmek gerekir. Bu, wire/manifest/receipt ve kalıcı guard geçişini etkiler. Bu hedefli düzeltmede böyle bir protokol değişikliği yapılmadı; P2-2 otomatik readiness açısından açık operasyonel sınırdır.

**İlk trafik açılışında, her instance/key dağıtımında ve key değişiminde aşağıdaki smoke kontrolü zorunludur:**

1. Production'da TLS ve doğru sertifika/trust store ile gerçek Auth'a giriş yapın. Mevcut MFA kuralları aynen uygulanır; geçici MFA kapatma veya servis credential'ı kullanma yoktur.
2. O girişte Auth'un verdiği `restofy_access` cookie'siyle Auth `GET /api/v1/auth/session` sonucunun 200 olduğunu doğrulayın. Ayrı bir test imzalayıcısının ürettiği JWT eşdeğer kanıt değildir.
3. **Aynı JWT** ile, her QR instance'ının güvenilir private adresinde `GET /api/v1/qr/tables` çağrısını 200 olarak doğrulayın. ADMIN/WAITER ve doğru restoran kullanılır. QR'a refresh/MFA cookie'si gönderilmez; public gateway veya load balancer üzerinden tek başarılı istek bütün instance'ları doğrulamaz.
4. Bir instance 401/403/503 verirse o instance personel trafiğine açılmaz. Anahtar/issuer/restoran/rol ayrımı incelenir; doğrulama gevşetilmez. Bu kontrol yanlış anahtarla yeşil readiness'e rağmen başarısız olur.
5. Test oturumunu normal koordineli logout ile kapatın; FINALIZE sonrasında aynı JWT'nin bütün QR instance'larında 401 olduğunu doğrulayın. QR kesintisinde logout tamamlanmış sayılmaz.

Cookie/JWT, parola ve servis anahtarları komut geçmişine, loga veya bakım çıktısına yazılmaz. Kontrollü istemcinin belleği veya onaylı credential mekanizması kullanılır. Kontrol kaydı yalnız instance, commit/config deployment kimliği, zaman ve HTTP sonuçlarını içerir.

`ops/tests/q5-network.mjs` geçici gerçek Auth'un ürettiği JWT'yi doğru ve yanlış anahtarlı QR instance'larında karşılaştırır. Bu otomatik regresyon testi, production'daki yukarıdaki zorunlu dağıtım kontrolünün yerine geçmez.

## Pending tanısı ve receipt süresi

Worker WARN kayıtlarında yalnız operation UUID, sabit aşama ve kategori bulunur: SOURCE_IDENTITY, CHALLENGE, BOOTSTRAP, CLAIM, DELIVERY; DATABASE, HTTP durum kodu veya TRANSPORT_OR_PROTOCOL. Coordinator TRANSPORT, ACK_VALIDATION, FINALIZE veya BUSINESS_EXPIRY aşamasını bildirir. Exception mesajı, cause/stack trace, HTTP gövdesi veya credential kaydedilmez. Bu kayıtlar izleme platformu veya otomatik kurtarma değildir; pending süre alarmı deployment sorumluluğudur.

Normal QR receipt kabul süresi üreticiyle aynı **en fazla 30 saniye** olmuştur; önceki 60 saniyelik üst sınır kaldırılmıştır. Kalıcı olarak doğrulanmış ACK'in daha sonraki FINALIZE'da tekrar token süresine bakılarak yok sayılması gerekmez; ACK kaydı ve kaynak/manifest kontrolleri korunur.

## CI karşılaştırması

`origin/dev` tabanı `94f80e5` için [Backend verify 41339093125](https://github.com/software-lecture-labs/Restofy/actions/runs/41339093125) ve Q5 `805b77c` için [PR verify 42091225200](https://github.com/software-lecture-labs/Restofy/actions/runs/42091225200), Order persistence testlerinde aynı `relation "orders" does not exist` ve `relation "carts" does not exist` hatalarını içerir. Q5 diff'inde Order, `backend/test-support` veya `backend/pom.xml` değişikliği yoktur. Bu düzeltme Order/ortak test altyapısını değiştirmez. Yeni yerel düzeltmeler commit/push edilmediği için bunlara ait yeni GitHub CI sonucu yoktur.
