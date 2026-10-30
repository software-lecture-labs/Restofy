# Auth teslim durumu — 29 Ekim 2026

> Bu sayfanın test sayıları önceki personel/alıcı teslimine aittir. Sonraki onaylı Auth/Mail
> sağlamlaştırması, genişleyen dar kapsam ve güncel testler [hardening-status.md](hardening-status.md) dosyasındadır.

**UYGULANDI:** Auth PMD/Checkstyle + GitHub CI dosyası, bağımsız JWT tüketici testleri,
14 dalın SHA kaydı, 12 servis entegrasyon sayfası, frontend/renk paleti ve hoca yorumları takibi.
Ek teslim: **ADMIN personel yönetimi ve Notification aktif alıcı sorgusu**; [kesin API sözleşmesi](staff-directory-api.md).
Bu önceki fazda yeni uçlar yalnız Auth içindeydi; sonraki dar kapsam kullanıcı onayıyla genişletildi.
Notification için opsiyonel public-key ayarı eklendi; gerçek anahtar veya ortam ayarı yazılmadı.

## Doğrulama

| Kontrol | Sonuç |
|---|---|
| Başlangıç Auth verify | 277 test başarılı |
| Son Auth verify | **306 test; 0 başarısız, 0 hata, 0 atlama; BUILD SUCCESS** |
| Yeni bağımsız tüketici testi | 7 başarılı; JCA RSA doğrulaması, MFA ayrımı, imza, boyut, expiry ve revoke gecikmesi |
| Personel / alıcı / servis JWT / audit / reset | **22 yeni test başarılı**; HTTP, rol/MFA/CSRF, iptal, stale/duplicate/no-op, eşzamanlı ADMIN, ayrı anahtar, reset iptali/kimlik yarışı |
| ArchUnit | 1 test gerçekten çalıştı; başarılı |
| Checkstyle / PMD | **0 / 0 ihlal**; üretim ve test kaynakları dahil |
| JaCoCo line coverage | **%97,19**; 94 sınıf analiz edildi, sınıf eşleşme uyarısı yok; rapor aynı son verify koşusunda üretildi |
| Kalite kapısı negatif/pozitif kanıtı | İhlalli main/test örneğinde her araç exit 1 ve 2 bulgu; temiz örnekte exit 0 ve 0 bulgu |
| Workflow YAML | Ayrıştırıldı; tetikler, read izni, SHA sabitleme ve always-artifact kontrol edildi |
| GitHub CI çalışması | **DOĞRULANMADI**; push/workflow dispatch yapılmadı |
| Gerçek servisler arası E2E / frontend Auth | **DOĞRULANMADI**; diğer servis adapterları bu teslimde uygulanmadı |
| Bağımsız son inceleme | Yeni doğrulanmış bulgu yok; Admin/Notification güncel dallarında gerçek client yok; [inceleme kaydı](delivery-review.md) |

**Windows coverage notu:** Önceki koşularda native Cp1254/Türkçe klasör adı nedeniyle JaCoCo verisi
yanlış karakterli kardeş yola yazılıyordu. Son 306 testlik koşuda yalnız CLI'da `jacoco.destFile` ve
`jacoco.dataFile` aynı, tek koşuya özel ASCII geçici dosyaya yönlendirildi. Auth verify gerçek veriyi
kendisi okuyup raporu üretti; önceki exec dosyaları birleştirilmedi/kopyalanmadı. Bu bir yerel komut
workaround'ıdır; varsayılan Windows yol davranışının kalıcı düzeltildiği iddia edilmez.
Eski üretilmiş test kalıntıları otomatik silinmedi. Linux CI komutu değişmedi.

Yerel kanıtlar (Git dışı): `tmp/auth-quality/auth-delivery-release.log`, `auth-delivery-release-exit.txt`,
`delivery-verified-summary.json`, `delivery-coverage-path.txt`; önceki kalite kapısı kanıtları `gate-proof.json`, `probe/*`.
İlk ek HTTP koşusunda çift Origin'in ilk değerine güvenildiği görüldü; belirsiz Origin artık 403 ile reddedilir.
İlk geniş koşudaki iki import sırası bulgusu giderildi; yukarıdaki son koşu tamamıyla başarılıdır.
Son incelemede eski e-posta/reset yetkisi açığı üç testle düzeltmeden önce **3 FAIL / 0 ERROR** olarak
kanıtlandı, düzeltmeyle **3 PASS** oldu; son tam koşuda rollback kontrolüyle birlikte geçti.
Kod bekleyen/doğrulanmış reset yetkilerini hesap değişiminde iptal eder ve reset isteğinde kilit sonrası
güncel e-postayı tekrar denetler. Kanıt: `delivery-reset-red.log`, `delivery-reset-green.log`.

