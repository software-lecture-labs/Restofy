# Auth/Mail Sonar güvenlik incelemesi — 31 Ekim 2026

## Sonuç ve sınır

Order ekibinin ilettiği S4790 ve S4502 bulguları kod ve birincil kaynaklarla incelendi.
Bu incelemede algoritma/CSRF politikasını değiştirmeyi gerektiren doğrulanmış bir istismar
gösterilmedi. İki kararın gerekçesi aşağıdadır; bu, Sonar bulgularının kapatıldığı anlamına gelmez.
Sonar sunucusu başlatılmadı, scan veya durum değişikliği yapılmadı. Yerel 5099 API'sinden
kural metadata'sı alınamadı; Order ekibinin instance'ındaki bulgu tipi ve gate teyit edilmedi.
Rapordaki test ve gate sayıları karşı ekibin koşusudur; kendi doğrulamamız olarak sunulmaz.

## S4790 — TOTP içinde HmacSHA1

- `TotpVerifier` ham SHA-1 parola hash'i kullanmıyor; 20 rastgele baytlık kullanıcı sırrıyla
  RFC 6238/HOTP uyumlu HMAC tabanlı tek kullanımlık kod hesaplıyor.
- 30 saniyelik adım, sınırlı zaman sapması, sabit zamanlı karşılaştırma ve son kabul edilen
  counter kontrolü var. Hesap/peer limitleri ve veritabanında koşullu counter tüketimi ayrı korumalardır.
- Enrollment URI'si açıkça `algorithm=SHA1&digits=6&period=30` yayımlıyor. Algoritmayı tek satırda
  SHA-256'ya çevirmek mevcut authenticator kurulumlarını uyumsuz hâle getirir.
- **SHA-1 RFC tarafından zorunlu tutulmuyor:** RFC 6238 SHA-256/512'ye de izin verir. Mevcut
  kararın gerekçesi protokol/kurulum uyumluluğudur; genel amaçlı SHA-1 güvenliği iddiası değildir.
- Kaynakta incelenen kanıt: `MfaSecurityTests.matchesRfc6238Sha1Vectors` ve
  `MfaFactorsTests.totpValidationAndConditionalCounterConsumptionAreSeparate`.
  Bu turda yeniden çalıştırılmadılar.

Sonar inceleme gerekçesi:

> Kullanım, genel hash veya parola saklama değil, mevcut enrollment sözleşmesine bağlı RFC 6238
> HMAC-SHA1 TOTP'dir. Üretici/verifier algoritması aynı olmalıdır. Rastgele kullanıcı sırrı,
> dar zaman penceresi, rate limit ve atomik replay koruması mevcuttur. Algoritma geçişi ayrı
> enrollment/uyumluluk planı gerektirir; yalnız statik analiz uyarısı için değiştirilmemiştir.

