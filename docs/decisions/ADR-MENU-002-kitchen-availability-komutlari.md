# ADR-MENU-002 — Kitchen availability komutları: güvenilir çağıran, kalıcı idempotency ve sıralama sınırı

- **Durum:** Menu sağlayıcı kararı kabul edildi; tüketici uyarlamaları ve ortak entegrasyon onayı bekleniyor
- **Tarih:** 2026-10-30
- **İnceleme güncellemesi:** 2026-11-11; Menu PR #19 / Kitchen PR #16 karşılaştırması
- **Sahip:** Menu (Merve); Kitchen ve Auth ile ortak karar gerektirir
- **İlgili kod:** `KitchenAvailabilityCommandService`, `TrustedKitchenCallerProvider`, `DenyAllKitchenCallerProvider`,
  `ProductAvailabilityCommand`, `ProductAvailabilityCommandRepository`, `V67__create_product_availability_commands.sql`

## Bağlam

Menu, ürün availability bilgisinin **tek yazarı ve sağlayıcı sözleşmesinin sahibidir**; Kitchen kapatma/açma komutunu ileten
tüketicidir. Kitchen PR #16'nın `d7e6aabceda573f91c08faa665ffcdad893e6957` ucunda `MenuAvailabilityPort`, komut/durum
modelleri ve `ProductAvailabilityService` akışı vardır. PR 9 Kasım 2026'da merge edilmiştir. Gerçek üretim adaptörü yoktur;
`MenuIntegrationConfiguration` varsayılanı `MENU_NOT_INTEGRATED` döndürür. Menu tarafında HTTP ucu bulunmaz ve
`DenyAllKitchenCallerProvider` hiçbir çağıranı doğrulamaz. Personel JWT'si ve kişinin `KITCHEN` rolü, Kitchen **servisinin**
kimliğini veya adına işlem yapılan personelin yetkisini tek başına kanıtlamaz (D-09). İstemcinin `X-Role`,
`X-Restaurant-Id` gibi başlıkları yetki kaynağı değildir.

