# RESTOFY — Web Design & Frontend Development Rules

> **Belge türü:** Kalıcı geliştirme ve tasarım kuralları. Bu belge bir yapılacaklar listesi, durum raporu veya özelliklerin tamamlandığına ilişkin kanıt değildir.
>
> **Kapsam:** Restofy'nin Next.js frontend'i; halka açık sayfalar, QR ile müşteri deneyimi, restoran operasyon ekranları ve yönetim arayüzleri.
>
> **İlgili belge:** `.agents/SECURITY.md` — güvenlik, kimlik doğrulama, yetkilendirme, veri sahipliği ve sır yönetimi konularında önceliklidir.

## 1. Temel ilkeler ve uygulama kapsamı

Restofy; restoranların QR menü, sipariş, mutfak, garson, ödeme ve yönetim süreçlerini bir araya getiren bir uygulamadır. Tasarım kararları yalnız görselliğe değil, **işlem doğruluğuna, kullanım hızına, erişilebilirliğe ve güvene** dayanmalıdır.

- **Tutarlılık:** Aynı anlamı taşıyan eylemler, alanlar, durumlar ve bileşenler tüm ekranlarda aynı şekilde davranmalıdır.
- **Açıklık:** Kullanıcı ne yapacağını, işlemin hangi aşamada olduğunu ve sonraki adımını kolayca anlamalıdır. Önemli bilgi süslemelere feda edilmez.
- **Mobile-first:** QR müşteri akışı öncelikle telefon ekranı ve dokunmatik kullanım düşünülerek tasarlanır. Operasyon panelleri ise gerçek kullanım cihazına göre ayrıca optimize edilir.
- **Accessibility by design:** Erişilebilirlik geliştirme sonrasında eklenen yama değil, ilk tasarım kararıdır. Hedef WCAG 2.2 AA'dır.
- **Privacy by design:** Özel sipariş, masa, ödeme, hesap ve restoran bilgileri metadata, URL, log, analitik ya da paylaşım önizlemesine taşınmaz.
- **Progressive enhancement:** Ağ, cihaz, ekran, hareket tercihi ve tarayıcı sınırlamaları dikkate alınır. Temel işlev yalnız animasyona veya hover'a bağımlı olmaz.
- **KISS / DRY / YAGNI:** İşlevi olmayan sayfa, sahte içerik, gereksiz paket, SEO şeması veya PWA altyapısı sadece bir standardı görünürde karşılamak için üretilmez.

Bu belgedeki koşullu kurallar, yalnız ilgili özellik veya kullanıcı yüzeyi mevcut olduğunda uygulanır. Örneğin video yoksa video oynatıcı yapılmaz; halka açık indekslenebilir sayfa yoksa göstermelik sitemap oluşturulmaz. Hukuki yükümlülükler iş modeli ve faaliyet bölgelerine göre değerlendirilir; bu belge hukuki danışmanlık yerine geçmez.

## 2. Kullanıcı yüzeylerini birbirinden ayır

Restofy'de her sayfa aynı erişim, SEO, tasarım ve oturum politikasına tabi değildir.

| Kullanıcı yüzeyi | Tasarım önceliği | Arama motoru politikası |
| --- | --- | --- |
| Restofy marka, tanıtım, iletişim, SSS ve varsa fiyatlandırma | Güven, açıklık, dönüşüm ve kurumsal bütünlük | İçerik gerçekten halka açıksa indekslenebilir |
| Restoranın genel menüsü | İştah açıcı sunum, ürün keşfi, hızlı okunabilirlik | Restoranın yayın kararına göre indekslenebilir |
| Masaya özel QR, sepet, sipariş ve ödeme | Hız, işlem güveni, düşük sürtünme | `noindex`; sitemap dışında |
| Garson ve mutfak ekranları | Hızlı fark edilebilir durum, büyük eylemler ve hata önleme | `noindex`; sitemap dışında |
| Restoran yönetimi ve platform admin | Veri yoğunluğu, rol bazlı işlem ve denetlenebilirlik | `noindex`; sitemap dışında |
| Giriş, kayıt, şifre ve hesap işlemleri | Güvenlik, anlaşılır formlar, oturum sürekliliği | Normalde `noindex`; sitemap dışında |

Sitenin genel kimliği restoran ürününe uygun olmalıdır; ancak kamuya açık tanıtım sayfalarının görsel dili, yoğun operasyon panellerinin kullanılabilirliğini zayıflatmamalıdır.

## 3. Görsel dil ve tasarım sistemi

### 3.1 Marka kimliği ve görsel hiyerarşi

