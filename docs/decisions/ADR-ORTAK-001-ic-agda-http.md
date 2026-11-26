# ADR-ORTAK-001 — Servisler arası çağrılarda iç ağda HTTP istisnası

- **Durum:** Önerildi (#63'te hocanın B kararı üzerine; ayrıntılar onay bekliyor)
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
| Kitchen → Auth `/session` | `AUTH_SERVICE_URL` | Yok (#61, `KitchenAuthProperties`) |
| Auth → Mail | `auth.reset.mail-url` | Geliştirme profilinde her adrese (`MailClient`) |
| Waiter → QR / Order (imzalı) | `QR_SERVICE_URL`, `ORDER_SERVICE_URL` | Her ortamda (`ServiceUrlProperties` yalnız `http(s)` biçimine bakıyor) |

## Karar

### Ayar

`INTERNAL_HTTP_ALLOWED`, değeri `true` ya da `false`, varsayılanı `false`.

- `docker-compose.yml`'deki ortak ortam bloğuna `INTERNAL_HTTP_ALLOWED: ${INTERNAL_HTTP_ALLOWED:-false}` eklenir.
- `.env.example` (dev şablonu) `INTERNAL_HTTP_ALLOWED=true` taşır. Böylece yerel Compose çalışır; ayar yazılmamış
  bir ortamda HTTPS şartı aynen kalır.
- `true` ve `false` dışındaki bir değer servisin başlamasını durdurur.

### Bir servis-servis adresi ne zaman HTTP olabilir

Aşağıdakilerin **hepsi** sağlanırsa:

1. `INTERNAL_HTTP_ALLOWED=true`.
2. Etkin Spring profilleri `(dev | test) & !prod` ifadesine uyar. `order-auth`, `order-qr` gibi özellik profilleri
   yanında olabilir; `prod` hiçbir zaman olmaz.
3. Adresin host'u ya bir **Compose servis adıdır** (noktasız tek etiket, yalnız `a-z`, `0-9`, `-`, örneğin
   `auth-service`), ya da loopback (`localhost`, `127.0.0.1`, `[::1]`). IP adresi (loopback hariç) ve noktalı alan
   adı HTTP ile kabul edilmez.
4. Adres yalnız origin'dir: kullanıcı bilgisi, sorgu ya da parça yoktur (bugünkü doğrulamalar aynen kalır).

Biri sağlanmazsa bugünkü davranış geçerlidir: HTTP adresle servis başlamaz.

### Yanlış kullanımı engelleme

- `INTERNAL_HTTP_ALLOWED=true` iken profil kuralı (2) sağlanmıyorsa servis **başlamaz**; ayar sessizce yok sayılmaz.
  Prod'a yanlışlıkla taşınan bir `.env` böylece fark edilir.
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
2. Ayar açık ve profil `dev` iken `http://auth-service:8080` kabul edilir.
3. Ayar açık ve profil `prod` (ya da `dev,prod`) iken servis başlamaz.
4. Ayar açıkken `http://10.0.0.5:8080` ve `http://auth.example.com` reddedilir.
5. Ayar `yes` gibi geçersiz bir değerse servis başlamaz.

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
