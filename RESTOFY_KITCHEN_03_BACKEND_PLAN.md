# RESTOFY — KITCHEN-03 | ÜRÜN STOK VE SATIŞ ERİŞİLEBİLİRLİĞİ — BACKEND AI UYGULAMA PLANI

> **Bağımsız, doğrudan kodlama ajanına verilecek uygulama talimatı**  
> **Faz:** 3/4 · **Ana task:** 4 · **İzlenebilir alt adım:** 16  
> **Kapsam:** Yalnız Kitchen Service'in ürün erişilebilirliği operasyonları, Menu Service ile gelecekteki entegrasyon sınırı, V1 manuel fire/israf ve temel gün sonu mutfak raporu.  
> **Faz bitişi:** Kapsamlı test ve rapor; kullanıcı onayı olmadan KITCHEN-04'e geçme.  
> **MUTLAK YASAK:** `git add`, `git commit`, `git push`, `git merge`, `git rebase`, `git tag`, release veya deploy YAPMA. **Bu ve diğer AI uygulama/plan Markdown (`.md`) dosyaları asla GitHub'a gitmeyecek; Git staging'e kesinlikle alınmayacak.**

---

## 0. AJAN ROLÜ, ÖNCELİKLER VE BAŞLANGIÇ KOŞULLARI

Sen Restofy mikroservis projesinde **Kitchen Service backend** geliştirmesini yapan yazılım ajanısın. Yalnız **KITCHEN-03** fazını uygula. Mevcut repoyu ve tamamlanan KITCHEN-01/02 çalışmalarını koru; metindeki geçmiş gözlemleri kodu görmeden doğrulanmış gerçek sayma.

### 0.1 Kaynak/karar önceliği

1. Kullanıcının bu faza ait açık talimatları ve açıkça onayladığı kararlar.
2. `docs/gereksinimler/Restofy_Nihai_Gereksinimler.docx` içindeki normatif **Kitchen Service V1** kararları.
3. `docs/SECURITY.md` güvenlik standardı: güvenlik hiçbir gerekçeyle gevşetilemez.
4. `docs/decisions/migrations.md` migration sahipliği ve sürüm aralıkları.
5. Varsa gerçek repodaki `architecture.md`, `database.md`, `er-diagram.md`, `requirements.md`, `folder-structure.md` ve Kitchen-02 sözleşmesi. Bunlardaki öneriler uygulanmış olgu değil, referanstır.
6. Önceki fazların çalışan kodu, testleri, uygulanmış migration geçmişi ve doğrulanmış API sözleşmeleri.

Çelişki varsa sessiz varsayım üretme: normatif V1 kuralını koru, açık mimari kararı ayrı raporla. Gerçek servis/DB özellikleri, teknoloji sürümleri ve yollar repo üzerinden teyit edilir.

### 0.2 Başlangıç keşfi — mevcut kodu koru

- `git status --short`, `git diff`, migration listesi ve servis ağaçlarını **salt okunur** incele; başka geliştiricinin commit edilmemiş değişikliğine dokunma.
- `backend/services/kitchen-service` içinde controller, DTO, entity, repository, service, security, exception, test, Flyway ve `docs/kitchen-02-contract.md` dahil ilgili dosyaları incele.
- Java 25 / Spring Boot 4.1.1 / PostgreSQL 17 / Flyway / JPA validate beklentisini gerçek `pom.xml` ve yapılandırmadan doğrula; sürüm düşürme veya dependency upgrade yapma.
- KITCHEN-02 çıktısında bildirilen `V81`, `V82` (command identity), `V83` (outbox), 168 Kitchen / 265 backend başarılı test ve `KitchenOrderReady` kayıtlarının durumunu **ön bilgi** kabul et; canlı ortamda doğrulanmamış şeyleri doğrulanmış gösterme.
- Mutfak hazırlık (`WAITING → PREPARING → READY`), strict idempotency, `Idempotency-Key`, security / tenant sınırı ve outbox mekanizmasını bozma. KITCHEN-02 outbox'ını Menu entegrasyonu için körlemesine genişletme.
- Servislerin birbirinden bağımsız geliştirildiği aşamadasınız. Menu henüz uyumlu endpoint sunmuyorsa hedef API'yi uydurma; sözleşmeyi/portu netleştir, **gerçek senkronizasyonu BLOCKED/NOT VERIFIED** olarak bildir.

### 0.3 Değişiklik sınırı

**İzinli:** Yalnız Kitchen Service uygulama kodu, Kitchen testleri, Kitchen'e ait yeni Flyway migration'ları ve gerekliyse Kitchen'e ait **proje içi teknik API sözleşmesi**; mevcut paylaşılmış dosyalara onaysız dokunma.

**Yasak:** Başka servis kodu/DB'si, Gateway, frontend, Auth, Menu, Notification, Order, ortak `pom.xml`, Compose, pre-push, root `.env*` veya ortak ENV sözleşmesini açık izin almadan değiştirmek; Flyway `clean/repair/baseline`, DB reset/volume silme, uygulanmış migration'ı değiştirmek; test bypass, `permitAll`, coverage azaltma.

