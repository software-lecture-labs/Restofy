# REQUEST-01 · Kitchen → Menu availability entegrasyon ihtiyaçları

**24 Kasım 2026 · Merve (Menu) → Nisa (Kitchen) · bilgi: Kerem (Auth, servis kimliği), Alper (ortak Compose/ENV)**

| | |
|---|---|
| **Talep ID** | KITCHEN-REQUEST-01 (Menu'nun Kitchen ile ilk sözleşme talebi) |
| **Talep eden** | Menu Service (Merve) |
| **İlgili servis ve ekip** | Kitchen Service (Nisa). Madde 5 servis kimliği olduğu için Auth (Kerem); ortak ENV/Compose için Alper |
| **Bağlı PDA görevi** | MENU-INT-03 — Kitchen → Menu entegrasyonu |
| **Durum** | **Karar bekliyor.** MENU-INT-03 **KISMİ**: Menu'da komut işleyicisi ve salt okunur kapalı ürün sorgusu ([ADR-MENU-007](../../decisions/ADR-MENU-007-kitchen-kapali-urun-sorgusu.md)) hazır; HTTP ucu **açılmadı**; **modeller birebir uyumlu değil** ve gerçek bağlantı yok |

Bu belge bir taleptir. Kitchen, Auth, Order veya QR kodunda değişiklik içermez. İlk commit'te yalnız belgeydi; MENU-INT-03 ile Menu tarafında kararlardan bağımsız bir okuma katmanı eklendi (bölüm 9). Komut işleyicisi **değişmedi**. Aşağıdaki "Mevcut" bölümleri bu eklemeden **önceki** durumu anlatır (özellikle "Menu'de karşılık yoktur" denen kapalı ürün listesi); "İstenen", "Öneri" ve "Seçenek" başlıkları, bölüm 9'da UYGULANDI denmedikçe, uygulanmamıştır. Bu belge [ADR-MENU-002](../../decisions/ADR-MENU-002-kitchen-availability-komutlari.md)'yi tekrar etmez; onun kararlarını Kitchen'ın **şu anki** kodu ile karşılaştırır ve Kitchen'dan karar ister.

## Dayanak

- Kod tabanı: `origin/dev` (`ca9ab1a`).
- Kitchen: `MenuAvailabilityPort`, `MenuClosedProductsPort`, `MenuIntegrationConfiguration`, `ProductAvailabilityService`, `ProductAvailabilityException`, `MenuAvailabilityCommand`, `ProductAvailabilityState`, `ClosedProduct`/`ClosedProductsPage`/`ClosedProductsQuery`, `ChangeProductAvailabilityRequest`, `KitchenProductController`, `ProductAvailabilityStatus`; migration'lar `V84__create_product_availability_commands.sql`, `V85__make_availability_reason_optional.sql`.
- Menu: `KitchenAvailabilityCommandService`, `KitchenAvailabilityCommand`, `KitchenAvailabilityResult`, `TrustedKitchenCaller`/`TrustedKitchenCallerProvider`, `DenyAllKitchenCallerProvider`, `ProductAvailability`, `AvailabilityStatus`, `CommandOutcome`, `ApiExceptionHandler`, `ProductController`/`ProductFilter`; migration'lar `V63`, `V67`; README "Kitchen availability komutları".
- Karar ve gereksinimler: ADR-MENU-002; `docs/requirements.md` F-KT-03, D-09, D-10; `docs/architecture.md` Menu bölümü ve §5.2.
- MENU-INT-01 (Auth, PR #46) ve MENU-INT-02 (Order, PR #49) `dev`'de değildir; bu talep onlara bağımlı değildir.

## 1. Entegrasyonun amacı

Menu, ürün availability bilgisinin **tek yazarıdır**. Kitchen ürünü "stokta yok" veya "geçici kapalı" yapma ve yeniden açma niyetini Menu'ye komut olarak iletir; Menu'nün onayı olmadan Kitchen başarı göstermez (`docs/architecture.md`). Kitchen ayrıca kapalı ürünler listesini Menu'den okur ve kendi kopyasını tutmaz. Bugün iki yön de bağlı değildir:

- Kitchen `MenuIntegrationConfiguration` varsayılanı her çağrıda `MENU_NOT_INTEGRATED` (sonuç **belirsiz**, `503`) döner.
- Menu'de Kitchen için bir HTTP ucu yoktur; `DenyAllKitchenCallerProvider` hiçbir çağıranı doğrulamaz, her komut `401` alır.

Amaç, iki tarafın birlikte kabul ettiği sürümlü bir komut/okuma sözleşmesi ve güvenli bir servis kimliği tanımlamaktır.

## 2. Mevcut durum (koddan)

### 2.1 Kitchen tarafı

| Konu | Mevcut |
|---|---|
| Giriş | `PATCH /api/v1/kitchen/products/{productId}/availability`; yalnız `KITCHEN` rolü. Gövde: `status`, `reasonCode`, `reasonText`. `Idempotency-Key` zorunlu ve **komut kimliğidir** (UUID). Restoran ve kullanıcı yalnız doğrulanmış principal'dan gelir |
| Durumlar | `AVAILABLE`, `OUT_OF_STOCK`, `TEMPORARILY_CLOSED` (`isClosed()` ikisi için doğru) |
| Neden | **İsteğe bağlı** (`V85`, "KITCHEN-03: ürün sahibinin kararı"): kod `[A-Z][A-Z0-9_]{1,39}` (2–40), metin en çok 500 karakter ve kontrol karakteri yok; boş değer `null` kabul edilir, "nothing is invented" |
| Akış | Kısa transaction'da komut kaydı → **transaction ve kilit dışında** Menu çağrısı → kısa transaction'da cevabın kaydı. Hata, cevap commit edildikten **sonra** fırlatılır |
| Tekrar | Aynı komut kimliği Kitchen'da cevaplanmaz; aynı kimlikle **Menu'ye yeniden sorulur**. Aynı kimlik, farklı niyet (ürün, durum, neden, kullanıcı) yerelde çakışmadır (`COMMAND_CONFLICT`) |
| Port | `MenuAvailabilityPort.change(MenuAvailabilityCommand)`. Komut: `commandId`, `restaurantId`, `productId`, `targetStatus`, `reasonCode`, `reasonText`, `requestedByUserId`. **`expectedVersion` yok** |
| Port sonucu | `APPLIED` (ayrıntılı `ProductAvailabilityState`), `PRODUCT_NOT_FOUND`, `PRODUCT_INACTIVE`, `INVALID_COMMAND`, `COMMAND_CONFLICT`, `VERSION_CONFLICT`, `UNAVAILABLE` (sonuç **belirsiz**) |
| Başarı koşulu (`judge`) | `APPLIED` ancak `state != null`, `state.productId == komut.productId` **ve `state.changedAt != null`** ise kabul. Aksi hâlde `UNAVAILABLE/INCONSISTENT_RESPONSE`. `state.status != hedef` ise `VERSION_CONFLICT/STATE_MISMATCH` |
| Kayıt | `product_availability_commands`: `outcome` `UNCONFIRMED`/`CONFIRMED`/`REJECTED`; `CONFIRMED` ⇔ `confirmed_at IS NOT NULL` (değer Menu'nün `changedAt`'ıdır). Ret kalıcıdır: aynı komut kimliği bir daha uygulanmaz |
| `ProductAvailabilityState` | `productId`, `status`, `reasonCode`, `reasonText`, `changedAt`, `changedByUserId`, `version`, `published` (null = Menu söylemedi) |
| Okuma | `MenuClosedProductsPort.list(restaurantId, statuses, page, size)` → `ClosedProduct` (`productId`, `productName`, `status`, neden, `changedAt`, `changedByUserId`, `version`, `published`) ve `totalElements`; sıra `changedAt DESC, productId ASC`. `GET /api/v1/kitchen/products/closed` |
| Gerçek adaptör | **Yok** (iki port da varsayılan olarak `MENU_NOT_INTEGRATED`) |

### 2.2 Menu tarafı

| Konu | Mevcut |
|---|---|
| İşleyici | `KitchenAvailabilityCommandService.handle(KitchenAvailabilityCommand)`; **HTTP ucu yok**, üretimde çağıran yok (`DenyAllKitchenCallerProvider` → `UnauthenticatedException`) |
| Komut | `commandId`, `productId`, `targetStatus`, `reasonCode`, `reasonText`, isteğe bağlı `expectedVersion`. Restoran ve `onBehalfOfUserId` komutta değil, **doğrulanmış çağırandan** (`TrustedKitchenCaller`) gelir |
| Neden | **Kapalı duruma geçişte kod zorunlu** (entity `requiresReason`; ayrıca DB: `ck_product_availability_closed_reason`, `ck_product_availability_history_closed_reason`). Kod `[A-Z][A-Z0-9_]{0,39}` (1–40); metin en çok 500, satır sonu/sekme serbest, diğer kontrol karakterleri ve çiftsiz surrogate yasak. `AVAILABLE` için neden isteğe bağlı |
| Geçişler | Üç durum arasındaki tüm geçişler serbest. Aynı durum + aynı normalize neden `NO_CHANGE` |
| Sonuç (makbuz) | `KitchenAvailabilityResult(outcome, replayed, status, version)`; `outcome` `APPLIED` veya `NO_CHANGE`. **`productId`, `changedAt`, `changedByUserId`, `published` yok** (ADR-MENU-002 karar 8, bilerek) |
| İdempotency | `product_availability_commands`, kapsam `(restaurant_id, command_id)`; kabul edilen her komut (etkisizler dahil) bir kez yazılır, kalıcıdır, saklama süresi yoktur. Aynı kimlik + aynı içerik: ilk sonuç (`replayed=true`, tekrar uygulanmaz). Aynı kimlik + farklı içerik: `CommandIdConflictException`. İçerik: ürün, kaynak, hedef durum, kod, metin, `expectedVersion`, adına işlem yapılan kullanıcı. Reddedilen komut **kayıt bırakmaz** (sonra aynı kimlik yeniden denenebilir) |
| Sıra | `expectedVersion` verilirse **availability** sürümü eşleşmeli (ürün sürümü değil); eşleşmezse `ProductAvailabilityVersionConflictException`. Ürün başına `PESSIMISTIC_WRITE` kilidi; komutlar kilidi alma sırasına göre uygulanır. Güncelleme, geçmiş ve komut kaydı **tek yerel transaction**'dadır |
| Ürün koşulları | Ürün yalnız çağıranın restoranında aranır; yok ve başka restoranın ürünü **aynıdır** (`ProductNotFoundException`). Pasif veya yayınsız ürün için **ret yoktur**; availability aktiflik ve yayından bağımsızdır |
| Okuma | Kitchen'ın kapalı ürün listesi için **Menu'de karşılık yoktur**. Yönetim listesi (`GET /api/v1/menu/products`) yalnız ADMIN'e açıktır ve `ProductFilter` availability filtresi içermez |
| Hata gösterimi | `ApiExceptionHandler`: `CommandIdConflictException`, sürüm çakışması ve availability-kaydı-yok **aynı** `409 "Conflict"` başlığıyla döner; makine okunur ayırt edici kod yoktur |

## 3. Sözleşme karşılaştırması

| Konu | Kitchen | Menu | Durum |
|---|---|---|---|
| Durum enum'u | 3 değer | Aynı 3 değer | **Uyumlu** |
| Açma/kapama komutu | `AVAILABLE`/kapalı geçişleri | Tüm geçişler serbest | **Uyumlu** |
| `commandId` | `Idempotency-Key`, UUID, ilk deneme ve tekrarda aynı | `(restaurant, commandId)` UUID, kalıcı, tekrar = ilk sonuç | **Uyumlu**; bkz. 4 |
| İdempotency içeriği | ürün, durum, kod, metin, kullanıcı | + `expectedVersion`, kaynak | **Kısmen**: Kitchen `expectedVersion` eklerse içerik eşitliği değişir |
| `expectedVersion` | Komutta ve portta yok | İsteğe bağlı, availability sürümü | **Uyumsuz** (Menu isteğe bağlı olduğu için Kitchen göndermeden de çalışır, ama sıra korumasız) |
| `reasonCode` | **İsteğe bağlı**, 2–40 | Kapatmada **zorunlu**, 1–40; `AVAILABLE`'de isteğe bağlı | **UYUMSUZ (engelleyici).** Nedensiz kapatma isteği Menu'de `400` (`INVALID_COMMAND`) alır ve Kitchen'da kalıcı ret olur |
| `reasonText` | ≤ 500, kontrol karakteri yok | ≤ 500, satır sonu/sekme serbest | **Uyumlu** (Kitchen kümesi Menu'ninkinin altkümesi) |
| Başarı sonucu | `APPLIED` + ayrıntılı durum; `changedAt` zorunlu | `APPLIED`/`NO_CHANGE` + `replayed` + `status` + `version`; `changedAt` yok | **UYUMSUZ (engelleyici).** Mevcut makbuzla her komut `INCONSISTENT_RESPONSE` ve `UNAVAILABLE` olur; `confirmed_at` hiç yazılamaz |
| `NO_CHANGE` ve `replayed` | Ayrım yok (yalnız `APPLIED`) | Ayrı alanlar | **Uyumsuz**: bilgi Kitchen modelinde kayboluyor |
| Pasif ürün | `PRODUCT_INACTIVE` ret öngörür | Ret yok; Menu bunu kural saymıyor (ADR-MENU-002 karar 9) | **Uyumsuz / açık karar** |
| Ürün yok / başka restoran | `PRODUCT_NOT_FOUND` | `ProductNotFoundException`, ikisi aynı | **Uyumlu** |
| Sürüm çakışması | `VERSION_CONFLICT` | `ProductAvailabilityVersionConflictException` | **Uyumlu**, ama Kitchen sürüm göndermiyor (yalnız eşzamanlı yazma yarışı çıkar) |
| Komut kimliği çakışması | `COMMAND_CONFLICT` | `CommandIdConflictException` | **Uyumlu** anlamca; ayrı kod yok (bkz. hata satırı) |
| Availability kaydı yok | Karşılığı yok | `ProductAvailabilityMissingException` (`409`) | **Uyumsuz**: Kitchen'da yer yok; yeni ürünlerde pratikte oluşmaz, eski veriler için backfill `TEMPORARILY_CLOSED` yazdı |
| Belirsiz sonuç | `UNAVAILABLE`, başarı sayılmaz | (adaptör yok) | **Uyumlu ilke**; Menu tarafında zaman aşımı/5xx'te Kitchen'ın davranışı ayrıca yazılmalı |
| Restoran | Komutta `restaurantId` var, principal'dan | Komutta yok; yalnız doğrulanmış çağırandan | **Uyumlu ilke**; çağırandan ne taşınacağı açık (madde 5) |
| Kullanıcı | `requestedByUserId` | `onBehalfOfUserId` (çağırandan); `changed_by_user_id` olarak yazılır | **Açık**: kim imzalıyor (madde 5) |
| Servis kimliği | Gerçek adaptör yok | `DenyAll`, fail-closed | **Açık (D-09)** |
| Kapalı ürün listesi | `MenuClosedProductsPort` bekliyor | **Menu'de uç ve sorgu yok** | **Uyumsuz (eksik)** |

## 4. Gecikmiş ve tekrarlanan komutlar

| Senaryo | Menu bugün | Kitchen bugün | Not |
|---|---|---|---|
| Aynı komut, yanıt kayboldu, aynı kimlikle tekrar | İlk sonuç (`replayed=true`), tekrar uygulanmaz | Menu'ye yeniden sorar | Doğru; ama sonuç **tarihsel**tir, ürün sonra değişmiş olabilir |
| Aynı kimlik, farklı içerik | `CommandIdConflictException` | Yerelde `COMMAND_CONFLICT` | Kimlik kullanıcıyı da kapsar: başka personel aynı kimliği kullanırsa çakışır |
| Sonuç belirsiz (zaman aşımı), kullanıcı **yeni** kimlikle yeni komut verir | İkisi de uygulanabilir, **kilit alma sırasıyla** | İkisi bağımsız komuttur | Belirsiz ilk komut daha sonra uygulanabilir ve **yeniyi ezebilir** |
| Gecikmiş eski komut, `expectedVersion` yok | Yeni durumu **ezer** (veritabanı komutun eski olduğunu bilemez) | `expectedVersion` göndermez | ADR-MENU-002'nin bilinen sınırı |
| ADMIN aynı ürünü Menu'den değiştirir | Availability sürümü artar | Kitchen bunu görmez | Kitchen'ın kapalı listesi anlıktır; olay yok (D-10) |
| Reddedilen komut yeniden gönderilir | Kayıt yok, yeniden değerlendirilir | Ret **kalıcıdır**, bir daha Menu'ye sorulmaz | Menu kurala göre sonradan kabul edebilir; Kitchen etmez |
| Ayrı kimlikle ikinci kapatma (ör. neden değişti) | `APPLIED` (neden farkı değişikliktir) | Bağımsız komut | Beklenen |

Menu'nün saklama süresi yoktur: komut kayıtları silinmez. Kitchen'ın kayıt saklama politikası bu belgede **bilinmiyor**; farklıysa bir taraf aynı kimliği "unuttuğunda" davranış belirsizleşir (açık soru 10).

## 5. Restoran izolasyonu ve servis kimliği (D-09)

- **Menu'nün ilkesi:** Restoran ve kullanıcı hiçbir zaman gövdeden, başlıktan veya parametreden gelmez; yalnız doğrulanmış **servis** kimliğinden. Personelin `KITCHEN` rolü Kitchen **servisini** kanıtlamaz (`TrustedKitchenCaller` belgesi).
- **Bugün:** Menu'nün kapısı kapalıdır; `Kitchen → Menu` için bir imza/doğrulama mekanizması **yoktur**. Kitchen'ın `MenuAvailabilityCommand`'ı `restaurantId` ve `requestedByUserId` taşır; Menu bunları **kimlik olarak kullanmaz**.
- **Örnek:** Order → QR çağrısı RS256 imzalı `Authorization: Service <jwt>` kullanır (`iss`, `aud`, `kid`, `jti`, `restaurant_id`, `method`, `target`, `body_sha256`, 30 sn ömür, `scope`). Menu bu modelin Kitchen için de kopyalanmasını önerebilir ama bu **Auth ve Kitchen kararıdır**.
- **Kullanıcı bilgisi:** Menu `onBehalfOfUserId`'yi `changed_by_user_id` olarak yazar. Servisin bildirdiği kullanıcı bir **beyandır**, yetki kararında kullanılamaz (Order belgesindeki `reported_staff_id` ile aynı ilke). Menu kimliğin yalnız denetim amaçlı olduğunu mu kabul etmeli?
- **Tek restoran / çok restoran:** Kitchen tek restorana mı bağlı, yoksa tüm restoranlar adına mı çağırır? Menu çağıranın restoranını esas alır ve uyuşmayanı reddeder.

Karar verilene kadar Menu'de uç açılmaz; üretim fail-closed kalır.

## 6. İstenen kararlar

Her madde için Menu'nün önerisi verilir; **karar Kitchen/ürün sahibi/Auth ile birlikte verilir**, Menu kendiliğinden seçmez.

1. **Kapatma nedeni (engelleyici).** Kitchen'da neden isteğe bağlıdır (`V85`, ürün sahibi kararı); `docs/requirements.md` F-KT-03 ise "neden/zaman" der. Menu'de kapatmada kod zorunludur (entity + iki DB kısıtı).
   - (a) Menu kapatmada nedeni isteğe bağlı yapar: `product_availability` ve `product_availability_history` kısıtları ve entity değişir (Menu migration'ı; Menu aralığı V61–V70, V68–V70 boş). Geçmişte "nedensiz kapatma" mümkün olur.
   - (b) Kitchen nedeni kapatmada zorunlu yapar (ürün sahibi kararı geri alınır).
   - (c) Kitchen eksik nedeni sabit bir kodla doldurur. Kitchen'ın "uydurma yok" kuralına ve ADR-MENU-002'ye (adaptörde uydurulmaz) **aykırıdır**; Menu önermez.
   Menu önerisi: kararı ürün sahibi versin; (a) ve (b) uygulanabilir, (c) değil.
2. **Başarı makbuzu (engelleyici).** Kitchen `changedAt` ve ürün kimliği ister (`confirmed_at`); Menu makbuzunda yok.
   - (a) Kitchen yalnız `outcome`, `replayed`, `status`, `version` ile çalışır; `changedAt` yerine kendi onay zamanını (veya null) kullanır; `judge()` ve `confirmed_at` kuralı değişir.
   - (b) Menu makbuza `productId`, `changedAt`, `changedByUserId` ekler. Tekrarda **ilk** cevabın tutarlı olması için ilk cevabın kalıcı saklanması gerekir (V67'ye yeni kolon, Menu migration'ı; "V67 yeniden yazılmaz").
   - (c) Kitchen komuttan sonra canlı durumu Menu'den ayrıca okur (madde 6).
3. **`outcome` ve `replayed` Kitchen modeline taşınsın mı?** Kitchen `NO_CHANGE` ve replay'i "uygulandı" diye gömmemeli (ADR-MENU-002). `MenuAvailabilityPort.Result`'a alanlar eklenecek mi?
4. **`expectedVersion` ve sıra.** ADR-MENU-002 seçenekleri: **A** isteğe bağlı sürüm (bugünkü, korumasız); **B** zorunlu sürüm (Kitchen önce sürümü okumalı; kapalı liste `ClosedProduct.version` verir ama `AVAILABLE` ürün için okuma yok); **C** monoton sıra veya tazelik (Kitchen sıra numarası/olay zamanı gönderir; yalnız zaman damgası garanti değildir). Belirsiz bir komutun sonradan uygulanıp yeniyi ezmesi A'da engellenemez.
5. **Pasif/yayınsız ürün.** `PRODUCT_INACTIVE` Kitchen'da bir ret; Menu'de yok. (a) Kitchen bu rejeti kaldırır; (b) ortak yeni bir kural: arşivli ürünün availability'si değişemez (Menu davranış değişikliği, ayrı karar). Menu önerisi (a): availability aktiflik/yayından bağımsızdır.
6. **Kapalı ürün listesi (Menu'de eksik).** Menu'de Kitchen için okuma ucu yok. Bir uç istenirse: kapsam (yalnız kapalı ürünler, sıra `changedAt DESC, productId ASC`), alanlar (`productName`, durum, neden, `changedAt`, `changedByUserId`, availability `version`, `published`), sayfa boyutu (Kitchen 1–100), toplam sayı. `published` anlamı: `is_published` bayrağı mı, yoksa "müşteriye görünür" (`ProductEligibility.isVisibleToCustomer`: aktif ∧ yayında ∧ kategori zinciri aktif) mü?
7. **Hata kodları.** Menu bugün üç ayrı çakışmayı tek `409` ile döner. Ayrık makine okunur kodlar (ör. `COMMAND_CONFLICT`, `VERSION_CONFLICT`, `AVAILABILITY_MISSING`, `PRODUCT_NOT_FOUND`, `INVALID_COMMAND`) kabul mü? `AVAILABILITY_MISSING` Kitchen'da hangi `Kind`'e eşlenir?
8. **Servis kimliği (D-09, Auth ile).** Order → QR modeli mi, Auth'un merkezi çözümü mü? Kitchen → Menu için `aud`, `scope`, ömür, `kid`, istek bağlama (`method`, `target`, `body_sha256`), tek restoran bağlama, ve `on-behalf-of` kullanıcının imzalı claim olarak taşınması. Menu'de ENV ve Compose değişikliği gerekecek (Alper); `docs/SECURITY.md` gereği yeni ENV açık onay ister, bu belge **yeni ENV eklemez**.
9. **Belirsiz sonuç ve yeniden deneme.** Kitchen yeniden denemeyi kim yapar (kullanıcı aynı `Idempotency-Key` ile mi, arka plan işi mi)? Üst sınır, zaman aşımı değerleri (Kitchen'da henüz bir istemci zaman aşımı ayarı yok; Order'da bağlantı 3 sn, okuma 5 sn), Menu'nün komut kayıtlarını ne kadar tutması.
10. **Saklama.** Kitchen komut kayıtları ne kadar saklanıyor? Menu kayıtları süresizdir.
11. **Ret kalıcılığı.** Kitchen reddi kalıcı sayar; Menu reddedilen komutu kaydetmez. Menu sonradan kuralı gevşetirse (ör. madde 1a) Kitchen'daki eski ret ne olacak?
12. **Sürümleme ve sözleşme yeri.** Sözleşme `v1`; alan ekleme geriye uyumlu. Sözleşme belgesi nerede yaşasın (Menu README, ayrı belge)?

## 7. Bu talebin kapsamı dışında

- Menu → Kitchen/Order olay yayını (`ProductAvailabilityChanged`, D-10).
- Order'ın Menu'den fiyat/availability okuması (ayrı talep: `order-request-01.md`, PR #49'da).
- Kitchen sipariş durumları, fire, iptal akışları.
- Menu'de kod veya migration değişikliği, Kitchen'da kod değişikliği.

## 8. Sonraki adım

Kararlar bu belgeye yanıt olarak (PR yorumu veya ayrı yanıt belgesi) geldikten sonra, madde 1 ve 2'ye göre Menu tarafında gerekli değişiklikler (neden kuralı, makbuz, okuma sorgusu, fail-closed servis kimliği kapısı, sözleşme testleri) ayrı bir PR'da ve gerekiyorsa ayrı bir migration ile yapılır. Kitchen'daki gerçek HTTP adaptörü ve model uyarlaması Kitchen ekibinin işidir. Servis kimliği (madde 8) çözülmeden hiçbir iç uç açılmaz.

## 9. MENU-INT-03 uygulama durumu (Menu tarafı)

| Madde | Durum |
|---|---|
| Kitchen komutlarını karşılayan iç işleyici (`KitchenAvailabilityCommandService`): restoran bazlı ürün doğrulaması, kalıcı `commandId` idempotency, isteğe bağlı `expectedVersion`, `APPLIED`/`NO_CHANGE`/`replayed` | **Mevcuttu, değiştirilmedi** (ADR-MENU-002; testleri zaten kapsamlı) |
| Salt okunur kapalı ürün sorgusu (`KitchenClosedProductsService`): çağıranın restoranı, süzgeç, sıra `changedAt DESC, productId ASC`, sayfalama ve eşleşen toplam, tek anlık görüntü | **UYGULANDI** (iç katman; tel biçimi **kararlaştırılmadı**) |
| Listelenen availability `version` değerinin komutun `expectedVersion`ı ile eşleşmesi | **UYGULANDI ve testle doğrulandı** |
| `active` ve `published` ham bayrak olarak; arşivli/yayınsız kapalı ürün listeden çıkarılmaz | **UYGULANDI** (varsayılan, yorumsuz; 6. madde 5 ve 6 kararı bekliyor) |
| Doğrulanmış servis çağıranı kapısı (komutlarla ortak) | **Mevcuttu, fail-closed**: üretimde her komut ve sorgu 401 |
| HTTP ucu (komut ve sorgu) | **UYGULANMADI.** D-09 ve yol/biçim kararları olmadan açılmaz |
| Kapatma nedeninin isteğe bağlı olması, makbuza `changedAt`/`productId`, `NO_CHANGE`/`replayed` değişikliği, zorunlu `expectedVersion`, `PRODUCT_INACTIVE` kuralı | **UYGULANMADI** (6. madde 1–5; Kitchen/ürün sahibi kararı) |
| Servis kimliği doğrulayıcı (Kitchen → Menu), ENV/Compose | **UYGULANMADI** (Auth/Kitchen kararı, 6. madde 8) |
| Gerçek Kitchen → Menu uçtan uca testi | **DOĞRULANMADI** (Kitchen'ın gerçek adaptörü yok) |
