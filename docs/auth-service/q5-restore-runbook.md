# Q5 — Desteklenen QR restore prosedürü

Bu prosedür QR veritabanının mantıksal veya fiziksel geri yüklenmesi içindir. Çalışan PostgreSQL sunucusunda `pg_restore` da desteklenir; restart tek başına restore kanıtı sayılmaz. Bu belge deployment yapmaz, aşağıdaki komutlar operatör tarafından hedef doğrulandıktan sonra uygulanır.

## Güven sınırı

Auth veritabanı güncel ve güvenilir oturum/kimlik otoritesi olarak korunmalıdır. QR yedeğindeki `ready`, nonce ve receipt kayıtları restore sonrası güven kaynağı değildir. Yeni nonce restore tamamlandıktan sonra işletim sisteminin rastgelelik kaynağıyla üretilir; Auth bu nonce için güncel bütün SID'leri yeni, kalıcı bir baseline'a alır.

Habersiz SQL rollback'i, partial restore, canlı VM/RAM snapshot geri sarılması veya operatörün aşağıdaki adımları atlaması otomatik tespit garantisi altında değildir. Böyle bir garanti rollback'ten etkilenmeyen bağımsız güvenilir durum gerektirir. PostgreSQL başlangıç zamanı bunun yerine geçmez.

## Zorunlu sıra

1. Personel ingress'ini bakım modunda kapatın. Yalnız readiness sonucuna güvenmeyin: restore edilmiş `ready=true` yanlış olabilir. Bütün Auth worker ve QR süreçlerini, tüm replica/eski sürümler dahil durdurun; otomatik restart/scale mekanizmalarını bakım süresince durdurun. QR bağlantılarının kapanmasını doğrulayın. Compose için `docker compose stop auth-service qr-service` yalnız o Compose projesini kapsar; başka replica/process kalmadığını ayrıca kontrol edin.
2. Hedef primary host/port/database ve onaylı yedeği doğrulayın. Auth DB'nin geri sarılmadığını doğrulayın. Restore işlemini bütün uygulama süreçleri ve ingress kapalıyken yapın. Restore başarısızsa sonraki adıma veya trafiği açmaya geçmeyin.
3. **Restore'dan sonra, herhangi bir Auth/QR süreci başlamadan**, QR DB'de `ops/qr/q5-restore-quarantine.sql` çalıştırın. Script durable primary ve tek guard satırı ister; `ready=false` ve yeni rastgele nonce'u aynı transaction'da commit eder. Mevcut kayıtlı consumer generation değiştirilmez. Sadece `ready=false` yazmak yeterli değildir.
4. SQL'in exit code 0 olduğunu, guard'ın kapalı ve nonce'un restore edilmiş değerden farklı olduğunu kontrol edin. Bakım SQL'i tamamlandıktan sonra tekrar restore yapılırsa bu adımı **yeniden** uygulayın. SQL veya kontrol başarısızsa ingress ve uygulamalar kapalı kalır; `ready=true` elle yazılmaz.
5. QR süreçlerini yalnız private bootstrap erişimiyle başlatın. Personel ingress'i kapalı kalır. Bütün QR instance'larında readiness 503 ve personel istekleri 503 olmalıdır. Şimdi Auth süreçlerini başlatın. Worker yeni nonce'u alır, kaynak bariyerini PREPARING yapar, güncel tam baseline'ı QR'a kalıcılaştırır ve doğrulanmış ACK sonrası baseline iptalini tamamlar. Auth HTTP çağrısı DB transaction'ı dışında kalır.
6. İki tarafta nonce eşleşmesi, yeni baseline kimliği ve readiness UP doğrulansın. QR `ready=true` tek başına yeterli değildir: Auth FINALIZE/source ACTIVE de tamamlanmalıdır. Restore öncesi iptal edilmiş bir test SID'sinin bütün QR instance'larında 401, yeni login'in başarılı olduğu kontrollü smoke testle doğrulansın. Sonra personel ingress'i açın. Mevcut personelin tekrar giriş yapması beklenir.

Örnek bakım komutu (bağlantı değişkenlerinin doğru hedefi gösterdiği önceden doğrulanmalıdır; password komut satırına yazılmaz, onaylı psql credential mekanizması kullanılır):

```powershell
psql -X --host="$env:POSTGRES_HOST" --port="$env:POSTGRES_PORT" `
  --username="$env:POSTGRES_USER" --dbname="$env:DB_QR_NAME" `
  -v ON_ERROR_STOP=1 -f ops/qr/q5-restore-quarantine.sql
```

SQL yalnız QR'ın `public.staff_revocation_guard_state` tablosunu değiştirir; eski engelleri/receipt'leri silmez. Yanlış DB'de tablo yoksa hata verir. Bağlantı/kimlik kontrolü operatör sorumluluğudur; script bir deployment otomasyonu değildir.

## Crash ve tekrar

- Restore veya karantina sırasında crash: uygulamalar/ingress kapalı kalır; restore durumu doğrulanır ve karantina SQL'i tekrar çalıştırılır.
- Karantina commit'inden sonra crash: QR kapalı kalır. Private Auth/QR dönüşünde kalıcı bootstrap devam eder.
- Bootstrap sırasında crash/kayıp ACK: aynı yeni nonce ve kalıcı manifest güvenli yeniden denemeyle tamamlanır.
- Karantina SQL'ini yeniden çalıştırmak yeni nonce üretir; eski baseline'ı geçersiz kılar ve yeniden giriş gerektirir. Bunu çalışan uygulamalar altında rutin bir komut olarak kullanmayın.

## Auth veya iki veritabanının birlikte restore edilmesi

Bu QR-only prosedür kaybolmuş hesap kapatma, rol kaldırma veya parola değişikliği kararlarını yeniden oluşturamaz. Auth restore'u personel ingress'i kapalıyken kimlik/yetki kararlarının güvenilir kayıtlarla uzlaştırılmasını gerektirir. Kararlar doğrulanmadan yalnız yeni nonce/baseline ile trafik açmak desteklenmez. Uzlaştırma sonrasında QR karantina ve yeni tam baseline sırası uygulanır. Bu PR otomatik Auth yetki geçmişi kurtarma sistemi eklemez.

## Otomatik kanıt

`StaffRevocationStoreTests.sameProcessRestoreQuarantineMustInvalidateTheOldNonce`, `ready=false` negatif kontrolünü ve bakım SQL'inin readiness/nonce davranışını gerçek PostgreSQL'de doğrular. `ops/tests/q5-network.mjs`, aynı postmaster altında gerçek `pg_dump`/`pg_restore`, izole güvensiz negatif kontrol, bakım SQL'i, iki QR instance'ı ve Auth'un yeni baseline'ını doğrular. Negatif kontrol yalnız geçici loopback test ortamındadır; production prosedüründe restore edilmiş QR karantinadan önce başlatılmaz.