- Restofy'nin tasarımı profesyonel, özgün ve restoran bağlamı net bir ürün kimliği taşımalıdır. Rastgele gradient'ler, gereksiz glow efektleri, birbiriyle ilgisiz ikonlar ve jenerik “AI dashboard” görünümü kullanılmaz.
- Renk, tipografi, boşluk, köşe yarıçapı, gölge, sınır ve katman kararları tasarım tokenları üzerinden yönetilir. Aynı değerler farklı ekranlarda bağımsız olarak kopyalanmaz.
- Sıcak ve iştah açıcı vurgular müşteri menüsünde kullanılabilir; operasyon ekranlarında okunabilirlik ve durum ayrımı estetikten önceliklidir. Renkler yalnız marka hissi için seçilir, iş anlamını tek başına taşımaz.
- Bir ekranın ilk bakışta ana başlığı, birincil eylemi ve en önemli içeriği anlaşılmalıdır. Çok sayıda eşit ağırlıklı CTA veya aynı anda açılan uyarılar oluşturulmaz.
- Yazı büyüklükleri, satır yüksekliği, satır uzunluğu ve kontrast okumayı kolaylaştırmalıdır. Farklı ekranlarda tutarlı başlık ve metin hiyerarşisi korunur.
- İkonlar anlaşılabilir etiketlerin yerini keyfî biçimde almamalıdır. Kritik eylemlerde ikon + metin tercih edilir; sadece ikonlu butonların erişilebilir adı bulunur.
- Kullanılan görseller performans ve lisans açısından uygun, gerçek içeriği temsil eden ve kaliteli görseller olmalıdır. Placeholder görseller kalıcı ürün içeriği olarak sunulmaz.

### 3.2 Düzen, bileşenler ve tema

- Tekrar eden buton, form alanı, modal, toast, tablo, sekme, kart ve navigasyon kalıpları ortak UI bileşenleriyle oluşturulur. Feature'a özgü iş mantığı ortak UI katmanına taşınmaz.
- Responsive düzenler telefon, tablet ve masaüstünde yatay taşma, üst üste binme, kesilen metin ve kullanılamayan kontroller üretmemelidir.
- Light/dark tema desteklendiğinde iki tema eşdeğer hiyerarşi, kontrast, odak ve durum görünürlüğü sağlamalıdır; yalnız renkleri ters çevirmek yeterli değildir. Seçim ve sistem tercihi tutarlı yönetilir.
- Navigasyonda aktif konum net görünür. Marka logosu uygun ana sayfaya götürür; iç sayfalarda bilgi mimarisine uygun breadcrumb kullanılır. Müşteri QR akışına gereksiz admin navigasyonu eklenmez.
- Animasyonlar kısa, amaçlı ve isteğe bağlı olmalıdır. `prefers-reduced-motion` dikkate alınır; yanıp sönen, dikkat dağıtan veya otomatik ses başlatan deneyimler üretilmez.

## 4. Sayfa davranışları ve durum yönetimi

Her veri getiren veya işlem başlatan ekranın davranışı başarılı yanıt kadar başarısızlık durumunda da tasarlanmalıdır.

- **Loading:** Kullanıcı bir işlem devam ederken bunu anlamalıdır. Uygun yerde loading göstergesi veya skeleton kullanılır; skeleton gerçek yerleşimi yanıltmamalıdır.
- **Empty:** Veri yoksa bunun sebebi ve uygun sonraki eylem açıklanır. “Boş ekran” bırakılmaz.
- **Success:** Sunucu sonucu kesinleşmeden başarılı sipariş, ödeme, kayıt veya güncelleme mesajı gösterilmez.
- **Error:** Kullanıcı anlayacağı dilde sorunun nedenini, mümkünse nasıl düzelteceğini ve güvenli tekrar deneme yolunu görür. Stack trace veya sunucu ayrıntıları gösterilmez.
- **Disabled:** Pasif kontrollerin nedeni mümkün olduğunda anlaşılır olmalıdır. Form yüklenirken çift gönderim engellenir.
- **Hover / Focus / Active:** Mouse, klavye ve dokunmatik durumları ayrı değerlendirilir. Hover ile sunulan bir bilgi yalnız hover'a bağlı kalmaz.
- **Confirmation / Undo:** Silme, iptal, iade ve yetki gibi etkisi yüksek eylemlerde açık onay veya güvenli geri alma sağlanır. Geri dönüşü olmayan eylem kullanıcıdan saklanmaz.
- **Offline / Timeout:** Bağlantı kesildiğinde ekran sessizce başarısız olmaz. Sunucu sonucunun bilinmediği mutasyonlar otomatik yeni bir istekle körlemesine tekrarlanmaz; sonuç sorgulanır veya güvenli yeniden deneme akışı kullanılır.

