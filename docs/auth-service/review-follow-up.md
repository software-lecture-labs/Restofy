# PR #2 — inceleme takibi

Kaynak: [hocanın PR #2 yorumları](https://github.com/software-lecture-labs/Restofy/pull/2).
Kontrol tarihi: 29 Ekim 2026. Bu dosya uzak yorum/issue işlemi yapmaz.

| Hocanın maddesi | Mevcut karşılık / bu teslimin yaklaşımı | Kanıt veya kalan iş |
|---|---|---|
| Hesap kilitleme DoS | Hesap+peer 10, hesap tavanı 50; kalan risk açık | LoginRateLimiter, ADR-AUTH-002; dağıtık tahminle hesap tavanı tüketilebilir |
| Bellek dolunca masum kullanıcıya 429 | BoundedWindows en eskiyi tahliye eder | Mevcut limiter testleri; tahliye sayaç kaybı ödünleşimidir |
| Login/MFA/DB limiter farkı | Davranışlar belgeli, tek strateji henüz yok | #9 açık; ikinci Auth örneği öncesi ayrı iş |
| Global advisory kilit ve magic number | AdvisoryLocks ve adlandırılmış sabitler mevcut | Auth/Mail global kilit ölçek sınırı ADR-AUTH-002 |
| authenticate okuma yolu yazma kilidi | Mevcut davranış korundu | Stateful/stateless ve concurrency tasarımı ayrı iş; sessizce lock kaldırılmadı |
| İki sekmenin refresh yarışı | 10 sn grace, ikinci credential üretimi yok | StaffSessionIntegrationTests, StaffAuthHttpTests; frontend single-flight bekliyor |
| Origin olmayan curl/Postman | Bilinçli 403, güvenlik kuralı korundu | Auth README, staff-contract; curl için Origin+CSRF gerekir |
| Beklenmeyen 500 görünmezliği | Güvenli olay logu; mesaj/token loglanmaz | AuthExceptionHandler, ilgili HTTP/güvenlik testleri |
| ENV süreleri ve 900/604800 gerekçesi | Aralık kontrolü; dev varsayılanı protokol zorunluluğu değildir | AuthSettings: access 300–3600 sn, refresh 1–30 gün |
| ENV hatasında değişken adını belirtme | Değer değil yalnız anahtar adı | SessionSecurityTests negatif ayarlar |
| Tekrarlanan profil kontrolü | EnvironmentPolicy + ArchUnit | ArchitectureRulesTests gerçekten çalışan JUnit sonucu kontrol edilir |
| Commit sonrası Mail kaybı | Best-effort mevcut karar; dayanıklılık iddiası yok | ADR-AUTH-001; outbox sonraki karar |
| Session/service aynı RSA anahtarı | Hâlâ mevcut; bu teslimde değişmedi | #6 kapalı/not_planned; uygulanmış çözüm değildir |
| String durum değerleri | PasswordResetStatus/MailJobStatus enumları | Mevcut constraint eşleşme testleri |
| Test DB initializer kopyası | Ayrı servis korumaları korundu | #7 kapalı/not_planned; ortak Maven modülü kapsam dışı |
| BCrypt algoritma geçişi | Cost 12 ve eski hash biçimi korundu | #5 kapalı/not_planned; mevcut hashleri bozan geçiş yapılmadı |
| Tek satır çok ifade, import, uzun satır | Kalıcı Checkstyle + PMD; üretim/test kaynakları | 140 karakter, OneStatementPerLine, import sırası, FQN ve seçili PMD kuralları |
| JPA/JdbcTemplate standardı | Atomik SQL ile CRUD mevcut ayrım korunur | #8 kapalı/not_planned; ekip standardı ayrı iş |
| README ENV/Compose yanlış ifadesi | Önceki 9e0fd4c düzeltmesi korunur | Auth README'ye yalnız bu entegrasyon paketinin girişi eklendi |
| ADR referansı var, dosya yok | ADR-AUTH-001/002 zaten takipli; içerik korunur | Doküman bağlantıları dosya varlığıyla kontrol edilir |
| ArchUnit JUnit uyumu ve koşma kanıtı | Surefire'da ArchitectureRulesTests sayısı ayrıca kontrol | Başlangıçta 1 test, 0 hata/başarısızlık/skip; son sonuç status.md |
| CI yok | Auth clean verify GitHub Actions dosyası eklendi | Uzakta çalışması push yapılmadığından DOĞRULANMADI |
| Dağınık büyük düzeltme / PR hijyeni | Kalite, sözleşme testi ve doküman ayrı yerel commit | Uzak PR threadine yorum gönderilmez; commit geçmişi yeniden yazılmaz |
| Kapalı issue'ya dayanarak “izleniyor” deme | Güncel issue durumları açıkça ayrıldı | #9/#10 açık; #5–#8 kapalı; çözülmüş denmedi |

## Açık kararlar ve ekip bağımlılıkları

- **#10 (açık):** Gateway arkasında güvenilir gerçek peer adresi. Auth/Gateway/proxy sözleşmesi ve sahte forwarded header testleri gerekir. Mevcut getRemoteAddr güvenilir son hopu gösterir; gerçek kullanıcı IP'si olduğu iddia edilmez.
- **#9 (açık):** Tek rate-limit stratejisi; bellek/DB/Gateway/Redis seçimi bu teslimde yapılmadı.
- **#5–#8 (kapalı/not_planned):** GitHub statüsü teknik ihtiyacın giderildiği anlamına gelmez. Yeniden açma/yazışma kullanıcı kararıdır.
- Staff yönetimi ve personel alıcı dizini yerel Auth tesliminde [uygulandı](staff-directory-api.md).
  Notification için ayrı public key güveni vardır; bu, mevcut Auth→Mail anahtar paylaşımı #6'yı çözmez.
- Key rotation/JWKS, kayıp MFA prosedürü, gerçek SMTP ve guest taşıması ayrı kapsamdır.
- Diğer servisler mock ile bağımsız geliştirilebilir. [Issue #4](https://github.com/software-lecture-labs/Restofy/issues/4) tartışmasındaki bu yaklaşım, production'da mock kimlik kabulü anlamına gelmez.

Her değişiklik için yeni test sonucu esas alınır. Eski PR test sayısı veya Sonar sonucu bu teslimin doğrulaması olarak kullanılmaz.
