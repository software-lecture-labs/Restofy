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

5 Kasım 2026: Order sorumlusu Hamza, Order <-> Kitchen entegrasyon kararı için V47'yi
(V41-V50 aralığı içinde, yeni numara almadan) paylaşıldıktan sonra açık kararıyla güncelledi:
iptal nedeni kataloğuna CUSTOMER_REQUEST ve order_cancellations.decided_order_version
(kararın sipariş sürümü, bir kez yazılır). V47'nin checksum'ı değiştiği için eski V47'yi
uygulamış her Order veritabanı yeniden oluşturulmalıdır. Güncel Order zinciri: V41-V49, V50 boş.
Bu, paylaşılmış migration geçmişini yeniden yazma genel izni değildir.