HTTP 401, 403, 404, 409, 429, 500, 503 gibi durumlar tek bir genel hata mesajına indirgenmemelidir. Giriş gerekliliği, yetki yokluğu, nesnenin bulunmaması, çakışma, hız sınırı ve servis kesintisi kullanıcı açısından farklı mesajlar gerektirir.

## 5. Formlar, doğrulama ve işlem geri bildirimi

- Her alanın erişilebilir etiketi, gerekiyorsa açıklaması ve beklenen formatı bulunur. Placeholder, label'ın tek alternatifi değildir.
- Gerekli alanlar, yalnız boşluk içeren değerler, uzunluk sınırları, veri türleri, sayısal aralıklar, e-posta/telefon/URL biçimi ve alanlar arası tutarlılık kontrol edilir.
- Hatalar ilgili alanla ilişkilendirilir, sadece renkle anlatılmaz ve ekran okuyucuya uygun biçimde duyurulur. Gönderimde ilk hataya odaklanma davranışı düşünülür.
- Form doğrulaması istemcide kullanıcıya yardımcı olur; backend doğrulaması ve yetkilendirmesi **daima nihai kaynaktır**. Sunucu doğrulama hatası doğru alana bağlanır.
- Kullanıcı düzeltebileceği bir hata aldıysa mevcut girdileri gereksiz yere silinmez. Gizli/parola/ödeme verileri ise güvenli yaşam döngüsüne göre ele alınır.
- Gönderim sırasında açık loading durumu ve tekrar gönderim koruması bulunur. Yalnız butonu devre dışı bırakmak çift sipariş/çift ödeme için yeterli değildir; backend idempotency kurallarına uyulur.
- Başarı ve hata toast'ları anlaşılır, uygun sürede görünür ve ekran okuyucu tarafından algılanabilir olmalıdır. Kritik bilgi yalnız geçici toast içinde bırakılmaz.
- İletişim/destek formu varsa spam koruması, veri işleme bilgilendirmesi, tekrar eden gönderim davranışı ve gerçek gönderim sonucu sağlanır. E-posta gönderimi başarısızsa başarı mesajı uydurulmaz.

## 6. Erişilebilirlik standardı

**Hedef WCAG 2.2 AA'dır.** Semantik HTML, gerçek kontrol davranışı ve manuel testler önceliklidir; ARIA yanlış HTML davranışını düzeltmek için genel çözüm olarak kullanılmaz.

### Görme ve ekran okuyucu

- Sayfalarda doğru `lang`, başlık hiyerarşisi, landmark'lar (`main`, `nav`, `header`, `footer`) ve içerik akışı bulunur.
- Anlam taşıyan görsellerin `alt` metni, dekoratif görsellerin uygun boş `alt` değeri kullanılır. Tablo ve grafiklerde başlık, ilişki ve gerekirse metinsel açıklama bulunur.
- Formlar, butonlar, ikonlu eylemler, dialoglar ve canlı sipariş bildirimleri erişilebilir adlara ve uygun `aria-live` davranışına sahip olur.
- Metin büyütme, zoom, reflow ve kontrast kontrolleri tasarım aşamasında düşünülür. Renk, ses, hareket veya yalnız simge tek bilgi kanalı olarak kullanılmaz.

### Klavye, motor erişim ve dokunma

- Tüm işlevler klavyeyle kullanılabilir, sekme sırası mantıklıdır, odak göstergesi belirgindir ve klavye tuzağı yoktur.
- Modal açılma/kapanma odak yönetimi doğru yapılır. Sayfa başında “İçeriğe geç” bağlantısı sağlanır.
- Dokunmatik hedefler yanlış dokunmayı azaltacak boyutta ve aralıkta olmalıdır. Sürükle-bırak işlemlerine alternatif eylem sunulur.
- Zaman sınırı içeren akışlarda kullanıcının bilgilendirilmesi, gerektiğinde süreyi uzatması ve verisini kaybetmemesi sağlanır; güvenlik açısından uzatılamayan sınırlar açıkça anlatılır.

### İşitme, bilişsel erişim ve hareket

