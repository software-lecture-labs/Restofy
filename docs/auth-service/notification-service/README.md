# notification-service ↔ Auth

Sahip: **Hamza**. Dal: `feature/notification-service`; incelenen SHA: `494b189949ab05d31d2d80aae3202bb61e314c0d`.
Durum: **İSKELET; çalışan business API/adapter yok**. İnceleme: 29 Ekim 2026; kod/dal incelemesi, canlı entegrasyon testi değildir.

## Mevcut sınır

Bildirim uygulaması incelenen dalda iskelet. Yerel Auth tesliminde aktif rol bazlı alıcı UUID sorgusu hazırdır:
`GET /api/v1/internal/auth/staff-recipients`. [Kesin JWT, anahtar, DTO ve hata sözleşmesi](../staff-directory-api.md).

## Sağladığımız ve beklediğimiz

Auth personel kimliğini [staff-v1](../staff-contract.md) ile sağlar. Servis geliştirilirken ortak staff sözleşmesine uyulmalı; yalnız gerçek personel uçları için yerel JWT doğrulaması bağlanmalı. Henüz olmayan business endpoint veya kesin rol matrisi bu belgede icat edilmez.

## Client / olay / Saga

Order/Kitchen/Waiter/QR olay tüketimi; okundu alıcı bazlı, çözüm iş sahibinde. Notification→Auth client'ı
kendi RS256 anahtarı ve `Service` JWT'siyle alıcı UUID'lerini okur; staff cookie veya Mail Bearer kullanılmaz.
Auth bildirim Saga merkezi değildir; yalnız aktif hesap/rol bilgisi sağlar, mesai/online politikası sağlamaz.

## Yetki ve hata davranışı

Öneri: deny-by-default, backend rol/restoran/nesne kontrolü; staff cookie mutasyonlarında CSRF + Origin; servis/guest kimliği ayrı.

Personel sözleşmesinde geçersiz kimlik 401, yetersiz rol 403; dependency 503/UNKNOWN başarıya çevrilmez. Kaynak gizleme ve iş çatışması sahibinin mevcut 404/409 sözleşmesine uyar. Yerel JWT doğrulaması anlık global logout garantisi vermez.

## Kabul ve sorumlu

Auth tarafı StaffRecipientHttpTests/NotificationServiceTokensTests ile doğrulandı; gerçek Notification bağlantısı DOĞRULANMADI.
Hamza kendi signer/client'ını ve anahtarını bağlar; Auth'a yalnız public key tanıtılır. Anahtar yokken uç 401 ile kapalıdır.
Boş alıcı sonucu ile Auth kesintisi ayrılır; hasNext sayfalaması ve olay/teslim idempotency'si Notification'dadır.

Tüketici adapterı servis sahibinin; Auth üretici sözleşmesi Kerem'in sorumluluğudur. Başka servisin kodu bu teslimde değiştirilmedi.

## Kaynaklar

- [resources/application.properties](https://github.com/software-lecture-labs/Restofy/blob/494b189949ab05d31d2d80aae3202bb61e314c0d/backend/services/notification-service/src/main/resources/application.properties)

Mevcut test kaynakları (varlıkları PASS kanıtı değildir):

- [NotificationServiceApplicationTests.java](https://github.com/software-lecture-labs/Restofy/blob/494b189949ab05d31d2d80aae3202bb61e314c0d/backend/services/notification-service/src/test/java/com/restofy/notification/NotificationServiceApplicationTests.java)