### 0.4 MARKDOWN / GITHUB YASAĞI — ÇOK ÖNEMLİ

**Bu talimat her adımda, her taskta ve sonraki fazlara aktarılacak güvenlik/iş akışı kuralıdır:**

1. **`RESTOFY_KITCHEN_03_BACKEND_PLAN.md` ve önceki/sonraki `RESTOFY_KITCHEN_*_BACKEND_PLAN.md`, `RESTOFY_KITCHEN_SERVICE_BACKEND_MASTER_PLAN.md` gibi AI'ya verilen görev ve plan Markdownları hiçbir koşulda GitHub'a gönderilmeyecek.**
2. Bu plan dosyalarını **asla `git add` ile stage etme; `git commit`, `git push`, pull request/merge, otomatik upload ya da paylaşım aksiyonu gerçekleştirme**. Kullanıcı bu dosyalar için sonradan commit istemedikçe varsayılan kalıcı yasak geçerlidir; bu fazın talimatı gereği **hiçbir commit/push zaten yapılamaz**.
3. **Tüm `.md` dosyalarını körlemesine silme veya genel `.gitignore` kuralıyla dışlama**: projede zaten sürüm kontrollü `README.md`, `docs/SECURITY.md` gibi meşru dokümantasyon vardır. Koruma özellikle AI çalışma/plan Markdown dosyalarını hedefler.
4. Plan `.md` dosyaları mümkünse **repository dışında** tutulur. Repository içine önceden bırakılmışsa salt okunur `git status --short` ve gerekiyorsa `git ls-files` ile izlenme durumunu raporla; hiçbir Git indeks/izleme değişikliği yapma. `.gitignore`/`.git/info/exclude` değişikliği gerekirse öneri sun, açık onay olmadan düzenleme yapma.
5. Zorunlu teknik `docs/kitchen-03-contract.md` gibi yeni Markdown hazırlanırsa, kullanıcı istemedikçe **bu da Git'e alınmayacak**. Önceden takip edilen belgeye ilişkin değişiklik varsa raporla; yine Git komutu ile yazma yapma. **Hiçbir `.md` dosyasını sen GitHub'a çıkarma.**
6. Faz sonu raporunda özellikle `Markdown/GitHub aktarımı: YAPILMADI`, `git add/commit/push/merge: YAPILMADI` satırlarını göster. Bu, kullanıcıya sonradan yaptığı manuel işlemlerde de AI plan Markdownlarını hariç tutması gerektiğini hatırlatır.

---

## 1. PROJE SINIRLARI VE KITCHEN-03'ÜN AMACI

Restofy; masa QR, menü, sipariş, mutfak, garson ve bildirim alanlarından oluşan çok restoranlı 12 iş servisli projedir. Kitchen Service yalnızca **mutfak operasyonunun** sahibidir; ürün kataloğu, yayın durumu ve satışa uygunluk gerçeğinin yazarı **Menu Service** olarak tasarlanır.

| Alan | Yetkili servis | Kitchen-03'ün sorumluluğu |
|---|---|---|
| Ürün ID, isim, kategori, yayın ve satışa uygunluk | Menu | Ürünü stokta yok / geçici kapalı / yeniden açık yapmak için **yetkili komut** üretmek; kesin sonucu yalnız Menu onayından sonra göstermek |
| Mutfak hazırlığı, `READY` zamanı | Kitchen | Faz 1–2 davranışını değiştirmemek |
| Sipariş kabulü, fiyat, sipariş iptali | Order | Geçmiş siparişleri ürün kapatılması nedeniyle geriye dönük iptal etmemek |
| Menüde müşteriye gösterim | Menu | Bir ürünü satışa kapatma kararı sonrası Menu'de doğrulanmış değişiklik; Kitchen menüyü kendi DB'sinden değiştirmez |
| Fire ve israfın manuel operasyon kaydı | Kitchen | Ürün/adet/neden/açıklama/zaman/aktör; otomatik hammadde düşümü **yok** |
| Gün sonu mutfak sayımları | Kitchen | Kitchen'da tutulan olay ve durumlar üzerinden restoran/tarih bazlı sayısal rapor |
| Kimlik/rol/restaurant üyeliği | Auth | Güvenilir doğrulanmış principal, rol ve tenant sınırı; Auth yoksa fail closed |

### 1.1 Kesin iş kuralları