- Video/ses içeriği varsa uygun altyazı, metin dökümü ve gerekiyorsa sesli betimleme sağlanır. Canlı yayın ve işaret dili ihtiyaçları ürün ve hukuki kapsam temelinde değerlendirilir.
- Sesli mutfak/garson bildirimi mutlaka görsel karşılık taşır; renkli durum etiketi de metinle desteklenir.
- Ekranlar tutarlı sözcükler, anlaşılır hata mesajları ve düzeltme yönlendirmeleri kullanır. Form hatası yapıldığında kullanıcının ilerleyebilmesi kolaylaştırılır.
- Otomatik dönen/hareket eden içerikler durdurulabilir, hareket azaltma sistem tercihi dikkate alınır ve yanıp sönme riski oluşturulmaz.

Erişilebilirlik değerlendirmesi yalnız Lighthouse veya axe sonucuna dayanamaz: klavye, gerçek mobil ekran okuyucu ve temel kullanıcı akışları manuel olarak denenir.

## 7. Restofy'ye özgü müşteri QR ve sipariş deneyimi

- QR açıldığında restoran/masa bağlamı backend tarafından doğrulanır. Geçersiz, kapalı, iptal edilmiş veya süresi bitmiş bağlantı için açıklayıcı bir ekran sunulur.
- Genel restoran menüsü ile **masaya özel sipariş yetkisi** ayrı kavramlardır. Halka açık menüyü görebilmek, başka masanın siparişini yönetme hakkı vermez.
- Menü ürününde uygun olduğunda fotoğraf, ad, açıklama, güncel fiyat/para birimi, opsiyonlar, stok/uygunluk ve işletmeden sağlanan alerjen bilgileri açık biçimde gösterilir. Yanlış veya doğrulanmamış alerjen bilgisi uydurulmaz.
- Ürün seçenekleri, adet, ara toplam, hizmet/vergilendirme bilgisi ve nihai tutar anlaşılır olmalıdır. Hesaplama ve stok kontrolü server-side doğrulanır; kullanıcıya değişen fiyat veya tükenen ürün açıklanır.
- Sepet, sipariş verme ve ödeme işlemlerinde çift gönderim engellenir. Ağ zaman aşımında “sipariş kesin başarısız” veya “ödendi” gibi doğrulanmamış hüküm verilmez.
- Sipariş durumu güncellemeleri anlaşılır dil ve ekran okuyucu uyumlu bildirimlerle gösterilir; yenileme, geri navigasyon ve bağlantı kopması sonucu veri karışması önlenir.
- Telefon için dikey akış, okunur fiyatlar, büyük ekleme/çıkarma butonları, erişilebilir ürün özelleştirme ve tek elle erişim tercih edilir. Yavaş mobil internet ve restoran Wi-Fi koşulları göz önünde bulundurulur.
- Müşteri oturumu, QR/masa yetkisi ve sipariş bilgisi farklı masalar, tarayıcı kullanıcıları veya restoranlar arasında yanlışlıkla paylaşılmaz. Ödeme sonucu yalnız backend/ödeme sağlayıcısı doğrulamasından sonra gösterilir.

## 8. Mutfak, garson, yönetim ve admin arayüzleri

- Mutfak ekranında yeni/işlemde/hazır/iptal durumları ilk bakışta ayırt edilir; metin ve zaman bilgisi renkleri tamamlar. Kritik iş akışı yoğun animasyon veya küçük kontrol hedefleriyle yavaşlatılmaz.
- Garson ekranında masa, servis durumu, ilgili sipariş ve zaman bilgileri hızlı erişilebilir olmalıdır. Gereksiz müşteri kişisel verisi gösterilmez.
- Yetkili kullanıcının hangi restoran bağlamında çalıştığı net görünür. Restoran değişimi sonrası eski restoranın kişisel verileri, önbelleği ve operasyon kayıtları ekrana taşınmaz.
- Eşzamanlı personel güncellemelerinde eski veri ve çakışan işlem kullanıcıya açıklanır. Başarılı/başarısız durum için sunucu yanıtı esastır.
- Stok değişimi, sipariş iptali, iade, kullanıcı/rol yönetimi ve ödeme onayı gibi kritik eylemler gerekçesine uygun confirmation, doğru yetki kontrolü ve audit gerektirebilir.
- Yönetim tabloları erişilebilir başlıklar, filtreleme, sıralama, pagination, boş/yükleniyor/hata durumlarıyla tasarlanır. Mobilde kullanılmaz hâle gelen geniş tablo yerine uygun alternatif sunulur.
- Admin veya personel ekranı URL'sinin saklanması güvenlik önlemi değildir. Backend oturum, rol ve restoran sahipliği denetimleri `SECURITY.md` uyarınca uygulanır.

## 9. Next.js 16 ve frontend kod mimarisi

