# ADR-MENU-009 — Order katalog sorgusu: imzalı servis kimliğiyle HTTP ucu

- **Durum:** Menu tarafı uygulandı ve gerçek Order (PR #79 dalı) ile yerelde doğrulandı. Üretimde uç, Order'ın açık anahtarı ve **restoran yetki listesi** tanımlanana kadar kapalıdır. ENV adları ve Compose bağlantısı tamam; D-09'un resmi onayı, gerçek değerlerin ortak ortamda tanımlanması ve TLS kararı bekliyor
- **Tarih:** 2026-11-28
- **Sahip:** Menu (Merve); Order (Hamza) ile ortak sözleşme, Auth (Kerem) bilgi
- **İlgili kod:** `OrderCatalogController`, `OrderCatalogService`, `OrderCallerFilter`, `OrderCallerSettings`, `OrderInternalSecurityConfiguration`, `RequestOrderCallerProvider`, `OrderSignedRequest`
- **İlgili belgeler:** [order-request-01](../menu-service/requests/order-request-01.md), [ADR-MENU-006](ADR-MENU-006-order-katalog-sorgusu.md); Order tarafı: PR #79, `docs/order-service/menu-entegrasyon-rehberi.md`

## Bağlam

[ADR-MENU-006](ADR-MENU-006-order-katalog-sorgusu.md) iç uygulama katmanını ve fail-closed kapıyı kurmuştu; HTTP ucu servis kimliği olmadığı için açılmamıştı. Auth merkezi bir servis kimliği vermez (`staff-contract.md`); her çağıran kendi anahtarıyla isteğini imzalar (Kerem, PR #49 ve #46). Hamza'nın Order adaptörü (PR #79) bu modele ve aşağıdaki sözleşmeye göre yazıldı.

## Karar

1. **Uç:** `POST /api/v1/internal/menu/catalog/products:lookup`, gövde `{"restaurantId","productIds"}` (en çok 50 farklı id; id'ler ilk görülme sırasıyla tekilleştirilir). Gateway `/api/v1/internal/**` yolunu yönlendirmez. Yanıt `200 {"products":[...]}`, `Cache-Control: no-store`.
2. **Yanıt minimaldir (karar):** `productId, name, published, available, currency, basePriceGrossMinor, taxRateBps, priceIssue, options[]`; seçenek: `optionId, name, type, priceDeltaMinor, minQuantity, maxQuantity, active`. **`availabilityStatus`, ham fiyat/KDV, kapanma nedeni ve `priceVersion` gönderilmez.** Kesin değer yoksa `null` ve `priceIssue` nedeni: KDV hariç, para biriminin minor hanesinden ince ya da aralık dışı fiyat; tam baz puan olmayan KDV. Yuvarlama ve tahmin yoktur; Order bu ürünü satılamaz sayar (D-08 açık, ortak fail-closed varsayılan).
3. **Ayrı güvenlik zinciri** (`/api/v1/internal/menu/**`, `@Order(0)`): durumsuz, form/basic/oturum/CSRF yok; `Cookie`, `Origin`, `Content-Encoding` ve ikinci `Authorization` başlığı 401. Menu'nun diğer zincirleri ve yönetim/müşteri uçları değişmedi; personel ve guest kimliği bu zincire giremez, Order kimliği onlara giremez.
4. **Kimlik doğrulama** (`Authorization: Service <JWT>`; 401 kimlik, 403 bağlama): yalnız RS256, anahtar `kid` ile yapılandırılmış Order açık anahtarlarından seçilir; `iss=restofy-order`, `sub=order-service`, **tek** `aud=restofy-menu` (birden çok audience kabul edilmez), `token_type=service`; `iat==nbf`, ömür ≤ 30 sn, UUID `jti`; `scope=menu:catalog-read`; `restaurant_id` zorunlu; `method`, `target` ve gövdenin SHA-256'sı bu isteğe bağlı; yol tam olarak bu uç ve `POST`, aynı önekteki başka yol 403. Gövde 8 KiB üstü 413. İleri/geri 5 sn saat farkı tolere edilir (Order `iat`'ı geriye almaz). Kullanılmış `jti` saklanmaz: uç salt okunurdur, yetkili çağırana aynı veriyi verir; durum değiştiren bir yol eklenirse komut kimliği gerekir.
5. **Restoran yetkisi (anahtar başına, karar 3):** (a) imzadaki `restaurant_id` gövdedeki `restaurantId` ile eşit olmalı (`OrderCatalogService`, aksi 403); (b) **çağıran anahtar o restoran için yetkili olmalı**. Yetki kaynağı operatör yapılandırmasıdır: `MENU_ORDER_CALLER_RESTAURANTS` = `{"<kid>":["<restoran uuid>", ...]}` (özellik `restofy.menu.order.caller-restaurants`; **onaylı ENV**, `.env.example` ve Compose'un yalnız `menu-service` bloğunda, değersiz). **Girdisi olmayan anahtar hiçbir restoranı okuyamaz (403); joker ve "tüm restoranlar" yoktur.** Tanımsız anahtara ait liste, geçersiz UUID ve bilinmeyen anahtar başlangıçta uygulamayı durdurur (hata ayar adını söyler, değeri asla göstermez). Başka restoranın ürünü, yetkili restoranda bile yanıtta yoktur.
6. **Açık anahtarlar:** `MENU_ORDER_CALLER_PUBLIC_KEYS` = `{"<kid>":"<Base64 X.509 RSA>"}` (≥ 2048 bit; **onaylı ENV**, `.env.example` ve Compose'un yalnız `menu-service` bloğunda). Boş (varsayılan) = kimse güvenilmez, her istek 401. Özel anahtar Menu'ye hiç gelmez.
7. **TLS:** Order çağrıyı yalnız HTTPS'le yapar (`MENU_SERVICE_URL`). Menu uç, şemayı kendisi doğrulayamaz (TLS sunucuda veya önünde sonlanır); Spring'in standart `server.ssl.*` ayarlarıyla açılır, Menu koduna eklenmedi. Üretimde TLS sonlandırma ve Compose'un düz HTTP `MENU_SERVICE_URL` varsayılanı **açık altyapı kararıdır**; HTTPS kuralı gevşetilmedi.

## Doğrulama (abartılmadan)

| Seviye | Kapsam |
|---|---|
| Birim | `OrderCallerFilterTests` (sabit saatle her sınır: ömür, skew, bağlama, yetki, algoritma karışıklığı, tarayıcı başlıkları, yol/yöntem), `OrderCallerSettingsTests` |
| Uç + PostgreSQL | `OrderCatalogEndpointApiTests`: Order'ın imzalayıcısının ürettiği kimlikle; yanıt şekli, fiyat/KDV/seçenek, izolasyon, restoran yetkisi, gövde doğrulaması, sızıntı; yanıt ayrıca Order adaptörünün kurallarına (kaynaktan yeniden uygulanmış) karşı denetlendi |
| **Gerçek Order ↔ gerçek Menu** | Order (PR #79 `d973223`, `order-menu` profili, sahte Menu adaptörü yok) ve Menu iki ayrı süreç, atılabilir PostgreSQL, üretilmiş anahtarlar, **HTTPS** (kendi imzalı sertifika). Sepete ürün ekleme: satılabilir ürün 10000 minor + %10; KDV hariç ürün 409; bilinmeyen 404; başka restoran 404; Menu'da fiyat değişince sepette `priceChanged`; kapalı ürün 409; yetki listesi yokken ve Menu kapalıyken 503; geri gelince 200. Günlüklerde anahtar ve JWT yok |
| Yapılmayan | Order'ın QR ve Auth'u bu denemede **sahte** (dev profili); gerçek QR ile üç servisli akış, Gateway, Compose ağı ve üretim TLS'i denenmedi |

## Bekleyenler

- **PR #79 `dev`'e merge edilmedi** ve `docker-compose.yml`'de `dev` ile çakışıyor (Hamza).
- Gerçek `MENU_ORDER_CALLER_RESTAURANTS` değerinin ortak ortamda tanımlanması (Alper/Hamza): tanımlanana kadar anahtar tanımlı olsa bile **her istek 403** (fail-closed).
- D-09'un hoca onayı; anahtar dağıtımı/rotasyonu; TLS; hız sınırı bütçesi; `priceVersion` (üretilmiyor).
