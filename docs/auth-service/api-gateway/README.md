# api-gateway ↔ Auth

Sahip: **Ortak altyapı; Alper koordinasyonu**. Dal: `main`; incelenen SHA: `925be7fcb2b28976afab9f6fe83bde4d41d79288`.
Durum: **MEVCUT yönlendirme; güvenlik entegrasyonu DOĞRULANMADI**. İnceleme: 29 Ekim 2026; kod/dal incelemesi, canlı entegrasyon testi değildir.

## Mevcut sınır

Gateway Web MVC /api/v1/auth/** ve diğer servis rotalarını korur. Kimlik doğrulama, cookie aktarımı veya route varlığı downstream yetkilendirme kanıtı değildir.

## Sağladığımız ve beklediğimiz

Auth personel kimliğini [staff-v1](../staff-contract.md) ile sağlar. Cookie, Set-Cookie, Origin ve X-CSRF-TOKEN başlıklarını doğru iletmek; sahte kimlik headerlarını güven kaynağı yapmamak. Gerçek istemci IP zinciri #10 kapsamında ortak iştir.

## Client / olay / Saga

Tarayıcı → Gateway → Auth veya kaynak servisi. Gateway bir Saga yöneticisi değildir. İç servis komutları public catch-all rotalara açılmamalıdır.

## Yetki ve hata davranışı

Auth public uçlarının kendi CSRF/Origin kuralları korunur; diğer iş uçlarında kaynak servisi kendi rol/tenant/nesne kontrolünü yapar.

Personel sözleşmesinde geçersiz kimlik 401, yetersiz rol 403; dependency 503/UNKNOWN başarıya çevrilmez. Kaynak gizleme ve iş çatışması sahibinin mevcut 404/409 sözleşmesine uyar. Yerel JWT doğrulaması anlık global logout garantisi vermez.

## Kabul ve sorumlu

Gateway üzerinden login/CSRF/cookie aktarımı; yanlış Origin; dışarıdan iç Mail yoluna erişim reddi; sahte X-Role/X-User-Id başlıkları.

Tüketici adapterı servis sahibinin; Auth üretici sözleşmesi Kerem'in sorumluluğudur. Başka servisin kodu bu teslimde değiştirilmedi.

## Kaynaklar

- [resources/application.properties](https://github.com/software-lecture-labs/Restofy/blob/925be7fcb2b28976afab9f6fe83bde4d41d79288/backend/services/api-gateway/src/main/resources/application.properties)

Mevcut test kaynakları (varlıkları PASS kanıtı değildir):

- [ApiGatewayApplicationTests.java](https://github.com/software-lecture-labs/Restofy/blob/925be7fcb2b28976afab9f6fe83bde4d41d79288/backend/services/api-gateway/src/test/java/com/restofy/gateway/ApiGatewayApplicationTests.java)