- Mevcut **Next.js App Router + TypeScript + Tailwind + feature-based** düzeni korunur. İş mantığı `src/features/*` içinde, tekrar kullanılan sunum ve araç kodları `src/shared/*` içinde bulunur. Yeni bir klasör ağacı yalnız isim olarak standart görünmek için oluşturulmaz.
- `src/app` route, layout, metadata, loading/error boundary ve sayfa birleştirme katmanıdır; geniş restoran iş mantığı burada biriktirilmez.
- Server/Client Component sınırları bilinçli kullanılır. Gerekmedikçe bütün sayfalar `"use client"` yapılmaz; sunucu sırları hiçbir client bundle'a geçmez.
- Metinler TR/EN `next-intl` anahtarlarından alınır. UI metni komponentlere rastgele hard-code edilmez. Çeviri anahtarları anlamlı, eksiksiz ve eşdeğer olmalıdır.
- API çağrıları merkezi ve tutarlı hata/oturum/timeout davranışına sahip olur. Feature modülleri tarayıcı depolamasına gizli token yazmaz; sunucunun auth ve tenant sınırlarını atlayamaz.
- Route slug'ları kararlı, okunabilir ve benzersizdir; Türkçe karakter transliterasyonu/URL encoding kuralı tutarlı uygulanır. Tarayıcı geri-ileri, deep-link yenileme, query filtreleri ve 404/410 davranışları düşünülür.
- `not-found.tsx`, `error.tsx`, gerektiğinde `global-error.tsx` ve `loading.tsx` mevcut sayfa ihtiyaçlarına göre tanımlanır. Hata ekranlarında teknik exception veya sır gösterilmez.
- `favicon.ico` veya uygun icon dosyası ve sayfa bazlı Metadata API kullanılır. `manifest.ts`, sitemap veya özel image route'ları yalnız gerçek ürün ihtiyacı olduğunda eklenir.

## 10. SEO, GEO, URL ve sosyal paylaşım

### Public sayfa stratejisi

- SEO yalnız kamuya açık, **indekslenmesi istenen** sayfalarda uygulanır. Public tanıtım, SSS, iletişim ve işletme tercihine göre herkese açık menü sayfaları bu kapsamda olabilir.
- İndekslenebilir her sayfa özgün `title`, `description`, doğru canonical URL, anlamlı iç bağlantılar ve mevcut dil alternatifleri için uygun metadata taşır. Aynı başlık/açıklama bütün sayfalara körlemesine kopyalanmaz.
- Next.js Metadata API ile `robots.ts` üzerinden `/robots.txt` yönetilir. QR masa, sepet, sipariş, ödeme, mutfak, garson, admin ve hesap işlemleri `noindex` politikası altında tutulur.
- **`robots.txt` erişim kontrolü değildir.** Ayrıca `Disallow` ile taramayı engellemek, botun sayfadaki `noindex` direktifini görmesini de engelleyebilir. `noindex`, güvenli `X-Robots-Tag`/metadata ve gerçek backend erişim denetimi kendi amaçlarına göre uygulanır.
- `sitemap.ts` yalnız gerçekten indekslenecek public sayfalar bulunduğunda oluşturulur. Sitemap özel/oturum/QR token'lı URL içermez; canonical HTTPS adresleri kullanır. Search Console tanıtımı gerçek yayın ve alan adı hazır olduğunda yapılır.
- TR/EN `hreflang` yalnız gerçekten erişilebilir, karşılıklı ve ayrı dil URL'leri varsa kullanılır. Sadece cookie tabanlı locale geçişi, uydurma `/en` ve `/tr` sayfaları oluşturmaya gerekçe değildir.
- JSON-LD (`Restaurant`, `Menu`, uygun `FAQPage` vb.) yalnız doğrulanmış, gerçek, görünür içerikle tutarlı veri varsa eklenir. Zengin sonuç gösterileceği vaat edilmez; yorum, yıldız, fiyat veya işletme bilgisi uydurulmaz.
- GEO/AI keşfedilebilirliği için içeriğin açık, doğru, kaynaklanabilir ve semantik olması hedeflenir. `llms.txt` yayımlanacaksa yalnız kamuya açık içerik taşımalı; zorunlu standart veya indeksleme garantisi olarak sunulmamalıdır.

### Routing ve paylaşım

