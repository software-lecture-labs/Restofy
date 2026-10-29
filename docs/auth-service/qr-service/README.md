# qr-service ↔ Auth

Sahip: **Alper**. Dal: `feature/qr-service`; incelenen SHA: `b95371242e017d130940a3ff803cc95ea9867a1f`.
Durum: **MEVCUT QR-01..04 domain/HTTP sınırları; EKSİK güven adapterları**. İnceleme: 29 Ekim 2026; kod/dal incelemesi, canlı entegrasyon testi değildir.

## Mevcut sınır

28 Ekim QR rehberi güncel kaynaktır. TableManagementAccess, SessionLifecycleAccess, OrderAdmissionAccess, OrderOutcomeAccess, IdleExpiryOrderAccess ve SessionClosureEvidenceAccess kapalı/UNKNOWN/503 sınırlarıdır. Java reserve/confirm/reject ve outcome apply/inspect metotları HTTP endpoint değildir.

## Sağladığımız ve beklediğimiz

Auth personel kimliğini [staff-v1](../staff-contract.md) ile sağlar. Alper personel için yerel Auth JWT doğrulamasını ve WAITER/ADMIN kapanış bağlamını bağlamalı. Order/Payment exact command/fence/kanıt doğrulaması ayrı servis adapterlarıdır. Guest credential taşıması, public QR açılışı ve revoke politikası ayrıca ekip kararıdır; Auth guest token üretmez.

## Client / olay / Saga

Sabit orderId/key/digest → QR reserve → Order kalıcı kararı → confirm/reject. Kayıp ACK ve UNKNOWN ödeme uzlaştırılır; timeout ret kanıtı değildir. Kapanış personel onayı + tam Order/Payment mali kanıtı ister. Auth, bu Saga adımlarının koordinatörü değildir.

## Yetki ve hata davranışı

Yönetim ve kapanış aynı-restoran doğrulanmış kimlik ister. Guest, staff ve service identity ayrılır. Ödeme otomatik masa kapatmaz; CLOSING/CLOSED/CLEANING semantiği QR sahibindedir.

Personel sözleşmesinde geçersiz kimlik 401, yetersiz rol 403; dependency 503/UNKNOWN başarıya çevrilmez. Kaynak gizleme ve iş çatışması sahibinin mevcut 404/409 sözleşmesine uyar. Yerel JWT doğrulaması anlık global logout garantisi vermez.

## Kabul ve sorumlu

Gerçek JWT principalı; guest→staff yükseltme reddi; kapalı güven sınırları; admission/close yarışı; belirsiz mali kanıtta kapanış reddi; QR fragmentinin loga/sitemape gitmemesi.

Tüketici adapterı servis sahibinin; Auth üretici sözleşmesi Kerem'in sorumluluğudur. Başka servisin kodu bu teslimde değiştirilmedi.

## Kaynaklar

- [qr/entegrasyon-rehberi.md](https://github.com/software-lecture-labs/Restofy/blob/b95371242e017d130940a3ff803cc95ea9867a1f/docs/qr/entegrasyon-rehberi.md)
- [security/TableManagementAccess.java](https://github.com/software-lecture-labs/Restofy/blob/b95371242e017d130940a3ff803cc95ea9867a1f/backend/services/qr-service/src/main/java/com/restofy/qr/infrastructure/security/TableManagementAccess.java)
- [security/SessionLifecycleAccess.java](https://github.com/software-lecture-labs/Restofy/blob/b95371242e017d130940a3ff803cc95ea9867a1f/backend/services/qr-service/src/main/java/com/restofy/qr/infrastructure/security/SessionLifecycleAccess.java)

Mevcut test kaynakları (varlıkları PASS kanıtı değildir):

- [QrServiceApplicationTests.java](https://github.com/software-lecture-labs/Restofy/blob/b95371242e017d130940a3ff803cc95ea9867a1f/backend/services/qr-service/src/test/java/com/restofy/qr/QrServiceApplicationTests.java)
- [GuestSessionPostgresIntegrationTests.java](https://github.com/software-lecture-labs/Restofy/blob/b95371242e017d130940a3ff803cc95ea9867a1f/backend/services/qr-service/src/test/java/com/restofy/qr/infrastructure/GuestSessionPostgresIntegrationTests.java)
- [GuestTokenTests.java](https://github.com/software-lecture-labs/Restofy/blob/b95371242e017d130940a3ff803cc95ea9867a1f/backend/services/qr-service/src/test/java/com/restofy/qr/infrastructure/GuestTokenTests.java)
- [IdleSessionPostgresIntegrationTests.java](https://github.com/software-lecture-labs/Restofy/blob/b95371242e017d130940a3ff803cc95ea9867a1f/backend/services/qr-service/src/test/java/com/restofy/qr/infrastructure/IdleSessionPostgresIntegrationTests.java)
- [OrderAdmissionPostgresIntegrationTests.java](https://github.com/software-lecture-labs/Restofy/blob/b95371242e017d130940a3ff803cc95ea9867a1f/backend/services/qr-service/src/test/java/com/restofy/qr/infrastructure/OrderAdmissionPostgresIntegrationTests.java)
