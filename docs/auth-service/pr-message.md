# feat(auth,mail): personel entegrasyonu ve güvenilir kimlik altyapısı

## Amaç

Hocam merhaba,

PR #2'deki incelemenizde belirttiğiniz konuları ve #11'deki kapsam, Compose ve dokümantasyon
uyarılarınızı dikkate alarak Auth/Mail tarafını güncelledim. Diğer servislerin Auth'a bağlanabilmesi
için personel sözleşmesini testlerle korudum; ADMIN personel yönetimini ve Notification alıcı
sorgusunu tamamladım. Proxy, çok örnekli limiter, okuma kilidi, test altyapısı ve Mail kaybı
konularındaki düzeltmeler aşağıda yer alıyor.

## Değişiklikler

- Mevcut JWT/cookie/CSRF akışı korunur. ADMIN personel yönetiminde MFA, rol, restoran,
  stale version, eşzamanlı değişim ve hesap değişiminde session/reset iptali denetlenir.
- Notification için ayrı servis kimliğiyle yalnız aktif personel UUID'lerini dönen dar alıcı sorgusu eklenir.
- Gateway sahte forwarding başlıklarını temizler; Auth yalnız açıkça tanımlanmış dar proxy
  listesine güvenir. Login/MFA peer/reset sayaçları ortak PostgreSQL stratejisini kullanır.
- Salt okuma session doğrulaması yazma kilidi almaz; mutasyonlar kilit altında tekrar doğrulanır.
  MFA peer kontrolü account transaction'ından önce tamamlanarak bağlantı havuzunda iç içe borçlanma önlenir.
- Auth→Mail ayrı RSA signer kullanır. Auth şifreli transactional outbox, Mail şifreli payload ve
  restart recovery sağlar. Tekrarlarda messageId korunur; SMTP sonucu belirsizse kör resend yapılmaz.
- Auth üretim/test kaynakları `mvn verify` içinde PMD ve Checkstyle ile denetlenir.
  Ortak PostgreSQL test koruması test-scope modüle taşınır. ArchUnit gerçek profil API'lerini
  yakalar; kuralı bilerek ihlal eden regresyon testleri vardır.
- GitHub Actions dosyası Auth/Mail/Gateway/ortak modül testlerini çalıştırıp raporları saklayacak
  şekilde hazırlanır. Servis bazlı client/Saga sorumlulukları `docs/auth-service/` altında belgelenir.

## Şema ve yapılandırma

- Yeni eklemeli migration'lar: Auth **V5/V6**, Mail **V12**. Önceki migration'lar değiştirilmedi.
- Yeni zorunlu sırlar: `AUTH_MAIL_PRIVATE_KEY`, `AUTH_MAIL_OUTBOX_KEY`, `MAIL_PAYLOAD_ENCRYPTION_KEY`.
  `MAIL_AUTH_PUBLIC_KEY`, ayrı Mail signer'ının public karşılığıdır.
- `AUTH_TRUSTED_PROXY_CIDRS` varsayılan boş; `AUTH_NOTIFICATION_PUBLIC_KEY` opsiyonel aktarılır.
- Üç yeni sır eksikken Compose'un başka servisler için ayrıştırılması engellenmez; Auth/Mail
  kendi başlangıcında eksik/geçersiz anahtarı reddeder. Anahtar üretimi `docs/auth-service/key-setup.md` içindedir.
- Mevcut işler boşaltılarak koordineli signer/uygulama geçişi gerekir; rollback ve SMTP
  belirsizliği `ADR-AUTH-001`, limiter/proxy/okuma kararları `ADR-AUTH-002` içinde açıklanır.

## Doğrulama

```text
mvn -B -ntp -f backend/pom.xml -pl services/api-gateway,services/auth-service,services/mail-service -am clean verify
```

- Yerel Java 25/Testcontainers tam kapı: **484 başarılı test**, 0 başarısızlık/hata/atlama
  (ortak 37, Gateway 55, Auth 323, Mail 69).
- Sonraki test koruması değişikliği: **41 başarılı odaklı test** (ortak 37, Auth 2, Mail 2);
  bu koşu, tam koşudan sonra eklenen iki mimari regresyon testini de kapsıyor.
- Son Auth PMD/Checkstyle: **0/0**. Auth/Mail Java kaynaklarında package/import dışı >140 satır: **0**.
- Eski ArchUnit kuralı yasak çağrıyı kaçırırken regresyon RED; düzeltilen Auth/Mail kuralları GREEN.
- Yeni Compose sırlarının eksikliği sahte ENV ile eski ayarda RED, yeni ayarda GREEN.
- Windows Türkçe yolunda yalnız CLI üzerinden koşuya özel ASCII JaCoCo exec yolu kullanıldı;
  coverage eşiği/test zorunluluğu gevşetilmedi. Gerçek ENV, DB veya SMTP kullanılmadı.

GitHub Actions workflow'u hazır; **uzak CI sonucu henüz doğrulanmadı**.
Tam repo pre-push koşusunda frontend lint ve çalışmayan servislerin health kontrolleri
başarılı tamamlanmadı; bu PR için yukarıdaki Auth/Mail/Gateway kalite kapısı doğrulandı.

## Kapsam ve bilinen sınırlar

Auth mevcut tek restoran/personel modeli içindir. Çok restoranlı üyelik, tüm faktörleri kayıp
ADMIN için yeni kimlik ispatı, JWKS/online rotasyon ve mesai/sipariş aç-kapa politikası eklenmedi.
Diğer servisler kendi JWT/client/adapter ve kaynak yetkilendirmesini uygular; bu çalışma gerçek
servisler arası E2E veya frontend ekranı içermez. Yerel JWT tüketicisinde iptal access TTL kadar gecikebilir.

Kod değişiklikleri Auth/Mail ve onaylı dar Gateway forwarding/testleriyle sınırlıdır;
ortak POM yalnız test-support kaydı, Compose/örnek ENV yalnız ilgili aktarım için değişir.
Sonar ve diğer servislerin iş koduna müdahale edilmedi. Canlı migration/deploy uygulanmadı.

İnceleme yorumlarınızın karşılıklarını `docs/auth-service/pre-pr-review.md`, kapsam ve
kalan ekip bağımlılıklarını `docs/auth-service/completion-review.md` içinde belgeledim.

İlgili takipler: #9, #10.