- URL/slug adlandırma, büyük-küçük harf, trailing slash ve yönlendirme politikası proje boyunca tutarlı olmalıdır. 301/308 kalıcı; 302/307 geçici durumlarda doğru kullanılmalıdır.
- Yetkisiz veya bulunmayan özel nesnelerin URL çözümlemesi bilgiyi sızdırmamalıdır. Open redirect ve kontrolsüz dış bağlantılar engellenir.
- Public sayfalarda OpenGraph (`og:title`, `og:description`, `og:image`, `og:url`, `og:type`, `og:site_name`, uygun `og:locale`) ve X/Twitter kart bilgileri doğrulanmış public içeriğe dayanır.
- OG görselleri uygun boyut ve formatta, mutlak HTTPS adresiyle sunulur. QR masa anahtarı, ödeme işlemi, özel sipariş veya müşteri bilgileri önizleme görsellerine/metadatalara girmez.
- Yayına çıktıktan sonra arama, sosyal paylaşım ve tarayıcı önizlemeleri gerçek URL'lerle doğrulanır.

## 11. Footer, kurumsal içerik ve destek

- Halka açık tanıtım/kurumsal sayfalarda marka ve telif bilgisi, Hakkımızda, SSS, iletişim/destek, gerekli yasal metinler ve uygun sosyal/ekip bağlantıları erişilebilir bir footer yapısında sunulur.
- Açık kaynak lisansı, kaynak kod bağlantısı ve sürüm bilgisi varsa doğru ve güncel gösterilir; olmayan lisans ya da sosyal hesap uydurulmaz.
- Gizlilik, KVKK aydınlatma, kullanım koşulları, çerez politikası, tercih güncelleme ve erişilebilirlik bildirimi ilgili yasal/ürün kapsamına göre kolay bulunabilir olmalıdır.
- Personelin yoğun kullandığı mutfak ve garson ekranlarında kurumsal footer için iş akışı alanı harcanmaz; gerekli hukuki ve hesap bağlantılarına uygun alternatif menüyle erişilir.
- Destek kanalı erişilebilir, açık ve işe yarar olmalıdır. İletişim formu kullanılıyorsa güvenli e-posta altyapısı, kötüye kullanım koruması ve veri kullanım açıklaması gerektirir.

## 12. Hukuki uyum, çerezler ve veri mahremiyeti

- Restofy'nin platform ve restoran işletmecisi rolleri bakımından veri sorumlusu/veri işleyen ilişkisi gerçek hizmet modeline göre değerlendirilir; otomatik varsayılmaz.
- Gerekli KVKK aydınlatması; veri sorumlusu, işleme amacı, hukuki sebep, aktarım, saklama ve ilgili kişi başvuru yollarını doğru anlatır. Gizlilik ve kullanım koşulları tutarlı, erişilebilir ve günceldir.
- Açık rıza gereken durumlarda aydınlatmadan ayrı, özgür ve geri alınabilir olur. Ret seçeneği gizlenmez veya zorlaştırılmaz; yanıltıcı onay tasarımı kullanılmaz.
- Çerezler amaç ve kategori bazında envanterlenir. Zorunlu oturum cookie'leri ile isteğe bağlı analitik/pazarlama cookie'leri ayrılır. Hukuken onay gerektiren teknolojiler izin öncesinde başlatılmaz ve tercih sonradan değiştirilebilir.
- Gerçekte kullanılmayan analitik/pazarlama sistemi için sahte consent banner üretilmez. Tarayıcı depolaması ve üçüncü taraf SDK'ler de veri mahremiyeti açısından değerlendirilir.
- Saklama ve imha, veri minimizasyonu, yurt dışı veri aktarımı, ihlal müdahalesi ve başvuru süreçleri hizmette işlenen gerçek verilere göre planlanır.
- ETBİS, mesafeli satış/ön bilgilendirme, iptal-iade, ticari elektronik ileti, GDPR/VERBİS ve ödeme mevzuatı otomatik olarak her restoran senaryosuna uygulanmış varsayılmaz; yetkili kişilerce faaliyet kapsamı üzerinden değerlendirilir.
- Görsel, yazı tipi, logo, yemek görseli, kullanıcı içeriği ve üçüncü taraf paketlerin lisans/telif koşulları korunur.

## 13. Oturum, güvenlik ve özel veri gösterimi