- V1 **gerçek stok miktarı, hammadde, reçete, otomatik malzeme düşümü, satın alma** kapsamı değildir. Buradaki "stok" ürünün **satışa erişilebilirliği** anlamındadır.
- Ürün statüleri önerilen `AVAILABLE`, `OUT_OF_STOCK`, `TEMPORARILY_CLOSED` değerleridir. Gerçek Menu kontratı incelenmeden bu isimleri ortak kesin API olarak zorlamayın.
- Ürün `OUT_OF_STOCK` veya `TEMPORARILY_CLOSED` yapılırken **neden ve değişiklik zamanı** kaydedilmeli; yeniden açma yalnız yetkili komutla olmalı.
- Restoran/sistem kapanıp tekrar açıldığında **kapalı ürünler otomatik açılmaz**. Kaydedilmiş durum ve neden korunur; önceki tarihsel belgedeki "her açılışta otomatik aktive et" artık geçerli değildir.
- Menu katalog yayını (`is_published`) ile mutfağın satışa kapatma kararı farklıdır. Katalogdan gizli ürün Kitchen komutuyla kendiliğinden yayımlanamaz.
- Kitchen, başka servisin DB'sine doğrudan SQL/JPA erişimi kuramaz; cross-service FK / JOIN kullanamaz.
- Menu Service canlı değilse Kitchen içinde ikinci, kendi kendine gerçek kabul edilen availability tablosu oluşturup "müşteri menüsü artık kapandı" iddiasında bulunma. Yetkili komutu iletmek ve onayı almak gerekir. Menu iletişimi belirsizse `PENDING`/`UNCONFIRMED` gibi açık teknik sonuç ya da güvenli başarısızlık tasarla; **sahte başarılı kapanış gösterme**. Kullanıcı onayı gerektiren yeni dağıtık mimariyi ayrıca öner.
- Sipariş mutfak ekranındaki `READY`, garsonun servis ettiği anlamına gelmez. Kitchen'ın order hazırlık durumuna Menu güncellemesinden dolayı müdahale etme.
- **Manuel fire kayıtları** ve **temel gün sonu mutfak raporu**, nihai V1'de gereklidir. Kullanıcının dört ana task / 16 alt adım yapısını bozmadan aşağıda Task 3.4'ün kabul kapsamına dahildir.

### 1.2 Yetki tablosu (mevcut Auth kararı kesinleşince doğrula)

| İşlem | KITCHEN | ADMIN | Servis kimliği |
|---|---|---|---|
| Kapalı ürünleri görüntüleme | Kendi restoranında izinli | Kendi restoranında izinli | Yalnız yetkili iç sorgular |
| Ürünü stokta yok / geçici kapalı yapma | Kendi restoranında izinli | Varsayılan **izin yok**, ekip onayı gerek | Menu tarafında ayrıca doğrulanmış servis eylemi |
| Ürünü yeniden açma | Kendi restoranında izinli | Varsayılan **izin yok**, ekip onayı gerek | Yetkili Menu komutu |
| Fire kaydı ve listeleme | Kendi restoranında izinli | Rapor okumada önerilen izin; yazmada açık karar | Yalnız ihtiyaç olduğunda |
| Gün sonu sayısal rapor okuma | Kendi restoranında izinli | Kendi restoranında izinli | Yalnız yetkili projeksiyon |

**Not:** Önceki fazlarda ADMIN'in hazırlık durumuna yazması 403'tür; bu kararı stok erişilebilirliğine otomatik kesin onaylı politika gibi genişletme. Yukarıdaki varsayılan **least privilege** yaklaşımını kullan ve gerekirse başlangıçta karar bekle. Tenant başlıkla seçilmez; yalnız doğrulanmış kimlikten alınır. Kimliksiz 401; yetkisiz 403; başka restorandaki kaynağı sızdırmayan 404.

---

## 2. CHAT CHECKBOX İZLEME PROTOKOLÜ — ZORUNLU

**Her task için dört alt adım; toplam 16 adım.** Başlangıçta ve **HER alt adım tamamlandığında** aşağıdaki **tam listeyi yeniden chat'e** yaz. Tamamlanan adımı `[x]` yap; önce tamamlanan işin kısa kanıtını, sonra sıradaki adımı bildir. Yalnız dosyadaki checkbox'ı değiştirmek YETERLİ DEĞİLDİR.

