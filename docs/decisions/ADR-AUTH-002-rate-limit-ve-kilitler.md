# ADR-AUTH-002 — Hız sınırlama stratejisi ve global kilitler

- **Durum:** Kabul edildi (geliştirme aşaması); strateji birleştirme kararı **açık**
- **Tarih:** 2026-10-20
- **Sahip:** Auth (Kerem Kalyoncu)
- **İlgili kod:** `LoginRateLimiter`, `MfaPeerLimiter`, `BoundedWindows`, `PasswordResetLimiter`, `AdvisoryLocks`

## Bağlam

Auth'ta üç ayrı sınırlama yaklaşımı vardır ve bunların davranışı farklıdır:

| Sınırlayıcı | Depolama | Dolunca |
|---|---|---|
| `LoginRateLimiter`, `MfaPeerLimiter` | Süreç belleği (`BoundedWindows`) | En eski pencereler silinir, yeni kullanıcı reddedilmez |
| `PasswordResetLimiter` | PostgreSQL + `pg_advisory_xact_lock` | Fail-closed: yeni kova açılmaz, 429 |
| MFA hesap kilidi (`StaffMfa`) | PostgreSQL satırı | Hesap 5 dk kilitlenir |

## Hesap kilitleme (DoS) kararı

Eski kural, hesap başına 15 dakikada 10 denemeydi. E-postayı bilen biri adrese bakmaksızın bu
bütçeyi tüketip gerçek sahibi dışarıda tutabilirdi. Yeni kural:

1. **Hesap + uzak adres** başına 10 deneme (saldırgan yalnız kendi adresini tüketir).
2. Hesap başına toplam **50** deneme tavanı (çok adrese yayılmış tahmine karşı emniyet ağı).

Artık kalan risk: birçok adresten 50 deneme yapan biri 15 dk boyunca hesabı kilitleyebilir.
Bunu kabul ediyoruz çünkü (a) ADMIN hesabı zaten MFA ile korunur, parola tahmini tek başına
yetmez, (b) parolalar en az 12 karakterdir, (c) alternatif olan tam kilitsizlik, dağıtık
kaba kuvveti serbest bırakır. Kademeli gecikme (artan bekleme) ileride değerlendirilebilir.

## Tablo dolunca masum kullanıcı cezalandırılmamalı

Bellek içi sınırlayıcılarda yeni anahtarı reddetmek, sahte hesaplarla tabloyu dolduran bir
saldırganın bütün masum kullanıcıları 429'a düşürmesine yol açardı. Bu yüzden **en eski**
pencereler silinir. Bedeli: sel sırasında en eski anahtarların sayaçları unutulur. Login
isteği zaten BCrypt maliyeti taşıdığından sel yine de sınırlı hızda ilerler.
`PasswordResetLimiter` bunun tersine bilerek fail-closed'dır: kovalar 6 haneli kodun
tahminini sınırlar ve adres başına 30 istek/dk sınırı tabloyu hızlı doldurmayı güçleştirir.

## İstemci adresi (peer) sorunu

Sınırlayıcılar `request.getRemoteAddr()` kullanır. Gateway arkasında bu, Gateway'in kendi adresidir;
yani tüm tarayıcı trafiği tek "adres" gibi görünür ve adres bazlı sınırlar pratikte
hesap bazlıya yakınsar. Doğru çözüm, **yalnız güvenilir proxy'den** gelen `X-Forwarded-For` değerini
kabul etmek (Spring `server.forward-headers-strategy` + güvenilir iç ağ listesi) ve Gateway'in bu
başlığı eklediğini doğrulamaktır. Sahte başlık sınırı aşmak için kullanılabileceğinden bu bir
güvenlik sözleşmesi değişikliğidir ve ayrıca onay ister. **Açık iş.**

## Yatay ölçekleme

Bellek içi sayaçlar örnekler arasında paylaşılmaz; N örnekte etkin sınır yaklaşık N katıdır.
Parola sıfırlama sınırları ise veritabanında olduğu için tutarlıdır. Tek strateji seçilmelidir:

| Seçenek | Not |
|---|---|
| Hepsi PostgreSQL | Yeni altyapı yok, her istekte yazma yükü |
| Redis | Düşük gecikme, yeni bağımlılık ve işletim |
| Gateway'de | Servis kodundan bağımsız, ama hesap bazlı kural gateway'e taşınmalı |

Henüz tek Auth örneği çalıştığı için bu karar ertelenmiştir; ikinci örnek eklenmeden önce
verilmelidir ve ayrı bir issue ile izlenir.

## Global advisory kilitleri

`AdvisoryLocks.PASSWORD_RESET_LIMITS` (Auth) ve `AdvisoryLocks.MAIL_JOB_ADMISSION` (Mail), tablo
boyut sınırını örnekler arasında korumak için **global** kilittir: farklı kullanıcıların
istekleri bu kısa kilitte sıraya girer. Mevcut trafikte etkisi ihmal edilebilir; yük artarsa
kullanıcı başına kilit (`pg_advisory_xact_lock(hashtext(...))`) ya da kovaları sayaçla sınırlama
düşünülebilir. Kilit numaraları her serviste tek sınıfta (`AdvisoryLocks`) toplanmıştır;
numaralar yalnız kendi veritabanı içinde benzersiz olmak zorundadır.