- Giriş/kayıt, şifre sıfırlama, e-posta doğrulama, oturum süresi, çıkış ve varsa MFA/2FA akışları gerçek Auth Service sözleşmesine uygun olmalıdır; uygulanmamış auth davranışı varmış gibi UI'da gösterilmez.
- Frontend rol bazlı görünürlük yalnız kullanıcı deneyimidir; her özel endpoint, nesne ve restoran kapsamı backend'de doğrulanır. Bir kullanıcının başka restorana/masaya ait ID'yi bilmesi erişim sağlamamalıdır.
- Auth tokenları `localStorage`, `sessionStorage` veya IndexedDB içinde kalıcı tutulmaz; kabul edilmiş güvenli cookie yaklaşımı `SECURITY.md` ile tutarlı uygulanır. Cookie tercihleri HTTPS, `HttpOnly`, `Secure`, uygun `SameSite`, CSRF ve domain/path kararlarıyla birlikte ele alınır.
- Kullanıcıya ait içerik React tarafından metin olarak escape edilir; kullanıcı kaynaklı HTML keyfî biçimde render edilmez. Injection, open redirect ve dış URL güvenliği backend/frontend sınırlarında doğrulanır.
- CORS allowlist, CSRF, rate limit, CSP, güvenlik header'ları, audit, dosya yükleme, sır yönetimi ve mikroservis/DB izolasyonu ayrıntıları için `.agents/SECURITY.md` zorunludur. Bu belgede bir çelişki oluşursa güvenli sınır gevşetilmeden karar istenir.
- Özel sipariş, müşteri, personel ve ödeme verileri uygun `Cache-Control` politikalarıyla korunur. Kullanıcı/masa/restoran değişiminde eski hesap veya tenant'ın önbellekte kalmış verileri yeni kullanıcıya gösterilmez.

## 14. Çoklu dil ve yerelleştirme

- `next-intl` üzerinden TR/EN desteği tutarlı tutulur. Başlık, hata, validation, form, navigasyon, boş durum, bildirim ve metadata metinleri eşdeğer çevirilere sahip olur.
- `html lang` gerçek aktif dile uygun olmalıdır. Dil seçimi cookie ile yönetiliyorsa mevcut yaklaşım korunur; URL tabanlı locale routing ancak açık ürün kararıyla eklenir.
- Tarih, saat, sayı, para birimi, ondalık ayracı ve varsa vergi fiyatı ilgili locale ve iş kurallarına uygun biçimlendirilir. Sipariş verisi biçimlendirmesi hesabın kendisini değiştirmez.
- Eksik çeviriler anlaşılır fallback ile yönetilir; production ekranında ham çeviri anahtarı görünmez.
- Dil değiştirme işleminde kullanıcı makul ölçüde aynı sayfa ve iş bağlamında kalır; masa/sipariş oturumunu bozacak, yetkisini aşacak yönlendirme yapılmaz.

## 15. Performans ve teknik kalite

- Public sayfalarda Core Web Vitals ve Lighthouse sonuçları takip edilir; müşteri sipariş akışında ise ölçülen etkileşim hızı, düşük ağ kalitesi ve gerçek cihaz performansı önceliklidir.
- Next.js görüntü optimizasyonu, uygun responsive boyutlar, lazy loading, font optimizasyonu ve gerekli kadar JavaScript kullanımı tercih edilir.
- İlk ekranda gerekli sipariş/menü içeriği geç yüklenmemeli; ağır admin raporları ve sekonder paneller uygun code splitting ile ertelenebilir.
- Büyük listelerde pagination veya uygun sanallaştırma; arama/filtrelerde makul debounce ve erişilebilir geribildirim kullanılır.
- Cache stratejisi veri türüne göre seçilir: public/statik marka içeriği ile anlık sipariş, stok, ödeme ve özel kullanıcı verileri aynı cache politikasını paylaşmaz.
- API timeout, iptal/yeniden deneme ve beklenmeyen ağ kopması davranışları tanımlı olmalıdır. Çift sipariş/ödeme riski taşıyan istekler otomatik yeniden gönderilmez.
- Bundle büyüklüğü, gereksiz paketler, kaynak haritalarının production ifşası, Debug ve yüksek bellek tüketimi gözden geçirilir.

## 16. PWA, çevrimdışı ve analitik özellikleri

- PWA zorunlu değildir. Kurulacaksa `manifest.ts`, uygun ikonlar, service worker, cache yaşam döngüsü, güncelleme ve offline ekran davranışları birlikte tasarlanır.
- Offline görünüm yalnız güvenle önbelleğe alınabilen içerik için kullanılabilir. Sipariş oluşturma, ödeme veya stok güncelleme işlemi ağ yokken gerçekten tamamlanmış gibi sunulmaz.
- Analitik gerekiyorsa asgari ve amaca uygun olaylar ölçülür. Oturum/QR tokenı, masa kimliği, kişisel veri, ödeme ayrıntısı ve özel URL'ler analitik servise gönderilmez.
- Analitik SDK'ler varsa izin gereklilikleri, ön izin, opt-out ve iptal sonrası takip davranışı doğrulanır. Admin analytics ekranı yalnız yetkili kişilere açıktır ve gizliliği ihlal edecek hassas ayrıntıları sunmaz.

