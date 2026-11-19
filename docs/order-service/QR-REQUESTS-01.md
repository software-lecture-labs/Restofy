# QR-REQUESTS-01 — QR → Order lifecycle istemcisi

- **Muhatap:** Alper, QR Service
- **Kapsam:** QR-INT-01 için QR token üretimi, HTTP adaptörü ve ortak doğrulama. Order'ın yöntemleri, DTO'ları ve hata kodları [Order entegrasyon sözleşmesinde](INTEGRATION_CONTRACTS.md#51-qr-http-alanları-ve-sonuçları).

## QR'dan istenen değişiklik

`OrderSessionAccess` için doğrudan Order'a bağlanan bir HTTP adaptörü uygulayın. Yalnız QR'ın kendi doğruladığı restoran ve masa oturumu bağlamından üretilen çağrıları imzalayın. İşlem yolu, operationId, REST gövdesi ve varsa query gönderimden önce kesinleşmiş olmalı. Gateway rotası gerekmiyor. Ortak runtime'da bağlantı HTTPS olmalı.

QR kendi lifecycle imzalama anahtarından her HTTP çağrısı için yeni bir JWT üretir. Tokenı `Authorization: Service <JWT>` olarak yollar. Özel anahtar QR'ın güvenli çalışma ortamında tutulur. Auth Q5, QR payload şifreleme ve personel anahtarları ayrı kalır.

- JWT header `alg=RS256`, `kid` Order'a dağıtılan açık anahtarla aynı tanımlayıcı. RSA anahtar boyutu en az 2048 bit, credential en çok 4096 karakter.
- `iss=restofy-qr`, `sub=qr-service`, `aud=["restofy-order"]`, `token_type=service`, `scope=table-session:lifecycle`.
- `restaurant_id` QR'ın yetkili restoranının standart küçük harfli UUID'si. Order, bu restoranı kendi allowlist'iyle ve query/body'den çözümlenen restoranla eşler.
- `iat`, `nbf`, `exp` NumericDate olarak UTC epoch saniyesidir. `iat=nbf`, `0 < exp-iat ≤ 30` saniye. Order gelecekteki `iat` ve süresi dolmuş credential'ı reddeder. `jti` her token için yeni standart UUID.
- `method` büyük harfli `GET` veya `POST`. `target` sunucuya ulaşan ham path ve varsa `?` ile ham query. Şema, host ve fragment eklenmez. Query sırası ve yüzdelik kodlama imzalandığı gibi gönderilir.
- `body_sha256` gönderilen gövde baytlarının 64 küçük harfli hex SHA-256 özetidir. Boş GET gövdesi için değer `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`. POST JSON'u bir kez UTF-8 baytlarına çevirin, imzaladığınız baytları değiştirmeden gönderin. `body_sha256` ile QR komutunun `commandDigest` alanı ayrı amaçlar taşır.
- Tek Authorization başlığı gönderin. Cookie, Origin ve Content-Encoding göndermeyin. Order imzalı istekte en fazla 256 KiB gövde kabul eder.

Order tarafında `order-qr` profili, `dev` ve `test` olmadan çalışır. `ORDER_QR_SERVICE_PUBLIC_KEYS` her `kid` değerini Base64 kodlu X.509 SubjectPublicKeyInfo DER açık anahtarına eşleyen bir JSON nesnesidir. Örnek şekil `{"qr-2026-11":"<base64 DER>"}`. `ORDER_QR_SERVICE_RESTAURANTS` virgülle ayrılmış standart restoran UUID'leridir. Değerler Order çalışma ortamına aktarılır. Eksik veya geçersiz değerler Order başlangıcını durdurur. Değişkenler yalnız Order'ın bu profiline aittir. Aynı Order profilindeki her QR anahtarına listedeki restoranlar için erişim verilir.

Anahtar rotasyonunda yeni açık anahtarı önce eski anahtarla birlikte bütün Order örneklerine yükleyip yeniden başlatın. QR yeni `kid` ile imzalamaya geçince eski token ömrü dolduktan sonra eski anahtarı kaldırın. Acil iptal, anahtarın tüm Order örneklerinden kaldırılıp yapılandırmanın yeniden yüklenmesiyle tamamlanır. Order `jti` tekilliğini dağıtık olarak saklamaz. Token ömrü boyunca aynı imzalı istek tekrar ulaşabilir. TLS, aynı path/query/gövde bağı ve lifecycle tekilleştirme kuralları bu pencerenin sınırıdır. Tokenları ve özel anahtarları loglamayın.

## Yanıt eşleme ve kesinti

- Wire `tableSessionId` → QR `sessionId`. Her yanıtın restoran, main, operationId, purpose ve komut digest'ini ilgili isteğe göre doğrulayın. NOT_STARTED durumunda purpose null olabilir.
- IDLE komutunda oturumun tam görüntüsünü ve o değişmez komuttan üretilen digest'i saklayın. `requestedAt` anında süresi dolmuş en fazla 500 misafiri Order'a aday olarak gönderin. `mainIdleExpiresAt` veya `idleExpiresAt` null ise ilgili süre dolmamış sayılır. `requestedAt`, `guests` dizisi ve gönderilen her adayın `guestSessionId` değeri zorunlu. 500'ü aşan bir istek `422 VALIDATION_ERROR` alır.
- Daha fazla aday varsa önceki işlemi kesinleştirin. Ardından güncel tam oturum görüntüsünden kalan adayları yeni bir `operationId` ve yeni komutun digest'i ile değerlendirin. Bekleyen işlemi her tekrarda aynı aday listesi ve aynı digest ile gönderin.
- Aynı kalıcı `operationId` ve digest ile uzlaştırın. Kayıp yanıt, 503 veya süresi geçmiş sonuç kapanış ya da abort kanıtı değildir. Tekrar çağrısı için yeni token imzalayın ve QR komutunu koruyun.
- Hazırlık öncesinde ulaşan `NOT_CLOSED` için Order V131 kalıcı abort kaydı yazar ve digest'i geri yansıtan `200 NOT_STARTED` döner. Aynı komutun gecikmiş prepare'ı `409 INVALID_STATUS_TRANSITION` alır. Tek başına durum sorgusundan dönen `NOT_STARTED` abort kaydı oluşturmaz. QR, yerel abort kararı varsa aynı işlemle confirmation çağrısını tekrarlar ve yanıtı komutla eşleştirir.
- K13 kapsamında doğru CLOSED makbuzu zamanında ulaşmasa bile aynı komutla teyit edin. `scopeDrift` ve `driftBlockers` değişmez kaynak teyidine kaydedilir. QR yerel sonucu korur.
- Bu aşamada gerçek token ile siparişsiz oturum okuması ve idle protokolü çalıştırılabilir. Sipariş içeren oturumda Order yetkili Payment kanıtı bulunmadığında okuma ve yeni/yenilenen izin için 503 döner. Alper'in Payment kaynak sözleşmesi hazır olduğunda [K7 bağımlılığı](INTEGRATION_CONTRACTS.md#52-payment-kaynak-bağımlılığı) yeniden ele alınacak.

Ortak kabul testlerinde gerçek QR adaptörüyle imza ve kapsam, yanlış tenant ve istek bağı, 500'ü aşan adayların sıralı işlenmesi, ilk abort'tan sonra gecikmiş prepare ve timeout sonrası aynı işlemle uzlaştırma senaryolarını doğrulayın. Payment kaynağı yokken siparişli oturumun 503 davranışını da kontrol edin. Order'ın ayrı süreçlerle çalışan imzalı HTTP testinde kalıcı abort ve yeniden başlatma sonrası uzlaştırma doğrulandı. Ortak QR–Order testi QR adaptörü hazır olduğunda Alper'in sorumluluğunda.
