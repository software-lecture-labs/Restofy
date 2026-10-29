# Auth teslim durumu — 29 Ekim 2026

**UYGULANDI:** Auth PMD/Checkstyle + GitHub CI dosyası, bağımsız JWT tüketici testleri,
14 dalın SHA kaydı, 12 servis entegrasyon sayfası, frontend/renk paleti ve hoca yorumları takibi.
Yeni business endpoint, migration, ENV veya ortak altyapı değişikliği yok.

## Doğrulama

| Kontrol | Sonuç |
|---|---|
| Başlangıç Auth verify | 277 test başarılı |
| Son Auth verify | **284 test; 0 başarısız, 0 hata, 0 atlama; BUILD SUCCESS** |
| Yeni bağımsız tüketici testi | 7 başarılı; JCA RSA doğrulaması, MFA ayrımı, imza, boyut, expiry ve revoke gecikmesi |
| ArchUnit | 1 test gerçekten çalıştı; başarılı |
| Checkstyle / PMD | **0 / 0 ihlal**; üretim ve test kaynakları dahil |
| JaCoCo line coverage | **%97,02**; 79 sınıf, sınıf eşleşme uyarısı yok; aşağıdaki Windows notuna bak |
| Kalite kapısı negatif/pozitif kanıtı | İhlalli main/test örneğinde her araç exit 1 ve 2 bulgu; temiz örnekte exit 0 ve 0 bulgu |
| Workflow YAML | Ayrıştırıldı; tetikler, read izni, SHA sabitleme ve always-artifact kontrol edildi |
| GitHub CI çalışması | **DOĞRULANMADI**; push/workflow dispatch yapılmadı |
| Gerçek servisler arası E2E / frontend Auth | **DOĞRULANMADI**; diğer servis adapterları bu teslimde uygulanmadı |

**Windows coverage notu:** Bu ortamda native Cp1254/Türkçe klasör adı nedeniyle JaCoCo agent verisi
`JAVA PROJESÝ` adlı yanlış karakterli kardeş yola yazıldı. Verify test/analizleri geçti ancak rapor
adımı veri dosyasını bulamayarak atlandı. Aynı son test koşusunun 02:59:47 tarihli gerçek exec verisi
beklenen Auth target yoluna kopyalanıp `jacoco:report` ayrıca çalıştırıldı; rapor %97,02 verdi.
Bu, normal Windows verify'ın coverage üretim sorununun kalıcı düzeltildiği iddiası değildir.
Yanlış karakterli klasördeki üretilmiş test kalıntıları otomatik silinmedi. Ana repo veya migration değişmedi.

Yerel kanıtlar (Git dışı): `tmp/auth-quality/direct-auth-verify.log`, `verified-summary.json`,
`coverage-path-recovery.json`, `coverage-report.log`, `gate-proof.json`, `probe/*`.

## Tam pre-push: NOT SAFE TO COMMIT

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
| Auth/Mail / Kerem | Mevcut sözleşme/test hazır; gerçek SMTP ve ayrı servis anahtarı sonraki iş |
| Ortak ekip | #10 proxy peer adresi, #9 limiter stratejisi; staff CRUD/alıcı dizini ve guest politikası için ayrıca kapsam |

Diğer serviste gereken adapter için Auth'a varsayımsal endpoint eklenmedi. Ayrıntılar [ana indekste](README.md).
Hocanın ayrı işleri ve güncel issue durumları [inceleme takibinde](review-follow-up.md).

## Yerel commitler ve sınır

- `4cf9a1c` — kalite/stil kuralları ve Auth CI.
- `d54ec55` — bağımsız JWT tüketici testleri.
- Bu raporu içeren doküman commit'i: `docs(auth): servis entegrasyon planlarını ve doğrulama raporunu ekle`.

**Push, PR, issue, yorum, merge, deploy veya uzak ayar değişikliği yapılmadı.**
