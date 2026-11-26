# ADR-ORTAK-001 — Servisler arası çağrılarda iç ağda HTTP istisnası

- **Durum:** Önerildi (#63'te hocanın B kararı üzerine; #69 incelemesiyle ortam koşulu profilden `APP_ENV`'e
  çekildi, onay bekliyor)
- **Tarih:** 2026-11-26
- **Öneren:** Kerem Kalyoncu (Auth)
- **İlgili:** #63, #61 (Kitchen), `OrderLifecycleClient` (QR → Order mevcut istisnası)

## Bağlam

Personel çerezini ya da imzalı servis isteğini başka bir servise ileten istemciler hedef adresin HTTPS olmasını şart
koşuyor; HTTP adresle servis başlamıyor. Compose'da iç ağ için TLS yok ve `.env.example`'daki bütün iç adresler
`http://<servis>:8080`. Bu yüzden bu entegrasyonlar Compose'da uçtan uca denenemiyor.

#63'te üç seçenek konuşuldu. Hocanın kararı: **B'yi sınırlı biçimde şimdi açmak, iç ağda TLS'i (A) sonraya
bırakmak.** QR → Order'daki dev/test istisnası tek bir ortak kurala çevrilecek; her servis sahibi kendi istemcisini
küçük bir PR ile uyarlayacak.

Bugünkü durum (`dev`):

| Çağıran → hedef | Ayar | Bugün HTTP'ye izin |
|---|---|---|
| Order → Auth `/session` | `AUTH_SERVICE_URL` | Yok (`StaffSessionHttpAdapter`) |
| Order → QR | `QR_SERVICE_URL` | Yok (`QrSessionHttpAdapter`) |
| Waiter → Auth `/session` | `AUTH_SERVICE_URL` | Yok (`AuthProperties`) |
| QR → Order | `ORDER_SERVICE_URL` | Yalnız `(dev \| test) & !prod` profilinde ve yalnız loopback (`OrderLifecycleClient`) |
| Kitchen → Auth `/session` | `AUTH_SERVICE_URL` | Yok (#61'de `KitchenAuthProperties`; henüz `dev`'de değil) |
| Auth → Mail | `auth.reset.mail-url` | Geliştirme profilinde her adrese (`MailClient`) |
| Waiter → QR / Order (imzalı) | `QR_SERVICE_URL`, `ORDER_SERVICE_URL` | Her ortamda (`ServiceUrlProperties` yalnız `http(s)` biçimine bakıyor) |

## Karar

### Ayar

`INTERNAL_HTTP_ALLOWED`, değeri `true` ya da `false`, varsayılanı `false`.

- `docker-compose.yml`'deki ortak ortam bloğuna `INTERNAL_HTTP_ALLOWED: ${INTERNAL_HTTP_ALLOWED:-false}` eklenir.
- `APP_ENV` ortak bloğa **eklenmez**. Bugün yalnız Auth ve Order'a geçiliyor; QR'ın Q5 ayarları da ona baktığı için
  ortak blokta başka servislerin davranışını değiştirirdi. Kuralı uygulayan servis sahibi kendi PR'ında kendi
  servisine `APP_ENV: ${APP_ENV:-dev}` ekler. Eklemezse koşul (2) sağlanmaz ve ayar açıkken servis başlamaz;
  yani eksik bir ayar HTTP'ye izin vermez, servisi durdurur.
- `.env.example` (dev şablonu) `INTERNAL_HTTP_ALLOWED=true` taşır. Böylece yerel Compose çalışır; ayar yazılmamış
  bir ortamda HTTPS şartı aynen kalır.
- `true` ve `false` dışındaki bir değer servisin başlamasını durdurur.

### Bir servis-servis adresi ne zaman HTTP olabilir

Aşağıdakilerin **hepsi** sağlanırsa:

1. `INTERNAL_HTTP_ALLOWED=true`.
2. `APP_ENV` tam olarak `dev` ya da `test` (küçük harf; boş, eksik ya da başka bir değer koşulu sağlamaz) **ve**
   etkin Spring profilleri arasında `prod` ya da `production` yoktur. Diğer profillere bakılmaz.

   Profil yerine `APP_ENV`'e bakılmasının nedeni: Order'ın `order-auth` ve QR'ın `qr-order` entegrasyon profilleri
   `dev`/`test` profili olmadan açılır (`docs/order-service/INTEGRATION_CONTRACTS.md`, `docs/qr/qr-key-uretimi.md`).
   Profil şartı tam bu entegrasyonlarda servisi durdururdu. Order (`OrderRuntimeProfiles`) ve QR
   (`Q5SecuritySettings`) ortam ayrımını zaten `APP_ENV` ile yapıyor.
3. Adresin host'u ya bir **Compose servis adıdır** (noktasız tek etiket, yalnız `a-z`, `0-9`, `-`, örneğin
   `auth-service`), ya da loopback (`localhost`, `127.0.0.1`, `[::1]`). IP adresi (loopback hariç) ve noktalı alan
   adı HTTP ile kabul edilmez.
