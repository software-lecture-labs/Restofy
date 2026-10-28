# ADR-AUTH-001 — Parola sıfırlama e-postasının Auth'tan Mail'e iletilmesi

- **Durum:** Kabul edildi (geliştirme aşaması), gözden geçirilecek
- **Tarih:** 2026-10-20
- **Sahip:** Auth / Mail (Kerem Kalyoncu)
- **İlgili kod:** `MailClient` (Auth), `MailDeliveryWorker` ve `MailJobRepository` (Mail)

## Bağlam

Admin parola sıfırlamasında 6 haneli kod e-postayla gider. Auth, kodu veritabanına yazar ve
**transaction commit olduktan sonra** Mail servisine iç bir HTTP çağrısı yapar; Mail iş kaydını
kendi veritabanına yazar ve SMTP'ye teslim eder. Bu çağrı iki servis ve bir SMTP sağlayıcısı arasında
atomik yapılamaz.

## Mevcut davranış

| Aşama | Davranış |
|---|---|
| Auth → Mail | `MailClient`: 2 iş parçacıklı, 100 kapasiteli **bellek içi** kuyruk, commit sonrası çalışır. **Yeniden deneme yok.** Kuyruk dolarsa ya da çağrı başarısızsa yalnız `DISPATCH_FAILED` loglanır |
| Mail kabulü | Mail iş kaydını yazar (en çok 100 açık iş), tekrarı `messageId` ile ayırır, son teslim süresi 2 dk'dır |
| Mail → SMTP | En çok 3 deneme (geçici hatada 5 sn arayla), süre dolarsa iş `UNKNOWN`/`PAYLOAD_LOST` olur |

## Sorulan senaryolar

- **Auth, commit ile gönderim arasında yeniden başlarsa?** Kuyruktaki komut kaybolur. Kod veritabanında
  vardır ama kullanıcıya hiç ulaşmaz. Kullanıcı yeni kod ister (180 sn yeniden gönderim bekleme süresi
  sonrası); eski kod 15 dk sonra zaten geçersizdir.
- **Mail kısa süre kesilirse?** Auth çağrısı başarısız olur ve tekrarlanmaz; aynı sonuç.
- **Payload diske yazılmıyor mu?** Evet. Kod, Mail'in veritabanında **tutulmaz**; yalnız teslim üst
  verisi tutulur. Bu bilinçli bir güvenlik tercihidir: yeniden deneme için kodu saklamak, kodun
  ikinci bir yerde bulunması demektir.

## Karar

Geliştirme aşamasında **en iyi çaba (best effort), yeniden denemesiz** teslimi kabul ediyoruz.
Gerekçe: parola sıfırlama yalnız ADMIN içindir (az sayıda kullanıcı, düşük hacim), kullanıcı kodu
yeniden isteyebilir ve kodun ek bir yerde saklanmaması güvenlik açısından değerlidir.

## Seçenekler ve ödünleşimler

| Seçenek | Artı | Eksi |
|---|---|---|
| **Bugünkü hal** (bellek içi, denemesiz) | Basit, kod başka yerde saklanmaz | Restart/kesintide mesaj kaybı |
| Auth'ta kısa yeniden deneme | Geçici kesintiyi atlatır | Hâlâ restart'ta kaybolur |
| **Transactional outbox** (Auth veritabanında şifreli kod + gönderim kaydı) | Restart ve kesintiye dayanıklı, tam-bir-kez benzeri teslim | Kodun şifreli de olsa kalıcı saklanması, ek tablo, temizleme ve anahtar yönetimi |

## Sonuç ve izleme

- Gerçek SMTP sağlayıcısı bağlandığında ve kullanıcı sayısı arttığında bu ADR yeniden değerlendirilir.
- Outbox'a geçilirse kod kalıcı saklanacağı için `docs/SECURITY.md` açısından ayrıca onay gerekir.
- Gözlem: `mail_event=DISPATCH_FAILED` ve `RESULT_UNKNOWN` loglarının sıklığı bu kararın geçerliliğini gösterir.
