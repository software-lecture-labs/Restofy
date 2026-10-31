# Auth kapsam kapanışı — 30 Ekim 2026

## Güncel çalışma sırası

- Sonraki kullanıcı talebiyle ilk frontend `/login` sayfası uygulandı; landing/MFA/reset ve
  panel ekranları sonraki aşamadır. [Frontend durumu](frontend/README.md).
  Servis sahiplerinin somut Auth talepleri geldikçe backend üzerinden de ilerlenir.
- İstek geldiğinde çağıran servis, beklenen işlem/DTO, rol/restoran ve hata davranışı mevcut
  sözleşmeyle karşılaştırılır; eksik Auth işi uygulanır, tüketici adapter işi ilgili serviste kalır.
- Mevcut kapsamda yeni doğrulanmış Auth engeli yok; varsayımsal endpoint veya Saga eklenmez.
- Mail ayrı geliştirme konusu olarak takip edilir. Mevcut gönderim, şifreli kalıcılık ve recovery
  kodu vardır; bu durum Mail'in bütün ürün kapsamının veya gerçek SMTP işletiminin tamamlandığı anlamına gelmez.
- Minör değişikliklerden sonra test tekrarları yapılmaz. Biriken değişiklikler sonunda ilgili
  doğrulama birlikte yapılır; o zamana kadar yeni değişiklikler test edilmiş diye raporlanmaz.
- Yerel commit yapılabilir; kullanıcı izin vermeden push/PR/issue/merge/deploy yapılmaz.

## Sonuç

**Mevcut tek restoran/personel AUTH-01..04 ve onaylanan entegrasyon/sağlamlaştırma teslimi yerelde tamam.**
Bu kapsamda kodla doğrulanmış açık engel bulunmadı. Çok restoranlı platformun tamamı veya canlıya
hazır olunduğu iddia edilmez. Yeni özellik eklemek yerine mevcut kararlar, uygulama ve kanıt aşağıda eşleştirildi.

## Kabul matrisi

| Teslim | Kod / test kanıtı | Sonuç |
|---|---|---|
| AUTH-01 personel/parola/bootstrap | StaffPasswords, StaffBootstrapService; parola ve bootstrap testleri | UYGULANDI |
| AUTH-02 giriş, refresh, iptal, idle, cookie/CSRF | StaffSessionService, AuthRequestFilter; StaffAuthHttpTests, StaffSessionIntegrationTests, SessionSecurityTests | UYGULANDI |
| AUTH-03 ADMIN MFA/kurtarma/replay | MFA enrollment/verification, recovery kodları; MfaIntegrationTests ve MfaAdmissionPoolIntegrationTests | UYGULANDI |
| AUTH-04 ADMIN parola sıfırlama | PasswordResetService; PasswordResetIntegrationTests, PasswordResetIdentityRaceTests | UYGULANDI |
| ADMIN personel yönetimi | StaffDirectoryService; StaffDirectoryHttpTests, eşzamanlı değişim/reset iptali | UYGULANDI |
| Notification alıcı dizini | Ayrı dar servis JWT zinciri; StaffRecipientHttpTests, NotificationServiceTokensTests | UYGULANDI |
| Tüketici JWT sözleşmesi | staff-v1; bağımsız JCA tüketici testi, negatif imza/audience/tenant/MFA | UYGULANDI |
| Proxy ve ortak limiter | ClientAddressResolver, Gateway temizliği, SharedRateLimitStore; HTTP ve PostgreSQL testleri | UYGULANDI |
| Mail kesinti/restart ve anahtar ayrımı | Auth şifreli outbox, Mail şifreli payload, ayrı signer; kuyruk/relay/worker/crypto testleri | UYGULANDI |
| Kalite ve devir | PMD/Checkstyle, ortak test modülü, ArchUnit regresyonu, yerel workflow, 12 modül bağlantı belgesi | UYGULANDI; uzak CI DOĞRULANMADI |

## Auth'a ilişkin kararların karşılığı

Bu kayıt mevcut onaylı uygulamayı sabitler; yeni ürün veya güvenlik politikası getirmez.

- **D-09, Auth dilimi kapalı:** Tek restoran/personel başına tek rol; RS256 access JWT + HttpOnly
  cookie, zorunlu Origin/CSRF, ADMIN MFA ve tek kullanımlık kurtarma kodları. Auth DB'de session
  kontrol eder; tüketici yerel JWT doğrular, iptal access TTL kadar gecikebilir. Mail Bearer ve
  Notification Service kimlikleri ayrı sözleşmelerdir. Genel servis kimliği yayılımı ekip işi olarak açık kalır.