4. Adres yalnız origin'dir: kullanıcı bilgisi, sorgu ya da parça yoktur (bugünkü doğrulamalar aynen kalır).

Biri sağlanmazsa bugünkü davranış geçerlidir: HTTP adresle servis başlamaz.

### Yanlış kullanımı engelleme

- `INTERNAL_HTTP_ALLOWED=true` iken ortam kuralı (2) sağlanmıyorsa servis **başlamaz**; ayar sessizce yok sayılmaz.
  Prod'a yanlışlıkla taşınan bir `.env` böylece fark edilir.
- **Prod ortamında** `APP_ENV` açıkça `prod` yazılır, `INTERNAL_HTTP_ALLOWED` hiç yazılmaz. Compose'da `APP_ENV`
  yazılmazsa `dev` sayılır; `.env.example` olduğu gibi kopyalanırsa istisna da açılır. Bu, MFA kapatma ve geliştirme
  kullanıcıları gibi `APP_ENV`'e bağlı diğer geliştirme özellikleriyle aynı risktir; prod kurulumunda bu iki değer
  kontrol edilir.
- İstisna yalnız şemayı gevşetir. HTTPS adreslerde sertifika ve host doğrulaması kapatılmaz.
- Bu istisnayla HTTP kullanan istemci yönlendirme izlemez. İzleseydi `http://auth-service` bir 3xx ile isteği
  dışarıdaki bir adrese çevirebilir, çerez ya da imzalı istek açık metin oraya giderdi; (3) boşa çıkardı.
- Bir adres bu istisnayla HTTP kabul edildiğinde servis başlarken bir kez `WARN` yazar: ayarın adını ve "iç ağda
  açık metin" bilgisini. Adresin kendisi dışında hiçbir değer (anahtar, çerez) loglanmaz.

### Kapsam

- **Kapsar:** yalnız ortamdan okunan servis-servis taban adresleri (`AUTH_SERVICE_URL`, `QR_SERVICE_URL`,
  `ORDER_SERVICE_URL`, Mail adresi ve benzerleri).
- **Kapsamaz:** tarayıcıya dönük adresler (`CORS_ALLOWED_ORIGINS`, QR giriş adresi, Menu ürün görseli adresleri);
  Gateway yönlendirmeleri (Gateway yalnız iletir, bugün de doğrulamıyor); Q5 istemcileri (Auth → QR iptal komutu,
  QR'ın Q5 ayarları), onların kendi kuralı ve sözleşmesi var.
- QR → Order'daki loopback istisnası bu kuralın alt kümesi; QR uyarlarken Compose adlarını ekler.

### Her servisin küçük PR'ında beklenen testler

1. Ayar kapalıyken `http://auth-service:8080` reddedilir (bugünkü davranış).
2. Ayar açık, `APP_ENV=dev` iken `http://auth-service:8080` kabul edilir; `order-auth` gibi bir özellik profili tek
   başına etkin olsa da.
3. Ayar açıkken servis şu durumlarda başlamaz: `APP_ENV=prod`, `APP_ENV` boş ya da eksik, `APP_ENV=dev` ama `prod`
   profili etkin.
4. Ayar açıkken `http://10.0.0.5:8080` ve `http://auth.example.com` reddedilir.
5. Ayar `yes` gibi geçersiz bir değerse servis başlamaz.
6. HTTP adres 3xx döndürürse istemci yönlendirmeyi izlemez.

## Sonuçlar

- Compose'da entegrasyonlar HTTP ile uçtan uca denenebilir.
- **Kabul edilen risk:** Ayar açıkken personel çerezi ve imzalı istekler iç Docker ağında açık metin gider; ağdaki
  bir konteyner okuyabilir. Bu yalnız geliştirme ve test ortamı için kabul edilir.
- İç ağda TLS (A) gelince bu ayar kaldırılır ve istemcilerdeki istisna kodu silinir.
- Waiter'ın imzalı QR/Order istemcileri bugün HTTP'yi her ortamda kabul ediyor. Bu kurala uyarlanması önerilir;
  karar Waiter sahibinindir.
- Auth → Mail istisnası da bu kurala çekilir (Auth'un kendi PR'ı).

## Uygulama sırası

1. Bu ADR, `docker-compose.yml` ve `.env.example` değişikliği (varsayılan `false`, servis davranışı değişmez).
2. Her servis sahibi kendi istemcisini küçük bir PR ile uyarlar: Order (Hamza), Waiter (Umut), QR (Alper),
   Kitchen (Nisa, #61), Auth → Mail (Kerem).
