# ADR-MENU-009 — Menu API hız sınırı (rate limiting)

- **Durum:** Kabul edildi (geliştirme aşaması). **Limit değerleri ekip onayı bekliyor**; çoklu instance tutarlılığı PostgreSQL ile sağlanır, Redis/Gateway çözümü yoktur
- **Tarih:** 2026-11-25
- **Sahip:** Menu (Merve)
- **İlgili kod:** `RateLimiter`, `RateLimitSettings`, `RateLimitScope`, `RateLimitSubjects`, `PostgresRateLimitStore`, `PublicRateLimitInterceptor`, `AdminRateLimitInterceptor`, `RateLimitWebConfiguration`, migration `V68`
- **Dayanak:** `docs/SECURITY.md` §12, [ADR-WAITER-002](ADR-WAITER-002-misafir-cagri-hiz-siniri.md), [ADR-AUTH-002](ADR-AUTH-002-rate-limit-ve-kilitler.md), [ADR-QR-002](ADR-QR-002-security.md) K7

## Bağlam

Menu müşteriye açık okuma uçları ve personel yönetim uçları sunar; ikisinde de hız sınırı yoktu (README "Hız sınırı yok"). Repoda ortak bir hız sınırı altyapısı **yoktur**: Compose ve `.env.example`'da Redis yok, Gateway'de limit kuralı yok, "güvenilir proxy" ve istemci IP'si kararı verilmedi (SECURITY.md §12: `X-Forwarded-For` doğrudan güvenilmez). Ekip emsali: Waiter sayacı veritabanında tutar ve IP kullanmaz; Auth'un bellek içi sınırlayıcıları instance başına sayar, dolunca eski anahtarı siler; QR K7 "depo kesintisi sessizce sınırsız erişime dönmeyecek" der.

## Karar

