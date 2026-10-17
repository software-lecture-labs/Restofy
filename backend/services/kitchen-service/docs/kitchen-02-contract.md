# Kitchen Service — KITCHEN-02 Sipariş Hazırlama Süreci (Faz 2)

Durum: **Faz 2 çalışma notu.** Dış servislerle ilgili sözleşmeler *öneridir*; Order, Auth, Notification veya Gateway tarafında uygulanmış sayılmaz. KITCHEN-01 sözleşmesi: [kitchen-01-contract.md](kitchen-01-contract.md).

## 1. Başlangıç durumu — kodla doğrulanmış (2.1.1)

| Konu | Bulgu |
|---|---|
| Faz 1 | Commit `3ea09c4`; Kitchen testleri **67 test, 0 hata** (Java 25.0.4.1, `./mvnw clean test`) |
| Migration | `V81__create_kitchen_core.sql`: `kitchen_orders` zaten `started_at`, `ready_at`, `cancelled_at`, `version` ve `ck_kitchen_orders_timestamps` CHECK'ini (`ready_at >= started_at >= received_at`) içerir; `kitchen_status_history` `from_status`, `to_status`, `actor_user_id`, `source_event_id`, `occurred_at`, `reason` içerir. Durum ve zaman alanları için **yeni migration gerekmedi**. Komut kimliği için **`V82`** gerekti (bkz. §2.4) |
| Entity | `KitchenOrder` yalnız `receive(...)` fabrikasına sahipti; `@Version` var. `KitchenStatusHistory` yalnız `initial(...)` fabrikasına sahipti |
| Kalemler | `kitchen_order_items` üzerinde hazırlık durumu **yoktur** ve eklenmeyecek (V1 sipariş bazlı) |
| Gateway | `Path=/api/v1/kitchen,/api/v1/kitchen/**` — yöntem kısıtı ve prefix kırpma yok; PATCH yolları Gateway'de değişiklik gerektirmez |
| Güvenlik | Production'da kimlik üreten bileşen yoktur → tüm iş istekleri 401 (fail closed); yalnız `/actuator/health` anonim. CSRF Spring varsayılanında |

## 2. Tasarım kararları

1. **Durum makinesi:** yalnız `WAITING → PREPARING → READY`. `WAITING → READY`, `READY → *`, `PREPARING → WAITING`, `CANCELLED → *` yasak. `READY` Kitchen için terminaldir ve garson servisi sonrasında da değişmez. Kalem bazlı durum yoktur.
2. **Yazma yetkisi (onaylı karar):** hazırlık komutları yalnız **`KITCHEN`** rolüne açıktır. **`ADMIN` yalnız okur; her yazma isteği 403 alır.** Kimliksiz istek 401. Restoran ve kullanıcı **yalnız doğrulanmış principal'dan** alınır; istekteki `restaurantId`, `X-Role`, `X-Restaurant-Id`, `actorId`, `startedAt`, `status` değerleri etkisizdir (istek gövdesi okunmaz). Başka restoranın siparişi 404'tür (varlığı sızmaz).
3. **Eşzamanlılık:** komut siparişi `SELECT ... FOR UPDATE` (5 sn sınırlı) ile okur; durum + zaman damgası + `version` + history **tek yerel transaction**'dadır. `@Version` ikinci güvenlik ağıdır. Dış HTTP çağrısı kilit altında yapılmaz.
4. **Tekrar komut sözleşmesi (onaylı karar):** *sırf hedef duruma ulaşılmış olması 200 için yeterli değildir.* Her hazırlık komutu zorunlu bir **`Idempotency-Key`** (UUID) header'ı taşır; bu komut kimliği, komutun yaptığı geçişin history satırıyla birlikte saklanır (`kitchen_status_history.command_id`). Tekrar **ancak şu hepsi doğruysa** `200`'dür ve hiçbir şey değişmez (zaman, `version`, history, olay): aynı komut kimliği + aynı kullanıcı + aynı sipariş + aynı hedef geçiş ve sipariş hâlâ o hedef durumdadır. Aksi hâlde:
   - Sipariş hedef durumda ama komut farklı (başka kimlik veya başka operatör): **409** (`currentStatus` ile).
   - Aynı komut kimliği başka siparişte, başka kullanıcıyla veya başka geçişte kullanılmış: **409**.
   - Aynı komut, ama sipariş bu arada ilerlemiş (ör. `READY`): **409** ve gerçek durum bildirilir; bayat başarı dönülmez.
   - `READY`/`CANCELLED` siparişe başlatma: her zaman **409**.
   - Header yok veya UUID değil: **400**.
   Komut kimliği restoran içinde benzersizdir; farklı restoranlar aynı kimliği kullanabilir. **Migration `V82`:** `command_id` sütunu, `(restaurant_id, command_id)` kısmi benzersiz indeksi ve `(restaurant_id, kitchen_order_id, to_status)` benzersiz indeksi (her durum bir siparişe en fazla bir kez girilir — çift geçişe karşı veritabanı güvencesi). Mevcut satırlar için sütun boştur (sistem kayıtları komut kimliği taşımaz).