- **D-10, Auth→Mail dilimi kapalı:** Broker eklenmez; aynı business transaction'da AES-GCM outbox,
  commit sonrası iç HTTP, sabit messageId ve sınırlı retry. SMTP belirsizliğinde UNKNOWN kör
  tekrar gönderilmez. Genel Order/Payment/QR olay altyapısı hakkında karar verilmedi. ADR-AUTH-001 esas alınır.
- **D-13, Auth aralığı kapalı:** Esas belge `docs/decisions/migrations.md`: Auth V1–V10, Mail
  V11–V20. Kullanılan yeni sürümler V5/V6/V12; eski migration'lar değişmedi. Aralık genişletme ekip kararıdır.
- **#9/#10, yerel uygulama kapalı:** PostgreSQL ortak sayaç ve tek Gateway hop için dar allowlist;
  gerçek CIDR seçimi/dağıtım doğrulaması operatöre aittir. ADR-AUTH-002 esas alınır. GitHub issue'ları kapatılmadı.
- **Kayıp MFA:** Mevcut recovery kodu geçerli kurtarma yoludur. Parola sıfırlama MFA'yı kaldırmaz;
  `resetsPasswordRevokesEverySessionAndKeepsMfaAndAccountActive` bunu korur. Faktör ve tüm recovery
  kodları birlikte kayıpsa ayrı kimlik ispatı/onay prosedürü tanımlanmadan bypass eklenmez.
- **Hash/persistence:** Mevcut cost-12 BCrypt ve sınırlı JPA/JDBC ayrımı korunur. Hocanın açıkça
  geleceğe bıraktığı algoritma geçişi ve ekip geneli persistence standardı bu teslimi tamamlamak için icat edilmez.

## Kapatılmayan ortak kararlar ve nedenleri

| Konu | Sahip / neden |
|---|---|
| D-05 mesai, son garson logout'u, sipariş aç/kapa | Ürün + Admin + Auth; login/logout tek başına mesai veya sipariş yetkilendirme kararı sayılmıyor. Yeni iş akışı uydurulmadı. |
| Çok restoranlı üyelik/global yönetici | Ürün + mimari; mevcut tek restoran DB modeli bu özelliği sağlamaz. Tenant şeması ve geçiş gerektirir. |
| D-11 SLA, yedek, saklama, yük hedefleri | Operasyon + ekip; ölçülmemiş sayısal SLA veya saklama süresi uydurulmadı. |
| JWKS/online rotasyon, tüm faktörler kayıpken kimlik ispatı | Ayrı güvenlik tasarımı; sessiz bypass/anahtar değişimi yok. |
| Gerçek SMTP, CIDR, servisler arası E2E, frontend single-flight | Operatör ve tüketici servis sahipleri; bu çalışmada canlı sistem/diğer servis kodu değiştirilmedi. |

## Hoca incelemesi ve doğrulama

#2 ve #11'in konuşma/inline/review kayıtları bu kapanışta GET ile tekrar okundu:
20 + 1 hoca mesajı; önceki denetimden sonra yeni hoca mesajı yok.
[Mesaj karşılıkları ve düzeltilen tekrarlar](pre-pr-review.md) günceldir.

Üretim kodu önceki doğrulanmış `55a3f53`, mimari testleri `126f6bc` ile aynı; bu kapanışta
yalnız belgeler değişti. Bu nedenle aynı geniş test tekrar çalıştırılmadı.
Son gerçek tam `clean verify`: 484/484 başarılı; ardından iki yeni mimari regresyonu dahil
odaklı koşu 41/41 başarılı. PMD/Checkstyle 0. Kanıt ayrıntıları `pre-pr-review.md` içindedir.

Bu turda README'nin eski “iş endpoint'i yok” ifadesi, Service/Bearer ayrımı ve ortak test modülünü
build'e katmayan Maven komutu düzeltildi. Yerel bağlantı/dosya kontrolleri yapıldı.
Uzak CI, güncel dev ile birleştirme ve canlı çalışma sonucu hâlâ DOĞRULANMADI.

## PR teslimi

[Yerel PR başlığı ve açıklaması](pr-message.md) hazırdır; henüz PR açılmadı.
Hocanın küçük PR talebi gereği tüm dalın birleşik özeti tek büyük PR zorunluluğu olarak okunmamalı;
kalite/test altyapısı, kilit ayrımı, proxy/limiter, Mail kalıcılığı ve personel entegrasyonu
bağımlılık sırasıyla ayrı inceleme fazları olabilir. Geçmiş yeniden yazılmadı.
