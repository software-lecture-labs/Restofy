# notification-service ↔ Auth

Sahip: **Hamza**. Dal: `feature/notification-service`; incelenen SHA: `494b189949ab05d31d2d80aae3202bb61e314c0d`.
Durum: **İSKELET; çalışan business API/adapter yok**. İnceleme: 29 Ekim 2026; kod/dal incelemesi, canlı entegrasyon testi değildir.

## Mevcut sınır

Bildirim uygulaması incelenen dalda iskelet. Rol hedefleme için gelecekte yetkili alıcı çözümleme sözleşmesi gerekir; mevcut Authta personel dizini API yok.

## Sağladığımız ve beklediğimiz

Auth personel kimliğini [staff-v1](../staff-contract.md) ile sağlar. Servis geliştirilirken ortak staff sözleşmesine uyulmalı; yalnız gerçek personel uçları için yerel JWT doğrulaması bağlanmalı. Henüz olmayan business endpoint veya kesin rol matrisi bu belgede icat edilmez.

## Client / olay / Saga

Order/Kitchen/Waiter/QR olay tüketimi; okundu alıcı bazlı, çözüm iş sahibinde. Auth bildirim Saga merkezi değildir.

## Yetki ve hata davranışı

Öneri: deny-by-default, backend rol/restoran/nesne kontrolü; staff cookie mutasyonlarında CSRF + Origin; servis/guest kimliği ayrı.

Personel sözleşmesinde geçersiz kimlik 401, yetersiz rol 403; dependency 503/UNKNOWN başarıya çevrilmez. Kaynak gizleme ve iş çatışması sahibinin mevcut 404/409 sözleşmesine uyar. Yerel JWT doğrulaması anlık global logout garantisi vermez.

## Kabul ve sorumlu

Uygulama geldiğinde geçerli/yanlış rol/tenant, MFA ve service token karışıklığı, eksik CSRF ve downstream kesinti testleri. Bu turda çalışma zamanı DOĞRULANMADI.

Tüketici adapterı servis sahibinin; Auth üretici sözleşmesi Kerem'in sorumluluğudur. Başka servisin kodu bu teslimde değiştirilmedi.

## Kaynaklar

- [resources/application.properties](https://github.com/software-lecture-labs/Restofy/blob/494b189949ab05d31d2d80aae3202bb61e314c0d/backend/services/notification-service/src/main/resources/application.properties)

Mevcut test kaynakları (varlıkları PASS kanıtı değildir):

- [NotificationServiceApplicationTests.java](https://github.com/software-lecture-labs/Restofy/blob/494b189949ab05d31d2d80aae3202bb61e314c0d/backend/services/notification-service/src/test/java/com/restofy/notification/NotificationServiceApplicationTests.java)
