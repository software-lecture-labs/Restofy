# PostgreSQL ilk veritabanı kurulumu

Bu klasör, Docker ortamındaki PostgreSQL sunucusunun ilk kurulum betiğini içerir. Dosyanın adı `init-databases.sh`; `init.db` olarak bahsedilen işlem bu betiktir.

## Betik ne yapıyor?

`init-databases.sh`, 12 backend servisinin kullanacağı ayrı veritabanlarını oluşturur. İsimleri kod içine sabitlemek yerine PostgreSQL container'ına verilen ENV değişkenlerinden okur:

| Servis | Veritabanı adı değişkeni |
|---|---|
| admin-service | `DB_ADMIN_NAME` |
| auth-service | `DB_AUTH_NAME` |
| kitchen-service | `DB_KITCHEN_NAME` |
| mail-service | `DB_MAIL_NAME` |
| menu-service | `DB_MENU_NAME` |
| notification-service | `DB_NOTIFICATION_NAME` |
| order-service | `DB_ORDER_NAME` |
| payment-service | `DB_PAYMENT_NAME` |
| qr-service | `DB_QR_NAME` |
| rating-service | `DB_RATING_NAME` |
| timer-service | `DB_TIMER_NAME` |
| waiter-service | `DB_WAITER_NAME` |

API Gateway için ayrı veritabanı oluşturulmaz.

Betik, `POSTGRES_USER` kullanıcısıyla `POSTGRES_DB` veritabanına bağlanır. Her servis veritabanının mevcut olup olmadığını kontrol eder; eksikse `CREATE DATABASE` çalıştırır. Mevcut veritabanlarını silmez veya yeniden oluşturmaz. Boş bir veritabanı adı ya da SQL hatası olduğunda işlemi durdurur ve hata koduyla çıkar.

Bu işlem boş veritabanlarını hazırlar. Uygulama tabloları, indeksler ve şema değişiklikleri servislerin Flyway migration dosyalarıyla yönetilir. Betik ayrıca servis kullanıcıları oluşturmaz veya şifre değiştirmez. PostgreSQL'in ilk kullanıcı ve varsayılan veritabanı kurulumu resmi Docker imajının kendi başlangıç sürecine aittir.

## Ne zaman çalışır?

Ana `docker-compose.yml`, betiği PostgreSQL container'ının başlangıç dizinine salt okunur bağlar:

```yaml
volumes:
  - postgres_data:/var/lib/postgresql/data
  - ./postgres/init-databases.sh:/docker-entrypoint-initdb.d/01-init-databases.sh:ro
```

Resmi PostgreSQL imajı, bu dizindeki betikleri yalnızca veri dizini ilk kez hazırlanırken çalıştırır. Mevcut veri volume'üyle yeniden başlatma veya imajı yeniden build etme, betiği tekrar çalıştırmaz. Bu davranış [resmi imajın başlangıç kodunda](https://github.com/docker-library/postgres/blob/master/docker-entrypoint.sh) tanımlıdır.

```sh
docker compose up --build -d
```

Bu komut, yeni ve boş bir PostgreSQL volume'ü varsa ilk kurulumu da yapar. Normal `docker compose down`, verileri korur.

`docker compose down -v`, aktif projenin veritabanı volume'ünü ve içindeki verileri siler. Ardından yapılan `up`, yeni volume üzerinde betiği tekrar çalıştırır. Mevcut veritabanlarını korumak istediğinizde bu sıfırlama komutunu kullanmayın.

Mevcut volume üzerinde yeni bir servis veritabanı eklemek, otomatik ilk kurulumdan ayrı bir işlemdir. Sadece ENV'e yeni isim yazmak veya bu betiği değiştirmek mevcut volume üzerinde kendiliğinden yeni veritabanı oluşturmaz.

## Konum değiştiğinde nereler güncellenir?

Host tarafındaki güncel dosya yolu `postgres/init-databases.sh` şeklindedir. Çalışması için ana `docker-compose.yml` içindeki bind mount kaynağının bu yolu göstermesi gerekir. Container içindeki `/docker-entrypoint-initdb.d/01-init-databases.sh` yolu aynı kalır.

Bu konum, [OPS rehberinden](../ops/ops.md) de bağlantıyla gösterilir. ENV dosyaları, Java servisleri, Flyway, pre-push ve SonarQube ayarları host tarafındaki betik yoluna bağlı değildir; bu taşıma için onların değiştirilmesi gerekmez.

Shell dosyalarının Linux container'ında sorunsuz okunabilmesi için `.gitattributes` içinde `postgres/*.sh text eol=lf` kuralı bulunur.
