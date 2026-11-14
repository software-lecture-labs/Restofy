# ADR-MENU-003 — Rating özeti sözleşmesi ve hata davranışı

- **Durum:** Kabul edildi (geçici sözleşme), Rating Service geliştirilince doğrulanacak
- **Tarih:** 2026-10-30
- **Sahip:** Menu (Merve); Rating ile ortak karar gerektirir
- **İlgili kod:** `RatingSummaryPort`, `RatingSummary`, `UnavailableRatingSummaryPort`, `PublicProductDetailService`

## Bağlam

Ürün detayı, puan ortalamasını (F-MN-02) göstermelidir. Puan verisi Rating Service'indir. Rating Service **geliştirilmemiştir**
(`feature/rating-service` dalında yalnız uygulama iskeleti var): ortalama API'si, servisten servise kimlik ve onaylı bir veri
sözleşmesi yoktur. `docs/database.md` yalnız bir projeksiyon önerir (`rating_count`, `rating_sum`, ortalama sayı > 0 ise sum/count,
yoksa NULL).

## Karar

1. `RatingSummaryPort.summaryFor(restaurantId, productId)` → `RatingSummary(status, averageRating, ratingCount)`.
   - `RATED`: ortalama 1–5 aralığında, değerlendirme sayısı ≥ 1.
   - `NOT_RATED`: ortalama yok, sayı 0.
   - `UNAVAILABLE`: ortalama yok, sayı yok (kaynağa ulaşılamıyor, entegrasyon yok ya da yanıt geçersiz).
   `0` hiçbir zaman "bilinmiyor" anlamında kullanılmaz.
2. **1–5 aralığı geçici bir Menu varsayımıdır**; Rating Service geliştirildiğinde gerçek puanlama ölçeğiyle doğrulanmalıdır.
3. Menu ortalamayı **hesaplamaz**, Rating verisini **saklamaz**, Rating veritabanına **bağlanmaz**.
4. Üretim varsayılanı `UnavailableRatingSummaryPort`'tur: ağ çağrısı yapmaz, sabit puan üretmez, her zaman `UNAVAILABLE` döner.
   Test double yalnız `src/test` içindedir. Gerçek HTTP istemcisi, timeout ve tazelik bilgisi uygulanmamıştır; gerçek adaptörün
   bunları kendisinin sağlaması port sözleşmesinde şart koşulmuştur.
5. Port'tan dönen her değer müşteriye çıkmadan doğrulanır (`RatingSummary.validated`). `null`, durumla çelişen alanlar, aralık
   dışı ortalama ve negatif ya da sıfır sayı `UNAVAILABLE` olur. Port hata fırlatırsa ürün detayı yine 200 döner ve puan
   `UNAVAILABLE`'dır; hata metni müşteriye ve loga girmez (yalnız istisna sınıfı adı).
6. **Sıra ve işlem sınırı:** ürün görünürlüğü ve seçenekler salt okunur `REPEATABLE READ` işleminde okunur; Rating portu bu işlem
   **bittikten sonra** ve yalnız görünür bir ürün için çağrılır. Yavaş bir Rating kaynağı veritabanı bağlantısını tutmaz; gizli,
   bilinmeyen veya başka restoranın ürünü için Rating kaynağına hiç sorgu gitmez.

## Seçenekler ve ödünleşimler

| Seçenek | Artı | Eksi |
|---|---|---|
| **Port + `UNAVAILABLE` varsayılanı** | Sahte veri yok, gerçek servis gelince yalnız adaptör eklenir | Puan bölümü Rating gelene kadar hep "ulaşılamıyor" gösterir |
| Menu'de özet tablosu | Hızlı okuma | Rating verisi Menu'ye kopyalanır, iki gerçek kaynağı oluşur |
| Sabit/uydurma puan | Arayüz dolu görünür | Sahte veri; kabul edilemez |

## Sonuç ve izleme

- Rating Service ve servis kimliği çıktığında: puan ölçeği, durum adları, tazelik (ölçüm zamanı) ve timeout/hata sınıflandırması
  gerçek sözleşmeyle uyumlanır. Tazelik bilgisi olmadan eski ya da tutarsız yanıt Menu'den algılanamaz.
- Bu fazda Menu'nun müşteri uçları zaten ADR-MENU-001 gereği üretimde fail-closed'dur.
