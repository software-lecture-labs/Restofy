# ADR-MENU-002 — Kitchen availability komutları: güvenilir çağıran, kalıcı idempotency ve sıralama sınırı

- **Durum:** Kabul edildi (sözleşme ve işleyici hazır), gerçek Kitchen bağlantısı bekliyor
- **Tarih:** 2026-10-30
- **Sahip:** Menu (Merve); Kitchen ve Auth ile ortak karar gerektirir
- **İlgili kod:** `KitchenAvailabilityCommandService`, `TrustedKitchenCallerProvider`, `DenyAllKitchenCallerProvider`,
  `ProductAvailabilityCommand`, `ProductAvailabilityCommandRepository`, `V67__create_product_availability_commands.sql`

## Bağlam

Menu, ürün availability bilgisinin **tek yazarıdır**; Kitchen kapatma/açma komutunu iletir. Kitchen dalında ürün müsaitliği özelliği
(F-KT-03) ve Menu'ya giden bir istemci henüz yoktur. Auth yalnız **personel** oturumu üretir (RS256 JWT, `KITCHEN` rolü bir
kişidir); Kitchen **servisinin** kimliği tanımsızdır (D-09). Gateway kimlik doğrulamaz. `X-Role`, `X-Restaurant-Id` gibi başlıklar
yetki kanıtı olamaz.

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
5. **Sıra ve eşzamanlılık:** `expectedVersion` verilirse sıkı kontrol (eski sürüm çakışma); verilmezse ürünün availability satırı
   kilitlenir (`PESSIMISTIC_WRITE`) ve komutlar geliş sırasına göre uygulanır. Kilit **ürün başınadır**, global değildir.
6. **Audit:** availability güncellemesi, geçmiş satırı ve komut kaydı **tek işlemde** yazılır; hata yarım kayıt bırakmaz. Geçmiş satırı
   komutu bileşik FK ile aynı restoran ve ürün içinde adlandırır. `changed_by_user_id` adına işlem yapılan personeldir; kaynak komut
   kaydında `KITCHEN` olarak durur.
7. **Otomatik açılma yoktur.** Kapalı ürün yeniden başlatmada, gün sonunda veya zamanla kendiliğinden açılmaz; yalnız açık bir
   `AVAILABLE` komutu açar (`docs/requirements.md` F-KT-03, nihai karar).

## Seçenekler ve ödünleşimler

| Konu | Seçilen | Alternatif | Not |
|---|---|---|---|
| Güven | Port + fail-closed | Şimdi `/internal` ucu | Servis kimliği yokken kapı açmak yarar getirmez |
| Idempotency | Ayrı tablo | Yalnız geçmişe `command_id` | Etkisiz komut geçmiş satırı bırakmadığı için tekrarı ve içerik farkı algılanamazdı |
| Sıra | İsteğe bağlı sürüm + satır kilidi | Zorunlu sürüm | Kitchen, Menu'daki sürümü okumuyor |

## Bilinen sınır (gizlenmiyor)

`expectedVersion` olmayan **gecikmiş eski bir komut yeni durumu ezebilir**: veritabanı komutun eski olduğunu bilemez. Gerçek Kitchen
entegrasyonunda sıralama veya tazelik bilgisi (örneğin Kitchen'dan monoton sıra numarası ya da olay zamanı) ayrıca tasarlanmalıdır.

## Sonuç ve izleme

- Kitchen F-KT-03 ve servis kimliği (D-09) tamamlandığında HTTP adaptörü, sıra/tazelik mekanizması ve `onBehalfOfUserId`'nin
  güvenilir aktarımı birlikte karara bağlanır.
- `ProductAvailabilityChanged` olayı (Menu → projeksiyonlar) ve outbox, D-10 çözülene kadar yoktur.