```markdown
## RESTOFY — KITCHEN-03 | FAZ 3/4 — GÜNCEL CANLI İLERLEME
**Tamamlanan:** 0/16
**Şimdiki adım:** 3.1.1

### KITCHEN-03.1 — Stokta Olmayan Ürün
- [ ] 3.1.1 Mevcut Menu/Kitchen erişilebilirlik sözleşmesini ve veri sahipliğini doğrula
- [ ] 3.1.2 OUT_OF_STOCK komut DTO/validation ve yetkili port sınırını oluştur
- [ ] 3.1.3 Yetki, tenant, neden, idempotency ve hatalı senaryoları uygula
- [ ] 3.1.4 PostgreSQL/API/unit/contract testlerini çalıştır ve raporla

### KITCHEN-03.2 — Geçici Satışa Kapatma
- [ ] 3.2.1 Geçici kapanış neden/zaman/aktör sözleşmesini tasarla
- [ ] 3.2.2 TEMPORARILY_CLOSED komutunu güvenli şekilde uygula
- [ ] 3.2.3 Menu yanıtı, tekrar komut ve paralel değişim davranışlarını doğrula
- [ ] 3.2.4 Negatif yetki, yarış, persistence ve entegrasyon-sınırı testleri

### KITCHEN-03.3 — Yeniden Açma ve Kapalı Ürün Listesi
- [ ] 3.3.1 Reopen kurallarını ve kategori/yayın statüsü ayrımını doğrula
- [ ] 3.3.2 AVAILABLE komutunu uygula ve yetki kontrollerini koru
- [ ] 3.3.3 Kapalı ürün sorgusu, filtre/sıralama/sayfalama API'sini uygula
- [ ] 3.3.4 Testlerle doğrula: aç/kapat, izolasyon, eski komut, paralellik

### KITCHEN-03.4 — Açılış Kontrolü, Menü Senkronizasyonu, Fire ve Rapor
- [ ] 3.4.1 Restart/gün sonu davranışını ve Menu senkronizasyon statülerini netleştir
- [ ] 3.4.2 Kalıcı durumun yeniden yüklenmesini ve güvenli senkronizasyon sınırını uygula
- [ ] 3.4.3 Manuel fire/israf ve temel günlük sayısal raporu uygula
- [ ] 3.4.4 Kapsamlı test, tüm backend etkisi, API dokümanı ve faz sonu raporu

**Son tamamlanan:** —
**Sonraki adım:** 3.1.1
**Engeller / açık kararlar:** —
**Markdown/GitHub aktarımı:** YAPILMADI
```

### 2.1 İşleme ve ilerleme kuralları

1. **İlk mesaj:** keşif özetini ve tümü boş olan 16 checkbox listesini göster; ardından sadece 3.1.1'e başla.
2. Her alt adım: kod/migration/test + ilgili kanıt (değişen dosyalar, test komutu, sonuç) → **güncel tam checkbox listesi** → sıradaki adıma geçildiğini açıkça belirt.
3. Alt adımın konusu analiz ise analizin tamamlanması `[x]` olabilir; **uygulama/test engelleri ayrı `BLOCKED` veya `NOT VERIFIED` olarak görünür**. Bitmeyen işi tamamlandı gösterme.
4. Her 4 adımlık task sonunda kısa task özeti ver; önceki açık kararları ve durumları kaybetme. Tasklar arası onay kullanıcı ayrıca isterse beklenir; **Faz 3 → Faz 4 geçişi daima açık onaya bağlıdır.**
5. Ortak mimari veya güvenlik değişikliği, yeni ENV, servisler arası auth, gerçek Menu endpoint'ine bağımlı çalışma ya da canlı DB migration için **önce durup onay iste**. Onay gelmeden bu bölümü yapmış gibi işaretleme; bağımsız diğer alt adımları güvenle sürdürebiliyorsan engeli raporla.
6. Kullanıcıya sormadan KITCHEN-04'e, diğer servislere, commit/push'a geçme.

---

## 3. KITCHEN-03.1 — STOKTA OLMAYAN ÜRÜNLERİ İŞARETLEME

### 3.1.1 — Mevcut Menu/Kitchen sözleşmesini ve veri sahipliğini doğrula

**İş:** Menu ürün availability entity/API/kontratları, Kitchen mevcut RestClient veya port adaptörü, rol/tenant principal yapısı ve `docs/decisions/migrations.md` incelensin. Önceki mimari tasarımındaki `product_availability` ve `product_availability_history` **Menu DB'ye ait önerilerdir**; mevcut tablo/endpoint sanılmasın. Menu sahibinin onaylayacağı request/response sürümünü belirle; iletişim bilinmiyorsa `CONTRACT PROPOSED — NOT INTEGRATED` notu bırak.

**Kabul:** Ürün gerçeğinin tek yazarı Menu; Kitchen başka DB'ye bağlanmıyor, stok miktarı takip etmiyor. Gerçek katalog yayını ile availability ayrılıyor.

### 3.1.2 — OUT_OF_STOCK komut ve DTO/validation

**İş:** Yetkili mutfak kullanıcısının `restaurantId` bağlamında belirli `productId` için `OUT_OF_STOCK` istemesini tasarla. Asgari veri: ürün UUID, nedeni (`OUT_OF_STOCK`), isteğe bağlı sınırlı açıklama, güvenilir aktör ve correlation/command kimliği. Geçerli API yolu mevcut Gateway ve Kitchen rotalarıyla uyumlu olmalı; aşağıdaki **aday** yol repo teyidi olmadan kesin sözleşme değildir:

```text
PATCH /api/v1/kitchen/products/{productId}/availability
Idempotency-Key: <canonical UUID>
Body: {"status":"OUT_OF_STOCK","reasonCode":"OUT_OF_STOCK","reasonText":"Malzeme tükendi"}
```

