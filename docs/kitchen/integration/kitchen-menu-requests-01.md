# Kitchen ↔ Menu talepleri 01 (KITCHEN-INT-01)

- **Tarih:** 26 Kasım 2026
- **Talep eden:** Kitchen (Nisa)
- **İlgili servis / sorumlu:** Menu (Merve). Servis kimliği için Auth (Kerem), ENV/Compose için ekip onayı.
- **İncelenen:** `origin/dev` `6e72336`; PR #50 (`docs/menu-kitchen-request-01`, `9191871`) ve PR #46 (`docs/menu-auth-request-01`, `f3b8bf1`) **yalnız okundu**, birleştirilmedi ve çalıştırılmadı. Menu'nün Kitchen'dan istedikleri PR #50'deki [KITCHEN-REQUEST-01](https://github.com/software-lecture-labs/qr-management-system/blob/9191871/docs/menu-service/requests/kitchen-request-01.md) belgesindedir.

Bu belge Menu'nün talebinin **kimlik doğrulama ve delegasyon** bölümüne cevap verir ve Kitchen'ın Menu'den beklediğini yazar. Menu kodunda değişiklik içermez.

**Kapsam:** KITCHEN-INT-01 bir Kitchen–Auth entegrasyonudur. Menu tarafında bu task yalnız **güvenilir servis kimliği ve personel delegasyonunun güvenlik boyutunu** kapsar (Bölüm A). Menu HTTP uçları, ürün açma/kapama entegrasyonu ve model kararları sonraki işlerdir (Bölüm B) ve bu taskın tamamlanma şartı **değildir**.

# A. KITCHEN-INT-01: servis kimliği ve delegasyon güvenliği

Gereksinim **karşılanmadı**. Beklenen ortak sözleşme ve Kitchen'da kalan güvenlik işi [AUTH-05](kitchen-auth-requests-01.md) içindedir.

## A.1 Menu talebinin kimlikle ilgili maddeleri

| Menu'nün sorusu | Kitchen'ın bugünkü, koddan kesin durumu |
|---|---|
| Restoran ve kullanıcı nereden geliyor? (§5) | Yalnız doğrulanmış personel oturumundan (imzalı `restaurant_id`, `sub`). Gövde, header ve query değerleri yok sayılır; testlidir |
| Tek mi çok restoran mı? | Kitchen tek restorana sabitlenmiş değildir; her komut o personelin imzalı restoranını taşır. Auth MVP'si tek restoranlıdır |
| `onBehalfOfUserId` kim tarafından imzalanacak? (§6 m.8) | Karar verilmedi ([AUTH-05](kitchen-auth-requests-01.md)). Kitchen'ın portu `requestedByUserId` taşır; personel cookie'si/JWT'si Menu'ye gönderilmez |
| Servis kimliği (§6 m.8) | Yok. Gerçek adaptör yazılmadı; her komut `503 MENU_NOT_INTEGRATED` döner, hiçbir zaman başarı gösterilmez |

## A.2 MENU-02 — Servis kimliği ve delegasyon kapısı
**Beklenen:** AUTH-05 kararına uyan doğrulayıcı; servis kimliği yalnız Kitchen'a açılan işlemleri açar (komut ve kapalı ürün sorgusu); restoran kapsamı imzalı olur; personel kimliğinin yetki mi yoksa denetim bilgisi mi olduğu yazılır.
**Kabul:** Personel JWT'si/cookie'si servis kimliği yerine geçmez; yanlış restoran, scope, süre ve gövde özeti reddedilir. Gövde özeti koşulu, AUTH-05'te seçilecek model isteği imzaya bağlarsa geçerlidir.

---

# B. Sonraki entegrasyon işleri (bu taskın tamamlanma şartı değil)

## B.1 MENU-01 — Komut ve kapalı ürün sorgusu için HTTP sözleşmesi
**Mevcut:** Menu'de Kitchen için HTTP ucu yok (`DenyAllKitchenCallerProvider`; PR #50 de "uygulanmadı" yazar).
**Etki:** `PATCH /api/v1/kitchen/products/{productId}/availability` ve `GET /api/v1/kitchen/products/closed` personel kimliği doğrulansa bile 503 döner.
**Beklenen:** Yol, yöntem, istek/yanıt JSON'u, hata kodları, zaman aşımı beklentisi.
**Kabul:** Ortak örnek JSON'lar iki tarafın testinde; aynı komut kimliği aynı sonucu döner; zaman aşımı ve 5xx sonucu "bilinmiyor" sayılır.

## B.2 Koddan kesin olarak bilinen diğer cevaplar

- **Yeniden deneme (m.9):** Kitchen arka planda yeniden denemez. Kullanıcı aynı `Idempotency-Key` ile tekrar gönderirse Kitchen Menu'ye yeniden sorar. Menu adaptörü olmadığı için istemci zaman aşımı değeri de uygulanmış değildir.
- **Saklama (m.10):** Kitchen'da komut kayıtlarını silen bir iş yoktur; kayıtlar süresiz kalır. Bu bir saklama politikası kararı değildir (D-11).
- **Ret (m.11):** Kesin ret Kitchen'da kalıcıdır; aynı komut kimliği Menu'ye tekrar sorulmaz.

## B.3 Henüz cevaplanmayanlar (ürün/sözleşme kararı)

PR #50'nin 1–7 ve 12. maddeleri (kapatma nedeninin zorunluluğu, başarı makbuzunun alanları, `NO_CHANGE`/`replayed`, `expectedVersion`, `PRODUCT_INACTIVE`, kapalı ürün listesinin alanları, hata kodları, sürümleme) Auth entegrasyonunun kapsamı değildir ve burada **karara bağlanmadı**. Menu'nün işaretlediği iki engelleyici (neden kuralı ve `changedAt` içeren makbuz) Kitchen kodunda aynen duruyor.

## B.4 Sonraki iş (bu branch'te değil)

Öneri: güncel `dev` tabanlı ayrı branch `feature/kitchen-int-03-menu-integration`: gerçek Menu adaptörü ve model uyarlaması. Ön koşullar: AUTH-05, MENU-01/02 ve PR #50'nin 1–2. maddelerinde ürün kararı.

Menu'den istenen, bu başlıkları karşılayan (veya karşı öneri getiren) bir `request`/yanıt belgesi içeren PR'dır. Yanıt gelmezse mevcut sözleşmeden kesin olarak yapılabilecek bir gerçek adaptör işi **yoktur**: Menu'de çağrılacak uç bulunmuyor. Fail-closed varsayılan korunur.
