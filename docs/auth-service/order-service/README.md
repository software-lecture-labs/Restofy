# order-service ↔ Auth

Sahip: **Hamza**. Dal: `feature/order-service`; incelenen SHA: `fcd8c3be8bd2abd407b820fe17917a2515d77560`.
Durum: **UYUMSUZ personel taşıması; EKSİK gerçek adapter**. İnceleme: 29 Ekim 2026; kod/dal incelemesi, canlı entegrasyon testi değildir.

## Mevcut sınır

AuthContextPort.resolveStaff ve StaffActor(userId, restaurantId, role) mevcut. StaffAuthenticationFilter Authorization: Staff başlığını ve 512 karakter üst sınırını kullanıyor. Prod adapter 503, dev/test mock.

## Sağladığımız ve beklediğimiz

Auth personel kimliğini [staff-v1](../staff-contract.md) ile sağlar. Hamza cookie + yerel JWT doğrulayıcıyı bağlamalı; StaffActor imzalı sub/restaurant_id/role alanlarından üretilmeli. Eski 512 sınırı gerçek JWT için yetersiz; 4096 üst sınırla hizalanmalı. Auth introspection eklemeyecek.

## Client / olay / Saga

Order → QR admission ve Menu fiyat doğrulaması; Kitchen/Payment olayları Order iç uçlarına gelir. ServiceIdentityPort ayrı servis kimliğidir; personel tokenı servis credentialı değildir. Saga/telafi Order ve ilgili veri sahiplerindedir.

## Yetki ve hata davranışı

StaffAuthentication ve mevcut endpoint otoriteleri korunur; cookie mutasyonları Origin + CSRF gerektirir. QR guest yetkisi ayrı kalır. Auth kesintisi yerel JWT doğrulamasını durdurmaz; refresh/login etkilenir.

Personel sözleşmesinde geçersiz kimlik 401, yetersiz rol 403; dependency 503/UNKNOWN başarıya çevrilmez. Kaynak gizleme ve iş çatışması sahibinin mevcut 404/409 sözleşmesine uyar. Yerel JWT doğrulaması anlık global logout garantisi vermez.

## Kabul ve sorumlu

Gerçek Auth JWT ile StaffActor eşlemesi; >512 karakter geçerli token; yanlış aud/tenant/rol, MFA/guest/service karışıklığı; iptal/ödeme tekrarı; 503 mock sınırı.

Tüketici adapterı servis sahibinin; Auth üretici sözleşmesi Kerem'in sorumluluğudur. Başka servisin kodu bu teslimde değiştirilmedi.

## Kaynaklar

- [port/AuthContextPort.java](https://github.com/software-lecture-labs/Restofy/blob/fcd8c3be8bd2abd407b820fe17917a2515d77560/backend/services/order-service/src/main/java/com/restofy/order/application/port/AuthContextPort.java)
- [port/ServiceIdentityPort.java](https://github.com/software-lecture-labs/Restofy/blob/fcd8c3be8bd2abd407b820fe17917a2515d77560/backend/services/order-service/src/main/java/com/restofy/order/application/port/ServiceIdentityPort.java)
- [security/StaffAuthenticationFilter.java](https://github.com/software-lecture-labs/Restofy/blob/fcd8c3be8bd2abd407b820fe17917a2515d77560/backend/services/order-service/src/main/java/com/restofy/order/infrastructure/security/StaffAuthenticationFilter.java)

Mevcut test kaynakları (varlıkları PASS kanıtı değildir):

- [OrderServiceApplicationTests.java](https://github.com/software-lecture-labs/Restofy/blob/fcd8c3be8bd2abd407b820fe17917a2515d77560/backend/services/order-service/src/test/java/com/restofy/order/OrderServiceApplicationTests.java)
- [HeaderAuthenticationSecurityTests.java](https://github.com/software-lecture-labs/Restofy/blob/fcd8c3be8bd2abd407b820fe17917a2515d77560/backend/services/order-service/src/test/java/com/restofy/order/api/controller/HeaderAuthenticationSecurityTests.java)
- [IntegrationProfileWiringTests.java](https://github.com/software-lecture-labs/Restofy/blob/fcd8c3be8bd2abd407b820fe17917a2515d77560/backend/services/order-service/src/test/java/com/restofy/order/infrastructure/client/IntegrationProfileWiringTests.java)
- [RestClientIntegrationTests.java](https://github.com/software-lecture-labs/Restofy/blob/fcd8c3be8bd2abd407b820fe17917a2515d77560/backend/services/order-service/src/test/java/com/restofy/order/infrastructure/config/RestClientIntegrationTests.java)
- [CartPersistenceIntegrationTests.java](https://github.com/software-lecture-labs/Restofy/blob/fcd8c3be8bd2abd407b820fe17917a2515d77560/backend/services/order-service/src/test/java/com/restofy/order/infrastructure/persistence/CartPersistenceIntegrationTests.java)