5. **Zaman damgaları:** sunucu saatinden (`Clock`) bir kez yazılır; istemci veremez. `received_at <= started_at <= ready_at` DB CHECK'i ile de korunur; saat kayması `startedAt`'ın `receivedAt`'ten önceye düşmesine izin vermez.
6. **API yolları (onaylı karar):** `PATCH /api/v1/kitchen/orders/{orderId}/start-preparation` ve `PATCH /api/v1/kitchen/orders/{orderId}/mark-ready`. Eski adlar (`…/preparation/start`, `…/preparation/ready`, `…/ready`) ikinci bir API olarak **oluşturulmadı**. Gateway'de değişiklik gerekmez.
7. **Yanıt:** başarıda mevcut sipariş detay yanıtı (`KitchenOrderDetailResponse`); `startedAt/readyAt` boşken açık `null` (Faz 1 kararı).
8. **Kapsam dışı (Faz 2'de yapılmadı):** iptal mekanizması (Faz 4), ürün açma/kapama ve fire (Faz 3), ürün bazlı hazırlık, D-02 kararı, canlı Order/Notification teslimi, broker.

## 3. Başlatma API'si — `PATCH /api/v1/kitchen/orders/{orderId}/start-preparation` (task 2.1)

- **Erişim:** korumalı; yalnız **`KITCHEN`**. `ADMIN` ve diğer roller 403, kimliksiz 401. Kitchen altındaki **her yazma yöntemi** (POST/PUT/PATCH/DELETE) yalnız KITCHEN'a açıktır; ADMIN yalnız GET/HEAD yapar.
- **İstek:** gövde yoktur (varsa okunmaz). Zorunlu header: `Idempotency-Key: <uuid>`.
- **CSRF:** Spring varsayılanı korunur; testleri geçirmek için kapatılmadı. Kimlik doğrulama yöntemi (D-09) kesinleşince istemci tarafı yeniden değerlendirilecektir.
- **Başarı (200):** güncel sipariş detayı (GET detay ile aynı gövde): `PREPARING`, `startedAt` dolu, `readyAt` açık `null`.

| Durum | Ne zaman |
|---|---|
| 200 | `WAITING → PREPARING` uygulandı **veya** doğrulanmış tekrar (aynı komut kimliği + kullanıcı + sipariş, sipariş hâlâ `PREPARING`) |
| 400 | `orderId` UUID değil; `Idempotency-Key` yok veya UUID değil |
| 401 / 403 | kimliksiz / `KITCHEN` olmayan rol (ADMIN dahil) |
| 404 | sipariş yok **veya başka restoranın siparişi** (ayırt edilemez) |
| 409 | `PREPARING` ama farklı komut; `READY`/`CANCELLED`; komut kimliği başka bir komuta ait; sipariş bu arada ilerlemiş; eşzamanlı değişiklik/kilit zaman aşımı |

Örnek istek: `PATCH /api/v1/kitchen/orders/6f1c…/start-preparation` + `Idempotency-Key: 0b9d2f71-3a55-4a0e-8f51-2c5e8e3d7a22`.
Örnek 409: `{"status":409,"title":"Conflict","detail":"Order is already PREPARING and this command is not a repeat of the one that did it","currentStatus":"PREPARING"}`.

**Atomiklik:** durum, `started_at`, `version` ve `kitchen_status_history` (`WAITING → PREPARING`, aktör, komut kimliği, zaman) tek transaction'dadır; history yazımı başarısız olursa hepsi geri alınır (testle kanıtlı). Paralel istekler sıraya girer: aynı komutun paralel tekrarları hepsi 200 (tek geçiş uygulanır); farklı komutlardan yalnız biri 200, kalanı 409 alır.
