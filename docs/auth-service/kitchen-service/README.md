# kitchen-service ↔ Auth

Sahip: **Nisa**. Dal: `feature/kitchen-service`; incelenen SHA: `3c277496e6b5ff33291bf003e2f2e7daea0273cf`.
Durum: **EKSİK production personel kimliği ve dış teslim adapterları**. İnceleme: 29 Ekim 2026; kod/dal incelemesi, canlı entegrasyon testi değildir.

## Mevcut sınır

KitchenPrincipal(userId, restaurantId) ve KitchenAuthentication mevcut; production kimlik üreticisi yok. GET/HEAD KITCHEN veya ADMIN; yazma yalnız KITCHEN. Order/Notification event sink ve outbox sınırları mevcut, gerçek ağ teslimi tamamlanmış değil.

## Sağladığımız ve beklediğimiz

Auth personel kimliğini [staff-v1](../staff-contract.md) ile sağlar. Nisa yerel JWT doğrulayıp KitchenPrincipal + ROLE_* otoritelerini üretmeli. ADMIN rolü Kitchen yazma yetkisi kazanmaz. Menu/Order/Notification servis adapterları kendi ekip sözleşmelerine göre bağlanmalı.

## Client / olay / Saga

OrderAccepted → Kitchen; KitchenOrderReady → Order ve Notification. İptal isteği Order kararını bekler. Outbox kaydı gerçek teslim değildir; retry/ACK/idempotency kaynak ve tüketici ekiplerince tamamlanır. Auth bu iş olaylarını almaz.

## Yetki ve hata davranışı

WAITING → PREPARING → READY, Order onaylı iptal ve tenant sınırı korunur. Spring CSRF yerine Auth cookie sözleşmesini bağlamak tüketici işidir; koruma kapatılmaz.

Personel sözleşmesinde geçersiz kimlik 401, yetersiz rol 403; dependency 503/UNKNOWN başarıya çevrilmez. Kaynak gizleme ve iş çatışması sahibinin mevcut 404/409 sözleşmesine uyar. Yerel JWT doğrulaması anlık global logout garantisi vermez.

## Kabul ve sorumlu

ADMIN okur/yazamaz; KITCHEN aynı restoranda yazar; yabancı kaynak reddi; CSRF; tekrar hazır olayı; test sink ile production adapter ayrımı.

Tüketici adapterı servis sahibinin; Auth üretici sözleşmesi Kerem'in sorumluluğudur. Başka servisin kodu bu teslimde değiştirilmedi.

## Kaynaklar

- [security/KitchenPrincipal.java](https://github.com/software-lecture-labs/Restofy/blob/3c277496e6b5ff33291bf003e2f2e7daea0273cf/backend/services/kitchen-service/src/main/java/com/restofy/kitchen/infrastructure/security/KitchenPrincipal.java)
- [security/SecurityConfiguration.java](https://github.com/software-lecture-labs/Restofy/blob/3c277496e6b5ff33291bf003e2f2e7daea0273cf/backend/services/kitchen-service/src/main/java/com/restofy/kitchen/infrastructure/security/SecurityConfiguration.java)

Mevcut test kaynakları (varlıkları PASS kanıtı değildir):

- [KitchenServiceApplicationTests.java](https://github.com/software-lecture-labs/Restofy/blob/3c277496e6b5ff33291bf003e2f2e7daea0273cf/backend/services/kitchen-service/src/test/java/com/restofy/kitchen/KitchenServiceApplicationTests.java)
- [KitchenCommandNegativeSecurityTests.java](https://github.com/software-lecture-labs/Restofy/blob/3c277496e6b5ff33291bf003e2f2e7daea0273cf/backend/services/kitchen-service/src/test/java/com/restofy/kitchen/api/controller/KitchenCommandNegativeSecurityTests.java)
- [KitchenHttpSecurityTests.java](https://github.com/software-lecture-labs/Restofy/blob/3c277496e6b5ff33291bf003e2f2e7daea0273cf/backend/services/kitchen-service/src/test/java/com/restofy/kitchen/api/controller/KitchenHttpSecurityTests.java)
- [OutboundMessageContractTests.java](https://github.com/software-lecture-labs/Restofy/blob/3c277496e6b5ff33291bf003e2f2e7daea0273cf/backend/services/kitchen-service/src/test/java/com/restofy/kitchen/application/contract/OutboundMessageContractTests.java)
- [AcceptedOrderIngestServiceIntegrationTests.java](https://github.com/software-lecture-labs/Restofy/blob/3c277496e6b5ff33291bf003e2f2e7daea0273cf/backend/services/kitchen-service/src/test/java/com/restofy/kitchen/application/service/AcceptedOrderIngestServiceIntegrationTests.java)
