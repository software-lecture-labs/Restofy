# Auth/Mail sağlamlaştırma — yerel uygulama kapsamı

Durum: 30 Ekim 2026; onaylanan yerel uygulama ve son kalite kapısı tamamlandı.
Sonuçlar: [hardening-status.md](hardening-status.md).
Uzak işlem veya gerçek ortam/veritabanı değişikliği yok.
Önceki 5 maddelik kullanıcı isteği bu turun amacı; mevcut JWT/cookie ve Mail HTTP DTO sözleşmeleri korunur.

## Teknik seçimler

1. **Stil ve hata mesajları:** Auth/Mail testlerindeki gereksiz tam sınıf adlarını importlara taşı;
   Mail'de 140 karakter sınırını düzelt. MailSettings hatası yalnız ayar adını/format beklentisini bildirsin.
2. **Ortak test altyapısı:** `backend/libs/test-support` test-scope modülü; mevcut initializer korumalarının
   ortak çekirdeği. Servis başına küçük initializer ve hata bağlamı korunur. Ortak runtime/domain kütüphanesi yok.
3. **Okuma kilitleri:** Salt-okunur kimlik doğrulamasını snapshot sorgusuyla ayır;
   refresh, MFA, hesap değişimi, oturum iptali gibi yazmalarda kullanıcı kilidi ve transaction içi yeniden doğrulama korunur.
   Geçiş testleri pasifleştirme/rol değişikliği/refresh yarışını korumalıdır.
4. **Ortak limiter:** Mevcut PostgreSQL kullanılır; Redis/broker eklenmez. Login ve MFA peer budget'ları
   örnekler arası atomik DB sayaçlarına taşınır. Hesap/peer limitleri ve retry-after sözleşmesi korunur;
   tablo kapasitesi ve saldırı altında masum kullanıcı davranışı ayrıca test edilir.
5. **Proxy:** Auth varsayılan olarak yalnız socket adresine güvenir. Sadece açıkça tanımlanmış gateway
   adres/CIDR'lerinden gelen forwarding zinciri kabul edilir. Gateway dışarıdan gelen kimlik-adres başlıklarını
   temizleyip güvenilir socket peer bilgisini aktarır. Sahte XFF/Forwarded ve çok hop testleri gerekir.
6. **Anahtar ayrımı:** Auth→Mail servis JWT'si ayrı RSA private key kullanır; eski Auth oturum anahtarına
   sessiz fallback yapılmaz. Mail'deki mevcut public-key ayarı yeni servis anahtarının public kısmını doğrular.
   Geçiş sırası ve uyumlu dağıtım/geri dönüş sınırları belgelenir; gerçek anahtar oluşturulmaz/yazılmaz.
7. **Dayanıklılık:** Auth'ta transaction'a katılan kısa ömürlü, AES-GCM şifreli mail outbox'ı;
   restart sonrası devam, bounded retry, aynı messageId ile idempotent teslim ve süre sonunda payload temizliği.
   Mail kabulü sonrası payload kaybı da ele alınacaksa Mail'de şifreli teslim payload'ı ve recovery gerekir.
   SMTP kabulüyle DB sonucu arasındaki belirsizlik tam-bir-kez teslim olarak sunulmaz; UNKNOWN halinde kör resend yapılmaz.

## Gerekli dar kapsam genişlemesi

- Gateway: yalnız forwarding güveni ve testleri; route/business davranışı değişmez.
- Ortak Maven: yalnız test-support modül kaydı; Auth/Mail test bağımlılıkları.
- Şema: Auth'un kullanılmamış V5–V10, Mail'in V12–V20 aralığında **yeni, eklemeli** migration'lar.
  Önceki V1–V4/V11 dosyaları değişmez. Yalnız izole Testcontainers DB'de çalıştırılır.
- Yapılandırma: güvenilir proxy listesi, ayrı mail signing key ve outbox/payload şifreleme anahtarı için
  yeni ayarlar; `.env.example` ve Compose aktarımı tutarlı olmalı. Gerçek `.env` dosyaları okunmaz/değiştirilmez.
- Güvenlik kararı: mevcut ADR-AUTH-001 best-effort yaklaşımı şifreli ve süreli kalıcılığa geçer;
  ADR-AUTH-002 PostgreSQL ortak limiter stratejisiyle güncellenir.

Bu genişleme güvenlik belgesinin ENV ve hassas veri kalıcılığı kurallarına tabidir; gerekli açık kullanıcı onayı alındı.
Gerçek anahtar/ortam değerleri ve çalışan veritabanları yine bu işin dışında kalır.

## Kabul ve teslim

Önce ilgili testler, sonra Auth/Mail/Gateway ve ortak test modülü verify. Sahte proxy, birden fazla Auth
örneğinde sayaç tutarlılığı, row-lock yazma yarışları, iki ayrı RSA anahtarı, restart/retry/expiry,
şifreli payload/log gizliliği ve ortak initializer negatif testleri gerekir. Sonuçlar ve yerel commitler raporlanır.
Push, PR, issue, yorum, merge, deploy veya gerçek veritabanına migration yoktur.