**Önemli:** Faz 2'deki UUID/idempotency standardını koru; ancak her farklı ürün için önceden var olmayan Menu command contract'ını gizlice varsayma. İstek, Menu tarafından onaylanmadıysa başarı cevabı verilmez.

### 3.1.3 — Yetki, tenant, neden, tekrar ve hata güvenliği

**İş:** `KITCHEN` ve doğrulanmış restaurant identity; ürünün restoran ilişkisi Menu üzerinden doğrulanmalı. Boş/gereksiz uzun nedenler, sahte tenant/header, başka restoran ürünü, çakışan/tekrarlı `Idempotency-Key`, taşıma başarısızlığı, timeout ve sürüm çatışması için açık davranış. `200/202/409/503` seçimi, gerçekten uygulanmış garantiyi yansıtmalı; belirsiz sonucu `SUCCESS` yapma. Sınırsız retry veya aynı intent için yeni UUID ile kör tekrar yok.

### 3.1.4 — Testler ve kabul

**Test:** DTO boundary/validation; KITCHEN vs ADMIN/anonim/tenant; aynı komut tekrar; yanlış productId; Menu mock contract; geçici hata/timeout; Order geçmiş siparişini değiştirmeme. Menu gerçek servisi yoksa canlı değişimi `BLOCKED` olarak raporla; yetkili port/fake ile doğrulananı `TESTED LOCALLY` göster.

**Task 3.1 teslimi:** Sözleşme, güvenli komut sınırı ve test kanıtı; Menu canlı doğrulaması varsa gerçek HTTP kanıtı, yoksa net engel.

---

## 4. KITCHEN-03.2 — ÜRÜNLERİ GEÇİCİ OLARAK SATIŞA KAPATMA

### 3.2.1 — Neden, aktör ve zaman sözleşmesi

**İş:** `TEMPORARILY_CLOSED` durumu; açıklama/neden alanları, Menu tarafından kayıtlı `changedAt`, yetkili `changedBy`, `restaurantId` ve `productId`. Kapatma nedenini zorunlu yap; teknik sistem saati ile istemciden gelen uydurma zamanı ayır. Faz 1/2 olay snapshot'larına stok sebebiyle geriye dönük müdahale etme.

### 3.2.2 — Güvenli kapatma iş akışı

**İş:** Kitchen yetkili isteği doğrular → Menu portuna komut yollar → Menu kabul/doğrulama sonucu güvenle yorumlanır → yalnız onaylı sonuç UI/API'ye kesin kapalı olarak döner. Servis yoksa gerçek kapanış iddiası kurulmaz; uygun fail-closed/hata veya açık `PENDING` tasarımı ekip onayına tabidir. Uzun DB transaction/lock altında uzak HTTP tutma.

### 3.2.3 — Tekrarlama, yarış ve belirsiz yanıt

**İş:** Aynı komut tekrarında yeni availability history üretmeme garantisini **Menu tarafı sözleşmesi** gerektirir. Aynı ürün üzerinde mutfak ile admin/menü sahibi eşzamanlı güncelleme yaparsa eski versiyon yeni durumu ezmemeli; `version`/ETag veya koşullu işlem sözleşmesini değerlendirme. Belirsiz timeout'ta "kesin kapandı" demez. Onaysız dağıtık kesin atomiklik iddiası kurma.

### 3.2.4 — Testler

**Test:** Geçerli kapanış, eksik neden, rol/tenant reddi, iki kez aynı komut, farklı komut yarışı, Menu 4xx/5xx/timeout, invalid response, yanlış tenant, listeyle tutarlılık; Mock/fake ile doğrulanan ve gerçek Menu ile doğrulanmayan ayrı gösterilsin.

**Task 3.2 teslimi:** Geçici kapatma komutu, gerekçeli ve güvenli hata sözleşmesi, testler.

---

## 5. KITCHEN-03.3 — YENİDEN SATIŞA AÇMA VE KAPALI ÜRÜNLERİ LİSTELEME

### 3.3.1 — Reopen koşulları

**İş:** `OUT_OF_STOCK` ve `TEMPORARILY_CLOSED` ürünü yetkili komutla `AVAILABLE` yapma. `is_published=false` ise ürün otomatik yayımlanmaz. Gerekirse reopen için aktör/zaman/komut ID ve önceki kapatma gerekçesinin history içinde saklanması Menu sözleşmesinde yer alsın.

### 3.3.2 — Yeniden açma komutu

**İş:** Kapatmadaki imza, idempotency, tenant/rol ve koşullu update politikasıyla tutarlı uygula. Katalogdan silinmiş/pasif veya yanlış restoran ürünü için uygun hata. Kullanıcı yalnız Menu'nün kesin onayı sonrası "satışa açık" bilgisi görür.

### 3.3.3 — Kapalı ürünleri sorgulama

