# ADR-QR-001 — Masa kapanışı, sipariş kabulü ve finansal güvenlik sınırı

- **Durum:** A yaklaşımı ve K1–K9 kabul edildi. QR tarafı A'ya uyarlandı; gerçek Order/Payment/Auth entegrasyonu ve GitHub reviewer onayı bekliyor.
- **Oluşturulma:** 29 Ekim 2026. **Karar tarihi:** 30 Ekim 2026.
- **Sahipler:** QR ve Payment — Alper; Order — Hamza. Katılım ve ortak yanıtlar kullanıcı tarafından bu şekilde bildirildi.
- **Kaynak:** [PR #11 review](https://github.com/software-lecture-labs/Restofy/pull/11#pullrequestreview-5695894632) ve devamındaki merge öncesi karar/ADR yerleşimi geri bildirimi.
- **İncelenen Order:** `feature/order-service`, [7acc43f](https://github.com/software-lecture-labs/Restofy/commit/7acc43f116b76d4026879de4c44d06814c6aa55e). GitHub üzerinden salt okunur incelendi; yerel dala çekilmedi ve değiştirilmedi.

Bu belge kararları ve bunları uygulayacak işleri kaydeder. Kararın kabulü kodun değiştiği, migration'ın uygulandığı, yeni API açıldığı veya testlerin geçtiği anlamına gelmez. Mevcut kod ile seçilen hedef aşağıda ayrı gösterilir. Önceki `docs/ADR/` konumundan repository'nin `docs/decisions/` düzenine taşınmıştır.

## 1. Bağlam ve mevcut uygulama

[Mimari](../architecture.md) uyarınca sipariş kabulü ve iş durumunun sahibi Order, mali doğruluğun sahibi Payment, masa/guest oturumları ve masa kapanışının sahibi QR'dır. Servisler kendi veritabanlarına sahiptir; servisler arasında DB erişimi, fiziksel FK veya ortak JPA entity paylaşımı yapılmaz.

Karar sonrası güncel QR kodu ile uzak Order incelemesinin durumu:

- **QR:** Eski admission ve ayrıntılı iş/finans projeksiyonu kaldırıldı. [V29](../../backend/services/qr-service/src/main/resources/db/migration/V29__create_session_transition_operations.sql) yalnız QR'ın kalıcı lifecycle komut/sonucunu, [V30](../../backend/services/qr-service/src/main/resources/db/migration/V30__guard_session_transition_history.sql) değişmez geçmiş ve stage geçişlerini kurar. QR sipariş/Payment defteri tutmaz.
- **QR kapanışı:** [TableSessionClosureService](../../backend/services/qr-service/src/main/java/com/restofy/qr/application/service/TableSessionClosureService.java) aynı kalıcı komutla Order prepare/status/ACK/abort sınırını kullanır. Otomatik kapanış personel istemez; ayrı temizlendi staff yetkisinde kalır. Main/guest/masa değişiklikleri yerelde atomiktir. Eski close HTTP controller metodu kaldırıldı, diğer HTTP iş API'leri kapalıdır.
- **QR idle/guest:** [OrderSessionAccess](../../backend/services/qr-service/src/main/java/com/restofy/qr/application/port/OrderSessionAccess.java) varsayılan UNKNOWN/unavailable döndürür. Geçici Order okuması koruma/iptal zamanı içindir; fiziksel expiry kalıcı gate izni gerektirir. Bağımsız guest süreleri ve tokenla gerçek bağlam çözümü uygulanmıştır; gerçek ağ/aktivite aktarımı yoktur.
- **Order checkout:** [OrderCheckoutService](https://github.com/software-lecture-labs/Restofy/blob/7acc43f116b76d4026879de4c44d06814c6aa55e/backend/services/order-service/src/main/java/com/restofy/order/application/service/OrderCheckoutService.java#L170) QR oturumunu kontrol edip ayrı yerel transaction'da siparişi kaydeder. Kontrol ile commit arasında masa kapanabilir; K8 koordinasyonu mevcut değildir.
- **Order kimlik ve ödeme:** [QrSessionPort](https://github.com/software-lecture-labs/Restofy/blob/7acc43f116b76d4026879de4c44d06814c6aa55e/backend/services/order-service/src/main/java/com/restofy/order/application/port/QrSessionPort.java) token ile bağlam çözmeyi bekler; gerçek QR adaptörü yoktur. Payment bildirimi alan internal API vardır; seçilen Order → Payment sorgusu ve QR'a tam oturum kapanış uygunluğu sözleşmesi yoktur.
- **Order durum/teslimat:** Fulfillment COMPLETED ödeme olmadan mümkündür. A'da Order bu bilgiyi Payment mali uygunluğuyla birleştirir; eski QR birleşik iş/finans enum'u kaldırılmıştır. [OutboxEventWriter](https://github.com/software-lecture-labs/Restofy/blob/7acc43f116b76d4026879de4c44d06814c6aa55e/backend/services/order-service/src/main/java/com/restofy/order/infrastructure/messaging/OutboxEventWriter.java#L16) olay kaydeder; dispatcher yoktur. Kaynak aggregateVersion, başka bir servisin kesintisiz event sequence'ı sayılmaz.
- **Payment:** İncelenen yerel çalışma ağacında başlangıç altyapısı vardır; gerçek sorgu, tahsilat ve uzlaştırma entegrasyonu bu incelemede doğrulanmadı. Bu belge bunları uygulanmış kabul etmez.

## 2. Mimari seçim ve alternatifler

**A seçildi:** QR masa, ana/guest oturumları ve aktivite sürelerine odaklanır. Order, masa oturumunun bütün siparişlerini ve Payment'tan doğruladığı mali durumu değerlendirerek QR'a güvenilir kapanış uygunluğu sağlar. QR'daki ayrıntılı sipariş/finans kopyası sadeleştirilir.

| Seçenek | Sonuç | Gerekçe / maliyet |
| --- | --- | --- |
| A — Order'dan yetkili uygunluk ve kapanış koordinasyonu | Seçildi | QR'daki veri tekrarı ve sipariş/finans sorumluluğu azalır. Kalıcı koordinasyon ve servis kesintisinde bekleme gerekir. |
| B — QR'da olaylarla güncellenen küçük engelleyici iş projeksiyonu | Ana model olarak seçilmedi | Sürekli projeksiyon, eksik/sırasız olay ve yeniden eşitleme yükü getirir. |
| C — QR rezervasyonu ve ayrıntılı sipariş/finans projeksiyonu | Seçilmedi | Mevcut QR kodundan yararlanır; ancak Order'ı QR kabul protokolüne bağlar ve QR'da ayrıntılı kopya gerektirir. |

A, tek bir GET veya tekrar kullanılabilen `canClose=true` cevabına indirgenmez. Checkout ile kapanışın sıralanması, tam kaynak kapsamı ve kalıcı sonucu öğrenme K8/K9'un zorunlu parçalarıdır. QR'da minimal oturum/işlem kimliği ve sürüm tutmak ikinci bir sipariş/finans otoritesi kurmak anlamına gelmez.

## 3. Kararlar — K1–K9

### K1 — Normal kapanış otomatik olacak

Masa oturumunun tamamlanma ve mali koşulları sağlandığında ana oturum ve bütün bağlı aktif guest oturumları otomatik kapanır. Ayrıca garson/admin onayı aranmaz. Bu karar önceki zorunlu personel kapanış onayını değiştirir. Kalıcı masa QR kimliğinin silinmesi veya pasifleştirilmesi söz konusu değildir.

### K2 — Siparişlerin tamamlanması ve ödeme birlikte aranacak

Ödeme tamam olsa bile hazırlığı/teslimi devam eden sipariş varsa masa açık kalır. Sipariş tamam ama ödeme eksikse yine kapanmaz. Fulfillment'ın sahibi Order, mali doğruluğun sahibi Payment olarak kalır; QR birleşik uygunluğu Order'dan alır. Bu karar, ödeme başlatma API'sine ayrıca `COMPLETED` ön koşulu ekleyen bir kural değildir; bu çalışmada ödeme zamanına yeni bir yasak uygulanmaz.

### K3 — Siparişsiz guest'ten ödeme beklenmeyecek

QR okutmak borç doğurmaz. Örneğin üç guest'ten ikisi sipariş verdiyse kapanış bu iki kişinin gerçek sipariş/ödeme yükümlülüklerine bakar. Koşullar tamamlanınca siparişsiz üçüncü guest de main ve diğer aktif guest'lerle birlikte kapanır. Guest sayısı kadar ödeme kaydı aranmaz. Hiç sipariş verilmemiş masada normal 15 dakika idle kuralı sürer; boş kapsam anında "ödendi" sayılıp kapanmaz.

### K4 — MVP'de müşteri yalnız kendi siparişlerini ödeyecek

Müşterinin ödeme yetkisi kendi doğrulanmış guest oturumuna bağlı siparişleriyle sınırlıdır. Ortak masa hesabı ve başkası adına ödeme MVP kapsamı dışındadır. Aynı masada olmak diğer guest'in siparişine yetki vermez. Kişinin birden fazla kendi siparişi bulunabilir; bu karar ödeme başına sipariş sayısını belirlemez. Masa çapındaki servis uygunluk kontrolü müşteri erişimini genişletmez.

### K5 — Order tokenı QR'a doğrulatıp bağlamı alacak

Kimliği doğrulanmış Order servisi guest tokenıyla QR'a başvurur. QR tokenın gerçek kaydından `restaurantId`, `tableId`, `tableSessionId`, `guestSessionId` bilgilerini çözüp güncel kullanılabilirliği doğrular. Masa aktifliği, main/guest durumu, süreler ve sipariş koruması birlikte değerlendirilir; ham `ACTIVE` veya istemci kimlik beyanı yeterli değildir.

Doğrulama süreyi yenilemez ve tek başına sipariş kabul izni değildir. Geçersiz token ile QR erişilemiyor durumu ayrılır; belirsizlikte erişim açılmaz. Ham token loga/olaya yazılmaz. Mevcut Java guard'ının token + beklenen kimlik sözleşmesi gerçek iç servis sınırına uyarlanacaktır.

### K6 — Gerçek menü/sepet etkileşimi 15 dakikayı yenileyecek

Siparişsiz oturumda gerçek menüye giriş, kategori/ürün gezinmesi ve anlamlı sepet işlemleri son aktiviteyi yeniler. Başarılı sepet mutasyonlarını Order, gerçek menü etkileşimlerini frontend QR'a bildirir. Aktivite ilgili guest ve main'i yeniler; diğer guest'lerin bireysel süresini uzatmaz.

İşlem yapılmazsa son aktiviteden itibaren 15 dakika sonunda normal idle kuralları uygulanır. Sayfadan çıkışta anında kapanma veya tarayıcının çıkış bildirimi zorunluluğu yoktur. Açık ekran, arka plan sekmesi ve otomatik polling/heartbeat aktivite değildir. Gecikmiş bildirim kapanmış/süresi dolmuş oturumu yeniden açmaz.

Kabul edilmiş sipariş main'i ve sipariş sahibini korur. Böyle bir masa salt 15 dakika işlem yok diye kapanmaz. Bu koruma Order'ın geçici, güvenilir guest/main kapsamına bağlandı; gerçek adaptör henüz yoktur ve yerel kayıt yokluğu sipariş yokluğu sayılmayacaktır.

### K7 — Order, Payment bilgisini doğrulayıp QR'a uygunluk sağlayacak

Sipariş, guest ve masa oturumunu bilen Order, Payment'tan yetkili mali durumu sorgular; bütün masa kapsamıyla birleştirip QR'a uygunluk/engel sonucu sağlar. QR normal kapanış için Payment'ı ayrıca sorgulamaz ve sipariş/finans kararının ikinci sahibi olmaz. V29/V30 ve bağlı QR kodu bu hedefe uyarlandı: yalnız QR lifecycle komutu ve yerel sonuç saklanır; Order/Payment transport'u kapalıdır.

Kaynak kapsamı bütün guest'lerin gerçek siparişlerini, devam eden kabulleri ve mali belirsizlikleri kapsar. Müşteri/personel için varsayılan `active` sipariş listesi bu kapsam değildir; ödenmemiş `COMPLETED` sipariş de kapanışı engeller. Tek başına `PAID`, sıfır bakiye, `CANCELLED` veya boş sayfa bütün koşulların sağlandığını kanıtlamaz.

### K8 — Yeni checkout ve kapanış hazırlığı Order'da kalıcı sıralanacak

Order, restoran + `tableSessionId` kapsamında yeni sipariş kabulünü ve kapanış hazırlığını aynı kalıcı koordinasyonla sıralar:

1. Sipariş önce kalıcı kabul edilmişse kapanış hesabına katılır; koşullar sağlanmıyorsa masa açık kalır.
2. Kapanış hazırlığı önce kesinleşmişse yeni checkout sipariş kaydı üretmeden `409` döner.

Eski QR doğrulama cevabı, ayrı kilitler veya yalnız bir JVM'deki mutex bu engeli aşamaz. Checkout commit'i aynı koordinasyonu denetlemelidir. Ağ yanıtının sırası işlem sırası sayılmaz; hem güvenli kapanış hem sonradan kabul edilen yeni sipariş başarılı görünemez. QR'ın guest açma/doğrulama ve idle yolları da kapanış hazırlığına uygun davranmalıdır. Servisler arası transaction atomikliği iddia edilmez.

### K9 — Kayıp yanıt/kesinti aynı kalıcı işlem kimliğiyle çözülecek

Kapanış kalıcı, tenant ve masa oturumuna bağlı tek bir işlem kimliğiyle izlenir. Aynı işlem ikinci bir kapanış veya ödeme üretmez; farklı içerikle aynı kimliği kullanmak kabul edilmez. Süreç yeniden başlasa da aynı işlemin sonucu sorgulanıp güvenli biçimde devam edilir.

Sonuç bilinmiyorsa oturum kapanmış varsayılmaz. Başlamış kapanış da timeout yüzünden kendiliğinden iptal edilerek yeni siparişe açılmaz. QR/Order kalıcı sonuç ve teyidi uzlaştırır; sadece ağ hatası bir işlemin başarısızlığını kanıtlamaz. Kesin olarak uygun olmayan sonuçla belirsiz sonuç ayrılır; yeniden açma ancak uzlaştırılmış işlem durumuna göre yapılır.

Tekrar mekanizması yalnız kapanış koordinasyonunu tamamlar; müşteriden tekrar ödeme istemez, yeni tahsilat başlatmaz. Yeniden denemeler aralıklı ve kaynak sınırlarıyla yürütülür; süresiz sıkı döngü kurulmaz. Çelişkili kaynak kayıtları sessizce çözülmez, operasyonel uzlaştırma gerektirir. Mevcut genel POST çağrılarına kör otomatik retry eklenmez.

## 4. Korunan önceki kurallar

- Güvenli biçimde tamamen iptal edilen son sipariş sonrası ilgili guest için, bütün siparişler güvenli iptal edildiyse main için doğrulanmış iptal anından yeni 15 dakika kuralı korunur. Ödeme belirsizliği veya gerekli ama doğrulanmamış iade varken bu koruma otomatik kaldırılmaz; iptal talebi gerçekleşmiş iade sayılmaz. Bu ADR yeni bir otomatik iade politikası getirmez.
- Main, açık guest'ler ve masa durumu QR'ın tek yerel transaction'ında sonlandırılır. Siparişli normal kapanışta mevcut temizlik ayrımı korunur: `OCCUPIED → CLEANING`; ayrı yetkili temizlendi işlemi `AVAILABLE` yapar. Siparişsiz idle mevcut davranışına göre masayı serbest bırakır.
- Geçmiş sipariş, ödeme ve kapanış kayıtları silinmez. Normal otomatik kapanış için sahte personel UUID'si üretilmez; audit, gerçek servis/işlem kimliğini yansıtmalıdır. QR audit'i session_transition_operations içindeki kalıcı komut ve yerel sonuçtur; otomatik kapanışta closed_by_user_id NULL kalır.
- Gerçek Auth/servis kimliği, tenant/guest sahipliği ve müşteri tarama rate limit'i tamamlanmadan mevcut kapalı API'ler açılmaz. Yeni public endpoint, ENV, broker veya ortak domain kütüphanesi bu ADR ile eklenmiş değildir.

## 5. Hedef akış ve sözleşme yükümlülükleri

1. QR geçerli taramada main/guest açar. Order K5 ile gerçek guest bağlamını doğrular; ödeme yetkisi K4'le sınırlıdır.
2. Kullanıcı aktivitesi K6'yla işlenir. Siparişsiz idle, güvenilir Order kapsamıyla ve süre sınırındaki yeni checkout'la koordine edilerek sonlandırılır.
3. Order sipariş ve Payment durumundaki değişiklikleri kapanış değerlendirmesini tetikleyecek güvenilir bir mekanizmaya bağlar. Bildirim tek başına kapanış kanıtı değildir; QR'da ayrıntılı finans kopyası gerektirmez.
4. Kapanış için kalıcı işlem kimliği oluşturulur ve süreç K8 üzerinden Order'ın checkout'larıyla sıralanır. Order bütün oturum kapsamını ve Payment'ın yetkili mali bilgisini doğrular. Uzak sorgular uzun DB kilidi altında tutulmaz; kalıcı koordinasyon korunur ve sonuç ilgili sürümlerle bağlanır.
5. Sonuç uygun değilse kapanış yapılmaz; bilinmiyorsa K9 uzlaştırması sürer. Güvenilir uygunluk sonucu yalnız ilgili işlem, restoran, main ve kaynak kapsamı için geçerlidir; eski genel bir boolean yeniden kullanılamaz. Yeni ödeme denemesi, iptal veya değişen kaynak sürümü göz ardı edilemez.
6. QR güncel main/masa sürümlerini ve işlem durumunu kısa yerel transaction'da tekrar denetleyip main ve aktif guest'leri bitirir, masayı `CLEANING` yapar. Doğrulanmış normal kapanışta personel onayı aranmaz.
7. QR'ın kalıcı sonucu Order tarafıyla aynı kimlik üzerinden teyit/uzlaştırılır. Sonlandırma yanıtı kaybolursa kaynak sonuç sorgulanır; tahsilat veya kapanış yan etkisi tekrarlanmaz.

**İç sözleşmelerin uygulama yükümlülükleri:** Guest doğrulama, guest/main sipariş koruması ve sipariş yokluğu, kapanış hazırlığı/uygunluğu, sonuç sorgusu ve teyit; bütününde doğrulanmış servis kimliği, tenant kapsamı, sürümlenmiş DTO, tekrar güvenliği, kaynak tamlığı/tazeliği ve güvenli hata yanıtı gerekir. Kesin HTTP yolları/DTO adları henüz uygulanmadı; çalışan endpoint gibi belgelenmez. Aktarımın restart/kayıp yanıt güvencesi kalıcı iş kayıtlarıyla sağlanmalıdır. Order outbox'ının yalnız yazıcı olması teslimat garantisi vermez; gereken işleyici/gönderici uygulama kapsamına alınır. Broker seçimi D-10'u bu ADR genel olarak kapatmaz.

## 6. Migration ve merge öncesi koşullar

Reviewer, V29/V30'un yalnız bu branch'te olduğunu ve paylaşılan ortamda uygulanmadığını belirtti. Bu çalışma ekipteki bütün veritabanlarının `flyway_schema_history` kayıtlarını doğrulamadı. Önceki "V29/V30 zaten uygulanmış, değiştirilemez" ifadesi bu ayrımı yapmadığı için düzeltildi.

- Ölçüt yalnız production değildir. Paylaşılan/güvenilen Flyway geçmişine girmiş migration sessizce yeniden yazılmaz. Dev merge'i SQL'i kendi başına çalıştırmaz; ekip QR'ı başlattığında ortak şema yükümlülüğü oluşabilir.
- A seçildiği için eski ayrıntılı şema ve bağlı kod kaldırıldı; V29/V30 yalnız QR lifecycle işleminin değişmez komut/sonucunu tutacak şekilde revize edildi. Reviewer'ın paylaşılan ortamda uygulanmadığı bildirimi ve kullanıcının yalnız bu branch'in V29/V30'unu değiştirme açık onayı esas alındı. V21–V28 değiştirilmedi.
- Yerel QR DB'de eski V29/V30 checksum'ları bulundu. Kullanıcı yerel veri resetini açıkça onayladı; yalnız doğrulanmış QR veritabanı sıfırlanıp yeni imajla migration uygulandı. Eski QR verileri taşınmadı; diğer servis DB'leri ve PostgreSQL/Sonar volume'leri silinmedi. Ekipte eski QR geçmişi varsa kendi açık onaylı geçişi gerekir. Bu istisna başka migration/paylaşılan geçmiş için izin değildir.
- [Sürüm dağılımı](migrations.md) geçerlidir. QR V21–V30, Payment V31–V40, Order V41–V50'dir. Order'ın mevcut aralığı doludur; yeni kalıcı koordinasyon şeması gerekirse ekip yeni aralık tahsis etmelidir. V31 Payment'a, V51 Notification'a aittir; ödünç alınmaz.
- PR #11'in A'ya uyarlanmış kod/migration ve ilgili testleriyle review edilmesi gerekir. Bu belge tek başına "merge hazır" sonucu değildir. QR-04 uzun sürerse QR-01–QR-03'ü gerçekten bağımsızlaştırıp test ederek ayrı PR'a ayırmak alternatif olarak kalır; böyle bir ayırma yapılmış değildir.

## 7. Servis bazında uygulama ve doğrulama planı

| Sorumlu | Yapılacak iş | Bu karar çalışmasındaki durum |
| --- | --- | --- |
| QR / Alper | Admission/outcome kaldırma; Order kaynak sınırı, K5 token çözümü, otomatik kapanış/idle, K9 kalıcı komut/teyit ve bounded retry | IMPLEMENTED — yerel QR kodu/SQL ve PostgreSQL testleri; gerçek Order adaptörü kapalı |
| Order / Hamza | Gerçek QR adaptörü, K6 sepet aktivitesi, K8 checkout'la ortak kalıcı koordinasyon, Payment sorgusu, tam kapsam uygunluk ve K9 sonuç/teyit; yeni şema gerekirse migration aralığı kararı | NOT IMPLEMENTED — uzak branch'e yazılmadı |
| Payment / Alper | K4 sahiplik; Order'a yetkili mali durum, bekleyen girişim/tahsilat/iptal-iade ve uzlaştırma bilgisini işlem/kapsam/sürümle sağlamak | NOT VERIFIED — gerçek uygulama ve entegrasyon doğrulanmadı |
| Frontend / ilgili geliştirici | Gerçek menü aktivitesi, güvenli guest taşıması, kendi sipariş kapsamı, otomatik kapanış ve belirsiz işlem görünümü | NOT IMPLEMENTED — bu çalışma frontend içermez |
| Ortak / servis sahipleri | Gerçek kaynak/tenant/checkout yarışı ve Payment kapsamı E2E; gerçek servis kimliği | NOT VERIFIED — QR testlerinde Order otoritesi kontrollü; gerçek servisler arası E2E çalıştırılmadı |
| Dokümantasyon / Alper | Kararlar, yeni konum, mimari/gereksinim ve güncel entegrasyon rehberi | IMPLEMENTED — GitHub işlemleri geliştiriciye bırakıldı |

Uygulama PR'ları ortak sözleşme ve koordinasyon, QR sadeleştirme/geçiş, Payment ve gerçek Auth/servis bağlantısı gibi incelenebilir parçalara ayrılır. Bir parçanın tamamlanması bütün entegrasyonun bittiği anlamına gelmez. HTTP güvenlik mekanizması, kesin DTO/endpoint'ler, retry limitleri ve veri geçiş ayrıntıları uygulama işlerinde somutlaştırılır; sayı veya SLA uydurulmaz.

## 8. Ortak kabul testleri

| Senaryo | Beklenen sonuç |
| --- | --- |
| Sipariş yok, işlem yok / 14. dakikada gerçek etkileşim | İlk durumda 15 dakika idle; ikinci durumda ilgili guest/main yenilenir |
| Yalnız açık sekme/polling; iki telefon | Yapay yenileme olmaz; birinin hareketi diğer guest'i yenilemez |
| QR kesintisi, yanlış servis kimliği, sahte tenant/guest ID | Erişim açılmaz; geçersizlik ve bağımlılık kesintisi ayrılır |
| K5 doğrulama ve gecikmiş aktivite | Doğrulama süreyi yenilemez; terminal oturum diriltilmez |
| Kabul edilmiş sipariş ve 15 dakika hareketsizlik | Main ve sipariş sahibi korunur; siparişsiz guest kendi idle kuralına tabidir |
| Ödeme tamam/sipariş tamam değil; sipariş tamam/ödeme eksik | İkisinde de normal kapanış olmaz |
| İki sipariş sahibi ve bir siparişsiz guest | Gerçek yükümlülükler bitince tüm aktif guest/main kapanır; üçüncü ödeme aranmaz |
| Kendi siparişi / aynı masadaki başkası / başka restoran | K4 sahipliği doğrulanır; yetkisiz sipariş/ödeme erişimi reddedilir |
| Yeni checkout önce / kapanış hazırlığı önce | Önceki sipariş kapsamda; önce kapanışsa yeni checkout 409; çoklu instance'ta aynı sonuç |
| Uygunluk sorgusundan sonra kaynak sürümü/kapsamı değişir | Eski kanıtla kapanış olmaz; yeni ödeme/iptal/belirsizlik göz ardı edilmez |
| Kayıp ACK, QR/Order kesintisi, süreç yeniden başlatma | Aynı kalıcı kimlikle sonuç öğrenilir; çift kapanış, yeni tahsilat veya kör yeniden açma olmaz |
| Güvenli tam iptal / ödeme belirsizliği / iade bekliyor | Önceki güvenli iptal süre kuralı korunur; belirsiz durumda otomatik serbest bırakma olmaz |
| SQL hatası ve kapanış sonrası temizlendi | Main/guest/masa birlikte rollback/commit; temizlik ayrı; eski işlem yeni main'e dokunmaz |
| V29/V30 geçişi ve JPA validate | Onaylı geçmiş/veri planı ve PostgreSQL testleriyle doğrulanır; reset/repair ile hata gizlenmez |

Bu tablo ortak E2E test planıdır. QR'ın yerel davranışları PostgreSQL 17, kontrollü Order yanıtı ve gerçek Flyway ile test edilir; bu testler gerçek Order gate'i veya Payment mali doğruluğu kanıtı değildir. Backend pre-push ve Sonar sonuçları görev raporuna gerçek koşular tamamlandığında kaydedilir; frontend bu görevde çalıştırılmaz.

## 9. Verdiğimiz kararların son özeti

| Karar | Kabul edilen davranış |
| --- | --- |
| K1 | Personel onayı olmadan otomatik normal kapanış |
| K2 | Gerçek siparişler tamamlanmış ve mali koşullar sağlanmış olacak |
| K3 | Siparişsiz guest ödeme beklenen kişi sayılmayacak; main ile kapanacak |
| K4 | MVP'de müşteri yalnız kendi guest siparişlerini ödeyecek |
| K5 | Order tokenı QR'a doğrulatıp gerçek oturum bağlamını alacak |
| K6 | Gerçek menü/sepet aktivitesi 15 dakikayı yenileyecek; polling yenilemeyecek |
| K7 | A seçildi: Order Payment'ı doğrulayıp QR'a uygunluk sağlayacak; QR sadeleşecek |
| K8 | Checkout ve kapanış hazırlığını Order kalıcı sıralayacak; önce kapanışsa yeni checkout 409 |
| K9 | Kesinti/kayıp yanıt aynı kalıcı kimlikle sorgulanıp tamamlanacak; tekrar ödeme veya kör yeniden açma olmayacak |

**Karar görüşmesi tamamlandı.** QR'ın yerel A uyarlaması yapıldı; gerçek Order/Payment/Auth bağlantıları, ortak E2E ve reviewer onayı ayrı işlerdir. Commit/push, PR/comment güncelleme, review talebi ve merge geliştirici tarafından yapılır.
