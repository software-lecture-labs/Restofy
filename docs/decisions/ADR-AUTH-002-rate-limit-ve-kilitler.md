# ADR-AUTH-002 — Ortak limiter, güvenilir proxy ve okuma kilitleri

- Durum: 29 Ekim 2026 açık kullanıcı onayıyla yerel uygulama; canlı geçiş yapılmadı.
- Sahip: Auth/Mail, Kerem Kalyoncu.

## Depolama ve bütçeler

Login, MFA peer ve reset bütçeleri SharedRateLimitStore üzerinden aynı Auth PostgreSQL'inde
atomiktir. Yeni altyapı yok. V5 yalnız login/MFA peer tablolarını ekler; eski reset kovaları,
HMAC anahtarları, sayaçları ve advisory lock numarası korunur. Ham email/IP DB'ye yazılmaz.

Login hesap+peer 10/15dk, hesap toplam50/15dk; MFA peer30/dk. Reset'in önceki sıralı bütçeleri
ve 429/retry-after sözleşmesi korunur. Login/MFA peer tablo kapasitesi10000 dolunca en eski
expiry silinir; reset kapasitede fail-closed kalır. Çok adresli 50 deneme ile hesap DoS riski
ve sel altında eski login/MFA sayacının unutulması kabul edilen mevcut ödünleşimlerdir.

Her ailede ayrı, kısa transaction advisory lock vardır. Bu sayede birden çok Auth örneği aynı
bütçeyi tüketir; sayaç ilerlemesi 429'da korunur. Önceki bütçe dolarsa sonraki bütçe tüketilmez.
Bu kilitler saldırı altında kuyruklanabilir; yük testi/ölçüm ve ileride sharding ayrı işletim işidir.
DB kesintisi limitleri devre dışı bırakmaz; kimlik işlemi başarısız olur.
Yeni login/MFA tabloları ilk geçişte boştur; RAM sayaçları taşınamaz ve mevcut pencere bir kez sıfırlanır.
Bütün örnekler yenilenmeden ortak sınır garantisi verilmez; eski RAM sürümüyle karışık filo desteklenmez.

## Tek Gateway hop güveni

Auth ve Gateway server.forward-headers-strategy=NONE kullanır; Tomcat remote-ip/protocol
header override'ları başlangıçta reddedilir. Gerçek socket peer'in önceden dönüştürülmemesi gerekir.
Gateway dışarıdan gelen Forwarded ve tüm X-Forwarded-* alanlarını temizleyip yalnız kendi socket
peer'ini tek X-Forwarded-For olarak aktarır. Auth bu alanı yalnız AUTH_TRUSTED_PROXY_CIDRS
listesindeki doğrudan socket peer'den kabul eder. Boş liste hiçbir proxy'ye güvenmez; /0 yasaktır.
Hostname/DNS, port, çok hop zinciri, birden fazla XFF ve Forwarded ile karma başlık kabul edilmez;
Auth güvenli biçimde socket adresine döner. IPv4/IPv6 literal CIDR desteklenir.

Operatör yalnız doğrudan Gateway çıkış adresini/ağını dar tanımlar; uygulama gerçek ağ/CIDR seçmez.
Gateway önüne ikinci proxy konacaksa bu protokol yeniden tasarlanır. Güvenilir subnet'teki
başka bir istemci XFF taklit edebileceğinden ağ izolasyonu ve dar allowlist zorunlu varsayımdır.
Header temizliği Origin/CSRF kontrolünün yerine geçmez. Gerçek dağıtık ağ testi yapılmadı.

## Okuma ve yazma ayrımı

Session GET ve personel liste/tekli okuma aynı JPQL snapshot'ında aktif kullanıcı, canlı oturum,
rol/restoran durumunu doğrular; FOR UPDATE ve revoke yazması yoktur. Yazma operasyonları hâlâ
kullanıcı kilidi altında tekrar doğrular. Pasifleştirme/rol değişikliği bir sonraki okumada görülür;
başlamış snapshot için anlık global iptal garantisi verilmez. Bağımsız servislerde JWT ömrü
kadar logout gecikmesi mevcut sözleşmedir.