**İş:** Restoran bazında yalnız `OUT_OF_STOCK` ve `TEMPORARILY_CLOSED` durumları için liste; ürün ID/adı (Menu sağlıyorsa), durum, gerekçe, kapatılma anı ve mümkünse aktör; filtre ve sayfalama. **Menu gerçek kaynak** olduğundan Kitchen gizli ikinci gerçeği üretmez. Örnek aday rota (repo doğrulaması şart):

```text
GET /api/v1/kitchen/products/closed?status=OUT_OF_STOCK&page=0&size=20
```

Eksik parametre ile boş parametreyi ayrı ele al (`?status=` boş ise 400), uygun max size, deterministik sıralama, güvenli filtreleme, boş liste/hata/timeout davranışı. Kamuya açık cache veya başka restoran bilgisi yok.

### 3.3.4 — Testler

**Test:** Ürün kapat-aç-kapat; yeniden açınca reason history saklanması; `is_published` ayrımı; farklı restoran; pagination sınırları; boş/geçersiz status; eski durum yanıtı; çift komut; Menu entegrasyonu yoksa yalnız mock sınırının doğrulanması.

**Task 3.3 teslimi:** Yetkili reopen ve kapalı ürün listeleme sözleşmesi/uygulaması; doğrulama sınırı açık.

---

## 6. KITCHEN-03.4 — AÇILIŞ KONTROLÜ, MENU SENKRONİZASYONU, FIRE VE GÜN SONU RAPORU

> **Ana task sayısını değiştirmeden** nihai Kitchen Service V1'de zorunlu olan manuel fire/israf ve gün sonu sayısal rapor da burada uygulanacak. Task 3.4, sadece kontrol/senkronizasyonla bitmiş sayılmaz.

### 3.4.1 — Yeniden açılış ve Menu senkronizasyonu kararlarının netleştirilmesi

**İş:** Servis/restart veya restoran sistem açılışında kapalı ürünlere ilişkin kayıtlı Menu gerçeği okunur; **asla otomatik `AVAILABLE` yapma**. Menü yeniden ulaşılabilir olduğunda en güncel yetkili durumun nasıl sorgulanacağı, bağlantı kesintisi, stale projection/TTL, dayanıklı teslim ihtiyacı ve kaynak versiyonu belirlenir. Polling/dispatcher/yeni ENV gerektiriyorsa ekip onayı olmadan ekleme. "Sistem açılışı" ile her Kitchen uygulaması restart'ının aynı şey olduğu varsayılmaz.

### 3.4.2 — Kalıcılık ve senkronizasyon sınırı

**İş:** Menu var olan endpoint/kontratıyla doğrulanmışsa kontrollü canlı entegrasyon ve başlangıç senkronizasyonunu uygula. **Henüz yoksa** port + sözleşme + test fake'iyle izole entegrasyon sınırını bırak, `MENU SYNC BLOCKED / LIVE NOT VERIFIED` de. DB'de kalıcı Menu availability gerçeği kopyası oluşturma; geçmiş ve teknik izleme gerekiyorsa kaynağın sahibi/türetme politikası ayrı belirlenir. Sistem tekrar başladı diye ürün açılmaz. UI'ye belirsiz veri "kesin açık" dönmez.

### 3.4.3 — Manuel fire/israf kaydı ve temel günlük rapor

**A. Fire/israf: zorunlu V1**

- Kitchen DB'sinde yalnız Kitchen'ın sahibi olduğu `waste_records` benzeri tabloyu gerçek migration durumuyla uyumlu şekilde oluştur. `restaurant_id`, `product_id` (Menu mantıksal REF), `product_name_snapshot` (güvenilir katalog bilgisi mevcutsa), `quantity` (**pozitif integer**), `reason_code`, `description`, `recorded_at` (UTC `timestamptz`), `recorded_by_user_id` (doğrulanmış principal), isteğe bağlı `order_id` mantıksal REF. Gerçek ürün snapshot erişilemiyorsa ürün adı uydurma; doğrulama sınırını açık tut.
- Neden kodları: `WRONG_PREPARATION` (yanlış hazırlama), `SPOILAGE` (bozulma), `DROPPED_OR_DAMAGED` (düşürme/hasar), `WASTE_AFTER_CANCELLATION` (iptal sonrası israf), `OTHER` (diğer). Kod isimleri mevcut konvansiyonla uyumlu hale getirilsin; anlam sabit.
- Manuel fire oluşturma + tarih/ürün filtreli listeleme; kullanıcı ve zaman audit'i; neden ve açıklama validation; metin uzunluğu sınırı; sayfalama; başka restoran kayıtlarını reddet.
- Fire kaydı **hammadde stok miktarını azaltmaz**, ürünün availability durumunu kendiliğinden kapatmaz; siparişe zorunlu bağlı değildir. Duplicate istek için idempotency veya açık tekrar koruması değerlendir; faz 2 anahtar kullanımını keyfi bozma.

**B. Gün sonu mutfak raporu: zorunlu V1**