Kaynak: [RFC 6238, §1.2 ve §5](https://www.rfc-editor.org/rfc/rfc6238.html).

## S4502 — Mail iç gönderim yolunda CSRF istisnası

- İstisna yalnız `/internal/api/v1/mail/password-reset-jobs` yolundadır; yetkili işlem POST'tur.
- `ServiceFilter` tek `Authorization: Bearer` başlığını zorunlu tutar; kimlik cookie, session,
  kullanıcı header'ı veya HTTP Basic'ten türetilmez. Basic/form login kapalı, session stateless,
  diğer iş yolları denyAll'dur. Üç anonim health GET'i ayrı kalır.
- JWT için RS256, imza, issuer, subject, audience, token_type, scope, zamanlar ve UUID/jti
  doğrulanır; yetki `MAIL_RESET_SEND` ile sınırlandırılır. Token/body messageId bağı da kontrol edilir.
- Tarayıcı başka sitenin formundan bu özel Authorization değerini kendiliğinden ekleyemez.
  **Sadece stateless veya sadece JSON olmak CSRF güvenliği kanıtı değildir.**
- Kaynakta incelenen kanıt: `MailIntegrationTests` içinde token olmadan/yanlış kimlikle ret,
  yanlış claim/süre ve başka yolların reddi; geçerli servis tokenıyla CSRF'siz kabul senaryosu.
  Bu turda yeniden çalıştırılmadılar; cookie-only/Basic için adlandırılmış ek regresyonlar daha açık kanıt sağlayabilir.

Sonar inceleme gerekçesi:

> Bu uç yalnız backend servisinin açık Bearer JWT'siyle kimlik doğrular; tarayıcının otomatik
> taşıdığı cookie/Basic kimliğine güvenmez. CSRF istisnası dar iç yolla sınırlıdır; JWT ve
> endpoint yetkilendirmesi korunur. Cookie/Basic/browser kimliği bu yola eklenirse karar yeniden incelenmelidir.

Kaynak: [Spring Security — CSRF ve stateless uygulamalar](https://docs.spring.io/spring-security/reference/features/exploits/csrf.html).

## Sonar üzerinde yapılacak işlem

1. Bulguyu üreten instance'ın sürümünü, gerçek issue tipini, kural açıklamasını ve gate koşulunu kontrol edin.
   Güncel Sonar belgeleri eski hotspot kurallarının vulnerability/security issue'ya taşınabildiğini belirtir.
2. Hotspot ise kanıtla gerekçeli `Safe` incelemesi; issue/vulnerability ise ilgili sürümün
   `False positive` / `Accepted` iş akışına göre yetkili inceleme gerekir. Bunlar eşanlamlı kararlar değildir.
3. Bu kayıt incelendikten sonra uygun durum, gerekçesiyle **bulgunun bulunduğu sunucuda** işlenir.
   Kerem'in başka yerel Sonar sunucusunda yaptığı işaretleme Order ekibinin sunucusuna taşınmaz.
4. İlgili sunucuda metrik/gate yeniden kontrol edilmeden ERROR'un kalktığı söylenmez.
   `NOSONAR`, toplu suppression, dosya dışlama veya gate eşiği gevşetme eklenmedi.

Kaynak: [Sonar — Security Hotspots yaşam döngüsü ve geçişi](https://docs.sonarsource.com/sonarqube-server/user-guide/security-hotspots).

## 31 Ekim — yerel kaynak düzeltmeleri

Yerel Sonar API'sinde Auth/Mail için 36 açık kayıt görüldü; bu sayı yalnız yeni kod bulgularının sayısı değildir.

- Limiter tablo seçimi yalnız enum içinde tanımlanan tam, sabit SQL sorgularıyla yapılır; bütün veri değerleri parametrelidir.
- Proxy subnet kaydında array içeriğine göre `equals`, `hashCode` ve `toString` sağlanır.
- Notification yalnız GET yetkilendirir; gereksiz CSRF kapatma kaldırıldı, varsayılan koruma açık kaldı.
- Mail payload temizliği ortak private metottan çağrılır; public transaction sınırları korunur.
- İstisna testlerinde hazırlık işlemleri lambda dışına alındı; rol/prefix sabitleri ve context assertion düzeltildi.

`NotificationServiceTokens` için dosya düzeyindeki tarih API uyarısı ayrı değerlendirilmelidir:
üretim kodu `java.time.Clock` ve `Instant` kullanır. Tek `Date.from` dönüşümü JJWT adaptöründedir.
Yerel `jjwt-api-0.13.0.jar` üzerinde `javap io.jsonwebtoken.Clock`, arayüzün
`public abstract java.util.Date now()` istediğini doğruladı. Bu köprüyü kaldırmak enjekte edilen
saati ve süre testlerini bozar. Kaynağa gerekçe eklendi; suppression veya güvenlik gevşetmesi eklenmedi.

Yerel Java 25/Testcontainers `verify`: ortak test modülü 37, Auth 324, Mail 70 olmak üzere
**431 başarılı test**, sıfır hata/başarısızlık/atlama; Auth PMD ve Checkstyle başarılı.
Gerçek SMTP veya uygulama veritabanları kullanılmadı.

Son SQL metin düzenlemesinden sonra limiter + ortak altyapı **46/46**, record-pattern
düzenlemesinden sonra proxy + ortak altyapı **54/54** başarılıdır; bu sayılar 431'e eklenmez.
İlk limiter seçimi ortak modülde test seçmediği için durdu; ortak modül testi de seçilerek düzeltildi.
Kanıtlar: `tmp/auth-quality/sonar-findings-verify.log`, `sonar-rate-limit-final.log`, `sonar-proxy-final.log`.

Sonar kaynak analizi mevcut coverage XML'lerini yeniden kullanır; tam rapor üretimi tekrar çalıştırılmadı.
JWT `Date` adaptörüne ait yanlış pozitif adayının Sonar durumunu değiştirmek için açık kullanıcı
onayı beklenir; bu çalışmada issue yorumu/durumu, gate eşiği veya kural ayarı değiştirilmedi.

Son kaynak analizi API ile doğrulandı: önceki 36 Auth/Mail kaydından **35'i giderildi**;
yeni record-pattern uyarısı da giderildi. Mail'de açık kayıt yok; Auth'ta yalnız yukarıdaki
`java:S2143` tarih adaptörü kaydı açık. Backend genelinde 30 açık kayıt, yeni kodda 4 bulgu
nedeniyle gate hâlâ ERROR; diğer servislerin kodları değiştirilmedi.