## 17. Test ve kabul kuralları

- Değişen özelliğe uygun unit, integration ve E2E testleri yazılır. Testler gerçek iş sözleşmesini doğrulamalı, yalnız var olan bir fonksiyonun çağrılmasını PASS saymamalıdır.
- Kritik akışlar; QR açma, menü/opsiyon, sepete ekleme, sipariş gönderimi, durum izleme, personel geçişleri, ödeme sonucu, oturum yetkisi ve tenant izolasyonudur. Bunların başarılı ve hata senaryoları ayrıca test edilir.
- Tarayıcı uyumluluğu, gerçek mobil cihaz, zoom/reflow, klavye, ekran okuyucu, otomatik erişilebilirlik taraması, yavaş ağ ve offline davranışı özellik gerektirdikçe doğrulanır.
- Public sayfalarda kırık bağlantı, metadata, OG görsel, robots/sitemap, canonical, dil eşdeğerliği ve arama motoru paylaşım davranışı gerçek URL'lerle kontrol edilir.
- Günlük yerel kalite kapısı repository'deki `pre-push/` sistemidir. SonarQube **bağımsız ve isteğe bağlıdır**; kullanmayan geliştiricinin pre-push'ı Sonar tokenı veya Sonar sunucusuna bağımlı olamaz.
- Kod kalitesi için ESLint, TypeScript, frontend testleri ve Next.js build çıktıları kullanılır; bağımlılık güvenliği kontrolü de uygulanır. Çalıştırılmayan test başarılı raporlanmaz.
- Erişilebilirlik, performans veya güvenlik bulgusu yalnız lint/test sonucu başarılı diye göz ardı edilmez; gerçek kullanıcı işlevselliği esastır.

## 18. Yayın, bakım ve operasyon

- Production domain, HTTPS, environment variable ve secret yönetimi onaylanmadan canlıya çıkılmaz. `NEXT_PUBLIC_*` değerleri herkes tarafından okunabilir kabul edilir; secret içermez.
- CI/CD, ortam ayrımı, deploy sonrası smoke testleri, yedek/geri yükleme, migration, rollback, izleme, alarm ve olay müdahalesi gerçek deployment tasarımına uygun olmalıdır.
- SSL/domain yenileme, güncelleme ve bakım sorumlulukları tanımlı olmalıdır. Production admin/API erişim sınırları ve logların kişisel veri barındırmaması kontrol edilir.
- Yayın sonrası karşılaşılan erişilebilirlik, güvenlik, SEO, performans veya ödeme hataları kullanıcı etkisine göre önceliklendirilir.

## 19. Kodlama ajanları için davranış kuralları

1. Göreve başlamadan önce ilgili mevcut kodu ve `.agents/SECURITY.md` dosyasını oku. Bu belge, mevcut uygulanmış özelliklerin yerine geçen bir varsayım listesi değildir.
2. Görev kapsamına giren kuralları **kod ve test kararına dönüştür**. İstenmeyen sayfa, yapılandırma veya entegrasyonu yalnızca “tam görünmek” için oluşturma.
3. Mevcut `Next.js`, `next-intl`, feature-based yapı, Gateway/API sözleşmeleri ve veri sahipliği kararlarını koru. Mimari değişiklik gerekiyorsa önce gerekçesini bildir ve onay iste.
4. Güvenlik için backend kuralları geçerlidir. İstemci kontrolü, SEO `noindex` veya görünmeyen admin linki erişim kontrolü sayılmaz.
5. Yeni ENV anahtarı, gizli değer, dış servis, dependency, auth/tenant/ödeme/QR yetki mimarisi değişikliği öncesinde `SECURITY.md` kapsamındaki onay gereksinimini uygula.
6. Her geliştirmede **ne değiştiğini, neden değiştiğini, hangi testlerin gerçekten çalıştığını ve bilinçli olarak ertelenen durumları** kısa ve dürüst biçimde raporla. Bu belgeyi checkbox durum tablosuna dönüştürme.
7. Açık yetki verilmeden `git commit`, `git push`, production deploy, veri silme, veritabanı `clean` veya volume kaldırma işlemi yapma.

---

**Kuralın özü:** Restofy frontend'inde estetik, performans, erişilebilirlik, SEO ve hukuki uyum birlikte düşünülür; ancak hiçbir görsel veya geliştirme kolaylığı güvenliği, sipariş doğruluğunu veya restoranlar arası veri izolasyonunu zayıflatamaz.