- Yetkili restoran ve **iş günü tarihi** için yalnız Kitchen'ın bildiği iş gerçeğinden `received / ready / cancelled / waiting / preparing` sipariş adetleri; toplam fire adedi ve neden kırılımı döndür.
- Çifte sayımı önleyen tanımları netleştir: `received` seçilen günde alınan kayıtlar; `ready`/`cancelled` seçilen günde ilgili geçiş zamanı veya rapor için seçilen operasyonel yaklaşım; `waiting`/`preparing` seçilen gün sonu anlık durumları. **Aynı sipariş farklı metriklere anlamlı şekilde girebilir**; bu kategorileri birbirinin yerine kullanma. Kaynak tanımlar kesin değilse raporda kullanılan tanımı açıkça yaz; sayıların yanlış veya sahte kesin olmasına izin verme.
- Restoranın **IANA timezone** bilgisi güvenilir Admin kaynağından gelmiyorsa önceden onaylanmış yapılandırmayı kullan; `Europe/Istanbul` değerini tüm restoranlara evrensel varsayma. İş günü sınırları `[start,end)`; UTC'ye doğru çevir, DST testlerini düşün.
- Hatalı tarih, başka restoran erişimi, boş veri, gün sınırı, eşzamanlı kayıt, iptal ve READY event sayım ayrımı, fire neden kırılımı için test yaz. **Finansal ciro/kâr, hammadde stok raporu veya admin genel raporu** ekleme.
- Örnek aday rotalar (mevcut route ve contract ile doğrulanmalı):

```text
POST /api/v1/kitchen/waste
GET  /api/v1/kitchen/waste?dateFrom=2026-10-20&dateTo=2026-10-20&productId=<uuid>
GET  /api/v1/kitchen/reports/daily?date=2026-10-20
```

### 3.4.4 — Test, kalite, sözleşme, kapanış raporu

1. Task 3.1–3.4 için unit + controller/security + gerçek PostgreSQL/Testcontainers + migration/Flyway + eşzamanlılık + idempotency + restart + tarih/tenant testlerini koş. Dış bağımlılık yoksa mock/fake test sonucu ile canlı entegrasyonu **ayrı** raporla.
2. Mevcut Faz 1/2 regresyonlarının korunmasını doğrula. Gerçek Java 25 ve Maven Wrapper ile Kitchen `clean verify`, koşullar uygunsa **tüm backend** `clean verify`; pre-push'u ancak kullanıcı özel olarak izin verirse çalıştır. `mvn`/JDK eksikse `NOT RUN` veya `NOT VERIFIED` de; Java sürümünü düşürme.
3. JaCoCo raporları, test sayıları, 0 fail/skip, değişen dosyalar ve eklenen Flyway sürümünü açıkla. Kitchen migration aralığı `V81–V90` (V82/V83 daha önce atanmış); sıradaki kullanılmayan numarayı **gerçek klasör ve DB Flyway history incelemesinden sonra** seç. Gerçek DB'ye onaysız migration uygulama. Var olan migration'ı değiştirme.
4. Gerekirse ilgili API sözleşmesini Kitchen'e ait teknik belgede tut; bu ve bütün AI plan `.md` dosyaları Git'e eklenmeyecek. Herhangi bir Markdown dosyasının GitHub'a gönderilmesi **kesin yasak**.
5. Kapsam dışı D-02 (iptal), D-09 (Auth/CSRF), D-10 (transport) ve Menu yetki/endpoint kararlarını `BLOCKED` veya `DECISION REQUIRED` olarak kaydet; tamamlandı gibi gösterme.
6. Tam 16-adım checkbox listesini son kez göster; her ana taskın `IMPLEMENTED / TESTED / BLOCKED / NOT VERIFIED` durumunu ayrı ver. Faz kapanışından sonra **KITCHEN-04 için dur ve kullanıcının açık onayını bekle**.

---

## 7. YATAY TEKNİK VE GÜVENLİK KURALLARI

