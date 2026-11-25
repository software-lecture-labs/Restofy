# ADR-AUTH-002 — Hız sınırlama stratejisi

- **Durum:** Kabul edildi; strateji birleştirme (#9) ve istemci adresi (#10) kararları uygulandı
- **Tarih:** 2026-10-20 (güncelleme: 2026-11-24)
- **Sahip:** Auth (Kerem Kalyoncu)
- **İlgili kod:** `RateLimitStore`, `PostgresRateLimitStore`, `LoginRateLimiter`, `MfaPeerLimiter`,
  `PasswordResetLimiter`, `ClientAddressResolver`, Gateway `ClientAddressHeaderFilter`

## Bağlam

Auth'ta eskiden iki ayrı depolama vardı: giriş ve MFA adres sınırları süreç belleğinde
(`BoundedWindows`), parola sıfırlama sınırları PostgreSQL'de ve **global** bir advisory kilit
arkasındaydı. Bellek içi sayaçlar örnekler arasında paylaşılmaz (N örnekte etkin sınır ~N katı),
global kilit ise farklı kullanıcıların isteklerini sıraya sokar.

## Karar (#9): tek strateji, PostgreSQL

Bütün sınırlar tek `rate_limits` tablosunda, tek `RateLimitStore` arkasındadır. Eşikler değişmedi.

| Sınırlayıcı | Kova | Tablo dolunca |
|---|---|---|
| `LoginRateLimiter` | hesap+adres (10/15 dk), hesap (50/15 dk) | En az denemeli kova silinir (`EVICT_LOWEST`) |
| `MfaPeerLimiter` | adres (30/dk) | `EVICT_LOWEST` |
| `PasswordResetLimiter` | adres, istek, bekleme, saatlik, günlük, yanlış faktör, kod tahmini | Yeni kova reddedilir, 429 (`REJECT`) |
| MFA hesap kilidi (`StaffMfa`) | PostgreSQL satırı | Bu karardan bağımsız, hesap 5 dk kilitlenir |

Depo şunları sağlar:

- **Atomik sayım.** Her deneme tek `UPDATE` ya da `INSERT ... ON CONFLICT DO UPDATE` ile yazılır;
  eşzamanlı istekler ve birden çok Auth örneği artış kaybedemez. Global kilit yoktur.
- **Kendi bağlantısı.** Depo, otomatik commit'li küçük ayrı bir havuz kullanır
  (`auth.rate-limit.pool-size`, varsayılan 5). Sayaç, çağıranın transaction'ı geri alınsa da kalır
  (başarısız tahmin geri alınamaz); çağıranın tuttuğu bağlantıyı beklemek de mümkün değildir.
- **Hash'li anahtar.** Tabloda hesap ya da adres yoktur, yalnız HMAC değeri vardır.
- **Süre temizliği.** Süresi dolan kovalar dakikada bir silinir; tablo doluyken ayrıca anında temizlenir.
- **Kapalı hata.** Veritabanı sayaçlara ulaşamazsa istek 503 ile reddedilir; sınırsız hizmet verilmez.

## Hesap kilitleme (DoS) kararı

Eski kural, hesap başına 15 dakikada 10 denemeydi. E-postayı bilen biri adrese bakmaksızın bu
bütçeyi tüketip gerçek sahibi dışarıda tutabilirdi. Yeni kural:

1. **Hesap + uzak adres** başına 10 deneme (saldırgan yalnız kendi adresini tüketir).
2. Hesap başına toplam **50** deneme tavanı (çok adrese yayılmış tahmine karşı emniyet ağı).

Artık kalan risk: birçok adresten 50 deneme yapan biri 15 dk boyunca hesabı kilitleyebilir.
Bunu kabul ediyoruz çünkü (a) ADMIN hesabı zaten MFA ile korunur, parola tahmini tek başına
yetmez, (b) parolalar en az 12 karakterdir, (c) alternatif olan tam kilitsizlik, dağıtık
kaba kuvveti serbest bırakır. Kademeli gecikme (artan bekleme) ileride değerlendirilebilir.


## Tablo dolunca masum kullanıcı cezalandırılmamalı, ama kilit silinmemeli

Sahte hesaplarla tabloyu dolduran bir saldırgan, yeni kovayı reddeden bir tasarımda bütün masum
kullanıcıları 429'a düşürebilirdi. Bu yüzden **bütün** sınırlarda (giriş, MFA, parola sıfırlama) tablo
dolunca yer açılır; önce süresi dolmuş kovalar silinir. Eski sürüm **en eski** kovayı siliyordu; bu, saldırganın tabloyu doldurarak bir hesabın
kilitli sayacını silmesine izin verirdi. Yeni kural:

1. En az denemeli kova silinir (eşitlikte en eski). Limite ulaşmış (**kilitli**) kova asla silinmez.
2. Tabloda yalnız kilitli kova kalırsa yeni kova reddedilir (fail-closed). Kilitli kovaları doldurmak
   çok pahalıdır.
3. Hesap başına tavan (50) ayrı kovada tutulur; hesap+adres kovası silinse bile işlemeye devam eder.

Bilerek **adres başına toplam bütçe yoktur**: adresler ayırt edilemediğinde (`AUTH_TRUSTED_PROXY_CIDRS`
boş ya da eskimiş) herkesi tek kovaya toplar ve hiçbir e-posta bilmeden tüm girişleri kilitlemeyi ucuzlatırdı.
Yapılandırma eksikse Auth açılışta uyarı verir.

Bir hedef hesabın sayacını sıfırlatmak için tabloyu, hedeften yüksek sayaçlı ama henüz kilitlenmemiş
kovalarla doldurmak gerekir; bu, onlarca bin istek ister ve karşılığında birkaç ek tahmin hakkı kazandırır.

Parola sıfırlama önceden tablo dolunca yeni kovayı reddediyordu (`REJECT`): rastgele adreslerle tabloyu
dolduran biri parola sıfırlamayı herkese kapatabilirdi. #9'daki karar gereği o da aynı kurala geçti; ayrı
politika kaldırıldı. Kod tahmin bütçesi tükenmiş (kilitli) bir kova yine asla silinmez. Adres başına 30
istek/dk sınırı tabloyu hızlı doldurmayı güçleştirir. V8'deki `evictable` kolonu artık hep `true` yazılır;
uygulanmış migration değiştirilmediği için kolon yerinde kalır.

Tablo boyutu (`auth.rate-limit.max-buckets`, varsayılan 10 000) **yumuşak** sınırdır: global kilit
olmadığı için aynı anda yeni kova açan isteklerin sayısı kadar aşılabilir (havuz boyutuyla sınırlı).

## İstemci adresi (#10)

Gateway arkasında `request.getRemoteAddr()` Gateway'in adresidir; sınırlar bu yüzden tek kovaya düşerdi.

- **Gateway** istemcinin gönderdiği `X-Forwarded-For`, `Forwarded` ve `X-Real-IP` başlıklarını siler
  (`ClientAddressHeaderFilter`) ve kendisine bağlanan gerçek adresi `X-Forwarded-For` olarak ekler.
- **Auth** (`ClientAddressResolver`) bu başlığı yalnız soket adresi `AUTH_TRUSTED_PROXY_CIDRS` içindeyse
  okur ve **sağdan** yürür: güvenilir proxy'lerin eklediği son adres istemcidir, soldaki girdiler
  istemcinin yazdığıdır ve inanılmaz. Liste boşsa (varsayılan) başlık yok sayılır.
- `0.0.0.0/0` ve `::/0` reddedilir; ayar yalnız Gateway'in tek adresi olmalıdır (`/32`), tüm Docker
  ağı değil: ağdaki her container güvenilir olurdu.
- Docker Compose'ta `restofy_network` sabit `10.213.0.0/24` subnet'ini kullanır ve Gateway'in adresi
  `10.213.0.10` olarak sabitlenmiştir. `AUTH_TRUSTED_PROXY_CIDRS`'nin compose varsayılanı bu yüzden
  `10.213.0.10/32`'dir. Diğer container'lar yalnız üst yarıdan (`10.213.0.128/25`) adres alır, Gateway'in
  adresi başkasına verilmez. Subnet ev/ofis ağlarıyla (192.168.x, 10.0.x) ve Docker'ın otomatik
  ağlarıyla (172.17–172.31) çakışmayacak biçimde seçildi. Ağ ilk kez değişince bir kez
  `docker compose down` (volume silmeden, `-v` olmadan) gerekir. PROD'da da sabit bir Gateway adresi verilir.
- IPv6 istemciler rate limit anahtarında **/64** olarak gruplanır (`RateLimitStore.peerKey`): bir /64
  genelde tek abonedir ve içindeki adresleri döndürerek hesap+adres bütçesini tazeleyemez. Audit kaydı
  tam adresi tutar.

## Gelecek işler

- Ayrı bir Redis ya da Gateway tabanlı sınırlama gerekirse `RateLimitStore` arayüzü değiştirilmeden
  yeni bir uygulamayla eklenebilir.
- Mail servisinin kendi iş kabul kilidi bu kararın kapsamı dışındadır.