## Tam pre-push: NOT SAFE TO COMMIT

Bu bölüm önceki kalite turunun tam repo koşusudur; ek personel/alıcı tesliminde yalnız Auth kapısı yeniden çalıştırıldı.
İlk tam koşu **9/14 PASS**, exit 1. Auth'taki altı uzun test satırı backend kapısını durdurdu;
satırlar düzeltildi ve son Auth verify/analizleri ayrıca başarılı çalıştırıldı. Tam koşu yeniden
yeşil tamamlanmış gibi raporlanmaz; diğer backend modüllerinin o koşudaki devamı SKIPPED kaldı.

- **PASS:** Git/secret dosya kontrolü; frontend TypeScript, test, coverage, build, HTTP ve Playwright;
  production npm audit; Docker yapılandırması.
- **FAIL:** İlk backend test/build/coverage satırları; frontend lint; backend HTTP health.
- Frontend lint bağımlılık dosyalarına kadar tarama yapıp uzadı; yalnız bu görevin ESLint süreci
  331 saniyede durduruldu. Frontend ayarı değiştirilmedi.
- Beklenen backend servisleri seçilen Compose projesinde çalışmıyor. Bunları başlatma/rebuild veya
  veri migration uygulama işlemi yapılmadı.
- Tam npm audit geliştirme bağımlılıklarında 2 HIGH bildiriyor; production audit PASS. Bağımlılık değiştirilmedi.
- Tam koşu kanıtı: `tmp/auth-quality/full-pre-push.log`, `full-exit.txt`, `lint-interrupted.txt`.

## Kim kimi bekliyor?

| Taraf | Beklenen |
|---|---|
| Order / Hamza | Eski Staff header + 512 sınırını cookie/4096/JWT doğrulamasıyla değiştirmek; AuthContextPort consumer adapterı |
| Kitchen / Nisa | KitchenPrincipal ve ROLE eşlemesi; ADMIN yalnız okur; event teslim adapterları ayrı |
| Menu / Merve | CurrentActorProvider; personel/public/Kitchen servis kimliği ayrımı |
| QR / Alper | Yönetim/kapanış staff adapterı; Order/Payment kanıtları ve guest browser taşıması ayrı sözleşmeler |
| Waiter / Umut | AuthenticatedStaff üreticisi, cookie CSRF, Order/QR gerçek bağlantıları |
| Admin UI / Umut | Hazır Auth personel API'sine bağlanmak; version/409, MFA ve CSRF davranışını uygulamak |
| Notification / Hamza | Hazır Auth alıcı sorgusuna kendi RS256 signer/client'ıyla bağlanmak; yalnız public key'i Auth'a tanıtmak |
| Auth/Mail / Kerem | Bildirilen personel/kimlik/alıcı uçları hazır; gerçek SMTP işletim doğrulaması ayrı; anahtar ayrımı sonraki onaylı sağlamlaştırmada uygulandı |
| Ortak ekip | #10/#9 yerelde uygulandı, uzak issue durumu değiştirilmedi; çok restoranlı üyelik, key rotation ve guest politikası ayrı |

Diğer servislerin client/Saga işleri Auth içine taşınmadı. Auth'a somut personel yönetimi/alıcı ihtiyacı eklendi;
Order/Kitchen/Menu/QR/Waiter için hazır JWT sözleşmesi korunur. Ayrıntılar [ana indekste](README.md).
Hocanın ayrı işleri ve güncel issue durumları [inceleme takibinde](review-follow-up.md).

## Yerel commitler ve sınır

- `4cf9a1c` — kalite/stil kuralları ve Auth CI.
- `d54ec55` — bağımsız JWT tüketici testleri.
- `cd304d5` — ilk servis entegrasyon planları ve doğrulama raporu.
- `3f3a00b` — personel yönetimi, güvenli hesap/reset değişimi ve audit.
- `117e117` — Notification için ayrı servis kimliği ve alıcı sorgusu.
- Bu ek teslimin doküman commit'i: `docs(auth): hazır personel ve alıcı sözleşmelerini kaydet`.

**Push, PR, issue, yorum, merge, deploy veya uzak ayar değişikliği yapılmadı.**
