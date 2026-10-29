# waiter-service ↔ Auth

Sahip: **Umut**. Dal: `feature/waiter-service`; incelenen SHA: `49d5e3c72597130f7d275b1c59b90eab1816c47d`.
Durum: **EKSİK AuthenticatedStaff üreticisi; gerçek downstream bağlantıları tamamlanmamış**. İnceleme: 29 Ekim 2026; kod/dal incelemesi, canlı entegrasyon testi değildir.

## Mevcut sınır

AuthenticatedStaff(staffUserId, restaurantId, role) ve WAITER sınırı mevcut; staff authentication filtresi yok. QrClient, OrderClient, NotificationClient ve guest doğrulama filtresi var; stub testleri gerçek E2E değildir. Kodda atıf yapılan docs/waiter/handoff-auth.md incelenen dalda yok.

## Sağladığımız ve beklediğimiz

Auth personel kimliğini [staff-v1](../staff-contract.md) ile sağlar. Umut cookie/JWT doğrulamasından AuthenticatedStaff üretmeli. sub → staffUserId, restaurant_id → restaurantId, role → yerel rol. Guest çağrısının QR doğrulaması staff akışından bağımsız kalmalı.

## Client / olay / Saga

Waiter → Order served komutu; Waiter → QR close/cleaned; Notification çağrı olayları. Downstream business sonucu Waiterın ikinci otoritesi olmaz. Auth doğrudan sipariş teslimi veya masa kapanışı yapmaz.

## Yetki ve hata davranışı

Staff uçları mevcut WAITER kuralını korur. Cookie staff mutasyonlarında CSRF + Origin; yalnız başlık credentiallı guest çağrısının ayrı güvenlik gerekçesi korunur.

Personel sözleşmesinde geçersiz kimlik 401, yetersiz rol 403; dependency 503/UNKNOWN başarıya çevrilmez. Kaynak gizleme ve iş çatışması sahibinin mevcut 404/409 sözleşmesine uyar. Yerel JWT doğrulaması anlık global logout garantisi vermez.

## Kabul ve sorumlu

WAITER principalı, diğer roller ve yabancı restoran; guest/staff ayrımı; CSRF; aynı served komutunun tekrarı; close/cleaned version çakışması.

Tüketici adapterı servis sahibinin; Auth üretici sözleşmesi Kerem'in sorumluluğudur. Başka servisin kodu bu teslimde değiştirilmedi.

## Kaynaklar

- [security/AuthenticatedStaff.java](https://github.com/software-lecture-labs/Restofy/blob/49d5e3c72597130f7d275b1c59b90eab1816c47d/backend/services/waiter-service/src/main/java/com/restofy/waiter/infrastructure/security/AuthenticatedStaff.java)
- [security/SecurityConfiguration.java](https://github.com/software-lecture-labs/Restofy/blob/49d5e3c72597130f7d275b1c59b90eab1816c47d/backend/services/waiter-service/src/main/java/com/restofy/waiter/infrastructure/security/SecurityConfiguration.java)

Mevcut test kaynakları (varlıkları PASS kanıtı değildir):

- [WaiterServiceApplicationTests.java](https://github.com/software-lecture-labs/Restofy/blob/49d5e3c72597130f7d275b1c59b90eab1816c47d/backend/services/waiter-service/src/test/java/com/restofy/waiter/WaiterServiceApplicationTests.java)
- [QrCommandContractTests.java](https://github.com/software-lecture-labs/Restofy/blob/49d5e3c72597130f7d275b1c59b90eab1816c47d/backend/services/waiter-service/src/test/java/com/restofy/waiter/api/QrCommandContractTests.java)
- [WaiterApiSecurityTests.java](https://github.com/software-lecture-labs/Restofy/blob/49d5e3c72597130f7d275b1c59b90eab1816c47d/backend/services/waiter-service/src/test/java/com/restofy/waiter/api/WaiterApiSecurityTests.java)
- [RestClientConfigurationTests.java](https://github.com/software-lecture-labs/Restofy/blob/49d5e3c72597130f7d275b1c59b90eab1816c47d/backend/services/waiter-service/src/test/java/com/restofy/waiter/infrastructure/config/RestClientConfigurationTests.java)
