V1-V10      - AUTH-SERVICE          - KEREM
V11-V20     - MAIL-SERVICE          - KEREM

V21-V30     - QR-SERVICE            - ALPER
V31-V40     - PAYMENT-SERVICE       - ALPER

V41-V50     - ORDER-SERVICE         - HAMZA
V51-V60     - NOTIFICATION-SERVICE  - HAMZA

V61-V70     - MENU-SERVICE          - MERVE
V71-V80     - TIMER-SERVICE         - MERVE 
V81-V90     - KITCHEN-SERVICE       - NISA
V91-V100    - RATING-SERVICE        - NISA
V101-V110   - WAITER-SERVICE        - UMUT
V111-V120   - ADMIN-SERVICE         - UMUT 

V121-V130   - QR-SERVICE (EK ARALIK) - ALPER

31 Ekim 2026: QR/Payment sorumlusu Alper, Hamza'nın K13 sözleşmesine QR uyarlaması için
ek QR aralığını açıkça onayladı. V121 kaynak teyidi/kapsam sapması makbuzuna ayrıldı;
V122-V130 boş. Bu tahsis Order/Waiter onayı veya entegrasyonun tamamlandığı iddiası değildir.
QR branch CREATE birleştirmesinde V23-V25 kaldırıldı; bu tarihsel numaralar yeniden kullanılmayacak.
Güncel QR zinciri: V21,V22,V26-V30,V121. Yerel/paylaşılan geçmiş sınırları ADR-QR-001 §6'dadır.

24 Kasım 2026: Waiter masa bazlı temizleme (`POST /api/v1/internal/tables/{tableId}/cleaned`)
için Alper, V125'i `table_clean_commands` (komut kimliği, istek özeti ve QR yerel
sonucu; idempotency ve durum okuma kaydı) olarak onayladı. V124 `received_at`
göçü için ayrılmış ve boş kalır. Güncel QR zinciri V21,V22,V26–V30,V121–V123,V125.

24 Kasım 2026: QR'dan kendiliğinden başlayan oturum kapanışının Waiter'a dayanıklı
bildirimi için Alper, V126'yı `table_session_closure_events` (değiştirilemez olay
kaydı; `table_sessions.status` CLOSED/EXPIRED olduğunda tetikleyici ile yazılır,
`GET /api/v1/internal/session-closures` ile imleç üzerinden okunur) olarak onayladı.
V124 hâlâ `received_at` için ayrılmıştır. Güncel QR zinciri
V21,V22,V26–V30,V121–V123,V125,V126.

26 Kasım 2026: #51 incelemesindeki olay kaybı bulgusu için Alper, V127'yi
`record_table_session_closure_event` fonksiyonunun restoran bazında işlem kilidiyle
(`pg_advisory_xact_lock`) yeniden tanımı olarak onayladı. Aynı restoranda `event_seq`
sırası commit sırasıyla aynı olur; V126 değiştirilmedi. Güncel QR zinciri
V21,V22,V26–V30,V121–V123,V125–V127.

26 Kasım 2026: #58 incelemesi üzerine Order sepet aktivite makbuzlarının `received_at`
sütunu V124 yerine V128 olarak eklendi. QR'da Flyway `outOfOrder` kapalıdır; V125–V127'yi
uygulamış bir veritabanı sonradan gelen V124'ü reddederdi. V124 hiçbir ortak ortama
uygulanmadan bırakıldı ve yeniden kullanılmayacak. Güncel QR zinciri
V21,V22,V26–V30,V121–V123,V125–V128.

11 Kasım 2026: Order sorumlusu Hamza, Order <-> Kitchen entegrasyon kararını (iptal nedeni
kataloğuna CUSTOMER_REQUEST ve order_cancellations.decided_order_version: kararın sipariş
sürümü, bir kez yazılır) V50 olarak ekledi. dev'de paylaşılmış V47 DEĞİŞTİRİLMEDİ; checksum'ı
korunur ve dev'i çalıştırmış hiçbir Order veritabanının yeniden oluşturulması gerekmez.
Güncel Order zinciri: V41-V50. Order aralığında boş numara kalmadı; yeni bir Order migration'ı
için aralık genişletme kararı gerekir (D-13).

V131        - ORDER-SERVICE (EK SÜRÜM) - HAMZA

18 Kasım 2026: Order için V131 ayrıldı. Bu migration, hazırlık kaydı oluşmadan
önce verilen QR kapanış abort kararını kalıcılaştırır. Order zinciri V41–V50 ve V131.

18 Kasım 2026: QR-INT-02 kapsamında Alper, V123'ü Order `CartActivityRecorded`
olaylarının QR tarafında kalıcı tekilleştirilmesi için onayladı. V122 mevcut Q5
personel iptal korumasıdır. Güncel QR zinciri V21,V22,V26–V30,V121–V123.

V132        - ORDER-SERVICE (EK SÜRÜM) - HAMZA

23 Kasım 2026: V132, WAITER-03 servis edildi komutunun ilk sonucunu kalıcı olarak
saklamak için Order'a ayrıldı. Order zinciri V41–V50 ve V131–V132.

27 Kasım 2026: KITCHEN-INT-03 kapsamında Kitchen V90'ı, Order'ın mutfak onayı bekleyen
iptal isteklerini ve mutfağın cevabını tutan `order_cancellation_confirmations` tablosu için
kullandı. Kitchen zinciri V81–V90; Kitchen aralığında boş numara kalmadı, yeni bir Kitchen
migration'ı için aralık genişletme kararı gerekir.
