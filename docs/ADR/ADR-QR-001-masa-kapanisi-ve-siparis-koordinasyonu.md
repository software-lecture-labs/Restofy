# ADR-QR-001 — Masa kapanışı, sipariş kabulü ve finansal güvenlik sınırı

**Durum: ÖNERİ / KARAR BEKLİYOR — kabul edilmiş mimari karar değildir.**

**Tarih:** 29 Ekim 2026.

**Karara katılacaklar:** QR, Order ve Payment sorumluları; review geri bildirimiyle birlikte.

**Kaynak:** [PR #11 review](https://github.com/software-lecture-labs/Restofy/pull/11#pullrequestreview-5695894632).

Bu kayıt görüşme sorularını ve mevcut uygulamayı görünür kılar. Toplantı yapılmış, servis sahipleri onay vermiş veya alternatiflerden biri seçilmiş değildir. **Ortak karar sonrasında koddaki aktif yaklaşım ve entegrasyon rehberi güncellenecektir; bu dokümanla mevcut iş kodu/migration değiştirilmemiştir.**

## Bağlam ve mevcut kaynak

- [Mimari](../architecture.md) §3: Order sipariş kabulü/iş durumunun, Payment doğrulanmış tahsilatın, QR masa ve oturum/kapanışın sahibidir.
- Aynı belgenin §5.1'i kapanış ile sipariş kabulü yarışına karşı QR admission rezervasyonunu **öneri** olarak anlatır. Belgedeki öneri tek başına ekipçe kabul edilmiş protokol değildir.
- [V29](../../backend/services/qr-service/src/main/resources/db/migration/V29__create_order_admissions.sql): `order_admissions`; immutable komut, guest/main/order referansı, key/digest/deadline/fence ve kabul/ret durumu.
- [V30](../../backend/services/qr-service/src/main/resources/db/migration/V30__create_order_business_outcomes.sql): sıralı iş durumu/finansal güvenlik projeksiyonu ve tekrar güvenliği kayıtları.
- QR içindeki `SETTLED`, `CANCELLATION_CLEAR`, `RECONCILIATION_REQUIRED` alanları Payment tahsilat defteri değildir; kapanış/idle için yerel kanıt/projeksiyon amacı taşır. Yine de Order/Payment iç kurallarına bağımlılık ve birden çok doğruluk kaynağı izlenimi yaratabilir.
- Gerçek Order/Payment transport'u ve olumlu kaynak adaptörleri henüz yoktur. Üretim sınırları fail-closed; olumlu kanıtlar kontrollü testlerde bulunur. Personel/guest HTTP API'leri entegrasyona kadar kapalıdır.

## Korunması gereken iş kuralları

1. Siparişin kabul kararı Order'da, ödeme/tahsilat doğruluğu Payment'ta kalır; QR bu kayıtların ikinci iş otoritesi olmaz.
2. Kontrol ile kapanış arasına yeni siparişin girmesi, kayıp confirm veya belirsiz ödeme yüzünden güvenli olmayan masa kapanışı oluşmaz.
3. Eksik/gecikmiş bilgi veya servis timeout'ı “sipariş yok / ödeme tamam” kabul edilmez.
4. Ödeme tek başına kapatmaz; aynı restoranın doğrulanmış personel onayı gerekir.
5. Kapanış QR'ın yerel transaction'ında main, aktif guest'ler ve masa durumunu birlikte günceller. Uzak Order/Payment transaction'ı bununla atomik değildir.
6. Guest başına sipariş koruması ve güvenli iptal sonrası 15 dakika kuralları seçilecek minimal modelde nasıl korunacağıyla birlikte ele alınır.

## Masaya konacak alternatifler — henüz seçim yok

| Seçenek | Kazanç | Yanıtlanması gereken risk/maliyet |
| --- | --- | --- |
| A — Kapanışta Order'a yetkili sorgu | QR'da daha az sipariş/finans durumu; kapanış uygunluğu kaynak servisten alınır | Tek GET'ten sonra Order commit edebilir mi? Yeni siparişleri engelleyen kapanış koordinasyonu/fence kimin sorumluluğunda? Order/Payment kesintisinde nasıl uzlaştırılır? |
| B — Order olaylarından küçük açık-sipariş projeksiyonu | QR yalnız gerekli açık/engelleyici iş bilgisini bilir; ayrıntılı finans kopyası azalabilir | Eksik/sırasız/tekrarlı olay, kaynak sürümü, tazelik ve yeniden senkronizasyon nasıl yönetilir? PENDING kabul/ödeme belirsizliği ve guest koruması nasıl temsil edilir? |
| C — Mevcut admission + ayrıntılı projeksiyon | Kabul/kapanış yarışları, kayıp ACK ve guest koruması için mevcut yerel altyapı kullanılabilir | Üç servis arasındaki bağımlılık kabul edilebilir mi? Hangi alanlar çıkarılabilir? Kalıcı karar/fence/son tarih sözleşmesi taraflarca uygulanabilir mi? |

Bu tablo yeni API, broker veya ortak domain kütüphanesi kurma kararı değildir. Salt `canClose=true` yanıtı veya gecikebilen bir sayaç, yarış/belirsizlik çözülmeden güvenli kapanış garantisi sağlamaz.

## Görüşmede yanıtlanacak sorular

- QR'ın bilmesi gereken en küçük bilgi nedir: açık sipariş sayısı mı, kapanışı engelleyen işler mi, kaynak tarafından sürümlenmiş kapanış uygunluğu mu?
- Finansal ayrıntı QR'a taşınmadan Order, Payment sonucunu doğrulayıp QR'a minimal güvenilir kapanış sonucu sağlayabilir mi?
- Kapanış kararı verilirken devam eden veya henüz görünmeyen sipariş kabulü nasıl engellenecek? Son tarihten sonra gelen kalıcı kabul nasıl uzlaştırılacak?
- Order commit'i başarılı, QR teyidi kayıp olduğunda hangi servis hangi kalıcı kimlik/receipt ile sonucu sorgulayacak?
- Olay tercih edilirse olay sahibi/teslim garantisi, aggregate sırası, idempotency ve eksik olayın fark edilmesi nasıl tanımlanacak? Mevcut broker/dispatcher varmış gibi davranılmayacak.
- Order/Payment erişilemezse veya kapsam eksikse kapanış ne kadar süre bekleyecek; operasyonel uzlaştırmayı kim yapacak?
- Main ve sipariş veren guest korumasını, güvenli tam iptal sonrası süreyi hangi minimal sözleşme güncelleyecek?
- Sipariş kabulü ↔ kapanış, ödeme ↔ iptal ve kayıp yanıt yarışlarının ortak kabul testleri nelerdir?
- Hangi servis yalnız mantıksal referans/projeksiyon tutacak, hangi servis hangi durumu değiştirmeye yetkili olacak?
- Mevcut V29/V30 verileri ve eski kayıtlar, farklı bir seçenek kabul edilirse nasıl güvenli taşınacak?

## Karardan sonra doldurulacak alanlar

- **Seçilen seçenek / gerekçe:** Henüz belirlenmedi.
- **Reddedilen alternatifler / neden:** Henüz belirlenmedi.
- **Order, Payment, QR sözleşmeleri ve sahipleri:** Henüz belirlenmedi.
- **Hata/uzlaştırma ve eşzamanlılık kabul testleri:** Henüz belirlenmedi.
- **Katılımcılar, toplantı ve onay tarihi:** Henüz belirlenmedi.
- **Uygulama PR'ları ve veri geçiş planı:** Karardan sonra ayrı küçük PR'larla tanımlanacak.

Uygulanmış V29/V30 dosyaları silinmez veya yeniden yazılmaz. QR'a ayrılan V21–V30 aralığı doludur; yeni migration gerekirse [sürüm dağılımı](../decisions/migrations.md) uyarınca ekipten yeni aralık kararı alınır. V31 Payment'a aittir. Bu kayıtta reset, clean, repair veya volume silme önerilmez.

## İlgili sonraki işler

- `dev` merge'inden sonra Auth personel JWT'si ve `TableManagementAccess` entegrasyonunu küçük bir PR ile başlatmak.
- 19 endpoint için zorunlu olarak 19 PR açmak yerine ortak yetki/iş akışlarına göre küçük gruplama önerisini reviewer ile netleştirmek. İlk öneri masa listeleme/detay; yönetim, QR yönetimi ve lifecycle ayrı inceleme dilimleri olabilir. **Bu gruplama henüz onaylı izin matrisi değildir.**
- Auth bağlantısından sonra müşteri tarama akışının hız sınırını geliştirmek; rate limit ve guest güvenliği hazır olmadan tarama endpoint'lerini açmamak.
- Bundan sonraki dört ana task/faz için ayrı PR'lar kullanmak; gerekirse büyük bir fazı ayrıca anlamlı küçük parçalara bölmek.

İlgili rehber: [QR entegrasyonu](../qr/entegrasyon-rehberi.md). Auth açılışı, rate limit ve mimari alternatifler bu dokümanla uygulanmış sayılmaz.
