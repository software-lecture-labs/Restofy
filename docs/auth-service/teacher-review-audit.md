# Hocanın PR yorumları — güncel kod karşılaştırması

Kontrol: 29 Ekim 2026. Yerel kaynak: `471a52f`; uzak yazma veya uygulama kodu değişikliği yapılmadı.
Restofy'de `KeremKalyoncu` tarafından açılmış tüm durumdaki PR'lar sorgulandı; **yalnız PR #2** bulundu.
`buraksenyurt` hesabının **16 inline yorumu + 4 review mesajı = 20 mesajının tamamı** okundu.
Copilot'un iki kota mesajı öğretmen yorumu sayılmadı. Karşılaştırma eski PR diff'ine değil güncel yerel koda yapıldı.

**Sonuç: Bazı maddeler giderilmiş; bütün teknik borçlar kapanmamış. Yeni testlerde aynı stil hatası da tekrarlanmış.**
"Auth işlevsel kapsamı hazır" ifadesi, tüm öğretmen önerilerinin uygulandığı veya production'a hazır olunduğu anlamına gelmez.

## Hâlâ mevcut olanlar

| Öncelik / durum | Doğrulanan durum | Güncel kanıt |
|---|---|---|
| Üretim/ölçek öncesi | Güvenilir client IP çözümü yok. Login/MFA/reset `getRemoteAddr()` kullanıyor; Gateway arkasında aynı peer görülmesi riskini çözmez. | `StaffAuthController:54`, `StaffMfaController:44`, `PasswordResetController:50`; [#10 açık](https://github.com/software-lecture-labs/Restofy/issues/10) |
| Üretim/ölçek öncesi | Login/MFA peer sayaçları bellek içi, reset limitleri DB'de; ortak dağıtık strateji yok. | `LoginRateLimiter`, `MfaPeerLimiter`, `PasswordResetLimiter`; [#9 açık](https://github.com/software-lecture-labs/Restofy/issues/9) |
| Kısmi iyileştirme | Login 10 hesap+peer ve 50 hesap tavanı uyguluyor. Dağıtık saldırıda hesap tavanının tüketilmesi hâlâ mümkün. | `LoginRateLimiter:19` ve `check()`; ADR-AUTH-002 |
| Bilinçli dayanıklılık sınırı | Auth→Mail commit sonrası bellek kuyruğu, tek HTTP denemesi. Restart/kesintide kayıp hâlâ mümkün; ADR yazılması bu davranışı düzeltmez. | `MailClient:78`, `:86`, `:95`; ADR-AUTH-001 |
| Ayrı güvenlik iyileştirmesi | Auth→Mail JWT hâlâ personel JWT'siyle aynı Auth private key'iyle imzalanıyor. Yeni Notification anahtar ayrımı bunu çözmez. | `MailClient:115`, `NotificationServiceTokens:26`; [#6 kapalı/not_planned](https://github.com/software-lecture-labs/Restofy/issues/6) |
| Performans / tasarım | Auth okuma doğrulaması hâlâ DB'de `FOR UPDATE` alıyor; yeni personel okuması da `requireAdmin()` üzerinden bu yolu kullanıyor. | `StaffSessionService:104`, `:190`; `StaffDirectoryService:120` |
| Ölçek sınırı belgeli | Reset limiter global advisory lock ile serileşiyor. Kilit adı/sabiti düzeltildi; global davranış kaldırılmadı. | `PasswordResetLimiter:75`; `AdvisoryLocks.PASSWORD_RESET_LIMITS` |
| Bakım borcu | Auth/Mail test DB initializer'ları isim/mesaj/biçim farkları dışında aynı mantığı ayrı tutuyor. Ortak test modülü yok. | İki servisin `support/*TestDatabaseInitializer.java` dosyaları; [#7 kapalı/not_planned](https://github.com/software-lecture-labs/Restofy/issues/7) |
| İleriye dönük öneri | BCrypt cost 12 güvenliği korunuyor; DelegatingPasswordEncoder/önekli hash geçişi uygulanmadı. Bu, mevcut BCrypt'in kırık olduğu anlamına gelmez. | `StaffPasswords:16`; [#5 kapalı/not_planned](https://github.com/software-lecture-labs/Restofy/issues/5) |
| Ekip standardı | JPA ve JdbcTemplate birlikte kullanılmaya devam ediyor. | `StaffUserRepository`, `PasswordResetRepository`; [#8 kapalı/not_planned](https://github.com/software-lecture-labs/Restofy/issues/8) |
| Tekrarlanan stil hatası | **Yeni** `StaffDirectoryHttpTests` içinde import yerine tam sınıf adları var: `BadCredentialsException`, `java.util.List`, `java.util.Arrays`, `Matchers`. | `backend/services/auth-service/src/test/java/com/restofy/auth/api/StaffDirectoryHttpTests.java:104`, `:155`, `:220`, `:258` |
| Mail'de kalan aynı tür hata | Mail testlerinde paket/import dışı **23 satır 140 karakteri aşıyor**; tam sınıf adları da kullanılıyor. Üretim Java'sında bu ölçümle uzun satır yok. | `GreenMailSmokeTests:12`, `MailDeliveryWorkerTests:18`, `:46`, `MailIntegrationTests:44` |
| Mail'de tanı koyma eksikliği | MailSettings hatasında hangi ENV alanının bozuk olduğu söylenmiyor; genel mesaj korunuyor. | `backend/services/mail-service/src/main/java/com/restofy/mail/infrastructure/config/MailSettings.java:34` |

**Kalite kapısı neden stil hatasını yakalamadı?** Aktif PMD `UnnecessaryFullyQualifiedName` kuralı,
zaten bir import tarafından kapsanan tam adın gereksiz tekrarını yakalar; bütün tam sınıf adlarını yasaklamaz.
Kurulu PMD 7.17.0 `category/java/codestyle.xml` kural açıklaması kontrol edildi.
Dolayısıyla **PMD/Checkstyle 0**, hocanın bütün stil taleplerinin karşılandığının kanıtı değildir.
Auth kalite kapısı Mail kaynaklarını da tarıyor gibi sunulamaz; mevcut kurulum yalnız Auth içindir.

## 20 mesajın tamamı için karşılık

| # | Hocanın mesajı | Bugünkü karşılığı |
|---|---|---|
| 1 | [Hesap kilitleme DoS](https://github.com/software-lecture-labs/Restofy/pull/2#discussion_r4324430778) | **KISMEN:** 10 peer/hesap + 50 hesap modeli var; dağıtık hesap kilitleme ödünleşimi sürüyor. |
| 2 | [Bellek dolunca masum kullanıcıya 429](https://github.com/software-lecture-labs/Restofy/pull/2#discussion_r4324434400) | **KISMEN:** BoundedWindows/Login/MFA peer eskiyi tahliye ediyor; flood testi var. Reset DB'si dolunca hâlâ yeni bucket'a 429 veriyor; bu kodda belgeli fail-closed kararıdır (`PasswordResetLimiter:86`). |
| 3 | [Limiter stratejileri farklı](https://github.com/software-lecture-labs/Restofy/pull/2#discussion_r4324465367) | **HÂLÂ VAR:** #9 açık; ortak strateji uygulanmadı. |
| 4 | [Global kilit ve magic number](https://github.com/software-lecture-labs/Restofy/pull/2#discussion_r4324500213) | **KISMEN:** AdvisoryLocks/sabitler ve ölçek notu var; global serileştirme korunuyor. |
| 5 | [Okumada lock / stateless JWT](https://github.com/software-lecture-labs/Restofy/pull/2#discussion_r4324572368) | **HÂLÂ VAR:** authenticate→lockUser→FOR UPDATE; yalnız consumer JWT doğrulaması stateless olabilir. |
| 6 | [İki sekmenin refresh yarışı](https://github.com/software-lecture-labs/Restofy/pull/2#discussion_r4324638164) | **AUTH'TA GİDERİLDİ:** 10 sn grace, ikinci credential üretilmez; eşzamanlılık/HTTP testleri var. Frontend single-flight henüz uygulanmadı. |
| 7 | [Origin'siz curl/Postman](https://github.com/software-lecture-labs/Restofy/pull/2#discussion_r4324667010) | **AÇIKLANDI:** README/CSRF sözleşmesi davranışı belgeliyor. Browser POST hâlâ Origin/CSRF ister; Notification servis GET'i ayrı kimlik zincirindedir. |
| 8 | [Görünmez 500 / hassas logs](https://github.com/software-lecture-labs/Restofy/pull/2#discussion_r4324706157) | **GİDERİLDİ:** AuthExceptionHandler güvenli tür/stack özeti logluyor; istemci detayı almıyor. İlgili testler mevcut. |
| 9 | [TTL'ler / 900–604800 gerekçesi](https://github.com/software-lecture-labs/Restofy/pull/2#discussion_r4324748808) | **GİDERİLDİ:** ENV aralıkları access 300–3600 sn, refresh 1–30 gün; README eski sabit zorunluluğunu açıklıyor. |
| 10 | [ENV'de hatalı anahtar adı](https://github.com/software-lecture-labs/Restofy/pull/2#discussion_r4324790422) | **AUTH'TA GİDERİLDİ:** AuthSettings değişken adını, değeri değil bildiriyor. Önerilen anotasyon yaklaşımı zorunlu tutulmamış; özel doğrulama korunmuş. MailSettings'te benzer genel mesaj sürüyor. |
| 11 | [EnvironmentPolicy + ArchUnit](https://github.com/software-lecture-labs/Restofy/pull/2#discussion_r4324835931) | **AUTH'TA GİDERİLDİ:** Auth profil kararı EnvironmentPolicy'de; Mail kendi servis sınırında eşdeğer policy tutuyor. ArchUnit mevcut ve gerçekten koşmuş. |
| 12 | [Mail retry/outbox ve ADR](https://github.com/software-lecture-labs/Restofy/pull/2#discussion_r4324933408) | **KARAR BELGELİ, RİSK SÜRÜYOR:** ADR-AUTH-001 takipli; kalıcı outbox/yeniden gönderim uygulanmadı. |
| 13 | [Ayrı servis RSA anahtarı](https://github.com/software-lecture-labs/Restofy/pull/2#discussion_r4324970613) | **AUTH→MAIL'DE HÂLÂ VAR:** Notification'ın ayrı anahtarı, Mail paylaşımını gidermiyor. |
| 14 | [String durumlar yerine enum](https://github.com/software-lecture-labs/Restofy/pull/2#discussion_r4325021203) | **GİDERİLDİ:** PasswordResetStatus/MailJobStatus ve SQL parametrelerinde enum isimleri; schema eşleşme testleri var. |
| 15 | [Kopya test initializer](https://github.com/software-lecture-labs/Restofy/pull/2#discussion_r4325081611) | **HÂLÂ VAR:** İki ayrı uygulama; ortak test modülü yok. |
| 16 | [DelegatingPasswordEncoder önerisi](https://github.com/software-lecture-labs/Restofy/pull/2#discussion_r4325139517) | **UYGULANMADI:** İleriye dönük, bu PR için şart olmayan öneri. Eski hash biçimi korunuyor. |
| 17 | [İlk genel inceleme: stil/sabit/persistence](https://github.com/software-lecture-labs/Restofy/pull/2#pullrequestreview-5584328546) | **KISMEN:** Auth'ta kalıcı PMD/Checkstyle ve adlandırılmış iş sabitleri var; FQN kullanımı testlerde sürüyor, Mail'de uzun satırlar var, persistence standardı seçilmedi. |
| 18 | [Dört merge kontrolü + CI](https://github.com/software-lecture-labs/Restofy/pull/2#pullrequestreview-5601345947) | **KISMEN:** README düzeltmesi ve iki ADR Git'te; son Surefire'da ArchUnit 1/0/0/0. CI yerelde hazır ama GitHub'da çalışmadı. GitHub thread yanıt/resolve işlemi bu turda yapılmadı. |
| 19 | [Yerelde kalan commitler / kapalı issue'lar](https://github.com/software-lecture-labs/Restofy/pull/2#pullrequestreview-5632436195) | **KISMEN:** Eski düzeltmeler/ADR'ler merged PR'da mevcut. Bu teslimin yeni commitleri bilinçli olarak yerelde; #9/#10 açık, #5–#8 kapalı/not_planned. Kasıtlı bozulmuş ArchUnit deneyi bu turda yeniden üretilmedi. |
| 20 | [Onay + merge sonrası işler](https://github.com/software-lecture-labs/Restofy/pull/2#pullrequestreview-5637840250) | **ONAYLANMIŞ, BORÇLAR BİTMEMİŞ:** Son review APPROVED, PR birleşmiş. #9/#10 yeniden açık; okuma kilidi/test modülü hâlâ mevcut; CI kaynak dosyası hazır, uzak koşu doğrulanmadı. |

## Kanıtın sınırı ve işlem kaydı

- Mevcut Surefire: **306 başarılı test**, ArchUnit **1**; PMD/Checkstyle **0/0**. Bu turda testler yeniden çalıştırılmadı.
- GitHub'daki 16 inline thread hâlâ unresolved görünüyor; bu, her teknik konunun kodda hâlâ bozuk olduğu anlamına gelmez.
- #5–#8'in kapalı/not_planned olması çözülmüş olduklarını göstermez. Durumlar canlı GET ile doğrulandı; değiştirilmedi.
- Kod, ENV, migration, PR, issue, yorum ve thread durumuna dokunulmadı. Mail yalnız okundu.
- Sıradaki dar temizlik: Auth testlerindeki FQN kullanımları; ardından ayrı iş olarak #10/#9.
  Proxy güven modeli ve ortak limiter kararı verilmeden güvenlik ayarları sessizce değiştirilmemeli.
