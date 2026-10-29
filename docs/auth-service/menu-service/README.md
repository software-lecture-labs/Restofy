# menu-service ↔ Auth

Sahip: **Merve**. Dal: `feature/menu-service`; incelenen SHA: `c95985839f7f52f8bb0ce49e33da9bcb791bb622`.
Durum: **EKSİK CurrentActorProvider; iç Kitchen kimliği ayrı**. İnceleme: 29 Ekim 2026; kod/dal incelemesi, canlı entegrasyon testi değildir.

## Mevcut sınır

CurrentActorProvider.current → Optional<AuthenticatedActor(userId, restaurantId, role)> mevcut; production sağlayıcı kimliksiz döner. TrustedKitchenCallerProvider ve public restoran scope ayrı sınırlar.

## Sağladığımız ve beklediğimiz

Auth personel kimliğini [staff-v1](../staff-contract.md) ile sağlar. Merve Auth cookie/JWT doğrulamasından AuthenticatedActor üretmeli. Personel ADMIN bağlamı, Kitchen servis kimliği ve public menü scope birbirinin yerine geçmemeli. RatingSummaryPort yokluğu Auth eksikliği değildir.

## Client / olay / Saga

Menu fiyat/ürün uygunluğunun sahibidir; Order kataloğu doğrular; Kitchen uygunluk komutu Menuye gelir; puan özeti Ratingden gelir. Auth fiyat veya Saga endpointi sağlamaz.

## Yetki ve hata davranışı

ADMIN ürün yönetimi ve mevcut servis erişim kuralları korunur. Public menü okumak sipariş veya personel yetkisi vermez; cookie mutasyonları CSRF/Origin denetler.

Personel sözleşmesinde geçersiz kimlik 401, yetersiz rol 403; dependency 503/UNKNOWN başarıya çevrilmez. Kaynak gizleme ve iş çatışması sahibinin mevcut 404/409 sözleşmesine uyar. Yerel JWT doğrulaması anlık global logout garantisi vermez.

## Kabul ve sorumlu

Providerın imzalı kimlik üretmesi; ADMIN/yanlış rol/tenant; kitchen service tokenını personel saymama; public scope ve rating kesintisi ayrımı.

Tüketici adapterı servis sahibinin; Auth üretici sözleşmesi Kerem'in sorumluluğudur. Başka servisin kodu bu teslimde değiştirilmedi.

## Kaynaklar

- [security/CurrentActorProvider.java](https://github.com/software-lecture-labs/Restofy/blob/c95985839f7f52f8bb0ce49e33da9bcb791bb622/backend/services/menu-service/src/main/java/com/restofy/menu/application/security/CurrentActorProvider.java)
- [security/AuthenticatedActor.java](https://github.com/software-lecture-labs/Restofy/blob/c95985839f7f52f8bb0ce49e33da9bcb791bb622/backend/services/menu-service/src/main/java/com/restofy/menu/application/security/AuthenticatedActor.java)

Mevcut test kaynakları (varlıkları PASS kanıtı değildir):

- [MenuServiceApplicationTests.java](https://github.com/software-lecture-labs/Restofy/blob/c95985839f7f52f8bb0ce49e33da9bcb791bb622/backend/services/menu-service/src/test/java/com/restofy/menu/MenuServiceApplicationTests.java)
- [MenuSecurityChainApiTests.java](https://github.com/software-lecture-labs/Restofy/blob/c95985839f7f52f8bb0ce49e33da9bcb791bb622/backend/services/menu-service/src/test/java/com/restofy/menu/api/MenuSecurityChainApiTests.java)
- [ProductSecurityContractApiTests.java](https://github.com/software-lecture-labs/Restofy/blob/c95985839f7f52f8bb0ce49e33da9bcb791bb622/backend/services/menu-service/src/test/java/com/restofy/menu/api/ProductSecurityContractApiTests.java)
- [PublicDtoContractTests.java](https://github.com/software-lecture-labs/Restofy/blob/c95985839f7f52f8bb0ce49e33da9bcb791bb622/backend/services/menu-service/src/test/java/com/restofy/menu/api/dto/response/PublicDtoContractTests.java)
- [CategorySchemaPostgresIntegrationTests.java](https://github.com/software-lecture-labs/Restofy/blob/c95985839f7f52f8bb0ce49e33da9bcb791bb622/backend/services/menu-service/src/test/java/com/restofy/menu/infrastructure/persistence/CategorySchemaPostgresIntegrationTests.java)