- **Veri sahipliği:** Kitchen kendi PostgreSQL DB'si dışında tabloya yazamaz; Menu availability tek doğruluk kaynağıdır. Diğer servisin entity/JPA sınıfını import etme, DB bağlantısını paylaşma.
- **Güvenlik:** RBAC + tenant + kaynak sahibi doğrulaması sunucuda; sahte rol/restaurant header'ları geçersiz. `permitAll` ile iş endpoint açma, CSRF güvenliğini kaldırma. Auth henüz hazır değilse üretim yolları 401 olarak fail-closed kalabilir; local test principal fixture'ını production'a sızdırma.
- **Hata kontratı:** `401` kimliksiz, `403` rol yetkisiz, `404` görünmez kaynak, `400` kötü giriş, `409` gerçek conflict, `503` geçici uzak servis meşgul/kullanılamaz gibi durumlar; gerçek kod semantiği ve mevcut `ProblemDetail` standardıyla uyuşmalı. Stack trace, secret, URL, kişi bilgisi hata gövdesinde yok.
- **Idempotency:** Her yeni komuta ayrı kanonik UUID; yeniden denemede aynı anahtar. Anahtar tekrar kullanımına tenant/komut/ürün/aktör tutarlılığı şart. Outbox/idempotency garantisi uygulanmadıysa uygulanmış gösterme.
- **Zaman:** UTC kalıcı zaman, restoranın belirlenmiş saat diliminde rapor günü. Restart statüyü otomatik değiştiremez.
- **İzleme:** Yapılandırılmış, hassas veriden arındırılmış log; correlation ID; belirsiz dış istek ve yeniden denemelerin görünürlüğü. Güncel availability bilinmiyorsa sonucu kesinmiş gibi dönme.
- **Mimari:** Gereksiz broker, scheduler, distributed transaction, event dispatcher veya yeni dependency/ENV ekleme; ihtiyaç varsa önce karar iste. KITCHEN-02 Order outbox davranışını bozma.
- **Test dürüstlüğü:** PASS yalnız gerçekten koşturulup geçen kontrol için; gerçek Menu senkronizasyonunu mock ile başarılı sayma; pre-push çalıştırılmadıysa `NOT RUN` de. Veritabanı testlerini üretim verisine karşı kullanma.
- **Git ve Markdown:** Bu plan dosyası ve diğer AI faz planları **asla GitHub'a gitmeyecek**. Kod yazarken dahi `git add`, `git commit`, `git push`, `git merge` yapma; branch değişikliği, stage, PR, release yok. `.md` belgelerini Git'e alma; planları repo dışında tut. Meşru önceden izlenen dokümantasyon dosyalarını silme/değiştirme zorlaması yapma.

---

## 8. TAMAMLAMA / KABUL KONTROL LİSTESİ

- [ ] KITCHEN-03.1: Stokta yok komutu, Menu otoritesi, güvenlik ve test
- [ ] KITCHEN-03.2: Geçici kapatma, zorunlu neden ve zaman, belirsiz sonucu saklamama
- [ ] KITCHEN-03.3: Yeniden açma + restoran bazlı kapalı ürün listesi
- [ ] KITCHEN-03.4: Kapanış/açılışta kalıcı kapalı durum ve Menu senkronizasyonunun gerçek durumu
- [ ] KITCHEN-03.4: Manuel fire/israf kayıtları, filtreleme, adet/nedenler
- [ ] KITCHEN-03.4: Tarih/restoran bazlı temel sayısal gün sonu raporu
- [ ] Yetki, tenant, API validation ve Menu geçici hata/timeout senaryoları testli
- [ ] Java 25, Flyway, Testcontainers, regresyon ve coverage için dürüst kanıt
- [ ] Hiçbir ürün restart'ta kendiliğinden açılmıyor
- [ ] Menüye canlı iletim yoksa **BLOCKED / LIVE DELIVERY NOT VERIFIED** yazıldı
- [ ] **Tüm AI faz planı `.md` dosyaları GitHub dışında tutuldu; hiçbir `.md` Git'e eklenmedi**
- [ ] **Hiçbir `git add`, commit, push, merge, PR veya deploy yapılmadı**
- [ ] Faz 3 bitince duruldu, Faz 4 için kullanıcıdan açık onay beklendi

### 8.1 Faz sonu rapor formatı (zorunlu)

```text
RESTOFY — KITCHEN-03 | FAZ SONU RAPORU

A. 16 alt adımın TAM ve GÜNCEL checkbox listesi
B. Task sonuçları: 3.1 / 3.2 / 3.3 / 3.4
   Her biri: IMPLEMENTED / TESTED / BLOCKED / NOT VERIFIED
C. Kod ve migration değişiklikleri; eski Faz 1–2 regresyon durumu
D. API ve Menu sözleşmeleri (gerçek / önerilen / engelli ayrı)
E. Fire/israf ve gün sonu raporunun test edilen örnek senaryoları
F. Güvenlik, tenant, CSRF, idempotency ve yeniden deneme kararları
G. Test komutları, test sayıları, coverage, Java 25 ve pre-push durumu
H. Gerçek Menu senkronizasyonu ve dev DB migration: VERIFIED / NOT VERIFIED
I. Açık kararlar, entegrasyon engelleri ve teknik borçlar
J. Git / Markdown:
   - git add: YAPILMADI
   - git commit: YAPILMADI
   - git push: YAPILMADI
   - git merge: YAPILMADI
   - AI plan MD dosyalarının GitHub'a aktarımı: YAPILMADI
K. KITCHEN-04'e GEÇİLMEDİ; AÇIK KULLANICI ONAYI BEKLENİYOR
```

**SON EMİR:** Bu dosyayı **yalnız KITCHEN-03** için uygula. Başlangıçta ve her alt adımda **tüm 16 checkbox'ı chat'te göster**; biten adımı işaretle ve sıradakini bildir. İş bitince genel rapor verip **DUR**. **Plan Markdown dosyalarını GitHub'a ASLA gönderme. Git add/commit/push/merge ASLA yapma.**