1. **Sayaç Menu'nun kendi PostgreSQL'inde.** `api_rate_limit_windows (scope, subject, window_start, request_count)` (V68). Her istek tek bir atomik `INSERT ... ON CONFLICT DO UPDATE ... RETURNING` ile sayılır; eşzamanlı istekler (farklı instance'larda da) farklı sayı alır. Pencere ve kalan süre **veritabanı saatiyle** hesaplanır, instance saatleri önemsizdir. Yeni altyapı, yeni ENV ve başka servisin veritabanı yoktur.
2. **Sayaç anahtarı yalnız sunucunun doğruladığı değerlerden.** Kapsam (`RateLimitScope`) ve konu: doğrulanmış restoran kimliği, doğrulanmış aktör (restoran + kullanıcı) veya bağlantının TCP eş adresi (`getRemoteAddr()`). Başlık, sorgu, gövde ve URL'deki ham referans anahtar olmaz; `X-Forwarded-For`, `X-Restaurant-Id`, `X-User-Id` okunmaz. Anahtar kısa ve sabit biçimli olduğundan tablo istemci metniyle doldurulamaz.
3. **Üç sınıf.**

| Sınıf | Kapsam ve anahtar | Varsayılan (öneri) |
|---|---|---|
| Müşteri menüsü | Doğrulanmış restoran: `PUBLIC_RESTAURANT` (restoran) | 600 / 60 sn |
| | Kimsenin doğrulamadığı istek: `PUBLIC_UNVERIFIED` (eş adresi) | 30 / 60 sn |
| Yönetim | Okuma: `ADMIN_READ` (kullanıcı) ve `ADMIN_RESTAURANT_READ` (restoran) | 120 ve 600 / 60 sn |
| | Yazma: `ADMIN_WRITE` (kullanıcı) ve `ADMIN_RESTAURANT_WRITE` (restoran) | 30 ve 150 / 60 sn |
| | Doğrulanmış aktör yok: `ADMIN_UNVERIFIED` (eş adresi) | 20 / 60 sn |
| İç servis | `INTERNAL_RESTAURANT` (doğrulanmış restoran) | 600 / 60 sn |

   Değerler `restofy.menu.rate-limit.*` **özellikleridir** (ENV değil; Spring gevşek bağlama ile `RESTOFY_MENU_RATELIMIT_...` olarak da verilebilir). Koda gömülü varsayılan ve kapatma anahtarı yoktur: eksik, sıfır veya aralık dışı değer (`1..1.000.000` istek, `1..3600` sn) uygulamayı başlatmaz.
4. **Restoran izolasyonu.** Bir restoranın bütçesi diğerinin sayacına dokunmaz. Doğrulanmamış istek doğrulanmış restoranın bütçesini harcayamaz ve tersi de geçerli. Yönetimde kullanıcı kendi bütçesinden sonra restoranın bütçesine sayılır; kendi bütçesini aşan kullanıcı restoranınkini harcamaz.
5. **Yanıt.** Aşımda `429 Too Many Requests`, `Retry-After` (pencerenin bitimine kalan saniye, en az 1), `Cache-Control: no-store`, sabit RFC 9457 gövdesi. Hangi sayaç, anahtar veya limit olduğu yazılmaz.
6. **Fail-closed.** Sayaç sayılamazsa (veritabanı hatası, `null` yanıt) istek **geçirilmez**: `503`, `Retry-After` (`fail-closed-retry-after-seconds`, öneri 5). "Sayamadım" asla "limit altında" sayılmaz.
7. **Yer.** İki MVC interceptor (müşteri: `/api/v1/menu/public/**`, yönetim: diğer `/api/v1/menu/**`), Spring'in kendi yol kalıplarıyla, controller'dan **önce** ve DB transaction'ından **önce** (sayaç bağlantısı transaction içinde tutulmaz, havuz kilitlenmesi riski yok). Güvenlik zincirinden sonra çalışırlar: girişsiz (401) ve CSRF'siz (403) istekler sayılmaz.
8. **İdempotency bozulmaz.** Reddedilen istek işlenmeden döner; hiçbir şey yazılmaz. Aynı istek sonra tekrar gönderilince bir kez uygulanır, üçüncü gönderim sürüm çakışması (409) olur. Testle doğrulanır (availability değişimi).
9. **İç servis politikası hazır, bağlı değil.** `RateLimiter.requireServiceCall(restaurantId)` ve `INTERNAL_RESTAURANT` bütçesi vardır. Menu'da henüz iç servis HTTP ucu yok (Order/Kitchen talepleri onaysız, servis kimliği D-09 açık); uç eklendiğinde, çağıran doğrulandıktan sonra ve iş yapılmadan önce çağrılır. Kitchen komut servisine bağlanmadı: komutun kalıcı idempotency'siyle etkileşimi uç tasarlanırken netleşir.

## Seçenekler

| Seçenek | Artı | Eksi | Sonuç |
|---|---|---|---|
| Bellek içi (Auth'taki gibi) | Hızlı, yazma yok | Instance başına sayar (N instance, N× limit); tabloyu saldırgan doldurabilir | Seçilmedi |
| Redis | Düşük gecikme, yazma yükü yok | Repoda yok; yeni bağımlılık, ENV ve işletim onayı gerekir | Üretimde çalışıyormuş gibi gösterilmez; ortak karar bekler |
| Gateway'de | Servis kodundan bağımsız | Gateway'de kural yok; restoran/kullanıcı bazlı doğrulanmış kimlik orada yok | Ayrıca gereklidir, yerine geçmez (QR K7) |
| **Menu'nun PostgreSQL'i** | Yeni altyapı yok; instance'lar arası tutarlı; atomik | Sayılan istek başına bir yazma; çok yoğun bir restoran için sıcak satır | **Seçildi** |

## Sonuçlar ve bilinen sınırlar (gizlenmiyor)

- **Sabit pencere:** pencere sınırında bir istemci bütçesinin yaklaşık iki katını kullanabilir.
- **Yazma yükü:** her sayılan istek bir DB yazısıdır; sıcak bir restoran anahtarı satır kilidinde sıraya girer. Yük artarsa Redis veya Gateway düşünülmelidir. Bitmiş pencereler (2 saatten eski) instance başına en çok dakikada bir silinir.
- **Müşteri bütçesi restoran başınadır.** Güvenilir istemci IP'si ve doğrulanmış guest kimliği henüz yok: tek bir kötü niyetli müşteri restoranın bütçesini tüketip diğer müşterileri engelleyebilir. Guest doğrulaması (MENU-INT-04) ve güvenilir proxy kararı geldiğinde müşteri başına ayrı bütçe eklenir. Bugün müşteri uçları üretimde zaten 404 döner (doğrulayıcı yok), yani pratikte yalnız `PUBLIC_UNVERIFIED` çalışır.
- **Doğrulanmamış istek bütçesi kaba:** Gateway arkasında eş adresi Gateway'in adresidir; bu bütçe tüm doğrulanmamış trafiğin ortak bütçesi gibidir (Auth ADR-AUTH-002'deki aynı sorun). Hiçbir veri dönmediği için kabul edildi.
- **Sıralama:** MENU-INT-04'ün guest doğrulama interceptor'ı (PR #55) bu interceptor'lardan önce çalışmalıdır; burada sıra `100` ile açıkça geç tutuldu. Çözümleyici ağ çağrısı yapmamalıdır (bu interceptor onu bir kez daha sorar).
- **MENU-INT-01 ile uyum:** yönetim interceptor'ı aktörü `CurrentActorProvider` portundan alır; hangi gerçek sağlayıcı bağlanırsa onunla çalışır. Bugün `dev`'de yönetim uçlarına girilemez (Auth bağlı değil).
- **Doğrulanmadı:** iki gerçek JVM instance ile uçtan uca deneme ve yük testi yapılmadı. Çoklu instance davranışı aynı veritabanını paylaşan iki store örneği ve eşzamanlı istek testleriyle sınandı. Varsayılan değerler ürün/operasyon onayı bekler.
- **Test profili** limitleri yükseltir (`src/test/resources/config/application-test.properties`), çünkü mevcut paketler tek test restoranına dakikada binlerce istek yollar. Gerçek varsayılanlar `RateLimitSettingsTests` ile ayrı sınanır.