Bu güncelleme mevcut Menu davranışını korur; Kitchen ekibinin uyarlamaları veya ortak Auth/transport kararları tamamlanmış
sayılmaz. PR inceleme kaynakları: [Menu #19](https://github.com/software-lecture-labs/Restofy/pull/19),
[Kitchen #16](https://github.com/software-lecture-labs/Restofy/pull/16). İncelenen Kitchen kodu
[`ProductAvailabilityService`](https://github.com/software-lecture-labs/Restofy/blob/d7e6aabceda573f91c08faa665ffcdad893e6957/backend/services/kitchen-service/src/main/java/com/restofy/kitchen/application/service/ProductAvailabilityService.java)
ve [`MenuAvailabilityPort`](https://github.com/software-lecture-labs/Restofy/blob/d7e6aabceda573f91c08faa665ffcdad893e6957/backend/services/kitchen-service/src/main/java/com/restofy/kitchen/application/port/MenuAvailabilityPort.java)
üzerinden doğrulanmıştır. Uzak Menu PR ucu `c959858`, yerel inceleme ucu `50d5b5b`'dir; yerelde sonuç enum'u
`CommandOutcome`, uzak uçta `KitchenAvailabilityResult.Outcome` adını taşır; APPLIED/NO_CHANGE anlamları aynıdır.

## Karar

1. **Yalnız port ve işleyici; HTTP ucu yok.** `TrustedKitchenCallerProvider` doğrulanmış çağıranı (`restaurantId`, adına işlem yapılan
   `onBehalfOfUserId`) verir. Üretim varsayılanı hiçbir zaman çağıran döndürmez, bu yüzden üretimde hiçbir komut işlenmez. Güvenlik
   zinciri değişmedi. Gerçek servis kimliği gelince HTTP adaptörü ayrı, onaylı bir görevde eklenir (öneri: Gateway'in yönlendirmediği
   `/internal/v1/...` öneki ve ayrı güvenlik zinciri).
2. **Komut yalnız availability alanlarını taşır** (`commandId`, `productId`, hedef durum, neden kodu/metni, isteğe bağlı
   `expectedVersion`). Fiyat, KDV, kategori ve yayın durumu komutta yoktur. Restoran gövdeden değil çağırandan gelir.
3. **Üç durum arasındaki tüm geçişler serbest**; aynı duruma (aynı neden) geçiş no-op'tur; kapalı duruma geçişte neden kodu zorunludur.
4. **Kalıcı idempotency (V67):** `product_availability_commands` kabul edilen her komutu (etkisiz olanlar dahil) `(restaurant_id,
   command_id)` anahtarıyla bir kez yazar. Aynı kimlik + aynı içerik: eski sonuç döner, sürüm ve geçmiş değişmez. Aynı kimlik + farklı
   içerik: çakışma. Reddedilen komut kayıt bırakmaz. Yeniden başlatmada korunur. Kimliği sahiplenme `INSERT .. ON CONFLICT DO NOTHING`
   ile yapılır (hedefsiz: tabloda komut kimliğini içeren iki benzersiz anahtar vardır).
5. **Sıra ve eşzamanlılık:** `expectedVersion` verilirse sıkı kontrol (eski sürüm çakışma); yeni komut için availability satırı
   her iki durumda da kilitlenir (`PESSIMISTIC_WRITE`). Komutlar kilidi elde etme sırasına göre uygulanır; bu, gönderim veya ağda
   geliş sırası garantisi değildir. Kilit **ürün başınadır**, global değildir. Optimistic locking de korunur.
6. **Audit:** availability güncellemesi, geçmiş satırı ve komut kaydı **tek işlemde** yazılır; hata yarım kayıt bırakmaz. Geçmiş satırı
   komutu bileşik FK ile aynı restoran ve ürün içinde adlandırır. `changed_by_user_id` adına işlem yapılan personeldir; kaynak komut
   kaydında `KITCHEN` olarak durur.
7. **Otomatik açılma yoktur.** Kapalı ürün yeniden başlatmada, gün sonunda veya zamanla kendiliğinden açılmaz; yalnız açık bir
   `AVAILABLE` komutu açar (`docs/requirements.md` F-KT-03, nihai karar).
8. **Minimal işlem makbuzu:** `KitchenAvailabilityResult(outcome, replayed, status, version)` canonical v1 sonuç modelidir.
   `APPLIED` gerçek değişiklik, `NO_CHANGE` durum ve normalize neden kodu/metninin aynı olmasıdır. Aynı durum ve farklı neden
   değişiklik olabilir. Replay ilk makbuzu döndürür; ürün daha sonra değişmiş olsa da yeniden uygulanmaz. Bu cevap canlı ürün
   görünümü değildir. `changedAt`, `changedByUserId`, `published` gibi alanlar eklenmez veya uydurulmaz.
9. **Aktiflik/yayın bağımsızlığı:** Mevcut komut işleyicisi tenant içindeki ürünün varlığını kontrol eder; ayrıca pasif/yayınsız
   ürün yasağı uygulamaz. Availability değişikliği ürünü aktif veya yayınlı yapmaz; müşteri görünürlüğü ve sipariş uygunluğu
   `ProductEligibility` tarafından ayrıca hesaplanır. Kitchen'ın `PRODUCT_INACTIVE` varsayımı Menu kuralı sayılmaz.

## Canonical v1 ve Kitchen PR #16 uyarlaması

Alanların tam tablosu, örnek request/response ve mevcut exception eşlemeleri
[Menu README / Kitchen availability komutları](../../backend/services/menu-service/README.md#kitchen-availability-komutları)
bölümündedir. Bunlar mevcut uygulama modellerinin veri sözleşmesidir; uygulanmış internal HTTP method/path veya transport
protokolü değildir. Gelecekteki adaptörün hata kodları, kimlik aktarımı ve sürümleme biçimi ayrıca onaylanacaktır.

| Konu | Kitchen PR #16 | Menu canonical v1 / beklenen uyarlama |
|---|---|---|
| Sınır ve sahip | `MenuAvailabilityPort` istemci portu; Menu otorite | `KitchenAvailabilityCommandService` sağlayıcı işleyici; Java entity paylaşılmaz |
| Request | `commandId`, `restaurantId`, `productId`, hedef durum, nedenler, `requestedByUserId` | Komut gövdesi: kimlik, ürün, durum, nedenler, isteğe bağlı `expectedVersion`; tenant/aktör güvenilir bağlamda |
| Durumlar | AVAILABLE / OUT_OF_STOCK / TEMPORARILY_CLOSED | Aynı üç enum değeri |
| Beklenen sürüm | Request'te yok; sonuçta VERSION_CONFLICT var | İsteğe bağlı availability sürümü; ürün sürümü değil |
| Kapatma nedeni | Kod/metin isteğe bağlı; kod 2–40 karakter | Kapalı durumda kod zorunlu; 1–40 karakter; mevcut Menu ve DB doğrulaması korunur |
| Başarı | APPLIED ve ayrıntılı `ProductAvailabilityState` | APPLIED/NO_CHANGE + replay + durum/sürüm; Kitchen ayrımı kaybetmemeli |
| Zorunlu cevap alanı | `judge()` ürün kimliği ve `changedAt` bekler | Minimal makbuz bu alanları içermez; tüketici modeli uyarlanmalı, tarih uydurulmamalı |
| Tekrar | Aynı kimlikle tekrar Menu'ya gider; bazı açıklamalar canlı durumu çağrıştırır | İlk kabulün tarihsel sonucu; güncel görünüm ayrı okuma kararı |
| Pasif ürün | PRODUCT_INACTIVE reddi öngörür | Mevcut Menu'da böyle ret yok; yeni yasak ayrı ortak karar gerektirir |
| Kimlik | Gerçek üretim adaptörü yok | D-09 yokken fail-closed; header/gövde kimlikleri kanıt değildir |

Kitchen'ın APPLIED kategorisi içine NO_CHANGE veya replay'i bilgi kaybıyla gömmek uygun değildir. Tüketici ileride
canonical `outcome` ve `replayed` ayrımını taşımalıdır. İdempotency içeriği adına işlem yapılan personeli ve
`expectedVersion`ı da kapsar; aynı kimlikle bu alanların değiştirilmesi farklı içeriktir. Ürün/tenant erişimi ve doğrulama
idempotency replay'inden önce yapılır; geçmiş kayıt güncel yetki kontrollerini atlatmaz.

Hatalar da ayrı tutulur: komut kimliği çakışması, sürüm çakışması, ürün bulunamaması (başka tenant için de aynı sonuç),
availability kaydı eksikliği, geçersiz komut ve doğrulanmamış çağıran mevcut Menu exception'larıdır. Kitchen'da
`AVAILABILITY_MISSING` ve kimlik hatasının ayrı eşlenmesi **öneridir**, mevcut portta uygulanmış değildir. Timeout veya
erişilemezlikte sonuç belirsiz olabilir; başarı/NO_CHANGE sayılmaz. Ret ile belirsiz sonuç birbirine dönüştürülmez.

## Seçenekler ve ödünleşimler

| Konu | Seçilen | Alternatif | Not |
|---|---|---|---|
| Güven | Port + fail-closed | Şimdi `/internal` ucu | Servis kimliği yokken kapı açmak yarar getirmez |
| Idempotency | Ayrı tablo | Yalnız geçmişe `command_id` | Etkisiz komut geçmiş satırı bırakmadığı için tekrarı ve içerik farkı algılanamazdı |
| Sıra | İsteğe bağlı sürüm + satır kilidi | Zorunlu sürüm | Kitchen, Menu'daki sürümü okumuyor |

`expectedVersion` için değerlendirilen seçenekler:

- **A — İsteğe bağlı sürüm (bu görevde korunan):** Kitchen alanı göndermeyebilir; mevcut kilit/idempotency davranışı değişmez.
  Sürümsüz eski komutun yeniyi ezme riski devam eder.
- **B — Zorunlu sürüm:** Kitchen önce yetkili availability sürümünü okumalıdır; tüketici değişikliği ve okuma sözleşmesi
  gerektirir. Bu görevde seçilmedi.
- **C — Alternatif sıralama/tazelik:** Monoton sıra/fencing gibi mekanizma; yeniden başlatma, tekrar, çoklu üretici ve Menu
  kaynaklı yönetici değişiklikleriyle etkileşimi tasarlanmalıdır. Yalnız timestamp eklemek tek başına garanti değildir.
  Bu görevde uygulanmadı.

Canonical sözleşme v1 olarak belgelenir. Mevcut altı request ve dört response alanının anlamları korunur. Gerçek transport
geldiğinde ek alanlar, bilinmeyen enum değerleri ve sürüm geçişlerinin iki taraftaki davranışı ayrıca ortaklaştırılır.
Kitchen'ın mevcut modelleri uyarlanmış sayılmaz. Ayrıntılı bir tarihsel makbuz seçeneği istenirse ilk cevap snapshot'ının
kalıcı saklanması tasarlanmalı; mevcut V67 yeniden yazılmamalıdır. Bu görevde yeni alan veya migration eklenmez.

## Bilinen sınır (gizlenmiyor)

`expectedVersion` olmayan **gecikmiş eski bir komut yeni durumu ezebilir**: veritabanı komutun eski olduğunu bilemez. Gerçek Kitchen
entegrasyonunda sıralama veya tazelik bilgisi (örneğin Kitchen'dan monoton sıra numarası ya da olay zamanı) ayrıca tasarlanmalıdır.

## Sonuç ve izleme

- Kitchen tüketici doğrulaması (zorunlu kapatma nedeni), isteğe bağlı sürüm, minimal cevap modeli, replay gösterimi,
  PRODUCT_INACTIVE farkı ve availability/kimlik hata eşlemeleri Kitchen ekibi ve hocayla ortaklaştırılmayı bekler.
- Gerçek servis kimliği (D-09), tenant/personel yetki aktarımı, HTTP adaptörü, canlı durum okuması ve sıra/tazelik mekanizması
  ayrı onaylı görevlerdir. Üretim fail-closed kalır; bu dokümantasyon çalışması HTTP adaptörü geliştirmez.
- `ProductAvailabilityChanged` olayı (Menu → projeksiyonlar) ve outbox, D-10 çözülene kadar yoktur.
