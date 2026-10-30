# PR öncesi hoca yorumları denetimi — 30 Ekim 2026

## Kapsam ve kaynak

GitHub GET ile tüm durumdaki 5 PR ve her birinin konuşma, inline yorum ve review mesajları
okundu: #2, #11, #12, #13, #14. Hocanın (`buraksenyurt`) #2'de 20, #11'de 1 mesajı var;
diğer üç PR'da henüz yorumu yok. Kerem'in tek PR'ı #2. Uzak yazma yapılmadı.

[Auth incelemesi](https://github.com/software-lecture-labs/Restofy/pull/2),
[QR incelemesi](https://github.com/software-lecture-labs/Restofy/pull/11#pullrequestreview-5695894632).
Eski 20 yorumun tek tek bağlantıları [tarihsel denetimde](teacher-review-audit.md) korunur.
Bu inceleme yorumların Auth/Mail karşılığını değerlendirir; bütün reponun kusursuzluk kanıtı değildir.

## Bu turda bulunan tekrarlar

| Bulgu | Yerel düzeltme / kanıt |
|---|---|
| Yeni Compose sırları `${VAR:?}` ile başka servislerin config işlemini engelliyor | Yalnız yeni üç sır `${VAR:-}` oldu. Uygulama zorunluluk kontrolü korunur. Sahte ENV ile eski ayar RED, yeni ayar GREEN; gerçek ENV okunmadı, container başlatılmadı. |
| Yeni Auth outbox ve Mail payload SQL'inde durumlar yeniden string olarak yazılmış | MailOutboxStatus eklendi; iki repository mevcut/yeni enum `.name()` değerlerini parametre geçirir. Migration'lar değişmedi. |
| README hâlâ bellek limiterı, eski proxy ve eski MFA transaction sınırını anlatıyor | Paylaşılan PostgreSQL sayacı, dar proxy güveni ve peer/account transaction sırası güncellendi. |
| Yeni anahtarların üretimi anlatılmıyor | [Anahtar kurulumu](key-setup.md): biçimler, ayrı RSA/AES, Git dışı dosyalar ve koordineli geçiş. Gerçek anahtar üretilmedi. |
| ArchUnit kuralında `acceptsProfiles(Profiles[])` gerçek API ile eşleşmiyor | Yerel Spring API imzası kontrol edildi; `Profiles`, eski `String[]` ve `matchesProfiles(String[])` korundu. İki serviste dört ayrı ihlal örneğini denetleyen regresyon testi eklendi. Eski Auth kuralı RED (1 FAIL/0 ERROR), yeni kurallar GREEN. |

## Auth'taki 20 mesajın bugünkü karşılığı

| # | Konu | Durum |
|---|---|---|
| 1 | Hesap kilitleme DoS | 10 hesap/peer + 50 hesap; hesap tavanı ödünleşimi ADR'de açık. Tam DoS bağışıklığı iddiası yok. |
| 2 | 10000 kova doluluğu | Login/MFA eski kovayı tahliye eder; reset fail-closed. Kapasite/çok örnek testleri var. |
| 3 | Farklı limiter stratejileri | PostgreSQL ortak depo; #9 GitHub'da hâlâ açık, kapatılmadı. |
| 4 | Global advisory lock / magic number | Adlandırılmış sabitler; aile başına kısa serileştirme ve yük sınırı ADR'de. |
| 5 | Salt okumada yazma kilidi | GET snapshot; yazmada kilit altında tekrar kontrol; gerçek DB lock testi. Auth'un DB doğrulaması bilinçli olarak sürer. |
| 6 | İki sekmede refresh | Grace ve eşzamanlılık testleri; frontend single-flight bu kapsamda yok. |
| 7 | Origin'siz istemci | CSRF/Origin sözleşmesi belgeli; güvenlik gevşetilmedi. Servis kimliği ayrı zincir. |
| 8 | Görünmez 500 / hassas log | Güvenli hata türü/stack özeti; payload, token, sır veya exception mesajı loglanmaz. |
| 9 | TTL / eski sabit zorunluluğu | ENV aralıkları ve eski 900/604800 varsayımının kaldırılması belgeli. |
| 10 | ENV hata mesajları | Auth/Mail ilgili değişken adını ve biçimi bildirir; değer/cause yansıtılmaz. |
| 11 | Profil politikası / ArchUnit | Servis başına EnvironmentPolicy; bu turda kuralın API imzası ve regresyon kanıtı ayrıca ele alındı. |
| 12 | Mail kesintisi / restart | Şifreli Auth outbox + Mail payload/recovery; SMTP sonucu belirsiz iş kör yeniden gönderilmez. |
| 13 | Ortak RSA | Mail signer ayrı; session modulus'u tekrar kullanılamaz. |
| 14 | String durumlar | Reset/Mail enumları korunur; yeni outbox/payload tekrarları bu turda giderildi. |
| 15 | Kopya initializer | Ortak test-scope modül, 37 koruma testi; üretim JAR'ında yok. |
| 16 | DelegatingPasswordEncoder | Hocanın açıkça bu PR için şart tutmadığı gelecek önerisi; uygulanmadı, BCrypt korunur. |
| 17 | Stil / persistence | Import, satır, statement ve iş sabitleri kontrol altında. JPA/JDBC genel standardı ayrı ekip kararı; toplu dönüşüm yok. |
| 18 | README / ADR / test / PR hijyeni | Belgeler Git'te; ArchUnit kanıtı aşağıda. Thread yanıtları ve PR açıklaması uzağa gönderilmedi. |
| 19 | Yerelde kalan commit / kapalı issue | Yeni commitlerin yerelde olduğu açık; push izni yok. #9/#10 GET ile açık doğrulandı; durumları değiştirilmedi. |
| 20 | Merge sonrası proxy/limiter/test/CI | Yerel uygulamalar mevcut; workflow hazır, GitHub çalışması doğrulanmadı. |

## QR yorumundan Auth teslimine uygulanan dersler

- Yeni sırlar başka servislerin Compose ayrıştırmasını engellemez; anahtarsız Auth/Mail yine başlamaz.
- Sonar, QR, Order/Payment iş kuralları değiştirilmedi. Auth iş Saga'sını veya başka servisin verisini yönetmez.
- QR'ın personel adapterını açması QR tarafındaki küçük entegrasyon işidir; yeni Auth endpoint eksiği sayılmaz.
- QR tarama limiti QR sorumluluğudur; Auth login limiti onun yerine geçmez.
- Tek büyük PR yerine bağımsız inceleme fazları hazırlanmalı: kalite/test altyapısı; GET kilit ayrımı;
  proxy + ortak limiter; şifreli Mail + yeni ayarlar; personel/Notification sözleşmeleri.
  Her faz yalnız ihtiyaç duyduğu docs ve testleri içerir; bağımlı fazların sırası açıklanır.
  Burada dal geçmişi yeniden yazılmadı, yeni PR/dal açılmadı. Son büyük commit tek PR önerisi değildir.

## Doğrulama ve kalan teslim sınırı

30 Ekim `clean verify`: **484 test başarılı** (ortak37/Gateway55/Auth323/Mail69), hata/atlama yok.
Auth PMD/Checkstyle 0. Ardından yalnız test koruması genişletildi: ortak37 + Auth2 + Mail2
odaklı mimari testi başarılı. Bu iki yeni regresyonla toplam 486 farklı test doğrulanmış oldu;
486'nın tek bir toplu koşuda çalıştığı iddia edilmez. Son eklenen test kaynaklarının statik
kontrolü ayrıca çalıştırıldı; üretim kodu toplu kapıdan sonra değişmedi.
Son statik kapı: **BUILD SUCCESS**, PMD 0 / Checkstyle 0. Auth/Mail Java kaynaklarında
package/import dışı 140 karakter üstü satır: 0.

Compose eski/yeni karşılaştırması sahte ENV ile RED/GREEN; anahtar örneğinin PowerShell
sözdizimi ve dört dosyanın Git dışlaması doğrulandı (anahtar üretim komutları çalıştırılmadı).
İlk odaklı test seçimi ortak modülde hiç test seçmediği için durdu; kanıt sayılmadı.
Gerçek RED/GREEN koşularına ortak initializer testi de eklendi; test zorunluluğu gevşetilmedi.

İlk incelemede onay veya sıfır yorum garantisi verilemez. Gerçek ağ/CIDR, production sırları,
SMTP ve tüketici servislerle uçtan uca doğrulama operatör/entegrasyon işi olarak kalır.
PR açılacağı zaman güncel dev tabanında çatışma ve uzak CI ayrıca kontrol edilir; bu turda
rebase, push, PR, yorum, issue güncellemesi, merge veya deploy yapılmadı.

Yerel düzeltme commitleri: `55a3f53` (enum durumları), `126f6bc` (mimari koruma).
Compose ve dokümantasyon son ayrı committe saklanır. Önceki teslimin test/işletim sınırları
[sağlamlaştırma raporunda](hardening-status.md).
