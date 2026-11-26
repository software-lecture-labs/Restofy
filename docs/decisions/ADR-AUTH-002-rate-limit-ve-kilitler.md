# ADR-AUTH-002 — Hız sınırlama stratejisi

- **Durum:** Kabul edildi; strateji birleştirme (#9) ve istemci adresi (#10) kararları uygulandı
- **Tarih:** 2026-10-20 (güncelleme: 2026-11-26, #60 incelemesi)
- **Sahip:** Auth (Kerem Kalyoncu)
- **İlgili kod:** `RateLimitStore`, `PostgresRateLimitStore`, `LoginRateLimiter`, `MfaPeerLimiter`,
  `PasswordResetLimiter`, `ClientAddressResolver`, Gateway `ClientAddressHeaderFilter`

## Bağlam

Auth'ta eskiden iki ayrı depolama vardı: giriş ve MFA adres sınırları süreç belleğinde
(`BoundedWindows`), parola sıfırlama sınırları PostgreSQL'de ve **global** bir advisory kilit
arkasındaydı. Bellek içi sayaçlar örnekler arasında paylaşılmaz (N örnekte etkin sınır ~N katı),
global kilit ise farklı kullanıcıların isteklerini sıraya sokar.

## Karar (#9): tek strateji, PostgreSQL

Bütün sınırlar tek `rate_limits` tablosunda, tek `RateLimitStore` arkasındadır. Eski eşikler değişmedi; #60 incelemesiyle girişe adres başına
toplam bütçe eklendi.

| Sınırlayıcı | Kova | Tablo dolunca |
|---|---|---|
| `LoginRateLimiter` | adres (100/15 dk, yalnız istemci adresi biliniyorsa), hesap+adres (10/15 dk), hesap (50/15 dk) | Penceresi en erken kapanan kilitsiz kova silinir (`EVICT_OLDEST`) |
| `MfaPeerLimiter` | adres (30/dk) | `EVICT_OLDEST` |
| `PasswordResetLimiter` | adres, istek, bekleme, saatlik, günlük, yanlış faktör, kod tahmini | `EVICT_OLDEST` (önceden `REJECT`, aşağıya bkz.) |
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

1. Penceresi en erken kapanacak kova silinir (eşitlikte en az denemeli). Limite ulaşmış (**kilitli**) kova
   asla silinmez. İlk sürüm en az denemeli kovayı siliyordu; #60 incelemesinde bunun bir açık olduğu
   görüldü: tablo doluyken bir girişin iki yeni kovası (hesap+adres ve hesap, ikisi de 1 deneme) birbirini
   siliyor, hiçbiri limite ulaşmıyordu. Tablo bir kez dolunca, sayacı olmayan her hesaba sınırsız deneme
   yapılabiliyordu (yerelde doğrulandı: 15 yanlış denemenin hiçbiri 429 almadı). Yeni kovanın penceresi en
   geç kapanır; daha eski kova varken silinmez.
2. Tabloda yalnız kilitli kova kalırsa yeni kova reddedilir (fail-closed). Kilitli kovaları doldurmak
   çok pahalıdır.
3. Hesap başına tavan (50) ayrı kovada tutulur; hesap+adres kovası silinse bile işlemeye devam eder.

**Adres başına toplam bütçe (#60 incelemesi).** Hesaptan bağımsız olarak istemci adresi başına 15 dakikada
100 giriş denemesi sayılır. En önce kontrol edilir; aşılınca hesap kovası açılmaz, hesap bütçesi de harcanmaz.
Böylece tek adres bir pencerede en fazla 2 × 100 + 1 = 201 kova açabilir; varsayılan 10 000'lik tabloyu
doldurmak için en az ~50 farklı adres gerekir.

- **Neden 100:** Bir restoranın bütün personeli aynı internet çıkışını paylaşır ve başarılı girişler de
  sayılır. Kalabalık bir vardiya değişimi (30 kişi, birkaç deneme) bile bu sınırın altında kalır. Daha
  düşük bir değer saldırıya karşı çok az şey kazandırır, çünkü asıl korumayı yukarıdaki silme kuralı sağlar.
- **Yalnız adres biliniyorsa:** Bütçe yalnız `AUTH_TRUSTED_PROXY_CIDRS` doluyken uygulanır
  (`ClientAddressResolver.identifiesClients`). Ayar boşsa herkes Gateway'in adresinden gelir; bütçe herkese
  ortak olur ve hiçbir e-posta bilmeden bütün girişleri kilitlemeyi ucuzlatırdı. İlk sürümde adres
  bütçesinin bilerek konmamasının gerekçesi buydu, bu durum için geçerliliğini koruyor. Yapılandırma eksikse
  Auth açılışta uyarı verir.

**Kalan risk.** Bir hedef hesabın kilitsiz sayacını sıfırlatmak için tablodaki ondan eski bütün kilitsiz
kovaları dışarı itecek kadar yeni kova açmak gerekir: varsayılanla ~10 000 kova, adres bütçesiyle en az ~50
adres. Karşılığında hesap+adres başına birkaç ek tahmin kazanılır; ADMIN ayrıca MFA ister. Tabloyu yalnız
kilitli kovalarla doldurup yeni gelenlere 60 sn'lik 429 verdirmek ise daha pahalıdır. Bir adres bir pencerede
en fazla ~13 kilitli kova üretebilir, bu yüzden 15 dakika içinde ~800 farklı adres (IPv6'da /64) gerekir. Bu
ölçekteki dağıtık bir saldırı uygulama katmanının değil altyapının (Gateway, WAF) işidir. Gerekirse
`AUTH_RATE_LIMIT_MAX_BUCKETS` büyütülür.

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
